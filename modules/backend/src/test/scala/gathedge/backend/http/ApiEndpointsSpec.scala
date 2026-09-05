package gathedge.backend.http

import gathedge.backend.{TestAuthLayers, TestCaptchaService, TestDataSource}
import gathedge.backend.config.AppConfig
import gathedge.backend.db.{
  AuditLogRepository,
  EmailVerificationTokenRepository,
  GameRepository,
  GroupRepository,
  GuestClaimCodeRepository,
  LoginAttemptRepository,
  MetricsRepository,
  OAuthIdentityRepository,
  PasswordResetTokenRepository,
  SessionRepository,
  TextSearch,
  UsageEventRepository,
  UserRepository,
  WordRepository,
  WordRow,
}
import gathedge.backend.security.{PasswordHasher, SessionAuth}
import gathedge.backend.service.{
  AdminActor,
  AdminService,
  AuditTrail,
  AuthService,
  BackgroundJobs,
  EmailSender,
  GameService,
  GameWordList,
  OAuthClients,
  RateLimiter,
  SystemService,
  UsageStatsService,
  WordService,
}
import gathedge.shared.api.ApiFailure
import gathedge.shared.i18n.{MessageKeys, MessageRef}
import gathedge.shared.domain.{AnswerOutcome, GameMode, Gender, PartOfSpeech, Theme, User, WordLanguage}
import gathedge.shared.dto.{
  AdminUserDetail,
  AuditPage,
  AuthResponse,
  CaptchaStatusResponse,
  ClaimCodeResponse,
  ClaimRequest,
  CreateTagRequest,
  CreateUserRequest,
  CreateWordRequest,
  ErrorResponse,
  ForgotPasswordRequest,
  GameAnswerResult,
  GamePrompt,
  GameResults,
  PlayStarted,
  StartPlayRequest,
  SubmitAnswerRequest,
  NewTranslation,
  LoginRequest,
  PairSelectionResponse,
  Paging,
  ResendVerificationRequest,
  ResetPasswordRequest,
  SignupRequest,
  SignupResponse,
  SystemOverview,
  TagResponse,
  TaggedPair,
  UpdateThemeRequest,
  UpdateUserRequest,
  UserPage,
  VerifyEmailRequest,
  WordDetail,
  WordPage,
}
import zio.*
import zio.http.*
import zio.json.*
import zio.schema.codec.JsonCodec
import zio.test.*

import RouteRunner.{getWithQuery, orDieWithFailure, runRoutes, withCsrf, withSession}

/** Every route file is implemented against the declarative `Endpoint` API, whose codecs come from zio-schema, while the
  * frontend's DTOs also derive zio-json codecs and the aspects in `RouteSupport` still write their error bodies with
  * those. Nothing in the type system connects the two stacks, so this spec asserts the actual bytes: response bodies
  * are decoded with the *zio-json* codec, and error bodies as `dto.ErrorResponse`.
  *
  * The per-resource behaviour lives in `RouteGuardsSpec`; what is here is the encoding — statuses, enum
  * representations, empty 204s and the session cookie.
  */
object ApiEndpointsSpec extends ZIOSpecDefault {

  private val repos = {
    TestDataSource.sqlite >>> (
      UserRepository.test ++ SessionRepository.test ++ OAuthIdentityRepository.test ++
        EmailVerificationTokenRepository.test ++ PasswordResetTokenRepository.test ++ LoginAttemptRepository.test ++
        GuestClaimCodeRepository.test ++ AuditLogRepository.test ++ UsageEventRepository.test ++
        MetricsRepository.test ++ WordRepository.test ++ GameRepository.test ++ GroupRepository.test
    )
  }

  private val layer = {
    AppConfig.live ++
      ((AppConfig.live ++ Client.default) >>> OAuthClients.live) ++ (
        // AdminService and SystemService both sit on top of AuthService/AuditTrail now, so the stack is built in
        // order rather than side by side. `>+>` throughout, so AuthService stays in the environment for the fixtures.
        repos ++ PasswordHasher.live ++ RateLimiter.live ++ BackgroundJobs.live ++ TestCaptchaService.live ++
          GameWordList.live ++ TestAuthLayers.emailAndConfig >+>
          (AuthService.live ++ AuditTrail.live ++ GameService.live) >+>
          (AdminService.live ++ SystemService.live ++ UsageStatsService.live ++ WordService.live)
      )
  }

  private def signUp(email: String): ZIO[AuthService, Nothing, String] = {
    orDieWithFailure(AuthService.signup(email, "password123")).map(_._2.get)
  }

  /** Returns the acting administrator and their session id. `createUser` takes an acting-admin id for its audit log; 0
    * is the seeding path, the same one `AdminSeeder` uses.
    */
  private def adminSession(email: String): ZIO[AuthService & AdminService, Nothing, (User, String)] = {
    for {
      admin   <- orDieWithFailure(AdminService.createUser(AdminActor.system, email, "password123", isAdmin = true))
      session <- orDieWithFailure(AuthService.login(email, "password123")).map(_._2)
    } yield (admin, session)
  }

