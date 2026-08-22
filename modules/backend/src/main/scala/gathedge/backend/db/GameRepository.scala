package gathedge.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import gathedge.shared.dto.GamePlaySort
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

  /** Inserts `row` and one `game_tags` row per id in `tagIds`, as one unit of work — a game whose row landed but whose
    * tags didn't is not a state anything downstream can make sense of.
    */
  def insertGame(row: GameRow, tagIds: List[Long]): Task[GameRow]

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
    * setup screen's word-list preview reads before a game (and its `game_tags` rows) exist at all.
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

  /** Every answer recorded for `playId` so far, in the order they were answered. */
  def answersOf(playId: Long): Task[List[GamePlayAnswerRow]]

  /** This player's `(word_id, outcome)` for every answer recorded against `gameId`, restricted to plays whose own
    * `source_language`/`target_language` match the given direction — see [[GamePlayRow]]'s doc comment on why a play's
    * variant, not the game's, decides direction. One row per answer, not deduped or aggregated: turning this into
    * "played at all" / "how many mistakes" per word is [[gathedge.backend.service.GameService]]'s job, the same split
    * this file draws for [[eligibleWordPairs]]'s dedup.
    */
  def answerOutcomesFor(
    gameId: Long,
    playerUserId: Long,
    sourceLanguage: String,
    targetLanguage: String,
  ): Task[List[(Long, String)]]

  /** Inserts `answer` and updates `game_plays.score` (and `finished_at`, when given) in one transaction — a play whose
    * answer landed but whose running score did not update is not a state either side of this should ever observe.
    */
  def recordAnswer(answer: GamePlayAnswerRow, newScore: Int, finishedAt: Option[Long]): Task[Unit]

  def wordsByIds(ids: List[Long]): Task[List[WordRow]]

  /** One page of `gameId`'s plays, ordered by `sort` (a `dto.GamePlaySort` value; anything else falls back to newest
    * first) and narrowed to players whose address contains `playerContains`. Paged in SQL — see [[countPlaysMatching]]
    * for the other half. Player identity is resolved separately, via [[usersByIds]], the same split [[wordsByIds]]
    * already draws for answer text — a play row alone is never enough to render.
    */
  def listPlaysPage(
    gameId: Long,
    offset: Int,
    limit: Int,
    playerContains: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): Task[List[GamePlayRow]]

  /** How many of `gameId`'s plays [[listPlaysPage]] would return across every page. */
  def countPlaysMatching(gameId: Long, playerContains: Option[String]): Task[Long]

  /** `playId` if it belongs to `gameId`, so an owner can never be handed a play id that belongs to somebody else's game
    * by knowing only its bare number.
    */
  def findPlayInGame(gameId: Long, playId: Long): Task[Option[GamePlayRow]]

  def usersByIds(ids: List[Long]): Task[List[UserRow]]

  def gamesByIds(ids: List[Long]): Task[List[GameRow]]

  /** One page of `playerUserId`'s own plays across every game, most recently started first unless `sort` says otherwise
    * — the cross-game history [[listPlaysPage]] does not answer, since that one is scoped to a single game and its
    * owner. `gameId` narrows to one game; `trackedOnly` narrows to games whose owner turned on `trackResults` — `false`
    * for the player's own "my history" view (their own data needs no such gate), `true` for a viewer reading a share,
    * or an admin reading anyone's, both of which respect the same rule a game's own owner is bound by.
    */
  def listMyPlaysPage(
    playerUserId: Long,
    gameId: Option[Long],
    trackedOnly: Boolean,
    offset: Int,
    limit: Int,
    sort: Option[String],
    descending: Boolean,
  ): Task[List[GamePlayRow]]

  /** How many of `playerUserId`'s plays [[listMyPlaysPage]] would return across every page. */
  def countMyPlaysMatching(playerUserId: Long, gameId: Option[Long], trackedOnly: Boolean): Task[Long]
}

object GameRepository {

