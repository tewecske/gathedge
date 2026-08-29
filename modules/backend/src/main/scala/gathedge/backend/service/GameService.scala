package gathedge.backend.service

import gathedge.backend.db.{GamePlayAnswerRow, GamePlayRow, GameRepository, GameRow, TagRow, UserRow, WordRow}
import gathedge.backend.db.GroupRepository
import gathedge.shared.domain.{
  AnswerOutcome,
  GameMode,
  GameScoring,
  Gender,
  GroupRef,
  LanguageProfile,
  Tag,
  Word,
  WordLanguage,
  WordPreference,
}
import gathedge.shared.dto.{
  AllGamePage,
  AllGameSummary,
  GameAnswerResult,
  GameDetail,
  GamePlayDetail,
  GamePlayPage,
  GamePlaySummary,
  GamePrompt,
  GameResults,
  GameSetupWord,
  GameTagRef,
  GameVariantDto,
  MyPlayPage,
  MyPlaySummary,
  Paging,
  PlayStarted,
}
import gathedge.shared.i18n.MessageRef
import gathedge.shared.validation.Validation
import zio.*

import java.util.concurrent.TimeUnit

enum GameFailure {
  case NotFound
  case NotOwner

  /** One of the requested tag ids carries no marked pair in the requested language direction. */
  case TagNotEligible

  /** A game needs at least one tag to draw words from. */
  case NoTagsSelected

  /** `startPlay`'s pool of eligible words came back empty — normally impossible right after `createGame`, since that
    * already validates every tag id is eligible, but reachable if a tag's pairs were removed between creating the game
    * and starting a play.
    */
  case NoEligibleWords

  case ValidationError(fieldErrors: Map[String, MessageRef])
}

/** Creating, reading and renaming a vocabulary quiz — which tags it may be built from, minting its `slug`/`name` — plus
  * playing one: `startPlay`/`nextPrompt`/`submitAnswer`/`getResults`.
  */
trait GameService {

  /** Tags with a marked pair in the `sourceLanguage` -> `targetLanguage` direction, own tags first. */
  def eligibleTags(sourceLanguage: WordLanguage, targetLanguage: WordLanguage, viewerId: Long): UIO[List[Tag]]

  /** One page of every account's games, most recently created first unless `sort` says otherwise, with their tag names,
    * how many times each was played, how many accounts favorited each, and whether `viewerId` did. `nameContains`
    * narrows to games whose name contains it; `favoritesOnly` keeps only games `viewerId` favorited.
    */
  def allGames(
    viewerId: Long,
    nameContains: Option[String],
    favoritesOnly: Boolean,
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): UIO[AllGamePage]

  /** Marks `slug` as `userId`'s favorite — idempotent. [[GameFailure.NotFound]] if there is no such game. */
  def favoriteGame(slug: String, userId: Long): IO[GameFailure, Unit]

  /** Clears `userId`'s favorite mark on `slug` — idempotent. [[GameFailure.NotFound]] if there is no such game. */
  def unfavoriteGame(slug: String, userId: Long): IO[GameFailure, Unit]

  /** Every game records its plays for its owner to read back via [[listPlays]]/[[getPlayDetail]]. Direction, word
    * count, article display and word preference are no longer part of a game at all — see [[startPlay]].
    */
  def createGame(
    userId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
  ): IO[GameFailure, GameDetail]

  /** The eligible pool a game built from `tagIds`/`sourceLanguage`/`targetLanguage` would draw from, one row per source
    * word with every one of its marked accepted translations attached — a study list, so unlike [[startPlay]]'s own
    * pool this is not deduped to a single translation. What the setup screen previews before the game exists.
    */
  def eligibleWords(
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
  ): UIO[List[GameSetupWord]]

  def getBySlug(slug: String): IO[GameFailure, GameDetail]

  /** Only the owner may rename; anyone else gets [[GameFailure.NotOwner]]. `slug` never changes. */
  def rename(slug: String, newName: String, requesterUserId: Long): IO[GameFailure, GameDetail]

  /** Starts a fresh attempt at `slug` under the given variant. `swapDirection` plays the game's `targetLanguage` ->
    * `sourceLanguage` instead of its stored direction. `wordLimit`/`includeDefiniteArticles`/`wordPreference`/`mode`
    * are this play's own settings, snapshotted onto its `game_plays` row — see the design doc. `mode` also decides the
    * play's `maxScore`, since a clicked answer is worth less than a typed one. Fails [[GameFailure.ValidationError]]
    * for an out-of-range `wordLimit`, [[GameFailure.NoEligibleWords]] if the resolved direction's pool is empty right
    * now.
    */
  def startPlay(
    slug: String,
    playerUserId: Long,
    swapDirection: Boolean = false,
    wordLimit: Option[Int] = None,
    includeDefiniteArticles: Boolean = true,
    wordPreference: WordPreference = WordPreference.All,
    mode: GameMode = GameMode.Typing,
  ): IO[GameFailure, PlayStarted]

  /** The play-variant picker's preview: the resolved-direction eligible pool, in the order [[startPlay]] would sample
    * from for the same `swapDirection`/`wordPreference` — lets the picker show an honest "N eligible" before any play
    * exists. Anonymous-capable, so `playerUserId` is optional: a signed-in caller's own play history still shapes
    * `Unplayed`/`MostMistakes`; an anonymous caller has none, so both degrade to `All`'s order.
    */
  def playSetupPreview(
    slug: String,
    playerUserId: Option[Long],
    swapDirection: Boolean,
    wordPreference: WordPreference,
  ): IO[GameFailure, List[GameSetupWord]]

  /** The next unanswered word in `playId`, or `{finished: true}` once every eligible word has been answered. A
    * [[GameMode.MultipleChoice]] play also gets that word's clickable options. [[GameFailure.NotOwner]] if `playId`
    * does not belong to `requesterUserId`.
    */
  def nextPrompt(playId: Long, requesterUserId: Long): IO[GameFailure, GamePrompt]

  /** Scores `answerText` against `wordId`'s expected translation — looked up server-side, never trusting a
    * client-supplied translation id — and records it. When `wordId` has more than one marked translation in the game's
    * tags, `answerText` is scored against each and the best-scoring one wins. A clicked option arrives here as the
    * option's own text, so the same path grades both modes; only the rule differs (`GameScoring.scoreFor`). Answers
    * with acknowledgement only: never the score or whether it was correct, so a player is never shown correctness
    * mid-game.
    */
  def submitAnswer(playId: Long, wordId: Long, answerText: String, requesterUserId: Long): IO[GameFailure, Unit]

