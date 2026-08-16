package gathedge.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** `games`/`game_tags` — creating a game and reading it back — and `game_plays`/`game_play_answers` — one attempt at a
  * game and its per-word answer history.
  *
  * '''Nothing here logs a game's name or slug, or a submitted answer's text.''' A slug is a public identifier, not a
  * credential, but a game's name and a player's typed answer are both free text somebody typed — the same rule
  * [[QuillRepository.logged]] states for a tag name applies here: log ids and counts.
  */
trait GameRepository {

  def findBySlug(slug: String): Task[Option[GameRow]]

  def findGame(id: Long): Task[Option[GameRow]]

  /** Tags carrying at least one `word_tag_pairs` row whose source word is `sourceLanguage` and whose marked translation
    * word is `targetLanguage` — the set a game may be built from, paired with which source word makes each row
    * eligible. Not deduped to one row per tag: a tag with three eligible words comes back as three rows sharing that
    * tag, and turning that into a per-tag word count (or a bare tag id set) is the caller's job — the same split
    * [[eligibleWordPairs]]'s doc comment draws for per-word dedup. Which order tags come back in is the caller's job
    * too.
    */
  def eligibleTags(sourceLanguage: String, targetLanguage: String): Task[List[(TagRow, Long)]]

  /** Inserts `row`, one `game_tags` row per id in `tagIds`, and (for a `randomizeEachPlay = false` game) one
    * `game_word_pool` row per pair in `wordPool`, as one unit of work — the same "row + linked rows" shape
    * [[insertPlay]] models for `game_play_words`: a game whose row landed but whose tags or fixed pool didn't is not a
    * state anything downstream can make sense of. `wordPool` is empty for a `randomizeEachPlay = true` game.
    */
  def insertGame(row: GameRow, tagIds: List[Long], wordPool: List[(Long, Long)] = Nil): Task[GameRow]

  def tagsOf(gameId: Long): Task[List[TagRow]]

  /** Rows affected — `0` means `id` does not exist. Ownership is the service's job: this only writes. */
  def rename(id: Long, name: String, updatedAt: Long): Task[Long]

  /** Every game `ownerUserId` created, most recent first — the "my games" listing's source rows. */
  def gamesByOwner(ownerUserId: Long): Task[List[GameRow]]

  /** How many `game_plays` rows exist for each of `gameIds`, as one grouped query rather than one per game. A game with
    * zero plays is simply absent from the map — the caller's job to default it to `0`, the same split
    * [[eligibleTags]]'s doc comment draws for dedup.
    */
  def playCounts(gameIds: List[Long]): Task[Map[Long, Long]]

  def findPlay(id: Long): Task[Option[GamePlayRow]]

  /** Raw `(word_id, translation_word_id)` pairs for `gameId`'s tags, scoped to `sourceLanguage` -> `targetLanguage` —
    * the same join shape as [[eligibleTags]], through `game_tags` instead of a bare tag id list. Not deduped: a word
    * can sit under more than one of the game's tags. Deduping to one row per source word (lowest translation id on a
    * tie) is a business rule, not a projection, so it is the service's job.
    */
  def eligibleWordPairs(gameId: Long, sourceLanguage: String, targetLanguage: String): Task[List[(Long, Long)]]

  /** The same join shape as [[eligibleWordPairs]], through an explicit tag id list instead of `game_tags` — what the
    * setup screen's word-list preview reads before a game (and its `game_tags` rows) exist at all, and what
    * `GameService` also uses to sample a `randomizeEachPlay = false` game's fixed pool, via its own tags.
    */
  def eligibleWordPairsForTags(
    tagIds: List[Long],
    sourceLanguage: String,
    targetLanguage: String,
  ): Task[List[(Long, Long)]]

  /** Inserts `row` and one `game_play_words` row per pair in `wordPairs`, as one unit of work — the same "row + linked
    * rows" shape [[insertGame]] models for `game_tags`: a play whose `game_plays` row landed but whose word set didn't
    * is not a state anything downstream (`nextPrompt`, `submitAnswer`, `getResults`) can make sense of.
    */
  def insertPlay(row: GamePlayRow, wordPairs: List[(Long, Long)]): Task[GamePlayRow]

