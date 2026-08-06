package webapp1.backend

import webapp1.backend.config.AppConfig
import webapp1.backend.db.{
  DataSourceFactory,
  DbDialect,
  FlywayMigrator,
  PostgresEmailVerificationTokenRepository,
  PostgresGroupInvitationRepository,
  PostgresGroupMemberRepository,
  PostgresGroupPairRepository,
  PostgresGroupRepository,
  PostgresOAuthIdentityRepository,
  PostgresSessionRepository,
  PostgresTodoRepository,
  PostgresUserRepository,
}
import webapp1.backend.http.{
  AdminRoutes,
  AuthRoutes,
  DocsRoutes,
  GroupRoutes,
  InvitationRoutes,
  RouteSupport,
  TodoRoutes,
}
import webapp1.backend.security.PasswordHasher
import webapp1.backend.service.{
  AdminSeeder,
  AdminServiceLive,
  AuthServiceLive,
  EmailSender,
  GroupServiceLive,
  InMemoryRateLimiter,
  OAuthClients,
  RateLimiter,
  SessionReaper,
  TodoServiceLive,
}
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.logging.backend.SLF4J

import javax.sql.DataSource

object Main extends ZIOAppDefault {

  override val bootstrap: ZLayer[Any, Any, Unit] = {
    (Runtime.removeDefaultLoggers >>> SLF4J.slf4j) ++ AppConfig.configProvider
  }

  private val allRoutes = {
    val combined = {
      AuthRoutes.routes ++ TodoRoutes.routes ++ GroupRoutes.routes ++ InvitationRoutes.routes ++ AdminRoutes.routes ++
        DocsRoutes.routes
    }
    RouteSupport.handleFailures(combined) @@ Middleware.requestLogging()
  }

  private val program = {
    for {
      cfg         <- ZIO.service[AppConfig]
      _           <- ZIO.foreachDiscard(cfg.productionIssues)(issue => ZIO.logError(s"Unsafe production config: $issue"))
      _           <- ZIO.when(cfg.productionIssues.nonEmpty) {
          ZIO.fail(
            new IllegalStateException("Refusing to start: the production configuration is unsafe (see errors above)")
          )
        }
      dataSource  <- ZIO.service[DataSource]
      _           <- FlywayMigrator.migrate(dataSource, DbDialect.Postgresql)
      _           <- AdminSeeder.seedIfNeeded
      rateLimiter <- ZIO.service[RateLimiter]
      _           <- rateLimiter.runPruner.forkDaemon
      _           <- SessionReaper.run.forkDaemon
      _           <- ZIO.logInfo(s"Starting webapp1 backend on ${cfg.app.serverHost}:${cfg.app.serverPort} (env=${cfg.app.env})")
      _           <- Server.serve(allRoutes)
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
    PostgresOAuthIdentityRepository.live,
    PostgresEmailVerificationTokenRepository.live,
    PasswordHasher.live,
    InMemoryRateLimiter.live,
    EmailSender.live,
    AuthServiceLive.live,
    OAuthClients.live,
    // The outbound half of zio-http: the providers' token and tokeninfo endpoints are the only calls this server makes.
    Client.default,
    TodoServiceLive.live,
    GroupServiceLive.live,
    AdminServiceLive.live,
    Server.customized,
    ZLayer(ZIO.serviceWith[AppConfig](cfg => Server.Config.default.binding(cfg.app.serverHost, cfg.app.serverPort))),
    ZLayer(ZIO.serviceWith[AppConfig](cfg => NettyConfig.default.maxThreads(cfg.netty.maxThreads))),
  )
}
