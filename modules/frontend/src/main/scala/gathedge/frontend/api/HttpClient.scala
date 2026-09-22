package gathedge.frontend.api

import com.raquo.airstream.web.FetchStream
import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.i18n.{CurrentLocale, I18n}
import gathedge.shared.api.{ApiCall, ApiMethod}
import gathedge.shared.domain.Locale.code
import gathedge.shared.dto.ErrorResponse
import gathedge.shared.i18n.{MessageKeys, MessageRef}
import zio.json._

import scala.scalajs.js

/** What every API call in this app goes through: a thin wrapper over Airstream's [[FetchStream]] and zio-json.
  *
  * There is no ZIO runtime and no zio-http client on the frontend. A call takes its method and path from a shared
  * `*Paths` object (see `ApiPath`) through [[call]] and [[callUnit]]; the backend builds its routes from the same
  * object, so the two cannot drift.
  *
  * Two headers ride on every request. `X-Requested-With` is the CSRF token `RouteSupport.csrf` checks on the server;
  * `X-Locale` tells `RouteSupport.requestContext` which language this page runs in, which is the language the two
  * transactional emails are written in. The session cookie is applied by the browser — same-origin in dev (the Vite
  * proxy) and in production (nginx) — so nothing here touches it.
  */
object HttpClient {

  /** The one value no server produces: a call that never got an answer — offline, a dead socket, a body nothing could
    * decode. Every other `ApiError` carries the real HTTP status, so a page can still branch on 401/403/404/409.
    */
  private def noAnswer: ApiError = ApiError(0, I18n.t(MessageKeys.requestFailed), Map.empty)

  /** Every current browser has `fetch`; jsdom (the frontend test env) does not. Without it a call degrades to the same
    * `ApiError(0)` a dead socket gives, rather than a `ReferenceError` thrown at mount.
    */
  private val fetchAvailable: Boolean = js.typeOf(js.Dynamic.global.fetch) == "function"

  private def enc(s: String): String = js.URIUtils.encodeURIComponent(s)

  /** Renders `?k=v&…` from the parameters that are set, URL-encoded, dropping every `None`. Empty string when nothing
    * is set, so a call site can always append it.
    */
  def query(params: (String, Option[Any])*): String = {
    val parts = params.collect { case (k, Some(v)) => s"${enc(k)}=${enc(v.toString)}" }
    if (parts.isEmpty) "" else parts.mkString("?", "&", "")
  }

  /** Resolves a server-sent catalog key to the reader's language, the seam every call passes through on its way to a
    * page. The English `message` the server sends alongside the key is for callers with no catalog and is ignored here.
    */
  private def toApiError(status: Int, body: String): ApiError = {
    body.fromJson[ErrorResponse] match {
      case Right(err) =>
        ApiError(status, I18n.resolve(err.error), err.fieldErrors.view.mapValues(I18n.resolve).toMap)
      case Left(_)    =>
        // A body that is not `ErrorResponse`-shaped: keep the status a page can branch on, word it generically.
        ApiError(status, I18n.t(MessageKeys.requestFailed), Map.empty)
    }
  }

  private def send(
    method: dom.HttpMethod.type => dom.HttpMethod,
    path: String,
    body: Option[String],
  ): EventStream[(Int, Boolean, String)] = {
    val headers = {
      val base = List("X-Requested-With" -> "XMLHttpRequest", "X-Locale" -> CurrentLocale.value.code)
      if (body.isDefined) base :+ ("Content-Type" -> "application/json; charset=UTF-8") else base
    }
    if (!fetchAvailable) {
      // `.delay(0)` so the failure lands after the mount transaction, the way a real `fetch` rejection would — a page
      // must still get to render its pre-answer state.
      EventStream
        .fromValue(0)
        .delay(0)
        .map[(Int, Boolean, String)](_ => throw new RuntimeException("fetch is not available"))
    } else {
      FetchStream
        .raw(
          method,
          path,
          o => {
            o.credentials(_.include)
            o.headers(headers*)
            body.foreach(b => o.body(b))
          },
        )
        .flatMapSwitch(resp => EventStream.fromJsPromise(resp.text()).map(text => (resp.status, resp.ok, text)))
    }
  }

  /** Callers get `EventStream[Either[ApiError, A]]`: a failure is a value on the success path, never a stream error, so
    * an `onMountCallback`-driven load cannot hang on a rejected promise (the laminar skill's explicit-flattening rule).
    */
  private def decoded[A](stream: EventStream[(Int, Boolean, String)])(
    onOk: String => Either[ApiError, A]
  ): EventStream[Either[ApiError, A]] = {
    stream
      .map { case (status, ok, text) =>
        if (ok) onOk(text) else Left(toApiError(status, text))
      }
      .recover { case err =>
        dom.console.warn(s"Request failed: ${err.getMessage}")
        Some(Left(noAnswer))
      }
  }

  private def json[A: JsonDecoder](
    method: dom.HttpMethod.type => dom.HttpMethod,
    path: String,
    body: Option[String],
  ): EventStream[Either[ApiError, A]] = {
    decoded(send(method, path, body))(text => text.fromJson[A].left.map(_ => noAnswer))
  }

  private def verb(method: ApiMethod): dom.HttpMethod.type => dom.HttpMethod = method match {
    case ApiMethod.GET    => _.GET
    case ApiMethod.POST   => _.POST
    case ApiMethod.PUT    => _.PUT
    case ApiMethod.PATCH  => _.PATCH
    case ApiMethod.DELETE => _.DELETE
  }

  /** A call from a shared `*Paths` object, answered with a JSON body. */
  def call[A: JsonDecoder](c: ApiCall, body: Option[String] = None): EventStream[Either[ApiError, A]] =
    json(verb(c.method), c.path, body)

  /** A call from a shared `*Paths` object, answered with a bare 204. A failure still decodes the error body. */
  def callUnit(c: ApiCall, body: Option[String] = None): EventStream[Either[ApiError, Unit]] =
    decoded(send(verb(c.method), c.path, body))(_ => Right(()))
}
