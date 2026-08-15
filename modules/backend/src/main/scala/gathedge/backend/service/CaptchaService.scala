package gathedge.backend.service

import gathedge.backend.config.AppConfig
import zio.*
import zio.http.{Body, Client, Form, FormField, Request, Status, URL}
import zio.json.*

/** The bot check the captcha-gated auth endpoints run before doing their real work.
  *
  * Cloudflare Turnstile is the provider, and the interaction is the same shape as the OAuth clients': the browser hands
  * us a one-time token it got from Cloudflare, and we send it back over a server-to-server POST to `siteverify`, which
  * vouches for it. The token is never trusted on its own — it is a bearer assertion, so the proof is the provider's
  * answer, not the token's presence.
  */
trait CaptchaService {
  def verify(token: String, remoteIp: Option[String]): IO[CaptchaFailure, Unit]
}

/** What `verify` can fail with, kept out of [[AuthFailure]] so the auth endpoints' failure unions stay as narrow as
  * their descriptions. [[CaptchaFailure.VerificationFailed]] is an answer to a well-formed request — the token the
  * browser supplied did not check out — while [[CaptchaFailure.ProviderError]] is the provider being unreachable, a
  * degraded dependency rather than a caller's mistake.
  */
enum CaptchaFailure {
  case VerificationFailed
  case ProviderError
}

/** The `siteverify` response. Only `success` matters; `error-codes` is diagnostic and goes nowhere a caller can read.
  */
private final case class TurnstileVerifyResponse(success: Boolean) derives JsonDecoder

object CaptchaService {

  /** The `remoteip` field is optional at Cloudflare but worth sending: it ties the token to the address that presented
    * it, closing the gap where a stolen token is replayed from elsewhere.
    */
  val siteVerifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify"

  val live: URLayer[AppConfig & Client, CaptchaService] = ZLayer.fromFunction(TurnstileCaptchaService.apply)
}

final class TurnstileCaptchaService(config: AppConfig, client: Client) extends CaptchaService {

  private val requestTimeout = 10.seconds

  def verify(token: String, remoteIp: Option[String]): IO[CaptchaFailure, Unit] = {
    val fields = {
      List(
        FormField.simpleField("secret", config.captcha.secret),
        FormField.simpleField("response", token),
      ) ++ remoteIp.map(ip => FormField.simpleField("remoteip", ip)).toList
    }
    val form   = Form(fields*)
    for {
      url      <- ZIO
                    .fromEither(URL.decode(CaptchaService.siteVerifyUrl))
                    .mapError(_ => CaptchaFailure.ProviderError)
      response <- client
                    .batched(Request.post(url, Body.fromURLEncodedForm(form)))
                    .timeoutFail(new RuntimeException("Captcha verification timed out"))(requestTimeout)
                    .mapError(_ => CaptchaFailure.ProviderError)
      body     <- response.body.asString.mapError(_ => CaptchaFailure.ProviderError)
      result   <- ZIO
                    .fromEither(body.fromJson[TurnstileVerifyResponse])
                    .mapError(_ => CaptchaFailure.ProviderError)
      _        <- ZIO.unless(response.status == Status.Ok && result.success)(ZIO.fail(CaptchaFailure.VerificationFailed))
    } yield ()
  }
}