  /** A one-pair game owned by a fresh account, plus that account's session — the smallest thing a play can run on.
    * Built through the services rather than over HTTP: the tag and the marked pair behind an eligible game are `words`'
    * business, and this suite is about the game endpoints' own encoding.
    */
  private def gameFixture(
    email: String
  ): ZIO[AuthService & WordRepository & GameService, Nothing, (String, String)] = {
    for {
      signedUp       <- orDieWithFailure(AuthService.signup(email, "password123"))
      (user, session) = signedUp
      tag            <- WordRepository.insertTag(user.id, "wire", "wire", 0L, "de", "hu").orDie
      source         <- WordRepository
                          .ensureWord(
                            WordRow(
                              0L,
                              "de",
                              "Hund",
                              "hund",
                              PartOfSpeech.code(PartOfSpeech.Noun),
                              Gender.toColumn(Some(Gender.Masculine)),
                              1,
                              "dictionary",
                              None,
                              0L,
                              TextSearch.fold("hund"),
                            )
                          )
                          .orDie
      target         <- WordRepository
                          .ensureWord(
                            WordRow(
                              0L,
                              "hu",
                              "kutya",
                              "kutya",
                              PartOfSpeech.code(PartOfSpeech.Noun),
                              "",
                              1,
                              "dictionary",
                              None,
                              0L,
                              TextSearch.fold("kutya"),
                            )
                          )
                          .orDie
      _              <- WordRepository.pairTranslation(source.id, tag.id, target.id, 0L).orDie
      game           <- orDieWithFailure(GameService.createGame(user.id, WordLanguage.De, WordLanguage.Hu, List(tag.id)))
    } yield (game.slug, session.get)
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
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.Created,
            raw.fromJson[SignupResponse].map(_.user.email) == Right(Some("new@example.com")),
            // False only where verification is mandatory; the test config leaves the gate off.
            raw.fromJson[SignupResponse].map(_.signedIn) == Right(true),
            !raw.contains("Set-Cookie"),
            sessionCookie(response).exists(_.content.nonEmpty),
            sessionCookie(response).exists(_.isHttpOnly),
          )
        },
        test("login answers 200 and a cookie; the wrong password is a 401 in the usual ErrorResponse shape") {
          for {
            _       <- signUp("login@example.com")
            good    <- runRoutes(
                         AuthRoutes.routes,
                         withCsrf(
                           Request.post(
                             "/api/auth/login",
                             Body.fromString(LoginRequest("login@example.com", "password123").toJson),
                           )
                         ),
                       )
            goodRaw <- body(good)
            bad     <-
              runRoutes(
                AuthRoutes.routes,
                withCsrf(
                  Request.post("/api/auth/login", Body.fromString(LoginRequest("login@example.com", "nope").toJson))
                ),
              )
            badRaw  <- body(bad)
          } yield assertTrue(
            good.status == Status.Ok,
            goodRaw.fromJson[AuthResponse].map(_.user.email) == Right(Some("login@example.com")),
            sessionCookie(good).exists(_.content.nonEmpty),
            bad.status == Status.Unauthorized,
            badRaw.fromJson[ErrorResponse].map(_.message) == Right("Invalid email or password"),
          )
        },
        // `.outCodec(HttpCodec.status(...))` rather than `.out[Unit](Status.NoContent)`: a 204 must carry no body and
        // no Content-Length, which is exactly what a browser client needs in order to decode it at all.
        test("logout is an empty 204 that expires the cookie, with or without a session") {
          for {
            session             <- signUp("logout@example.com")
            withSessionResponse <- runRoutes(
                                     AuthRoutes.routes,
                                     withCsrf(withSession(Request.post("/api/auth/logout", Body.empty), session)),
                                   )
            raw                 <- body(withSessionResponse)
            anonymous           <- runRoutes(AuthRoutes.routes, withCsrf(Request.post("/api/auth/logout", Body.empty)))
          } yield assertTrue(
            withSessionResponse.status == Status.NoContent,
            raw.isEmpty,
            sessionCookie(withSessionResponse).exists(_.content.isEmpty),
            anonymous.status == Status.NoContent,
          )
        },
        test("/api/me returns the signed-in user, Theme still a bare JSON string") {
          for {
            session  <- signUp("me@example.com")
            response <- runRoutes(AuthRoutes.routes, withSession(Request.get("/api/me"), session))
            raw      <- body(response)
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
            raw      <- body(response)
          } yield assertTrue(response.status == Status.NoContent, raw.isEmpty)
        },
        test("an unknown verification token is a 400 in the usual ErrorResponse shape") {
          val request = withCsrf(Request.post("/api/auth/verify", Body.fromString(VerifyEmailRequest("nope").toJson)))
          for {
            response <- runRoutes(AuthRoutes.routes, request)
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            raw.fromJson[ErrorResponse].map(_.message).exists(_.contains("verification link")),
          )
        },
        // Public and read before any session exists, same as `providers`. With captcha unconfigured (the shipped
        // default) it answers no site key, which is what keeps the forms from loading the Turnstile script.
        test("the captcha status endpoint answers the unconfigured deployment with no site key") {
          for {
            response <- runRoutes(AuthRoutes.routes, Request.get("/api/auth/captcha-status"))
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.Ok,
            raw.fromJson[CaptchaStatusResponse] == Right(CaptchaStatusResponse(None, 0, 2)),
          )
        },
        // Same non-committal shape as the verification resend: a known address and an unknown one both answer an
        // empty 204, with no session and no CSRF-exempt special casing.
        test("asking for a password reset is an empty 204 for any address, with no session") {
          for {
            _        <- signUp("has-account@example.com")
            request   = withCsrf(
                          Request.post(
                            "/api/auth/password/forgot",
                            Body.fromString(ForgotPasswordRequest("has-account@example.com").toJson),
                          )
                        )
            response <- runRoutes(AuthRoutes.routes, request)
            raw      <- body(response)
            unknown  <- runRoutes(
                          AuthRoutes.routes,
                          withCsrf(
                            Request.post(
                              "/api/auth/password/forgot",
                              Body.fromString(ForgotPasswordRequest("nobody-here@example.com").toJson),
                            )
                          ),
                        )
          } yield assertTrue(response.status == Status.NoContent, raw.isEmpty, unknown.status == Status.NoContent)
        },
        test("an unknown password reset token is a 400 in the usual ErrorResponse shape") {
          val request = withCsrf(
            Request.post(
              "/api/auth/password/reset",
              Body.fromString(ResetPasswordRequest("nope", "somenewpass1").toJson),
            )
          )
          for {
            response <- runRoutes(AuthRoutes.routes, request)
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            raw.fromJson[ErrorResponse].map(_.message).exists(_.contains("password reset link")),
          )
        },
      ),
      suite("admin")(
        test("listing users returns a body the frontend's zio-json codec decodes") {
          for {
            admin    <- adminSession("list@example.com")
            response <- runRoutes(AdminRoutes.routes, withSession(Request.get("/api/admin/users"), admin._2))
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.Ok,
            // A page, not a bare list: the count is what lets the browser number its buttons, so it is part of the
            // body the client decodes rather than a header the codecs would not describe.
            raw.fromJson[UserPage].map(_.items.map(_.email)) == Right(List(Some("list@example.com"))),
            raw.fromJson[UserPage].map(_.total) == Right(1L),
          )
        },
        test("creating a user answers 201 with the created user") {
          for {
            admin    <- adminSession("creator@example.com")
            request   = Request.post(
                          "/api/admin/users",
                          Body.fromString(CreateUserRequest("fresh@example.com", "password123", isAdmin = false).toJson),
                        )
            response <- runRoutes(AdminRoutes.routes, withCsrf(withSession(request, admin._2)))
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.Created,
            raw.fromJson[User].map(_.email) == Right(Some("fresh@example.com")),
            raw.fromJson[User].map(_.isAdmin) == Right(false),
          )
        },
        // A 409 carries no `fieldErrors`: only `ApiFailure.BadRequest` has that field, so the encoded body is just
        // `{"message":...}`. It still decodes as `ErrorResponse`, whose `fieldErrors` defaults to empty — which is
        // what keeps the two codec stacks interchangeable for a client that only knows the DTO.
        test("a duplicate email is a 409 whose body still decodes as the usual ErrorResponse") {
          for {
            admin    <- adminSession("dup@example.com")
            request   = Request.post(
                          "/api/admin/users",
                          Body.fromString(CreateUserRequest("dup@example.com", "password123", isAdmin = false).toJson),
                        )
            response <- runRoutes(AdminRoutes.routes, withCsrf(withSession(request, admin._2)))
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.Conflict,
            !raw.contains("fieldErrors"),
            raw.fromJson[ErrorResponse] ==
              Right(
                ErrorResponse(MessageRef(MessageKeys.emailAlreadyRegistered), "Email already registered", Map.empty)
              ),
          )
        },
        test("a short password is a 400 carrying the service's per-field messages") {
          for {
            admin    <- adminSession("weak@example.com")
            request   = Request.post(
                          "/api/admin/users",
                          Body.fromString(CreateUserRequest("weak-user@example.com", "short", isAdmin = false).toJson),
                        )
            response <- runRoutes(AdminRoutes.routes, withCsrf(withSession(request, admin._2)))
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            raw.fromJson[ErrorResponse].map(_.message) == Right("Validation failed"),
            raw.fromJson[ErrorResponse].map(_.fieldErrors.contains("password")) == Right(true),
          )
        },
        test("an unknown id is a 404 with the message the mapping gives it") {
          for {
            admin    <- adminSession("missing@example.com")
            response <- runRoutes(AdminRoutes.routes, withSession(Request.get("/api/admin/users/999999"), admin._2))
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.NotFound,
            raw.fromJson[ErrorResponse].map(_.message) == Right("User not found"),
          )
        },
        test("updating a user goes through, and leaving the password blank keeps the old one") {
          for {
            admin       <- adminSession("updater@example.com")
            target      <- orDieWithFailure(
                             AdminService.createUser(AdminActor(admin._1.id), "target@example.com", "password123", false)
                           )
            request      = {
              Request.put(
                s"/api/admin/users/${target.id}",
                Body.fromString(UpdateUserRequest("renamed@example.com", isAdmin = true, password = None).toJson),
              )
            }
            response    <- runRoutes(AdminRoutes.routes, withCsrf(withSession(request, admin._2)))
            raw         <- body(response)
            stillLogsIn <- orDieWithFailure(AuthService.login("renamed@example.com", "password123"))
          } yield assertTrue(
            response.status == Status.Ok,
            raw.fromJson[User].map(_.email) == Right(Some("renamed@example.com")),
            raw.fromJson[User].map(_.isAdmin) == Right(true),
            stillLogsIn._1.email.contains("renamed@example.com"),
          )
        },
        test("deleting a user is a 204 with an empty body") {
          for {
            admin     <- adminSession("deleter@example.com")
            target    <- orDieWithFailure(
                           AdminService.createUser(AdminActor(admin._1.id), "doomed@example.com", "password123", false)
                         )
            response  <- runRoutes(
                           AdminRoutes.routes,
                           withCsrf(withSession(Request.delete(s"/api/admin/users/${target.id}"), admin._2)),
                         )
            raw       <- body(response)
            remaining <- AdminService.listUsers(page = Paging.firstPage, pageSize = 100, None, None, descending = false)
          } yield assertTrue(
            response.status == Status.NoContent,
            raw.isEmpty,
            !remaining.items.exists(_.email.contains("doomed@example.com")),
          )
        },
        test("an administrator still cannot delete their own account") {
          for {
            admin    <- adminSession("self@example.com")
            response <- runRoutes(
                          AdminRoutes.routes,
                          withCsrf(withSession(Request.delete(s"/api/admin/users/${admin._1.id}"), admin._2)),
                        )
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            raw.fromJson[ErrorResponse].map(_.message) == Right("You cannot delete your own account"),
          )
        },
        // The two codec stacks agree on this one too — it is by far the largest response body in the API, and the
        // frontend decodes it with zio-json while the endpoint encodes it with zio-schema.
        test("the account detail decodes as the DTO the frontend reads, and carries no credential") {
          for {
            admin    <- adminSession("detail@example.com")
            target   <- orDieWithFailure(
                          AdminService.createUser(AdminActor(admin._1.id), "detailed@example.com", "password123", false)
                        )
            _        <- orDieWithFailure(AuthService.login("detailed@example.com", "password123"))
            response <- runRoutes(
                          AdminRoutes.routes,
                          withSession(Request.get(s"/api/admin/users/${target.id}/detail"), admin._2),
                        )
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.Ok,
            raw.fromJson[AdminUserDetail].map(_.user.email) == Right(Some("detailed@example.com")),
            raw.fromJson[AdminUserDetail].map(_.hasPassword) == Right(true),
            raw.fromJson[AdminUserDetail].map(_.activeSessions) == Right(1),
            raw.fromJson[AdminUserDetail].map(_.lockout.maxAttempts) == Right(5),
            !raw.contains("$2a$"),
          )
        },
        // Five of the six diagnostics answer a bare 204. See `AdminEndpoints.deleteUser` for why that is a status
        // codec rather than `.out[Unit]`, and why an empty body with no `Content-Length` is the thing to assert.
        test("marking an address confirmed is a 204 with an empty body") {
          for {
            admin    <- adminSession("confirmer@example.com")
            signedUp <- orDieWithFailure(AuthService.signup("to-confirm@example.com", "password123"))
            request   = Request.post(s"/api/admin/users/${signedUp._1.id}/verify-email", Body.empty)
            response <- runRoutes(AdminRoutes.routes, withCsrf(withSession(request, admin._2)))
            raw      <- body(response)
            after    <- orDieWithFailure(AdminService.getUser(signedUp._1.id))
          } yield assertTrue(response.status == Status.NoContent, raw.isEmpty, after.emailVerified)
        },
        test("the audit log answers the entries the administrator's own actions wrote") {
          for {
            admin    <- adminSession("auditor-wire@example.com")
            _        <- orDieWithFailure(
                          AdminService.createUser(AdminActor(admin._1.id), "audited-wire@example.com", "pw12345678", false)
                        )
            response <- runRoutes(
                          AdminRoutes.routes,
                          withSession(getWithQuery("/api/admin/audit?pageSize=10&action=user.create"), admin._2),
                        )
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.Ok,
            raw.fromJson[AuditPage].map(_.items.forall(_.action == "user.create")) == Right(true),
            raw.fromJson[AuditPage].map(_.items.exists(_.actorEmail.contains("auditor-wire@example.com"))) ==
              Right(true),
            // The total is the count of what the filter matches, which is the whole point of paging server-side.
            raw.fromJson[AuditPage].map(_.total > 0L) == Right(true),
          )
        },
        // A query parameter that does not decode is `ApiEndpoint.codecError`'s 400, which never reaches a handler —
        // it has to come back as `ErrorResponse` like everything else, not as the library's own private shape.
        test("an unparseable query parameter is the API's own 400") {
          for {
            admin    <- adminSession("bad-query@example.com")
            response <- runRoutes(
                          AdminRoutes.routes,
                          withSession(getWithQuery("/api/admin/audit?page=not-a-number"), admin._2),
                        )
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            raw.fromJson[ErrorResponse].map(_.message) == Right("Malformed request"),
          )
        },
        test("the system overview decodes, and reports no configured secret") {
          for {
            admin    <- adminSession("sys-wire@example.com")
            response <- runRoutes(AdminRoutes.routes, withSession(Request.get("/api/admin/system"), admin._2))
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.Ok,
            raw.fromJson[SystemOverview].map(_.runtime.apiVersion).isRight,
            raw.fromJson[SystemOverview].map(_.stats.users > 0) == Right(true),
            // Not a blanket search for "password": `usersWithoutPassword` is a legitimate statistic. What must not
            // appear is a *value* — the bootstrap credential and the development database password — or a field
            // holding one. `SystemServiceSpec` checks the configuration half against the field names as well.
            !raw.contains("changeme123"),
            !raw.toLowerCase.contains("\"password\""),
            !raw.toLowerCase.contains("secret"),
          )
        },
      ),
      // The vocabulary's two reads are the only endpoints in the API that answer with *and* without a session, so what
      // is worth pinning on the wire is that both shapes are the same one — and that a guest is minted, cookie and all,
      // by an endpoint nobody had to sign in to reach.
      suite("words")(
        test("the listing answers a visitor with no session, and marks no tags on it") {
          for {
            response <- runRoutes(WordRoutes.routes, getWithQuery("/api/words?lang=de&q=zz"))
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.Ok,
            raw.fromJson[WordPage].map(_.items) == Right(Nil),
            raw.fromJson[WordPage].map(_.total) == Right(0L),
          )
        },
        test("a word is created, answered with its enums as bare strings, and found again by search") {
          for {
            session <- signUp("words-wire@example.com")
            request  = Request.post(
                         "/api/words",
                         Body.fromString(
                           CreateWordRequest(
                             WordLanguage.De,
                             "Haus",
                             PartOfSpeech.Noun,
                             Some(Gender.Neuter),
                             List(NewTranslation(WordLanguage.Hu, "ház", None, None)),
                             Nil,
                           ).toJson
                         ),
                       )
            created <- runRoutes(WordRoutes.routes, withCsrf(withSession(request, session)))
            raw     <- body(created)
            listed  <-
              runRoutes(WordRoutes.routes, withSession(getWithQuery("/api/words?lang=de&q=hau&target=hu"), session))
            listRaw <- body(listed)
          } yield assertTrue(
            created.status == Status.Created,
            // zio-schema wrote the enums; zio-json is reading them. That the two agree is the point of this spec.
            raw.contains("\"De\""),
            raw.contains("\"Noun\""),
            raw.contains("\"Neuter\""),
            raw.fromJson[WordDetail].map(_.word.text) == Right("Haus"),
            raw.fromJson[WordDetail].map(_.translations.map(_.word.text)) == Right(List("ház")),
            listed.status == Status.Ok,
            listRaw.fromJson[WordPage].map(_.items.map(_.word.text)) == Right(List("Haus")),
            listRaw.fromJson[WordPage].map(_.items.flatMap(_.translations.map(_.text))) == Right(List("ház")),
          )
        },
        test("an unparseable query parameter is the described 400, in the ordinary error shape") {
          for {
            response <- runRoutes(WordRoutes.routes, getWithQuery("/api/words?page=soon"))
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            raw.fromJson[ErrorResponse].map(_.message) == Right("Malformed request"),
          )
        },
        test("tagging answers a genuinely empty 204") {
          for {
            session <- signUp("words-tag@example.com")
            tagReq   = Request.post(
                         "/api/tags",
                         Body.fromString(CreateTagRequest("lesson1", WordLanguage.De, WordLanguage.Hu).toJson),
                       )
            tag     <- runRoutes(WordRoutes.routes, withCsrf(withSession(tagReq, session)))
            tagRaw  <- body(tag)
            tagId    = tagRaw.fromJson[TagResponse].map(_.tag.id).getOrElse(0L)
            wordReq  = Request.post(
                         "/api/words",
                         Body.fromString(
                           CreateWordRequest(WordLanguage.De, "Buch", PartOfSpeech.Noun, None, Nil, Nil).toJson
                         ),
                       )
            word    <- runRoutes(WordRoutes.routes, withCsrf(withSession(wordReq, session)))
            wordRaw <- body(word)
            wordId   = wordRaw.fromJson[WordDetail].map(_.word.id).getOrElse(0L)
            tagged  <- runRoutes(
                         WordRoutes.routes,
                         withCsrf(withSession(Request.put(s"/api/words/$wordId/tags/$tagId", Body.empty), session)),
                       )
            empty   <- body(tagged)
          } yield assertTrue(
            tag.status == Status.Created,
            tagged.status == Status.NoContent,
            empty.isEmpty,
            tagged.header(Header.ContentLength).isEmpty,
          )
        },
        test("marking a translation for practice answers 200 with no warning, and the row carries the mark back") {
          for {
            session   <- signUp("words-pair@example.com")
            tagReq     = Request.post(
                           "/api/tags",
                           Body.fromString(CreateTagRequest("lesson1", WordLanguage.De, WordLanguage.Hu).toJson),
                         )
            tag       <- runRoutes(WordRoutes.routes, withCsrf(withSession(tagReq, session)))
            tagRaw    <- body(tag)
            tagId      = tagRaw.fromJson[TagResponse].map(_.tag.id).getOrElse(0L)
            wordReq    = Request.post(
                           "/api/words",
                           Body.fromString(
                             CreateWordRequest(
                               WordLanguage.De,
                               "Brot",
                               PartOfSpeech.Noun,
                               Some(Gender.Neuter),
                               List(NewTranslation(WordLanguage.Hu, "kenyér", None, None)),
                               Nil,
                             ).toJson
                           ),
                         )
            word      <- runRoutes(WordRoutes.routes, withCsrf(withSession(wordReq, session)))
            wordRaw   <- body(word)
            detail     = wordRaw.fromJson[WordDetail]
            wordId     = detail.map(_.word.id).getOrElse(0L)
            targetId   = detail.map(_.translations.map(_.word.id)).getOrElse(Nil).headOption.getOrElse(0L)
            path       = s"/api/words/$wordId/tags/$tagId/translations/$targetId"
            marked    <- runRoutes(WordRoutes.routes, withCsrf(withSession(Request.put(path, Body.empty), session)))
            markedRaw <- body(marked)
            listed    <-
              runRoutes(WordRoutes.routes, withSession(getWithQuery("/api/words?lang=de&q=brot&target=hu"), session))
            listRaw   <- body(listed)
            shown     <- runRoutes(WordRoutes.routes, withSession(Request.get(s"/api/words/$wordId"), session))
            shownRaw  <- body(shown)
            unmarked  <- runRoutes(WordRoutes.routes, withCsrf(withSession(Request.delete(path), session)))
            after     <-
              runRoutes(WordRoutes.routes, withSession(getWithQuery("/api/words?lang=de&q=brot&target=hu"), session))
            afterRaw  <- body(after)
          } yield assertTrue(
            // Ok rather than NoContent, since the body may carry a warning — the shipped quota defaults are nowhere
            // near reachable here, so it carries none.
            marked.status == Status.Ok,
            markedRaw.fromJson[PairSelectionResponse] == Right(PairSelectionResponse(None)),
            listRaw.fromJson[WordPage].map(_.items.flatMap(_.pairs)) == Right(List(TaggedPair(tagId, targetId))),
            // The word page needs the same marks, and gets them on the body it already asks for.
            shownRaw.fromJson[WordDetail].map(_.pairs) == Right(List(TaggedPair(tagId, targetId))),
            unmarked.status == Status.NoContent,
            afterRaw.fromJson[WordPage].map(_.items.flatMap(_.pairs)) == Right(Nil),
          )
        },
        test("a word that is not there is the described 404") {
          for {
            response <- runRoutes(WordRoutes.routes, Request.get("/api/words/424242"))
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.NotFound,
            raw.fromJson[ErrorResponse].map(_.message) == Right("No such word"),
          )
        },
        test("writing needs a session, reading does not") {
          for {
            read  <- runRoutes(WordRoutes.routes, Request.get("/api/words"))
            write <- runRoutes(
                       WordRoutes.routes,
                       withCsrf(
                         Request.post(
                           "/api/tags",
                           Body.fromString(CreateTagRequest("nope", WordLanguage.De, WordLanguage.Hu).toJson),
                         )
                       ),
                     )
          } yield assertTrue(read.status == Status.Ok, write.status == Status.Unauthorized)
        },
      ),
      suite("games")(
        test("a play carries its mode both ways, and a clicked prompt carries its options") {
          for {
            fixture        <- gameFixture("games-wire@example.com")
            (slug, session) = fixture
            startRequest    = Request.post(
                                s"/api/games/$slug/plays",
                                Body.fromString(StartPlayRequest(mode = GameMode.MultipleChoice).toJson),
                              )
            started        <- runRoutes(GameRoutes.routes, withCsrf(withSession(startRequest, session)))
            startedRaw     <- body(started)
            playId          = startedRaw.fromJson[PlayStarted].map(_.playId).getOrElse(0L)
            prompt         <- runRoutes(
                                GameRoutes.routes,
                                withSession(Request.get(s"/api/games/plays/$playId/prompt"), session),
                              )
            promptRaw      <- body(prompt)
            results        <- runRoutes(
                                GameRoutes.routes,
                                withSession(Request.get(s"/api/games/plays/$playId/results"), session),
                              )
            resultsRaw     <- body(results)
          } yield assertTrue(
            started.status == Status.Created,
            // The request body's enum went out through zio-json; the endpoint read it through zio-schema.
            startedRaw.fromJson[PlayStarted].map(_.maxScore) == Right(1),
            prompt.status == Status.Ok,
            promptRaw.fromJson[GamePrompt].map(_.wordText) == Right(Some("der Hund")),
            promptRaw.fromJson[GamePrompt].map(_.options) == Right(List("kutya")),
            results.status == Status.Ok,
            // And back the other way: zio-schema wrote it, zio-json is reading it.
            resultsRaw.contains("\"MultipleChoice\""),
            resultsRaw.fromJson[GameResults].map(_.variant.mode) == Right(GameMode.MultipleChoice),
          )
        },
        test("submitting an answer answers 200 with the graded row") {
          for {
            fixture        <- gameFixture("games-answer-wire@example.com")
            (slug, session) = fixture
            startRequest    = Request.post(s"/api/games/$slug/plays", Body.fromString(StartPlayRequest().toJson))
            started        <- runRoutes(GameRoutes.routes, withCsrf(withSession(startRequest, session)))
            startedRaw     <- body(started)
            playId          = startedRaw.fromJson[PlayStarted].map(_.playId).getOrElse(0L)
            prompt         <- runRoutes(
                                GameRoutes.routes,
                                withSession(Request.get(s"/api/games/plays/$playId/prompt"), session),
                              )
            promptRaw      <- body(prompt)
            wordId          = promptRaw.fromJson[GamePrompt].toOption.flatMap(_.wordId).getOrElse(0L)
            answerRequest   = Request.post(
                                s"/api/games/plays/$playId/answers",
                                Body.fromString(SubmitAnswerRequest(wordId, "kutya").toJson),
                              )
            answered       <- runRoutes(GameRoutes.routes, withCsrf(withSession(answerRequest, session)))
            answeredRaw    <- body(answered)
          } yield assertTrue(
            // 200 with a body, not the bare 204 this endpoint used to answer: the player is told how the word went.
            answered.status == Status.Ok,
            answeredRaw.contains("\"Correct\""),
            answeredRaw.fromJson[GameAnswerResult].map(_.outcome) == Right(AnswerOutcome.Correct),
            answeredRaw.fromJson[GameAnswerResult].map(_.expectedTexts) == Right(List("kutya")),
          )
        },
      ),
      suite("guest")(
        test("minting a guest answers 201, a session cookie, and a user with no address") {
          for {
            response <- runRoutes(
                          AuthRoutes.routes,
                          withCsrf(Request.post("/api/guest", Body.fromString(UpdateThemeRequest(Theme.Light).toJson))),
                        )
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.Created,
            sessionCookie(response).exists(_.content.nonEmpty),
            raw.fromJson[AuthResponse].map(_.user.email) == Right(None),
            raw.fromJson[AuthResponse].map(_.user.isGuest) == Right(true),
          )
        },
        test("minting a guest seeds the account with the visitor's current theme") {
          for {
            response <- runRoutes(
                          AuthRoutes.routes,
                          withCsrf(Request.post("/api/guest", Body.fromString(UpdateThemeRequest(Theme.Dark).toJson))),
                        )
            raw      <- body(response)
          } yield assertTrue(raw.fromJson[AuthResponse].map(_.user.theme) == Right(Theme.Dark))
        },
        test("a malformed body is the described 400") {
          for {
            response <- runRoutes(AuthRoutes.routes, withCsrf(Request.post("/api/guest", Body.empty)))
          } yield assertTrue(response.status == Status.BadRequest)
        },
        test("a transfer code round-trips through the wire, and a wrong one is the described 404") {
          for {
            minted  <- runRoutes(
                         AuthRoutes.routes,
                         withCsrf(Request.post("/api/guest", Body.fromString(UpdateThemeRequest(Theme.Light).toJson))),
                       )
            session  = sessionCookie(minted).map(_.content).getOrElse("")
            code    <- runRoutes(
                         AuthRoutes.routes,
                         withCsrf(withSession(Request.post("/api/guest/code", Body.empty), session)),
                       )
            codeRaw <- body(code)
            value    = codeRaw.fromJson[ClaimCodeResponse].map(_.code).getOrElse("")
            claimed <- runRoutes(
                         AuthRoutes.routes,
                         withCsrf(Request.post("/api/guest/claim", Body.fromString(ClaimRequest(value).toJson))),
                       )
            wrong   <- runRoutes(
                         AuthRoutes.routes,
                         withCsrf(
                           Request.post("/api/guest/claim", Body.fromString(ClaimRequest("ZZZZ-ZZZZ").toJson))
                         ),
                       )
          } yield assertTrue(
            code.status == Status.Ok,
            value.nonEmpty,
            claimed.status == Status.Ok,
            sessionCookie(claimed).exists(_.content.nonEmpty),
            wrong.status == Status.NotFound,
          )
        },
        test("asking for the transfer code again answers the same one, not a fresh one") {
          for {
            minted    <- runRoutes(
                           AuthRoutes.routes,
                           withCsrf(Request.post("/api/guest", Body.fromString(UpdateThemeRequest(Theme.Light).toJson))),
                         )
            session    = sessionCookie(minted).map(_.content).getOrElse("")
            first     <- runRoutes(
                           AuthRoutes.routes,
                           withCsrf(withSession(Request.post("/api/guest/code", Body.empty), session)),
                         )
            firstRaw  <- body(first)
            second    <- runRoutes(
                           AuthRoutes.routes,
                           withCsrf(withSession(Request.post("/api/guest/code", Body.empty), session)),
                         )
            secondRaw <- body(second)
          } yield assertTrue(
            firstRaw.fromJson[ClaimCodeResponse].map(_.code) == secondRaw.fromJson[ClaimCodeResponse].map(_.code)
          )
        },
        test("a registered account cannot mint a transfer code: the described 403") {
          for {
            session  <- signUp("not-a-guest@example.com")
            response <- runRoutes(
                          AuthRoutes.routes,
                          withCsrf(withSession(Request.post("/api/guest/code", Body.empty), session)),
                        )
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.Forbidden,
            raw.fromJson[ErrorResponse].map(_.message) == Right("This account is already registered"),
          )
        },
      ),
      suite("aspects")(
        // No description says anything about sessions or CSRF; the aspects are still what enforce them.
        test("the aspects still apply on a described route: no session is a 401, no CSRF header a 403") {
          for {
            unauthenticated <- runRoutes(AuthRoutes.routes, Request.get("/api/me"))
            noCsrf          <- runRoutes(AuthRoutes.routes, Request.put("/api/me/theme", Body.empty))
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
          val forbiddenCodec    = JsonCodec.schemaBasedBinaryCodec[ApiFailure.Forbidden]
          for {
            unauthenticated     <- runRoutes(AuthRoutes.routes, Request.get("/api/me"))
            unauthenticatedBody <- unauthenticated.body.asChunk.orDie
            noCsrf              <- runRoutes(AuthRoutes.routes, Request.put("/api/me/theme", Body.empty))
            noCsrfBody          <- noCsrf.body.asChunk.orDie
          } yield assertTrue(
            unauthorizedCodec.decode(unauthenticatedBody) ==
              Right(ApiFailure.Unauthorized(MessageRef(MessageKeys.notAuthenticated), "Not authenticated")),
            forbiddenCodec.decode(noCsrfBody) ==
              Right(ApiFailure.Forbidden(MessageRef(MessageKeys.missingCsrfHeader), "Missing required header")),
          )
        },
        // ...and the same for the generic 500 `handleFailures` produces around a defect — also undeclared, since a
        // dead database is not part of the API's contract, but still `ApiFailure`-shaped on the wire.
        test("the generic 500 body decodes with the InternalError codec") {
          val internalCodec                      = JsonCodec.schemaBasedBinaryCodec[ApiFailure.InternalError]
          val dyingRoutes: Routes[Any, Response] = {
            Routes(Method.GET / "api" / "boom" -> handler((_: Request) => ZIO.die(new RuntimeException("boom"))))
          }
          for {
            response <- runRoutes(dyingRoutes, Request.get("/api/boom"))
            chunk    <- response.body.asChunk.orDie
          } yield assertTrue(
            response.status == Status.InternalServerError,
            internalCodec.decode(chunk) ==
              Right(ApiFailure.InternalError(MessageRef(MessageKeys.internalError), "Internal server error")),
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
            session  <- signUp("malformed@example.com")
            response <- runRoutes(
                          AuthRoutes.routes,
                          withCsrf(withSession(Request.put("/api/me/theme", Body.fromString("{ not json")), session)),
                        )
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            raw.fromJson[ErrorResponse] ==
              Right(ErrorResponse(MessageRef(MessageKeys.malformedRequest), "Malformed request", Map.empty)),
            badRequestCodec.decode(Chunk.fromArray(raw.getBytes)) ==
              Right(ApiFailure.BadRequest(MessageRef(MessageKeys.malformedRequest), "Malformed request")),
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
            session  <- signUp("codec-html@example.com")
            request   = withCsrf(
                          withSession(Request.put("/api/me/theme", Body.fromString("""{"thme":"dark"}""")), session)
                        )
            response <- runRoutes(AuthRoutes.routes, request.addHeader(Header.Accept.name, "text/html"))
            raw      <- body(response)
          } yield assertTrue(
            response.status == Status.BadRequest,
            response.header(Header.ContentType).map(_.mediaType) == Some(MediaType.application.json),
            raw.fromJson[ErrorResponse] ==
              Right(ErrorResponse(MessageRef(MessageKeys.malformedRequest), "Malformed request", Map.empty)),
          )
        },
      ),
    ).provide(layer, Scope.default) @@ TestAspect.timeout(60.seconds)
  }
}
