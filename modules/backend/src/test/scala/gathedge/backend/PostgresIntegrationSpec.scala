package gathedge.backend

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.testcontainers.utility.DockerImageName
import gathedge.backend.TestAuthLayers
import gathedge.backend.config.AppConfig
import gathedge.backend.db.{
  AuditLogRepository,
  DbDialect,
  EmailVerificationTokenRepository,
  FlywayMigrator,
  GuestClaimCodeRepository,
  LoginAttemptRepository,
  OAuthIdentityRepository,
  OAuthIdentityRow,
  PasswordResetTokenRepository,
  SessionRepository,
  UserRepository,
  WordRepository,
  WordRow,
}
import gathedge.backend.security.PasswordHasher
import gathedge.backend.service.{
  AdminActor,
  AdminService,
  AuditTrail,
  AuthService,
  EmailSender,
  RateLimiter,
  SessionReaper,
  WordService,
}
import gathedge.shared.dto.Paging
import zio._
import zio.test._

import java.util.concurrent.TimeUnit

import javax.sql.DataSource

/** Exercises the Postgres dialect for real — every other *ServiceSpec runs against SQLite (the test-side of the
  * dual-dialect strategy per the plan). This is the one place `RETURNING id`, `GENERATED ALWAYS AS IDENTITY`, and the
  * Postgres join SQL actually get executed rather than just compile-time-checked by Quill.
  *
  * Needs a Docker daemon reachable by testcontainers. Gated behind the `RUN_POSTGRES_TESTS=1` env var so `sbt test`
  * doesn't fail in environments without Docker (this sandbox included, at the time this was written).
  */
object PostgresIntegrationSpec extends ZIOSpecDefault {

  /** The schema the application owns, matching `db.schema` in application.conf. Set on both the pool and Flyway below,
    * exactly as production does it, so this spec exercises the real search_path rather than falling back to `public` —
    * a mismatch between the two halves is precisely the failure no other spec could see.
    */
  private val schema = "gathedge"

  private val containerDataSource: ZLayer[Any, Throwable, DataSource] = ZLayer.scoped {
    for {
      container <-
        ZIO.acquireRelease(
          ZIO.attempt {
            PostgreSQLContainer.Def(dockerImageName = DockerImageName.parse("postgres:16-alpine")).start()
          }
        )(c => ZIO.attempt(c.stop()).orDie)
      ds        <-
        ZIO.acquireRelease(
          ZIO.attempt {
            val config = new HikariConfig()
            config.setJdbcUrl(container.jdbcUrl)
            // Same reason as TestDataSource.sqlite: bypass DriverManager, whose registry is stale after
            // an sbt recompile hands the test run a new classloader.
            config.setDriverClassName("org.postgresql.Driver")
            config.setUsername(container.username)
            config.setPassword(container.password)
            config.setSchema(schema)
            new HikariDataSource(config)
          }
        )(ds => ZIO.attempt(ds.close()).orDie)
      _         <- FlywayMigrator.migrate(ds, DbDialect.Postgresql, Some(schema))
    } yield ds: DataSource
  }

  private val repoLayer = {
    containerDataSource >>> (
      UserRepository.live ++ SessionRepository.live ++ OAuthIdentityRepository.live ++
        EmailVerificationTokenRepository.live ++ PasswordResetTokenRepository.live ++ LoginAttemptRepository.live ++
        AuditLogRepository.live ++ GuestClaimCodeRepository.live ++ WordRepository.live
    )
  }

  // `>+>` rather than `>>>` so the repositories stay in the environment alongside the services: the delete-user test
  // asserts on the rows a cascade removed, which no service exposes once their owner is gone.
  private val layer = {
    repoLayer ++ PasswordHasher.live ++ RateLimiter.live ++ TestCaptchaService.live ++ TestAuthLayers.emailAndConfig >+>
      (AuthService.live ++ AuditTrail.live) >+> AdminService.live >+> WordService.live
  }

