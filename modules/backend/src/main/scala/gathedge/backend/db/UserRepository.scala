package gathedge.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import gathedge.shared.dto.UserSort
import zio.*

import javax.sql.DataSource

/** Dialect-independent interface. [[UserRepository.live]] backs production (Postgres), [[UserRepository.test]] backs
  * tests (SQLite) — see the plan's "dual-dialect DB strategy". Both wrap the same [[UserRepositoryLive]] below and are
  * swapped in purely via ZLayer wiring.
  */
trait UserRepository {
  def insert(
    email: String,
    passwordHash: Option[String],
    isAdmin: Boolean,
    theme: String,
    locale: String,
    createdAt: Long,
    emailVerifiedAt: Option[Long],
  ): Task[UserRow]

  /** An account with no address and no password, minted the first time a visitor tags a word. */
  def insertGuest(theme: String, locale: String, createdAt: Long): Task[UserRow]

  /** Gives a guest account an address and a password, in place. Nothing it owns moves, which is the whole point.
    * Returns rows affected, so a caller can tell a lost race from a success.
    */
  def upgradeGuest(id: Long, email: String, passwordHash: String, emailVerifiedAt: Option[Long]): Task[Long]

  /** Cannot return a guest: their address is NULL, and nothing equals NULL. */
  def findByEmail(email: String): Task[Option[UserRow]]
  def findById(id: Long): Task[Option[UserRow]]

  /** Batch form of [[findById]] — what `ProgressShareService` resolves a page of shares' other-side accounts with, the
    * same "resolve identity separately" split `GameRepository.usersByIds` draws for a play's player.
    */
  def findByIds(ids: List[Long]): Task[List[UserRow]]

  /** Guest accounts that have nothing on them and have been idle since `createdBefore`, oldest first.
    *
    * What `SessionReaper` sweeps. A guest holding tags is never in this list however long its session has been gone,
    * because a transfer code may be written down on somebody's desk.
    */
  def findAbandonedGuests(createdBefore: Long, limit: Int): Task[List[Long]]
  def updateTheme(userId: Long, theme: String): Task[Unit]
  def updateLocale(userId: Long, locale: String): Task[Unit]

  /** Idempotent: re-verifying an already-verified account just rewrites the timestamp. */
  def markEmailVerified(userId: Long, verifiedAt: Long): Task[Unit]
  def existsAdmin: Task[Boolean]

  /** One page of accounts, ordered by `sort` (a `dto.UserSort` value; anything else falls back to newest first) and
    * narrowed to addresses containing `emailContains`. Paged in SQL — see [[countMatching]] for the other half.
    */
  def listPage(
    offset: Int,
    limit: Int,
    emailContains: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): Task[List[UserRow]]

  /** How many accounts [[listPage]] would return across every page, which is what the page buttons are counted off. */
  def countMatching(emailContains: Option[String]): Task[Long]

  /** Updates email/admin flag only if the row exists. Returns rows affected. */
  def updateProfile(id: Long, email: String, isAdmin: Boolean): Task[Long]
  def updatePasswordHash(id: Long, passwordHash: String): Task[Unit]

  /** Both updates as one unit of work — `passwordHash` of `None` leaves the password alone. Returns rows affected by
    * the profile update.
    */
  def updateProfileAndPassword(id: Long, email: String, isAdmin: Boolean, passwordHash: Option[String]): Task[Long]
  def deleteById(id: Long): Task[Long]
}

/** Accessors, so a caller writes `UserRepository.findById(id)` instead of pulling the repository out of the environment
  * first. Every repository in this package has one, alongside its two `ZLayer`s — `live` (Postgres) and `test`
  * (SQLite).
  */
object UserRepository {
  def insert(
    email: String,
    passwordHash: Option[String],
    isAdmin: Boolean,
    theme: String,
    locale: String,
    createdAt: Long,
    emailVerifiedAt: Option[Long],
  ): RIO[UserRepository, UserRow] = {
    ZIO.serviceWithZIO[UserRepository](
      _.insert(email, passwordHash, isAdmin, theme, locale, createdAt, emailVerifiedAt)
    )
  }

  def insertGuest(theme: String, locale: String, createdAt: Long): RIO[UserRepository, UserRow] =
    ZIO.serviceWithZIO[UserRepository](_.insertGuest(theme, locale, createdAt))

  def upgradeGuest(
    id: Long,
    email: String,
    passwordHash: String,
    emailVerifiedAt: Option[Long],
  ): RIO[UserRepository, Long] =
    ZIO.serviceWithZIO[UserRepository](_.upgradeGuest(id, email, passwordHash, emailVerifiedAt))

