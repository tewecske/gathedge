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
  * The check is a primary-key read, so each gate makes it '''only after''' the ordinary owner test has already said no.
  * The common request pays nothing for it.
  */
object GlobalAdmin {

  def is(userRepo: UserRepository, userId: Long): UIO[Boolean] =
    userRepo.findById(userId).orDie.map(_.exists(_.isAdmin))

  /** The [[is]] a listing needs, where the reader may be a visitor with no session at all — who is never one. */
  def isReader(userRepo: UserRepository, reader: Option[Long]): UIO[Boolean] =
    ZIO.foreach(reader)(is(userRepo, _)).map(_.getOrElse(false))
}