  def spec = {
    suite("Postgres dialect (testcontainers)")(
      // `RETURNING id` and `GENERATED ALWAYS AS IDENTITY` are the two things this dialect does differently from the
      // SQLite one every other spec runs against, and a signup exercises both: the user row, the session row keyed by
      // the id it just produced, and the verification token pointing back at it.
      test("signup and login round-trip through real Postgres, with the rows keyed to the generated id") {
        for {
          signupResult <- AuthService.signup("pguser@example.com", "password123")
          (user, _)     = signupResult
          loggedIn     <- AuthService.login("pguser@example.com", "password123")
          sessions     <- SessionRepository.listForUser(user.id)
          tokens       <- EmailVerificationTokenRepository.findForUser(user.id)
          current      <- AuthService.currentUser(loggedIn._2)
        } yield assertTrue(
          user.id > 0,
          loggedIn._1.id == user.id,
          current.map(_.id).contains(user.id),
          sessions.forall(_.userId == user.id),
          sessions.size == 2,
          tokens.map(_.userId) == List(user.id),
        )
      },
      test("an admin profile-and-password edit commits as one unit and drops the user's sessions") {
        for {
          admin           <- AdminService.createUser(AdminActor.system, "pgadmin@example.com", "password123", isAdmin = true)
          target          <-
            AdminService.createUser(AdminActor(admin.id), "pgtarget@example.com", "password123", isAdmin = false)
          session         <- AuthService.login("pgtarget@example.com", "password123").map(_._2)
          updated         <- AdminService.updateUser(
                               AdminActor(admin.id),
                               target.id,
                               "pgrenamed@example.com",
                               isAdmin = true,
                               password = Some("replacedpw"),
                             )
          afterReset      <- AuthService.currentUser(session)
          withNewPassword <- AuthService.login("pgrenamed@example.com", "replacedpw")
        } yield assertTrue(
          updated.email.contains("pgrenamed@example.com"),
          updated.isAdmin,
          afterReset.isEmpty,
          withNewPassword._1.id == target.id,
        )
      },
      // Only Postgres can catch this: SQLite runs with `PRAGMA foreign_keys` off, so every foreign key in that dialect
      // is inert and the delete succeeds however the constraint is declared. `AdminService.deleteUser` issues a bare
      // `deleteById` and nothing else — removing the rows that point at the account *is* the constraint's job, and a
      // reference declared without an ON DELETE action instead raises
      // "update or delete on table \"users\" violates foreign key constraint", which `deleteById`'s `.orDie` turns
      // into a bare 500. Any new table that references `users` belongs in this test.
      test("deleting a user cascades to its sessions, identities, tokens, tags, practice pairs and transfer codes") {
        for {
          admin      <- AdminService.createUser(AdminActor.system, "pgdeladmin@example.com", "password123", isAdmin = true)
          signup     <- AuthService.signup("pgdeltarget@example.com", "password123")
          (target, _) = signup
          _          <- AuthService.login("pgdeltarget@example.com", "password123")
          _          <- OAuthIdentityRepository.insert(
                          OAuthIdentityRow(0L, target.id, "google", "pg-subject-1", target.email, 0L)
                        )
          // The vocabulary's three per-account tables, and the one table it shares with everybody.
          tag        <- WordRepository.insertTag(target.id, "lesson1", "lesson1", 0L)
          word       <- WordRepository.ensureWord(
                          WordRow(0L, "de", "Löffel", "löffel", "noun", "der", 1, "user", Some(target.id), 0L)
                        )
          spoon      <- WordRepository.ensureWord(WordRow(0L, "hu", "kanál", "kanál", "noun", "", 1, "user", None, 0L))
          _          <- WordRepository.insertTranslationPair(word.id, spoon.id, "user", Some(target.id), 0L)
          _          <- WordRepository.tagWord(word.id, tag.id, 0L)
          // `word_tag_pairs` reaches `users` only through `tags`, but that is the path that breaks: declared without an
          // ON DELETE action, the cascade *into* `tags` would raise a violation and `deleteUser` would answer 500.
          _          <- WordRepository.pairTranslation(word.id, tag.id, spoon.id, 0L)
          _          <- GuestClaimCodeRepository.insert(target.id, "PGDE-LETE-CODE-0001", 0L)
          _          <- AdminService.deleteUser(AdminActor(admin.id), target.id)
          gone       <- AdminService.getUser(target.id).either
          sessions   <- SessionRepository.listForUser(target.id)
          identities <- OAuthIdentityRepository.listForUser(target.id)
          tokens     <- EmailVerificationTokenRepository.findForUser(target.id)
          tags       <- WordRepository.listTags(target.id)
          pairs      <- WordRepository.pairsFor(target.id, List(word.id, spoon.id))
          codes      <- GuestClaimCodeRepository.countFor(target.id)
          // The word itself is the SET NULL case: somebody else may well have tagged it, so it outlives its author.
          stillThere <- WordRepository.findWordById(word.id)
          links      <- WordRepository.allTranslationsOf(word.id)
        } yield assertTrue(
          gone == Left(gathedge.backend.service.AdminFailure.NotFound),
          sessions.isEmpty,
          identities.isEmpty,
          tokens.isEmpty,
          tags.isEmpty,
          pairs.isEmpty,
          codes == 0L,
          stillThere.isDefined,
          stillThere.flatMap(_.createdBy).isEmpty,
          links.map(_._2.text) == List("kanál"),
          links.forall(_._1.createdBy.isEmpty),
        )
      },
      // `login_attempts` and `audit_log` are the two user references declared ON DELETE SET NULL rather than CASCADE,
      // and the same blind spot applies: SQLite enforces neither, so the whole SQLite suite passes whichever. Getting it
      // wrong in either direction is a real bug — CASCADE would erase the record of what was done to an account the
      // moment it is deleted, and NO ACTION would make `deleteUser` answer 500 for every account that has ever
      // signed in.
      test("deleting a user keeps its audit entries and sign-in history, with the references nulled out") {
        for {
          admin          <- AdminService.createUser(AdminActor.system, "pgaudit@example.com", "password123", isAdmin = true)
          target         <-
            AdminService.createUser(AdminActor(admin.id), "pgaudited@example.com", "password123", isAdmin = false)
          // Both directions of the reference: the target has attempts and is the subject of an audit entry, and it
          // is also the *actor* on one of its own (it clears its own lockout), so deleting it exercises
          // `audit_log.actor_user_id` as well as `login_attempts.user_id`.
          _              <- AuthService.login("pgaudited@example.com", "password123")
          _              <- AuthService.login("pgaudited@example.com", "wrong").either
          _              <- AdminService.clearLockout(AdminActor(target.id), target.id)
          attemptsBefore <- AdminService.loginAttempts(50, None).map(_.count(_.userId.contains(target.id)))
          auditBefore    <- AdminService.auditLog(Paging.firstPage, 50, None, false, None, None, Some(target.id.toString))
          _              <- AdminService.deleteUser(AdminActor(admin.id), target.id)
          gone           <- AdminService.getUser(target.id).either
          attemptsAfter  <- AdminService.loginAttempts(50, None).map(_.filter(_.email == "pgaudited@example.com"))
          auditAfter     <- AdminService.auditLog(Paging.firstPage, 50, None, false, None, None, Some(target.id.toString))
        } yield assertTrue(
          gone == Left(gathedge.backend.service.AdminFailure.NotFound),
          attemptsBefore == 2,
          auditBefore.items.nonEmpty,
          // The rows survive; only the foreign keys are cleared.
          attemptsAfter.size == 2,
          attemptsAfter.forall(_.userId.isEmpty),
          auditAfter.items.size >= auditBefore.items.size,
          auditAfter.items.exists(_.actorEmail.contains("pgaudit@example.com")),
          // The entry the deleted account wrote itself keeps the address it had, and loses only the id.
          auditAfter.items.exists(entry =>
            entry.actorEmail.contains("pgaudited@example.com") && entry.actorUserId.isEmpty
          ),
        )
      },
      // Both of these are queries the SQLite suite runs happily and Postgres refuses, because Quill names the SQL
      // alias after the lambda parameter and `user` is a reserved word there. `UPDATE users AS user SET ...` is a
      // syntax error, and so is a `WHERE user.is_guest` in the reaper's subquery — the whole guest feature was
      // green on SQLite and 500 on the real dialect. Anything touching `users` through a quoted lambda belongs here.
      test("a guest can be minted, carried by a transfer code and upgraded, on the real dialect") {
        for {
          minted    <- AuthService.createGuest(Some("10.9.0.1"))
          (guest, _) = minted
          code      <- AuthService.issueClaimCode(guest.id)
          claimed   <- AuthService.claimGuest(code, Some("10.9.0.2"))
          upgraded  <- AuthService.upgradeGuest(guest.id, "pgguest@example.com", "password123")
          signedIn  <- AuthService.login("pgguest@example.com", "password123")
          codeGone  <- AuthService.claimGuest(code, Some("10.9.0.3")).either
        } yield assertTrue(
          guest.isGuest,
          guest.email.isEmpty,
          claimed._1.id == guest.id,
          upgraded.id == guest.id,
          !upgraded.isGuest,
          upgraded.email.contains("pgguest@example.com"),
          signedIn._1.id == guest.id,
          codeGone.isLeft,
        )
      },
      // Three SQL shapes reach the real dialect here for the first time: `pairTranslation`'s four-statement transaction
      // with `returningGenerated`, `unpairTranslation`'s two-statement one, and the `||` inside the `DELETE` that
      // `untagWord` grew. SQLite would pass whatever any of them rendered to.
      test("marking and unmarking a practice answer round-trips on the real dialect") {
        for {
          reader  <- AuthService.createGuest(Some("10.9.2.1")).map(_._1)
          tag     <- WordService.createTag("pglesson", reader.id).map(_.tag)
          word    <- WordRepository.ensureWord(
                       WordRow(0L, "de", "Gabel", "gabel", "noun", "die", 1, "user", Some(reader.id), 0L)
                     )
          fork    <- WordRepository.ensureWord(WordRow(0L, "hu", "villa", "villa", "noun", "", 1, "user", None, 0L))
          _       <- WordRepository.insertTranslationPair(word.id, fork.id, "user", Some(reader.id), 0L)
          _       <- WordService.selectPair(word.id, tag.id, fork.id, reader.id)
          marked  <- WordRepository.pairsFor(reader.id, List(word.id, fork.id))
          links   <- WordRepository.tagsFor(reader.id, List(word.id, fork.id))
          _       <- WordService.deselectPair(word.id, tag.id, fork.id, reader.id)
          cleared <- WordRepository.pairsFor(reader.id, List(word.id, fork.id))
          _       <- WordService.selectPair(word.id, tag.id, fork.id, reader.id)
          _       <- WordService.untagWord(word.id, tag.id, reader.id)
          swept   <- WordRepository.pairsFor(reader.id, List(word.id, fork.id))
          left    <- WordRepository.tagsFor(reader.id, List(word.id, fork.id))
        } yield assertTrue(
          // Both directions of the pair, and both words filed under the tag.
          marked.map(row => (row.wordId, row.translationWordId)).toSet ==
            Set((word.id, fork.id), (fork.id, word.id)),
          links.map(_.wordId).toSet == Set(word.id, fork.id),
          cleared.isEmpty,
          // Untagging the word takes its pairs in that tag with it, both ways round, and leaves the translation filed.
          swept.isEmpty,
          left.map(_.wordId) == List(fork.id),
        )
      },
      test("the reaper's sweep runs, and takes only the guests with nothing on them") {
        for {
          empty      <- AuthService.createGuest(Some("10.9.1.1")).map(_._1)
          keeper     <- AuthService.createGuest(Some("10.9.1.2")).map(_._1)
          tag        <- WordRepository.insertTag(keeper.id, "keep", "keep", 0L)
          _           = tag
          now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
          // Both are minutes old, so a cutoff in the future is what makes them sweepable at all.
          abandoned  <- UserRepository.findAbandonedGuests(now + 1000L, 100)
          swept      <- SessionReaper.sweep
          emptyGone  <- UserRepository.findById(empty.id)
          keeperHere <- UserRepository.findById(keeper.id)
        } yield assertTrue(
          abandoned.contains(empty.id),
          !abandoned.contains(keeper.id),
          swept.guests >= 0L,
          emptyGone.isDefined || swept.guests > 0L,
          keeperHere.isDefined,
        )
      },
    ).provide(layer) @@ TestAspect.ifEnvSet("RUN_POSTGRES_TESTS") @@ TestAspect.sequential
  }
}
