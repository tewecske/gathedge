package gathedge.shared.domain

import zio.test.*

/** `GrammarTag.categoryOf`'s grouping, which `WordDetailPage`'s Forms section trusts to sort a lemma's forms into
  * headings without a grammar-table layout.
  */
object GrammarTagSpec extends ZIOSpecDefault {

  def spec = {
    suite("GrammarTag")(
      test("known tags land in their expected category") {
        assertTrue(
          GrammarTag.categoryOf("plural") == GrammarCategory.PluralCase,
          GrammarTag.categoryOf("dative,definite,plural") == GrammarCategory.PluralCase,
          GrammarTag.categoryOf("past") == GrammarCategory.Tense,
          GrammarTag.categoryOf("comparative") == GrammarCategory.Comparison,
          GrammarTag.categoryOf("diminutive") == GrammarCategory.Diminutive,
          GrammarTag.categoryOf("archaic") == GrammarCategory.AlternativeSpelling,
        )
      },
      test("an unmapped tag falls to Other, since relation is not a closed enum") {
        assertTrue(GrammarTag.categoryOf("totally-new-tag") == GrammarCategory.Other)
      },
      test("the lowest-priority constituent tag decides a mixed relation's category") {
        // "plural" (priority 10) beats "archaic" (priority 50) -- a plural/case fact outranks a register label.
        assertTrue(GrammarTag.categoryOf("archaic,plural") == GrammarCategory.PluralCase)
      },
      test("priorityOf agrees with categoryOf's own ordering, so a sort by it never contradicts the grouping") {
        assertTrue(
          GrammarTag.priorityOf(GrammarCategory.PluralCase) < GrammarTag.priorityOf(GrammarCategory.Tense),
          GrammarTag.priorityOf(GrammarCategory.Tense) < GrammarTag.priorityOf(GrammarCategory.Comparison),
          GrammarTag.priorityOf(GrammarCategory.Comparison) < GrammarTag.priorityOf(GrammarCategory.Diminutive),
          GrammarTag.priorityOf(GrammarCategory.Diminutive) < GrammarTag.priorityOf(
            GrammarCategory.AlternativeSpelling
          ),
          GrammarTag.priorityOf(GrammarCategory.AlternativeSpelling) < GrammarTag.priorityOf(GrammarCategory.Other),
        )
      },
    )
  }
}
