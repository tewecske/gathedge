package gathedge.shared.api

import zio.http.{Method, Path}
import zio.http.codec.PathCodec
import zio.http.endpoint.Endpoint
import zio.test.*

/** Pins [[ApiPath]] and [[ApiRoutes]]: a route built from a template matches the template, and every `*Endpoints`
  * object has exactly the routes its `*Paths` object lists — no more, no less.
  */
object ApiPathSpec extends ZIOSpecDefault {

  private def signature(e: Endpoint[?, ?, ?, ?, ?]): (String, String) =
    (e.route.method.name, e.route.pathCodec.render)

  private def signature(p: ApiPath): (String, String) = (p.method.toString, p.template)

  /** One entry per resource. A new `*Endpoints` file adds its line here. */
  private val resources: List[(String, List[Endpoint[?, ?, ?, ?, ?]], List[ApiPath])] = List(
    ("admin", AdminEndpoints.all, AdminPaths.all),
    ("auth", AuthEndpoints.all, AuthPaths.all),
    ("games", GameEndpoints.all, GamePaths.all),
    ("groups", GroupEndpoints.all, GroupPaths.all),
    ("progress shares", ProgressShareEndpoints.all, ProgressSharePaths.all),
    ("streak", StreakEndpoints.all, StreakPaths.all),
    ("words", WordEndpoints.all, WordPaths.all),
  )

  def spec = suite("ApiPath")(
    test("filling a template puts each argument in order, percent-encoded as one segment") {
      val p = ApiPath2[String, Long](ApiMethod.GET, "/api/x/{name}/y/{id}")
      assertTrue(
        p("a b/ö", 7L) == ApiCall(ApiMethod.GET, "/api/x/a%20b%2F%C3%B6/y/7"),
        p("a", 1L).withQuery("?q=1").path == "/api/x/a/y/1?q=1",
      )
    },
    test("a template whose placeholder count differs from its class is refused") {
      val tooFew  = scala.util.Try(ApiPath2[Long, Long](ApiMethod.GET, "/api/x/{id}"))
      val tooMany = scala.util.Try(ApiPath0(ApiMethod.GET, "/api/x/{id}"))
      assertTrue(tooFew.isFailure, tooMany.isFailure)
    },
    test("a derived route renders as its template and decodes the parameters in order") {
      val p     = ApiPath2[Long, Long](ApiMethod.PUT, "/api/words/{id}/tags/{tagId}")
      val route = ApiRoutes.route2(p, PathCodec.long, PathCodec.long)
      assertTrue(
        route.method == Method.PUT,
        route.pathCodec.render == p.template,
        route.decode(Method.PUT, Path("/api/words/7/tags/9")) == Right((7L, 9L)),
        route.decode(Method.GET, Path("/api/words/7/tags/9")).isLeft,
      )
    },
    test("every Endpoints object has exactly the routes of its Paths object") {
      val mismatches = resources.flatMap { case (resource, endpoints, paths) =>
        val fromEndpoints = endpoints.map(signature).toSet
        val fromPaths     = paths.map(signature).toSet
        (fromEndpoints -- fromPaths).map(s => s"$resource: endpoint $s has no path") ++
          (fromPaths -- fromEndpoints).map(s => s"$resource: path $s has no endpoint")
      }
      assertTrue(mismatches.isEmpty)
    },
  )
}
