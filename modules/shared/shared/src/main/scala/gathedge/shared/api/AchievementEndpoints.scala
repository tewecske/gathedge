package gathedge.shared.api

import gathedge.shared.dto.AchievementsResponse
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, outFailure}
import ApiSchemas.given

/** The method and path of every achievement call, written once. [[AchievementEndpoints]] builds its routes from these;
  * the frontend fills them in. No zio-http here, so the frontend can load this object.
  */
object AchievementPaths {

  import ApiMethod.*

  val achievements = ApiPath0(GET, "/api/me/achievements")

  val all: List[ApiPath] = List(achievements)
}

/** The signed-in account's achievements. Finishing a play awards them (see `AchievementService.evaluate`); this
  * endpoint reads them, so there is no input and no codec error.
  */
object AchievementEndpoints {

  private val paths = AchievementPaths

  val achievements = {
    Endpoint(ApiRoutes.route0(paths.achievements)).out[AchievementsResponse].outFailure(failure.unauthorized)
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(achievements)
}