  /** `playId`'s fixed word set, written once by [[insertPlay]] — what [[GameService]] reads instead of recomputing
    * [[eligibleWordPairs]] live on every call, now that a play's word set may be a sampled subset of the game's whole
    * eligible pool rather than always being the whole thing. Order is not meaningful; the caller already draws its own
    * random prompt order from this.
    */
  def wordPairsOf(playId: Long): Task[List[(Long, Long)]]

  /** `gameId`'s fixed word pool, for a `randomizeEachPlay = false` game — written once by [[insertGame]] (or replaced
    * by [[replaceGameWordPool]]) rather than sampled fresh by every play. Empty for a `randomizeEachPlay = true` game,
    * which keeps no rows here at all. Order is not meaningful, the same as [[wordPairsOf]].
    */
  def wordPoolOf(gameId: Long): Task[List[(Long, Long)]]

  /** Replaces `gameId`'s whole fixed word pool with `pairs`, in one transaction — the reshuffle action's only write.
    * Deleting first and inserting fresh, rather than diffing, matches how small this table is expected to stay (one
    * game's `wordLimit`, at most) and keeps the same "delete then insert" shape simple.
    */
  def replaceGameWordPool(gameId: Long, pairs: List[(Long, Long)]): Task[Unit]

  /** Every answer recorded for `playId` so far, in the order they were answered. */
  def answersOf(playId: Long): Task[List[GamePlayAnswerRow]]

  /** Inserts `answer` and updates `game_plays.score` (and `finished_at`, when given) in one transaction — a play whose
    * answer landed but whose running score did not update is not a state either side of this should ever observe.
    */
  def recordAnswer(answer: GamePlayAnswerRow, newScore: Int, finishedAt: Option[Long]): Task[Unit]

  def wordsByIds(ids: List[Long]): Task[List[WordRow]]
}

object GameRepository {

  def findBySlug(slug: String): RIO[GameRepository, Option[GameRow]] =
    ZIO.serviceWithZIO[GameRepository](_.findBySlug(slug))

  def findGame(id: Long): RIO[GameRepository, Option[GameRow]] =
    ZIO.serviceWithZIO[GameRepository](_.findGame(id))

  def eligibleTags(sourceLanguage: String, targetLanguage: String): RIO[GameRepository, List[(TagRow, Long)]] =
    ZIO.serviceWithZIO[GameRepository](_.eligibleTags(sourceLanguage, targetLanguage))

  def insertGame(row: GameRow, tagIds: List[Long], wordPool: List[(Long, Long)] = Nil): RIO[GameRepository, GameRow] =
    ZIO.serviceWithZIO[GameRepository](_.insertGame(row, tagIds, wordPool))

  def tagsOf(gameId: Long): RIO[GameRepository, List[TagRow]] =
    ZIO.serviceWithZIO[GameRepository](_.tagsOf(gameId))

  def rename(id: Long, name: String, updatedAt: Long): RIO[GameRepository, Long] =
    ZIO.serviceWithZIO[GameRepository](_.rename(id, name, updatedAt))

  def gamesByOwner(ownerUserId: Long): RIO[GameRepository, List[GameRow]] =
    ZIO.serviceWithZIO[GameRepository](_.gamesByOwner(ownerUserId))

  def playCounts(gameIds: List[Long]): RIO[GameRepository, Map[Long, Long]] =
    ZIO.serviceWithZIO[GameRepository](_.playCounts(gameIds))

  def findPlay(id: Long): RIO[GameRepository, Option[GamePlayRow]] =
    ZIO.serviceWithZIO[GameRepository](_.findPlay(id))

  def eligibleWordPairs(
    gameId: Long,
    sourceLanguage: String,
    targetLanguage: String,
  ): RIO[GameRepository, List[(Long, Long)]] =
    ZIO.serviceWithZIO[GameRepository](_.eligibleWordPairs(gameId, sourceLanguage, targetLanguage))

  def eligibleWordPairsForTags(
    tagIds: List[Long],
    sourceLanguage: String,
    targetLanguage: String,
  ): RIO[GameRepository, List[(Long, Long)]] =
    ZIO.serviceWithZIO[GameRepository](_.eligibleWordPairsForTags(tagIds, sourceLanguage, targetLanguage))

