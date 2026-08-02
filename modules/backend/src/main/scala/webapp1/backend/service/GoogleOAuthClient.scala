package webapp1.backend.service

import webapp1.backend.config.{AppConfig, GoogleSection}
import zio.*
import zio.http.{Body, Client, Form, FormField, QueryParams, Request, Response, Status, URL}
import zio.json.*

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

final case class GoogleIdentity(subject: String, email: String, emailVerified: Boolean)

private final case class GoogleTokenResponse(id_token: String) derives JsonDecoder

// Google's tokeninfo endpoint returns email_verified as the *string* "true"/"false".
private final case class GoogleTokenInfo(aud: String, sub: String, email: String, email_verified: String)
    derives JsonDecoder

trait GoogleOAuthClient {
  def authorizationUrl(state: String): String
  def exchangeAndVerify(code: String): Task[GoogleIdentity]
}

/** Server-side OAuth2 authorization-code flow. `id_token` is verified via Google's `tokeninfo` endpoint (checks
  * `aud`/signature/`exp` server-side) rather than local JWKS verification — one extra network round trip, but avoids
  * pulling in a JWT/JWKS library for this scope. Can be upgraded to local JWKS verification later without changing the
  * [[GoogleOAuthClient]] interface.
  *
  * The two calls go out over zio-http's own `Client` — the same library the server is built on — rather than a second
  * HTTP stack. That is not just tidiness: `java.net.http`'s synchronous `send` had to run inside `attemptBlocking`,
  * which cannot be interrupted, so a Google endpoint that accepted the connection and then went quiet held a
  * blocking-pool thread until the OS gave up on the socket. A `Client` request is an ordinary interruptible effect, so
  * the timeout below actually releases everything it was holding, and a caller that gives up (the browser closing the
  * OAuth tab) cancels the request with it.
  */
final class GoogleOAuthClientLive(config: GoogleSection, client: Client) extends GoogleOAuthClient {

  private val requestTimeout = 10.seconds

  private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  private def urlOf(raw: String, query: QueryParams = QueryParams.empty): Task[URL] = {
    ZIO
      .fromEither(URL.decode(raw))
      .mapBoth(
        err => new RuntimeException(s"Invalid Google endpoint URL '$raw': ${err.getMessage}"),
        _.addQueryParams(query),
      )
  }

  /** One round trip, bounded in wall-clock time rather than by socket-level timeouts. `batched` reads the whole
    * response body before returning, so nothing is left to consume after the effect completes.
    */
  private def send(request: Request, what: String): Task[Response] = {
    client.batched(request).timeoutFail(new RuntimeException(s"Google $what timed out"))(requestTimeout)
  }

  def authorizationUrl(state: String): String = {
    val params = Map(
      "client_id" -> config.clientId,
      "redirect_uri" -> config.redirectUri,
      "response_type" -> "code",
      "scope" -> "openid email",
      "state" -> state,
    )
    val query = params
      .map { case (k, v) =>
        s"${enc(k)}=${enc(v)}"
      }
      .mkString("&")
    s"https://accounts.google.com/o/oauth2/v2/auth?$query"
  }

  def exchangeAndVerify(code: String): Task[GoogleIdentity] = {
    for {
      idToken <- exchangeCode(code)
      identity <- verifyIdToken(idToken)
    } yield identity
  }

  private def exchangeCode(code: String): Task[String] = {
    val form = Form(
      FormField.simpleField("code", code),
      FormField.simpleField("client_id", config.clientId),
      FormField.simpleField("client_secret", config.clientSecret),
      FormField.simpleField("redirect_uri", config.redirectUri),
      FormField.simpleField("grant_type", "authorization_code"),
    )
    for {
      url <- urlOf("https://oauth2.googleapis.com/token")
      // `fromURLEncodedForm` sets `Content-Type: application/x-www-form-urlencoded` itself, which the token
      // endpoint requires and which the hand-built body had to declare separately.
      response <- send(Request.post(url, Body.fromURLEncodedForm(form)), "token exchange")
      _ <-
        ZIO
          .unless(response.status == Status.Ok)(
            ZIO.fail(new RuntimeException(s"Google token exchange failed with status ${response.status.code}"))
          )
          .unit
      body <- response.body.asString
      idToken <- ZIO
        .fromEither(body.fromJson[GoogleTokenResponse])
        .mapBoth(err => new RuntimeException(s"Malformed Google token response: $err"), _.id_token)
    } yield idToken
  }

  private def verifyIdToken(idToken: String): Task[GoogleIdentity] = {
    for {
      url <- urlOf("https://oauth2.googleapis.com/tokeninfo", QueryParams("id_token" -> idToken))
      response <- send(Request.get(url), "id_token verification")
      _ <-
        ZIO
          .unless(response.status == Status.Ok)(ZIO.fail(new RuntimeException("Google id_token verification failed")))
          .unit
      body <- response.body.asString
      info <- ZIO
        .fromEither(body.fromJson[GoogleTokenInfo])
        .mapError(err => new RuntimeException(s"Malformed Google tokeninfo response: $err"))
      _ <-
        ZIO
          .unless(info.aud == config.clientId)(ZIO.fail(new RuntimeException("Google id_token audience mismatch")))
          .unit
    } yield GoogleIdentity(info.sub, info.email, info.email_verified == "true")
  }
}

object GoogleOAuthClient {
  val live: URLayer[AppConfig & Client, GoogleOAuthClient] = ZLayer.fromFunction((cfg: AppConfig, client: Client) =>
    new GoogleOAuthClientLive(cfg.google, client): GoogleOAuthClient
  )
}
