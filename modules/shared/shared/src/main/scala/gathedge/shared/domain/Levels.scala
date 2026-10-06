package gathedge.shared.domain

import zio.json.*

import scala.annotation.tailrec

/** Where an account stands on the level curve. `levelStartXp` is the total XP that `level` needs, and `nextLevelXp` the
  * total the next level needs, so a progress bar is `totalXp - levelStartXp` of `nextLevelXp - levelStartXp`.
  */
final case class LevelProgress(level: Int, totalXp: Long, levelStartXp: Long, nextLevelXp: Long) derives JsonCodec

/** The XP rules and the level curve. Pure arithmetic, in `shared`, so the server that counts the XP and the browser
  * that draws the bar agree.
  *
  * XP comes from three places, and every one is derived from rows that already exist:
  *   - each qualifying play (see [[Achievements.isQualifying]]) gives [[xpPerPlay]];
  *   - each correct answer in a qualifying play gives [[xpPerCorrectAnswer]]; a typo or a wrong answer gives nothing;
  *   - each unlocked achievement tier gives [[Achievement.xpFor]].
  *
  * Only qualifying plays give XP, for the reason they are the only plays an achievement counts: a one-word play is two
  * clicks.
  */
object Levels {

  val xpPerPlay: Int = 5

  val xpPerCorrectAnswer: Int = 1

  /** Going from level `L` to `L + 1` costs `L` times this. */
  val xpPerLevelStep: Long = 100L

  /** The total XP that `level` needs: `50 × L × (L − 1)`. Level 1 needs nothing. */
  def totalFor(level: Int): Long = {
    if (level <= 1) 0L
    else xpPerLevelStep * level.toLong * (level.toLong - 1L) / 2L
  }

  /** The highest level that `totalXp` reaches. Never below 1. */
  def levelFor(totalXp: Long): Int = {
    if (totalXp <= 0L) {
      1
    } else {
      // The inverse of totalFor, rounded down, then corrected for floating-point error at a boundary.
      val estimate = ((1.0 + math.sqrt(1.0 + 8.0 * totalXp.toDouble / xpPerLevelStep.toDouble)) / 2.0).toInt
      settle(math.max(1, estimate), totalXp)
    }
  }

  @tailrec
  private def settle(level: Int, totalXp: Long): Int = {
    if (totalFor(level + 1) <= totalXp) settle(level + 1, totalXp)
    else if (level > 1 && totalFor(level) > totalXp) settle(level - 1, totalXp)
    else level
  }

  def progress(totalXp: Long): LevelProgress = {
    val xp    = math.max(0L, totalXp)
    val level = levelFor(xp)
    LevelProgress(level, xp, totalFor(level), totalFor(level + 1))
  }

  /** The XP that `plays` qualifying plays with `correctAnswers` correct answers between them give. */
  def playXp(plays: Int, correctAnswers: Long): Long = {
    xpPerPlay.toLong * plays.toLong + xpPerCorrectAnswer.toLong * correctAnswers
  }
}
