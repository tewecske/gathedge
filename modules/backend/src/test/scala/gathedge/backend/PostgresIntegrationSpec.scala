package gathedge.backend

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.testcontainers.utility.DockerImageName
import gathedge.backend.TestAuthLayers
import gathedge.backend.config.AppConfig
import gathedge.backend.db.{
  AuditLogRepository,
  LoginAttemptRepository,
  DbDialect,
  EmailVerificationTokenRepository,
  FlywayMigrator,
  OAuthIdentityRepository,
  OAuthIdentityRow,
  SessionRepository,
  UserRepository,
}
import gathedge.backend.security.PasswordHasher
import gathedge.backend.service.{AdminActor, AdminService, AuditTrail, AuthService, EmailSender, RateLimiter}
import gathedge.shared.dto.Paging
import zio._
import zio.test._

import javax.sql.DataSource

/** Exercises the Postgres dialect for real — every other *ServiceSpec runs against SQLite (the test-side of the
  * dual-dialect strategy per the plan). This is the one place `RETURNING id`, `GENERATED ALWAYS AS IDENTITY`, and the
  * Postgres join SQL actually get executed rather than just compile-time-checked by Quill.
  *
  * Needs a Docker daemon reachable by testcontainers. Gated behind the `RUN_POSTGRES_TESTS=1` env var so `sbt test`
  * doesn't fail in environments without Docker (this sandbox included, at the time this was written).
  */
object PostgresIntegrationSpec extends ZIOSpecDefault {

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
            new HikariDataSource(config)
          }
        )(ds => ZIO.attempt(ds.close()).orDie)
      _         <- FlywayMigrator.migrate(ds, DbDialect.Postgresql)
    } yield ds: DataSource
  }

  private val repoLayer = {
    containerDataSource >>> (
      UserRepository.live ++ SessionRepository.live ++ OAuthIdentityRepository.live ++
        EmailVerificationTokenRepository.live ++ LoginAttemptRepository.live ++ AuditLogRepository.live
    )
  }

  // `>+>` rather than `>>>` so the repositories stay in the environment alongside the services: the delete-user test
  // asserts on the rows a cascade removed, which no service exposes once their owner is gone.
  private val layer = {
    repoLayer ++ PasswordHasher.live ++ RateLimiter.live ++ TestAuthLayers.emailAndConfig >+>
      (AuthService.live ++ AuditTrail.live) >+> AdminService.live
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
          updated.email == "pgrenamed@example.com",
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
      test("deleting a user cascades to its sessions, linked identities and verification tokens") {
        for {
          admin      <- AdminService.createUser(AdminActor.system, "pgdeladmin@example.com", "password123", isAdmin = true)
          signup     <- AuthService.signup("pgdeltarget@example.com", "password123")
          (target, _) = signup
          _          <- AuthService.login("pgdeltarget@example.com", "password123")
          _          <- OAuthIdentityRepository.insert(
                          OAuthIdentityRow(0L, target.id, "google", "pg-subject-1", Some(target.email), 0L)
                        )
          _          <- AdminService.deleteUser(AdminActor(admin.id), target.id)
          gone       <- AdminService.getUser(target.id).either
          sessions   <- SessionRepository.listForUser(target.id)
          identities <- OAuthIdentityRepository.listForUser(target.id)
          tokens     <- EmailVerificationTokenRepository.findForUser(target.id)
        } yield assertTrue(
          gone == Left(gathedge.backend.service.AdminFailure.NotFound),
          sessions.isEmpty,
          identities.isEmpty,
          tokens.isEmpty,
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
    ).provide(layer) @@ TestAspect.ifEnvSet("RUN_POSTGRES_TESTS") @@ TestAspect.sequential
  }
}
