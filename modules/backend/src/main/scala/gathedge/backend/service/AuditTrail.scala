package gathedge.backend.service

import gathedge.backend.db.{AuditLogRepository, AuditLogRow, UserRepository}
import gathedge.backend.security.SecurityLog
import zio.*

import java.util.concurrent.TimeUnit

/** The one place an administrator action is recorded.
  *
  * It writes twice — a line to the `security` slf4j logger, which is what an operator watches and retains, and a row in
  * `audit_log`, which is what the admin screens read. A single method for both is the point: the log and the screen
  * would otherwise describe different sets of events, and the one that goes missing is always the one nobody is
  * currently looking at.
  */
trait AuditTrail {
  def record(
    actor: AdminActor,
    action: String,
    targetType: Option[String],
    targetId: Option[String],
    message: String,
  ): UIO[Unit]

  /** The common case: an action against one account. */
  def recordUser(actor: AdminActor, action: String, targetId: Long, message: String): UIO[Unit] = {
    record(actor, action, Some("user"), Some(targetId.toString), message)
  }

  /** An action against the deployment rather than an account. */
  def recordSystem(actor: AdminActor, action: String, message: String): UIO[Unit] = {
    record(actor, action, Some("system"), None, message)
  }
}

object AuditTrail {
  val live: URLayer[UserRepository & AuditLogRepository, AuditTrail] = ZLayer.fromFunction(AuditTrailLive.apply)
}

final case class AuditTrailLive(userRepo: UserRepository, auditRepo: AuditLogRepository) extends AuditTrail {

  /** The actor's address is looked up here rather than carried by every caller, and *stored* on the row rather than
    * joined at read time — the entry has to still name who acted after that administrator's own account is deleted,
    * which is precisely the case an audit trail exists for.
    *
    * A failed write is logged and swallowed. The log line has already been emitted, so nothing is unrecorded; turning a
    * completed administrative action into a 500 would tell the administrator it had not happened, which would be worse
    * than losing the queryable copy of it.
    */
  def record(
    actor: AdminActor,
    action: String,
    targetType: Option[String],
    targetId: Option[String],
    message: String,
  ): UIO[Unit] = {
    SecurityLog.info(s"[admin id=${actor.userId}] $message") *> {
      (
        for {
          now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
          actorEmail <- userRepo.findById(actor.userId).map(_.flatMap(_.email))
          _          <- auditRepo.insert(
                          AuditLogRow(
                            id = 0L,
                            occurredAt = now,
                            // `AdminActor.system` is id 0, which matches no row: an entry with no actor is the
                            // application acting on its own behalf.
                            actorUserId = Option.when(actor.userId != 0L)(actor.userId),
                            actorEmail = actorEmail,
                            action = action,
                            targetType = targetType,
                            targetId = targetId,
                            detail = Some(message),
                            ip = actor.clientIp,
                          )
                        )
        } yield ()
      ).catchAllCause(cause => ZIO.logErrorCause(s"Could not record audit entry '$action'", cause))
    }
  }
}
