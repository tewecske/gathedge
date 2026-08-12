package gathedge.backend.service

import jakarta.mail.internet.{InternetAddress, MimeMessage}
import jakarta.mail.{Authenticator, Message, PasswordAuthentication, Session, Transport}
import org.slf4j.LoggerFactory
import gathedge.backend.config.AppConfig
import zio.*

import java.util.Properties

trait EmailSender {
  def send(to: String, subject: String, body: String): Task[Unit]
}

/** Used whenever no SMTP host is configured: logs instead of sending. That is the default in development, and it is how
  * a verification link is read locally — off the backend's stdout.
  */
final class LoggingEmailSender extends EmailSender {
  private val logger = LoggerFactory.getLogger("gathedge.email")

  def send(to: String, subject: String, body: String): Task[Unit] = {
    ZIO.attempt(logger.info(s"""[dev email] to=$to subject="$subject"\n$body"""))
  }
}

/** Plain-text mail over SMTP via Jakarta Mail.
  *
  * `Transport.send` is a blocking socket call, so it runs on the blocking pool. Unlike the OAuth clients — which had to
  * move off `java.net.http` because a synchronous send inside `attemptBlocking` is uninterruptible — this one is only
  * ever called from a fiber that is already free to take its time, and every caller either forks it or tolerates its
  * failure.
  */
final case class SmtpEmailSender(config: AppConfig) extends EmailSender {
  private val smtp = config.mail.smtp

  private val session: Session = {
    val props = new Properties()
    props.put("mail.smtp.host", smtp.host)
    props.put("mail.smtp.port", smtp.port.toString)
    props.put("mail.smtp.starttls.enable", smtp.startTls.toString)
    if (smtp.username.nonEmpty) {
      props.put("mail.smtp.auth", "true")
      Session.getInstance(
        props,
        new Authenticator {
          override protected def getPasswordAuthentication: PasswordAuthentication = {
            new PasswordAuthentication(smtp.username, smtp.password)
          }
        },
      )
    } else {
      // An unauthenticated relay — a local MTA, or a test server such as MailHog.
      Session.getInstance(props)
    }
  }

  def send(to: String, subject: String, body: String): Task[Unit] = {
    ZIO.attemptBlocking {
      val message = new MimeMessage(session)
      message.setFrom(new InternetAddress(config.mail.from))
      message.setRecipient(Message.RecipientType.TO, new InternetAddress(to))
      message.setSubject(subject, "UTF-8")
      message.setText(body, "UTF-8")
      Transport.send(message)
    }
  }
}

object EmailSender {

  /** An empty `mail.smtp.host` switches sending off the same way an empty client id switches an OAuth provider off, so
    * the stack boots with no mail server configured at all.
    */
  val live: RLayer[AppConfig, EmailSender] = ZLayer {
    for {
      config <- ZIO.service[AppConfig]
      sender <-
        if (config.isMailConfigured) {
          ZIO
            .logInfo(s"Sending mail via SMTP at ${config.mail.smtp.host}:${config.mail.smtp.port}")
            .as(SmtpEmailSender(config): EmailSender)
        } else {
          ZIO
            .logInfo("No SMTP host configured; outgoing mail will be logged instead of sent")
            .as(new LoggingEmailSender: EmailSender)
        }
    } yield sender
  }
}
