package gathedge.backend.config

import zio.*
import zio.test.*

/** What the loaded configuration answers about Facebook, which two unrelated features now read.
  *
  * Both cases are built by copying the loaded config rather than by reading the ambient one, so the spec says the same
  * thing on a machine that happens to have `FACEBOOK_CLIENT_ID` in its environment.
  */
object AppConfigSpec extends ZIOSpecDefault {

  private def withFacebook(config: AppConfig, clientId: String, clientSecret: String): AppConfig = {
    val facebook = config.oauth.facebook.copy(clientId = clientId, clientSecret = clientSecret)
    config.copy(oauth = config.oauth.copy(facebook = facebook))
  }

  def spec = {
    suite("AppConfig")(
      test("messengerAppId is the Facebook app id, and is absent while Facebook is switched off") {
        for {
          loaded <- ZIO.service[AppConfig]
        } yield {
          val configured = withFacebook(loaded, "fb-app-id", "fb-secret")
          val halfDone   = withFacebook(loaded, "fb-app-id", "")
          val off        = withFacebook(loaded, "", "")
          assertTrue(
            configured.messengerAppId.contains("fb-app-id"),
            halfDone.messengerAppId.isEmpty,
            off.messengerAppId.isEmpty,
          )
        }
      }
    ).provide(AppConfig.live)
  }
}
