package gathedge.shared.domain

import zio.test._

/** The wordlist editor's filter, which both ends read.
  *
  * These rules used to live in the browser, where the whole wordlist was in hand. The listing is paged by the database
  * now: the SQL behind `WordRepository.tagEntryPage` decides which *words* a page holds, and [[TagEntryFilter.matches]]
  * decides which of a word's rows are drawn. The two must say the same thing about the same row, so the rules are
  * stated once, here, and `WordServiceSpec` checks the SQL against them on real rows.
  */
object TagEntryFilterSpec extends ZIOSpecDefault {

  private def bucket(imported: Boolean, matchKind: PairMatch, hasTarget: Boolean): Option[EntryBucket] =
    EntryBucket.of(imported, matchKind, hasTarget)

  def spec = {
    suite("TagEntryFilter")(
      suite("EntryBucket.of")(
        test("each kind of imported row falls in its own bucket") {
          assertTrue(
            bucket(imported = true, PairMatch.Verified, hasTarget = true).contains(EntryBucket.Verified),
            bucket(imported = true, PairMatch.Paired, hasTarget = true).contains(EntryBucket.Paired),
            bucket(imported = true, PairMatch.Manual, hasTarget = true).contains(EntryBucket.Other),
            bucket(imported = true, PairMatch.Manual, hasTarget = false).contains(EntryBucket.Unmatched),
          )
        },
        // The one row in no bucket at all, which is why an empty selection has to mean "every row": there is no chip
        // that would otherwise show it.
        test("a hand-marked row on a hand-added word is in no bucket") {
          assertTrue(
            bucket(imported = false, PairMatch.Manual, hasTarget = true).isEmpty,
            bucket(imported = false, PairMatch.Manual, hasTarget = false).isEmpty,
          )
        },
        // A pair an import asserted is not a pair the dictionary confirmed — the badge and the chip must not blur the
        // two, which is the regression `match_kind` was added for.
        test("a tabular import's pair is never the verified bucket") {
          assertTrue(
            bucket(imported = true, PairMatch.Paired, hasTarget = true).contains(EntryBucket.Paired),
            !bucket(imported = true, PairMatch.Paired, hasTarget = true).contains(EntryBucket.Verified),
          )
        },
      ),
      suite("matches")(
        test("nothing selected shows every row") {
          assertTrue(
            TagEntryFilter.none.isEmpty,
            TagEntryFilter.none
              .matches(imported = false, PairMatch.Manual, hasTarget = true, createdByMe = false, inMyOtherTags = false),
          )
        },
        test("the buckets are OR'd; a row matches when its bucket is among the selected") {
          val filter = TagEntryFilter(buckets = Set(EntryBucket.Verified, EntryBucket.Unmatched))
          assertTrue(
            filter.matches(imported = true, PairMatch.Verified, hasTarget = true, false, false),
            filter.matches(imported = true, PairMatch.Manual, hasTarget = false, false, false),
            !filter.matches(imported = true, PairMatch.Manual, hasTarget = true, false, false),
            !filter.matches(imported = true, PairMatch.Paired, hasTarget = true, false, false),
          )
        },
        test("\"imported by me\" needs both createdByMe and imported, and ANDs with the buckets") {
          val mine = TagEntryFilter(importedByMe = true)
          assertTrue(
            mine.matches(imported = true, PairMatch.Manual, hasTarget = false, createdByMe = true, false),
            !mine.matches(imported = false, PairMatch.Manual, hasTarget = false, createdByMe = true, false),
            !mine.matches(imported = true, PairMatch.Manual, hasTarget = false, createdByMe = false, false),
            !mine
              .copy(buckets = Set(EntryBucket.Verified))
              .matches(imported = true, PairMatch.Manual, hasTarget = false, createdByMe = true, false),
          )
        },
        test("\"only in this wordlist\" keeps a row only when no other tag of mine holds the word") {
          val unique = TagEntryFilter(uniqueToTag = true)
          assertTrue(
            unique.matches(imported = false, PairMatch.Manual, hasTarget = true, false, inMyOtherTags = false),
            !unique.matches(imported = false, PairMatch.Manual, hasTarget = true, false, inMyOtherTags = true),
          )
        },
      ),
      // The chips travel as one query parameter, so a reader can send the narrowed listing on. A stale or hand-typed
      // one is dropped rather than refused, the rule every other listing filter follows.
      suite("codes / parse")(
        test("the selection round-trips through the query parameter") {
          val chosen = Set(EntryBucket.Unmatched, EntryBucket.Verified)
          assertTrue(
            TagEntryFilter.parse(Some(TagEntryFilter.codes(chosen))) == chosen,
            // Always in `EntryBucket.all`'s order, so the same selection spells the same address.
            TagEntryFilter.codes(chosen) == "verified,unmatched",
            TagEntryFilter.codes(Set.empty) == "",
          )
        },
        test("an unreadable chip is dropped, not refused") {
          assertTrue(
            TagEntryFilter.parse(Some("verified,shoe-size")) == Set(EntryBucket.Verified),
            TagEntryFilter.parse(Some("")) == Set.empty[EntryBucket],
            TagEntryFilter.parse(None) == Set.empty[EntryBucket],
          )
        },
      ),
    )
  }
}
