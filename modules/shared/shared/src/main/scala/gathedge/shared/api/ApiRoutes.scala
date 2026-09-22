package gathedge.shared.api

import zio.http.{Method, RoutePattern}
import zio.http.codec.PathCodec

/** Turns an [[ApiPath]] into the zio-http route an `Endpoint` is built on. The route's literals and parameter names
  * come from the template; the caller gives only each parameter's codec, e.g. `PathCodec.long`.
  *
  * Only the `*Endpoints` descriptions call this, so the frontend never reaches zio-http through it.
  */
object ApiRoutes {

  def route0(p: ApiPath0): RoutePattern[Unit] = {
    val (List(r0), _) = split(p): @unchecked
    method(p) / literals(r0)
  }

  def route1[A](p: ApiPath1[A], a: String => PathCodec[A]): RoutePattern[A] = {
    val (List(r0, r1), List(n0)) = split(p): @unchecked
    method(p) / (literals(r0) / a(n0) / literals(r1))
  }

  def route2[A, B](p: ApiPath2[A, B], a: String => PathCodec[A], b: String => PathCodec[B]): RoutePattern[(A, B)] = {
    val (List(r0, r1, r2), List(n0, n1)) = split(p): @unchecked
    method(p) / (literals(r0) / a(n0) / literals(r1) / b(n1) / literals(r2))
  }

  def route3[A, B, C](
    p: ApiPath3[A, B, C],
    a: String => PathCodec[A],
    b: String => PathCodec[B],
    c: String => PathCodec[C],
  ): RoutePattern[(A, B, C)] = {
    val (List(r0, r1, r2, r3), List(n0, n1, n2)) = split(p): @unchecked
    method(p) / (literals(r0) / a(n0) / literals(r1) / b(n1) / literals(r2) / c(n2) / literals(r3))
  }

  private def method(p: ApiPath): Method = Method.fromString(p.method.toString)

  /** `/api/words/{id}/tags/{tagId}` gives the literal runs `[[api, words], [tags], []]` and the names `[id, tagId]`. */
  private def split(p: ApiPath): (List[List[String]], List[String]) = {
    val segments = p.template.split('/').toList.filter(_.nonEmpty)
    segments.foldRight((List(List.empty[String]), List.empty[String])) { case (s, (runs, names)) =>
      if (s.startsWith("{")) (Nil :: runs, s.drop(1).dropRight(1) :: names)
      else ((s :: runs.head) :: runs.tail, names)
    }
  }

  private def literals(run: List[String]): PathCodec[Unit] =
    run.foldLeft(PathCodec.empty)((codec, s) => codec / PathCodec.literal(s))
}
