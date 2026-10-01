package gathedge.backend.service

import gathedge.backend.db.UserRepository
import zio.*

/** Whether a caller holds `users.is_admin` — the same flag `RouteSupport.adminOnly` runs `/api/admin` on, read here for
  * the services outside it.
  *
  * A global administrator passes every ownership and membership gate in `WordService`, `GameService` and
  * `GroupService`: they may rename and delete any wordlist, edit any wordlist's content, rename any game, read any
  * game's plays, and run any study group. Two things stay outside that rule:
  *
  *   - '''A running play is nobody else's to drive.''' `GameService.requireOwnedPlay` guards `nextPrompt`,
  *     `submitAnswer` and `getResults` — answering into somebody's live session would write results they did not
  *     produce, and an administrator already reads finished plays through `/api/admin`.
  *   - '''`ownedByMe` keeps telling the truth.''' The mark says who owns the row, not who may write to it, and the
  *     listings, the collect picker and the quota that charges an owner all read it that way. `editableByMe` is the
  *     permission half, and that one an administrator gets on every row.
  *
  * A global administrator is also never rate limited on the budgets keyed on an account (`RateLimitKey.wordUpload`,
  * `.groupJoin`, `.shareRedeem`) — see [[unlessAdmin]]. The budgets a caller meets before signing in (login, signup,
  * verification, password reset, guest, claim) stay as they are: nobody is known to be an administrator there yet, and
  * an administrator's address is the last one to hand an unmetered password guesser.
  *
  * The check is a primary-key read, so each gate makes it '''only after''' the ordinary owner test has already said no.
  * The common request pays nothing for it.
  */
object GlobalAdmin {

  def is(userRepo: UserRepository, userId: Long): UIO[Boolean] =
    userRepo.findById(userId).orDie.map(_.exists(_.isAdmin))

  /** Runs `meter` — a rate-limit check and its count — for anyone but a global administrator, who skips it. */
  def unlessAdmin[E](userRepo: UserRepository, userId: Long)(meter: IO[E, Unit]): IO[E, Unit] =
    is(userRepo, userId).flatMap(admin => if (admin) ZIO.unit else meter)

  /** The [[is]] a listing needs, where the reader may be a visitor with no session at all — who is never one. */
  def isReader(userRepo: UserRepository, reader: Option[Long]): UIO[Boolean] =
    ZIO.foreach(reader)(is(userRepo, _)).map(_.getOrElse(false))
}
