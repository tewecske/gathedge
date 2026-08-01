package webapp1.frontend.api

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.shared.dto.ErrorResponse
import zio.json._

import scala.scalajs.js

final case class ApiError(status: Int, message: String, fieldErrors: Map[String, String])

/** Thin `fetch` wrapper, using the shared zio-json DTOs for (de)serialization. Returns `EventStream`s per the laminar
  * skill's explicit-flattening guidance — callers `flatMapSwitch` these from a click/submit stream.
  */
object ApiClient {

  private def buildInit(method: String, bodyJson: Option[String]): dom.RequestInit = {
    val init = js
      .Dynamic
      .literal(
        method = method,
        headers = js.Dictionary("Content-Type" -> "application/json", "X-Requested-With" -> "XMLHttpRequest"),
        credentials = "include",
      )
    bodyJson.foreach(b => init.updateDynamic("body")(b))
    init.asInstanceOf[dom.RequestInit]
  }

  private def errorFrom(status: Int, text: String): ApiError = {
    text.fromJson[ErrorResponse] match {
      case Right(err) =>
        ApiError(status, err.message, err.fieldErrors)
      case Left(_) =>
        ApiError(status, s"Request failed with status $status", Map.empty)
    }
  }

  // A rejected fetch promise (offline, DNS failure, connection refused, CORS) would
  // otherwise propagate as a stream error instead of a value, leaving callers that
  // only `.foreach`/`child <--` off the success path stuck (e.g. App's initial
  // session load never completing). Surface it as a normal Left instead.
  private def networkSafe[A](stream: EventStream[Either[ApiError, A]]): EventStream[Either[ApiError, A]] = {
    stream.recover { case err =>
      Some(Left(ApiError(0, s"Network error: ${err.getMessage}", Map.empty)))
    }
  }

  private def parseResponse[Res: JsonDecoder](response: dom.Response): EventStream[Either[ApiError, Res]] = {
    EventStream
      .fromJsPromise(response.text())
      .map { text =>
        if (response.ok) {
          text.fromJson[Res].left.map(err => ApiError(response.status, s"Malformed response: $err", Map.empty))
        } else {
          Left(errorFrom(response.status, text))
        }
      }
  }

  /** For responses with no meaningful body (e.g. 204 No Content) — doesn't attempt to JSON-decode a success body, only
    * an error body on failure.
    */
  private def parseNoContentResponse(response: dom.Response): EventStream[Either[ApiError, Unit]] = {
    EventStream
      .fromJsPromise(response.text())
      .map { text =>
        if (response.ok)
          Right(())
        else
          Left(errorFrom(response.status, text))
      }
  }

  private def request(method: String, path: String, bodyJson: Option[String]): EventStream[dom.Response] = {
    EventStream.fromJsPromise(dom.fetch(path, buildInit(method, bodyJson)))
  }

  def get[Res: JsonDecoder](path: String): EventStream[Either[ApiError, Res]] = {
    networkSafe(request("GET", path, None).flatMapSwitch(parseResponse[Res]))
  }

  def post[Req: JsonEncoder, Res: JsonDecoder](path: String, body: Req): EventStream[Either[ApiError, Res]] = {
    networkSafe(request("POST", path, Some(body.toJson)).flatMapSwitch(parseResponse[Res]))
  }

  /** POST with no request body, decoding a JSON response (e.g. accepting an invitation, which needs no input but
    * returns the joined Group).
    */
  def postNoBody[Res: JsonDecoder](path: String): EventStream[Either[ApiError, Res]] = {
    networkSafe(request("POST", path, None).flatMapSwitch(parseResponse[Res]))
  }

  def put[Req: JsonEncoder, Res: JsonDecoder](path: String, body: Req): EventStream[Either[ApiError, Res]] = {
    networkSafe(request("PUT", path, Some(body.toJson)).flatMapSwitch(parseResponse[Res]))
  }

  /** POST with no meaningful response body (e.g. 204 No Content), no request body. */
  def postNoContent(path: String): EventStream[Either[ApiError, Unit]] = {
    networkSafe(request("POST", path, None).flatMapSwitch(parseNoContentResponse))
  }

  /** POST with a request body but no meaningful response body. */
  def postBodyNoContent[Req: JsonEncoder](path: String, body: Req): EventStream[Either[ApiError, Unit]] = {
    networkSafe(request("POST", path, Some(body.toJson)).flatMapSwitch(parseNoContentResponse))
  }

  /** PUT with a request body but no meaningful response body. */
  def putBodyNoContent[Req: JsonEncoder](path: String, body: Req): EventStream[Either[ApiError, Unit]] = {
    networkSafe(request("PUT", path, Some(body.toJson)).flatMapSwitch(parseNoContentResponse))
  }

  /** DELETE with no request body and no meaningful response body. */
  def delete(path: String): EventStream[Either[ApiError, Unit]] = {
    networkSafe(request("DELETE", path, None).flatMapSwitch(parseNoContentResponse))
  }
}