  def findByEmail(email: String): RIO[UserRepository, Option[UserRow]] =
    ZIO.serviceWithZIO[UserRepository](_.findByEmail(email))

  def findAbandonedGuests(createdBefore: Long, limit: Int): RIO[UserRepository, List[Long]] =
    ZIO.serviceWithZIO[UserRepository](_.findAbandonedGuests(createdBefore, limit))

  def findById(id: Long): RIO[UserRepository, Option[UserRow]] =
    ZIO.serviceWithZIO[UserRepository](_.findById(id))

  def findByIds(ids: List[Long]): RIO[UserRepository, List[UserRow]] =
    ZIO.serviceWithZIO[UserRepository](_.findByIds(ids))

  def updateTheme(userId: Long, theme: String): RIO[UserRepository, Unit] =
    ZIO.serviceWithZIO[UserRepository](_.updateTheme(userId, theme))

  def updateLocale(userId: Long, locale: String): RIO[UserRepository, Unit] =
    ZIO.serviceWithZIO[UserRepository](_.updateLocale(userId, locale))

  def markEmailVerified(userId: Long, verifiedAt: Long): RIO[UserRepository, Unit] =
    ZIO.serviceWithZIO[UserRepository](_.markEmailVerified(userId, verifiedAt))

  def existsAdmin: RIO[UserRepository, Boolean] =
    ZIO.serviceWithZIO[UserRepository](_.existsAdmin)

  def listPage(
    offset: Int,
    limit: Int,
    emailContains: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): RIO[UserRepository, List[UserRow]] =
    ZIO.serviceWithZIO[UserRepository](_.listPage(offset, limit, emailContains, sort, descending))

  def countMatching(emailContains: Option[String]): RIO[UserRepository, Long] =
    ZIO.serviceWithZIO[UserRepository](_.countMatching(emailContains))

  def updateProfile(id: Long, email: String, isAdmin: Boolean): RIO[UserRepository, Long] =
    ZIO.serviceWithZIO[UserRepository](_.updateProfile(id, email, isAdmin))

  def updatePasswordHash(id: Long, passwordHash: String): RIO[UserRepository, Unit] =
    ZIO.serviceWithZIO[UserRepository](_.updatePasswordHash(id, passwordHash))

  def updateProfileAndPassword(
    id: Long,
    email: String,
    isAdmin: Boolean,
    passwordHash: Option[String],
  ): RIO[UserRepository, Long] =
    ZIO.serviceWithZIO[UserRepository](_.updateProfileAndPassword(id, email, isAdmin, passwordHash))

  def deleteById(id: Long): RIO[UserRepository, Long] =
    ZIO.serviceWithZIO[UserRepository](_.deleteById(id))

  val live: ZLayer[DataSource, Nothing, UserRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new UserRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): UserRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, UserRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new UserRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): UserRepository
  )
}

/** Dialect-generic implementation shared by both Postgres and SQLite. Quill's `ctx.run` dispatches SQL rendering off
  * `ctx.idiom` at runtime, so a single quoted-query body works for any `ZioJdbcContext[Dialect, Naming]` — no need to
  * hand-duplicate the query bodies per dialect, only the context instance differs (see `live`/`test` above). Every
  * repository in this package is built the same way.
  */
final class UserRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with UserRepository {
  import ctx._

  private inline def users = quote(querySchema[UserRow]("users"))

  // Read-only views of tables `WordRepository` owns, for the one question only this repository can ask: which guest
  // accounts have nothing on them. Reading another repository's tables is fine — what does not compose across
  // repositories is a *transaction*, see `QuillRepository`.
  private inline def tagRows         = quote(querySchema[TagRow]("tags"))
  private inline def wordRows        = quote(querySchema[WordRow]("words"))
  private inline def translationRows = quote(querySchema[WordTranslationRow]("word_translations"))
  private inline def guestClaimCodes = quote(querySchema[GuestClaimCodeRow]("guest_claim_codes"))

