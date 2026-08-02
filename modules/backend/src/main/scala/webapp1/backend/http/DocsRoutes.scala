package webapp1.backend.http

import webapp1.shared.api.AdminEndpoints
import zio.http.*
import zio.http.codec.PathCodec.path
import zio.http.endpoint.openapi.{OpenAPI, OpenAPIGen, SwaggerUI}

/** Swagger UI over the declaratively described endpoints.
  *
  * The document is derived from the same `AdminEndpoints` values `AdminRoutes` is implemented against, so it cannot
  * drift from the server: a path, body or status that changes there changes here, with nothing to keep in sync by hand.
  * Only the admin resource appears — the other route files are built with the imperative DSL and describe nothing.
  *
  * Mounted under `/api` so the Vite dev proxy forwards it like any other backend route. The routes are public: they
  * expose the shape of the admin API, not its data, and every endpoint they document still requires an admin session.
  */
object DocsRoutes {

  /** The title also names the JSON document — it is served at `<basePath>/<url-encoded title>.json` — so keep it free
    * of spaces.
    */
  val openApi: OpenAPI = {
    OpenAPIGen.fromEndpoints(
      title = "webapp1-admin-api",
      version = "0.1.0",
      AdminEndpoints.listUsers,
      AdminEndpoints.getUser,
      AdminEndpoints.createUser,
      AdminEndpoints.updateUser,
      AdminEndpoints.deleteUser,
    )
  }

  val basePath = "api" / "docs" / "openapi"

  val routes: Routes[Any, Response] = SwaggerUI.routes(basePath, openApi)
}
