package gathedge.backend.service

import gathedge.backend.config.{AppConfig, GoogleSection, MicrosoftSection}
import gathedge.shared.domain.OAuthProvider
import zio.*
import zio.http.{Body, Client, Form, FormField, QueryParams, Request, Response, Status, URL}
import zio.json.*
import zio.json.ast.Json

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** What a completed sign-in tells us about the person signing in.
  *
  * `subject` is the provider's own stable identifier and the *only* field allowed to decide which account is entered —
  * `email` is display metadata. See `AuthService.loginWithOAuth` for why: matching on email is the account-takeover
  * path, and it is closed by never auto-linking.
  */
final case class OAuthIdentity(provider: OAuthProvider, subject: String, email: String, emailVerified: Boolean)

private final case class TokenResponse(id_token: String) derives JsonDecoder

// Google's tokeninfo endpoint returns email_verified as the *string* "true"/"false".
private final case class GoogleTokenInfo(aud: String, sub: String, email: String, email_verified: String)
    derives JsonDecoder

trait OAuthClient {
  def provider: OAuthProvider
  def authorizationUrl(state: String): String
  def exchangeAndVerify(code: String): Task[OAuthIdentity]
}

/** Lookup from provider to its client, or `None` when that provider has no credentials configured. Routes use the
  * `None` to answer 404 rather than offering a button that cannot work.
  */
trait OAuthClients {
  def forProvider(provider: OAuthProvider): Option[OAuthClient]
}

/** The half of the authorization-code flow that is identical for every provider: swap the one-time `code` for a token
  * response over a back-channel POST.
  *
  * The two calls go out over zio-http's own `Client` — the same library the server is built on — rather than a second
  * HTTP stack. That is not just tidiness: `java.net.http`'s synchronous `send` had to run inside `attemptBlocking`,
  * which cannot be interrupted, so a provider endpoint that accepted the connection and then went quiet held a
  * blocking-pool thread until the OS gave up on the socket. A `Client` request is an ordinary interruptible effect, so
  * the timeout below actually releases everything it was holding, and a caller that gives up (the browser closing the
  * OAuth tab) cancels the request with it.
  */
abstract class BackChannelOAuthClient(client: Client) extends OAuthClient {

  private val requestTimeout = 10.seconds

  protected def clientId: String
  protected def clientSecret: String
  protected def redirectUri: String
  protected def tokenEndpoint: String

  protected def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  protected def urlOf(raw: String, query: QueryParams = QueryParams.empty): Task[URL] = {
    ZIO
      .fromEither(URL.decode(raw))
      .mapBoth(
        err => new RuntimeException(s"Invalid ${provider.toString} endpoint URL '$raw': ${err.getMessage}"),
        _.addQueryParams(query),
      )
  }

  /** One round trip, bounded in wall-clock time rather than by socket-level timeouts. `batched` reads the whole
    * response body before returning, so nothing is left to consume after the effect completes.
    */
  protected def send(request: Request, what: String): Task[Response] = {
    client.batched(request).timeoutFail(new RuntimeException(s"${provider.toString} $what timed out"))(requestTimeout)
  }

  protected def queryString(params: Map[String, String]): String = {
    params
      .map { case (k, v) =>
        s"${enc(k)}=${enc(v)}"
      }
      .mkString("&")
  }

  /** Reads the `id_token` out of the token endpoint's response. Providers return more than this (access token, refresh
    * token, expiry); none of it is needed once the identity is known, so none of it is decoded or stored.
    */
  protected def exchangeCode(code: String): Task[String] = {
    val form = Form(
      FormField.simpleField("code", code),
      FormField.simpleField("client_id", clientId),
      FormField.simpleField("client_secret", clientSecret),
      FormField.simpleField("redirect_uri", redirectUri),
      FormField.simpleField("grant_type", "authorization_code"),
    )
    for {
      url      <- urlOf(tokenEndpoint)
      // `fromURLEncodedForm` sets `Content-Type: application/x-www-form-urlencoded` itself, which the token
      // endpoint requires and which a hand-built body would have to declare separately.
      response <- send(Request.post(url, Body.fromURLEncodedForm(form)), "token exchange")
      _        <-
        ZIO
          .unless(response.status == Status.Ok)(
            ZIO.fail(
              new RuntimeException(s"${provider.toString} token exchange failed with status ${response.status.code}")
            )
          )
          .unit
      body     <- response.body.asString
      idToken  <-
        ZIO
          .fromEither(body.fromJson[TokenResponse])
          .mapBoth(err => new RuntimeException(s"Malformed ${provider.toString} token response: $err"), _.id_token)
    } yield idToken
  }
}

