package gathedge.shared.api

import gathedge.shared.dto.StreakResponse
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, outFailure}
import ApiSchemas.given

/** The method and path of every streak call, written once. [[StreakEndpoints]] builds its routes from these; the
  * frontend fills them in. No zio-http here, so the frontend can load this object.
  */
object StreakPaths {

  import ApiMethod.*

  val streak = ApiPath0(GET, "/api/me/streak")

  val all: List[ApiPath] = List(streak)
}

/** The signed-in account's daily play streak. Finishing a game writes it (see `StreakService.recordPlay`); this
  * endpoint only reads it, so there is no input and no codec error.
  */
object StreakEndpoints {

  private val paths = StreakPaths

  val streak = {
    Endpoint(ApiRoutes.route0(paths.streak)).out[StreakResponse].outFailure(failure.unauthorized)
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(streak)
}
