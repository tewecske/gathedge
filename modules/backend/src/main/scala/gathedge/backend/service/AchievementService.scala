package gathedge.backend.service

import gathedge.backend.db.{
  AchievementRepository,
  GamePlayRow,
  GameRepository,
  StreakRepository,
  UserAchievementRow,
  UserStreakRow,
}
import gathedge.shared.domain.{Achievements, GameMode, Streak}
import gathedge.shared.dto.{AchievementProgress, AchievementUnlock, AchievementsResponse}
import zio.*

import java.util.concurrent.TimeUnit

/** Achievements: the counts they measure, and the tiers an account unlocked.
  *
  * Each evaluation computes every count again from `game_plays` and `user_streaks`, and inserts each tier the counts
  * reach that has no row yet. So the first evaluation of an account is also its backfill: an existing player gets what
  * they earned, with no separate migration step. A tier once written stays, even when a deleted game takes some plays
  * with it.
  */
trait AchievementService {

  /** Brings the account's tiers up to date with its counts, and answers the tiers unlocked just now, in catalog order.
    * Called when a play finishes, after the streak is written.
    */
  def evaluate(userId: Long): Task[List[AchievementUnlock]]

  /** Every achievement of the catalog, started or not, with its tier, count and next threshold. Evaluates first, so an
    * account that has not finished a play since achievements arrived still sees what it earned.
    */
  def overview(userId: Long): Task[AchievementsResponse]
}

object AchievementService {
  def evaluate(userId: Long): RIO[AchievementService, List[AchievementUnlock]] =
    ZIO.serviceWithZIO[AchievementService](_.evaluate(userId))

  def overview(userId: Long): RIO[AchievementService, AchievementsResponse] =
    ZIO.serviceWithZIO[AchievementService](_.overview(userId))

  val live: URLayer[AchievementRepository & GameRepository & StreakRepository, AchievementService] =
    ZLayer.fromFunction(AchievementServiceLive.apply)

  /** The count each achievement measures, by code, from the account's finished plays and its streak row.
    *
    *   - A play counts only when it qualifies (see [[Achievements.isQualifying]]).
    *   - A perfect play scored every point of a non-zero maximum.
    *   - A language pair is unordered, so English to German and German to English are one pair.
    *   - A comeback is a day with a qualifying play whose previous day with any finished play lies one to
    *     [[Streak.graceDays]] missed days back: the play continued the streak in its grace period.
    */
  def counts(plays: List[GamePlayRow], streak: Option[UserStreakRow]): Map[String, Int] = {
    val finished   = plays.filter(_.finishedAt.isDefined)
    val qualifying = finished.filter(play => Achievements.isQualifying(finished = true, play.wordCount))

    val perMode = GameMode.all.map { mode =>
      Achievements.gamesPlayedIn(mode).code -> qualifying.count(play => GameMode.fromString(play.mode).contains(mode))
    }

    val pairs = qualifying.groupBy(play => Set(play.sourceLanguage, play.targetLanguage))

    val playDays       = finished.flatMap(_.finishedAt).map(Streak.dayOf).distinct.sorted
    val qualifyingDays = qualifying.flatMap(_.finishedAt).map(Streak.dayOf).toSet
    val comebacks      = playDays.zip(playDays.drop(1)).count { case (previous, day) =>
      val missed = day - previous - 1
      qualifyingDays.contains(day) && missed >= 1 && missed <= Streak.graceDays
    }

    Map(
      Achievements.gamesPlayed.code   -> qualifying.size,
      Achievements.perfectGames.code  -> qualifying.count(play => play.maxScore > 0 && play.score >= play.maxScore),
      Achievements.longestStreak.code -> streak.map(_.longestStreak).getOrElse(0),
      Achievements.daysPlayed.code    -> streak.map(_.totalDays).getOrElse(0),
      Achievements.marathon.code      -> qualifying.count(_.wordCount >= Achievements.marathonWordCount),
      Achievements.polyglot.code      -> pairs.count { case (_, inPair) =>
        inPair.size >= Achievements.polyglotPlaysPerPair
      },
      Achievements.comeback.code      -> comebacks,
    ) ++ perMode
  }
}

final case class AchievementServiceLive(
  repo: AchievementRepository,
  games: GameRepository,
  streaks: StreakRepository,
) extends AchievementService {

  /** What one evaluation found: the rows it added, every row the account now has, and the counts. */
  private final case class Evaluation(
    added: List[UserAchievementRow],
    rows: List[UserAchievementRow],
    counts: Map[String, Int],
  )

  private def run(userId: Long): Task[Evaluation] = {
    for {
      now    <- Clock.currentTime(TimeUnit.MILLISECONDS)
      plays  <- games.finishedPlaysOf(userId)
      streak <- streaks.find(userId)
      have   <- repo.forUser(userId)
      counted = AchievementService.counts(plays, streak)
      owned   = have.map(row => (row.code, row.tier)).toSet
      due     = Achievements.all.flatMap { achievement =>
                  val reached = achievement.ladder.tierFor(counted.getOrElse(achievement.code, 0))
                  (1 to reached).toList
                    .filterNot(tier => owned.contains((achievement.code, tier)))
                    .map(tier => UserAchievementRow(userId, achievement.code, tier, now))
                }
      added  <- if (due.isEmpty) ZIO.succeed(Nil) else repo.insertNew(due)
    } yield Evaluation(added, have ++ added, counted)
  }

  def evaluate(userId: Long): Task[List[AchievementUnlock]] = {
    run(userId).map(_.added.map(row => AchievementUnlock(row.code, row.tier, row.unlockedAt)))
  }

  def overview(userId: Long): Task[AchievementsResponse] = {
    run(userId).map { evaluation =>
      val byCode = evaluation.rows.groupBy(_.code)
      AchievementsResponse(
        Achievements.all.map { achievement =>
          val count = evaluation.counts.getOrElse(achievement.code, 0)
          val rows  = byCode.getOrElse(achievement.code, Nil)
          // A stored tier can be above what the count reaches now, when a deleted game took plays with it.
          val tier  = (achievement.ladder.tierFor(count) :: rows.map(_.tier)).max
          AchievementProgress(
            code = achievement.code,
            tier = tier,
            count = count,
            nextThreshold = achievement.ladder.threshold(tier + 1),
            unlockedAt = rows.find(_.tier == tier).map(_.unlockedAt),
          )
        }
      )
    }
  }
}
