package webapp1.backend.http

import webapp1.backend.{TestAuthLayers, TestDataSource}
import webapp1.backend.config.AppConfig
import webapp1.backend.db.{
  SqliteEmailVerificationTokenRepository,
  SqliteGroupInvitationRepository,
  SqliteGroupMemberRepository,
  SqliteGroupPairRepository,
  SqliteGroupRepository,
  SqliteOAuthIdentityRepository,
  SqliteSessionRepository,
  SqliteTodoRepository,
  SqliteUserRepository,
}
import webapp1.backend.security.{PasswordHasher, SessionAuth}
import webapp1.backend.service.{
  AdminService,
  AdminServiceLive,
  AuthService,
  AuthServiceLive,
  EmailSender,
  GroupService,
  GroupServiceLive,
  InMemoryRateLimiter,
  OAuthClients,
  TodoService,
  TodoServiceLive,
}
import webapp1.shared.api.ApiFailure
import webapp1.shared.domain.{Group, GroupMember, Theme, TodoItem, User}
import webapp1.shared.dto.{
  AuthResponse,
  CreateTodoRequest,
  CreateUserRequest,
  ErrorResponse,
  LoginRequest,
  ResendVerificationRequest,
  SignupRequest,
  SignupResponse,
  UpdateUserRequest,
  VerifyEmailRequest,
}
import zio.*
import zio.http.*
import zio.json.*
import zio.schema.codec.JsonCodec
import zio.test.*

import RouteRunner.{orDieWithFailure, runRoutes, withCsrf, withSession}

/** Every route file is implemented against the declarative `Endpoint` API, whose codecs come from zio-schema, while the
  * frontend's DTOs also derive zio-json codecs and the aspects in `RouteSupport` still write their error bodies with
  * those. Nothing in the type system connects the two stacks, so this spec asserts the actual bytes: response bodies
  * are decoded with the *zio-json* codec, and error bodies as `dto.ErrorResponse`.
  *
  * The per-resource behaviour lives in `RouteGuardsSpec`, `GroupRoutesSpec` and `InvitationRoutesSpec`; what is here is
  * the encoding — statuses, enum representations, empty 204s and the session cookie.
  */
object ApiEndpointsSpec extends ZIOSpecDefault {

  private val repos = {
    TestDataSource.sqlite >>> (
      SqliteUserRepository.live ++ SqliteSessionRepository.live ++ SqliteTodoRepository.live ++
        SqliteGroupRepository.live ++ SqliteGroupMemberRepository.live ++ SqliteGroupPairRepository.live ++
        SqliteGroupInvitationRepository.live ++ SqliteOAuthIdentityRepository.live ++
        SqliteEmailVerificationTokenRepository.live
    )
  }

  private val layer = {
    AppConfig.live ++
      ((AppConfig.live ++ Client.default) >>> OAuthClients.live) ++ (
        (repos ++ PasswordHasher.live ++ InMemoryRateLimiter.live ++ TestAuthLayers.emailAndConfig) >>>
          (AuthServiceLive.live ++ AdminServiceLive.live)
      ) ++
      (repos >>> TodoServiceLive.live) ++
      ((repos ++ TestAuthLayers.emailAndConfig) >>> GroupServiceLive.live)
  }

  private def signUp(email: String): ZIO[AuthService, Nothing, String] = {
    ZIO.serviceWithZIO[AuthService](service => orDieWithFailure(service.signup(email, "password123")).map(_._2.get))
  }

  /** Returns the acting administrator and their session id. `createUser` takes an acting-admin id for its audit log; 0
    * is the seeding path, the same one `AdminSeeder` uses.
    */
  private def adminSession(email: String): ZIO[AuthService & AdminService, Nothing, (User, String)] = {
    for {
      adminService <- ZIO.service[AdminService]
      authService <- ZIO.service[AuthService]
      admin <- orDieWithFailure(adminService.createUser(0L, email, "password123", isAdmin = true))
      session <- orDieWithFailure(authService.login(email, "password123")).map(_._2)
    } yield (admin, session)
  }

  private def body(response: Response): ZIO[Any, Nothing, String] = {
    response.body.asString.orDie
  }

  private def sessionCookie(response: Response): Option[Cookie.Response] = {
    response
      .headers(Header.SetCookie)
      .collectFirst {
        case Header.SetCookie(cookie) if cookie.name == SessionAuth.cookieName =>
          cookie
      }
  }

