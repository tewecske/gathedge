package gathedge.frontend.api

import gathedge.shared.api.{
  AdminEndpoints,
  AuthEndpoints,
  GameEndpoints,
  GroupEndpoints,
  ProgressShareEndpoints,
  WordEndpoints,
}
import zio.http.endpoint.Endpoint
import zio.test._

/** The frontend no longer builds its calls from the zio-http `Endpoint` values — `HttpClient` and the `*ApiClient`
  * files spell out the method and path of every call by hand, and the browser bundle carries no zio-http client. This
  * spec is what replaces the compile-time link: it renders every described endpoint to `METHOD /path/{param}` and
  * asserts the `*ApiClient` files call exactly that set.
  *
  * When it fails: an endpoint was added, removed, renamed or re-verbed in `shared/api`. Update the matching
  * `*ApiClient` method and the [[clientRoutes]] list below together.
  */
object ApiPathParitySpec extends ZIOSpecDefault {

  private def render(endpoints: List[Endpoint[?, ?, ?, ?, ?]]): Set[String] = {
    endpoints.map(e => s"${e.route.method.render} ${e.route.pathCodec.render}").toSet
  }

  private val endpointRoutes: Set[String] = {
    render(
      AuthEndpoints.all ++
        WordEndpoints.all ++
        GameEndpoints.all ++
        GroupEndpoints.all ++
        ProgressShareEndpoints.all ++
        AdminEndpoints.all
    )
  }

  /** `POST /api/words/tags/{tagId}/bulk-upload/preview` is described but is deliberately not called through an
    * `*ApiClient`: `BulkUploadDialog` speaks to it with a raw `XMLHttpRequest` for upload-progress events, which
    * neither `FetchStream` nor the old zio-http client exposes.
    */
  private val notThroughClient: Set[String] = Set("POST /api/words/tags/{tagId}/bulk-upload/preview")

  /** Every `(method, path)` the `*ApiClient` files call. `oauthStartUrl` is excluded — it is a document navigation, not
    * a `fetch`.
    */
  private val clientRoutes: Set[String] = Set(
    // ApiClient
    "POST /api/auth/signup",
    "POST /api/auth/verify",
    "POST /api/auth/verification/resend",
    "POST /api/auth/login",
    "POST /api/auth/password/forgot",
    "POST /api/auth/password/reset",
    "POST /api/auth/logout",
    "GET /api/me",
    "PUT /api/me/theme",
    "PUT /api/me/locale",
    "POST /api/guest",
    "POST /api/guest/code",
    "POST /api/guest/claim",
    "POST /api/auth/upgrade",
    "GET /api/auth/providers",
    "GET /api/auth/captcha-status",
    "GET /api/me/identities",
    "DELETE /api/me/identities/{provider}",
    "PUT /api/me/password",
    // WordApiClient
    "GET /api/words",
    "GET /api/words/{id}",
    "POST /api/words",
    "POST /api/words/{id}/translations",
    "DELETE /api/words/{id}/translations/{translationId}",
    "GET /api/tags",
    "POST /api/tags",
    "PUT /api/tags/{tagId}",
    "DELETE /api/tags/{tagId}",
    "POST /api/tags/{tagId}/copy",
    "PUT /api/words/{id}/tags/{tagId}",
    "DELETE /api/words/{id}/tags/{tagId}",
    "PUT /api/words/{id}/tags/{tagId}/translations/{translationWordId}",
    "DELETE /api/words/{id}/tags/{tagId}/translations/{translationWordId}",
    "POST /api/words/tags/{tagId}/bulk-upload/confirm",
    // GameApiClient
    "GET /api/games/setup",
    "GET /api/games/setup/words",
    "GET /api/games/mine",
    "POST /api/games",
    "GET /api/games/{slug}",
    "PATCH /api/games/{slug}",
    "POST /api/games/{slug}/plays",
    "GET /api/games/{slug}/plays/setup",
    "GET /api/games/plays/{playId}/prompt",
    "POST /api/games/plays/{playId}/answers",
    "GET /api/games/plays/{playId}/results",
    "GET /api/games/{slug}/plays",
    "GET /api/games/{slug}/plays/{playId}",
    "GET /api/games/plays/mine",
    // GroupApiClient
    "GET /api/groups",
    "GET /api/groups/{groupId}",
    "POST /api/groups",
    "POST /api/groups/join",
    "POST /api/groups/{groupId}/leave",
    "POST /api/groups/{groupId}/invite-code/regenerate",
    "PUT /api/groups/{groupId}/members/{userId}/role",
    "DELETE /api/groups/{groupId}/members/{userId}",
    "PUT /api/groups/{groupId}/tags/{tagId}",
    "DELETE /api/groups/{groupId}/tags/{tagId}",
    // ProgressShareApiClient
    "POST /api/progress-shares/code",
    "POST /api/progress-shares/redeem",
    "GET /api/progress-shares/shared-with-me",
    "GET /api/progress-shares/viewers",
    "GET /api/progress-shares/{sharerUserId}/plays",
    "DELETE /api/progress-shares/viewers/{viewerUserId}",
    // AdminApiClient
    "GET /api/admin/users",
    "GET /api/admin/users/{id}",
    "POST /api/admin/users",
    "PUT /api/admin/users/{id}",
    "DELETE /api/admin/users/{id}",
    "GET /api/admin/users/{id}/detail",
    "GET /api/admin/users/{id}/plays",
    "POST /api/admin/users/{id}/verify-email",
    "POST /api/admin/users/{id}/verification/resend",
    "DELETE /api/admin/users/{id}/sessions",
    "DELETE /api/admin/users/{id}/identities/{provider}",
    "DELETE /api/admin/users/{id}/lockout",
    "GET /api/admin/audit",
    "GET /api/admin/login-attempts",
    "GET /api/admin/rate-limits",
    "POST /api/admin/rate-limits/clear",
    "GET /api/admin/system",
    "POST /api/admin/system/prune",
    "GET /api/admin/word-forms/anomalies",
    "POST /api/admin/word-forms/anomalies/delete",
    "GET /api/admin/usage/routes",
    "GET /api/admin/usage/suspicious",
  )

  def spec = {
    suite("ApiPathParitySpec")(
      test("every described endpoint is called by an *ApiClient (bulk-upload preview excepted)") {
        assertTrue((endpointRoutes -- clientRoutes) == notThroughClient)
      },
      test("no *ApiClient call names a path that is not a described endpoint") {
        assertTrue((clientRoutes -- endpointRoutes).isEmpty)
      },
    )
  }
}
