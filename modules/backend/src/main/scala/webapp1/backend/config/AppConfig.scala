package webapp1.backend.config

import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider

enum AppEnv derives CanEqual {
  case Dev, Production
}

object AppEnv {
  def parse(s: String): AppEnv = {
    if (s.trim.equalsIgnoreCase("production")) Production else Dev
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
}

object AppConfig {
  private val configDesc = deriveConfig[AppConfig]

  // application.conf keys are kebab-case; case class fields stay idiomatic camelCase
  // and the provider maps between the two.
  val live: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer {
      val provider = TypesafeConfigProvider.fromResourcePath().kebabCase
      provider.load(configDesc)
    }
}