  /** `playId`'s score and full answer history, for the results screen. [[GameFailure.NotOwner]] if it does not belong
    * to `requesterUserId`.
    */
  def getResults(playId: Long, requesterUserId: Long): IO[GameFailure, GameResults]

  /** `playId`'s score and full answer history, for an administrator viewing `playerUserId`'s history. Authorization is
    * the caller's job (the `adminOnly` aspect); this only checks that `playId` really is `playerUserId`'s, answering
    * [[GameFailure.NotFound]] otherwise — an administrator is not "the owner", so there is no [[GameFailure.NotOwner]]
    * here. The admin-scoped counterpart of [[getResults]].
    */
  def resultsForPlayer(playId: Long, playerUserId: Long): IO[GameFailure, GameResults]

  /** One page of `slug`'s plays, for its owner. [[GameFailure.NotOwner]] for anyone else. `playerContains` narrows to
    * players whose address contains it.
    */
  def listPlays(
    slug: String,
    requesterUserId: Long,
    page: Int,
    pageSize: Int,
    playerContains: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): IO[GameFailure, GamePlayPage]

  /** One play's full answer history, for its game's owner — the owner-facing equivalent of [[getResults]]. Same
    * failures as [[listPlays]], plus [[GameFailure.NotFound]] if `playId` does not belong to `slug`.
    */
  def getPlayDetail(slug: String, playId: Long, requesterUserId: Long): IO[GameFailure, GamePlayDetail]

  /** `userId`'s own plays across every game, most recently started first unless `sort` says otherwise. Always the
    * caller's own data. `gameId` narrows to one game; `nameContains` narrows to games whose name contains it.
    */
  def myPlays(
    userId: Long,
    gameId: Option[Long],
    nameContains: Option[String],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): UIO[MyPlayPage]

  /** `targetUserId`'s plays across every game. Authorization (a progress share, or being an administrator) is the
    * caller's job; this never fails on its own. `gameId` narrows to one game; `nameContains` narrows to games whose
    * name contains it.
    */
  def playsOf(
    targetUserId: Long,
    gameId: Option[Long],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
    nameContains: Option[String] = None,
  ): UIO[MyPlayPage]
}

object GameService {

  def eligibleTags(
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    viewerId: Long,
  ): URIO[GameService, List[Tag]] =
    ZIO.serviceWithZIO[GameService](_.eligibleTags(sourceLanguage, targetLanguage, viewerId))

  def allGames(
    viewerId: Long,
    nameContains: Option[String],
    favoritesOnly: Boolean,
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): URIO[GameService, AllGamePage] = {
    ZIO.serviceWithZIO[GameService](
      _.allGames(viewerId, nameContains, favoritesOnly, page, pageSize, sort, descending)
    )
  }

  def favoriteGame(slug: String, userId: Long): ZIO[GameService, GameFailure, Unit] =
    ZIO.serviceWithZIO[GameService](_.favoriteGame(slug, userId))

  def unfavoriteGame(slug: String, userId: Long): ZIO[GameService, GameFailure, Unit] =
    ZIO.serviceWithZIO[GameService](_.unfavoriteGame(slug, userId))

  def createGame(
    userId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
  ): ZIO[GameService, GameFailure, GameDetail] = {
    ZIO.serviceWithZIO[GameService](_.createGame(userId, sourceLanguage, targetLanguage, tagIds))
  }

  def eligibleWords(
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
  ): URIO[GameService, List[GameSetupWord]] =
    ZIO.serviceWithZIO[GameService](_.eligibleWords(sourceLanguage, targetLanguage, tagIds))

  def getBySlug(slug: String): ZIO[GameService, GameFailure, GameDetail] =
    ZIO.serviceWithZIO[GameService](_.getBySlug(slug))

  def rename(slug: String, newName: String, requesterUserId: Long): ZIO[GameService, GameFailure, GameDetail] =
    ZIO.serviceWithZIO[GameService](_.rename(slug, newName, requesterUserId))

  def startPlay(
    slug: String,
    playerUserId: Long,
    swapDirection: Boolean = false,
    wordLimit: Option[Int] = None,
    includeDefiniteArticles: Boolean = true,
    wordPreference: WordPreference = WordPreference.All,
    mode: GameMode = GameMode.Typing,
  ): ZIO[GameService, GameFailure, PlayStarted] = {
    ZIO.serviceWithZIO[GameService](
      _.startPlay(slug, playerUserId, swapDirection, wordLimit, includeDefiniteArticles, wordPreference, mode)
    )
  }

  def playSetupPreview(
    slug: String,
    playerUserId: Option[Long],
    swapDirection: Boolean,
    wordPreference: WordPreference,
  ): ZIO[GameService, GameFailure, List[GameSetupWord]] = {
    ZIO.serviceWithZIO[GameService](_.playSetupPreview(slug, playerUserId, swapDirection, wordPreference))
  }

  def nextPrompt(playId: Long, requesterUserId: Long): ZIO[GameService, GameFailure, GamePrompt] =
    ZIO.serviceWithZIO[GameService](_.nextPrompt(playId, requesterUserId))

  def submitAnswer(
    playId: Long,
    wordId: Long,
    answerText: String,
    requesterUserId: Long,
  ): ZIO[GameService, GameFailure, Unit] =
    ZIO.serviceWithZIO[GameService](_.submitAnswer(playId, wordId, answerText, requesterUserId))

  def getResults(playId: Long, requesterUserId: Long): ZIO[GameService, GameFailure, GameResults] =
    ZIO.serviceWithZIO[GameService](_.getResults(playId, requesterUserId))

  def resultsForPlayer(playId: Long, playerUserId: Long): ZIO[GameService, GameFailure, GameResults] =
    ZIO.serviceWithZIO[GameService](_.resultsForPlayer(playId, playerUserId))

  def listPlays(
    slug: String,
    requesterUserId: Long,
    page: Int,
    pageSize: Int,
    playerContains: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): ZIO[GameService, GameFailure, GamePlayPage] = {
    ZIO.serviceWithZIO[GameService](
      _.listPlays(slug, requesterUserId, page, pageSize, playerContains, sort, descending)
    )
  }

  def getPlayDetail(slug: String, playId: Long, requesterUserId: Long): ZIO[GameService, GameFailure, GamePlayDetail] =
    ZIO.serviceWithZIO[GameService](_.getPlayDetail(slug, playId, requesterUserId))

  def myPlays(
    userId: Long,
    gameId: Option[Long],
    nameContains: Option[String],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): URIO[GameService, MyPlayPage] =
    ZIO.serviceWithZIO[GameService](_.myPlays(userId, gameId, nameContains, page, pageSize, sort, descending))

