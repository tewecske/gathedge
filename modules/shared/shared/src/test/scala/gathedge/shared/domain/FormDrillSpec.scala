package gathedge.shared.domain

import zio.test.*

/** `FormDrill` builds a round from the word page's own layout, and `DrillBoard` keeps the player's chips. */
object FormDrillSpec extends ZIOSpecDefault {

  import FormTemplatesSpec.{Form, forms}

  private def round(
    template: FormTemplate,
    ref: DrillTableRef,
    lemma: String,
    fixture: String,
    language: WordLanguage,
  ): Option[DrillRound] = {
    val profile = LanguageProfile.of(language)
    FormDrill.round(
      template,
      ref,
      forms(lemma, fixture),
      lemma,
      (kind: FormPrefix, tags: Set[String]) => profile.prefix(kind, None, tags, lemma),
    )(_.relation, (form: Form) => form.id, _.text)
  }

  def spec = suite("FormDrill")(
    suite("round")(
      test("lists the template's tables in the order the word page draws them") {
        val tables = FormDrill.tables(FormTemplates.germanVerb)
        assertTrue(
          tables.head == DrillTableRef(0, 0, None, Some(FormLabel.tags("present"))),
          tables.map(ref => (ref.section, ref.table)).distinct.size == tables.size,
        )
      },
      test("turns each filled cell of the chosen table into a gap, row by row") {
        val ref    = FormDrill.tables(FormTemplates.germanVerb).head
        val result = round(FormTemplates.germanVerb, ref, "kaufen", FormFixtures.deKaufen, WordLanguage.De)
        val cells  = result.toList.flatMap(_.rows).flatMap(_.cells)
        assertTrue(
          result.map(_.gaps.size).contains(12),
          cells.headOption.contains(DrillCell.Gap(0, "kaufe", Some("ich"))),
          cells.collect { case DrillCell.Gap(index, _, _) => index } == (0 until 12).toList,
          result.exists(_.gaps.count(_ == "kauft") >= 2),
        )
      },
      test("gives no round for a table with fewer gaps than the minimum") {
        val ref = FormDrill.tables(FormTemplates.englishNoun).head
        assertTrue(round(FormTemplates.englishNoun, ref, "child", FormFixtures.enChild, WordLanguage.En).isEmpty)
      },
      test("gives no round for a table the template does not have") {
        val ref = DrillTableRef(9, 9, None, None)
        assertTrue(round(FormTemplates.germanVerb, ref, "kaufen", FormFixtures.deKaufen, WordLanguage.De).isEmpty)
      },
    ),
    suite("DrillBoard")(
      test("accepts two chips of the same text in either of their gaps") {
        val board = DrillBoard(List("a", "b", "a"), List("a", "a", "b"))
          .place(1, 0)
          .place(0, 2)
          .place(2, 1)
          .check
        assertTrue(board.isSolved, board.firstTry.contains(3), board.bank.isEmpty)
      },
      test("sends a wrong chip back to the bank and scores only the first check") {
        val checked = DrillBoard(List("a", "b", "c"), List("a", "b", "c"))
          .place(0, 0)
          .place(2, 1)
          .place(1, 2)
          .check
        val fixed   = checked.place(1, 1).place(2, 2).check
        assertTrue(
          checked.locked == Set(0),
          checked.wrong == Set(1, 2),
          checked.bank == List(1, 2),
          checked.firstTry.contains(1),
          fixed.isSolved,
          fixed.firstTry.contains(1),
        )
      },
      test("moves a chip between gaps, swaps out a gap's chip, and leaves a locked gap alone") {
        val board  = DrillBoard(List("a", "b", "c"), List("a", "b", "c")).place(0, 0).check
        val moved  = board.place(1, 1).place(1, 2)
        val swap   = moved.place(2, 2)
        val locked = swap.place(1, 0).clear(0)
        assertTrue(
          moved.placed == Map(0 -> 0, 2 -> 1),
          swap.placed == Map(0 -> 0, 2 -> 2),
          swap.bank == List(1),
          locked.placed == swap.placed,
          swap.clear(2).placed == Map(0 -> 0),
        )
      },
    ),
  )
}