/** Server-side OAuth2 authorization-code flow. `id_token` is verified via Google's `tokeninfo` endpoint (checks
  * `aud`/signature/`exp` server-side) rather than local JWKS verification — one extra network round trip, but avoids
  * pulling in a JWT/JWKS library for this scope.
  */
final class GoogleOAuthClient(config: GoogleSection, client: Client) extends BackChannelOAuthClient(client) {

  val provider: OAuthProvider = OAuthProvider.Google

  protected val clientId: String      = config.clientId
  protected val clientSecret: String  = config.clientSecret
  protected val redirectUri: String   = config.redirectUri
  protected val tokenEndpoint: String = "https://oauth2.googleapis.com/token"

  def authorizationUrl(state: String): String = {
    val params = Map(
      "client_id"     -> config.clientId,
      "redirect_uri"  -> config.redirectUri,
      "response_type" -> "code",
      "scope"         -> "openid email",
      "state"         -> state,
    )
    s"https://accounts.google.com/o/oauth2/v2/auth?${queryString(params)}"
  }

  def exchangeAndVerify(code: String): Task[OAuthIdentity] = {
    for {
      idToken  <- exchangeCode(code)
      identity <- verifyIdToken(idToken)
    } yield identity
  }

  private def verifyIdToken(idToken: String): Task[OAuthIdentity] = {
    for {
      url      <- urlOf("https://oauth2.googleapis.com/tokeninfo", QueryParams("id_token" -> idToken))
      response <- send(Request.get(url), "id_token verification")
      _        <-
        ZIO
          .unless(response.status == Status.Ok)(ZIO.fail(new RuntimeException("Google id_token verification failed")))
          .unit
      body     <- response.body.asString
      info     <- ZIO
                    .fromEither(body.fromJson[GoogleTokenInfo])
                    .mapError(err => new RuntimeException(s"Malformed Google tokeninfo response: $err"))
      _        <-
        ZIO
          .unless(info.aud == config.clientId)(ZIO.fail(new RuntimeException("Google id_token audience mismatch")))
          .unit
    } yield OAuthIdentity(provider, info.sub, info.email, info.email_verified == "true")
  }
}

/** Server-side OAuth2 authorization-code flow against the Microsoft identity platform (v2.0 endpoints).
  *
  * Microsoft publishes no `tokeninfo` equivalent, so Google's trick does not transfer. Rather than take on a JWT/JWKS
  * dependency to check the id_token signature, this decodes the payload and validates `iss`/`aud`/`exp` as ordinary
  * fields — which OIDC Core 1.0 §3.1.3.7 item 6 explicitly permits: when the ID token is received via *direct*
  * communication between client and token endpoint, TLS server validation may stand in for checking the signature.
  *
  * '''That is the whole basis for skipping the signature check, so it holds only as long as the token arrives the way
  * it does here''' — over a back-channel TLS POST straight to `login.microsoftonline.com`, never routed through the
  * browser. Do not reuse [[decodeIdTokenClaims]] on a token that reached the server any other way (an implicit-flow
  * fragment, a client-supplied header); that token needs a real signature check.
  */
final class MicrosoftOAuthClient(config: MicrosoftSection, client: Client) extends BackChannelOAuthClient(client) {

  val provider: OAuthProvider = OAuthProvider.Microsoft

  protected val clientId: String      = config.clientId
  protected val clientSecret: String  = config.clientSecret
  protected val redirectUri: String   = config.redirectUri
  protected val tokenEndpoint: String = s"https://login.microsoftonline.com/${config.tenant}/oauth2/v2.0/token"

  def authorizationUrl(state: String): String = {
    val params = Map(
      "client_id"     -> config.clientId,
      "redirect_uri"  -> config.redirectUri,
      "response_type" -> "code",
      "scope"         -> "openid email profile",
      "response_mode" -> "query",
      "state"         -> state,
    )
    s"https://login.microsoftonline.com/${config.tenant}/oauth2/v2.0/authorize?${queryString(params)}"
  }

  def exchangeAndVerify(code: String): Task[OAuthIdentity] = {
    for {
      idToken  <- exchangeCode(code)
      now      <- Clock.instant
      identity <- ZIO.fromEither(MicrosoftOAuthClient.identityFrom(idToken, config, now.getEpochSecond))
    } yield identity
  }
}

object MicrosoftOAuthClient {

