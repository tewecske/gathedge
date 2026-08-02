package webapp1.backend.http

import webapp1.backend.TestDataSource
import webapp1.backend.db.{SqliteSessionRepository, SqliteUserRepository}
import webapp1.backend.security.PasswordHasher
import webapp1.backend.service.{AdminService, AdminServiceLive, AuthService, AuthServiceLive, InMemoryRateLimiter}
import webapp1.shared.api.AdminApiError
import webapp1.shared.domain.{Theme, User}
import webapp1.shared.dto.{CreateUserRequest, ErrorResponse, UpdateUserRequest}
import zio.*
import zio.http.*
import zio.json.*
import zio.schema.codec.JsonCodec
import zio.test.*

import RouteRunner.{orDieWithFailure, runRoutes, withCsrf, withSession}

/** `AdminRoutes` is implemented against the declarative `Endpoint` API, whose codecs come from zio-schema, while the
  * frontend decodes the same bytes with the zio-json codecs the DTOs derive. Nothing in the type system connects the
  * two, so these assert the actual bytes: every response body here is decoded with the *frontend's* codec, and the
  * error bodies are decoded as `dto.ErrorResponse`, the shape the imperative routes produce.
  */
object AdminEndpointsSpec extends ZIOSpecDefault {

  private val repoLayer = {
    TestDataSource.sqlite >>> (SqliteUserRepository.live ++ SqliteSessionRepository.live)
  }

