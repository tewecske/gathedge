package webapp1.backend.http

import zio.*
import zio.http.*
import zio.http.endpoint.openapi.OpenAPI
import zio.test.*

/** The reason to describe endpoints declaratively rather than build them by hand: the description is machine-readable,
  * so the OpenAPI document is derived from the same values the server is implemented against and cannot drift from
  * them.
  *
  * `DocsRoutes` serves both the document and the Swagger UI, so these cover the generation and the routes that expose
  * it.
  */
object OpenApiSpec extends ZIOSpecDefault {

  private val openApi = DocsRoutes.openApi

  private def run(request: Request) = {
    RouteRunner.runRoutes(DocsRoutes.routes, request)
  }

  private val paths = openApi.paths.keySet.map(_.name)

  /** Every operation in the document as `("METHOD", "/path")`, paired with whether it declares the session requirement.
    * `PathItem` keeps one `Option[Operation]` field per method, so there is no way to fold over them.
    */
  private val operations: Set[(String, String, Boolean)] = {
    openApi
      .paths
      .toSet
      .flatMap { (entry: (OpenAPI.Path, OpenAPI.PathItem)) =>
        val (path, item) = entry
        val byMethod = List(
          "GET" -> item.get,
          "PUT" -> item.put,
          "POST" -> item.post,
          "DELETE" -> item.delete,
          "OPTIONS" -> item.options,
          "HEAD" -> item.head,
          "PATCH" -> item.patch,
          "TRACE" -> item.trace,
        )
        byMethod.collect { case (method, Some(operation)) =>
          (method, path.name, operation.security.nonEmpty)
        }
      }
  }

  def spec = {
    suite("OpenAPI")(
      test("every described resource appears, with its path parameters") {
        assertTrue(
          paths ==
            Set(
              "/api/auth/signup",
              "/api/auth/login",
              "/api/auth/logout",
              "/api/me",
              "/api/me/theme",
              "/api/todos",
              "/api/todos/{id}/status",
              "/api/groups",
              "/api/groups/{id}",
              "/api/groups/{id}/pairs",
              "/api/groups/{id}/members",
              "/api/groups/{id}/members/{userId}",
              "/api/groups/{id}/invitations",
              "/api/invitations/{token}",
              "/api/invitations/{token}/accept",
              "/api/admin/users",
              "/api/admin/users/{id}",
            )
        )
      },
      // The two Google routes are the deliberate omission: they are browser redirects rather than a body protocol and
      // stay on the imperative DSL, so they describe nothing to generate from.
      test("the Google OAuth redirects are the only routes missing") {
        assertTrue(!paths.exists(_.contains("google")))
      },
      test("request and response bodies are named, and the success statuses are there") {
        val json = openApi.toJson
        assertTrue(
          json.contains("\"201\""),
          json.contains("\"204\""),
          json.contains("SignupRequest"),
          json.contains("CreateTodoRequest"),
          json.contains("InviteMemberRequest"),
          json.contains("CreateUserRequest"),
        )
      },
      // A client generated from this document can only decode the statuses it names, and the aspects, the rate limiter
      // and `handleFailures` can answer with statuses no handler ever raises.
      test("every failure status is documented, including the ones only the aspects produce") {
        val json = openApi.toJson
        assertTrue(
          json.contains("\"400\""),
          json.contains("\"401\""),
          json.contains("\"403\""),
          json.contains("\"404\""),
          json.contains("\"409\""),
          json.contains("\"429\""),
          json.contains("\"500\""),
        )
      },
      // The session is a `HandlerAspect` on whole `Routes` values, so no description in `shared` states it and the
      // generator cannot infer it; `DocsRoutes` supplies the split. These pin both halves of it, so adding a public
      // endpoint without listing it (or a protected one and listing it by mistake) fails here rather than silently
      // documenting the wrong thing.
      test("the session cookie is declared as a security scheme") {
        val schemes = openApi.components.toList.flatMap(_.securitySchemes.keys.map(_.name))
        val json = openApi.toJson
        assertTrue(
          schemes == List("sessionCookie"),
          json.contains("\"apiKey\""),
          json.contains("\"in\":\"cookie\""),
          json.contains("\"name\":\"session\""),
        )
      },
      test("exactly the endpoints reachable without a session are exempt from it") {
        val open = operations.collect { case (method, path, false) =>
          (method, path)
        }
        assertTrue(
          open ==
            Set(
              ("POST", "/api/auth/signup"),
              ("POST", "/api/auth/login"),
              ("POST", "/api/auth/logout"),
              ("GET", "/api/invitations/{token}"),
            )
        )
      },
      test("every other operation requires the session cookie") {
        val guarded = operations.collect { case (method, path, true) =>
          (method, path)
        }
        assertTrue(
          guarded.size == operations.size - 4,
          guarded.contains(("GET", "/api/me")),
          guarded.contains(("POST", "/api/todos")),
          guarded.contains(("POST", "/api/invitations/{token}/accept")),
          guarded.contains(("DELETE", "/api/admin/users/{id}")),
        )
      },
      test("the Swagger UI is served, and the document under the title's name") {
        for {
          ui <- run(Request.get("/api/docs/openapi"))
          uiBody <- ui.body.asString.orDie
          document <- run(Request.get("/api/docs/openapi/webapp1-api.json"))
          documentBody <- document.body.asString.orDie
        } yield assertTrue(
          ui.status == Status.Ok,
          uiBody.contains("swagger-ui"),
          document.status == Status.Ok,
          documentBody.contains("/api/admin/users"),
          documentBody.contains("/api/todos"),
        )
      },
    ).provide(Scope.default) @@ TestAspect.timeout(60.seconds)
  }
}
