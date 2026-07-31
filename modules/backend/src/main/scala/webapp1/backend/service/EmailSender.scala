package webapp1.backend.service

import org.slf4j.LoggerFactory
import zio.*

trait EmailSender {
  def send(to: String, subject: String, body: String): Task[Unit]
}

/** Dev-only implementation: logs instead of sending. A real SMTP implementation
  * can be added later behind the same [[EmailSender]] interface without touching
  * callers (group invites in M3 are the first real user of this).
  */
final class LoggingEmailSender extends EmailSender {
  private val logger = LoggerFactory.getLogger("webapp1.email")

  def send(to: String, subject: String, body: String): Task[Unit] = {
    ZIO.attempt(logger.info(s"""[dev email] to=$to subject="$subject"\n$body"""))
  }
}

object EmailSender {
  val live: ULayer[EmailSender] = ZLayer.succeed(new LoggingEmailSender: EmailSender)
}
