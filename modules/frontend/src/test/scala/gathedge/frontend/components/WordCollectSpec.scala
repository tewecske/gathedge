package gathedge.frontend.components

import gathedge.shared.domain.{Gender, PartOfSpeech, Word, WordLanguage}
import gathedge.shared.dto.{TaggedPair, TranslationOption, WordSummary}
import zio.test._

/** The two rules a tick and a chip are made of, stated as tables.
  *
  * Neither can be reached through a mounted page under jsdom — every request fails, so no row and no translation ever
  * arrives — and both are wrong in the same way if they are asked about the wrong tag: "any tag I have" instead of the
  * one being collected into is what made a filtered listing look fully collected.
  */
object WordCollectSpec extends ZIOSpecDefault {

  private val summary = WordSummary(
    word = Word(1L, WordLanguage.De, "Haus", PartOfSpeech.Noun, Some(Gender.Das)),
    translations = List(TranslationOption(2L, "ház"), TranslationOption(3L, "otthon")),
    tagIds = List(10L, 11L),
    pairs = List(TaggedPair(10L, 2L), TaggedPair(11L, 3L)),
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
        )
      },
    )
  }
}
