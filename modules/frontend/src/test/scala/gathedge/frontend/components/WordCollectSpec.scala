package gathedge.frontend.components

import gathedge.shared.domain.{Gender, GroupRef, PartOfSpeech, Tag, Word, WordLanguage}
import gathedge.shared.dto.{TaggedPair, TranslationOption, WordSummary}
import gathedge.shared.i18n.UiKeys
import zio.test._

/** The two rules a tick and a chip are made of, stated as tables.
  *
  * Neither can be reached through a mounted page under jsdom — every request fails, so no row and no translation ever
  * arrives — and both are wrong in the same way if they are asked about the wrong tag: "any tag I have" instead of the
  * one being collected into is what made a filtered listing look fully collected.
  */
object WordCollectSpec extends ZIOSpecDefault {

  private val summary = WordSummary(
    word = Word(1L, WordLanguage.De, "Haus", PartOfSpeech.Noun, Some(Gender.Neuter)),
    translations = List(TranslationOption(2L, "ház"), TranslationOption(3L, "otthon")),
    tagIds = List(10L, 11L),
    pairs = List(TaggedPair(10L, 2L), TaggedPair(11L, 3L)),
    mainWord = None,
    variants = Nil,
    variantsTotal = 0,
    isContext = false,
  )

  def spec = {
    suite("WordCollect")(
      test("a chip is selected by the collect tag, not by any tag the reader has") {
        assertTrue(
          WordCollect.selectedTranslationIds(summary.pairs, Some(10L)) == Set(2L),
          WordCollect.selectedTranslationIds(summary.pairs, Some(11L)) == Set(3L),
          // A tag the word is not marked under shows nothing marked.
          WordCollect.selectedTranslationIds(summary.pairs, Some(12L)) == Set.empty[Long],
          // Only before the tag list arrives, or for a reader with no tags at all.
          WordCollect.selectedTranslationIds(summary.pairs, None) == Set(2L, 3L),
          // "No tag" chosen on purpose shows nothing marked — not "every tag you have", which left a just-cleared
          // select still showing the previous tag's chips.
          WordCollect.selectedTranslationIds(summary.pairs, None, explicitNone = true) == Set.empty[Long],
        )
      },
      test("a tick is set by the collect tag, not by any tag the reader has") {
        assertTrue(
          WordCollect.isTagged(summary.tagIds, Some(10L)),
          WordCollect.isTagged(summary.tagIds, Some(11L)),
          !WordCollect.isTagged(summary.tagIds, Some(12L)),
          WordCollect.isTagged(summary.tagIds, None),
          // A word nobody has filed anywhere is untagged whichever way the question is asked.
          !WordCollect.isTagged(Nil, Some(10L)),
          !WordCollect.isTagged(Nil, None),
          // "No tag" chosen on purpose: nothing is ticked, even for a word filed under other tags.
          !WordCollect.isTagged(summary.tagIds, None, explicitNone = true),
        )
      },
      // A reader with nothing to collect into — a signed-out visitor, or an account with no wordlist — sees where their
      // first tick goes, not "No wordlist": that tick mints the default tag.
      test("the select's empty entry names the default tag until the reader has a wordlist to collect into") {
        val mine      = Tag(10L, "mine", 0L, ownedByMe = true, editableByMe = true)
        val theirs    = Tag(11L, "theirs", 0L, ownedByMe = false, editableByMe = false)
        // A global admin may write to every row, but a stranger's wordlist is still not a collect tag.
        val adminSees = Tag(13L, "stranger", 0L, ownedByMe = false, editableByMe = true)
        assertTrue(
          WordCollect.emptyOptionLabel(Nil) == WordCollect.defaultTagName,
          WordCollect.emptyOptionLabel(List(theirs, adminSees)) == WordCollect.defaultTagName,
          WordCollect.emptyOptionLabel(List(theirs, mine)) == UiKeys.wordsCollectNone,
        )
      },
      test("a remembered tag this account cannot write to is dropped, not written against") {
        val mine      = Tag(10L, "mine", 0L, ownedByMe = true, editableByMe = true)
        val theirs    = Tag(11L, "theirs", 0L, ownedByMe = false, editableByMe = false)
        // A wordlist somebody else owns is only editable through the group that shares it, so the fixture carries one
        // — which is what tells it apart from the row below.
        val classTag  = Tag(12L, "lesson1", 0L, ownedByMe = false, Some(GroupRef(1L, "Period 3")), editableByMe = true)
        // What a global admin sees on a stranger's ungrouped wordlist: editable, and still not a collect tag.
        val adminSees = Tag(13L, "stranger", 0L, ownedByMe = false, editableByMe = true)
        assertTrue(
          // The whole of the guest bug: `localStorage` outlives an account, so an id left there may name a tag the
          // reader cannot write to. Kept, it fails with "No such tag"; dropped, the first click makes a tag instead.
          WordCollect.keptCollectTag(Some(11L), List(theirs)) == None,
          WordCollect.keptCollectTag(Some(11L), Nil) == None,
          // A tag they may write to is kept, group tag or own.
          WordCollect.keptCollectTag(Some(10L), List(mine, theirs)) == Some(10L),
          WordCollect.keptCollectTag(Some(12L), List(classTag, theirs)) == Some(12L),
          // With nothing remembered, their own comes before a group's, and a group's before nothing.
          WordCollect.keptCollectTag(None, List(classTag, mine)) == Some(10L),
          WordCollect.keptCollectTag(None, List(theirs, classTag)) == Some(12L),
          WordCollect.keptCollectTag(None, List(theirs)) == None,
          // "No tag" chosen deliberately is respected — not replaced by one of the reader's own on the next refresh.
          WordCollect.keptCollectTag(None, List(mine, classTag), explicitNone = true) == None,
          // A confirmed remembered id still wins over the explicit-none flag (they re-picked a real tag).
          WordCollect.keptCollectTag(Some(10L), List(mine), explicitNone = true) == Some(10L),
          // A global administrator may write to every wordlist there is, which does not make every wordlist a place
          // to collect into: with nothing of their own and nothing shared, the first click still makes a tag.
          WordCollect.keptCollectTag(None, List(adminSees)) == None,
          // Remembered on purpose, it is still kept — the reader picked it.
          WordCollect.keptCollectTag(Some(13L), List(adminSees)) == Some(13L),
        )
      },
      test("marking a translation adds the tag and the pair") {
        val empty   = WordSummary(
          word = summary.word,
          translations = List(),
          tagIds = Nil,
          pairs = Nil,
          mainWord = None,
          variants = Nil,
          variantsTotal = 0,
          isContext = false,
        )
        val change  = WordCollect.PairChange(1L, 20L, 2L, true)
        val updated = empty.copy(
          tagIds = (empty.tagIds :+ 20L).distinct,
          pairs = (empty.pairs :+ TaggedPair(20L, 2L)).distinct,
        )
        assertTrue(
          updated.tagIds == List(20L),
          updated.pairs == List(TaggedPair(20L, 2L)),
        )
      },
      test("unmarking a translation removes only the pair, not the tag") {
        val change  = WordCollect.PairChange(1L, 10L, 2L, false)
        val updated = summary.copy(
          pairs = summary.pairs.filterNot(p => p.tagId == 10L && p.translationWordId == 2L)
        )
        assertTrue(
          updated.tagIds == List(10L, 11L),          // Tag stays.
          updated.pairs == List(TaggedPair(11L, 3L)), // Just the other pair.
        )
      },
      test("tagging a word adds the tag, untagging removes it and its pairs") {
        val tagChange = WordCollect.TagChange(1L, 10L, false)
        val updated   = summary.copy(
          tagIds = summary.tagIds.filterNot(_ == 10L),
          pairs = summary.pairs.filterNot(_.tagId == 10L),
        )
        assertTrue(
          updated.tagIds == List(11L),
          updated.pairs == List(TaggedPair(11L, 3L)),
        )
      },
      // WordDetailPage.applyChange keeps its tag list as `List[Tag]`, not `List[Long]`, because the tag bar shows a
      // name. `Tag` carries `wordCount`, which the tag-list refresh after every chip click can change — marking a
      // second translation as an answer for the same word genuinely adds a word to the tag. A second, third, fourth
      // chip click for the same word and the same collect tag must still show that one tag once, not once per click.
      test("re-marking translations under the same tag replaces the stale copy instead of piling one up per click") {
        val tag         = Tag(10L, "saved", wordCount = 3, ownedByMe = true)
        val afterClick1 = WordCollect.withTag(Nil, tag)
        val refreshed   = tag.copy(wordCount = 4) // the tag-list refresh that lands between two clicks
        val afterClick2 = WordCollect.withTag(afterClick1, refreshed)
        val afterClick3 = WordCollect.withTag(afterClick2, refreshed.copy(wordCount = 5))
        assertTrue(
          afterClick1 == List(tag),
          afterClick2 == List(refreshed),
          afterClick3.map(_.id) == List(10L),
          afterClick3.size == 1,
        )
      },
      // Both dropdowns share one grouping rule: the reader's own tags in one `<optgroup>`, everyone else's in another,
      // own first — the marking `<option>` itself cannot carry. A reader with none of their own gets no "My tags"
      // group at all rather than an empty one.
      test("both tag dropdowns group the reader's own tags ahead of everyone else's") {
        val mine   = Tag(1L, "mine", wordCount = 2, ownedByMe = true)
        val theirs = Tag(2L, "theirs", wordCount = 5, ownedByMe = false)
        assertTrue(
          WordCollect.tagOptionGroups(List(theirs, mine)).size == 2,
          WordCollect.tagOptionGroups(List(mine)).size == 1,
          WordCollect.tagOptionGroups(List(theirs)).size == 1,
          WordCollect.tagOptionGroups(Nil).isEmpty,
        )
      },
      // A pair goes only into a tag of its own two languages, so with no collect tag a chip that names its pair files
      // into such a tag, or a new one, and never into the first tag the reader has.
      test("with no collect tag a pair files into a tag of its own two languages, the reader's own first") {
        val deEn     = Tag(10L, "words", 0L, ownedByMe = true, editableByMe = true, targetLanguage = WordLanguage.En)
        val enDe     = deEn.copy(id = 11L, sourceLanguage = WordLanguage.En, targetLanguage = WordLanguage.De)
        val deHu     = Tag(12L, "hungarian", 0L, ownedByMe = true, editableByMe = true)
        val classTag = Tag(13L, "class", 0L, ownedByMe = false, Some(GroupRef(1L, "Period 3")), editableByMe = true)
        val stranger = Tag(14L, "stranger", 0L, ownedByMe = false, editableByMe = true)
        assertTrue(
          WordCollect.tagForPair(List(deHu, deEn), (WordLanguage.De, WordLanguage.En)).map(_.id) == Some(10L),
          // Either order: English to German is the same pair as German to English.
          WordCollect.tagForPair(List(deHu, enDe), (WordLanguage.De, WordLanguage.En)).map(_.id) == Some(11L),
          // The reader's own tag comes before a group's.
          WordCollect.tagForPair(List(classTag, deHu), (WordLanguage.Hu, WordLanguage.De)).map(_.id) == Some(12L),
          WordCollect.tagForPair(List(classTag), (WordLanguage.De, WordLanguage.Hu)).map(_.id) == Some(13L),
          // No tag of the pair: a new one is made.
          WordCollect.tagForPair(List(deHu), (WordLanguage.De, WordLanguage.En)).isEmpty,
          // A tag the reader could write to only as an administrator is no collect tag.
          WordCollect.tagForPair(List(stranger), (WordLanguage.De, WordLanguage.Hu)).isEmpty,
        )
      },
      test("a new tag for a pair takes the default name, with the pair's codes when the reader already has that name") {
        val default = Tag(10L, WordCollect.defaultTagName, 0L, ownedByMe = true, editableByMe = true)
        val shared  = Tag(11L, WordCollect.defaultTagName.toLowerCase, 0L, ownedByMe = false, editableByMe = true)
        val pair    = (WordLanguage.De, WordLanguage.En)
        assertTrue(
          WordCollect.defaultTagNameFor(Nil, pair) == WordCollect.defaultTagName,
          // Names are unique per owner, so another reader's tag of that name is no clash.
          WordCollect.defaultTagNameFor(List(shared), pair) == WordCollect.defaultTagName,
          WordCollect.defaultTagNameFor(List(default), pair) == s"${WordCollect.defaultTagName} GER–ENG",
        )
      },
    )
  }
}
