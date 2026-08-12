package webapp1.backend.http

import zio.*
import zio.http.*
import zio.http.Status.*
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
    openApi.paths.toSet
      .flatMap { (entry: (OpenAPI.Path, OpenAPI.PathItem)) =>
        val (path, item) = entry
        val byMethod     = List(
          "GET"     -> item.get,
          "PUT"     -> item.put,
          "POST"    -> item.post,
          "DELETE"  -> item.delete,
          "OPTIONS" -> item.options,
          "HEAD"    -> item.head,
          "PATCH"   -> item.patch,
          "TRACE"   -> item.trace,
        )
        byMethod.collect { case (method, Some(operation)) =>
          (method, path.name, operation.security.nonEmpty)
        }
      }
  }

  /** Every operation as `("METHOD", "/path") -> the statuses it documents`. */
  private val statuses: Map[(String, String), Set[Status]] = {
    openApi.paths.toList.flatMap { entry =>
      val (path, item) = entry
      val byMethod     = List("GET" -> item.get, "PUT" -> item.put, "POST" -> item.post, "DELETE" -> item.delete)
      byMethod.collect { case (method, Some(operation)) =>
        val declared = operation.responses.keySet
          .collect { case OpenAPI.StatusOrDefault.StatusValue(status) =>
            status
          }
        ((method, path.name), declared)
      }
    }.toMap
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
              "/api/auth/providers",
              "/api/auth/verify",
              "/api/auth/verification/resend",
              "/api/me",
              "/api/me/theme",
              "/api/me/locale",
              "/api/me/identities",
              "/api/me/identities/{provider}",
              "/api/me/password",
              "/api/admin/users",
              "/api/admin/users/{id}",
              "/api/admin/users/{id}/detail",
              "/api/admin/users/{id}/verify-email",
              "/api/admin/users/{id}/verification/resend",
              "/api/admin/users/{id}/sessions",
              "/api/admin/users/{id}/identities/{provider}",
              "/api/admin/users/{id}/lockout",
              "/api/admin/audit",
              "/api/admin/login-attempts",
              "/api/admin/rate-limits",
              "/api/admin/rate-limits/clear",
              "/api/admin/system",
              "/api/admin/system/prune",
            )
        )
      },
      // The two OAuth redirect routes are the deliberate omission: they are browser redirects rather than a body
      // protocol and stay on the imperative DSL, so they describe nothing to generate from. `/api/auth/providers` is
      // an ordinary described endpoint and does appear — it answers a JSON body, it just happens to be about them.
      test("the OAuth redirect routes are the only ones missing") {
        assertTrue(
          !paths.exists(_.endsWith("/start")),
          !paths.exists(_.endsWith("/callback")),
          paths.contains("/api/auth/providers"),
        )
      },
      test("request and response bodies are named, and the success statuses are there") {
        val json = openApi.toJson
        assertTrue(
          json.contains(s"\"${Created.code}\""),
          json.contains(s"\"${NoContent.code}\""),
          json.contains("SignupRequest"),
          json.contains("UpdateThemeRequest"),
          json.contains("CreateUserRequest"),
        )
      },
      // Each operation documents exactly the statuses a well-behaved caller can receive from it: its own handler's
      // failures, plus the session aspect's 401. Pinning the whole table is the point of describing failures per
      // endpoint rather than uniformly: it is the only place the two halves of that judgement (a mapping in
      // `ApiFailures`, an aspect in `RouteSupport`) are checked against the descriptions.
      //
      // Reading the table: 401 is on everything behind `authenticated`/`adminOnly`, so only the anonymous auth routes
      // lack it. 400 is a handler's validation failure everywhere except `PUT /api/me/theme` and `PUT /api/me/locale`,
      // whose service calls are `.orDie`'d: there it is only reachable through `ApiEndpoint.codecError`, and declared
      // so the client decodes it instead of dying. 404 is a resource the request named and could not be found — a
      // request whose path matches no route at all is answered by `RouteSupport`'s `notFound` replacement, never
      // reaches an endpoint, and so is documented on none of them. 403 appears only on `POST /api/auth/login`, where
      // `AuthService` raises it for an unconfirmed address; the CSRF and `adminOnly` aspects answer 403 too but
      // describe it nowhere. 429 is only where the rate limiter is. And 500 is on nothing at all. The last three are
      // `ApiEndpoint.failure`'s rule: a status a well-behaved caller cannot provoke is not part of the contract this
      // document states.
      test("every operation documents exactly the statuses it can answer with") {
        assertTrue(
          statuses ==
            Map(
              ("POST", "/api/auth/signup")                              -> Set(Created, BadRequest, Unauthorized, Conflict, TooManyRequests),
              // The 403 is `AuthFailure.EmailNotVerified` — the service's own answer, not an aspect's, which is why
              // this is the only path in the API that documents one.
              ("POST", "/api/auth/login")                               -> Set(Ok, BadRequest, Unauthorized, Forbidden, Conflict, TooManyRequests),
              ("POST", "/api/auth/logout")                              -> Set(NoContent),
              // Public, no input, no aspect: the one operation in the API that documents no failure status at all.
              ("GET", "/api/auth/providers")                            -> Set(Ok),
              // One 400 for an unknown, spent or expired token alike; nothing else is reachable.
              ("POST", "/api/auth/verify")                              -> Set(NoContent, BadRequest),
              // Answers 204 for every address, known or not, so the limiter's 429 is the only visible failure.
              ("POST", "/api/auth/verification/resend")                 -> Set(NoContent, BadRequest, TooManyRequests),
              ("GET", "/api/me")                                        -> Set(Ok, Unauthorized),
              ("PUT", "/api/me/theme")                                  -> Set(Ok, BadRequest, Unauthorized),
              ("PUT", "/api/me/locale")                                 -> Set(Ok, BadRequest, Unauthorized),
              ("GET", "/api/me/identities")                             -> Set(Ok, Unauthorized),
              // 409 is the lockout guard (unlinking the last credential); 400 covers both an unparseable
              // provider segment and one that is simply not linked, since `AuthFailure` has no NotFound case.
              ("DELETE", "/api/me/identities/{provider}")               -> Set(NoContent, BadRequest, Unauthorized, Conflict),
              ("PUT", "/api/me/password")                               -> Set(NoContent, BadRequest, Unauthorized),
              ("GET", "/api/admin/users")                               -> Set(Ok, BadRequest, Unauthorized),
              ("POST", "/api/admin/users")                              -> Set(Created, BadRequest, Unauthorized, NotFound, Conflict),
              ("GET", "/api/admin/users/{id}")                          -> Set(Ok, BadRequest, Unauthorized, NotFound, Conflict),
              ("PUT", "/api/admin/users/{id}")                          -> Set(Ok, BadRequest, Unauthorized, NotFound, Conflict),
              ("DELETE", "/api/admin/users/{id}")                       -> Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              // The six account-diagnostic operations all go through `ApiFailures.admin`, so they carry its whole
              // union — the same residual slack the group endpoints have, and narrowing it means narrowing
              // `AdminService`'s signatures rather than these descriptions. The 409 is real on the unlink (last
              // credential) and only theoretical on the rest.
              ("GET", "/api/admin/users/{id}/detail")                   -> Set(Ok, BadRequest, Unauthorized, NotFound, Conflict),
              ("POST", "/api/admin/users/{id}/verify-email")            ->
                Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              ("POST", "/api/admin/users/{id}/verification/resend")     ->
                Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              ("DELETE", "/api/admin/users/{id}/sessions")              ->
                Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              ("DELETE", "/api/admin/users/{id}/identities/{provider}") ->
                Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              ("DELETE", "/api/admin/users/{id}/lockout")               ->
                Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              // The read-only operations declare only what they can actually answer: a 400 where a query
              // parameter can fail to decode (`ApiEndpoint.codecError`), and the aspect's 401. `rateLimits`,
              // `systemOverview` and `systemPrune` take no input at all, so they have no 400 to declare — which is
              // what the user list used to be, before paging gave it query parameters of its own.
              ("GET", "/api/admin/audit")                               -> Set(Ok, BadRequest, Unauthorized),
              ("GET", "/api/admin/login-attempts")                      -> Set(Ok, BadRequest, Unauthorized),
              ("GET", "/api/admin/rate-limits")                         -> Set(Ok, Unauthorized),
              ("POST", "/api/admin/rate-limits/clear")                  -> Set(NoContent, BadRequest, Unauthorized),
              ("GET", "/api/admin/system")                              -> Set(Ok, Unauthorized),
              ("POST", "/api/admin/system/prune")                       -> Set(Ok, Unauthorized),
            )
        )
      },
      // The uniform set this started from put all seven failure statuses on every operation. Describing each
      // endpoint's own failures, and then dropping the three a well-behaved caller cannot provoke, is what takes it to
      // the count below: 74 across 27 operations. (It was 136 across 44 while the Todo and Group example features were
      // in the skeleton, and the shape of that arithmetic is the same — an operation declares its handler's failures
      // plus a 401 where an aspect guards it, plus a 400 wherever it has an input, a query parameter or a header codec
      // that can fail to decode.) Nothing enforces the total; it is here so a change that quietly re-widens the
      // descriptions shows up as a number going up. The three assertions under it are the rule itself, stated where it
      // can be checked.
      test("no operation documents a status only some other endpoint can answer with") {
        val successes: Set[Status] = Set(Ok, Created, NoContent)
        val declared               = statuses.values.map(_.diff(successes).size).sum
        // `keySet.filter`, not `collect` over the map: a `collect` yielding the key pair rebuilds a *Map* keyed by
        // method, which keeps one operation per verb and silently drops the rest.
        val describes              = { (status: Status) =>
          {
            statuses.keySet.filter(operation => statuses(operation).contains(status))
          }
        }
        assertTrue(
          declared == 74,
          declared < statuses.size * 7,
          // A service's own answer, never the CSRF or `adminOnly` aspect's: `AuthService`'s unverified-email refusal
          // on login is the only one in the skeleton. A feature whose service raises a permission failure of its own
          // adds its paths here.
          describes(Forbidden) == Set(("POST", "/api/auth/login")),
          // The rate limiter wraps signup, login and the verification resend, and nothing else.
          describes(TooManyRequests) ==
            Set(("POST", "/api/auth/signup"), ("POST", "/api/auth/login"), ("POST", "/api/auth/verification/resend")),
          describes(InternalServerError).isEmpty,
        )
      },
      // The session is a `HandlerAspect` on whole `Routes` values, so no description in `shared` states it and the
      // generator cannot infer it; `DocsRoutes` supplies the split. These pin both halves of it, so adding a public
      // endpoint without listing it (or a protected one and listing it by mistake) fails here rather than silently
      // documenting the wrong thing.
      test("the session cookie is declared as a security scheme") {
        val schemes = openApi.components.toList.flatMap(_.securitySchemes.keys.map(_.name))
        val json    = openApi.toJson
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
              ("GET", "/api/auth/providers"),
              // Both are reached by an account that cannot sign in yet, so neither can be behind the session.
              ("POST", "/api/auth/verify"),
              ("POST", "/api/auth/verification/resend"),
            )
        )
      },
      test("every other operation requires the session cookie") {
        val guarded = operations.collect { case (method, path, true) =>
          (method, path)
        }
        assertTrue(
          guarded.size == operations.size - 6,
          guarded.contains(("GET", "/api/me")),
          guarded.contains(("GET", "/api/me/identities")),
          guarded.contains(("PUT", "/api/me/password")),
          guarded.contains(("PUT", "/api/me/theme")),
          guarded.contains(("DELETE", "/api/admin/users/{id}")),
        )
      },
      test("the Swagger UI is served, and the document under the title's name") {
        for {
          ui           <- run(Request.get("/api/docs/openapi"))
          uiBody       <- ui.body.asString.orDie
          document     <- run(Request.get("/api/docs/openapi/webapp1-api.json"))
          documentBody <- document.body.asString.orDie
        } yield assertTrue(
          ui.status == Status.Ok,
          uiBody.contains("swagger-ui"),
          document.status == Status.Ok,
          documentBody.contains("/api/admin/users"),
          documentBody.contains("/api/me/theme"),
        )
      },
    ).provide(Scope.default) @@ TestAspect.timeout(60.seconds)
  }
}
