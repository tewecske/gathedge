package webapp1.backend

import webapp1.backend.config.AppConfig
import webapp1.backend.service.EmailSender
import zio.*

/** One outgoing message, as [[RecordingEmailSender]] saw it. */
final case class SentEmail(to: String, subject: String, body: String)

/** Read side of [[RecordingEmailSender]], so a test can assert on what was sent — and, for verification, pull the token
  * out of the link the way a person would out of their inbox.
  */
trait SentEmails {
  def all: UIO[Vector[SentEmail]]

  /** The token out of the most recent `…/verify-email/<token>` link, if there is one. */
  def lastVerificationToken: UIO[Option[String]] = {
    all.map(_.reverseIterator.flatMap(email => SentEmails.tokenIn(email.body)).nextOption())
  }
}

object SentEmails {
  private val linkPattern = """/verify-email/([A-Za-z0-9_-]+)""".r

  private def tokenIn(body: String): Option[String] = {
    linkPattern.findFirstMatchIn(body).flatMap(m => Option(m.group(1)))
  }
}

/** An [[EmailSender]] that keeps what it was handed instead of sending or logging it.
  *
  * The one layer provides both halves, so the sender the service writes through and the recording a test reads back are
  * the same instance.
  */
object RecordingEmailSender {
  val live: ULayer[EmailSender & SentEmails] = {
    ZLayer.fromZIOEnvironment {
      Ref
        .make(Vector.empty[SentEmail])
        .map { ref =>
          val sender    = {
            new EmailSender {
              def send(to: String, subject: String, body: String): Task[Unit] = {
                ref.update(_ :+ SentEmail(to, subject, body))
              }
            }
          }
          val recording = {
            new SentEmails {
              def all: UIO[Vector[SentEmail]] = ref.get
            }
          }
          ZEnvironment[EmailSender, SentEmails](sender, recording)
        }
    }
  }
}

/** Layer plumbing every auth spec needs now that `AuthService.live` depends on a mailer and on config.
  *
  * `AppConfig.live` reads `application.conf`, where `app.require-email-verification` is false — which is what keeps the
  * existing specs (and the e2e golden path) describing an unverified account that can still sign in. A spec that wants
  * the gate on asks for [[configWith]].
  */
object TestAuthLayers {

  /** `AppConfig` with `require-email-verification` overridden, for the specs that exercise the login gate. */
  def configWith(requireEmailVerification: Boolean): ZLayer[Any, Config.Error, AppConfig] = {
    AppConfig.live
      .project(config => config.copy(app = config.app.copy(requireEmailVerification = requireEmailVerification)))
  }

  /** The non-repository half of `AuthService.live`'s requirements: a config and the logging mailer it selects. */
  val emailAndConfig: ZLayer[Any, Throwable, EmailSender & AppConfig] = {
    AppConfig.live ++ (AppConfig.live >>> EmailSender.live)
  }
}
