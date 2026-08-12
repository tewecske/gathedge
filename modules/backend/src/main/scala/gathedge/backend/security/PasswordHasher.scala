package gathedge.backend.security

import org.mindrot.jbcrypt.BCrypt
import zio.*

trait PasswordHasher {
  def hash(password: String): Task[String]
  def verify(password: String, hash: String): Task[Boolean]
}

final case class BCryptPasswordHasher(cost: Int) extends PasswordHasher {
  def hash(password: String): Task[String] = {
    ZIO.attemptBlocking(BCrypt.hashpw(password, BCrypt.gensalt(cost)))
  }

  def verify(password: String, hash: String): Task[Boolean] = {
    ZIO.attemptBlocking(BCrypt.checkpw(password, hash)).catchAll(_ => ZIO.succeed(false))
  }
}

object PasswordHasher {
  // Cost 12 balances hashing latency against brute-force resistance for an
  // interactive login endpoint (~250ms/hash on typical server hardware).
  val live: ULayer[PasswordHasher] = ZLayer.succeed(BCryptPasswordHasher(cost = 12): PasswordHasher)
}
