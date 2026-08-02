package webapp1.backend.http

import webapp1.shared.api.{AdminEndpoints, AuthEndpoints, GroupEndpoints, InvitationEndpoints, TodoEndpoints}
import zio.http.*
import zio.http.codec.PathCodec.path
import zio.http.endpoint.openapi.{OpenAPI, OpenAPIGen, SwaggerUI}

/** Swagger UI over the described endpoints.
  *
  * The document is derived from the same values in `shared` that the route files are implemented against, so it cannot
  * drift from the server: a path, body or status that changes there changes here, with nothing to keep in sync by hand.
  *
  * Every endpoint appears except the two Google OAuth redirects, which are browser navigations rather than a body
  * protocol and stay on the imperative DSL (see `AuthRoutes`).
  *
  * Mounted under `/api` so the Vite dev proxy forwards it like any other backend route. The routes are public: they
  * expose the shape of the API, not its data, and every endpoint they document still requires whatever session the
  * aspects demand.
  */
object DocsRoutes {

  /** The title also names the JSON document — it is served at `<basePath>/<url-encoded title>.json` — so keep it free
    * of spaces.
    */
  val openApi: OpenAPI = {
    OpenAPIGen.fromEndpoints(
      title = "webapp1-api",
      version = "0.1.0",
      endpoints =
        AuthEndpoints.all ++ TodoEndpoints.all ++ GroupEndpoints.all ++ InvitationEndpoints.all ++ AdminEndpoints.all,
    )
  }

  val basePath = "api" / "docs" / "openapi"

  val routes: Routes[Any, Response] = SwaggerUI.routes(basePath, openApi)
}
