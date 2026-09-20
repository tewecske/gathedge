package gathedge.shared.domain

import zio.json.*

/** What the header shows for a daily play streak. */
enum StreakState derives JsonCodec {

  /** Played today or yesterday: the streak is alive. */
  case Active

  /** One or two whole days missed: the streak is broken, but the next play continues it. */
  case AtRisk

  /** More than two days missed, or never played: the next play starts a new streak. */
  case Inactive
}

/** The rules of a daily streak, over UTC epoch days. One home for them, so the server that writes a streak and the
  * server that reads it agree.
  */
object Streak {

  /** How many whole days a player may miss and still continue the streak. */
  val graceDays = 2

  private val millisPerDay = 86_400_000L

  def dayOf(epochMillis: Long): Long = Math.floorDiv(epochMillis, millisPerDay)

  /** The state of a streak whose last play fell on `lastPlayDay`, seen on `today`. */
  def stateOn(lastPlayDay: Option[Long], today: Long): StreakState = {
    lastPlayDay match {
      case None       =>
        StreakState.Inactive
      case Some(last) =>
        val missed = today - last - 1
        if (missed <= 0) StreakState.Active
        else if (missed <= graceDays) StreakState.AtRisk
        else StreakState.Inactive
    }
  }

  /** The streak length after a play on `today`. A repeat play on the same day changes nothing. */
  def afterPlay(current: Int, lastPlayDay: Option[Long], today: Long): Int = {
    lastPlayDay match {
      case Some(last) if last == today                                    =>
        current
      case Some(_) if stateOn(lastPlayDay, today) != StreakState.Inactive =>
        current + 1
      case _                                                              =>
        1
    }
  }
}
