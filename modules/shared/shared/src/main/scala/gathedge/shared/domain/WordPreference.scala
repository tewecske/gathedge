package gathedge.shared.domain

import zio.json.*

/** Which words a play should prefer sampling from, when `wordLimit` narrows the eligible pool below its full size — see
  * the "game variants redesign" design doc's "priority sampling, not a hard filter" section. Has no effect at all when
  * the pool is not narrowed. Stored on `game_plays.word_preference`.
  */
enum WordPreference derives JsonCodec, CanEqual {
  case All,
    LeastPlayed,
    MostMistakes
}

object WordPreference {

  val all: List[WordPreference] = List(All, LeastPlayed, MostMistakes)

  /** What `game_plays.word_preference` stores and what the `wordPreference` query param carries. Written out rather
    * than derived from `toString`, the same reasoning `AnswerOutcome.code`/`WordLanguage.code` follow.
    *
    * [[LeastPlayed]] keeps the code `"unplayed"` it was written under: every play ever recorded carries that string,
    * and the option still prefers the same words first — a word with no answers is the least played one there is. A
    * migration would buy a tidier column and lose nothing else, so the old rows keep their word. Note this is the one
    * case where the code and the JSON form (the case name, from `derives JsonCodec`) differ.
    */
  def code(preference: WordPreference): String = {
    preference match {
      case All          =>
        "all"
      case LeastPlayed  =>
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
        Some(LeastPlayed)
      case "mostmistakes" =>
        Some(MostMistakes)
      case _              =>
        None
    }
  }
}