  def insertPlay(row: GamePlayRow, wordPairs: List[(Long, Long)]): RIO[GameRepository, GamePlayRow] =
    ZIO.serviceWithZIO[GameRepository](_.insertPlay(row, wordPairs))

  def wordPairsOf(playId: Long): RIO[GameRepository, List[(Long, Long)]] =
    ZIO.serviceWithZIO[GameRepository](_.wordPairsOf(playId))

  def wordPoolOf(gameId: Long): RIO[GameRepository, List[(Long, Long)]] =
    ZIO.serviceWithZIO[GameRepository](_.wordPoolOf(gameId))

  def replaceGameWordPool(gameId: Long, pairs: List[(Long, Long)]): RIO[GameRepository, Unit] =
    ZIO.serviceWithZIO[GameRepository](_.replaceGameWordPool(gameId, pairs))

  def answersOf(playId: Long): RIO[GameRepository, List[GamePlayAnswerRow]] =
    ZIO.serviceWithZIO[GameRepository](_.answersOf(playId))

  def recordAnswer(
    answer: GamePlayAnswerRow,
    newScore: Int,
    finishedAt: Option[Long],
  ): RIO[GameRepository, Unit] =
    ZIO.serviceWithZIO[GameRepository](_.recordAnswer(answer, newScore, finishedAt))

  def wordsByIds(ids: List[Long]): RIO[GameRepository, List[WordRow]] =
    ZIO.serviceWithZIO[GameRepository](_.wordsByIds(ids))

  val live: ZLayer[DataSource, Nothing, GameRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GameRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): GameRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, GameRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GameRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): GameRepository
  )
}

final class GameRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with GameRepository {
  import ctx._

  private inline def games           = quote(querySchema[GameRow]("games"))
  private inline def gameTags        = quote(querySchema[GameTagRow]("game_tags"))
  private inline def words           = quote(querySchema[WordRow]("words"))
  private inline def tags            = quote(querySchema[TagRow]("tags"))
  private inline def wordTagPairs    = quote(querySchema[WordTagPairRow]("word_tag_pairs"))
  private inline def gamePlays       = quote(querySchema[GamePlayRow]("game_plays"))
  private inline def gamePlayAnswers = quote(querySchema[GamePlayAnswerRow]("game_play_answers"))
  private inline def gamePlayWords   = quote(querySchema[GamePlayWordRow]("game_play_words"))
  private inline def gameWordPool    = quote(querySchema[GameWordPoolRow]("game_word_pool"))

  def findBySlug(slug: String): Task[Option[GameRow]] = {
    logged(run(ctx.run(quote(games.filter(_.slug == lift(slug))))).map(_.headOption)) { found =>
      s"games.findBySlug found=${found.isDefined}"
    }
  }

  def findGame(id: Long): Task[Option[GameRow]] = {
    logged(run(ctx.run(quote(games.filter(_.id == lift(id))))).map(_.headOption)) { found =>
      s"games.findGame found=${found.isDefined}"
    }
  }

  def eligibleTags(sourceLanguage: String, targetLanguage: String): Task[List[(TagRow, Long)]] = {
    val q = quote {
      (for {
        pair   <- wordTagPairs
        source <- words.join(w => w.id == pair.wordId && w.language == lift(sourceLanguage))
        target <- words.join(w => w.id == pair.translationWordId && w.language == lift(targetLanguage))
        tag    <- tags.join(t => t.id == pair.tagId)
      } yield (tag, pair.wordId)).distinct
    }
    logged(run(ctx.run(q))) { rows =>
      s"games.eligibleTags source=$sourceLanguage target=$targetLanguage rows=${rows.size}"
    }
  }