  def spec = {
    suite("described endpoints, on the wire")(
      suite("auth")(
        // The success value is a pair — the body and the `Set-Cookie` header — because the cookie is part of the
        // endpoint description. It still has to reach the client as a real header, not as a field in the JSON.
        test("signup answers 201 with a SignupResponse body and a session cookie in the header") {
          val request = withCsrf(
            Request.post("/api/auth/signup", Body.fromString(SignupRequest("new@example.com", "password123").toJson))
          )
          for {
            response <- runRoutes(AuthRoutes.routes, request)
            raw <- body(response)
          } yield assertTrue(
            response.status == Status.Created,
            raw.fromJson[SignupResponse].map(_.user.email) == Right("new@example.com"),
            // False only where verification is mandatory; the test config leaves the gate off.
            raw.fromJson[SignupResponse].map(_.signedIn) == Right(true),
            !raw.contains("Set-Cookie"),
            sessionCookie(response).exists(_.content.nonEmpty),
            sessionCookie(response).exists(_.isHttpOnly),
          )
        },
        test("login answers 200 and a cookie; the wrong password is a 401 in the usual ErrorResponse shape") {
          for {
            _ <- signUp("login@example.com")
            good <- runRoutes(
              AuthRoutes.routes,
              withCsrf(
                Request.post(
                  "/api/auth/login",
                  Body.fromString(LoginRequest("login@example.com", "password123").toJson),
                )
              ),
            )
            goodRaw <- body(good)
            bad <- runRoutes(
              AuthRoutes.routes,
              withCsrf(
                Request.post("/api/auth/login", Body.fromString(LoginRequest("login@example.com", "nope").toJson))
              ),
            )
            badRaw <- body(bad)
          } yield assertTrue(
            good.status == Status.Ok,
            goodRaw.fromJson[AuthResponse].map(_.user.email) == Right("login@example.com"),
            sessionCookie(good).exists(_.content.nonEmpty),
            bad.status == Status.Unauthorized,
            badRaw.fromJson[ErrorResponse].map(_.message) == Right("Invalid email or password"),
          )
        },
        // `.outCodec(HttpCodec.status(...))` rather than `.out[Unit](Status.NoContent)`: a 204 must carry no body and
        // no Content-Length, which is exactly what a browser client needs in order to decode it at all.
        test("logout is an empty 204 that expires the cookie, with or without a session") {
          for {
            session <- signUp("logout@example.com")
            withSessionResponse <- runRoutes(
              AuthRoutes.routes,
              withCsrf(withSession(Request.post("/api/auth/logout", Body.empty), session)),
            )
            raw <- body(withSessionResponse)
            anonymous <- runRoutes(AuthRoutes.routes, withCsrf(Request.post("/api/auth/logout", Body.empty)))
          } yield assertTrue(
            withSessionResponse.status == Status.NoContent,
            raw.isEmpty,
            sessionCookie(withSessionResponse).exists(_.content.isEmpty),
            anonymous.status == Status.NoContent,
          )
        },
        test("/api/me returns the signed-in user, Theme still a bare JSON string") {
          for {
            session <- signUp("me@example.com")
            response <- runRoutes(AuthRoutes.routes, withSession(Request.get("/api/me"), session))
            raw <- body(response)
          } yield assertTrue(
            response.status == Status.Ok,
            raw.contains("\"theme\":\"Light\""),
            raw.fromJson[AuthResponse].map(_.user.theme) == Right(Theme.Light),
          )
        },
        // Both verification routes are public and answer an empty 204, which is the shape a browser client has the
        // most trouble with — `outCodec(status(NoContent))` rather than `out[Unit]`, per `AdminEndpoints.deleteUser`.
        test("resending a verification link is an empty 204 for any address, with no session") {
          val request = withCsrf(
            Request.post(
              "/api/auth/verification/resend",
              Body.fromString(ResendVerificationRequest("stranger@example.com").toJson),
            )
          )
          for {
            response <- runRoutes(AuthRoutes.routes, request)
            raw <- body(response)
          } yield assertTrue(response.status == Status.NoContent, raw.isEmpty)
        },
        test("an unknown verification token is a 400 in the usual ErrorResponse shape") {
          val request = withCsrf(Request.post("/api/auth/verify", Body.fromString(VerifyEmailRequest("nope").toJson)))
          for {
            response <- runRoutes(AuthRoutes.routes, request)
            raw <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            raw.fromJson[ErrorResponse].map(_.message).exists(_.contains("verification link")),
          )
        },
      ),
      suite("todos")(
        // TodoStatus is a Scala 3 enum, which zio-json writes as a bare string. If zio-schema wrapped it in an object
        // the frontend would fail to decode every item it loads.
        test("creating and listing todos keeps TodoStatus a bare string") {
          for {
            session <- signUp("todo@example.com")
            created <- runRoutes(
              TodoRoutes.routes,
              withCsrf(
                withSession(Request.post("/api/todos", Body.fromString(CreateTodoRequest("milk").toJson)), session)
              ),
            )
            createdRaw <- body(created)
            listed <- runRoutes(TodoRoutes.routes, withSession(Request.get("/api/todos"), session))
            listedRaw <- body(listed)
          } yield assertTrue(
            created.status == Status.Created,
            createdRaw.contains("\"status\":\"ToDo\""),
            createdRaw.fromJson[TodoItem].map(_.text) == Right("milk"),
            listed.status == Status.Ok,
            listedRaw.fromJson[List[TodoItem]].map(_.map(_.text)) == Right(List("milk")),
          )
        }
      ),
      suite("groups")(
        test("creating a group and listing its members keeps GroupRole a bare string") {
          for {
            session <- signUp("group@example.com")
            created <- runRoutes(
              GroupRoutes.routes,
              withCsrf(withSession(Request.post("/api/groups", Body.fromString("""{"name":"Acme"}""")), session)),
            )
            createdRaw <- body(created)
            groupId = createdRaw.fromJson[Group].map(_.id).getOrElse(0L)
            members <- runRoutes(GroupRoutes.routes, withSession(Request.get(s"/api/groups/$groupId/members"), session))
            membersRaw <- body(members)
          } yield assertTrue(
            created.status == Status.Created,
            createdRaw.contains("\"myRole\":\"Admin\""),
            createdRaw.fromJson[Group].map(_.name) == Right("Acme"),
            membersRaw.fromJson[List[GroupMember]].map(_.map(_.email)) == Right(List("group@example.com")),
          )
        },
        test("deleting a group is an empty 204") {
          for {
            session <- signUp("delete-group@example.com")
            created <- runRoutes(
              GroupRoutes.routes,
              withCsrf(withSession(Request.post("/api/groups", Body.fromString("""{"name":"Doomed"}""")), session)),
            )
            createdRaw <- body(created)
            groupId = createdRaw.fromJson[Group].map(_.id).getOrElse(0L)
            deleted <- runRoutes(
              GroupRoutes.routes,
              withCsrf(withSession(Request.delete(s"/api/groups/$groupId"), session)),
            )
            deletedRaw <- body(deleted)
          } yield assertTrue(deleted.status == Status.NoContent, deletedRaw.isEmpty)
        },
      ),
      suite("admin")(
        test("listing users returns a body the frontend's zio-json codec decodes") {
          for {
            admin <- adminSession("list@example.com")
            response <- runRoutes(AdminRoutes.routes, withSession(Request.get("/api/admin/users"), admin._2))
            raw <- body(response)
          } yield assertTrue(
            response.status == Status.Ok,
            raw.fromJson[List[User]].map(_.map(_.email)) == Right(List("list@example.com")),
          )
        },
        test("creating a user answers 201 with the created user") {
          for {
            admin <- adminSession("creator@example.com")
            request = Request.post(
              "/api/admin/users",
              Body.fromString(CreateUserRequest("fresh@example.com", "password123", isAdmin = false).toJson),
            )
            response <- runRoutes(AdminRoutes.routes, withCsrf(withSession(request, admin._2)))
            raw <- body(response)
          } yield assertTrue(
            response.status == Status.Created,
            raw.fromJson[User].map(_.email) == Right("fresh@example.com"),
            raw.fromJson[User].map(_.isAdmin) == Right(false),
          )
        },
        // A 409 carries no `fieldErrors`: only `ApiFailure.BadRequest` has that field, so the encoded body is just
        // `{"message":...}`. It still decodes as `ErrorResponse`, whose `fieldErrors` defaults to empty — which is
        // what keeps the two codec stacks interchangeable for a client that only knows the DTO.
        test("a duplicate email is a 409 whose body still decodes as the usual ErrorResponse") {
          for {
            admin <- adminSession("dup@example.com")
            request = Request.post(
              "/api/admin/users",
              Body.fromString(CreateUserRequest("dup@example.com", "password123", isAdmin = false).toJson),
            )
            response <- runRoutes(AdminRoutes.routes, withCsrf(withSession(request, admin._2)))
            raw <- body(response)
          } yield assertTrue(
            response.status == Status.Conflict,
            !raw.contains("fieldErrors"),
            raw.fromJson[ErrorResponse] == Right(ErrorResponse("Email already registered", Map.empty)),
          )
        },
        test("a short password is a 400 carrying the service's per-field messages") {
          for {
            admin <- adminSession("weak@example.com")
            request = Request.post(
              "/api/admin/users",
              Body.fromString(CreateUserRequest("weak-user@example.com", "short", isAdmin = false).toJson),
            )
            response <- runRoutes(AdminRoutes.routes, withCsrf(withSession(request, admin._2)))
            raw <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            raw.fromJson[ErrorResponse].map(_.message) == Right("Validation failed"),
            raw.fromJson[ErrorResponse].map(_.fieldErrors.contains("password")) == Right(true),
          )
        },
        test("an unknown id is a 404 with the message the mapping gives it") {
          for {
            admin <- adminSession("missing@example.com")
            response <- runRoutes(AdminRoutes.routes, withSession(Request.get("/api/admin/users/999999"), admin._2))
            raw <- body(response)
          } yield assertTrue(
            response.status == Status.NotFound,
            raw.fromJson[ErrorResponse].map(_.message) == Right("User not found"),
          )
        },
        test("updating a user goes through, and leaving the password blank keeps the old one") {
          for {
            admin <- adminSession("updater@example.com")
            adminService <- ZIO.service[AdminService]
            target <- orDieWithFailure(adminService.createUser(admin._1.id, "target@example.com", "password123", false))
            request = Request.put(
              s"/api/admin/users/${target.id}",
              Body.fromString(UpdateUserRequest("renamed@example.com", isAdmin = true, password = None).toJson),
            )
            response <- runRoutes(AdminRoutes.routes, withCsrf(withSession(request, admin._2)))
            raw <- body(response)
            authService <- ZIO.service[AuthService]
            stillLogsIn <- orDieWithFailure(authService.login("renamed@example.com", "password123"))
          } yield assertTrue(
            response.status == Status.Ok,
            raw.fromJson[User].map(_.email) == Right("renamed@example.com"),
            raw.fromJson[User].map(_.isAdmin) == Right(true),
            stillLogsIn._1.email == "renamed@example.com",
          )
        },
        test("deleting a user is a 204 with an empty body") {
          for {
            admin <- adminSession("deleter@example.com")
            adminService <- ZIO.service[AdminService]
            target <- orDieWithFailure(adminService.createUser(admin._1.id, "doomed@example.com", "password123", false))
            response <- runRoutes(
              AdminRoutes.routes,
              withCsrf(withSession(Request.delete(s"/api/admin/users/${target.id}"), admin._2)),
            )
            raw <- body(response)
            remaining <- adminService.listUsers
          } yield assertTrue(
            response.status == Status.NoContent,
            raw.isEmpty,
            !remaining.exists(_.email == "doomed@example.com"),
          )
        },
        test("an administrator still cannot delete their own account") {
          for {
            admin <- adminSession("self@example.com")
            response <- runRoutes(
              AdminRoutes.routes,
              withCsrf(withSession(Request.delete(s"/api/admin/users/${admin._1.id}"), admin._2)),
            )
            raw <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            raw.fromJson[ErrorResponse].map(_.message) == Right("You cannot delete your own account"),
          )
        },
      ),
      suite("aspects")(
        // No description says anything about sessions or CSRF; the aspects are still what enforce them.
        test("the aspects still apply on a described route: no session is a 401, no CSRF header a 403") {
          for {
            unauthenticated <- runRoutes(TodoRoutes.routes, Request.get("/api/todos"))
            noCsrf <- runRoutes(TodoRoutes.routes, Request.post("/api/todos", Body.empty))
          } yield assertTrue(unauthenticated.status == Status.Unauthorized, noCsrf.status == Status.Forbidden)
        },
        // A description has to *declare* a status for a client generated from it to decode one: an undeclared status
        // fails as a defect ("Expected status code ... but found Unauthorized") instead of a value the caller can
        // branch on. That is why 401 is declared everywhere behind the session aspect and the CSRF aspect's 403 is
        // declared nowhere — an expired session is ordinary, a missing `X-Requested-With` is a client this API did not
        // generate. Both bodies come from `RouteSupport`/`JsonSupport` rather than from any endpoint codec, so this is
        // where the two stacks are checked against each other; the 403's shape is pinned because it is still the shape
        // on the wire, whether or not a description names it.
        test("the aspect-built bodies decode with the endpoints' own zio-schema codecs") {
          val unauthorizedCodec = JsonCodec.schemaBasedBinaryCodec[ApiFailure.Unauthorized]
          val forbiddenCodec = JsonCodec.schemaBasedBinaryCodec[ApiFailure.Forbidden]
          for {
            unauthenticated <- runRoutes(TodoRoutes.routes, Request.get("/api/todos"))
            unauthenticatedBody <- unauthenticated.body.asChunk.orDie
            noCsrf <- runRoutes(TodoRoutes.routes, Request.post("/api/todos", Body.empty))
            noCsrfBody <- noCsrf.body.asChunk.orDie
          } yield assertTrue(
            unauthorizedCodec.decode(unauthenticatedBody) == Right(ApiFailure.Unauthorized("Not authenticated")),
            forbiddenCodec.decode(noCsrfBody) == Right(ApiFailure.Forbidden("Missing required header")),
          )
        },
        // ...and the same for the generic 500 `handleFailures` produces around a defect — also undeclared, since a
        // dead database is not part of the API's contract, but still `ApiFailure`-shaped on the wire.
        test("the generic 500 body decodes with the InternalError codec") {
          val internalCodec = JsonCodec.schemaBasedBinaryCodec[ApiFailure.InternalError]
          val dyingRoutes: Routes[Any, Response] = {
            Routes(Method.GET / "api" / "boom" -> handler((_: Request) => ZIO.die(new RuntimeException("boom"))))
          }
          for {
            response <- runRoutes(dyingRoutes, Request.get("/api/boom"))
            chunk <- response.body.asChunk.orDie
          } yield assertTrue(
            response.status == Status.InternalServerError,
            internalCodec.decode(chunk) == Right(ApiFailure.InternalError("Internal server error")),
          )
        },
        // A body the request codec rejects is the one failure that reaches neither `ApiFailures` nor `handleFailures`:
        // `Endpoint.implementHandler` catches the `HttpCodecError` itself and encodes it through the endpoint's
        // *second* error codec, `codecError`. The status was never wrong; the body was, because the library default
        // writes a private `{"name", "message"}` shape that no client built from these descriptions decodes.
        // `ApiEndpoint.codecError` routes it through `ApiFailure.BadRequest` instead.
        test("a body the request codec rejects is a 400 in the usual ErrorResponse shape") {
          val badRequestCodec = JsonCodec.schemaBasedBinaryCodec[ApiFailure.BadRequest]
          for {
            session <- signUp("malformed@example.com")
            response <- runRoutes(
              TodoRoutes.routes,
              withCsrf(withSession(Request.post("/api/todos", Body.fromString("{ not json")), session)),
            )
            raw <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            raw.fromJson[ErrorResponse] == Right(ErrorResponse("Malformed request", Map.empty)),
            badRequestCodec.decode(Chunk.fromArray(raw.getBytes)) == Right(ApiFailure.BadRequest("Malformed request")),
            // The discarded `HttpCodecError` names the schema path it failed on. None of it reaches the caller.
            !raw.contains("MalformedBody"),
          )
        },
        // Two things at once. The default codec offers `text/html` *ahead of* `application/json`, so a caller sending a
        // browser `Accept` used to get an HTML page rather than any JSON at all. And `PUT /api/me/theme` is the one
        // endpoint whose 400 exists only for this: its service call is `.orDie`'d, so no handler can raise one, and
        // without the declaration the client would fail the response as a defect instead of decoding it.
        test("a rejected body stays JSON for a caller that asks for HTML") {
          for {
            session <- signUp("codec-html@example.com")
            request = withCsrf(
              withSession(Request.put("/api/me/theme", Body.fromString("""{"thme":"dark"}""")), session)
            )
            response <- runRoutes(AuthRoutes.routes, request.addHeader(Header.Accept.name, "text/html"))
            raw <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            response.header(Header.ContentType).map(_.mediaType) == Some(MediaType.application.json),
            raw.fromJson[ErrorResponse] == Right(ErrorResponse("Malformed request", Map.empty)),
          )
        },
      ),
    ).provide(layer, Scope.default) @@ TestAspect.timeout(60.seconds)
  }
}
