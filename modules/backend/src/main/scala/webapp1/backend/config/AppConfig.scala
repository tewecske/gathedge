package webapp1.backend.config

import webapp1.shared.domain.OAuthProvider
import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider

enum AppEnv derives CanEqual {
  case Dev,
    Production
}

object AppEnv {
  def parse(s: String): AppEnv = {
    if (s.trim.equalsIgnoreCase("production"))
      Production
    else
      Dev
  }
}

/** `requireEmailVerification` is the login gate only. Verification tokens are issued, emailed and redeemable whether or
  * not it is set — switching it on is what makes an unverified account unable to sign in with a password.
  *
  * `trustedProxyHops` is how many reverse proxies sit in front of this server, and it is the *only* thing that makes
  * `X-Forwarded-For` believable — see `RouteSupport.clientAddress`. It has to be configured rather than detected,
  * because both wrong answers are a security bug: trusting the header with nothing in front means any client can choose
  * its own rate-limit identity, and trusting nothing while a proxy *is* in front collapses every request onto the
  * proxy's address, which is what the limiter then locks out wholesale.
  */
final case class AppSection(
  env: String,
  serverHost: String,
  serverPort: Int,
  publicBaseUrl: String,
  requireEmailVerification: Boolean,
  trustedProxyHops: Int,
  loginAttemptRetentionDays: Int,
)
final case class DbSection(url: String, user: String, password: String)
final case class SessionSection(cookieSecure: Boolean)
final case class BootstrapAdminSection(email: String, password: String)
final case class GoogleSection(clientId: String, clientSecret: String, redirectUri: String)

/** `tenant` selects which Microsoft accounts may sign in: `common` (work/school *and* personal), `organizations`,
  * `consumers`, or a tenant GUID. It is part of both the authorize and token URLs, and of the `iss` value the id_token
  * must carry.
  */
final case class MicrosoftSection(clientId: String, clientSecret: String, redirectUri: String, tenant: String)

final case class OAuthSection(google: GoogleSection, microsoft: MicrosoftSection)

/** An empty `host` switches SMTP off the same way an empty client id switches a provider off: [[EmailSender.live]] then
  * falls back to the logging implementation, which is what makes the whole stack boot with no mail server at all.
  */
final case class SmtpSection(host: String, port: Int, username: String, password: String, startTls: Boolean)

final case class MailSection(from: String, smtp: SmtpSection)

/** `maxThreads` is handed straight to Netty's event loop group, where 0 means "decide for me" (2× available
  * processors), so that is the default here too.
  */
final case class NettySection(maxThreads: Int)

final case class AppConfig(
  app: AppSection,
  db: DbSection,
  session: SessionSection,
  bootstrapAdmin: BootstrapAdminSection,
  oauth: OAuthSection,
  mail: MailSection,
  netty: NettySection,
) {
  def appEnv: AppEnv        = AppEnv.parse(app.env)
  def isProduction: Boolean = appEnv == AppEnv.Production

  /** A provider with no client id/secret is simply switched off: its routes answer 404 and the frontend omits its
    * button. That is what lets the stack boot with no social sign-in configured at all.
    */
  def isOAuthConfigured(provider: OAuthProvider): Boolean = {
    provider match {
      case OAuthProvider.Google    =>
        oauth.google.clientId.nonEmpty && oauth.google.clientSecret.nonEmpty
      case OAuthProvider.Microsoft =>
        oauth.microsoft.clientId.nonEmpty && oauth.microsoft.clientSecret.nonEmpty
    }
  }

  def configuredOAuthProviders: List[OAuthProvider] = OAuthProvider.all.filter(isOAuthConfigured)

  /** No SMTP host means mail is logged rather than sent — fine in development, where the verification link is read off
    * stdout, and refused in production by [[productionIssues]] once verification is mandatory.
    */
  def isMailConfigured: Boolean = mail.smtp.host.nonEmpty

  /** Settings that are fine for local development but unsafe in production. `Main` refuses to start while this is
    * non-empty, so a missing `${?ENV_VAR}` override can't silently downgrade a production deployment to development
    * defaults (session cookies without `Secure` being the worst of them).
    */
  def productionIssues: List[String] = {
    if (!isProduction) {
      Nil
    } else {
      List(
        Option.when(!session.cookieSecure)(
          "session.cookie-secure is false, so session cookies would be sent over plain HTTP (set SESSION_COOKIE_SECURE=true)"
        ),
        Option.when(!app.publicBaseUrl.startsWith("https://"))(
          s"app.public-base-url is '${app.publicBaseUrl}', which is not https (set PUBLIC_BASE_URL)"
        ),
        Option.when(db.password == AppConfig.developmentDbPassword)(
          "db.password is still the development default (set DB_PASSWORD)"
        ),
        // Mandatory verification with no way to send the link locks every new account out
        // permanently — in dev the same combination is allowed, since LoggingEmailSender puts the
        // link on stdout where a developer can follow it.
        Option.when(app.requireEmailVerification && !isMailConfigured)(
          "app.require-email-verification is on but no SMTP host is configured, so nobody could ever " +
            "receive a verification link (set SMTP_HOST, or clear REQUIRE_EMAIL_VERIFICATION)"
        ),
      ).flatten
    }
  }
}

object AppConfig {
  private val configDesc = deriveConfig[AppConfig]

  /** Must match the `db.password` default in application.conf. */
  val developmentDbPassword = "webapp1"

  /** The version stamped on the generated OpenAPI document and reported by the system overview. One constant rather
    * than a literal in each place, so the document and the screen cannot disagree about which build is running.
    */
  val apiVersion = "0.1.0"

  /** application.conf keys are kebab-case; case class fields stay idiomatic camelCase and the provider maps between the
    * two. `${?ENV_VAR}` substitution is Typesafe Config's own, so environment overrides keep working.
    */
  val provider: ConfigProvider = TypesafeConfigProvider.fromResourcePath().kebabCase

  /** Installs [[provider]] as *the* config provider rather than only reading through it here, so that a stray
    * `ZIO.config` anywhere else — ours or a library's — resolves against application.conf instead of silently falling
    * back to environment variables and system properties. `Main` puts this in its `bootstrap`; [[live]] composes it in
    * as well so the layer stays self-sufficient for tests, which have their own bootstrap.
    */
  val configProvider: ZLayer[Any, Nothing, Unit] = Runtime.setConfigProvider(provider)

  val live: ZLayer[Any, Config.Error, AppConfig] = configProvider >>> ZLayer(ZIO.config(configDesc))
}
