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

/** Which wordlists `GET /api/tags/page` narrows to — the flat listing's own reading of [[Tag.ownedByMe]]/
  * [[Tag.editableByMe]], the three groups `TagsPage`'s sections used to be. [[Mine]] is `ownedByMe`; [[Group]] is
  * `editableByMe` without `ownedByMe` — a study group's tag, not the caller's own; [[Other]] is everything not
  * `editableByMe`. A signed-out reader has neither, so [[Mine]]/[[Group]] simply answer empty.
  */
enum TagScope derives JsonCodec, CanEqual {
  case All, Mine, Group, Other
}

object TagScope {

  val all: List[TagScope] = List(All, Mine, Group, Other)

  def code(scope: TagScope): String = {
    scope match {
      case All   =>
        "all"
      case Mine  =>
        "mine"
      case Group =>
        "group"
      case Other =>
        "other"
    }
  }

  /** Anything unrecognised falls back to [[All]] — the same lenient rule every other listing filter in a query string
    * follows, so a stale link narrows to nothing rather than failing.
    */
  def fromString(value: String): TagScope = all.find(scope => code(scope) == value.toLowerCase).getOrElse(All)
}

/** Which kind of row the wordlist editor's four provenance chips narrow to — read off a row's `imported` flag, its
  * [[PairMatch]] and whether it has an answer at all, and never stored.
  *
  * The four are mutually exclusive, and one kind of row is in none of them: a pair the reader marked by hand on a
  * membership no import wrote. That is why an empty selection means "every row" rather than "no row" — there would
  * otherwise be no chip that shows it.
  */
enum EntryBucket derives JsonCodec, CanEqual {

  /** An import found the two words already linked in the dictionary. */
  case Verified

  /** A tabular import put the two cells on one line. Nothing has checked the pair. */
  case Paired

  /** An imported row whose pair the reader marked by hand afterwards. */
  case Other

  /** An imported word that still has no answer. */
  case Unmatched
}

object EntryBucket {

  val all: List[EntryBucket] = List(Verified, Paired, Other, Unmatched)

  /** What the `match` query parameter spells. Written out rather than derived from `toString`, the rule
    * [[PairMatch.code]] follows: renaming a case must not change an address somebody bookmarked.
    */
  def code(bucket: EntryBucket): String = {
    bucket match {
      case Verified  =>
        "verified"
      case Paired    =>
        "paired"
      case Other     =>
        "other"
      case Unmatched =>
        "unmatched"
    }
  }

  def fromString(value: String): Option[EntryBucket] = all.find(bucket => code(bucket) == value.trim.toLowerCase)

  /** The bucket a row falls in, or `None` for the hand-marked row that is in none of them.
    *
    * Stated over the three facts a row carries rather than over a row type, so the browser can ask it of a rendered row
    * and the server of a projection it is about to send — and so the database predicate behind the paged listing has
    * exactly one definition to mirror.
    */
  def of(imported: Boolean, matchKind: PairMatch, hasTarget: Boolean): Option[EntryBucket] = {
    matchKind match {
      case PairMatch.Verified                        =>
        Some(Verified)
      case PairMatch.Paired                          =>
        Some(Paired)
      case PairMatch.Manual if imported && hasTarget =>
        Some(Other)
      case PairMatch.Manual if imported              =>
        Some(Unmatched)
      case PairMatch.Manual                          =>
        None
    }
  }
}

/** Everything the editor's filter bar narrows its rows by, in one value — the wordlist editor's counterpart to the
  * other listings' filters, and, since that listing is paged by the database, a value both ends read.
  *
  * The browser puts it in the URL, `GET /api/tags/{tagId}/entries/page` takes it as query parameters, and the database
  * narrows the page and the count by it. [[matches]] is what keeps the three in step: the SQL decides which *words*
  * have a row the filter admits, and this decides which of that word's rows are shown, so a word carrying two answers
  * under two different provenances shows only the one that was asked for.
  *
  * The two flags AND on top of the buckets, and both are about the reader: with nobody signed in, `createdByMe` is
  * never true and `inMyOtherTags` never is either, so "imported by me" answers empty and "only in this wordlist" lets
  * everything through. That is the honest reading of both for a visitor, not a special case.
  */
final case class TagEntryFilter(
  buckets: Set[EntryBucket] = Set.empty,
  importedByMe: Boolean = false,
  uniqueToTag: Boolean = false,
) {

  /** Whether this narrows anything at all — what lets the listing skip the whole predicate. */
  def isEmpty: Boolean = buckets.isEmpty && !importedByMe && !uniqueToTag

  /** Whether one row passes: its bucket is among those selected (none selected is every bucket), and both flags hold.
    */
  def matches(
    imported: Boolean,
    matchKind: PairMatch,
    hasTarget: Boolean,
    createdByMe: Boolean,
    inMyOtherTags: Boolean,
  ): Boolean = {
    val bucketOk = buckets.isEmpty || EntryBucket.of(imported, matchKind, hasTarget).exists(buckets.contains)
    val mineOk   = !importedByMe || (createdByMe && imported)
    val uniqueOk = !uniqueToTag || !inMyOtherTags
    bucketOk && mineOk && uniqueOk
  }
}

object TagEntryFilter {

  /** The unfiltered listing — every row of the wordlist. */
  val none: TagEntryFilter = TagEntryFilter()

  /** The buckets as one query parameter: their codes, comma-joined, in [[EntryBucket.all]]'s order so the same
    * selection always spells the same address. Empty when nothing is selected, which writes no parameter at all.
    */
  def codes(buckets: Set[EntryBucket]): String = {
    EntryBucket.all.filter(buckets.contains).map(EntryBucket.code).mkString(",")
  }

  /** Reads that parameter back. Anything unrecognised is dropped rather than refused — the lenient rule every other
    * listing filter in a query string follows, so a stale link still opens a list of rows.
    */
  def parse(value: Option[String]): Set[EntryBucket] = {
    value.toList.flatMap(_.split(',').toList).flatMap(code => EntryBucket.fromString(code)).toSet
  }
}
