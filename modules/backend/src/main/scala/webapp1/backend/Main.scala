package webapp1.backend

import webapp1.backend.config.AppConfig
import webapp1.backend.db.{
  DataSourceFactory,
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
import webapp1.backend.http.{AdminRoutes, AuthRoutes, GroupRoutes, InvitationRoutes, RouteSupport, TodoRoutes}
import webapp1.backend.security.PasswordHasher
import webapp1.backend.service.{
  AdminSeeder,
  AdminServiceLive,
  AuthServiceLive,
  EmailSender,
  GoogleOAuthClient,
  GroupServiceLive,
  InMemoryRateLimiter,
  RateLimiter,
  SessionReaper,
  TodoServiceLive,
}
import zio.*
import zio.http.*
import zio.logging.backend.SLF4J

import javax.sql.DataSource

object Main extends ZIOAppDefault {

  override val bootstrap: ZLayer[Any, Any, Unit] = Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private val allRoutes = {
    val combined =
      AuthRoutes.routes ++ TodoRoutes.routes ++ GroupRoutes.routes ++ InvitationRoutes.routes ++ AdminRoutes.routes
    RouteSupport.handleDefects(combined) @@ Middleware.requestLogging()
  }

  private val program = {
    for {
      cfg <- ZIO.service[AppConfig]
      _ <- ZIO.foreachDiscard(cfg.productionIssues)(issue => ZIO.logError(s"Unsafe production config: $issue"))
      _ <-
        ZIO.when(cfg.productionIssues.nonEmpty) {
          ZIO.fail(
            new IllegalStateException("Refusing to start: the production configuration is unsafe (see errors above)")
          )
        }
      dataSource <- ZIO.service[DataSource]
      _ <- FlywayMigrator.migrate(dataSource, DbDialect.Postgresql)
      _ <- AdminSeeder.seedIfNeeded
      rateLimiter <- ZIO.service[RateLimiter]
      _ <- rateLimiter.runPruner.forkDaemon
      _ <- SessionReaper.run.forkDaemon
      _ <- ZIO.logInfo(s"Starting webapp1 backend on port ${cfg.app.serverPort} (env=${cfg.app.env})")
      _ <- Server.serve(allRoutes)
    } yield ()
  }

  def run = program.provide(
    AppConfig.live,
    DataSourceFactory.postgresLive,
    PostgresUserRepository.live,
    PostgresSessionRepository.live,
    PostgresTodoRepository.live,
    PostgresGroupRepository.live,
    PostgresGroupMemberRepository.live,
    PostgresGroupPairRepository.live,
    PostgresGroupInvitationRepository.live,
    PasswordHasher.live,
    InMemoryRateLimiter.live,
    EmailSender.live,
    AuthServiceLive.live,
    GoogleOAuthClient.live,
    TodoServiceLive.live,
    GroupServiceLive.live,
    AdminServiceLive.live,
    Server.live,
    ZLayer(ZIO.serviceWith[AppConfig](cfg => Server.Config.default.port(cfg.app.serverPort))),
  )
}
