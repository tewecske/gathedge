package gathedge.shared.api

import java.nio.charset.StandardCharsets

/** The HTTP methods the API uses. */
enum ApiMethod {
  case GET, POST, PUT, PATCH, DELETE
}

/** One call, ready to send: its method and its path, with every parameter filled in. Append a query string with
  * [[withQuery]].
  */
final case class ApiCall(method: ApiMethod, path: String) {
  def withQuery(query: String): ApiCall = copy(path = path + query)
}

/** One endpoint's method and path template, e.g. `GET /api/progress-shares/{sharerUserId}/plays`.
  *
  * The template is written once, here. The frontend fills it in to make an [[ApiCall]]. [[ApiRoutes]] turns it into the
  * zio-http route that the `*Endpoints` descriptions are built on. So the backend, the OpenAPI document and the
  * frontend cannot disagree about a path or a method.
  *
  * Nothing in this file imports zio-http. That lets the frontend use it without the Scala.js linker keeping the
  * endpoint machinery.
  *
  * One class per number of path parameters. Each one refuses a template whose placeholder count is different, when its
  * `*Paths` object loads.
  */
sealed trait ApiPath {
  def method: ApiMethod
  def template: String
}

final case class ApiPath0(method: ApiMethod, template: String) extends ApiPath {
  ApiPath.requireArity(template, 0)
  def apply(): ApiCall = ApiCall(method, template)
}

final case class ApiPath1[A](method: ApiMethod, template: String) extends ApiPath {
  ApiPath.requireArity(template, 1)
  def apply(a: A): ApiCall = ApiCall(method, ApiPath.fill(template, List(a)))
}

final case class ApiPath2[A, B](method: ApiMethod, template: String) extends ApiPath {
  ApiPath.requireArity(template, 2)
  def apply(a: A, b: B): ApiCall = ApiCall(method, ApiPath.fill(template, List(a, b)))
}

final case class ApiPath3[A, B, C](method: ApiMethod, template: String) extends ApiPath {
  ApiPath.requireArity(template, 3)
  def apply(a: A, b: B, c: C): ApiCall = ApiCall(method, ApiPath.fill(template, List(a, b, c)))
}

object ApiPath {

  private val placeholder = "\\{([^/{}]+)\\}".r

  private[api] def requireArity(template: String, arity: Int): Unit = {
    val found = placeholder.findAllMatchIn(template).size
    require(found == arity, s"$template has $found path parameters, its ApiPath class takes $arity")
  }

  /** Puts each argument into the next placeholder, in order, percent-encoded as one path segment. */
  private[api] def fill(template: String, args: List[Any]): String = {
    val it = args.iterator
    placeholder.replaceAllIn(template, _ => java.util.regex.Matcher.quoteReplacement(encodeSegment(it.next().toString)))
  }

  /** Keeps RFC 3986's unreserved characters and percent-encodes every other UTF-8 byte. */
  private[api] def encodeSegment(s: String): String = {
    val sb = new StringBuilder
    s.getBytes(StandardCharsets.UTF_8).foreach { b =>
      val c = (b & 0xff).toChar
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-._~".contains(c)) sb += c
      else sb ++= f"%%${b & 0xff}%02X"
    }
    sb.toString
  }
}
