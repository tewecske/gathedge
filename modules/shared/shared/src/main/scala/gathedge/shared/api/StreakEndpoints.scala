package gathedge.shared.api

import gathedge.shared.dto.StreakResponse
import zio.http.Method
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, outFailure}
import ApiSchemas.given

/** The signed-in account's daily play streak. Playing a game writes it (see `StreakService.recordPlay`); this endpoint
  * only reads it, so there is no input and no codec error.
  */
object StreakEndpoints {

  val streak = {
    Endpoint(Method.GET / "api" / "me" / "streak").out[StreakResponse].outFailure(failure.unauthorized)
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(streak)
}