  def playsOf(
    targetUserId: Long,
    gameId: Option[Long],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
    nameContains: Option[String] = None,
  ): URIO[GameService, MyPlayPage] =
    ZIO.serviceWithZIO[GameService](_.playsOf(targetUserId, gameId, page, pageSize, sort, descending, nameContains))

  val live: URLayer[GameRepository & GameWordList & GroupRepository, GameService] = {
    ZLayer.fromFunction((repo: GameRepository, words: GameWordList, groupRepo: GroupRepository) =>
      GameServiceLive(repo, words, groupRepo)
    )
  }

  /** Fresh random `(adjective, noun)` pairs are tried this many times before falling back to a numeric suffix on the
    * last pair tried.
    */
  val randomAttempts = 5

  /** Total attempts (random plus suffixed) before giving up. With `GameWordList.live`'s ~80x80 words this is
    * unreachable in practice; it exists only so slug generation is a total function.
    */
  val maxAttempts = 30
}

final case class GameServiceLive(repo: GameRepository, wordList: GameWordList, groupRepo: GroupRepository)
    extends GameService {

  private def toTag(row: TagRow, wordCount: Long, viewerId: Long, group: Option[GroupRef] = None): Tag = {
    Tag(row.id, row.name, wordCount, row.userId == viewerId, group)
  }

  /** Batched form of `WordServiceLive.resolveGroupRefs`, copied in shape: one query for every tag row's `groupId`
    * rather than one per row.
    */
  private def resolveGroupRefs(rows: List[TagRow]): UIO[Map[Long, GroupRef]] = {
    val ids = rows.flatMap(_.groupId).distinct
    if (ids.isEmpty)
      ZIO.succeed(Map.empty)
    else
      groupRepo.findGroupsByIds(ids).orDie.map(_.map(g => g.id -> GroupRef(g.id, g.name)).toMap)
  }

  /** `Word.displayText`, gated by a game's own `includeDefiniteArticles` — the choke point [[nextPrompt]],
    * [[submitAnswer]] and [[answerResultsOf]] all go through, so a game that turned the article off never sees it in a
    * prompt, a scored answer, or a results row.
    */
  private def wordText(row: WordRow, includeDefiniteArticles: Boolean): String = {
    if (includeDefiniteArticles) Word.displayText(row.language, row.text, row.gender) else row.text
  }

  def eligibleTags(sourceLanguage: WordLanguage, targetLanguage: WordLanguage, viewerId: Long): UIO[List[Tag]] = {
    repo
      .eligibleTags(WordLanguage.code(sourceLanguage), WordLanguage.code(targetLanguage))
      .orDie
      .flatMap { rows =>
        // One row per (tag, eligible source word) — see the repo method's doc comment on why dedup is left to the
        // caller. Counting distinct word ids per tag is what turns that into "how many words this tag would draw
        // prompts from", the number the setup screen's checkbox shows next to each tag's name.
        val byTag = rows.groupBy(_._1).view.mapValues(_.map(_._2).distinct.size.toLong).toMap
        resolveGroupRefs(byTag.keys.toList).map { groupRefs =>
          Tag.sorted(byTag.map { case (row, wordCount) =>
            toTag(row, wordCount, viewerId, row.groupId.flatMap(groupRefs.get))
          }.toList)
        }
      }
  }

  def allGames(
    viewerId: Long,
    nameContains: Option[String],
    favoritesOnly: Boolean,
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): UIO[AllGamePage] = {
    val favoritesOf = Option.when(favoritesOnly)(viewerId)
    for {
      rows          <- repo
                         .listAllGamesPage(nameContains, favoritesOf, Paging.offset(page, pageSize), pageSize, sort, descending)
                         .orDie
      total         <- repo.countAllGamesMatching(nameContains, favoritesOf).orDie
      gameIds        = rows.map(_.id)
      tagsByGame    <- ZIO
                         .foreach(rows)(row => repo.tagsOf(row.id).orDie.map(tags => row.id -> tagRefs(tags)))
                         .map(_.toMap)
      playCounts    <- repo.playCounts(gameIds).orDie
      likeCounts    <- repo.favoriteCounts(gameIds).orDie
      favoritedMine <- repo.favoritedGameIds(viewerId, gameIds).orDie
    } yield AllGamePage(
      rows.map { row =>
        AllGameSummary(
          slug = row.slug,
          name = row.name,
          sourceLanguage = WordLanguage.fromString(row.sourceLanguage).getOrElse(WordLanguage.En),
          targetLanguage = WordLanguage.fromString(row.targetLanguage).getOrElse(WordLanguage.En),
          tags = tagsByGame.getOrElse(row.id, Nil),
          playCount = playCounts.getOrElse(row.id, 0L),
          likeCount = likeCounts.getOrElse(row.id, 0L),
          favoritedByMe = favoritedMine.contains(row.id),
          createdAt = row.createdAt,
        )
      },
      total,
    )
  }

  def favoriteGame(slug: String, userId: Long): IO[GameFailure, Unit] = {
    for {
      game <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      now  <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _    <- repo.addFavorite(userId, game.id, now).orDie
    } yield ()
  }

  def unfavoriteGame(slug: String, userId: Long): IO[GameFailure, Unit] = {
    for {
      game <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      _    <- repo.removeFavorite(userId, game.id).orDie
    } yield ()
  }

  private def capitalize(word: String): String = {
    if (word.isEmpty) word else word.charAt(0).toUpper +: word.substring(1)
  }

  /** One `(slug, name)` candidate. `attempt` is 0-based: the first [[GameService.randomAttempts]] draw a fresh random
    * pair each time; every attempt after that reuses the last pair drawn and appends an increasing numeric suffix — the
    * "falling back to a numeric suffix" the feature calls for.
    */
  private def candidate(attempt: Int, lastPair: Option[(String, String)]): UIO[((String, String), String, String)] = {
    if (attempt < GameService.randomAttempts || lastPair.isEmpty) {
      for {
        adjIndex  <- Random.nextIntBounded(wordList.adjectives.size)
        nounIndex <- Random.nextIntBounded(wordList.nouns.size)
        adjective  = wordList.adjectives(adjIndex)
        noun       = wordList.nouns(nounIndex)
      } yield ((adjective, noun), s"$adjective-$noun", s"${capitalize(adjective)} ${capitalize(noun)}")
    } else {
      val (adjective, noun) = lastPair.get
      val suffix            = attempt - GameService.randomAttempts + 2
      ZIO.succeed(((adjective, noun), s"$adjective-$noun-$suffix", s"${capitalize(adjective)} ${capitalize(noun)}"))
    }
  }

  private def insertWithRetry(
    userId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
    now: Long,
    attempt: Int,
    lastPair: Option[(String, String)],
  ): UIO[GameRow] = {
    if (attempt >= GameService.maxAttempts)
      ZIO.die(new RuntimeException("Exhausted every attempt to generate a unique game slug"))
    else {
      candidate(attempt, lastPair).flatMap { case (pair, slug, name) =>
        val row = GameRow(
          id = 0L,
          ownerUserId = userId,
          slug = slug,
          name = name,
          sourceLanguage = WordLanguage.code(sourceLanguage),
          targetLanguage = WordLanguage.code(targetLanguage),
          createdAt = now,
          updatedAt = now,
        )
        repo.insertGame(row, tagIds).catchAll { error =>
          repo.findBySlug(slug).orDie.flatMap {
            case Some(_) =>
              insertWithRetry(
                userId,
                sourceLanguage,
                targetLanguage,
                tagIds,
                now,
                attempt + 1,
                Some(pair),
              )
            case None    =>
              ZIO.die(error)
          }
        }
      }
    }
  }

  def createGame(
    userId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
  ): IO[GameFailure, GameDetail] = {
    for {
      _          <- ZIO.when(tagIds.isEmpty)(ZIO.fail(GameFailure.NoTagsSelected))
      eligible   <- repo.eligibleTags(WordLanguage.code(sourceLanguage), WordLanguage.code(targetLanguage)).orDie
      eligibleIds = eligible.map(_._1.id).toSet
      _          <- ZIO.unless(tagIds.forall(eligibleIds.contains))(ZIO.fail(GameFailure.TagNotEligible))
      now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
      row        <-
        insertWithRetry(userId, sourceLanguage, targetLanguage, tagIds, now, attempt = 0, lastPair = None)
      tags       <- repo.tagsOf(row.id).orDie
    } yield GameDetail(row.slug, row.name, sourceLanguage, targetLanguage, tagRefs(tags))
  }

  /** The setup screen's preview of the pool a game built from `tagIds` would draw from, one row per source word with
    * every one of its marked accepted translations attached — unlike [[eligibleWordPool]]/[[dedupeToOnePerWord]], not
    * deduped to a single translation, since this is a study list rather than a draw pool. Reads raw pairs straight from
    * [[GameRepository.eligibleWordPairsForTags]] instead of a game's own `game_tags`, since no game exists yet.
    */
  def eligibleWords(
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
  ): UIO[List[GameSetupWord]] = {
    for {
      pairs       <- repo
                       .eligibleWordPairsForTags(tagIds, WordLanguage.code(sourceLanguage), WordLanguage.code(targetLanguage))
                       .orDie
      wordIds      = (pairs.map(_._1) ++ pairs.map(_._2)).distinct
      words       <- repo.wordsByIds(wordIds).orDie
      textById     = words.map(w => w.id -> Word.displayText(w.language, w.text, w.gender)).toMap
      translations = pairs.groupBy(_._1).view.mapValues(_.map(_._2).distinct.flatMap(textById.get).sorted).toMap
    } yield translations.toList
      .flatMap { case (wordId, texts) => textById.get(wordId).map(text => GameSetupWord(wordId, text, texts)) }
      .sortBy(_.text)
  }

  /** A game's tag rows as the wire carries them: id plus name, ordered by name so a listing renders them the same way
    * every time. The id lets the frontend link each name to the tag's own page.
    */
  private def tagRefs(rows: List[TagRow]): List[GameTagRef] =
    rows.map(row => GameTagRef(row.id, row.name)).sortBy(_.name)

  private def detailOf(row: GameRow): UIO[GameDetail] = {
    repo.tagsOf(row.id).orDie.map { tags =>
      GameDetail(
        row.slug,
        row.name,
        WordLanguage.fromString(row.sourceLanguage).getOrElse(WordLanguage.En),
        WordLanguage.fromString(row.targetLanguage).getOrElse(WordLanguage.En),
        tagRefs(tags),
      )
    }
  }

  def getBySlug(slug: String): IO[GameFailure, GameDetail] = {
    for {
      row    <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      detail <- detailOf(row)
    } yield detail
  }

  /** Loads `slug` and checks it belongs to `requesterUserId` — the ownership check every owner-only game action needs,
    * shared by [[rename]], [[listPlays]] and [[getPlayDetail]]. Games reveal their existence to non-owners (a shared
    * link must be viewable by anyone), so this fails [[GameFailure.NotOwner]] rather than [[GameFailure.NotFound]] for
    * somebody else's game — the same 403, not 404, choice [[rename]] already made before this was extracted.
    */
  private def requireOwnGame(slug: String, requesterUserId: Long): IO[GameFailure, GameRow] = {
    for {
      row <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      _   <- ZIO.unless(row.ownerUserId == requesterUserId)(ZIO.fail(GameFailure.NotOwner))
    } yield row
  }

  def rename(slug: String, newName: String, requesterUserId: Long): IO[GameFailure, GameDetail] = {
    for {
      row    <- requireOwnGame(slug, requesterUserId)
      valid  <- ZIO
                  .fromEither(Validation.validateGameName(newName))
                  .mapError(error => GameFailure.ValidationError(Map("name" -> error)))
      now    <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _      <- repo.rename(row.id, valid, now).orDie
      detail <- detailOf(row.copy(name = valid))
    } yield detail
  }

  /** Dedupes raw `(word_id, translation_word_id)` pairs to one row per source word — the lowest translation id on a tie
    * — per [[GameRepository.eligibleWordPairs]]'s doc comment on why dedup is a business rule and not the query's job.
    * Shared by [[eligibleWordPool]] (a game's own tags) and [[eligibleWordPoolForTags]] (an explicit tag list).
    */
  private def dedupeToOnePerWord(pairs: List[(Long, Long)]): List[(Long, Long)] = {
    pairs.groupBy(_._1).view.mapValues(_.map(_._2).min).toList
  }

  /** Dedupes `(word_id, translation_word_id)` pairs so no two source words share a `translation_word_id` — the lowest
    * `word_id` on a tie. Two different source words that translate to the same target word are ambiguous to grade
    * against each other, so [[sampleWordPool]] applies this only when it is about to draw a *limited* subset — an
    * unrestricted ("all words") play keeps every source word, collisions included, per TODO.txt's "unless all words is
    * selected" rule.
    */
  private def dedupeSameTarget(pairs: List[(Long, Long)]): List[(Long, Long)] = {
    pairs.groupBy(_._2).view.mapValues(_.map(_._1).min).toList.map { case (translationId, wordId) =>
      (wordId, translationId)
    }
  }

  /** `(word_id, translation_word_id)` pairs eligible for `gameId` in the `sourceLanguage` -> `targetLanguage`
    * direction, deduped to one row per source word. Takes explicit codes rather than a `GameRow` because a play may
    * resolve to the reverse of the game's own stored direction — see [[GameServiceLive.startPlay]].
    */
  private def eligibleWordPoolFor(
    gameId: Long,
    sourceLanguage: String,
    targetLanguage: String,
  ): UIO[List[(Long, Long)]] = {
    repo.eligibleWordPairs(gameId, sourceLanguage, targetLanguage).orDie.map(dedupeToOnePerWord)
  }

  /** This player's per-word answer history for `gameId` in the `sourceLanguage` -> `targetLanguage` direction — total
    * answers and how many were not [[AnswerOutcome.Correct]] — the ordering signal for
    * [[WordPreference.Unplayed]]/[[WordPreference.MostMistakes]]. A word absent from the map has never been answered by
    * this player in this direction — see [[GameRepository.answerOutcomesFor]].
    *
    * `playerUserId` is optional because [[playSetupPreview]] is anonymous-capable: an anonymous caller has no play
    * history to look up, so this answers an empty map without touching the repository — every word then ties at "0
    * total, 0 mistakes", which degrades `Unplayed`/`MostMistakes` to the same order as `All`.
    */
  private def wordStats(
    gameId: Long,
    playerUserId: Option[Long],
    sourceLanguage: String,
    targetLanguage: String,
  ): UIO[Map[Long, (Int, Int)]] = {
    playerUserId match {
      case None      =>
        ZIO.succeed(Map.empty)
      case Some(uid) =>
        repo.answerOutcomesFor(gameId, uid, sourceLanguage, targetLanguage).orDie.map { rows =>
          rows
            .groupBy(_._1)
            .view
            .mapValues { outcomes =>
              val total    = outcomes.size
              val mistakes = outcomes.count(_._2 != AnswerOutcome.code(AnswerOutcome.Correct))
              (total, mistakes)
            }
            .toMap
        }
    }
  }

  /** `pool` reordered so `preference`'s preferred subset comes first — see the design doc's "priority sampling, not a
    * hard filter" rule. Shuffled first in every case, so ties (including "no history at all", which every word shares
    * under [[WordPreference.All]]) are broken randomly rather than by pool order, and `.sortBy` is stable, so that
    * shuffle survives within each tie group. Feeds [[sampleWordPool]]'s actual draw for play — [[playSetupPreview]]
    * uses [[preferenceOrderedStable]] instead, since its list is a study aid the player rereads across renders and
    * should not reshuffle underneath them.
    */
  private def preferenceOrdered(
    pool: List[(Long, Long)],
    stats: Map[Long, (Int, Int)],
    preference: WordPreference,
  ): UIO[List[(Long, Long)]] = {
    Random.shuffle(pool).map { shuffled =>
      preference match {
        case WordPreference.All          =>
          shuffled
        case WordPreference.Unplayed     =>
          shuffled.sortBy(pair => if (stats.contains(pair._1)) 1 else 0)
        case WordPreference.MostMistakes =>
          shuffled.sortBy(pair => -stats.get(pair._1).map(_._2).getOrElse(0))
      }
    }
  }

  /** [[preferenceOrdered]] without the shuffle: `pool` reordered so `preference`'s preferred subset comes first, ties
    * kept in `pool`'s own order. [[playSetupPreview]] passes a pool already sorted by display text (matching
    * [[eligibleWords]]'s setup-screen preview), so a tie falls back to alphabetical order rather than randomizing the
    * study list on every request.
    */
  private def preferenceOrderedStable(
    pool: List[(Long, Long)],
    stats: Map[Long, (Int, Int)],
    preference: WordPreference,
  ): List[(Long, Long)] = {
    preference match {
      case WordPreference.All          =>
        pool
      case WordPreference.Unplayed     =>
        pool.sortBy(pair => if (stats.contains(pair._1)) 1 else 0)
      case WordPreference.MostMistakes =>
        pool.sortBy(pair => -stats.get(pair._1).map(_._2).getOrElse(0))
    }
  }

  /** `pool` itself when `limit` is absent or no smaller than the pool. Otherwise [[dedupeSameTarget]]'s collision-free
    * subset, `limit`'s first [[preferenceOrdered]] words of it — for [[WordPreference.Unplayed]]/
    * [[WordPreference.MostMistakes]] this is the "fill from the preferred subset, then top up from the rest" rule the
    * design doc describes; for [[WordPreference.All]] it is a uniform random sample, exactly as before this feature
    * existed. `pool` itself may still be smaller than `n` after deduping — `.take` just yields fewer words then.
    */
  private def sampleWordPool(
    gameId: Long,
    playerUserId: Long,
    sourceLanguage: String,
    targetLanguage: String,
    pool: List[(Long, Long)],
    limit: Option[Int],
    preference: WordPreference,
  ): UIO[List[(Long, Long)]] = {
    limit match {
      case Some(n) if n < pool.size =>
        for {
          stats   <- wordStats(gameId, Some(playerUserId), sourceLanguage, targetLanguage)
          ordered <- preferenceOrdered(dedupeSameTarget(pool), stats, preference)
        } yield ordered.take(n)
      case _                        =>
        ZIO.succeed(pool)
    }
  }

  /** Loads `playId` and checks it belongs to `requesterUserId` — the ownership check every play-id endpoint needs,
    * mirroring [[rename]]'s check for a game's slug.
    */
  private def requireOwnedPlay(playId: Long, requesterUserId: Long): IO[GameFailure, GamePlayRow] = {
    for {
      play <- repo.findPlay(playId).orDie.someOrFail(GameFailure.NotFound)
      _    <- ZIO.unless(play.playerUserId == requesterUserId)(ZIO.fail(GameFailure.NotOwner))
    } yield play
  }

  def startPlay(
    slug: String,
    playerUserId: Long,
    swapDirection: Boolean = false,
    wordLimit: Option[Int] = None,
    includeDefiniteArticles: Boolean = true,
    wordPreference: WordPreference = WordPreference.All,
    mode: GameMode = GameMode.Typing,
  ): IO[GameFailure, PlayStarted] = {
    for {
      game                            <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      validLimit                      <- ZIO
                                           .foreach(wordLimit)(limit => ZIO.fromEither(Validation.validateWordLimit(limit)))
                                           .mapError(error => GameFailure.ValidationError(Map("wordLimit" -> error)))
      resolved                         =
        if (swapDirection) (game.targetLanguage, game.sourceLanguage) else (game.sourceLanguage, game.targetLanguage)
      (resolvedSource, resolvedTarget) = resolved
      pool                            <- eligibleWordPoolFor(game.id, resolvedSource, resolvedTarget)
      _                               <- ZIO.when(pool.isEmpty)(ZIO.fail(GameFailure.NoEligibleWords))
      sampled                         <- sampleWordPool(game.id, playerUserId, resolvedSource, resolvedTarget, pool, validLimit, wordPreference)
      now                             <- Clock.currentTime(TimeUnit.MILLISECONDS)
      wordCount                        = sampled.size
      maxScore                         = wordCount * GameScoring.pointsPerWord(mode)
      row                             <- repo
                                           .insertPlay(
                                             GamePlayRow(
                                               id = 0L,
                                               gameId = game.id,
                                               playerUserId = playerUserId,
                                               score = 0,
                                               maxScore = maxScore,
                                               wordCount = wordCount,
                                               startedAt = now,
                                               finishedAt = None,
                                               sourceLanguage = resolvedSource,
                                               targetLanguage = resolvedTarget,
                                               wordLimit = validLimit,
                                               includeDefiniteArticles = includeDefiniteArticles,
                                               wordPreference = WordPreference.code(wordPreference),
                                               mode = GameMode.code(mode),
                                             ),
                                             sampled,
                                           )
                                           .orDie
    } yield PlayStarted(row.id, wordCount, maxScore)
  }

  /** Unlike [[eligibleWordPoolFor]]'s draw pool (deduped to one translation per source word, for unambiguous grading),
    * the preview shown here carries every one of a word's marked accepted translations — the same "study list"
    * treatment [[eligibleWords]] gives the setup screen, one screen over. `ordered` still drives the word selection and
    * its [[WordPreference]] order; only the displayed translations are widened back out to the raw pairs.
    */
  def playSetupPreview(
    slug: String,
    playerUserId: Option[Long],
    swapDirection: Boolean,
    wordPreference: WordPreference,
  ): IO[GameFailure, List[GameSetupWord]] = {
    for {
      game                            <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      resolved                         =
        if (swapDirection) (game.targetLanguage, game.sourceLanguage) else (game.sourceLanguage, game.targetLanguage)
      (resolvedSource, resolvedTarget) = resolved
      rawPairs                        <- repo.eligibleWordPairs(game.id, resolvedSource, resolvedTarget).orDie
      pool                             = dedupeToOnePerWord(rawPairs)
      stats                           <- wordStats(game.id, playerUserId, resolvedSource, resolvedTarget)
      words                           <- repo.wordsByIds((rawPairs.map(_._1) ++ rawPairs.map(_._2)).distinct).orDie
      textById                         = words.map(w => w.id -> Word.displayText(w.language, w.text, w.gender)).toMap
      sortedPool                       = pool.sortBy(pair => textById.getOrElse(pair._1, ""))
      ordered                          = preferenceOrderedStable(sortedPool, stats, wordPreference)
      translationsById                 =
        rawPairs.groupBy(_._1).view.mapValues(_.map(_._2).distinct.flatMap(textById.get).sorted).toMap
    } yield ordered.flatMap { case (wordId, _) =>
      textById.get(wordId).map(text => GameSetupWord(wordId, text, translationsById.getOrElse(wordId, Nil)))
    }
  }

  def nextPrompt(playId: Long, requesterUserId: Long): IO[GameFailure, GamePrompt] = {
    for {
      play       <- requireOwnedPlay(playId, requesterUserId)
      pool       <- repo.wordPairsOf(playId).orDie
      answered   <- repo.answersOf(playId).orDie
      answeredIds = answered.map(_.wordId).toSet
      remaining   = pool.filterNot(pair => answeredIds.contains(pair._1))
      prompt     <- remaining match {
                      case Nil     =>
                        ZIO.succeed(GamePrompt(finished = true))
                      case choices =>
                        for {
                          index                  <- Random.nextIntBounded(choices.size)
                          (wordId, translationId) = choices(index)
                          wordRows               <- repo.wordsByIds(List(wordId)).orDie
                          text                    =
                            wordRows.headOption.map(row => wordText(row, play.includeDefiniteArticles)).getOrElse("")
                          options                <- modeOf(play) match {
                                                      case GameMode.Typing         =>
                                                        ZIO.succeed(Nil)
                                                      case GameMode.MultipleChoice =>
                                                        optionsFor(play, wordId, translationId)
                                                    }
                        } yield GamePrompt(
                          finished = false,
                          wordId = Some(wordId),
                          wordText = Some(text),
                          position = Some(answeredIds.size + 1),
                          options = options,
                        )
                    }
    } yield prompt
  }

  /** How many buttons a [[GameMode.MultipleChoice]] prompt shows at most: the accepted translation plus three
    * distractors. Fewer is a legal prompt — a thin word pool is answered with two or three buttons rather than with
    * words the game does not teach.
    */
  private val optionCount = 4

  /** How an option is compared for uniqueness and for exclusion — the same trim/case-fold `GameScoring` grades by, so
    * two options can never be one click apart from the same answer.
    */
  private def optionKey(text: String): String = text.trim.toLowerCase

  /** The clickable options for one [[GameMode.MultipleChoice]] prompt: `translationId`'s text, plus up to three
    * distractors, shuffled.
    *
    * Distractors come from the game's own material only, in two groups. The pool group is every other target word
    * eligible for this game in the play's direction — read live rather than from the play's own sampled set, so a
    * four-word play still gets real alternatives. The confusable group is what `word_forms` links to those words (a
    * plural, a declined case, the lemma of a form) plus, for a German answer shown with its article, the same noun
    * under another article — `der Hund` beside `die Hunde`. One confusable is drawn first when one exists, so the
    * harder distinction shows up whenever the material offers it; the rest of the slots are filled from the pool, then
    * topped up from whatever is left.
    *
    * Every accepted translation of the prompt word is excluded: a distractor that would also be graded correct is not a
    * wrong answer.
    */
  private def optionsFor(play: GamePlayRow, wordId: Long, translationId: Long): UIO[List[String]] = {
    for {
      pairs        <- repo.eligibleWordPairs(play.gameId, play.sourceLanguage, play.targetLanguage).orDie
      acceptedIds   = (translationId :: pairs.collect { case (w, t) if w == wordId => t }).distinct
      poolIds       = pairs.map(_._2).distinct
      poolWords    <- repo.wordsByIds(poolIds).orDie
      relatedWords <- repo.relatedWords(poolIds).orDie
      correctRow    = poolWords.find(_.id == translationId)
      siblings     <- genderSiblingsOf(play, correctRow)
      correct       = correctRow.map(row => wordText(row, play.includeDefiniteArticles)).getOrElse("")
      excluded      = (correct :: poolWords.filter(row => acceptedIds.contains(row.id)).map { row =>
                        wordText(row, play.includeDefiniteArticles)
                      }).map(optionKey).toSet
      poolTexts     = poolWords
                        .filterNot(row => acceptedIds.contains(row.id))
                        .map(row => wordText(row, play.includeDefiniteArticles))
      confusables   = (relatedWords.filter(_.language == play.targetLanguage) ++ siblings)
                        .filterNot(row => acceptedIds.contains(row.id))
                        .map(row => wordText(row, play.includeDefiniteArticles)) ++ articleVariantsOf(play, correctRow)
      shuffledPool <- Random.shuffle(poolTexts)
      shuffledElse <- Random.shuffle(confusables)
      distractors   = (shuffledElse.take(1) ++ shuffledPool ++ shuffledElse.drop(1))
                        .filterNot(text => excluded.contains(optionKey(text)))
                        .distinctBy(optionKey)
                        .take(optionCount - 1)
      options      <- Random.shuffle(correct :: distractors)
    } yield options
  }

  /** The other `words` rows spelled like the accepted answer — `der See` when the answer is `die See`. Only for an
    * answer shown with its article, in a language that has gender: without the article the two are the same string,
    * which would be no distractor at all. Reads by text rather than by id because gender is part of a word's identity,
    * so the siblings are separate rows.
    */
  private def genderSiblingsOf(play: GamePlayRow, correctRow: Option[WordRow]): UIO[List[WordRow]] = {
    val targetLanguage = WordLanguage.fromString(play.targetLanguage).getOrElse(WordLanguage.En)
    val genderedNoun   = correctRow.filter { row =>
      play.includeDefiniteArticles && LanguageProfile.of(targetLanguage).hasGenders && row.gender.nonEmpty
    }
    genderedNoun match {
      case None      =>
        ZIO.succeed(Nil)
      case Some(row) =>
        repo.wordsByTexts(play.targetLanguage, List(row.text)).orDie.map(_.filter(_.id != row.id))
    }
  }

  /** The accepted answer under each of its language's other articles — `die Hund`, `das Hund` for `der Hund`; `la
    * perro` for `el perro`. The last-resort confusable, used when the dictionary holds no real sibling or form to
    * offer: the article is the half of a gendered noun a learner has to memorise, so a prompt whose only distinction is
    * the article is still the right question. Never produced for anything but a gendered noun shown with its article.
    */
  private def articleVariantsOf(play: GamePlayRow, correctRow: Option[WordRow]): List[String] = {
    correctRow.toList.flatMap { row =>
      val targetLanguage = WordLanguage.fromString(play.targetLanguage).getOrElse(WordLanguage.En)
      val profile        = LanguageProfile.of(targetLanguage)
      if (play.includeDefiniteArticles && profile.hasGenders) {
        Gender.fromColumn(row.gender).toList.flatMap { own =>
          profile.genders.filterNot(_ == own).flatMap(profile.article).map(article => article + " " + row.text)
        }
      } else
        Nil
    }
  }

  /** `play`'s stored mode, falling back to [[GameMode.Typing]] for anything unrecognised — the same lenient read
    * [[variantOf]] gives every other stored code.
    */
  private def modeOf(play: GamePlayRow): GameMode = {
    GameMode.fromString(play.mode).getOrElse(GameMode.Typing)
  }

  /** `wordId`'s translation ids eligible under `play`'s own resolved direction — every marked pair, not just the one
    * the prompt was drawn against — so [[submitAnswer]] can credit any of a word's accepted translations. Reads
    * `play.gameId`/`play.sourceLanguage`/`play.targetLanguage` rather than the game's own (now direction-agnostic) row,
    * since a play may have swapped direction relative to another play of the same game.
    */
  private def candidateTranslationIds(play: GamePlayRow, wordId: Long, fallback: Long): UIO[List[Long]] = {
    repo
      .eligibleWordPairs(play.gameId, play.sourceLanguage, play.targetLanguage)
      .orDie
      .map(pairs => (fallback :: pairs.collect { case (w, t) if w == wordId => t }).distinct)
  }

  def submitAnswer(playId: Long, wordId: Long, answerText: String, requesterUserId: Long): IO[GameFailure, Unit] = {
    for {
      play            <- requireOwnedPlay(playId, requesterUserId)
      pool            <- repo.wordPairsOf(playId).orDie
      translationId   <- ZIO.fromOption(pool.find(_._1 == wordId).map(_._2)).orElseFail(GameFailure.NotFound)
      candidateIds    <- candidateTranslationIds(play, wordId, translationId)
      candidateWords  <- repo.wordsByIds(candidateIds).orDie
      textById         = candidateWords.map(row => row.id -> wordText(row, play.includeDefiniteArticles)).toMap
      scoreOne         = GameScoring.scoreFor(modeOf(play))
      scoredById       = candidateIds.flatMap(id => textById.get(id).map(text => id -> scoreOne(text, answerText)))
      (bestId, scored) = {
        scoredById
          .maxByOption(_._2.points)
          .getOrElse(translationId -> scoreOne(textById.getOrElse(translationId, ""), answerText))
      }
      now             <- Clock.currentTime(TimeUnit.MILLISECONDS)
      answeredSoFar   <- repo.answersOf(playId).orDie
      position         = answeredSoFar.size + 1
      newScore         = answeredSoFar.map(_.points).sum + scored.points
      finishedAt       = Option.when(position == play.wordCount)(now)
      answer           = GamePlayAnswerRow(
                           id = 0L,
                           playId = playId,
                           wordId = wordId,
                           translationWordId = bestId,
                           position = position,
                           userAnswer = answerText,
                           outcome = AnswerOutcome.code(scored.outcome),
                           points = scored.points,
                           answeredAt = now,
                         )
      _               <- repo.recordAnswer(answer, newScore, finishedAt).orDie
    } yield ()
  }

  /** `answers` mapped to their display text, shared by [[getResults]] (player-facing) and [[getPlayDetail]]
    * (owner-facing) — the two answer to the same shape, just addressed and gated differently.
    *
    * `expectedTexts` carries '''every''' translation the play would have accepted for a word, not just the one row
    * [[submitAnswer]] scored against: the same union [[candidateTranslationIds]] builds — every marked target of the
    * word across all the game's tags in the play's direction, plus the recorded `translationWordId` as a floor in case
    * a pair was unmarked since. Sorted, deduped, and never empty.
    */
  private def answerResultsOf(
    play: GamePlayRow,
    answers: List[GamePlayAnswerRow],
  ): UIO[List[GameAnswerResult]] = {
    for {
      pairs        <- repo.eligibleWordPairs(play.gameId, play.sourceLanguage, play.targetLanguage).orDie
      targetsByWord = pairs.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
      expectedIds   = answers.map { a =>
                        a.id -> (a.translationWordId :: targetsByWord.getOrElse(a.wordId, Nil)).distinct
                      }.toMap
      allIds        = answers.flatMap(a => a.wordId :: expectedIds.getOrElse(a.id, Nil)).distinct
      words        <- repo.wordsByIds(allIds).orDie
      textOf        = words.map(w => w.id -> wordText(w, play.includeDefiniteArticles)).toMap
    } yield answers.map { a =>
      GameAnswerResult(
        wordText = textOf.getOrElse(a.wordId, ""),
        expectedTexts = expectedIds.getOrElse(a.id, Nil).flatMap(textOf.get).distinct.sorted,
        givenText = a.userAnswer,
        outcome = AnswerOutcome.fromString(a.outcome).getOrElse(AnswerOutcome.Wrong),
      )
    }
  }

  /** `play`'s own settings, as the wire-facing [[GameVariantDto]] — embedded in every play-facing response
    * ([[GameResults]], [[GamePlaySummary]], [[GamePlayDetail]], [[MyPlaySummary]]) so a reader can see what variant a
    * given play actually ran under.
    */
  private def variantOf(play: GamePlayRow): GameVariantDto = {
    GameVariantDto(
      sourceLanguage = WordLanguage.fromString(play.sourceLanguage).getOrElse(WordLanguage.En),
      targetLanguage = WordLanguage.fromString(play.targetLanguage).getOrElse(WordLanguage.En),
      wordLimit = play.wordLimit,
      includeDefiniteArticles = play.includeDefiniteArticles,
      wordPreference = WordPreference.fromString(play.wordPreference).getOrElse(WordPreference.All),
      mode = modeOf(play),
    )
  }

  def getResults(playId: Long, requesterUserId: Long): IO[GameFailure, GameResults] = {
    requireOwnedPlay(playId, requesterUserId).flatMap(assembleResults)
  }

  def resultsForPlayer(playId: Long, playerUserId: Long): IO[GameFailure, GameResults] = {
    for {
      play    <- repo.findPlay(playId).orDie.someOrFail(GameFailure.NotFound)
      _       <- ZIO.unless(play.playerUserId == playerUserId)(ZIO.fail(GameFailure.NotFound))
      results <- assembleResults(play)
    } yield results
  }

  /** The tail shared by [[getResults]] and [[resultsForPlayer]] once the play is loaded and the caller is cleared. */
  private def assembleResults(play: GamePlayRow): IO[GameFailure, GameResults] = {
    for {
      answers <- repo.answersOf(play.id).orDie
      results <- answerResultsOf(play, answers)
    } yield GameResults(play.score, play.maxScore, play.wordCount, results, variantOf(play))
  }

  private def summaryOf(play: GamePlayRow, usersById: Map[Long, UserRow]): GamePlaySummary = {
    val player = usersById.get(play.playerUserId)
    GamePlaySummary(
      playId = play.id,
      playerEmail = player.flatMap(_.email),
      playerIsGuest = player.exists(_.isGuest),
      score = play.score,
      maxScore = play.maxScore,
      wordCount = play.wordCount,
      startedAt = play.startedAt,
      finishedAt = play.finishedAt,
      variant = variantOf(play),
    )
  }

  def listPlays(
    slug: String,
    requesterUserId: Long,
    page: Int,
    pageSize: Int,
    playerContains: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): IO[GameFailure, GamePlayPage] = {
    for {
      game      <- requireOwnGame(slug, requesterUserId)
      plays     <- repo
                     .listPlaysPage(game.id, Paging.offset(page, pageSize), pageSize, playerContains, sort, descending)
                     .orDie
      total     <- repo.countPlaysMatching(game.id, playerContains).orDie
      usersById <- repo.usersByIds(plays.map(_.playerUserId).distinct).orDie.map(_.map(u => u.id -> u).toMap)
    } yield GamePlayPage(plays.map(play => summaryOf(play, usersById)), total)
  }

  def getPlayDetail(slug: String, playId: Long, requesterUserId: Long): IO[GameFailure, GamePlayDetail] = {
    for {
      game    <- requireOwnGame(slug, requesterUserId)
      play    <- repo.findPlayInGame(game.id, playId).orDie.someOrFail(GameFailure.NotFound)
      player  <- repo.usersByIds(List(play.playerUserId)).orDie.map(_.headOption)
      answers <- repo.answersOf(playId).orDie
      results <- answerResultsOf(play, answers)
    } yield GamePlayDetail(
      playId = play.id,
      playerEmail = player.flatMap(_.email),
      playerIsGuest = player.exists(_.isGuest),
      score = play.score,
      maxScore = play.maxScore,
      wordCount = play.wordCount,
      startedAt = play.startedAt,
      finishedAt = play.finishedAt,
      answers = results,
      variant = variantOf(play),
    )
  }

  private def myPlaySummaryOf(play: GamePlayRow, gamesById: Map[Long, GameRow]): MyPlaySummary = {
    val game = gamesById.get(play.gameId)
    MyPlaySummary(
      playId = play.id,
      gameSlug = game.map(_.slug).getOrElse(""),
      gameName = game.map(_.name).getOrElse(""),
      score = play.score,
      maxScore = play.maxScore,
      wordCount = play.wordCount,
      startedAt = play.startedAt,
      finishedAt = play.finishedAt,
      variant = variantOf(play),
    )
  }

  /** Shared by [[myPlays]] and [[playsOf]] — one page of a user's cross-game play history. The two differ only in who
    * the caller is and who ran the authorization check; the query is the same.
    */
  private def playsPageFor(
    targetUserId: Long,
    gameId: Option[Long],
    nameContains: Option[String],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): UIO[MyPlayPage] = {
    for {
      plays     <- repo
                     .listMyPlaysPage(
                       targetUserId,
                       gameId,
                       nameContains,
                       Paging.offset(page, pageSize),
                       pageSize,
                       sort,
                       descending,
                     )
                     .orDie
      total     <- repo.countMyPlaysMatching(targetUserId, gameId, nameContains).orDie
      gamesById <- repo.gamesByIds(plays.map(_.gameId).distinct).orDie.map(_.map(g => g.id -> g).toMap)
    } yield MyPlayPage(plays.map(play => myPlaySummaryOf(play, gamesById)), total)
  }

  def myPlays(
    userId: Long,
    gameId: Option[Long],
    nameContains: Option[String],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): UIO[MyPlayPage] = {
    playsPageFor(userId, gameId, nameContains, page, pageSize, sort, descending)
  }

  def playsOf(
    targetUserId: Long,
    gameId: Option[Long],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
    nameContains: Option[String],
  ): UIO[MyPlayPage] = {
    playsPageFor(targetUserId, gameId, nameContains, page, pageSize, sort, descending)
  }
}
