package gathedge.backend.http

import gathedge.backend.service.{AuthService, StreakService}
import gathedge.shared.api.StreakEndpoints
import gathedge.shared.domain.User
import zio.*
import zio.http.*

/** The account's own daily streak. Session only, like `GameRoutes.sessionRoutes`; the aspect is on the `Routes` value.
  */
object StreakRoutes {

  private val streakRoute = {
    StreakEndpoints.streak.implementHandler(
      handler((_: Unit) => ZIO.serviceWithZIO[User](user => StreakService.current(user.id).orDie))
    )
  }

  val routes: Routes[AuthService & StreakService, Response] = {
    Routes(streakRoute) @@ RouteSupport.authenticated @@ RouteSupport.csrf
  }
}