  def insertGame(row: GameRow, tagIds: List[Long], wordPool: List[(Long, Long)] = Nil): Task[GameRow] = {
    val inserted = transaction(
      for {
        id <- ctx.run(quote(games.insertValue(lift(row)).returningGenerated(_.id)))
        _  <- ZIO.unless(tagIds.isEmpty) {
                val links = tagIds.map(tagId => GameTagRow(0L, id, tagId))
                ctx.run(quote {
                  liftQuery(links).foreach(row => gameTags.insert(_.gameId -> row.gameId, _.tagId -> row.tagId))
                })
              }
        _  <- ZIO.unless(wordPool.isEmpty) {
                val links = wordPool.map { case (wordId, translationWordId) =>
                  GameWordPoolRow(0L, id, wordId, translationWordId)
                }
                ctx.run(quote {
                  liftQuery(links).foreach(row => {
                    gameWordPool.insert(
                      _.gameId            -> row.gameId,
                      _.wordId            -> row.wordId,
                      _.translationWordId -> row.translationWordId,
                    )
                 })
                })
              }
      } yield id
    )
    logged(inserted.map(id => row.copy(id = id))) { game =>
      s"games.insert id=${game.id} owner=${row.ownerUserId} tags=${tagIds.size} pool=${wordPool.size}"
    }
  }

  def tagsOf(gameId: Long): Task[List[TagRow]] = {
    val q = quote {
      gameTags.filter(_.gameId == lift(gameId)).join(tags).on((link, tag) => link.tagId == tag.id).map {
        case (_, tag) => tag
      }
    }
    logged(run(ctx.run(q)))(rows => s"games.tagsOf id=$gameId rows=${rows.size}")
  }

  def rename(id: Long, name: String, updatedAt: Long): Task[Long] = {
    val q = quote {
      games.filter(_.id == lift(id)).update(_.name -> lift(name), _.updatedAt -> lift(updatedAt))
    }
    logged(run(ctx.run(q)))(rows => s"games.rename id=$id rows=$rows")
  }

  def gamesByOwner(ownerUserId: Long): Task[List[GameRow]] = {
    val q = quote {
      games.filter(_.ownerUserId == lift(ownerUserId)).sortBy(_.createdAt)(using Ord.desc)
    }
    logged(run(ctx.run(q)))(rows => s"games.gamesByOwner owner=$ownerUserId rows=${rows.size}")
  }

  def playCounts(gameIds: List[Long]): Task[Map[Long, Long]] = {
    if (gameIds.isEmpty)
      ZIO.succeed(Map.empty)
    else {
      val q = quote {
        gamePlays
          .filter(play => liftQuery(gameIds).contains(play.gameId))
          .groupBy(play => play.gameId)
          .map { case (gameId, plays) => (gameId, plays.size) }
      }
      logged(run(ctx.run(q)).map(_.toMap))(counts => s"games.playCounts games=${gameIds.size} rows=${counts.size}")
    }
  }

  def findPlay(id: Long): Task[Option[GamePlayRow]] = {
    logged(run(ctx.run(quote(gamePlays.filter(_.id == lift(id))))).map(_.headOption)) { found =>
      s"games.findPlay found=${found.isDefined}"
    }
  }

  def eligibleWordPairs(gameId: Long, sourceLanguage: String, targetLanguage: String): Task[List[(Long, Long)]] = {
    val q = quote {
      for {
        link   <- gameTags.filter(_.gameId == lift(gameId))
        pair   <- wordTagPairs.join(p => p.tagId == link.tagId)
        source <- words.join(w => w.id == pair.wordId && w.language == lift(sourceLanguage))
        target <- words.join(w => w.id == pair.translationWordId && w.language == lift(targetLanguage))
      } yield (pair.wordId, pair.translationWordId)
    }
    logged(run(ctx.run(q))) { rows =>
      s"games.eligibleWordPairs game=$gameId source=$sourceLanguage target=$targetLanguage rows=${rows.size}"
    }
  }

  def eligibleWordPairsForTags(
    tagIds: List[Long],
    sourceLanguage: String,
    targetLanguage: String,
  ): Task[List[(Long, Long)]] = {
    if (tagIds.isEmpty)
      ZIO.succeed(Nil)
    else {
      val q = quote {
        for {
          pair   <- wordTagPairs.filter(p => liftQuery(tagIds).contains(p.tagId))
          source <- words.join(w => w.id == pair.wordId && w.language == lift(sourceLanguage))
          target <- words.join(w => w.id == pair.translationWordId && w.language == lift(targetLanguage))
        } yield (pair.wordId, pair.translationWordId)
      }
      logged(run(ctx.run(q))) { rows =>
        s"games.eligibleWordPairsForTags tags=${tagIds.size} source=$sourceLanguage target=$targetLanguage rows=${rows.size}"
      }
    }
  }