  private val layer: ZLayer[Any, Throwable, AuthService & AdminService] = {
    (repoLayer ++ PasswordHasher.live ++ InMemoryRateLimiter.live) >>> (AuthServiceLive.live ++ AdminServiceLive.live)
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

  private def get(path: String, session: String) = {
    runRoutes(AdminRoutes.routes, withSession(Request.get(path), session))
  }

  private def send(request: Request, session: String) = {
    runRoutes(AdminRoutes.routes, withCsrf(withSession(request, session)))
  }

  def spec = {
    suite("AdminRoutes (declarative Endpoint API)")(
      test("listing users returns a body the frontend's zio-json codec decodes") {
        for {
          admin <- adminSession("list@example.com")
          response <- get("/api/admin/users", admin._2)
          raw <- response.body.asString.orDie
        } yield assertTrue(
          response.status == Status.Ok,
          raw.fromJson[List[User]].map(_.map(_.email)) == Right(List("list@example.com")),
        )
      },
      // The one place the two codec stacks could silently disagree: Theme is a Scala 3 enum, which zio-json writes as
      // a bare string. If zio-schema wrapped it in an object the frontend would break on every user it loads.
      test("the Theme enum is still a bare JSON string, as zio-json writes it") {
        for {
          admin <- adminSession("theme@example.com")
          response <- get("/api/admin/users", admin._2)
          raw <- response.body.asString.orDie
        } yield assertTrue(
          raw.contains("\"theme\":\"Light\""),
          raw.fromJson[List[User]].map(_.map(_.theme)) == Right(List(Theme.Light)),
        )
      },
      test("creating a user answers 201 with the created user") {
        for {
          admin <- adminSession("creator@example.com")
          body = CreateUserRequest("fresh@example.com", "password123", isAdmin = false).toJson
          response <- send(Request.post("/api/admin/users", Body.fromString(body)), admin._2)
          raw <- response.body.asString.orDie
        } yield assertTrue(
          response.status == Status.Created,
          raw.fromJson[User].map(_.email) == Right("fresh@example.com"),
          raw.fromJson[User].map(_.isAdmin) == Right(false),
        )
      },
      test("a duplicate email is a 409 whose body decodes as the usual ErrorResponse, field errors included") {
        for {
          admin <- adminSession("dup@example.com")
          body = CreateUserRequest("dup@example.com", "password123", isAdmin = false).toJson
          response <- send(Request.post("/api/admin/users", Body.fromString(body)), admin._2)
          raw <- response.body.asString.orDie
        } yield assertTrue(
          response.status == Status.Conflict,
          raw.fromJson[ErrorResponse] ==
            Right(ErrorResponse("Email already registered", Map("email" -> "Email already registered"))),
        )
      },
      test("a short password is a 400 carrying the service's per-field messages") {
        for {
          admin <- adminSession("weak@example.com")
          body = CreateUserRequest("weak-user@example.com", "short", isAdmin = false).toJson
          response <- send(Request.post("/api/admin/users", Body.fromString(body)), admin._2)
          raw <- response.body.asString.orDie
        } yield assertTrue(
          response.status == Status.BadRequest,
          raw.fromJson[ErrorResponse].map(_.message) == Right("Validation failed"),
          raw.fromJson[ErrorResponse].map(_.fieldErrors.contains("password")) == Right(true),
        )
      },
      test("an unknown id is a 404 with the same message the imperative version sent") {
        for {
          admin <- adminSession("missing@example.com")
          response <- get("/api/admin/users/999999", admin._2)
          raw <- response.body.asString.orDie
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
          body = UpdateUserRequest("renamed@example.com", isAdmin = true, password = None).toJson
          response <- send(Request.put(s"/api/admin/users/${target.id}", Body.fromString(body)), admin._2)
          raw <- response.body.asString.orDie
          authService <- ZIO.service[AuthService]
          stillLogsIn <- orDieWithFailure(authService.login("renamed@example.com", "password123"))
        } yield assertTrue(
          response.status == Status.Ok,
          raw.fromJson[User].map(_.email) == Right("renamed@example.com"),
          raw.fromJson[User].map(_.isAdmin) == Right(true),
          stillLogsIn._1.email == "renamed@example.com",
        )
      },
      // `.out[Unit](Status.NoContent)` has to produce a genuinely empty body: a 204 with content would be a protocol
      // violation, and the frontend never tries to parse one.
      test("deleting a user is a 204 with an empty body") {
        for {
          admin <- adminSession("deleter@example.com")
          adminService <- ZIO.service[AdminService]
          target <- orDieWithFailure(adminService.createUser(admin._1.id, "doomed@example.com", "password123", false))
          response <- send(Request.delete(s"/api/admin/users/${target.id}"), admin._2)
          raw <- response.body.asString.orDie
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
          response <- send(Request.delete(s"/api/admin/users/${admin._1.id}"), admin._2)
          raw <- response.body.asString.orDie
        } yield assertTrue(
          response.status == Status.BadRequest,
          raw.fromJson[ErrorResponse].map(_.message) == Right("You cannot delete your own account"),
        )
      },
      // The endpoint description says nothing about sessions; the aspects are still what enforce them.
      test("the aspects still apply: no session is a 401, no CSRF header a 403") {
        for {
          unauthenticated <- runRoutes(AdminRoutes.routes, Request.get("/api/admin/users"))
          noCsrf <- runRoutes(AdminRoutes.routes, Request.post("/api/admin/users", Body.empty))
        } yield assertTrue(unauthenticated.status == Status.Unauthorized, noCsrf.status == Status.Forbidden)
      },
      // ...but the description has to *declare* those statuses, because a client generated from it decodes only what
      // it names: an undeclared status fails as a defect ("Expected status code ... but found Unauthorized") instead
      // of a value the caller can branch on. These bodies come from `RouteSupport`/`JsonSupport`, not from the
      // endpoint codecs, so this checks the two agree.
      test("the aspect responses decode with the endpoint's own zio-schema codecs") {
        val unauthorizedCodec = JsonCodec.schemaBasedBinaryCodec[AdminApiError.Unauthorized]
        val forbiddenCodec = JsonCodec.schemaBasedBinaryCodec[AdminApiError.Forbidden]
        for {
          unauthenticated <- runRoutes(AdminRoutes.routes, Request.get("/api/admin/users"))
          unauthenticatedBody <- unauthenticated.body.asChunk.orDie
          noCsrf <- runRoutes(AdminRoutes.routes, Request.post("/api/admin/users", Body.empty))
          noCsrfBody <- noCsrf.body.asChunk.orDie
        } yield assertTrue(
          unauthorizedCodec.decode(unauthenticatedBody) == Right(AdminApiError.Unauthorized("Not authenticated")),
          forbiddenCodec.decode(noCsrfBody) == Right(AdminApiError.Forbidden("Missing required header")),
        )
      },
      test("a malformed request body is a 400, not a 500") {
        for {
          admin <- adminSession("malformed@example.com")
          response <- send(Request.post("/api/admin/users", Body.fromString("{ not json")), admin._2)
        } yield assertTrue(response.status == Status.BadRequest)
      },
    ).provide(layer, Scope.default) @@ TestAspect.timeout(60.seconds)
  }
}
