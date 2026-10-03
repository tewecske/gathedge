package gathedge.backend.http

import gathedge.backend.service.{AchievementService, AuthService}
import gathedge.shared.api.AchievementEndpoints
import gathedge.shared.domain.User
import zio.*
import zio.http.*

/** The account's own achievements. Session only, like `StreakRoutes`; the aspect is on the `Routes` value. */
object AchievementRoutes {

  private val achievementsRoute = {
    AchievementEndpoints.achievements.implementHandler(
      handler((_: Unit) => ZIO.serviceWithZIO[User](user => AchievementService.overview(user.id).orDie))
    )
  }

  val routes: Routes[AuthService & AchievementService, Response] = {
    Routes(achievementsRoute) @@ RouteSupport.authenticated @@ RouteSupport.csrf
  }
}
