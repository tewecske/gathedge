package webapp1.backend.config

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

final case class AppSection(env: String, serverPort: Int, publicBaseUrl: String)
final case class DbSection(url: String, user: String, password: String)
final case class SessionSection(cookieSecure: Boolean)
final case class BootstrapAdminSection(email: String, password: String)
final case class GoogleSection(clientId: String, clientSecret: String, redirectUri: String)

final case class AppConfig(
  app: AppSection,
  db: DbSection,
  session: SessionSection,
  bootstrapAdmin: BootstrapAdminSection,
  google: GoogleSection,
) {
  def appEnv: AppEnv = AppEnv.parse(app.env)
  def isProduction: Boolean = appEnv == AppEnv.Production
  def isGoogleOAuthConfigured: Boolean = google.clientId.nonEmpty && google.clientSecret.nonEmpty

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
      ).flatten
    }
  }
}

object AppConfig {
  private val configDesc = deriveConfig[AppConfig]

  /** Must match the `db.password` default in application.conf. */
  val developmentDbPassword = "webapp1"

  // application.conf keys are kebab-case; case class fields stay idiomatic camelCase
  // and the provider maps between the two.
  val live: ZLayer[Any, Config.Error, AppConfig] = ZLayer {
    val provider = TypesafeConfigProvider.fromResourcePath().kebabCase
    provider.load(configDesc)
  }
}