  /** `iss` carries the *tenant that issued the token*, which is a GUID even when the request went to `/common`, so a
    * literal comparison only works for a single-tenant configuration. Both forms are accepted against the configured
    * tenant, and the host is pinned in every case.
    */
  private def issuerIsAcceptable(issuer: String, tenant: String): Boolean = {
    val expected    = s"https://login.microsoftonline.com/$tenant/v2.0"
    val multiTenant = tenant == "common" || tenant == "organizations" || tenant == "consumers"
    if (multiTenant)
      issuer.startsWith("https://login.microsoftonline.com/") && issuer.endsWith("/v2.0")
    else
      issuer == expected
  }

  /** Splits a JWS compact serialization and base64url-decodes the claims. Deliberately ignores the header and the
    * signature — see the class scaladoc for why that is sound *here and only here*.
    */
  private[service] def decodeIdTokenClaims(idToken: String): Either[String, Json] = {
    val parts = idToken.split('.')
    if (parts.length != 3)
      Left("id_token is not a three-part JWS")
    else {
      for {
        bytes <- scala.util
                   .Try(Base64.getUrlDecoder.decode(parts(1)))
                   .toEither
                   .left
                   .map(err => s"id_token payload is not valid base64url: ${err.getMessage}")
        json  <- new String(bytes, StandardCharsets.UTF_8).fromJson[Json].left.map(err => s"id_token payload: $err")
      } yield json
    }
  }

  private def stringField(claims: Json, name: String): Option[String] = {
    claims.asObject.flatMap(_.get(name)).flatMap(_.asString)
  }

  private def longField(claims: Json, name: String): Option[Long] = {
    claims.asObject.flatMap(_.get(name)).flatMap(_.asNumber).map(_.value.longValue)
  }

  /** Validates the claims a back-channel id_token still has to carry on its own, then projects them onto
    * [[OAuthIdentity]]. `nowEpochSeconds` is passed in rather than read from the clock so the failure cases are
    * testable without waiting.
    */
  private[service] def identityFrom(
    idToken: String,
    config: MicrosoftSection,
    nowEpochSeconds: Long,
  ): Either[Throwable, OAuthIdentity] = {
    val result = {
      for {
        claims   <- decodeIdTokenClaims(idToken)
        issuer   <- stringField(claims, "iss").toRight("id_token has no iss claim")
        _        <- Either.cond(issuerIsAcceptable(issuer, config.tenant), (), s"id_token issuer '$issuer' is not accepted")
        audience <- stringField(claims, "aud").toRight("id_token has no aud claim")
        _        <- Either.cond(audience == config.clientId, (), "id_token audience mismatch")
        expiry   <- longField(claims, "exp").toRight("id_token has no exp claim")
        _        <- Either.cond(expiry > nowEpochSeconds, (), "id_token has expired")
        subject  <- stringField(claims, "sub").toRight("id_token has no sub claim")
        // Personal accounts report `email`; work/school accounts often only carry `preferred_username`.
        // Neither is trusted for account matching — it is stored for display — but an account with no
        // address at all has nothing to show, so treat that as a failure rather than inventing one.
        email    <- stringField(claims, "email")
                      .orElse(stringField(claims, "preferred_username"))
                      .toRight("id_token carries neither an email nor a preferred_username claim")
      } yield {
        // Microsoft asserts no email_verified claim. Work/school addresses are tenant-controlled and personal
        // ones are verified at account creation, but since nothing here matches on email, the flag is only
        // ever displayed — reporting it as unverified is the honest reading of "the provider did not say".
        OAuthIdentity(OAuthProvider.Microsoft, subject, email, emailVerified = false)
      }
    }
    result.left.map(message => new RuntimeException(s"Microsoft $message"))
  }
}

final case class OAuthClientsLive(config: AppConfig, client: Client) extends OAuthClients {

  private val clients: Map[OAuthProvider, OAuthClient] = {
    OAuthProvider.all
      .filter(config.isOAuthConfigured)
      .map { provider =>
        val impl = {
          provider match {
            case OAuthProvider.Google    =>
              new GoogleOAuthClient(config.oauth.google, client): OAuthClient
            case OAuthProvider.Microsoft =>
              new MicrosoftOAuthClient(config.oauth.microsoft, client): OAuthClient
          }
        }
        provider -> impl
      }
      .toMap
  }

  def forProvider(provider: OAuthProvider): Option[OAuthClient] = clients.get(provider)
}

object OAuthClients {
  def forProvider(provider: OAuthProvider): URIO[OAuthClients, Option[OAuthClient]] =
    ZIO.serviceWith[OAuthClients](_.forProvider(provider))

  val live: URLayer[AppConfig & Client, OAuthClients] = ZLayer.fromFunction(OAuthClientsLive.apply)
}
