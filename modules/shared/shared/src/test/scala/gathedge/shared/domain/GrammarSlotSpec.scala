package gathedge.shared.domain

import zio.test.*

/** What a `word_forms.relation` string means for the article in front of the word.
  *
  * The relations asserted here are real ones, taken from the imported dictionary rather than invented: the tag
  * vocabulary is not a closed enum, so the value of this suite is that it pins the '''lenient''' reads — a relation
  * with no case, one with no number, one naming several cases, and one made entirely of tags that say nothing about the
  * article.
  */
object GrammarSlotSpec extends ZIOSpecDefault {

  private def slot(relation: String): (GrammaticalCase, GrammaticalNumber) = {
    val parsed = GrammarTag.slotOf(relation)
    (parsed.grammaticalCase, parsed.number)
  }

  def spec = {
    suite("GrammarTag.slotOf")(
      test("reads the case and the number a relation names") {
        assertTrue(
          slot("dative,definite,plural") == ((GrammaticalCase.Dative, GrammaticalNumber.Plural)),
          slot("genitive,singular") == ((GrammaticalCase.Genitive, GrammaticalNumber.Singular)),
          slot("accusative,definite,plural") == ((GrammaticalCase.Accusative, GrammaticalNumber.Plural)),
          slot("definite,nominative,plural") == ((GrammaticalCase.Nominative, GrammaticalNumber.Plural)),
        )
      },
      test("a relation naming no case is nominative and one naming no number is singular") {
        assertTrue(
          slot("plural") == ((GrammaticalCase.Nominative, GrammaticalNumber.Plural)),
          slot("genitive") == ((GrammaticalCase.Genitive, GrammaticalNumber.Singular)),
          slot("") == ((GrammaticalCase.Nominative, GrammaticalNumber.Singular)),
          GrammarTag.slotOf("diminutive,neuter") == FormSlot.citation,
        )
      },
      // `accusative,genitive,nominative,plural` is one real relation on `Versuch` -> `Versuche`. Its three cases take
      // three different plural articles, so without a fixed reading order the article would depend on tag order.
      test("a relation naming several cases is read in the citation case's favour, always the same way") {
        assertTrue(
          slot("accusative,genitive,nominative,plural") == ((GrammaticalCase.Nominative, GrammaticalNumber.Plural)),
          slot("genitive,nominative,plural") == slot("nominative,genitive,plural"),
          slot("accusative,dative") == ((GrammaticalCase.Dative, GrammaticalNumber.Singular)),
        )
      },
      test("the tags that describe the adjective ending, not the article, are not looked at") {
        assertTrue(
          slot("dative,singular,weak") == slot("dative,mixed,singular"),
          slot("dative,singular,weak") == slot("dative,singular,strong"),
          slot("dative,singular,weak") == ((GrammaticalCase.Dative, GrammaticalNumber.Singular)),
        )
      },
      test("tags are matched case-insensitively and trimmed, like every other relation read") {
        assertTrue(slot(" Dative , Plural ") == ((GrammaticalCase.Dative, GrammaticalNumber.Plural)))
      },
      // The worked example from the issue: `die Sache` declined across its table, each cell reached from the relation
      // the dictionary really stores for it.
      test("a German feminine noun's forms come out with the articles the grammar book prints") {
        val profile = LanguageProfile.of(WordLanguage.De)

        def show(text: String, relation: String): String = {
          profile.displayIn(text, Some(Gender.Feminine), GrammarTag.slotOf(relation))
        }

        assertTrue(
          show("Sache", "") == "die Sache",
          show("Sache", "genitive,singular") == "der Sache",
          show("Sache", "dative,singular") == "der Sache",
          show("Sachen", "definite,nominative,plural") == "die Sachen",
          show("Sachen", "definite,genitive,plural") == "der Sachen",
          show("Sachen", "dative,definite,plural") == "den Sachen",
          show("Sachen", "accusative,definite,plural") == "die Sachen",
        )
      },
      // The rule that keeps a form's inherited gender from doing harm: a `Word` on its own names no declension cell,
      // so showing the citation article would print `der Tisches` for a genitive. Bare is the honest answer there.
      test("a form shown without its cell is written bare; with its cell it wears the right article") {
        val lemma = Word(1L, WordLanguage.De, "Tisch", PartOfSpeech.Noun, Some(Gender.Masculine))
        val form  = Word(2L, WordLanguage.De, "Tisches", PartOfSpeech.Noun, Some(Gender.Masculine), isForm = true)
        assertTrue(
          Word.display(lemma) == "der Tisch",
          Word.display(form) == "Tisches",
          Word.displayIn(form, GrammarTag.slotOf("genitive,singular")) == "des Tisches",
          Word.displayIn(lemma, FormSlot.citation) == "der Tisch",
        )
      },
      // The reported bug, as data: `Nächte` reaches `Nacht` through four relations naming three cells, one of them the
      // genitive plural. Drawing among them asked `der Nächte` on one play in three. A pair records one reading, so
      // the nominative is the only defensible cell, and it is the same one on every play.
      test("a plural form is asked in the nominative, never in another case of the same number") {
        val nachte  = List(
          "accusative,definite,plural",
          "definite,genitive,plural",
          "definite,nominative,plural",
          "plural",
        )
        val profile = LanguageProfile.of(WordLanguage.De)
        val slot    = FormSlot.preferred(nachte.map(GrammarTag.slotOf).distinct)
        assertTrue(
          slot == FormSlot(GrammaticalCase.Nominative, GrammaticalNumber.Plural),
          profile.displayIn("Nächte", Some(Gender.Feminine), slot) == "die Nächte",
        )
      },
      // `Wege` carries the archaic dative singular `dem Wege` beside its three plural cells. A number-first preference
      // would pick that and ask a plural word in the singular; the case has to dominate the number.
      test("a form carrying an odd singular cell is still asked as the plural it is") {
        val wege    = List(
          "accusative,definite,plural",
          "dative,singular",
          "definite,genitive,plural",
          "definite,nominative,plural",
          "plural",
        )
        val profile = LanguageProfile.of(WordLanguage.De)
        val slot    = FormSlot.preferred(wege.map(GrammarTag.slotOf).distinct)
        assertTrue(
          slot == FormSlot(GrammaticalCase.Nominative, GrammaticalNumber.Plural),
          profile.displayIn("Wege", Some(Gender.Masculine), slot) == "die Wege",
        )
      },
      test("a form with no nominative cell keeps its own rather than being forced into one") {
        val profile = LanguageProfile.of(WordLanguage.De)
        val slot    = FormSlot.preferred(List("genitive,singular", "genitive,singular,weak").map(GrammarTag.slotOf))
        assertTrue(
          slot == FormSlot(GrammaticalCase.Genitive, GrammaticalNumber.Singular),
          profile.displayIn("Tisches", Some(Gender.Masculine), slot) == "des Tisches",
        )
      },
      test("a word standing in no cell at all is the citation cell") {
        assertTrue(FormSlot.preferred(Nil) == FormSlot.citation)
      },
      test("a German masculine noun declines its own way in the singular and the same way in the plural") {
        val profile = LanguageProfile.of(WordLanguage.De)

        def show(text: String, relation: String): String = {
          profile.displayIn(text, Some(Gender.Masculine), GrammarTag.slotOf(relation))
        }

        assertTrue(
          show("Tisch", "") == "der Tisch",
          show("Tisches", "genitive,singular") == "des Tisches",
          show("Tisch", "dative,singular") == "dem Tisch",
          show("Tisch", "accusative,singular") == "den Tisch",
          show("Tische", "plural") == "die Tische",
          show("Tischen", "dative,definite,plural") == "den Tischen",
        )
      },
    )
  }
}
