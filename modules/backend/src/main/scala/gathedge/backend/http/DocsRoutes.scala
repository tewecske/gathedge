package gathedge.backend.http

import gathedge.backend.security.SessionAuth
import gathedge.backend.config.AppConfig
import gathedge.shared.Branding
import gathedge.shared.api.{AdminEndpoints, AuthEndpoints, WordEndpoints}
import zio.http.*
import zio.http.codec.Doc
import zio.http.codec.PathCodec.path
import zio.http.endpoint.Endpoint
import zio.http.endpoint.openapi.{OpenAPI, OpenAPIGen, SwaggerUI}

/** Swagger UI over the described endpoints.
  *
  * The document is derived from the same values in `shared` that the route files are implemented against, so it cannot
  * drift from the server: a path, body or status that changes there changes here, with nothing to keep in sync by hand.
  *
  * Every endpoint appears except the two OAuth redirects, which are browser navigations rather than a body protocol and
  * stay on the imperative DSL (see `AuthRoutes`).
  *
  * Mounted under `/api` so the Vite dev proxy forwards it like any other backend route. The routes are public: they
  * expose the shape of the API, not its data, and every endpoint they document still requires whatever session the
  * aspects demand.
  */
object DocsRoutes {

  private val allEndpoints = AuthEndpoints.all ++ WordEndpoints.all ++ AdminEndpoints.all

  /** The endpoints reachable without a session, i.e. the ones whose `Routes` value in this package does *not* carry the
    * `authenticated` or `adminOnly` aspect. Everything else needs the session cookie.
    *
    * This is the one fact about the API that the descriptions in `shared` deliberately do not state — authentication is
    * a `HandlerAspect` on whole `Routes` values, which nothing can read back — so the document cannot derive it and it
    * is listed here instead, in the module that owns the aspects. `OpenApiSpec` pins both sides of the split.
    */
  private val publicEndpoints: List[Endpoint[?, ?, ?, ?, ?]] = {
    List(
      AuthEndpoints.signup,
      AuthEndpoints.login,
      AuthEndpoints.logout,
      AuthEndpoints.providers,
      AuthEndpoints.verifyEmail,
      AuthEndpoints.resendVerification,
      AuthEndpoints.forgotPassword,
      AuthEndpoints.resetPassword,
    ) ++
      // Minting a guest and redeeming a transfer code both hand out a session to somebody who has none.
      AuthEndpoints.publicGuest ++
      // The dictionary reads. They are wrapped in `optionalUser` rather than `authenticated`: a visitor sees the same
      // words and no tags, which is what lets the feature be used before signing up for anything.
      WordEndpoints.public
  }

  private val sessionSchemeName = "sessionCookie"

  /** The session is an opaque token in an `HttpOnly` cookie, so this is an `apiKey` scheme located in a cookie — not
    * `http`/`bearer`. Swagger UI cannot fill it in (that is the point of `HttpOnly`); a browser that has signed in
    * through `/api/auth/login` sends it on its own.
    */
  private val sessionScheme: OpenAPI.SecurityScheme = {
    OpenAPI.SecurityScheme
      .ApiKey(
        description = Some(Doc.p("Opaque session token set by /api/auth/signup and /api/auth/login.")),
        name = SessionAuth.cookieName,
        in = OpenAPI.SecurityScheme.ApiKey.In.Cookie,
      )
  }

  private val requiresSession = {
    List(OpenAPI.SecurityScheme.SecurityRequirement(Map(sessionSchemeName -> List.empty[String])))
  }

  /** Marks every operation that is not in [[publicEndpoints]] as requiring the session cookie.
    *
    * Which operation is which is decided by generating a second document from the public endpoints alone and using its
    * paths and methods as the exemption list, rather than by matching path strings by hand — the two documents render a
    * path the same way (`/api/admin/users/{id}`) because the same generator produced both.
    */
  private def requireSession(document: OpenAPI): OpenAPI = {
    val publicDocument =
      OpenAPIGen.fromEndpoints(title = "public", version = AppConfig.apiVersion, endpoints = publicEndpoints)

    def mark(operation: Option[OpenAPI.Operation], isPublic: Option[OpenAPI.Operation]): Option[OpenAPI.Operation] = {
      operation.map(op => {
        if (isPublic.isDefined)
          op
        else
          op.copy(security = requiresSession)
      })
    }

    val paths = document.paths
      .map { case (path, item) =>
        val open   = publicDocument.paths.getOrElse(path, OpenAPI.PathItem.empty)
        val marked = item.copy(
          get = mark(item.get, open.get),
          put = mark(item.put, open.put),
          post = mark(item.post, open.post),
          delete = mark(item.delete, open.delete),
          options = mark(item.options, open.options),
          head = mark(item.head, open.head),
          patch = mark(item.patch, open.patch),
          trace = mark(item.trace, open.trace),
        )
        (path, marked)
      }

    val components = document.components.getOrElse(OpenAPI.Components())
    val withScheme = {
      OpenAPI.Key
        .fromString(sessionSchemeName)
        .map { key =>
          components.copy(securitySchemes = components.securitySchemes + (key -> OpenAPI.ReferenceOr.Or(sessionScheme)))
        }
    }
    document.copy(paths = paths, components = withScheme.orElse(document.components))
  }

  /** The title also names the JSON document — it is served at `<basePath>/<url-encoded title>.json` — which is why it
    * is built from [[Branding.slug]] rather than [[Branding.appName]]: the slug is the half guaranteed to be free of
    * spaces.
    */
  val openApi: OpenAPI = {
    requireSession(
      OpenAPIGen.fromEndpoints(
        title = s"${Branding.slug}-api",
        version = AppConfig.apiVersion,
        endpoints = allEndpoints,
      )
    )
  }

  val basePath = "api" / "docs" / "openapi"

  val routes: Routes[Any, Response] = SwaggerUI.routes(basePath, openApi)
}
