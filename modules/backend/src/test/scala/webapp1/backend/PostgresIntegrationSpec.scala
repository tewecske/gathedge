package webapp1.backend

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.testcontainers.utility.DockerImageName
import webapp1.backend.config.AppConfig
import webapp1.backend.db.{
  DbDialect,
  FlywayMigrator,
  PostgresGroupInvitationRepository,
  PostgresGroupMemberRepository,
  PostgresGroupPairRepository,
  PostgresGroupRepository,
  PostgresSessionRepository,
  PostgresTodoRepository,
  PostgresUserRepository,
}
import webapp1.backend.security.PasswordHasher
import webapp1.backend.service.{AuthServiceLive, EmailSender, GroupServiceLive, InMemoryRateLimiter, TodoServiceLive}
import webapp1.shared.domain.TodoStatus
import zio._
import zio.test._

import javax.sql.DataSource

/** Exercises the Postgres dialect for real — every other *ServiceSpec runs against
  * SQLite (the test-side of the dual-dialect strategy per the plan). This is the
  * one place `RETURNING id`, `GENERATED ALWAYS AS IDENTITY`, and the Postgres join
  * SQL actually get executed rather than just compile-time-checked by Quill.
  *
  * Needs a Docker daemon reachable by testcontainers. Gated behind the
  * `RUN_POSTGRES_TESTS=1` env var so `sbt test` doesn't fail in environments
  * without Docker (this sandbox included, at the time this was written).
  */
object PostgresIntegrationSpec extends ZIOSpecDefault {

  private val containerDataSource: ZLayer[Any, Throwable, DataSource] =
    ZLayer.scoped {
      for {
        container <- ZIO.acquireRelease(ZIO.attempt {
                       PostgreSQLContainer.Def(dockerImageName = DockerImageName.parse("postgres:16-alpine")).start()
                     })(c => ZIO.attempt(c.stop()).orDie)
        ds <- ZIO.acquireRelease(ZIO.attempt {
                val config = new HikariConfig()
                config.setJdbcUrl(container.jdbcUrl)
                config.setUsername(container.username)
                config.setPassword(container.password)
                new HikariDataSource(config)
              })(ds => ZIO.attempt(ds.close()).orDie)
        _ <- FlywayMigrator.migrate(ds, DbDialect.Postgresql)
      } yield ds: DataSource
    }

  private val repoLayer =
    containerDataSource >>> (
      PostgresUserRepository.live ++
        PostgresSessionRepository.live ++
        PostgresTodoRepository.live ++
        PostgresGroupRepository.live ++
        PostgresGroupMemberRepository.live ++
        PostgresGroupPairRepository.live ++
        PostgresGroupInvitationRepository.live
    )

  private val layer =
    repoLayer ++ PasswordHasher.live ++ InMemoryRateLimiter.live ++ EmailSender.live ++ AppConfig.live >>>
      (AuthServiceLive.live ++ TodoServiceLive.live ++ GroupServiceLive.live)

  def spec = suite("Postgres dialect (testcontainers)")(
    test("signup, login, todo add/move, and group create/pairs all round-trip through real Postgres") {
      for {
        authService  <- ZIO.service[webapp1.backend.service.AuthService]
        todoService  <- ZIO.service[webapp1.backend.service.TodoService]
        groupService <- ZIO.service[webapp1.backend.service.GroupService]
        signupResult <- authService.signup("pguser@example.com", "password123")
        (user, _)     = signupResult
        todo         <- todoService.addTodo(user.id, "verify postgres")
        moved        <- todoService.moveTodo(user.id, todo.id, TodoStatus.Done)
        group        <- groupService.createGroup(user.id, "PG Group")
        pair         <- groupService.addPair(user.id, user.email, group.id, "src", "tgt")
        pairs        <- groupService.listPairs(user.id, group.id)
      } yield assertTrue(
        moved.status == TodoStatus.Done,
        group.name == "PG Group",
        pair.source == "src",
        pairs.map(_.id) == List(pair.id),
      )
    }
  ).provide(layer) @@ TestAspect.ifEnvSet("RUN_POSTGRES_TESTS")
}