  def findBySlug(slug: String): RIO[GameRepository, Option[GameRow]] =
    ZIO.serviceWithZIO[GameRepository](_.findBySlug(slug))

  def findGame(id: Long): RIO[GameRepository, Option[GameRow]] =
    ZIO.serviceWithZIO[GameRepository](_.findGame(id))

  def eligibleTags(sourceLanguage: String, targetLanguage: String): RIO[GameRepository, List[(TagRow, Long)]] =
    ZIO.serviceWithZIO[GameRepository](_.eligibleTags(sourceLanguage, targetLanguage))

  def insertGame(row: GameRow, tagIds: List[Long]): RIO[GameRepository, GameRow] =
    ZIO.serviceWithZIO[GameRepository](_.insertGame(row, tagIds))

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

  def answersOf(playId: Long): RIO[GameRepository, List[GamePlayAnswerRow]] =
    ZIO.serviceWithZIO[GameRepository](_.answersOf(playId))

  def answerOutcomesFor(
    gameId: Long,
    playerUserId: Long,
    sourceLanguage: String,
    targetLanguage: String,
  ): RIO[GameRepository, List[(Long, String)]] =
    ZIO.serviceWithZIO[GameRepository](_.answerOutcomesFor(gameId, playerUserId, sourceLanguage, targetLanguage))

  def recordAnswer(
    answer: GamePlayAnswerRow,
    newScore: Int,
    finishedAt: Option[Long],
  ): RIO[GameRepository, Unit] =
    ZIO.serviceWithZIO[GameRepository](_.recordAnswer(answer, newScore, finishedAt))

  def wordsByIds(ids: List[Long]): RIO[GameRepository, List[WordRow]] =
    ZIO.serviceWithZIO[GameRepository](_.wordsByIds(ids))

  def listPlaysPage(
    gameId: Long,
    offset: Int,
    limit: Int,
    playerContains: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): RIO[GameRepository, List[GamePlayRow]] =
    ZIO.serviceWithZIO[GameRepository](_.listPlaysPage(gameId, offset, limit, playerContains, sort, descending))

  def countPlaysMatching(gameId: Long, playerContains: Option[String]): RIO[GameRepository, Long] =
    ZIO.serviceWithZIO[GameRepository](_.countPlaysMatching(gameId, playerContains))

  def findPlayInGame(gameId: Long, playId: Long): RIO[GameRepository, Option[GamePlayRow]] =
    ZIO.serviceWithZIO[GameRepository](_.findPlayInGame(gameId, playId))

  def usersByIds(ids: List[Long]): RIO[GameRepository, List[UserRow]] =
    ZIO.serviceWithZIO[GameRepository](_.usersByIds(ids))

  def gamesByIds(ids: List[Long]): RIO[GameRepository, List[GameRow]] =
    ZIO.serviceWithZIO[GameRepository](_.gamesByIds(ids))

  def listMyPlaysPage(
    playerUserId: Long,
    gameId: Option[Long],
    trackedOnly: Boolean,
    offset: Int,
    limit: Int,
    sort: Option[String],
    descending: Boolean,
  ): RIO[GameRepository, List[GamePlayRow]] = {
    ZIO.serviceWithZIO[GameRepository](
      _.listMyPlaysPage(playerUserId, gameId, trackedOnly, offset, limit, sort, descending)
    )
  }

  def countMyPlaysMatching(playerUserId: Long, gameId: Option[Long], trackedOnly: Boolean): RIO[GameRepository, Long] =
    ZIO.serviceWithZIO[GameRepository](_.countMyPlaysMatching(playerUserId, gameId, trackedOnly))

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
  // Read-only view of a table `UserRepository` owns, for the one question only this repository can ask: which
  // player played a tracked game. Reading another repository's tables is fine — see `UserRepository`'s own note
  // on this for `findAbandonedGuests`. The lambda parameter is `row`, never `user` — Postgres reserved word.
  private inline def users           = quote(querySchema[UserRow]("users"))

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

