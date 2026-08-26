package gathedge.backend.service

import gathedge.backend.db.{ProgressShareRepository, ProgressShareRow, UserRepository, UserRow}
import gathedge.backend.security.{SecurityLog, Tokens}
import gathedge.shared.dto.{SharedViewer, SharedWithMe}
import zio.*

import java.util.concurrent.TimeUnit

/** What can go wrong redeeming a share code, or reading a share it granted. */
enum ProgressShareFailure {

  /** No such code, or one that has been revoked — one case for both, so the code space cannot be probed, the same
    * reasoning `GuestClaimFailure.InvalidCode` follows.
    */
  case CodeInvalid

  /** A code always belongs to somebody, and it is never worth sharing with oneself. */
  case CannotShareWithSelf

  /** This (sharer, viewer) pair already has a grant — redeeming the same code twice is a no-op error, not a second
    * grant.
    */
  case AlreadyShared

  /** [[ProgressShareService.requireShareAccess]]: the caller holds no grant from the account it asked to read. */
  case NotShared

  /** [[RateLimitKey.shareRedeem]] tripped for this caller. */
  case RateLimited
}

/** Progress sharing: letting one account's game history be read by another, on either side's own say-so — a "sharer"
  * whose plays become visible and a "viewer" who may read them, never a role like "parent" or "teacher".
  * `GameService.trackedPlaysOf` is what actually answers the plays, once this has decided the read is allowed.
  */
trait ProgressShareService {

  /** The caller's own share code, minted on the first call and the same one answered again on every call after — the
    * account's share panel can ask for it any time and get the same answer back rather than minting a fresh one each
    * time, the same shape `AuthService.issueClaimCode` follows for a guest's transfer code.
    */
  def issueCode(userId: Long): UIO[String]

  /** Grants `viewerUserId` a share from `code`'s owner. */
  def redeem(viewerUserId: Long, code: String): IO[ProgressShareFailure, Unit]

  /** Every account currently sharing its game history with `viewerUserId`. */
  def sharersOf(viewerUserId: Long): UIO[List[SharedWithMe]]

  /** Every account `sharerUserId` currently shares its game history with. */
  def viewersOf(sharerUserId: Long): UIO[List[SharedViewer]]

  /** Fails [[ProgressShareFailure.NotShared]] unless `sharerUserId` has granted `viewerUserId` a share — the
    * authorization check `ProgressShareRoutes` runs before ever calling `GameService.trackedPlaysOf`.
    */
  def requireShareAccess(viewerUserId: Long, sharerUserId: Long): IO[ProgressShareFailure, Unit]

  /** Revokes one viewer's access to `sharerUserId`'s game history. Idempotent: revoking a viewer with no share is not
    * an error.
    */
  def revoke(sharerUserId: Long, viewerUserId: Long): UIO[Unit]
}

object ProgressShareService {

  def issueCode(userId: Long): URIO[ProgressShareService, String] =
    ZIO.serviceWithZIO[ProgressShareService](_.issueCode(userId))

  def redeem(viewerUserId: Long, code: String): ZIO[ProgressShareService, ProgressShareFailure, Unit] =
    ZIO.serviceWithZIO[ProgressShareService](_.redeem(viewerUserId, code))

  def sharersOf(viewerUserId: Long): URIO[ProgressShareService, List[SharedWithMe]] =
    ZIO.serviceWithZIO[ProgressShareService](_.sharersOf(viewerUserId))

  def viewersOf(sharerUserId: Long): URIO[ProgressShareService, List[SharedViewer]] =
    ZIO.serviceWithZIO[ProgressShareService](_.viewersOf(sharerUserId))

  def requireShareAccess(
    viewerUserId: Long,
    sharerUserId: Long,
  ): ZIO[ProgressShareService, ProgressShareFailure, Unit] =
    ZIO.serviceWithZIO[ProgressShareService](_.requireShareAccess(viewerUserId, sharerUserId))

  def revoke(sharerUserId: Long, viewerUserId: Long): URIO[ProgressShareService, Unit] =
    ZIO.serviceWithZIO[ProgressShareService](_.revoke(sharerUserId, viewerUserId))

