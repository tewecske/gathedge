package gathedge.shared.domain

import zio.json.*

/** A label one account puts on words. Tagging is still the only notion of "mine" the vocabulary has — a word is in your
  * collection if and only if you have tagged it — but the tag itself is visible to every account, not only the one that
  * made it: a reader may filter by anybody's tag, or copy its name into one of their own, even though only the owner
  * may attach or detach words with it.
  *
  * An entity with an id rather than a string on a word, because a name may contain anything a reader types and the tag
  * bar has to list them anyway. Names are unique per account, case-insensitively — not globally, since two accounts
  * copying the same idea (or the default "saved") is expected, not a collision.
  *
  * @param wordCount
  *   how many words currently carry it, which is what the tag picker shows next to each name.
  * @param ownedByMe
  *   whether the caller is the account that made it. Still the only thing that decides whether they may rename or
  *   delete it, and what the two tag dropdowns mark and sort on; editing its *content* is now also open to any member
  *   of `group`, see `WordService.requireEditableTag`.
  * @param group
  *   the [[GroupRef]] this tag has been attached to, if any. `None` for an ordinary tag, unaffected by any of this.
  * @param editableByMe
  *   whether the caller may edit this tag's *content* — `ownedByMe`, or a member of `group`. Every real construction
  *   states it explicitly (see `WordService.toTag`); the default is the conservative `false` rather than mirroring
  *   `ownedByMe`, purely because a case class default cannot read a sibling parameter. This is what the collect picker
  *   in `WordCollect` offers a tick or a chip against; it is never itself the authority — every write is re-checked
  *   server-side by `WordService.requireEditableTag`.
  * @param sourceLanguage
  *   the first half of the tag's language pair. Mandatory: chosen when the tag is created, editable only while the tag
  *   has no `word_tag_pairs` row, and locked for good after. It says which side of a bidirectional `word_tag_pairs` row
  *   is the "source". A word may be attached to the tag if it is in either of the tag's two languages, and a pair if
  *   its two words are the tag's two languages, one each — whichever way round.
  * @param targetLanguage
  *   the other half — the language the tag asks its answers in by default. `WordDetailPage` opens its add-a-translation
  *   form on the side of this pair the word is not, so a reader collecting into a `de → hu` tag is offered Hungarian on
  *   a German word and German on a Hungarian one.
  */
final case class Tag(
  id: Long,
  name: String,
  wordCount: Long,
  ownedByMe: Boolean,
  group: Option[GroupRef] = None,
  editableByMe: Boolean = false,
  sourceLanguage: WordLanguage = WordLanguage.De,
  targetLanguage: WordLanguage = WordLanguage.Hu,
) derives JsonCodec

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

  /** The order both tag dropdowns show: the reader's own tags first, then everyone else's, alphabetically within each
    * group. Applied wherever a tag list is about to be rendered rather than trusted from the wire, so the rule holds
    * even if a caller's own sort gets out of step with it.
    */
  def sorted(tags: List[Tag]): List[Tag] = tags.sortBy(tag => (!tag.ownedByMe, tag.name.toLowerCase))
}

/** Where a practice pair inside a tag came from — `word_tag_pairs.match_kind`.
  *
  * The two import paths make different claims, and the editor has to be able to say which one a row carries. The
  * free-text path can only mark a pair `word_translations` already links, so the dictionary has checked it. The tabular
  * path writes the pair its row asserts, for words the dictionary may never have heard of. Marking both the same way
  * claimed a check that had not happened.
  */
enum PairMatch derives JsonCodec, CanEqual {

  /** The reader marked this pair themselves — a chip on the words page, an editor row, a copied tag. */
  case Manual

  /** Both words were in the dictionary and already each other's translation. */
  case Verified

  /** An import wrote the pair from a row that put the two cells on one line. Nothing has checked it. */
  case Paired
}

object PairMatch {

  val all: List[PairMatch] = List(Manual, Verified, Paired)

  /** What `word_tag_pairs.match_kind` stores. [[Manual]] is `''`, the `words.gender` rule: absent is one of the values,
    * not the absence of one. Written out rather than derived from `toString`, so renaming a case cannot orphan stored
    * rows.
    */
  def code(kind: PairMatch): String = {
    kind match {
      case Manual   =>
        ""
      case Verified =>
        "verified"
      case Paired   =>
        "paired"
    }
  }

  /** Anything unrecognised reads as [[Manual]] — a row whose provenance is not known is not a claim about one. */
  def fromString(value: String): PairMatch = all.find(kind => code(kind) == value.toLowerCase).getOrElse(Manual)

  extension (kind: PairMatch) {
    def wireCode: String = code(kind)
  }
}