  def insertPlay(row: GamePlayRow, wordPairs: List[(Long, Long)]): Task[GamePlayRow] = {
    val inserted = transaction(
      for {
        id <- ctx.run(quote(gamePlays.insertValue(lift(row)).returningGenerated(_.id)))
        _  <- ZIO.unless(wordPairs.isEmpty) {
                val links = wordPairs.map { case (wordId, translationWordId) =>
                  GamePlayWordRow(0L, id, wordId, translationWordId)
                }
                ctx.run(quote {
                  liftQuery(links).foreach(row => {
                    gamePlayWords.insert(
                      _.playId            -> row.playId,
                      _.wordId            -> row.wordId,
                      _.translationWordId -> row.translationWordId,
                    )
                  })
                })
              }
      } yield id
    )
    logged(inserted.map(id => row.copy(id = id))) { play =>
      s"games.insertPlay id=${play.id} game=${play.gameId} player=${play.playerUserId} words=${play.wordCount}"
    }
  }

  def wordPairsOf(playId: Long): Task[List[(Long, Long)]] = {
    val q = quote {
      gamePlayWords.filter(_.playId == lift(playId)).map(row => (row.wordId, row.translationWordId))
    }
    logged(run(ctx.run(q)))(rows => s"games.wordPairsOf play=$playId rows=${rows.size}")
  }

  def wordPoolOf(gameId: Long): Task[List[(Long, Long)]] = {
    val q = quote {
      gameWordPool.filter(_.gameId == lift(gameId)).map(row => (row.wordId, row.translationWordId))
    }
    logged(run(ctx.run(q)))(rows => s"games.wordPoolOf game=$gameId rows=${rows.size}")
  }

  def replaceGameWordPool(gameId: Long, pairs: List[(Long, Long)]): Task[Unit] = {
    val effect = transaction(
      for {
        _ <- ctx.run(quote(gameWordPool.filter(_.gameId == lift(gameId)).delete))
        _ <- ZIO.unless(pairs.isEmpty) {
               val links = pairs.map { case (wordId, translationWordId) =>
                 GameWordPoolRow(0L, gameId, wordId, translationWordId)
               }
               ctx.run(quote {
                 liftQuery(links).foreach(row => {
                   gameWordPool.insert(
                     _.gameId            -> row.gameId,
                     _.wordId            -> row.wordId,
                     _.translationWordId -> row.translationWordId,
                   )
                 })
               })
             }
      } yield ()
    )
    logged(effect) { _ => s"games.replaceGameWordPool game=$gameId rows=${pairs.size}" }
  }

  def answersOf(playId: Long): Task[List[GamePlayAnswerRow]] = {
    val q = quote {
      gamePlayAnswers.filter(_.playId == lift(playId)).sortBy(_.position)
    }
    logged(run(ctx.run(q)))(rows => s"games.answersOf play=$playId rows=${rows.size}")
  }

  def recordAnswer(answer: GamePlayAnswerRow, newScore: Int, finishedAt: Option[Long]): Task[Unit] = {
    val effect = transaction(
      for {
        _ <- ctx.run(quote(gamePlayAnswers.insertValue(lift(answer)).returningGenerated(_.id)))
        _ <- finishedAt match {
               case Some(_) =>
                 ctx.run(
                   quote(
                     gamePlays
                       .filter(_.id == lift(answer.playId))
                       .update(_.score -> lift(newScore), _.finishedAt -> lift(finishedAt))
                   )
                 )
               case None    =>
                 ctx.run(quote(gamePlays.filter(_.id == lift(answer.playId)).update(_.score -> lift(newScore))))
             }
      } yield ()
    )
    logged(effect) { _ =>
      s"games.recordAnswer play=${answer.playId} position=${answer.position} outcome=${answer.outcome} " +
        s"finished=${finishedAt.isDefined}"
    }
  }

  def wordsByIds(ids: List[Long]): Task[List[WordRow]] = {
    val q = quote {
      words.filter(w => liftQuery(ids).contains(w.id))
    }
    logged(run(ctx.run(q)))(rows => s"games.wordsByIds requested=${ids.size} rows=${rows.size}")
  }
}
