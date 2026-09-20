package gathedge.backend.config

import gathedge.backend.service.InMemoryRateLimiter
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
      },
      // `RateLimiter.live` (tests) has no AppConfig, so it carries copies of the file's defaults; this keeps them honest.
      // Read as text so an env override on the machine running the suite cannot change the answer.
      test("InMemoryRateLimiter's built-in defaults match application.conf") {
        val conf = scala.io.Source.fromResource("application.conf").mkString

        def default(key: String): Int = s"(?m)^\\s*$key = (\\d+)\\s*$$".r.findFirstMatchIn(conf).get.group(1).nn.toInt

        assertTrue(
          default("rate-limit-max-attempts") == InMemoryRateLimiter.maxAttempts,
          default("guest-mint-max-attempts") == InMemoryRateLimiter.maxAttempts,
          default("rate-limit-window-minutes").minutes == InMemoryRateLimiter.window,
          default("rate-limit-prune-interval-minutes").minutes == InMemoryRateLimiter.pruneInterval,
        )
      },
    ).provide(AppConfig.live)
  }
}
