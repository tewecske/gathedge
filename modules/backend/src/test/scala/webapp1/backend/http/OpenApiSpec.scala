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

  /** Every operation as `("METHOD", "/path") -> the status codes it documents`. */
  private val statuses: Map[(String, String), Set[Int]] = {
    openApi
      .paths
      .toList
      .flatMap { entry =>
        val (path, item) = entry
        val byMethod = List("GET" -> item.get, "PUT" -> item.put, "POST" -> item.post, "DELETE" -> item.delete)
        byMethod.collect { case (method, Some(operation)) =>
          val codes = operation
            .responses
            .keySet
            .collect { case OpenAPI.StatusOrDefault.StatusValue(status) =>
              status.code
            }
          ((method, path.name), codes)
        }
      }
      .toMap
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
      // Each operation documents exactly the statuses it can answer with — its own handler's failures plus whatever
      // the aspects wrapped around its `Routes` value can produce instead of running it. Pinning the whole table is
      // the point of describing failures per endpoint rather than uniformly: it is the only place the two halves of
      // that judgement (a mapping in `ApiFailures`, an aspect in `RouteSupport`) are checked against the descriptions.
      //
      // Reading the table: 500 is everywhere, because `handleFailures` turns a defect on any route into one. 401 is on
      // everything behind `authenticated`/`adminOnly`, so only `GET /api/invitations/{token}` and the three anonymous
      // auth routes lack it. 403 follows `csrf` — hence its absence from the GETs — except under `adminOnly`, which
      // answers 403 for a non-administrator regardless of method, and on the group endpoints, where `GroupService`
      // raises it for membership and role. 429 exists only where the rate limiter does. And 404 is a resource the
      // request named and could not be found: a request whose path matches no route at all is answered by
      // `RouteSupport`'s `notFound` replacement, never reaches an endpoint, and so is documented on none of them.
      test("every operation documents exactly the statuses it can answer with") {
        assertTrue(
          statuses ==
            Map(
              ("POST", "/api/auth/signup") -> Set(201, 400, 401, 403, 409, 429, 500),
              ("POST", "/api/auth/login") -> Set(200, 400, 401, 403, 409, 429, 500),
              ("POST", "/api/auth/logout") -> Set(204, 403, 500),
              ("GET", "/api/me") -> Set(200, 401, 500),
              ("PUT", "/api/me/theme") -> Set(200, 401, 403, 500),
              ("GET", "/api/todos") -> Set(200, 401, 500),
              ("POST", "/api/todos") -> Set(201, 400, 401, 403, 404, 500),
              ("PUT", "/api/todos/{id}/status") -> Set(200, 400, 401, 403, 404, 500),
              ("GET", "/api/groups") -> Set(200, 401, 500),
              ("POST", "/api/groups") -> Set(201, 400, 401, 403, 404, 409, 500),
              ("GET", "/api/groups/{id}") -> Set(200, 400, 401, 403, 404, 409, 500),
              ("DELETE", "/api/groups/{id}") -> Set(204, 400, 401, 403, 404, 409, 500),
              ("GET", "/api/groups/{id}/pairs") -> Set(200, 400, 401, 403, 404, 409, 500),
              ("POST", "/api/groups/{id}/pairs") -> Set(201, 400, 401, 403, 404, 409, 500),
              ("GET", "/api/groups/{id}/members") -> Set(200, 400, 401, 403, 404, 409, 500),
              ("DELETE", "/api/groups/{id}/members/{userId}") -> Set(204, 400, 401, 403, 404, 409, 500),
              ("PUT", "/api/groups/{id}/members/{userId}") -> Set(204, 400, 401, 403, 404, 409, 500),
              ("POST", "/api/groups/{id}/invitations") -> Set(204, 400, 401, 403, 404, 409, 500),
              ("GET", "/api/invitations/{token}") -> Set(200, 400, 403, 404, 409, 500),
              ("POST", "/api/invitations/{token}/accept") -> Set(200, 400, 401, 403, 404, 409, 500),
              ("GET", "/api/admin/users") -> Set(200, 401, 403, 500),
              ("POST", "/api/admin/users") -> Set(201, 400, 401, 403, 404, 409, 500),
              ("GET", "/api/admin/users/{id}") -> Set(200, 400, 401, 403, 404, 409, 500),
              ("PUT", "/api/admin/users/{id}") -> Set(200, 400, 401, 403, 404, 409, 500),
              ("DELETE", "/api/admin/users/{id}") -> Set(204, 400, 401, 403, 404, 409, 500),
            )
        )
      },
      // The uniform set this replaced put all seven failure statuses on all 25 operations. Nothing enforces the
      // arithmetic; it is here so a change that quietly re-widens the descriptions shows up as a number going back up.
      test("no operation documents a status only some other endpoint can answer with") {
        val successCodes = Set(200, 201, 204)
        val declared = statuses.values.map(_.diff(successCodes).size).sum
        assertTrue(declared == 125, declared < 25 * 7)
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
