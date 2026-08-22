package gathedge.backend.service

import gathedge.backend.db.{GamePlayAnswerRow, GamePlayRow, GameRepository, GameRow, TagRow, UserRow, WordRow}
import gathedge.shared.domain.{AnswerOutcome, GameScoring, Tag, Word, WordLanguage, WordPreference}
import gathedge.shared.dto.{
  GameAnswerResult,
  GameDetail,
  GamePlayDetail,
  GamePlayPage,
  GamePlaySummary,
  GamePrompt,
  GameResults,
  GameSetupWord,
  GameVariantDto,
  MyGameSummary,
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

  /** [[GameService.listPlays]]/[[GameService.getPlayDetail]] on a game whose owner never turned on `trackResults` — the
    * play history is recorded regardless, this only refuses to show it.
    */
  case NotTracked
  case ValidationError(fieldErrors: Map[String, MessageRef])
}

/** Creating, reading and renaming a vocabulary quiz — which tags it may be built from, minting its `slug`/`name` — plus
  * playing one: `startPlay`/`nextPrompt`/`submitAnswer`/`getResults`.
  */
trait GameService {

  /** Tags with a marked pair in the `sourceLanguage` -> `targetLanguage` direction, own tags first. */
  def eligibleTags(sourceLanguage: WordLanguage, targetLanguage: WordLanguage, viewerId: Long): UIO[List[Tag]]

  /** `userId`'s own games, most recently created first, with their tag names and how many times each was played. */
  def myGames(userId: Long): UIO[List[MyGameSummary]]

  /** `trackResults`: `false` (the default, and the only behaviour before this parameter existed) means
    * [[listPlays]]/[[getPlayDetail]] answer [[GameFailure.NotTracked]] for this game; `true` opts into the
    * owner-facing results listing. Set once, here — there is no route to change it after creation. Direction,
    * word count, article display and word preference are no longer part of a game at all — see
    * [[startPlay]].
    */
  def createGame(
    userId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
    trackResults: Boolean = false,
  ): IO[GameFailure, GameDetail]

  /** The eligible pool a game built from `tagIds`/`sourceLanguage`/`targetLanguage` would draw from — deduped to one
    * row per source word, same as [[startPlay]]'s own pool. What the setup screen's word list previews before the game
    * exists.
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
    * `sourceLanguage` instead of its stored direction. `wordLimit`/`includeDefiniteArticles`/`wordPreference` are
    * this play's own settings, snapshotted onto its `game_plays` row — see the design doc. Fails
    * [[GameFailure.ValidationError]] for an out-of-range `wordLimit`, [[GameFailure.NoEligibleWords]] if the
    * resolved direction's pool is empty right now.
    */
  def startPlay(
    slug: String,
    playerUserId: Long,
    swapDirection: Boolean = false,
    wordLimit: Option[Int] = None,
    includeDefiniteArticles: Boolean = true,
    wordPreference: WordPreference = WordPreference.All,
  ): IO[GameFailure, PlayStarted]

  /** The play-variant picker's preview: the resolved-direction eligible pool, in the order [[startPlay]] would
    * sample from for the same `swapDirection`/`wordPreference` — lets the picker show an honest "N eligible"
    * before any play exists.
    */
  def playSetupPreview(
    slug: String,
    playerUserId: Long,
    swapDirection: Boolean,
    wordPreference: WordPreference,
  ): IO[GameFailure, List[GameSetupWord]]

  /** The next unanswered word in `playId`, or `{finished: true}` once every eligible word has been answered.
    * [[GameFailure.NotOwner]] if `playId` does not belong to `requesterUserId`.
    */
  def nextPrompt(playId: Long, requesterUserId: Long): IO[GameFailure, GamePrompt]

