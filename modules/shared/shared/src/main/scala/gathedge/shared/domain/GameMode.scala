package gathedge.shared.domain

import zio.json.*

/** How a play asks its questions. [[Typing]] is the original loop: the player writes the translation, and a single typo
  * still earns part of the point. [[MultipleChoice]] shows the same prompt with up to four translations to click, so
  * nothing is typed and nothing is half-right — see `GameScoring.scoreChoice`.
  *
  * A play-time choice, not a property of the game: the same quiz is playable either way, the same rule
  * [[WordPreference]] and the rest of `GameVariantDto` follow. Stored on `game_plays.mode`.
  */
enum GameMode derives JsonCodec, CanEqual {
  case Typing,
    MultipleChoice
}

object GameMode {

  val all: List[GameMode] = List(Typing, MultipleChoice)

  /** What `game_plays.mode` stores. Written out rather than derived from `toString`, the same reasoning
    * [[WordPreference.code]]/`AnswerOutcome.code` follow.
    */
  def code(mode: GameMode): String = {
    mode match {
      case Typing         =>
        "typing"
      case MultipleChoice =>
        "multipleChoice"
    }
  }

  def fromString(value: String): Option[GameMode] = {
    value.toLowerCase match {
      case "typing"         =>
        Some(Typing)
      case "multiplechoice" =>
        Some(MultipleChoice)
      case _                =>
        None
    }
  }
}
