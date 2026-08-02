package webapp1.backend.http

import zio.*
import zio.http.*
import zio.test.*

/** The reason to describe endpoints declaratively rather than build them by hand: the description is machine-readable,
  * so the OpenAPI document is derived from the same value the server is implemented against and cannot drift from it.
  *
  * `DocsRoutes` serves both the document and the Swagger UI, so these cover the generation and the routes that expose
  * it.
  */
object AdminOpenApiSpec extends ZIOSpecDefault {

  private val openApi = DocsRoutes.openApi

  private def run(request: Request) = {
    RouteRunner.runRoutes(DocsRoutes.routes, request)
  }

  def spec = {
    suite("admin OpenAPI")(
      test("every admin endpoint appears, with its path parameter and its error statuses") {
        val json = openApi.toJson
        assertTrue(
          openApi.paths.keySet.map(_.name) == Set("/api/admin/users", "/api/admin/users/{id}"),
          json.contains("\"201\""),
          json.contains("\"204\""),
          json.contains("\"404\""),
          json.contains("\"409\""),
          json.contains("CreateUserRequest"),
          json.contains("UpdateUserRequest"),
        )
      },
      // These three are produced by the aspects and by `handleFailures`, never by a handler, but a client generated
      // from this document can only decode the statuses it names.
      test("the statuses the aspects produce are documented too") {
        val json = openApi.toJson
        assertTrue(json.contains("\"401\""), json.contains("\"403\""), json.contains("\"500\""))
      },
      test("the Swagger UI is served, and the document under the title's name") {
        for {
          ui <- run(Request.get("/api/docs/openapi"))
          uiBody <- ui.body.asString.orDie
          document <- run(Request.get("/api/docs/openapi/webapp1-admin-api.json"))
          documentBody <- document.body.asString.orDie
        } yield assertTrue(
          ui.status == Status.Ok,
          uiBody.contains("swagger-ui"),
          document.status == Status.Ok,
          documentBody.contains("/api/admin/users"),
        )
      },
    ).provide(Scope.default) @@ TestAspect.timeout(60.seconds)
  }
}
