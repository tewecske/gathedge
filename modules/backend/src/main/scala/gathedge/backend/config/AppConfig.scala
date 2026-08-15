package gathedge.backend.config

import gathedge.shared.domain.OAuthProvider
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
/** `guestRetentionDays` bounds only the *empty* guest accounts — one minted for a visitor who then tagged nothing. A
  * guest holding words is never swept whatever this says, because a transfer code for it may be written down somewhere;
  * see `SessionReaper.sweep`.
  */
final case class AppSection(
  env: String,
  serverHost: String,
  serverPort: Int,
  publicBaseUrl: String,
  requireEmailVerification: Boolean,
  trustedProxyHops: Int,
  loginAttemptRetentionDays: Int,
  guestRetentionDays: Int,
  /** How long a "forgot password" link stays redeemable. Config rather than a literal — unlike
    * `AuthService.verificationValidity` — because a password reset link is a stronger credential than a verification
    * one (it grants a new password outright rather than merely proving an address), so a deployment may reasonably want
    * a shorter window than the 24 hours verification links get.
    */
  passwordResetTokenValidityHours: Int,
)

/** `schema` is the Postgres schema this application owns, and it is one setting read by two independent things that
  * must not disagree: `FlywayMigrator` creates it and migrates into it, and `DataSourceFactory` sets it as every pooled
  * connection's `search_path`. Point them at different schemas and the app migrates one and queries another. SQLite has
  * no schema concept and ignores it entirely.
  */
final case class DbSection(url: String, user: String, password: String, schema: String)
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

/** Cloudflare Turnstile, which gates the anonymous account-taking and email-sending endpoints against bots.
  *
  * An empty `siteKey` or `secret` switches the whole feature off, exactly the way an empty SMTP host or OAuth client id
  * does: the captcha-status endpoint answers "not required", the frontend renders no widget, and the auth endpoints
  * skip verification. That is what keeps `sbt test` and the e2e suite green with no keys configured, and what lets a
  * private deployment run without bot protection.
  *
  * `siteKey` is public — it is handed to the browser so it can render the widget — while `secret` is server-only and
  * must never leave this process.
  *
  * `loginThreshold` is how many failed sign-in attempts one address may make before the sign-in form demands a captcha.
  * It sits below `RateLimiter`'s hard lockout (`InMemoryRateLimiter.maxAttempts`), so a human gets a captcha challenge
  * while an automated spray still hits the lockout. The count comes from the login rate-limit budget keyed on the
  * client address (`RateLimitKey.ip`), not from `login_attempts` — it is the same in-memory window that later
  * hard-blocks.
  */
final case class CaptchaSection(siteKey: String, secret: String, loginThreshold: Int)

/** Two independent, standing per-account caps on the vocabulary — not time-windowed like `RateLimiter`, so they get
  * their own section rather than a `RateLimitKey` namespace: a tag count and a pair count do not reset after a window,
  * they only ever go up or down with what the account still owns.
  *
  * Each has a *soft* threshold, which only warns (see `dto.TagResponse.warning`), and a *hard* one, which blocks (see
  * `WordFailure.TagQuotaExceeded`/`PairQuotaExceeded`). `WordService.checkQuota` states the boundary precisely: an
  * account may hold up to the hard limit; the write that would push it past that is refused, and one that would only
  * reach or pass the soft limit still succeeds, with a warning.
  *
  * `wordPairs*` counts `word_tag_pairs` **rows**, summed across every tag the account owns — not marks, since
  * [[gathedge.backend.db.WordRepository.pairTranslation]] writes one row per direction, so a single chip click adds two
  * toward it.
  */
final case class QuotaSection(
  tagsPerUserSoft: Int,
  tagsPerUserHard: Int,
  wordPairsPerUserSoft: Int,
  wordPairsPerUserHard: Int,
)

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
  captcha: CaptchaSection,
  netty: NettySection,
  quotas: QuotaSection,
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

  /** Captcha is on only when both halves of the Turnstile credential are set. */
  def isCaptchaConfigured: Boolean = captcha.siteKey.nonEmpty && captcha.secret.nonEmpty

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
  val developmentDbPassword = "gathedge"

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
