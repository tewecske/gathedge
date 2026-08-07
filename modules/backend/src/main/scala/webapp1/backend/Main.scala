package webapp1.backend

import webapp1.backend.config.AppConfig
import webapp1.backend.db.{
  AuditLogRepository,
  DataSourceFactory,
  DbDialect,
  EmailVerificationTokenRepository,
  FlywayMigrator,
  GroupInvitationRepository,
  GroupMemberRepository,
  GroupPairRepository,
  GroupRepository,
  LoginAttemptRepository,
  MetricsRepository,
  OAuthIdentityRepository,
  SessionRepository,
  TodoRepository,
  UserRepository,
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
  AdminService,
  AuditTrail,
  AuthService,
  BackgroundJobs,
  EmailSender,
  GroupService,
  OAuthClients,
  RateLimiter,
  SessionReaper,
  SystemService,
  TodoService,
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
    UserRepository.live,
    SessionRepository.live,
    TodoRepository.live,
    GroupRepository.live,
    GroupMemberRepository.live,
    GroupPairRepository.live,
    GroupInvitationRepository.live,
    OAuthIdentityRepository.live,
    EmailVerificationTokenRepository.live,
    LoginAttemptRepository.live,
    AuditLogRepository.live,
    MetricsRepository.live,
    PasswordHasher.live,
    RateLimiter.live,
    BackgroundJobs.live,
    AuditTrail.live,
    EmailSender.live,
    AuthService.live,
    OAuthClients.live,
    // The outbound half of zio-http: the providers' token and tokeninfo endpoints are the only calls this server makes.
    Client.default,
    TodoService.live,
    GroupService.live,
    AdminService.live,
    SystemService.live,
    Server.customized,
    ZLayer(ZIO.serviceWith[AppConfig](cfg => Server.Config.default.binding(cfg.app.serverHost, cfg.app.serverPort))),
    ZLayer(ZIO.serviceWith[AppConfig](cfg => NettyConfig.default.maxThreads(cfg.netty.maxThreads))),
  )
}
