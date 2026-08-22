package gathedge.shared.domain

import zio.json.*

/** Which words a play should prefer sampling from, when `wordLimit` narrows the eligible pool below its full
  * size — see the "game variants redesign" design doc's "priority sampling, not a hard filter" section. Has no
  * effect at all when the pool is not narrowed. Stored on `game_plays.word_preference`.
  */
enum WordPreference derives JsonCodec, CanEqual {
  case All,
    Unplayed,
    MostMistakes
}

object WordPreference {

  val all: List[WordPreference] = List(All, Unplayed, MostMistakes)

  /** What `game_plays.word_preference` stores and what the `wordPreference` query param carries. Written out rather
    * than derived from `toString`, the same reasoning `AnswerOutcome.code`/`WordLanguage.code` follow.
    */
  def code(preference: WordPreference): String = {
    preference match {
      case All          =>
        "all"
      case Unplayed      =>
        "unplayed"
      case MostMistakes =>
        "mostMistakes"
    }
  }

  def fromString(value: String): Option[WordPreference] = {
    value.toLowerCase match {
      case "all"          =>
        Some(All)
      case "unplayed"     =>
        Some(Unplayed)
      case "mostmistakes" =>
        Some(MostMistakes)
      case _              =>
        None
    }
  }
}
