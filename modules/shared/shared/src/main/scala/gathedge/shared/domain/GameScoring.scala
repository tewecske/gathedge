package gathedge.shared.domain

import zio.json.*

/** How one submitted answer to a game prompt turned out. What `game_play_answers.outcome` stores. */
enum AnswerOutcome derives JsonCodec, CanEqual {
  case Correct,
    Typo,
    Wrong
}

object AnswerOutcome {

  /** What `game_play_answers.outcome` stores. Written out rather than derived from `toString`, the same reasoning
    * [[WordLanguage.code]] follows.
    */
  def code(outcome: AnswerOutcome): String = {
    outcome match {
      case Correct =>
        "correct"
      case Typo    =>
        "typo"
      case Wrong   =>
        "wrong"
    }
  }

  def fromString(value: String): Option[AnswerOutcome] = {
    value.toLowerCase match {
      case "correct" =>
        Some(Correct)
      case "typo"    =>
        Some(Typo)
      case "wrong"   =>
        Some(Wrong)
      case _         =>
        None
    }
  }

  extension (outcome: AnswerOutcome) {
    def wireCode: String = code(outcome)
  }
}

final case class ScoredAnswer(outcome: AnswerOutcome, points: Int) derives JsonCodec

/** Scoring a typed answer against the expected translation. Pure and cross-compiled so the same rule runs on the server
  * (deciding `game_play_answers.points`) and could run in the browser without a round trip, the same reasoning
  * `validation/Validation.scala` is shared between the signup form and the signup endpoint.
  */
object GameScoring {

  /** What an exact match is worth; `wordCount * maxPointsPerWord` is a play's `max_score`. */
  val maxPointsPerWord = 2

  /** A single typo — insertion, deletion, or substitution — is worth this many points. */
  val typoPoints = 1

  /** The number of single-character edits (insert, delete, substitute) that turn `a` into `b`.
    *
    * Classic dynamic-programming table, kept to two rolling rows rather than the full `a.length x b.length` grid — only
    * the previous row is ever read, so there is no reason to keep the rest.
    */
  def levenshtein(a: String, b: String): Int = {
    val (shorter, longer) = if (a.length <= b.length) (a, b) else (b, a)
    var previousRow       = (0 to shorter.length).toArray
    val currentRow        = Array.fill(shorter.length + 1)(0)
    for (i <- 1 to longer.length) {
      currentRow(0) = i
      for (j <- 1 to shorter.length) {
        currentRow(j) = {
          if (longer.charAt(i - 1) == shorter.charAt(j - 1))
            previousRow(j - 1)
          else
            1 + math.min(previousRow(j - 1), math.min(previousRow(j), currentRow(j - 1)))
        }
      }
      var k = 0
      while (k <= shorter.length) {
        previousRow(k) = currentRow(k)
        k += 1
      }
    }
    previousRow(shorter.length)
  }

  /** Normalizes both sides (trim + case-fold) before comparing: an exact match after normalizing is worth
    * [[maxPointsPerWord]], an edit distance of exactly one (a single typo) is worth [[typoPoints]], anything else is
    * worth nothing.
    */
  def score(expected: String, submitted: String): ScoredAnswer = {
    val normalizedExpected = expected.trim.toLowerCase
    val normalizedGiven    = submitted.trim.toLowerCase
    if (normalizedExpected == normalizedGiven)
      ScoredAnswer(AnswerOutcome.Correct, maxPointsPerWord)
    else if (levenshtein(normalizedExpected, normalizedGiven) == 1)
      ScoredAnswer(AnswerOutcome.Typo, typoPoints)
    else
      ScoredAnswer(AnswerOutcome.Wrong, 0)
  }
}