  /** Scores `answerText` against `wordId`'s expected translation — looked up server-side, never trusting a
    * client-supplied translation id — and records it. When `wordId` has more than one marked translation in the game's
    * tags, `answerText` is scored against each and the best-scoring one wins. Answers with acknowledgement only: never
    * the score or whether it was correct, so a player is never shown correctness mid-game.
    */
  def submitAnswer(playId: Long, wordId: Long, answerText: String, requesterUserId: Long): IO[GameFailure, Unit]

  /** `playId`'s score and full answer history, for the results screen. [[GameFailure.NotOwner]] if it does not belong
    * to `requesterUserId`.
    */
  def getResults(playId: Long, requesterUserId: Long): IO[GameFailure, GameResults]

  /** One page of `slug`'s plays, for its owner. [[GameFailure.NotOwner]] for anyone else; [[GameFailure.NotTracked]] if
    * the game never turned on `trackResults`. `playerContains` narrows to players whose address contains it.
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

  /** `userId`'s own plays across every game, most recently started first unless `sort` says otherwise. Never gated by
    * `trackResults` — it is always the caller's own data, the same reasoning [[getResults]] is never gated either.
    * `gameId` narrows to one game.
    */
  def myPlays(
    userId: Long,
    gameId: Option[Long],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): UIO[MyPlayPage]

  /** `targetUserId`'s plays across every game, narrowed to games whose owner turned on `trackResults` — the same rule
    * that gates a game's own owner. Authorization (a progress share, or being an administrator) is the caller's job;
    * this never fails on its own.
    */
  def trackedPlaysOf(
    targetUserId: Long,
    gameId: Option[Long],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): UIO[MyPlayPage]
}

object GameService {

  def eligibleTags(
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    viewerId: Long,
  ): URIO[GameService, List[Tag]] =
    ZIO.serviceWithZIO[GameService](_.eligibleTags(sourceLanguage, targetLanguage, viewerId))

  def myGames(userId: Long): URIO[GameService, List[MyGameSummary]] =
    ZIO.serviceWithZIO[GameService](_.myGames(userId))

  def createGame(
    userId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
    trackResults: Boolean = false,
  ): ZIO[GameService, GameFailure, GameDetail] = {
    ZIO.serviceWithZIO[GameService](_.createGame(userId, sourceLanguage, targetLanguage, tagIds, trackResults))
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
  ): ZIO[GameService, GameFailure, PlayStarted] = {
    ZIO.serviceWithZIO[GameService](
      _.startPlay(slug, playerUserId, swapDirection, wordLimit, includeDefiniteArticles, wordPreference)
    )
  }

  def playSetupPreview(
    slug: String,
    playerUserId: Long,
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
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): URIO[GameService, MyPlayPage] =
    ZIO.serviceWithZIO[GameService](_.myPlays(userId, gameId, page, pageSize, sort, descending))

  def trackedPlaysOf(
    targetUserId: Long,
    gameId: Option[Long],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): URIO[GameService, MyPlayPage] =
    ZIO.serviceWithZIO[GameService](_.trackedPlaysOf(targetUserId, gameId, page, pageSize, sort, descending))

