package gathedge.shared.domain

import zio.json.*

/** A label one account puts on words, and the only notion of ownership the vocabulary has: a word is in your collection
  * if and only if you have tagged it.
  *
  * An entity with an id rather than a string on a word, because a name may contain anything a reader types and the tag
  * bar has to list them anyway. Names are unique per account, case-insensitively.
  *
  * @param wordCount
  *   how many words currently carry it, which is what the tag picker shows next to each name.
  */
final case class Tag(id: Long, name: String, wordCount: Long) derives JsonCodec

object Tag {

  /** Names the phase-2 practice screen will use for the sets it computes — "everything", "what I still get wrong", and
    * so on. They are refused here so that a reader cannot create a tag today that collides with one of them later.
    *
    * Compared case-insensitively against the normalised name, so `all_unknown` is refused as surely as `ALL_UNKNOWN`.
    */
  val reservedNames: Set[String] = {
    Set(
      "all",
      "all_unknown",
      "own_unknown",
      "recent_unknown",
      "all_known",
      "own_known",
      "recent_known",
      "most_mistakes",
    )
  }

  val maxNameLength = 64

  /** The form a name is stored and compared in. Tag names are matched case-insensitively, so `Lesson1` and `lesson1`
    * are the same tag.
    */
  def normalize(name: String): String = name.trim.toLowerCase

  def isReserved(name: String): Boolean = reservedNames.contains(normalize(name))
}
