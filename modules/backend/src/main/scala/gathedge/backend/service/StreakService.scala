package gathedge.backend.service

import gathedge.backend.db.{StreakRepository, UserStreakRow}
import gathedge.shared.domain.{Streak, StreakState}
import gathedge.shared.dto.StreakResponse
import zio.*

import java.util.concurrent.TimeUnit

/** Daily play streaks. Days are UTC days: an account has no time zone on file. */
trait StreakService {

  /** Notes that the account played today. Extends, continues or restarts the streak by [[Streak.afterPlay]]. */
  def recordPlay(userId: Long): Task[Unit]

  /** The streak as it stands now. An account that never played gets zeros and `Inactive`. */
  def current(userId: Long): Task[StreakResponse]
}

object StreakService {
  def recordPlay(userId: Long): RIO[StreakService, Unit] =
    ZIO.serviceWithZIO[StreakService](_.recordPlay(userId))

  def current(userId: Long): RIO[StreakService, StreakResponse] =
    ZIO.serviceWithZIO[StreakService](_.current(userId))

  val live: URLayer[StreakRepository, StreakService] =
    ZLayer.fromFunction((repo: StreakRepository) => StreakServiceLive(repo): StreakService)
}

final case class StreakServiceLive(repo: StreakRepository) extends StreakService {

  private val today: UIO[Long] = Clock.currentTime(TimeUnit.MILLISECONDS).map(Streak.dayOf)

  def recordPlay(userId: Long): Task[Unit] = {
    for {
      day      <- today
      now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      existing <- repo.find(userId)
      _        <- ZIO.unless(existing.exists(_.lastPlayDay == day)) {
                    val length = Streak.afterPlay(existing.map(_.currentStreak).getOrElse(0), existing.map(_.lastPlayDay), day)
                    repo.upsert(
                      UserStreakRow(
                        userId = userId,
                        currentStreak = length,
                        longestStreak = Math.max(length, existing.map(_.longestStreak).getOrElse(0)),
                        totalDays = existing.map(_.totalDays).getOrElse(0) + 1,
                        lastPlayDay = day,
                        updatedAt = now,
                      )
                    )
                  }
    } yield ()
  }

  def current(userId: Long): Task[StreakResponse] = {
    for {
      day <- today
      row <- repo.find(userId)
    } yield {
      val state = Streak.stateOn(row.map(_.lastPlayDay), day)
      StreakResponse(
        current = if (state == StreakState.Inactive) 0 else row.map(_.currentStreak).getOrElse(0),
        longest = row.map(_.longestStreak).getOrElse(0),
        totalDays = row.map(_.totalDays).getOrElse(0),
        state = state,
      )
    }
  }
}
