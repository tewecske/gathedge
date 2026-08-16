package gathedge.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** `games` and `game_tags` — creating a game and reading it back. `game_plays`/`game_play_answers` get no methods here
  * yet; nothing writes to them until the play-session feature does.
  *
  * '''Nothing here logs a game's name or slug.''' A slug is a public identifier, not a credential, but a game's name is
  * free text somebody typed — the same rule [[QuillRepository.logged]] states for a tag name applies here: log ids and
  * counts.
  */
trait GameRepository {

  def findBySlug(slug: String): Task[Option[GameRow]]

  /** Tags carrying at least one `word_tag_pairs` row whose source word is `sourceLanguage` and whose marked translation
    * word is `targetLanguage` — the set a game may be built from. Distinct on tag id; which order they come back in is
    * the caller's job, not this query's.
    */
  def eligibleTags(sourceLanguage: String, targetLanguage: String): Task[List[TagRow]]

  /** Inserts `row` and one `game_tags` row per id in `tagIds`, as one unit of work: a game with only some of its tags
    * recorded is not a game anybody asked to create.
    */
  def insertGame(row: GameRow, tagIds: List[Long]): Task[GameRow]

  def tagsOf(gameId: Long): Task[List[TagRow]]

  /** Rows affected — `0` means `id` does not exist. Ownership is the service's job: this only writes. */
  def rename(id: Long, name: String, updatedAt: Long): Task[Long]
}

object GameRepository {

  def findBySlug(slug: String): RIO[GameRepository, Option[GameRow]] =
    ZIO.serviceWithZIO[GameRepository](_.findBySlug(slug))

  def eligibleTags(sourceLanguage: String, targetLanguage: String): RIO[GameRepository, List[TagRow]] =
    ZIO.serviceWithZIO[GameRepository](_.eligibleTags(sourceLanguage, targetLanguage))

  def insertGame(row: GameRow, tagIds: List[Long]): RIO[GameRepository, GameRow] =
    ZIO.serviceWithZIO[GameRepository](_.insertGame(row, tagIds))

  def tagsOf(gameId: Long): RIO[GameRepository, List[TagRow]] =
    ZIO.serviceWithZIO[GameRepository](_.tagsOf(gameId))

  def rename(id: Long, name: String, updatedAt: Long): RIO[GameRepository, Long] =
    ZIO.serviceWithZIO[GameRepository](_.rename(id, name, updatedAt))

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

  private inline def games        = quote(querySchema[GameRow]("games"))
  private inline def gameTags     = quote(querySchema[GameTagRow]("game_tags"))
  private inline def words        = quote(querySchema[WordRow]("words"))
  private inline def tags         = quote(querySchema[TagRow]("tags"))
  private inline def wordTagPairs = quote(querySchema[WordTagPairRow]("word_tag_pairs"))

  def findBySlug(slug: String): Task[Option[GameRow]] = {
    logged(run(ctx.run(quote(games.filter(_.slug == lift(slug))))).map(_.headOption)) { found =>
      s"games.findBySlug found=${found.isDefined}"
    }
  }

  def eligibleTags(sourceLanguage: String, targetLanguage: String): Task[List[TagRow]] = {
    val q = quote {
      (for {
        pair   <- wordTagPairs
        source <- words.join(w => w.id == pair.wordId && w.language == lift(sourceLanguage))
        target <- words.join(w => w.id == pair.translationWordId && w.language == lift(targetLanguage))
        tag    <- tags.join(t => t.id == pair.tagId)
      } yield tag).distinct
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
}
