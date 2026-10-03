package gathedge.shared.domain

/** The count each tier of an achievement needs. A short opening ladder, then a fixed step forever, so the tiers never
  * run out. A one-off is a ladder of one rung and no step.
  *
  * Pure arithmetic, in `shared`, so the server that awards a tier and the browser that shows the next one agree.
  */
final case class TierLadder(opening: List[Int], step: Option[Int]) {

  private val last = opening.lastOption.getOrElse(0)

  /** The highest tier that `count` reaches. `0` below the first rung. */
  def tierFor(count: Int): Int = {
    val onLadder = opening.count(_ <= count)
    if (onLadder < opening.size) {
      onLadder
    } else {
      step match {
        case Some(size) if size > 0 =>
          opening.size + (count - last) / size
        case _                      =>
          opening.size
      }
    }
  }

  /** The count that `tier` needs. `None` for a tier below 1, and for a tier past a one-off's only rung. */
  def threshold(tier: Int): Option[Int] = {
    if (tier < 1) {
      None
    } else if (tier <= opening.size) {
      Some(opening(tier - 1))
    } else {
      step.filter(_ > 0).map(size => last + (tier - opening.size) * size)
    }
  }

  /** True when the ladder has one rung and no step. */
  def isOneOff: Boolean = opening.size == 1 && step.isEmpty
}

/** One achievement of the catalog. `code` is what `user_achievements.code` stores, so it never changes. `mode` is set
  * only on the per-game-type "games played" achievements.
  */
final case class Achievement(code: String, mode: Option[GameMode], ladder: TierLadder)

/** The achievement catalog and the rules every count follows.
  *
  * An achievement rewards practice, never a write to the database: words added, tags or games made, sign-ups and logins
  * earn nothing.
  */
object Achievements {

  /** A play counts only when it is finished and asked at least this many words. A one-word play is two clicks. */
  val qualifyingWordCount = 10

  /** One qualifying play with at least this many words earns [[marathon]]. */
  val marathonWordCount = 50

  /** A language pair counts toward [[polyglot]] after this many qualifying plays in it. */
  val polyglotPlaysPerPair = 10

  /** True when a play counts toward an achievement. */
  def isQualifying(finished: Boolean, wordCount: Int): Boolean = finished && wordCount >= qualifyingWordCount

  private val gamesLadder = TierLadder(List(1, 5, 10, 25, 50, 75, 100), Some(50))

  private def oneOff(threshold: Int): TierLadder = TierLadder(List(threshold), None)

  /** Qualifying plays of every game type. */
  val gamesPlayed = Achievement("gamesPlayed", None, gamesLadder)

  /** Qualifying plays of one game type. Built from [[GameMode.all]], so a new game type gets its own achievement. */
  def gamesPlayedIn(mode: GameMode): Achievement = {
    Achievement(s"gamesPlayed.${GameMode.code(mode)}", Some(mode), gamesLadder)
  }

  /** Qualifying plays with every point scored. */
  val perfectGames = Achievement("perfectGames", None, TierLadder(List(1, 5, 10, 20, 30, 40, 50), Some(25)))

  /** The account's longest daily streak, in days. */
  val longestStreak = Achievement("longestStreak", None, TierLadder(List(3, 7, 14, 21, 30), Some(30)))

  /** The number of days the account played on. */
  val daysPlayed = Achievement("daysPlayed", None, TierLadder(List(10, 25, 50), Some(50)))

  /** One qualifying play with at least [[marathonWordCount]] words. */
  val marathon = Achievement("marathon", None, oneOff(1))

  /** [[polyglotPlaysPerPair]] qualifying plays in each of two language pairs. A pair is unordered: English to German
    * and German to English are one pair.
    */
  val polyglot = Achievement("polyglot", None, oneOff(2))

  /** A qualifying play that continued a streak in its grace period (see [[Streak.graceDays]]). */
  val comeback = Achievement("comeback", None, oneOff(1))

  /** Every achievement, in the order the profile shows them. */
  val all: List[Achievement] = {
    List(gamesPlayed) ++ GameMode.all.map(gamesPlayedIn) ++
      List(perfectGames, longestStreak, daysPlayed, marathon, polyglot, comeback)
  }

  def byCode(code: String): Option[Achievement] = all.find(_.code == code)
}
