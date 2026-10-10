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
    gender: Option[Gender] = None,
    askArticles: Boolean = false,
  ): Option[DrillRound] = {
    val profile = LanguageProfile.of(language)
    FormDrill.round(
      template,
      ref,
      forms(lemma, fixture),
      lemma,
      (kind: FormPrefix, tags: Set[String]) => profile.prefix(kind, gender, tags, lemma),
      askArticles,
    )(_.relation, (form: Form) => form.id, _.text)
  }

  private def cells(round: Option[DrillRound]): List[DrillCell] = round.toList.flatMap(_.rows).flatMap(_.cells)

  def spec = suite("FormDrill")(
    suite("round")(
      test("lists the template's tables in the order the word page draws them") {
        val tables = FormDrill.tables(FormTemplates.germanVerb)
        assertTrue(
          tables.head == DrillTableRef(0, 0, None, Some(FormLabel.tags("present"))),
          tables.map(ref => (ref.section, ref.table, ref.column)).distinct.size == tables.size,
        )
      },
      test("turns each filled cell of the chosen table into a gap, row by row") {
        val ref    = FormDrill.tables(FormTemplates.germanVerb).head
        val result = round(FormTemplates.germanVerb, ref, "kaufen", FormFixtures.deKaufen, WordLanguage.De)
        val gaps   = cells(result).collect { case gap: DrillCell.Gap => gap }
        assertTrue(
          gaps.headOption.contains(DrillCell.Gap(0, "kaufe", Some("ich"))),
          gaps.map(_.index) == gaps.indices.toList,
          result.exists(_.gaps.count(_ == "kauft") >= 2),
        )
      },
      test("gives a form spelled like the headword or like an earlier cell of its row") {
        val ref    = FormDrill.tables(FormTemplates.germanVerb).head
        val result = round(FormTemplates.germanVerb, ref, "kaufen", FormFixtures.deKaufen, WordLanguage.De)
        val first  = result.toList.flatMap(_.rows).headOption.toList.flatMap(_.cells)
        assertTrue(
          first == List(DrillCell.Gap(0, "kaufe", Some("ich")), DrillCell.Given("kaufe", Some("ich"))),
          !result.exists(_.gaps.contains("kaufen")),
          result.exists(_.gaps.contains("kaufest")),
          result.map(_.gaps.size).contains(7),
        )
      },
      test("drills a table wider than two columns one column at a time") {
        val indicative = FormDrill.tables(FormTemplates.spanishVerb).filter(ref => ref.section == 0 && ref.table == 0)
        val present    = indicative.head
        val result     = round(FormTemplates.spanishVerb, present, "comprar", FormFixtures.esComprar, WordLanguage.Es)
        assertTrue(
          indicative.map(_.column) == (0 until 5).map(Some(_)).toList,
          present.columnTitle.contains(FormLabel.tags("present")),
          result.map(_.columns.size).contains(1),
          result.exists(_.gaps.contains("compra")),
          !result.exists(_.gaps.contains("compraba")),
        )
      },
      test("asks only the main Hungarian cases, with the headword given") {
        val template = FormDrill.template(WordLanguage.Hu, PartOfSpeech.Noun).get
        val ref      = FormDrill.tables(template).head
        val result   = round(template, ref, "ház", FormFixtures.huHaz, WordLanguage.Hu)
        assertTrue(
          template.sections.head.tables.head.rows.size == FormDrill.hungarianCases.size,
          cells(result).headOption.contains(DrillCell.Given("ház", None)),
          result
            .map(_.gaps.toSet)
            .contains(
              Set("házak", "házat", "házakat", "háznak", "házaknak", "házban", "házakban", "házon", "házakon")
            ),
          FormDrill.template(WordLanguage.De, PartOfSpeech.Noun).contains(FormTemplates.germanNoun),
        )
      },
      test("lets the player choose the articles of a table that has them") {
        val ref     = FormDrill.tables(FormTemplates.germanNoun).head
        val haus    = (ask: Boolean) => {
          round(FormTemplates.germanNoun, ref, "Haus", FormFixtures.deHaus, WordLanguage.De, Some(Gender.Neuter), ask)
        }
        val asked   = haus(true)
        val nino    = (ask: Boolean) => {
          val romance = FormDrill.tables(FormTemplates.romanceNoun).head
          round(
            FormTemplates.romanceNoun,
            romance,
            "niño",
            FormFixtures.esNino,
            WordLanguage.Es,
            Some(Gender.Masculine),
            ask,
          )
        }
        val ninoAsk = nino(true)
        assertTrue(
          ref.articles,
          !FormDrill.tables(FormTemplates.germanVerb).head.articles,
          haus(false).exists(_.articles.isEmpty),
          asked.map(_.articles).contains(List("das", "die", "des", "der", "dem", "den", "das", "die")),
          cells(asked).headOption.contains(DrillCell.Given("Haus", Some("das"), Some(0))),
          nino(false).isEmpty,
          ninoAsk.map(_.gaps).contains(List("niños")),
          ninoAsk.map(_.articles.size).contains(2),
          LanguageProfile.of(WordLanguage.De).articleChoices == List("der", "die", "das", "des", "dem", "den"),
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
      test("checks the chosen articles beside the forms and scores both") {
        val board   = DrillBoard(List("a", "b"), List("a", "b"), articles = List("der", "die"))
          .place(0, 0)
          .place(1, 1)
          .choose(0, "der")
        val full    = board.choose(1, "das")
        val checked = full.check
        val fixed   = checked.choose(1, "die").check
        assertTrue(
          !board.isFull,
          full.isFull,
          checked.articlesLocked == Set(0),
          checked.articlesWrong == Set(1),
          checked.chosen == Map(0 -> "der"),
          checked.firstTry.contains(3),
          checked.choose(0, "die").chosen == Map(0 -> "der"),
          fixed.isSolved,
          fixed.maxScore == 4,
        )
      },
      test("reveals one form once a round, and it never scores") {
        val board    = DrillBoard(List("a", "b", "c"), List("c", "b", "a")).place(0, 0)
        val hinted   = board.reveal(None)
        val again    = hinted.reveal(None)
        val byChip   = DrillBoard(List("a", "b", "c"), List("c", "b", "a")).reveal(Some(1))
        val finished = hinted.place(1, 1).place(0, 2).check
        assertTrue(
          hinted.locked == Set(0),
          hinted.placed == Map(0 -> 2),
          hinted.bank == List(0, 1),
          hinted.hint.contains(DrillHint.Form(0)),
          !hinted.canHint,
          again == hinted,
          byChip.placed == Map(1 -> 1),
          byChip.locked == Set(1),
          finished.isSolved,
          finished.firstTry.contains(2),
        )
      },
      test("reveals an article once every form is in place") {
        val board = DrillBoard(List("a"), List("a"), articles = List("der", "die")).place(0, 0).check.reveal(None)
        assertTrue(
          board.hint.contains(DrillHint.Article(0)),
          board.chosen == Map(0 -> "der"),
          board.articlesLocked == Set(0),
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