  def insertGame(row: GameRow, tagIds: List[Long]): Task[GameRow] = {
    val inserted = transaction(
      for {
        id <- ctx.run(quote(games.insertValue(lift(row)).returningGenerated(_.id)))
        _  <- ZIO.unless(tagIds.isEmpty) {
                val links = tagIds.map(tagId => GameTagRow(0L, id, tagId))
                ctx.run(quote {
                  liftQuery(links).foreach(row => gameTags.insert(_.gameId -> row.gameId, _.tagId -> row.tagId))
                })
              }
      } yield id
    )
    logged(inserted.map(id => row.copy(id = id))) { game =>
      s"games.insert id=${game.id} owner=${row.ownerUserId} tags=${tagIds.size}"
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

  def answersOf(playId: Long): Task[List[GamePlayAnswerRow]] = {
    val q = quote {
      gamePlayAnswers.filter(_.playId == lift(playId)).sortBy(_.position)
    }
    logged(run(ctx.run(q)))(rows => s"games.answersOf play=$playId rows=${rows.size}")
  }

  def answerOutcomesFor(
    gameId: Long,
    playerUserId: Long,
    sourceLanguage: String,
    targetLanguage: String,
  ): Task[List[(Long, String)]] = {
    val q = quote {
      for {
        play   <- gamePlays.filter(p => {
                    p.gameId == lift(gameId) && p.playerUserId == lift(playerUserId) &&
                    p.sourceLanguage == lift(sourceLanguage) && p.targetLanguage == lift(targetLanguage)
                  })
        answer <- gamePlayAnswers.join(a => a.playId == play.id)
      } yield (answer.wordId, answer.outcome)
    }
    logged(run(ctx.run(q))) { rows =>
      s"games.answerOutcomesFor game=$gameId player=$playerUserId rows=${rows.size}"
    }
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

  /** The `LIKE` pattern behind the owner-facing player filter, or `None` when it is empty — same shape as
    * `UserRepository.emailPattern`.
    */
  private def playerPattern(playerContains: Option[String]): Option[String] = {
    playerContains.map(_.trim.toLowerCase).filter(_.nonEmpty).map(needle => s"%$needle%")
  }

  /** The rows a page is cut from, before ordering: the narrowing [[listPlaysPage]] and [[countPlaysMatching]] have to
    * share, or the total would count a different set than the page shows. The player filter is a correlated subquery
    * against `users` rather than a join — the same shape `WordRepository.taggedByUser` uses to narrow by another table
    * without joining a `DynamicQuery`, which has no precedent in this codebase.
    */
  private def matchingPlays(gameId: Long, playerContains: Option[String]): DynamicQuery[GamePlayRow] = {
    dynamicQuerySchema[GamePlayRow]("game_plays")
      .filterOpt(Some(gameId))((play, id) => quote(play.gameId == unquote(id)))
      .filterOpt(playerPattern(playerContains))((play, pattern) => {
        quote(
          users
            .filter(row => row.id == play.playerUserId && row.email.exists(address => address.like(unquote(pattern))))
            .nonEmpty
        )
      })
  }

  /** The `dto.GamePlaySort` vocabulary translated to an `ORDER BY`; anything else falls back to newest first, the same
    * default `UserRepository.ordered` uses. There is no "player" case — sorting by player would need the same
    * unprecedented dynamic join [[matchingPlays]] avoids for filtering, so that column is filterable only, the same
    * split the user list's sign-in badge and the audit trail's target already draw.
    */
  private def orderedPlays(
    query: DynamicQuery[GamePlayRow],
    sort: Option[String],
    descending: Boolean,
  ): DynamicQuery[GamePlayRow] = {
    sort match {
      case Some(GamePlaySort.score)     =>
        query.sortBy(_.score)(using ordering(descending))
      case Some(GamePlaySort.wordCount) =>
        query.sortBy(_.wordCount)(using ordering(descending))
      case Some(GamePlaySort.startedAt) =>
        query.sortBy(_.startedAt)(using ordering(descending))
      case _                            =>
        query.sortBy(_.startedAt)(using Ord.desc)
    }
  }

  def listPlaysPage(
    gameId: Long,
    offset: Int,
    limit: Int,
    playerContains: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): Task[List[GamePlayRow]] = {
    val page = orderedPlays(matchingPlays(gameId, playerContains), sort, descending).drop(offset).take(limit)
    // The player filter is a fragment of somebody's address, so it stays out of the message like every other one.
    logged(run(ctx.run(page))) { rows =>
      s"games.listPlaysPage game=$gameId offset=$offset limit=$limit sort=${sort.getOrElse("-")} rows=${rows.size}"
    }
  }

  def countPlaysMatching(gameId: Long, playerContains: Option[String]): Task[Long] = {
    logged(run(ctx.run(matchingPlays(gameId, playerContains).size))) { count =>
      s"games.countPlaysMatching game=$gameId count=$count"
    }
  }

  def findPlayInGame(gameId: Long, playId: Long): Task[Option[GamePlayRow]] = {
    val q = quote(gamePlays.filter(play => play.id == lift(playId) && play.gameId == lift(gameId)))
    logged(run(ctx.run(q)).map(_.headOption)) { found =>
      s"games.findPlayInGame game=$gameId found=${found.isDefined}"
    }
  }

  def usersByIds(ids: List[Long]): Task[List[UserRow]] = {
    val q = quote {
      users.filter(row => liftQuery(ids).contains(row.id))
    }
    logged(run(ctx.run(q)))(rows => s"games.usersByIds requested=${ids.size} rows=${rows.size}")
  }

  def gamesByIds(ids: List[Long]): Task[List[GameRow]] = {
    val q = quote {
      games.filter(g => liftQuery(ids).contains(g.id))
    }
    logged(run(ctx.run(q)))(rows => s"games.gamesByIds requested=${ids.size} rows=${rows.size}")
  }

  /** The rows a page of a player's cross-game history is cut from, before ordering — the same "narrowing the two paged
    * methods share" split [[matchingPlays]] draws for the owner-facing listing. `trackedOnly` is a correlated subquery
    * against `games`, the same shape [[matchingPlays]] uses against `users` for its player filter, rather than a join —
    * there is no precedent in this codebase for joining a `DynamicQuery`.
    */
  private def matchingMyPlays(
    playerUserId: Long,
    gameId: Option[Long],
    trackedOnly: Boolean,
  ): DynamicQuery[GamePlayRow] = {
    dynamicQuerySchema[GamePlayRow]("game_plays")
      .filterOpt(Some(playerUserId))((play, id) => quote(play.playerUserId == unquote(id)))
      .filterOpt(gameId)((play, id) => quote(play.gameId == unquote(id)))
      .filterOpt(Option.when(trackedOnly)(true))((play, _) =>
        quote(games.filter(g => g.id == play.gameId && g.trackResults).nonEmpty)
      )
  }

  def listMyPlaysPage(
    playerUserId: Long,
    gameId: Option[Long],
    trackedOnly: Boolean,
    offset: Int,
    limit: Int,
    sort: Option[String],
    descending: Boolean,
  ): Task[List[GamePlayRow]] = {
    val page =
      orderedPlays(matchingMyPlays(playerUserId, gameId, trackedOnly), sort, descending).drop(offset).take(limit)
    logged(run(ctx.run(page))) { rows =>
      s"games.listMyPlaysPage player=$playerUserId offset=$offset limit=$limit sort=${sort.getOrElse("-")} rows=${rows.size}"
    }
  }

  def countMyPlaysMatching(playerUserId: Long, gameId: Option[Long], trackedOnly: Boolean): Task[Long] = {
    logged(run(ctx.run(matchingMyPlays(playerUserId, gameId, trackedOnly).size))) { count =>
      s"games.countMyPlaysMatching player=$playerUserId count=$count"
    }
  }
}
