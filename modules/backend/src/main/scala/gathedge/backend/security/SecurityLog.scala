package gathedge.backend.security

import zio.*
import zio.logging.loggerName

/** Failed logins, rate-limit trips, admin-route denials and admin user-management actions, kept apart from general app
  * logging by a dedicated `security` logger (see logback.xml).
  *
  * These go through ZIO's logging rather than a direct slf4j call so they carry fiber id, spans and log annotations
  * like every other log line. The SLF4J backend picks the target logger from the `logger_name` annotation that
  * [[zio.logging.loggerName]] sets, falling back to the trace.
  */
object SecurityLog {

  val name = "security"

  def warn(message: String): UIO[Unit] = ZIO.logWarning(message) @@ loggerName(name)

  def info(message: String): UIO[Unit] = ZIO.logInfo(message) @@ loggerName(name)
}
