package webapp1.backend.service

import webapp1.backend.config.{AppConfig, GoogleSection}
import zio.*
import zio.json.*

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
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

/** Server-side OAuth2 authorization-code flow. `id_token` is verified via Google's
  * `tokeninfo` endpoint (checks `aud`/signature/`exp` server-side) rather than local
  * JWKS verification — one extra network round trip, but avoids pulling in a JWT/JWKS
  * library for this scope. Can be upgraded to local JWKS verification later without
  * changing the [[GoogleOAuthClient]] interface.
  */
final class GoogleOAuthClientLive(config: GoogleSection) extends GoogleOAuthClient {
  private val httpClient = HttpClient.newHttpClient()

  private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  def authorizationUrl(state: String): String = {
    val params = Map(
      "client_id"     -> config.clientId,
      "redirect_uri"  -> config.redirectUri,
      "response_type" -> "code",
      "scope"         -> "openid email",
      "state"         -> state,
    )
    val query = params.map { case (k, v) => s"${enc(k)}=${enc(v)}" }.mkString("&")
    s"https://accounts.google.com/o/oauth2/v2/auth?$query"
  }

  def exchangeAndVerify(code: String): Task[GoogleIdentity] = {
    for {
      idToken  <- exchangeCode(code)
      identity <- verifyIdToken(idToken)
    } yield identity
  }

  private def exchangeCode(code: String): Task[String] = {
    ZIO
      .attemptBlocking {
        val form = Map(
          "code"          -> code,
          "client_id"     -> config.clientId,
          "client_secret" -> config.clientSecret,
          "redirect_uri"  -> config.redirectUri,
          "grant_type"    -> "authorization_code",
        ).map { case (k, v) => s"${enc(k)}=${enc(v)}" }.mkString("&")
        val request = HttpRequest
          .newBuilder()
          .uri(URI.create("https://oauth2.googleapis.com/token"))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(form))
          .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      }
      .flatMap { response =>
        if (response.statusCode() != 200) {
          ZIO.fail(new RuntimeException(s"Google token exchange failed with status ${response.statusCode()}"))
        } else {
          ZIO
            .fromEither(response.body().fromJson[GoogleTokenResponse])
            .mapBoth(err => new RuntimeException(s"Malformed Google token response: $err"), _.id_token)
        }
      }
  }

  private def verifyIdToken(idToken: String): Task[GoogleIdentity] = {
    ZIO
      .attemptBlocking {
        val request = HttpRequest
          .newBuilder()
          .uri(URI.create(s"https://oauth2.googleapis.com/tokeninfo?id_token=${enc(idToken)}"))
          .GET()
          .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      }
      .flatMap { response =>
        if (response.statusCode() != 200) {
          ZIO.fail(new RuntimeException("Google id_token verification failed"))
        } else {
          ZIO
            .fromEither(response.body().fromJson[GoogleTokenInfo])
            .mapError(err => new RuntimeException(s"Malformed Google tokeninfo response: $err"))
        }
      }
      .flatMap { info =>
        if (info.aud != config.clientId) {
          ZIO.fail(new RuntimeException("Google id_token audience mismatch"))
        } else {
          ZIO.succeed(GoogleIdentity(info.sub, info.email, info.email_verified == "true"))
        }
      }
  }
}

object GoogleOAuthClient {
  val live: URLayer[AppConfig, GoogleOAuthClient] =
    ZLayer.fromFunction((cfg: AppConfig) => new GoogleOAuthClientLive(cfg.google): GoogleOAuthClient)
}