  val live: URLayer[GameRepository & GameWordList, GameService] = {
    ZLayer.fromFunction((repo: GameRepository, words: GameWordList) => GameServiceLive(repo, words))
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

final case class GameServiceLive(repo: GameRepository, wordList: GameWordList) extends GameService {

  private def toTag(row: TagRow, wordCount: Long, viewerId: Long): Tag = {
    Tag(row.id, row.name, wordCount, row.userId == viewerId)
  }

  /** `Word.displayText`, gated by a game's own `includeDefiniteArticles` — the choke point [[nextPrompt]],
    * [[submitAnswer]] and [[answerResultsOf]] all go through, so a game that turned the article off never sees it in a
    * prompt, a scored answer, or a results row.
    */
  private def wordText(row: WordRow, includeDefiniteArticles: Boolean): String = {
    if (includeDefiniteArticles) Word.displayText(row.text, row.gender) else row.text
  }

  def eligibleTags(sourceLanguage: WordLanguage, targetLanguage: WordLanguage, viewerId: Long): UIO[List[Tag]] = {
    repo
      .eligibleTags(WordLanguage.code(sourceLanguage), WordLanguage.code(targetLanguage))
      .orDie
      .map { rows =>
        // One row per (tag, eligible source word) — see the repo method's doc comment on why dedup is left to the
        // caller. Counting distinct word ids per tag is what turns that into "how many words this tag would draw
        // prompts from", the number the setup screen's checkbox shows next to each tag's name.
        val byTag = rows.groupBy(_._1).view.mapValues(_.map(_._2).distinct.size.toLong).toMap
        Tag.sorted(byTag.map { case (row, wordCount) => toTag(row, wordCount, viewerId) }.toList)
      }
  }

  def myGames(userId: Long): UIO[List[MyGameSummary]] = {
    for {
      rows       <- repo.gamesByOwner(userId).orDie
      tagsByGame <- ZIO
                      .foreach(rows)(row => repo.tagsOf(row.id).orDie.map(tags => row.id -> tags.map(_.name).sorted))
                      .map(_.toMap)
      counts     <- repo.playCounts(rows.map(_.id)).orDie
    } yield rows.map { row =>
      MyGameSummary(
        slug = row.slug,
        name = row.name,
        sourceLanguage = WordLanguage.fromString(row.sourceLanguage).getOrElse(WordLanguage.En),
        targetLanguage = WordLanguage.fromString(row.targetLanguage).getOrElse(WordLanguage.En),
        tagNames = tagsByGame.getOrElse(row.id, Nil),
        playCount = counts.getOrElse(row.id, 0L),
        createdAt = row.createdAt,
      )
    }
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
    trackResults: Boolean,
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
          trackResults = trackResults,
        )
        repo.insertGame(row, tagIds).catchAll { error =>
          repo.findBySlug(slug).orDie.flatMap {
            case Some(_) =>
              insertWithRetry(userId, sourceLanguage, targetLanguage, tagIds, trackResults, now, attempt + 1, Some(pair))
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
    trackResults: Boolean = false,
  ): IO[GameFailure, GameDetail] = {
    for {
      _          <- ZIO.when(tagIds.isEmpty)(ZIO.fail(GameFailure.NoTagsSelected))
      eligible   <- repo.eligibleTags(WordLanguage.code(sourceLanguage), WordLanguage.code(targetLanguage)).orDie
      eligibleIds = eligible.map(_._1.id).toSet
      _          <- ZIO.unless(tagIds.forall(eligibleIds.contains))(ZIO.fail(GameFailure.TagNotEligible))
      now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
      row        <- insertWithRetry(userId, sourceLanguage, targetLanguage, tagIds, trackResults, now, attempt = 0, lastPair = None)
      tags       <- repo.tagsOf(row.id).orDie
    } yield GameDetail(row.slug, row.name, sourceLanguage, targetLanguage, tags.map(_.name).sorted, row.trackResults)
  }

  /** The setup screen's preview of the pool a game built from `tagIds` would draw from — same dedup rule as
    * [[eligibleWordPool]], through [[GameRepository.eligibleWordPairsForTags]] instead of a game's own `game_tags`,
    * since no game exists yet.
    */
  def eligibleWords(
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
  ): UIO[List[GameSetupWord]] = {
    for {
      pool  <- eligibleWordPoolForTags(tagIds, WordLanguage.code(sourceLanguage), WordLanguage.code(targetLanguage))
      words <- repo.wordsByIds(pool.map(_._1)).orDie
    } yield words.map(w => GameSetupWord(w.id, Word.displayText(w.text, w.gender))).sortBy(_.text)
  }

  private def detailOf(row: GameRow): UIO[GameDetail] = {
    repo.tagsOf(row.id).orDie.map { tags =>
      GameDetail(
        row.slug,
        row.name,
        WordLanguage.fromString(row.sourceLanguage).getOrElse(WordLanguage.En),
        WordLanguage.fromString(row.targetLanguage).getOrElse(WordLanguage.En),
        tags.map(_.name).sorted,
        row.trackResults,
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
    * shared by [[rename]], [[reshuffle]], [[listPlays]] and [[getPlayDetail]]. Games reveal their existence to
    * non-owners (a shared link must be viewable by anyone), so this fails [[GameFailure.NotOwner]] rather than
    * [[GameFailure.NotFound]] for somebody else's game — the same 403, not 404, choice [[rename]]/[[reshuffle]] already
    * made before this was extracted.
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

  /** `(word_id, translation_word_id)` pairs eligible for `gameId` in the `sourceLanguage` -> `targetLanguage`
    * direction, deduped to one row per source word. Takes explicit codes rather than a `GameRow` because a play
    * may resolve to the reverse of the game's own stored direction — see [[GameServiceLive.startPlay]].
    */
  private def eligibleWordPoolFor(gameId: Long, sourceLanguage: String, targetLanguage: String): UIO[List[(Long, Long)]] = {
    repo.eligibleWordPairs(gameId, sourceLanguage, targetLanguage).orDie.map(dedupeToOnePerWord)
  }

  /** This player's per-word answer history for `gameId` in the `sourceLanguage` -> `targetLanguage` direction —
    * total answers and how many were not [[AnswerOutcome.Correct]] — the ordering signal for
    * [[WordPreference.Unplayed]]/[[WordPreference.MostMistakes]]. A word absent from the map has never been
    * answered by this player in this direction — see [[GameRepository.answerOutcomesFor]].
    */
  private def wordStats(
    gameId: Long,
    playerUserId: Long,
    sourceLanguage: String,
    targetLanguage: String,
  ): UIO[Map[Long, (Int, Int)]] = {
    repo.answerOutcomesFor(gameId, playerUserId, sourceLanguage, targetLanguage).orDie.map { rows =>
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

  /** `pool` reordered so `preference`'s preferred subset comes first — see the design doc's "priority sampling,
    * not a hard filter" rule. Shuffled first in every case, so ties (including "no history at all", which every
    * word shares under [[WordPreference.All]]) are broken randomly rather than by pool order, and `.sortBy` is
    * stable, so that shuffle survives within each tie group.
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

  /** `pool` itself when `limit` is absent or no smaller than the pool. Otherwise `limit`'s first
    * [[preferenceOrdered]] words — for [[WordPreference.Unplayed]]/[[WordPreference.MostMistakes]] this is the
    * "fill from the preferred subset, then top up from the rest" rule the design doc describes; for
    * [[WordPreference.All]] it is a uniform random sample, exactly as before this feature existed.
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
          stats   <- wordStats(gameId, playerUserId, sourceLanguage, targetLanguage)
          ordered <- preferenceOrdered(pool, stats, preference)
        } yield ordered.take(n)
      case _                        =>
        ZIO.succeed(pool)
    }
  }

  /** Same as [[eligibleWordPool]], through an explicit tag id list instead of a game's `game_tags` — what the setup
    * screen's word-list preview and a `randomizeEachPlay = false` game's fixed-pool sampling both call through, since
    * neither has a `game_tags` row set to read from yet.
    */
  private def eligibleWordPoolForTags(
    tagIds: List[Long],
    sourceLanguage: String,
    targetLanguage: String,
  ): UIO[List[(Long, Long)]] = {
    repo.eligibleWordPairsForTags(tagIds, sourceLanguage, targetLanguage).orDie.map(dedupeToOnePerWord)
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
  ): IO[GameFailure, PlayStarted] = {
    for {
      game       <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      validLimit <- ZIO
                      .foreach(wordLimit)(limit => ZIO.fromEither(Validation.validateWordLimit(limit)))
                      .mapError(error => GameFailure.ValidationError(Map("wordLimit" -> error)))
      resolved    = if (swapDirection) (game.targetLanguage, game.sourceLanguage) else (game.sourceLanguage, game.targetLanguage)
      (resolvedSource, resolvedTarget) = resolved
      pool       <- eligibleWordPoolFor(game.id, resolvedSource, resolvedTarget)
      _          <- ZIO.when(pool.isEmpty)(ZIO.fail(GameFailure.NoEligibleWords))
      sampled    <- sampleWordPool(game.id, playerUserId, resolvedSource, resolvedTarget, pool, validLimit, wordPreference)
      now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
      wordCount   = sampled.size
      maxScore    = wordCount * GameScoring.maxPointsPerWord
      row        <- repo
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
                        ),
                        sampled,
                      )
                      .orDie
    } yield PlayStarted(row.id, wordCount, maxScore)
  }

  def playSetupPreview(
    slug: String,
    playerUserId: Long,
    swapDirection: Boolean,
    wordPreference: WordPreference,
  ): IO[GameFailure, List[GameSetupWord]] = {
    for {
      game     <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      resolved  = if (swapDirection) (game.targetLanguage, game.sourceLanguage) else (game.sourceLanguage, game.targetLanguage)
      (resolvedSource, resolvedTarget) = resolved
      pool     <- eligibleWordPoolFor(game.id, resolvedSource, resolvedTarget)
      stats    <- wordStats(game.id, playerUserId, resolvedSource, resolvedTarget)
      ordered  <- preferenceOrdered(pool, stats, wordPreference)
      words    <- repo.wordsByIds(ordered.map(_._1)).orDie
      textById  = words.map(w => w.id -> Word.displayText(w.text, w.gender)).toMap
    } yield ordered.flatMap { case (wordId, _) => textById.get(wordId).map(text => GameSetupWord(wordId, text)) }
  }

  def nextPrompt(playId: Long, requesterUserId: Long): IO[GameFailure, GamePrompt] = {
    for {
      play       <- requireOwnedPlay(playId, requesterUserId)
      game       <- repo.findGame(play.gameId).orDie.someOrFail(GameFailure.NotFound)
      pool       <- repo.wordPairsOf(playId).orDie
      answered   <- repo.answersOf(playId).orDie
      answeredIds = answered.map(_.wordId).toSet
      remaining   = pool.filterNot(pair => answeredIds.contains(pair._1))
      prompt     <- remaining match {
                      case Nil     =>
                        ZIO.succeed(GamePrompt(finished = true))
                      case choices =>
                        for {
                          index      <- Random.nextIntBounded(choices.size)
                          (wordId, _) = choices(index)
                          wordRows   <- repo.wordsByIds(List(wordId)).orDie
                          text        = wordRows.headOption.map(row => wordText(row, game.includeDefiniteArticles)).getOrElse("")
                        } yield GamePrompt(
                          finished = false,
                          wordId = Some(wordId),
                          wordText = Some(text),
                          position = Some(answeredIds.size + 1),
                        )
                    }
    } yield prompt
  }

  /** `wordId`'s translation ids eligible under `game`'s tags — every marked pair, not just the one the prompt was drawn
    * against — so [[submitAnswer]] can credit any of a word's accepted translations, not only the one `wordPairsOf`
    * happened to fix for this play.
    */
  private def candidateTranslationIds(game: GameRow, wordId: Long, fallback: Long): UIO[List[Long]] = {
    repo
      .eligibleWordPairs(game.id, game.sourceLanguage, game.targetLanguage)
      .orDie
      .map(pairs => (fallback :: pairs.collect { case (w, t) if w == wordId => t }).distinct)
  }

  def submitAnswer(playId: Long, wordId: Long, answerText: String, requesterUserId: Long): IO[GameFailure, Unit] = {
    for {
      play            <- requireOwnedPlay(playId, requesterUserId)
      pool            <- repo.wordPairsOf(playId).orDie
      translationId   <- ZIO.fromOption(pool.find(_._1 == wordId).map(_._2)).orElseFail(GameFailure.NotFound)
      game            <- repo.findGame(play.gameId).orDie.someOrFail(GameFailure.NotFound)
      candidateIds    <- candidateTranslationIds(game, wordId, translationId)
      candidateWords  <- repo.wordsByIds(candidateIds).orDie
      textById         = candidateWords.map(row => row.id -> wordText(row, game.includeDefiniteArticles)).toMap
      scoredById       = candidateIds.flatMap(id => textById.get(id).map(text => id -> GameScoring.score(text, answerText)))
      (bestId, scored) = {
        scoredById
          .maxByOption(_._2.points)
          .getOrElse(translationId -> GameScoring.score(textById.getOrElse(translationId, ""), answerText))
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
    */
  private def answerResultsOf(
    answers: List[GamePlayAnswerRow],
    includeDefiniteArticles: Boolean,
  ): UIO[List[GameAnswerResult]] = {
    for {
      words <- repo.wordsByIds(answers.flatMap(a => List(a.wordId, a.translationWordId)).distinct).orDie
      textOf = words.map(w => w.id -> wordText(w, includeDefiniteArticles)).toMap
    } yield answers.map { a =>
      GameAnswerResult(
        wordText = textOf.getOrElse(a.wordId, ""),
        expectedText = textOf.getOrElse(a.translationWordId, ""),
        givenText = a.userAnswer,
        outcome = AnswerOutcome.fromString(a.outcome).getOrElse(AnswerOutcome.Wrong),
      )
    }
  }

  def getResults(playId: Long, requesterUserId: Long): IO[GameFailure, GameResults] = {
    for {
      play    <- requireOwnedPlay(playId, requesterUserId)
      game    <- repo.findGame(play.gameId).orDie.someOrFail(GameFailure.NotFound)
      answers <- repo.answersOf(playId).orDie
      results <- answerResultsOf(answers, game.includeDefiniteArticles)
    } yield GameResults(play.score, play.maxScore, play.wordCount, results)
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
      _         <- ZIO.unless(game.trackResults)(ZIO.fail(GameFailure.NotTracked))
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
      _       <- ZIO.unless(game.trackResults)(ZIO.fail(GameFailure.NotTracked))
      play    <- repo.findPlayInGame(game.id, playId).orDie.someOrFail(GameFailure.NotFound)
      player  <- repo.usersByIds(List(play.playerUserId)).orDie.map(_.headOption)
      answers <- repo.answersOf(playId).orDie
      results <- answerResultsOf(answers, game.includeDefiniteArticles)
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
    )
  }

  /** Shared by [[myPlays]] and [[trackedPlaysOf]] — the only difference between "my own history" and "someone else's,
    * already authorized" is whether untracked games are filtered out.
    */
  private def playsPageFor(
    targetUserId: Long,
    gameId: Option[Long],
    trackedOnly: Boolean,
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
                       trackedOnly,
                       Paging.offset(page, pageSize),
                       pageSize,
                       sort,
                       descending,
                     )
                     .orDie
      total     <- repo.countMyPlaysMatching(targetUserId, gameId, trackedOnly).orDie
      gamesById <- repo.gamesByIds(plays.map(_.gameId).distinct).orDie.map(_.map(g => g.id -> g).toMap)
    } yield MyPlayPage(plays.map(play => myPlaySummaryOf(play, gamesById)), total)
  }

  def myPlays(
    userId: Long,
    gameId: Option[Long],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): UIO[MyPlayPage] = {
    playsPageFor(userId, gameId, trackedOnly = false, page, pageSize, sort, descending)
  }

  def trackedPlaysOf(
    targetUserId: Long,
    gameId: Option[Long],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): UIO[MyPlayPage] = {
    playsPageFor(targetUserId, gameId, trackedOnly = true, page, pageSize, sort, descending)
  }
}