  def insert(
    email: String,
    passwordHash: Option[String],
    isAdmin: Boolean,
    theme: String,
    locale: String,
    createdAt: Long,
    emailVerifiedAt: Option[Long],
  ): Task[UserRow] = {
    val row = UserRow(0L, Some(email), passwordHash, isAdmin, theme, locale, createdAt, emailVerifiedAt, false)
    logged(run(ctx.run(quote(users.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))) {
      user =>
        s"users.insert id=${user.id} admin=$isAdmin verified=${emailVerifiedAt.isDefined}"
    }
  }

  def insertGuest(theme: String, locale: String, createdAt: Long): Task[UserRow] = {
    val row = UserRow(0L, None, None, false, theme, locale, createdAt, None, true)
    logged(run(ctx.run(quote(users.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))) {
      user => s"users.insertGuest id=${user.id}"
    }
  }

  /** The lambda is `row`, not `user`, and that is not style: Quill names the SQL alias after the parameter, and `user`
    * is a reserved word in Postgres — `UPDATE users AS user SET ...` is a syntax error there and perfectly fine on
    * SQLite, so the whole SQLite suite passes while the real dialect refuses every call. The same rule applies to every
    * quoted lambda in this file.
    */
  def upgradeGuest(id: Long, email: String, passwordHash: String, emailVerifiedAt: Option[Long]): Task[Long] = {
    val q = quote(
      users
        .filter(row => row.id == lift(id) && row.isGuest)
        .update(
          _.email           -> lift(Option(email)),
          _.passwordHash    -> lift(Option(passwordHash)),
          _.emailVerifiedAt -> lift(emailVerifiedAt),
          _.isGuest         -> lift(false),
        )
    )
    logged(run(ctx.run(q)))(rows => s"users.upgradeGuest id=$id verified=${emailVerifiedAt.isDefined} rows=$rows")
  }

  def findByEmail(email: String): Task[Option[UserRow]] = {
    logged(run(ctx.run(quote(users.filter(_.email.contains(lift(email)))))).map(_.headOption)) { found =>
      s"users.findByEmail found=${found.isDefined}"
    }
  }

  def findById(id: Long): Task[Option[UserRow]] = {
    logged(run(ctx.run(quote(users.filter(_.id == lift(id))))).map(_.headOption)) { found =>
      s"users.findById id=$id found=${found.isDefined}"
    }
  }

  def findByIds(ids: List[Long]): Task[List[UserRow]] = {
    val q = quote(users.filter(row => liftQuery(ids).contains(row.id)))
    logged(run(ctx.run(q)))(rows => s"users.findByIds requested=${ids.size} rows=${rows.size}")
  }

  def updateTheme(userId: Long, theme: String): Task[Unit] = {
    logged(run(ctx.run(quote(users.filter(_.id == lift(userId)).update(_.theme -> lift(theme))))).unit) { _ =>
      s"users.updateTheme id=$userId theme=$theme"
    }
  }

  def updateLocale(userId: Long, locale: String): Task[Unit] = {
    logged(run(ctx.run(quote(users.filter(_.id == lift(userId)).update(_.locale -> lift(locale))))).unit) { _ =>
      s"users.updateLocale id=$userId locale=$locale"
    }
  }

  def markEmailVerified(userId: Long, verifiedAt: Long): Task[Unit] = {
    val q = quote(users.filter(_.id == lift(userId)).update(_.emailVerifiedAt -> lift(Option(verifiedAt))))
    logged(run(ctx.run(q)).unit)(_ => s"users.markEmailVerified id=$userId")
  }

  def existsAdmin: Task[Boolean] = {
    logged(run(ctx.run(quote(users.filter(_.isAdmin).size))).map(_ > 0)) { exists =>
      s"users.existsAdmin exists=$exists"
    }
  }

  /** The `LIKE` pattern behind the user list's search box, or `None` when it is empty.
    *
    * Addresses are stored lowercased — every path that writes one normalises first — so lowercasing the needle is the
    * whole of the case-insensitivity, with no `lower()` in the SQL for the two dialects to disagree about. A `%` or `_`
    * typed into the box is still a wildcard: escaping them needs an `ESCAPE` clause the dialects spell differently, and
    * a wider match is a harmless answer to a rare input.
    */
  private def emailPattern(emailContains: Option[String]): Option[String] = {
    emailContains.map(_.trim.toLowerCase).filter(_.nonEmpty).map(needle => s"%$needle%")
  }

  /** The rows a page is cut from, before ordering: the narrowing [[listPage]] and [[countMatching]] have to share, or
    * the total would count a different set than the page shows.
    */
  private def matching(emailContains: Option[String]): DynamicQuery[UserRow] = {
    dynamicQuerySchema[UserRow]("users")
      .filterOpt(emailPattern(emailContains))((row, pattern) =>
        quote(row.email.exists(address => address.like(unquote(pattern))))
      )
  }

  /** Guests with nothing on them: no tag, no word or translation they typed, and no transfer code anybody could still
    * be holding. Ordered oldest first and bounded, since this is housekeeping rather than a listing.
    */
  def findAbandonedGuests(createdBefore: Long, limit: Int): Task[List[Long]] = {
    val q = quote {
      users
        .filter(row => {
          row.isGuest && row.createdAt < lift(createdBefore) &&
          tagRows.filter(_.userId == row.id).isEmpty &&
          wordRows.filter(_.createdBy.contains(row.id)).isEmpty &&
          translationRows.filter(_.createdBy.contains(row.id)).isEmpty &&
          guestClaimCodes.filter(_.userId == row.id).isEmpty
        })
        .sortBy(_.createdAt)(using Ord.asc)
        .map(_.id)
        .take(lift(limit))
    }
    logged(run(ctx.run(q)))(ids => s"users.findAbandonedGuests rows=${ids.size}")
  }

  /** The `dto.UserSort` vocabulary translated to an `ORDER BY`. The mapping lives here rather than in the service
    * because this is the only layer that knows a column exists; an unknown name is not an error but simply the
    * listing's own order, which is what lets a client stop sending a sort without coordinating a deploy.
    *
    * That default order is newest first. Once a listing is paged, the default decides which rows are on the *first
    * page* rather than merely which end they are read from, and the account somebody has just created is the one they
    * are looking for.
    */
  private def ordered(
    query: DynamicQuery[UserRow],
    sort: Option[String],
    descending: Boolean,
  ): DynamicQuery[UserRow] = {
    sort match {
      case Some(UserSort.email)     =>
        query.sortBy(_.email)(using ordering(descending))
      case Some(UserSort.admin)     =>
        query.sortBy(_.isAdmin)(using ordering(descending))
      case Some(UserSort.confirmed) =>
        query.sortBy(_.emailVerifiedAt)(using ordering(descending))
      case Some(UserSort.created)   =>
        query.sortBy(_.createdAt)(using ordering(descending))
      case _                        =>
        query.sortBy(_.createdAt)(using Ord.desc)
    }
  }

  def listPage(
    offset: Int,
    limit: Int,
    emailContains: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): Task[List[UserRow]] = {
    val page = ordered(matching(emailContains), sort, descending).drop(offset).take(limit)
    // The search term is a fragment of somebody's address, so it stays out of the message like every other address.
    logged(run(ctx.run(page))) { rows =>
      s"users.listPage offset=$offset limit=$limit sort=${sort.getOrElse("-")} desc=$descending rows=${rows.size}"
    }
  }

  def countMatching(emailContains: Option[String]): Task[Long] = {
    logged(run(ctx.run(matching(emailContains).size)))(count => s"users.countMatching count=$count")
  }

  def updateProfile(id: Long, email: String, isAdmin: Boolean): Task[Long] = {
    val q = quote(users.filter(_.id == lift(id)).update(_.email -> lift(Option(email)), _.isAdmin -> lift(isAdmin)))
    logged(run(ctx.run(q)))(rows => s"users.updateProfile id=$id admin=$isAdmin rows=$rows")
  }

  def updatePasswordHash(id: Long, passwordHash: String): Task[Unit] = {
    val q = quote(users.filter(_.id == lift(id)).update(_.passwordHash -> lift(Option(passwordHash))))
    logged(run(ctx.run(q)).unit)(_ => s"users.updatePasswordHash id=$id")
  }

  def updateProfileAndPassword(id: Long, email: String, isAdmin: Boolean, passwordHash: Option[String]): Task[Long] = {
    val profile = ctx.run(
      quote(users.filter(_.id == lift(id)).update(_.email -> lift(Option(email)), _.isAdmin -> lift(isAdmin)))
    )
    val updated = {
      passwordHash match {
        case None       =>
          run(profile)
        case Some(hash) =>
          val password = ctx.run(quote(users.filter(_.id == lift(id)).update(_.passwordHash -> lift(Option(hash)))))
          // One unit of work: an admin edit must not be able to land the new password while leaving
          // the email/role change behind, or the other way round.
          transaction(password *> profile)
      }
    }
    logged(updated) { rows =>
      s"users.updateProfileAndPassword id=$id admin=$isAdmin password=${passwordHash.isDefined} rows=$rows"
    }
  }

  def deleteById(id: Long): Task[Long] = {
    logged(run(ctx.run(quote(users.filter(_.id == lift(id)).delete))))(rows => s"users.deleteById id=$id rows=$rows")
  }
}
