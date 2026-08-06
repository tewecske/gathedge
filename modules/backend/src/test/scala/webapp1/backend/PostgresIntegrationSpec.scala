package webapp1.backend

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.testcontainers.utility.DockerImageName
import webapp1.backend.TestAuthLayers
import webapp1.backend.config.AppConfig
import webapp1.backend.db.{
  DbDialect,
  EmailVerificationTokenRepository,
  FlywayMigrator,
  GroupInvitationRepository,
  GroupMemberRepository,
  GroupPairRepository,
  GroupRepository,
  OAuthIdentityRepository,
  SessionRepository,
  TodoRepository,
  UserRepository,
}
import webapp1.backend.security.PasswordHasher
import webapp1.backend.service.{AdminService, AuthService, EmailSender, GroupService, RateLimiter, TodoService}
import webapp1.shared.domain.{GroupRole, TodoStatus}
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
      UserRepository.live ++ SessionRepository.live ++ TodoRepository.live ++
        GroupRepository.live ++ GroupMemberRepository.live ++ GroupPairRepository.live ++
        GroupInvitationRepository.live ++ OAuthIdentityRepository.live ++
        EmailVerificationTokenRepository.live
    )
  }

  // `>+>` rather than `>>>` so the repositories stay in the environment alongside the services: the delete-user test
  // asserts on the rows a cascade removed, which no service exposes once their owner is gone.
  private val layer = {
    repoLayer ++ PasswordHasher.live ++ RateLimiter.live ++ TestAuthLayers.emailAndConfig >+>
      (AuthService.live ++ TodoService.live ++ GroupService.live ++ AdminService.live)
  }

  def spec = {
    suite("Postgres dialect (testcontainers)")(
      test("signup, login, todo add/move, and group create/pairs all round-trip through real Postgres") {
        for {
          signupResult <- AuthService.signup("pguser@example.com", "password123")
          (user, _)     = signupResult
          todo         <- TodoService.addTodo(user.id, "verify postgres")
          moved        <- TodoService.moveTodo(user.id, todo.id, TodoStatus.Done)
          group        <- GroupService.createGroup(user.id, "PG Group")
          pair         <- GroupService.addPair(user.id, user.email, group.id, "src", "tgt")
          pairs        <- GroupService.listPairs(user.id, group.id)
          // Proves the creator's membership row committed with the group: without it the group
          // would be invisible here and unreachable through every other group endpoint.
          myGroups     <- GroupService.myGroups(user.id)
        } yield assertTrue(
          moved.status == TodoStatus.Done,
          group.name == "PG Group",
          pair.source == "src",
          pairs.map(_.id) == List(pair.id),
          myGroups.map(_.id) == List(group.id),
        )
      },
      test("an admin profile-and-password edit commits as one unit and drops the user's sessions") {
        for {
          admin           <- AdminService.createUser(0L, "pgadmin@example.com", "password123", isAdmin = true)
          target          <- AdminService.createUser(admin.id, "pgtarget@example.com", "password123", isAdmin = false)
          session         <- AuthService.login("pgtarget@example.com", "password123").map(_._2)
          updated         <- AdminService.updateUser(
                               admin.id,
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
      // Only Postgres can catch this: SQLite runs with `PRAGMA foreign_keys` off, so `group_pairs.created_by` and
      // `group_invitations.invited_by` are inert there and the delete succeeds however the constraint is declared.
      // Before V6 those two were the only user references without an ON DELETE action, and this raised
      // "update or delete on table \"users\" violates foreign key constraint" — which `deleteById`'s `.orDie` turned
      // into a bare 500 for any admin trying to remove a user who had ever added a pair or sent an invitation.
      test("deleting a user cascades to the group pairs they authored and the invitations they sent") {
        for {
          admin          <- AdminService.createUser(0L, "pgdeladmin@example.com", "password123", isAdmin = true)
          target         <- AdminService.createUser(admin.id, "pgdeltarget@example.com", "password123", isAdmin = false)
          group          <- GroupService.createGroup(target.id, "Doomed Author Group")
          _              <- GroupService.addPair(target.id, target.email, group.id, "src", "tgt")
          _              <- GroupService.inviteMember(target.id, group.id, "pginvitee@example.com", GroupRole.ReadWrite)
          _              <- AdminService.deleteUser(admin.id, target.id)
          gone           <- AdminService.getUser(target.id).either
          remainingPairs <- GroupPairRepository.listForGroup(group.id)
        } yield assertTrue(gone == Left(webapp1.backend.service.AdminFailure.NotFound), remainingPairs.isEmpty)
      },
    ).provide(layer) @@ TestAspect.ifEnvSet("RUN_POSTGRES_TESTS") @@ TestAspect.sequential
  }
}
