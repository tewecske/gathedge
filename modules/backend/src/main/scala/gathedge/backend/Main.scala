package gathedge.backend

import gathedge.backend.config.AppConfig
import gathedge.backend.db.{
  AuditLogRepository,
  DataSourceFactory,
  DbDialect,
  EmailVerificationTokenRepository,
  FlywayMigrator,
  GuestClaimCodeRepository,
  LoginAttemptRepository,
  MetricsRepository,
  OAuthIdentityRepository,
  PasswordResetTokenRepository,
  SessionRepository,
  UsageEventRepository,
  UserRepository,
  WordRepository,
}
import gathedge.backend.http.{AdminRoutes, AuthRoutes, DocsRoutes, RouteSupport, WordRoutes}
import gathedge.backend.i18n.Messages
import gathedge.backend.security.PasswordHasher
import gathedge.backend.service.{
  AdminSeeder,
  AdminService,
  AuditTrail,
  AuthService,
  BackgroundJobs,
  CaptchaService,
  EmailSender,
  OAuthClients,
  RateLimiter,
  SessionReaper,
  SystemService,
  UsageStatsService,
  UsageTracker,
  WordService,
}
import gathedge.shared.Branding
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
    val combined = AuthRoutes.routes ++ WordRoutes.routes ++ AdminRoutes.routes ++ DocsRoutes.routes
    // Ours rather than `Middleware.requestLogging()`: that one logs the whole URL, and one of this API's URLs carries a
    // credential — the OAuth authorization code arrives as a query parameter. See `RouteSupport.loggableUrl`.
    RouteSupport.handleFailures(combined) @@ RouteSupport.requestLogging @@ RouteSupport.usageTracking
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
      _           <- FlywayMigrator.migrate(dataSource, DbDialect.Postgresql, Some(cfg.db.schema))
      _           <- AdminSeeder.seedIfNeeded
      rateLimiter <- ZIO.service[RateLimiter]
      _           <- rateLimiter.runPruner.forkDaemon
      _           <- SessionReaper.run.forkDaemon
      _           <- ZIO.logInfo(
                       s"Starting ${Branding.slug} backend on ${cfg.app.serverHost}:${cfg.app.serverPort} (env=${cfg.app.env})"
                     )
      _           <- Server.serve(allRoutes)
    } yield ()
  }

  def run = program.provide(
    AppConfig.live,
    DataSourceFactory.postgresLive,
    UserRepository.live,
    SessionRepository.live,
    OAuthIdentityRepository.live,
    EmailVerificationTokenRepository.live,
    PasswordResetTokenRepository.live,
    LoginAttemptRepository.live,
    AuditLogRepository.live,
    UsageEventRepository.live,
    MetricsRepository.live,
    GuestClaimCodeRepository.live,
    WordRepository.live,
    PasswordHasher.live,
    RateLimiter.live,
    BackgroundJobs.live,
    AuditTrail.live,
    EmailSender.live,
    // Fails the boot if a catalog is missing from the image, before the server binds.
    Messages.live,
    AuthService.live,
    OAuthClients.live,
    // The outbound half of zio-http: the providers' token and tokeninfo endpoints are the only calls this server makes.
    Client.default,
    CaptchaService.live,
    WordService.live,
    AdminService.live,
    SystemService.live,
    UsageTracker.live,
    UsageStatsService.live,
    Server.customized,
    ZLayer(ZIO.serviceWith[AppConfig](cfg => Server.Config.default.binding(cfg.app.serverHost, cfg.app.serverPort))),
    ZLayer(ZIO.serviceWith[AppConfig](cfg => NettyConfig.default.maxThreads(cfg.netty.maxThreads))),
  )
}
