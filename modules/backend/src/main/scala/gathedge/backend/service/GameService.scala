package gathedge.backend.service

import gathedge.backend.db.{GameRepository, GameRow, TagRow}
import gathedge.shared.domain.{Tag, WordLanguage}
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
  case ValidationError(fieldErrors: Map[String, MessageRef])
}

/** A game as a caller may see it: no owner-only data, no id — `slug` is what a reader addresses it by. */
final case class GameDetail(
  slug: String,
  name: String,
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  tagNames: List[String],
)

/** Creating and reading a vocabulary quiz. Playing one is [[gathedge.backend.service.GameService]]'s later extension
  * (once `startPlay`/`nextPrompt`/`submitAnswer`/`getResults` exist) — this covers only a game's own identity: which
  * tags it may be built from, minting its `slug`/`name`, and renaming it.
  */
trait GameService {

  /** Tags with a marked pair in the `sourceLanguage` -> `targetLanguage` direction, own tags first. */
  def eligibleTags(sourceLanguage: WordLanguage, targetLanguage: WordLanguage, viewerId: Long): UIO[List[Tag]]

  def createGame(
    userId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
  ): IO[GameFailure, GameDetail]

  def getBySlug(slug: String): IO[GameFailure, GameDetail]

  /** Only the owner may rename; anyone else gets [[GameFailure.NotOwner]]. `slug` never changes. */
  def rename(slug: String, newName: String, requesterUserId: Long): IO[GameFailure, GameDetail]
}

object GameService {

  def eligibleTags(
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    viewerId: Long,
  ): URIO[GameService, List[Tag]] =
    ZIO.serviceWithZIO[GameService](_.eligibleTags(sourceLanguage, targetLanguage, viewerId))

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

  private def toTag(row: TagRow, viewerId: Long): Tag = Tag(row.id, row.name, wordCount = 0L, row.userId == viewerId)

  def eligibleTags(sourceLanguage: WordLanguage, targetLanguage: WordLanguage, viewerId: Long): UIO[List[Tag]] = {
    repo
      .eligibleTags(WordLanguage.code(sourceLanguage), WordLanguage.code(targetLanguage))
      .orDie
      .map(rows => Tag.sorted(rows.map(toTag(_, viewerId))))
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
      eligibleIds = eligible.map(_.id).toSet
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
}