  val live: URLayer[ProgressShareRepository & UserRepository & RateLimiter, ProgressShareService] = {
    ZLayer.fromFunction(ProgressShareServiceLive.apply)
  }
}

final case class ProgressShareServiceLive(
  repo: ProgressShareRepository,
  userRepo: UserRepository,
  rateLimiter: RateLimiter,
) extends ProgressShareService {

  def issueCode(userId: Long): UIO[String] = {
    for {
      existing <- repo.findActiveCodeForUser(userId).orDie
      code     <- existing match {
                    case Some(row) =>
                      ZIO.succeed(row.code)
                    case None      =>
                      for {
                        code <- Tokens.claimCode()
                        now  <- Clock.currentTime(TimeUnit.MILLISECONDS)
                        _    <- repo.insertCode(userId, code, now).orDie
                        // The code itself never appears in a log line: it is the credential, like a session id.
                        _    <- SecurityLog.info(s"Issued a progress-share code for account $userId")
                      } yield code
                  }
    } yield code
  }

  def redeem(viewerUserId: Long, code: String): IO[ProgressShareFailure, Unit] = {
    val normalized = Tokens.normalizeClaimCode(code)
    val key        = RateLimitKey.shareRedeem(viewerUserId)
    for {
      blocked  <- rateLimiter.isBlocked(key)
      _        <- ZIO.when(blocked)(ZIO.fail(ProgressShareFailure.RateLimited))
      _        <- rateLimiter.recordFailure(key)
      found    <- repo.findActiveCode(normalized).orDie
      claimed  <- ZIO.fromOption(found).orElseFail(ProgressShareFailure.CodeInvalid)
      // A code whose account has since been deleted answers the same "no such code" as one that never existed.
      sharer   <- userRepo.findById(claimed.userId).orDie.someOrFail(ProgressShareFailure.CodeInvalid)
      _        <- ZIO.when(sharer.id == viewerUserId)(ZIO.fail(ProgressShareFailure.CannotShareWithSelf))
      existing <- repo.findShare(sharer.id, viewerUserId).orDie
      _        <- ZIO.when(existing.isDefined)(ZIO.fail(ProgressShareFailure.AlreadyShared))
      now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _        <- repo.insertShare(sharer.id, viewerUserId, now).orDie
      _        <- repo.markCodeUsed(claimed.id, now).orDie
      _        <- rateLimiter.clear(key)
      _        <- SecurityLog.info(s"Progress share redeemed: sharer=${sharer.id} viewer=$viewerUserId")
    } yield ()
  }

  private def labelOf(row: UserRow): (Option[String], Boolean) = (row.email, row.isGuest)

  def sharersOf(viewerUserId: Long): UIO[List[SharedWithMe]] = {
    for {
      shares    <- repo.listSharersFor(viewerUserId).orDie
      usersById <- userRepo.findByIds(shares.map(_.sharerUserId).distinct).orDie.map(_.map(u => u.id -> u).toMap)
    } yield shares.flatMap { share =>
      usersById.get(share.sharerUserId).map { user =>
        val (email, isGuest) = labelOf(user)
        SharedWithMe(share.sharerUserId, email, isGuest, share.createdAt)
      }
    }
  }

  def viewersOf(sharerUserId: Long): UIO[List[SharedViewer]] = {
    for {
      shares    <- repo.listViewersFor(sharerUserId).orDie
      usersById <- userRepo.findByIds(shares.map(_.viewerUserId).distinct).orDie.map(_.map(u => u.id -> u).toMap)
    } yield shares.flatMap { share =>
      usersById.get(share.viewerUserId).map { user =>
        val (email, isGuest) = labelOf(user)
        SharedViewer(share.viewerUserId, email, isGuest, share.createdAt)
      }
    }
  }

  def requireShareAccess(viewerUserId: Long, sharerUserId: Long): IO[ProgressShareFailure, Unit] = {
    repo.findShare(sharerUserId, viewerUserId).orDie.flatMap {
      case Some(_) =>
        ZIO.unit
      case None    =>
        ZIO.fail(ProgressShareFailure.NotShared)
    }
  }

  def revoke(sharerUserId: Long, viewerUserId: Long): UIO[Unit] = {
    repo.deleteShare(sharerUserId, viewerUserId).orDie.unit
  }
}
