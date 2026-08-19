package gathedge.backend.http

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
      val byMethod     =
        List("GET" -> item.get, "PUT" -> item.put, "POST" -> item.post, "DELETE" -> item.delete, "PATCH" -> item.patch)
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
              "/api/auth/captcha-status",
              "/api/auth/verify",
              "/api/auth/verification/resend",
              "/api/auth/password/forgot",
              "/api/auth/password/reset",
              "/api/auth/upgrade",
              "/api/guest",
              "/api/guest/code",
              "/api/guest/claim",
              "/api/words",
              "/api/words/{id}",
              "/api/words/{id}/translations",
              "/api/words/{id}/translations/{translationId}",
              "/api/words/{id}/tags/{tagId}",
              "/api/words/{id}/tags/{tagId}/translations/{translationWordId}",
              "/api/words/tags/{tagId}/bulk-upload/preview",
              "/api/words/tags/{tagId}/bulk-upload/confirm",
              "/api/tags",
              "/api/tags/{tagId}",
              "/api/tags/{tagId}/copy",
              "/api/games",
              "/api/games/setup",
              "/api/games/setup/words",
              "/api/games/mine",
              "/api/games/plays/mine",
              "/api/games/{slug}",
              "/api/games/{slug}/reshuffle",
              "/api/games/{slug}/plays",
              "/api/games/{slug}/plays/{playId}",
              "/api/games/plays/{playId}/prompt",
              "/api/games/plays/{playId}/answers",
              "/api/games/plays/{playId}/results",
              "/api/me",
              "/api/me/theme",
              "/api/me/locale",
              "/api/me/identities",
              "/api/me/identities/{provider}",
              "/api/me/password",
              "/api/admin/users",
              "/api/admin/users/{id}",
              "/api/admin/users/{id}/detail",
              "/api/admin/users/{id}/plays",
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
              "/api/admin/usage/routes",
              "/api/admin/usage/suspicious",
              "/api/progress-shares/code",
              "/api/progress-shares/redeem",
              "/api/progress-shares/viewers",
              "/api/progress-shares/shared-with-me",
              "/api/progress-shares/{sharerUserId}/plays",
              "/api/progress-shares/viewers/{viewerUserId}",
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
      // document states. `POST /api/guest` joins `PUT /api/me/theme`/`PUT /api/me/locale` as the exceptions where 400
      // is only reachable through `ApiEndpoint.codecError`, never the handler's own union — minting a guest cannot
      // itself fail to decode a valid `Theme`.
      test("every operation documents exactly the statuses it can answer with") {
        assertTrue(
          statuses ==
            Map(
              ("POST", "/api/auth/signup")                                                -> Set(Created, BadRequest, Unauthorized, Conflict, TooManyRequests),
              // The 403 is `AuthFailure.EmailNotVerified` — the service's own answer, not an aspect's, which is why
              // this is the only path in the API that documents one.
              ("POST", "/api/auth/login")                                                 -> Set(Ok, BadRequest, Unauthorized, Forbidden, Conflict, TooManyRequests),
              ("POST", "/api/auth/logout")                                                -> Set(NoContent),
              // Public, no input, no aspect: the one operation in the API that documents no failure status at all.
              ("GET", "/api/auth/providers")                                              -> Set(Ok),
              // Same shape as providers: public, no input, no aspect, so no failure status. The request context it
              // reads (the client address) is supplied by an aspect rather than described.
              ("GET", "/api/auth/captcha-status")                                         -> Set(Ok),
              // One 400 for an unknown, spent or expired token alike; nothing else is reachable.
              ("POST", "/api/auth/verify")                                                -> Set(NoContent, BadRequest),
              // Answers 204 for every address, known or not, so the limiter's 429 is the only visible failure.
              ("POST", "/api/auth/verification/resend")                                   -> Set(NoContent, BadRequest, TooManyRequests),
              // Same non-committal shape as the verification resend, and the same reason: reporting which
              // addresses have accounts would make this an enumeration oracle.
              ("POST", "/api/auth/password/forgot")                                       -> Set(NoContent, BadRequest, TooManyRequests),
              // One 400 for a token that is unknown, spent or expired alike, or for a new password that fails
              // validation — nothing else is reachable.
              ("POST", "/api/auth/password/reset")                                        -> Set(NoContent, BadRequest),
              // Minting a guest takes the visitor's current theme as input, so its 400 is only the body's codec
              // error; the limiter's 429 is its only handler-raised failure. Redeeming a code adds the same 400 and
              // a 404 for a code that is unknown or revoked — one answer for both, so the code space cannot be probed.
              ("POST", "/api/guest")                                                      -> Set(Created, BadRequest, TooManyRequests),
              ("POST", "/api/guest/claim")                                                -> Set(Ok, BadRequest, NotFound, TooManyRequests),
              // The two 403s outside login, and for the same reason: `AuthService` raises them for an account that is
              // not a guest, which is an answer to a well-formed request rather than an aspect's rejection.
              ("POST", "/api/guest/code")                                                 -> Set(Ok, Unauthorized, Forbidden),
              ("POST", "/api/auth/upgrade")                                               -> Set(Ok, BadRequest, Unauthorized, Forbidden, Conflict),
              // The vocabulary's two reads are the only operations in the API guarded by `optionalUser`: they answer
              // for a visitor with no session, so neither declares a 401. The 400 is a query parameter or a path
              // segment that fails to decode.
              ("GET", "/api/words")                                                       -> Set(Ok, BadRequest),
              ("GET", "/api/words/{id}")                                                  -> Set(Ok, BadRequest, NotFound),
              ("POST", "/api/words")                                                      -> Set(Created, BadRequest, Unauthorized),
              ("POST", "/api/words/{id}/translations")                                    ->
                Set(Ok, BadRequest, Unauthorized, NotFound, Conflict),
              ("DELETE", "/api/words/{id}/translations/{translationId}")                  ->
                Set(NoContent, BadRequest, Unauthorized, NotFound),
              ("PUT", "/api/words/{id}/tags/{tagId}")                                     -> Set(NoContent, BadRequest, Unauthorized, NotFound),
              ("DELETE", "/api/words/{id}/tags/{tagId}")                                  -> Set(NoContent, BadRequest, Unauthorized, NotFound),
              // Marking a translation as a practice answer. One 404 for a word that is not there, a translation the
              // word does not have, and a tag that is not the caller's alike — none of which an account may learn by
              // trying. 409 is the account already owning as many word_tag_pairs rows as the pair quota's hard
              // threshold allows. `Ok` rather than `NoContent`: the body may carry a non-fatal warning when the write
              // only crossed the quota's *soft* threshold.
              ("PUT", "/api/words/{id}/tags/{tagId}/translations/{translationWordId}")    ->
                Set(Ok, BadRequest, Unauthorized, NotFound, Conflict),
              ("DELETE", "/api/words/{id}/tags/{tagId}/translations/{translationWordId}") ->
                Set(NoContent, BadRequest, Unauthorized, NotFound),
              // Scans an uploaded file's text for dictionary words in each of two languages and answers the matches
              // and unmatched tokens, writing nothing. 404 is the tag, for the reason every other write in this
              // resource answers 404 for one, even though this call is read-only. 429 is this endpoint's own
              // rate-limit budget (`RateLimitKey.wordUpload`), shared with confirm below — unlike every other write
              // here, a single call can scan thousands of tokens.
              ("POST", "/api/words/tags/{tagId}/bulk-upload/preview")                     ->
                Set(Ok, BadRequest, Unauthorized, NotFound, TooManyRequests),
              // Commits what the reader chose out of the preview: tags every accepted match and creates/links every
              // manually paired word into the caller's own tag. Same 404/429 shape as preview, sharing its budget.
              ("POST", "/api/words/tags/{tagId}/bulk-upload/confirm")                     ->
                Set(Ok, BadRequest, Unauthorized, NotFound, TooManyRequests),
              // Listing tags takes no input, so it has no 400 to declare.
              ("GET", "/api/tags")                                                        -> Set(Ok, Unauthorized),
              // 409 covers a name the account already has *and* already owning as many tags as the quota's hard
              // threshold allows — `error.key` tells the two apart. The body may carry a warning instead when the
              // write only crossed the *soft* threshold.
              ("POST", "/api/tags")                                                       -> Set(Created, BadRequest, Unauthorized, Conflict),
              ("DELETE", "/api/tags/{tagId}")                                             -> Set(NoContent, BadRequest, Unauthorized, NotFound),
              // Copying seeds a tag of the caller's own from any tag's name, including one they do not own, and copies
              // its word/pair snapshot with it: 404 for a source tag that does not exist, 409 for the ordinary
              // already-have-one-by-that-name case *and* for either quota's hard threshold — checked before anything
              // is written, so a blocked copy leaves nothing behind.
              ("POST", "/api/tags/{tagId}/copy")                                          ->
                Set(Created, BadRequest, Unauthorized, NotFound, Conflict),
              // Setup takes no input the codec can fail to decode (both query parameters are read leniently, the
              // same as the vocabulary listing's `lang`/`target`), so its only failure is the aspect's 401.
              ("GET", "/api/games/setup")                                                 -> Set(Ok, Unauthorized),
              // The setup screen's word-list preview. A missing/empty tagIds simply answers an empty list, not a
              // 400, so its only failure is the aspect's 401, the same shape as setup.
              ("GET", "/api/games/setup/words")                                           -> Set(Ok, Unauthorized),
              // Same shape as setup: no input the codec can fail to decode, so its only failure is the aspect's 401.
              ("GET", "/api/games/mine")                                                  -> Set(Ok, Unauthorized),
              // The caller's own play history: never gated by trackResults, so its only failures are the query
              // codec's 400 and the aspect's 401.
              ("GET", "/api/games/plays/mine")                                            -> Set(Ok, BadRequest, Unauthorized),
              // createGame's own failures are all BadRequest (no tags selected, a tag ineligible for the language
              // pair, or a validation error) — it never raises NotFound/NotOwner.
              ("POST", "/api/games")                                                      -> Set(Created, BadRequest, Unauthorized),
              // Guarded by `optionalUser`, the same reasoning as the vocabulary reads: a shared game link must be
              // viewable before any guest is minted, so this declares no 401.
              ("GET", "/api/games/{slug}")                                                -> Set(Ok, NotFound),
              // The only endpoint whose 403 is a business rule outside login/guest: `GameService.rename` raises
              // `NotOwner` for anyone but the game's owner.
              ("PATCH", "/api/games/{slug}")                                              ->
                Set(Ok, BadRequest, Unauthorized, Forbidden, NotFound),
              // Owner-only, the same shape as rename: NotFound for an unknown slug, Forbidden for one that
              // belongs to somebody else, Conflict for a game with nothing fixed to reshuffle.
              ("POST", "/api/games/{slug}/reshuffle")                                     ->
                Set(NoContent, Unauthorized, Forbidden, NotFound, Conflict),
              // startPlay's own failures are NotFound (unknown slug) and BadRequest (the pool came back empty);
              // it never raises NotOwner, so no Forbidden.
              ("POST", "/api/games/{slug}/plays")                                         ->
                Set(Created, BadRequest, Unauthorized, NotFound),
              // The three play-id operations share one shape: NotFound for an unknown playId, Forbidden for one
              // that belongs to somebody else.
              ("GET", "/api/games/plays/{playId}/prompt")                                 ->
                Set(Ok, BadRequest, Unauthorized, Forbidden, NotFound),
              ("POST", "/api/games/plays/{playId}/answers")                               ->
                Set(NoContent, BadRequest, Unauthorized, Forbidden, NotFound),
              ("GET", "/api/games/plays/{playId}/results")                                ->
                Set(Ok, BadRequest, Unauthorized, Forbidden, NotFound),
              // Owner-only, and only for a `trackResults = true` game: NotFound for an unknown slug (or, for the
              // detail operation, a playId that does not belong to it), Forbidden for a game that belongs to somebody
              // else, Conflict for a game that never turned tracking on.
              ("GET", "/api/games/{slug}/plays")                                          ->
                Set(Ok, BadRequest, Unauthorized, Forbidden, NotFound, Conflict),
              ("GET", "/api/games/{slug}/plays/{playId}")                                 ->
                Set(Ok, BadRequest, Unauthorized, Forbidden, NotFound, Conflict),
              ("GET", "/api/me")                                                          -> Set(Ok, Unauthorized),
              ("PUT", "/api/me/theme")                                                    -> Set(Ok, BadRequest, Unauthorized),
              ("PUT", "/api/me/locale")                                                   -> Set(Ok, BadRequest, Unauthorized),
              ("GET", "/api/me/identities")                                               -> Set(Ok, Unauthorized),
              // 409 is the lockout guard (unlinking the last credential); 400 covers both an unparseable
              // provider segment and one that is simply not linked, since `AuthFailure` has no NotFound case.
              ("DELETE", "/api/me/identities/{provider}")                                 -> Set(NoContent, BadRequest, Unauthorized, Conflict),
              ("PUT", "/api/me/password")                                                 -> Set(NoContent, BadRequest, Unauthorized),
              ("GET", "/api/admin/users")                                                 -> Set(Ok, BadRequest, Unauthorized),
              ("POST", "/api/admin/users")                                                -> Set(Created, BadRequest, Unauthorized, NotFound, Conflict),
              ("GET", "/api/admin/users/{id}")                                            -> Set(Ok, BadRequest, Unauthorized, NotFound, Conflict),
              ("PUT", "/api/admin/users/{id}")                                            -> Set(Ok, BadRequest, Unauthorized, NotFound, Conflict),
              ("DELETE", "/api/admin/users/{id}")                                         -> Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              // The six account-diagnostic operations all go through `ApiFailures.admin`, so they carry its whole
              // union — the same residual slack the group endpoints have, and narrowing it means narrowing
              // `AdminService`'s signatures rather than these descriptions. The 409 is real on the unlink (last
              // credential) and only theoretical on the rest.
              ("GET", "/api/admin/users/{id}/detail")                                     -> Set(Ok, BadRequest, Unauthorized, NotFound, Conflict),
              // Same union as the other five account-diagnostic operations, through the same `ApiFailures.admin`.
              ("GET", "/api/admin/users/{id}/plays")                                      -> Set(Ok, BadRequest, Unauthorized, NotFound, Conflict),
              ("POST", "/api/admin/users/{id}/verify-email")                              ->
                Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              ("POST", "/api/admin/users/{id}/verification/resend")                       ->
                Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              ("DELETE", "/api/admin/users/{id}/sessions")                                ->
                Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              ("DELETE", "/api/admin/users/{id}/identities/{provider}")                   ->
                Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              ("DELETE", "/api/admin/users/{id}/lockout")                                 ->
                Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              // The read-only operations declare only what they can actually answer: a 400 where a query
              // parameter can fail to decode (`ApiEndpoint.codecError`), and the aspect's 401. `rateLimits`,
              // `systemOverview` and `systemPrune` take no input at all, so they have no 400 to declare — which is
              // what the user list used to be, before paging gave it query parameters of its own.
              ("GET", "/api/admin/audit")                                                 -> Set(Ok, BadRequest, Unauthorized),
              ("GET", "/api/admin/login-attempts")                                        -> Set(Ok, BadRequest, Unauthorized),
              ("GET", "/api/admin/rate-limits")                                           -> Set(Ok, Unauthorized),
              ("POST", "/api/admin/rate-limits/clear")                                    -> Set(NoContent, BadRequest, Unauthorized),
              ("GET", "/api/admin/system")                                                -> Set(Ok, Unauthorized),
              ("POST", "/api/admin/system/prune")                                         -> Set(Ok, Unauthorized),
              ("GET", "/api/admin/usage/routes")                                          -> Set(Ok, BadRequest, Unauthorized),
              ("GET", "/api/admin/usage/suspicious")                                      -> Set(Ok, BadRequest, Unauthorized),
              // Progress sharing: minting a code takes no input, so its only failure is the aspect's 401.
              ("POST", "/api/progress-shares/code")                                       -> Set(Ok, Unauthorized),
              // Redeeming can raise every ProgressShareFailure case: an unknown/revoked code, one's own code, or a
              // grant that already exists.
              ("POST", "/api/progress-shares/redeem")                                     ->
                Set(NoContent, BadRequest, Unauthorized, NotFound, Conflict),
              ("GET", "/api/progress-shares/viewers")                                     -> Set(Ok, Unauthorized),
              ("GET", "/api/progress-shares/shared-with-me")                              -> Set(Ok, Unauthorized),
              // Forbidden covers a caller with no share from the requested sharer.
              ("GET", "/api/progress-shares/{sharerUserId}/plays")                        ->
                Set(Ok, BadRequest, Unauthorized, Forbidden),
              // Idempotent: revoking a viewer with no share answers the same 204 as one that had one, so its only
              // failure is the aspect's 401.
              ("DELETE", "/api/progress-shares/viewers/{viewerUserId}")                   -> Set(NoContent, Unauthorized),
            )
        )
      },
      // The uniform set this started from put all seven failure statuses on every operation. Describing each
      // endpoint's own failures, and then dropping the three a well-behaved caller cannot provoke, is what takes it to
      // the count below: 192 across 47 operations. (It was 136 across 44 while the Todo and Group example features were
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
          declared == 192,
          declared < statuses.size * 7,
          // A service's own answer, never the CSRF or `adminOnly` aspect's: `AuthService`'s unverified-email refusal
          // on login, and `GameService`'s not-owner refusal (on rename, reshuffle, the three play-id operations, and
          // the owner-facing results listing/detail), are the ones in the skeleton. A feature whose service raises a
          // permission failure of its own adds its paths here.
          describes(Forbidden) ==
            Set(
              ("POST", "/api/auth/login"),
              ("POST", "/api/guest/code"),
              ("POST", "/api/auth/upgrade"),
              ("PATCH", "/api/games/{slug}"),
              ("POST", "/api/games/{slug}/reshuffle"),
              ("GET", "/api/games/plays/{playId}/prompt"),
              ("POST", "/api/games/plays/{playId}/answers"),
              ("GET", "/api/games/plays/{playId}/results"),
              ("GET", "/api/games/{slug}/plays"),
              ("GET", "/api/games/{slug}/plays/{playId}"),
              // `ProgressShareService.requireShareAccess`'s own refusal: the caller holds no grant from the
              // requested sharer.
              ("GET", "/api/progress-shares/{sharerUserId}/plays"),
            ),
          // The rate limiter wraps signup, login, the verification resend, the password-reset request, and the two
          // guest paths, plus both bulk word upload endpoints — the one non-auth feature with a budget of its own,
          // since a single confirm call can create and tag thousands of rows and preview shares its budget.
          describes(TooManyRequests) ==
            Set(
              ("POST", "/api/auth/signup"),
              ("POST", "/api/auth/login"),
              ("POST", "/api/auth/verification/resend"),
              ("POST", "/api/auth/password/forgot"),
              // Both are anonymous and both write: one mints an account, the other hands out a session.
              ("POST", "/api/guest"),
              ("POST", "/api/guest/claim"),
              ("POST", "/api/words/tags/{tagId}/bulk-upload/preview"),
              ("POST", "/api/words/tags/{tagId}/bulk-upload/confirm"),
            ),
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
              ("GET", "/api/auth/captcha-status"),
              // Both are reached by an account that cannot sign in yet, so neither can be behind the session.
              ("POST", "/api/auth/verify"),
              ("POST", "/api/auth/verification/resend"),
              // Both are reached by a visitor with no session by definition — asking for the link and
              // redeeming it are the whole of the recovery path for an account that cannot sign in.
              ("POST", "/api/auth/password/forgot"),
              ("POST", "/api/auth/password/reset"),
              // A visitor with no session mints one here, or brings a transfer code to it.
              ("POST", "/api/guest"),
              ("POST", "/api/guest/claim"),
              // The vocabulary reads, which are the whole reason the feature needs no sign-up.
              ("GET", "/api/words"),
              ("GET", "/api/words/{id}"),
              // A shared game link, the same reasoning as the vocabulary reads.
              ("GET", "/api/games/{slug}"),
            )
        )
      },
      test("every other operation requires the session cookie") {
        val guarded = operations.collect { case (method, path, true) =>
          (method, path)
        }
        assertTrue(
          guarded.size == operations.size - 14,
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
          document     <- run(Request.get("/api/docs/openapi/gathedge-api.json"))
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
