package gathedge.backend.service

import gathedge.backend.db.{GamePlayAnswerRow, GamePlayRow, GameRepository, GameRow, TagRow, WordRow}
import gathedge.shared.domain.{AnswerOutcome, GameScoring, Tag, Word, WordLanguage}
import gathedge.shared.dto.{GameAnswerResult, GameDetail, GamePrompt, GameResults, MyGameSummary, PlayStarted}
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

  /** `userId`'s own games, most recently created first, with their tag names and how many times each was played. */
  def myGames(userId: Long): UIO[List[MyGameSummary]]

  def createGame(
    userId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
  ): IO[GameFailure, GameDetail]

  def getBySlug(slug: String): IO[GameFailure, GameDetail]

  /** Only the owner may rename; anyone else gets [[GameFailure.NotOwner]]. `slug` never changes. */
  def rename(slug: String, newName: String, requesterUserId: Long): IO[GameFailure, GameDetail]

  /** Starts a fresh attempt at `slug`. Fails [[GameFailure.NoEligibleWords]] if the game's tags carry no eligible pair
    * right now.
    */
  def startPlay(slug: String, playerUserId: Long): IO[GameFailure, PlayStarted]

  /** The next unanswered word in `playId`, or `{finished: true}` once every eligible word has been answered.
    * [[GameFailure.NotOwner]] if `playId` does not belong to `requesterUserId`.
    */
  def nextPrompt(playId: Long, requesterUserId: Long): IO[GameFailure, GamePrompt]

  /** Scores `answerText` against `wordId`'s expected translation — looked up server-side, never trusting a
    * client-supplied translation id — and records it. Answers with acknowledgement only: never the score or whether it
    * was correct, so a player is never shown correctness mid-game.
    */
  def submitAnswer(playId: Long, wordId: Long, answerText: String, requesterUserId: Long): IO[GameFailure, Unit]

  /** `playId`'s score and full answer history, for the results screen. [[GameFailure.NotOwner]] if it does not belong
    * to `requesterUserId`.
    */
  def getResults(playId: Long, requesterUserId: Long): IO[GameFailure, GameResults]
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
  ): ZIO[GameService, GameFailure, GameDetail] =
    ZIO.serviceWithZIO[GameService](_.createGame(userId, sourceLanguage, targetLanguage, tagIds))

  def getBySlug(slug: String): ZIO[GameService, GameFailure, GameDetail] =
    ZIO.serviceWithZIO[GameService](_.getBySlug(slug))

  def rename(slug: String, newName: String, requesterUserId: Long): ZIO[GameService, GameFailure, GameDetail] =
    ZIO.serviceWithZIO[GameService](_.rename(slug, newName, requesterUserId))

  def startPlay(slug: String, playerUserId: Long): ZIO[GameService, GameFailure, PlayStarted] =
    ZIO.serviceWithZIO[GameService](_.startPlay(slug, playerUserId))

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
          // A concurrent caller may have taken this exact slug between the attempt and here; the unique
          // index is what decides, and the loser simply tries again with a fresh candidate — the same
          // race tolerance WordRepository.ensureWord applies to a word's natural key.
          repo.findBySlug(slug).orDie.flatMap {
            case Some(_) =>
              insertWithRetry(userId, sourceLanguage, targetLanguage, tagIds, now, attempt + 1, Some(pair))
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
      row        <- insertWithRetry(userId, sourceLanguage, targetLanguage, tagIds, now, attempt = 0, lastPair = None)
      tags       <- repo.tagsOf(row.id).orDie
    } yield GameDetail(row.slug, row.name, sourceLanguage, targetLanguage, tags.map(_.name).sorted)
  }

  private def detailOf(row: GameRow): UIO[GameDetail] = {
    repo.tagsOf(row.id).orDie.map { tags =>
      GameDetail(
        row.slug,
        row.name,
        WordLanguage.fromString(row.sourceLanguage).getOrElse(WordLanguage.En),
        WordLanguage.fromString(row.targetLanguage).getOrElse(WordLanguage.En),
        tags.map(_.name).sorted,
      )
    }
  }

  def getBySlug(slug: String): IO[GameFailure, GameDetail] = {
    for {
      row    <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      detail <- detailOf(row)
    } yield detail
  }

  def rename(slug: String, newName: String, requesterUserId: Long): IO[GameFailure, GameDetail] = {
    for {
      row    <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      _      <- ZIO.unless(row.ownerUserId == requesterUserId)(ZIO.fail(GameFailure.NotOwner))
      valid  <- ZIO
                  .fromEither(Validation.validateGameName(newName))
                  .mapError(error => GameFailure.ValidationError(Map("name" -> error)))
      now    <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _      <- repo.rename(row.id, valid, now).orDie
      detail <- detailOf(row.copy(name = valid))
    } yield detail
  }

  /** `(word_id, translation_word_id)` pairs eligible for `game`, deduped to one row per source word — the lowest
    * translation id on a tie, per [[GameRepository.eligibleWordPairs]]'s doc comment on why dedup is a business rule
    * and not the query's job.
    */
  private def eligibleWordPool(game: GameRow): UIO[List[(Long, Long)]] = {
    repo
      .eligibleWordPairs(game.id, game.sourceLanguage, game.targetLanguage)
      .orDie
      .map(_.groupBy(_._1).view.mapValues(_.map(_._2).min).toList)
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

  /** A play's `game_id` always names a real game — `games` cascades onto `game_plays`, so a missing row here is a
    * defect, not a caller error, the same reasoning `insertWithRetry` dies rather than fails on an exhausted slug.
    */
  private def requireGame(gameId: Long): UIO[GameRow] = {
    repo.findGame(gameId).orDie.flatMap {
      case Some(game) =>
        ZIO.succeed(game)
      case None       =>
        ZIO.die(new IllegalStateException(s"game $gameId missing for an existing play"))
    }
  }

  def startPlay(slug: String, playerUserId: Long): IO[GameFailure, PlayStarted] = {
    for {
      game     <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      pool     <- eligibleWordPool(game)
      _        <- ZIO.when(pool.isEmpty)(ZIO.fail(GameFailure.NoEligibleWords))
      now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      wordCount = pool.size
      maxScore  = wordCount * GameScoring.maxPointsPerWord
      row      <- repo
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
                      )
                    )
                    .orDie
    } yield PlayStarted(row.id, wordCount, maxScore)
  }

  def nextPrompt(playId: Long, requesterUserId: Long): IO[GameFailure, GamePrompt] = {
    for {
      play       <- requireOwnedPlay(playId, requesterUserId)
      game       <- requireGame(play.gameId)
      pool       <- eligibleWordPool(game)
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
                          wordText    = wordRows.headOption.map(row => Word.displayText(row.text, row.gender)).getOrElse("")
                        } yield GamePrompt(
                          finished = false,
                          wordId = Some(wordId),
                          wordText = Some(wordText),
                          position = Some(answeredIds.size + 1),
                        )
                    }
    } yield prompt
  }

  def submitAnswer(playId: Long, wordId: Long, answerText: String, requesterUserId: Long): IO[GameFailure, Unit] = {
    for {
      play          <- requireOwnedPlay(playId, requesterUserId)
      game          <- requireGame(play.gameId)
      pool          <- eligibleWordPool(game)
      translationId <- ZIO.fromOption(pool.find(_._1 == wordId).map(_._2)).orElseFail(GameFailure.NotFound)
      expectedWords <- repo.wordsByIds(List(translationId)).orDie
      expectedText   = expectedWords.headOption.map(row => Word.displayText(row.text, row.gender)).getOrElse("")
      scored         = GameScoring.score(expectedText, answerText)
      now           <- Clock.currentTime(TimeUnit.MILLISECONDS)
      answeredSoFar <- repo.answersOf(playId).orDie
      position       = answeredSoFar.size + 1
      newScore       = answeredSoFar.map(_.points).sum + scored.points
      finishedAt     = Option.when(position == play.wordCount)(now)
      answer         = GamePlayAnswerRow(
                         id = 0L,
                         playId = playId,
                         wordId = wordId,
                         translationWordId = translationId,
                         position = position,
                         userAnswer = answerText,
                         outcome = AnswerOutcome.code(scored.outcome),
                         points = scored.points,
                         answeredAt = now,
                       )
      _             <- repo.recordAnswer(answer, newScore, finishedAt).orDie
    } yield ()
  }

  def getResults(playId: Long, requesterUserId: Long): IO[GameFailure, GameResults] = {
    for {
      play    <- requireOwnedPlay(playId, requesterUserId)
      answers <- repo.answersOf(playId).orDie
      wordIds  = answers.flatMap(a => List(a.wordId, a.translationWordId)).distinct
      words   <- repo.wordsByIds(wordIds).orDie
      textOf   = words.map(w => w.id -> Word.displayText(w.text, w.gender)).toMap
      results  = answers.map { a =>
                   GameAnswerResult(
                     wordText = textOf.getOrElse(a.wordId, ""),
                     expectedText = textOf.getOrElse(a.translationWordId, ""),
                     givenText = a.userAnswer,
                     outcome = AnswerOutcome.fromString(a.outcome).getOrElse(AnswerOutcome.Wrong),
                   )
                 }
    } yield GameResults(play.score, play.maxScore, play.wordCount, results)
  }
}
