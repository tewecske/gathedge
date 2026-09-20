package gathedge.shared.domain

import zio.test.*

/** Every [[WordLanguage]] must have a profile that is internally consistent — its own display/strip round-trip, and its
  * parse table naming only genders it actually lists — so a language added to the enum without an entry here (or with a
  * broken one) fails at test time rather than showing a blank picker or the wrong article.
  */
object LanguageProfileSpec extends ZIOSpecDefault {

  def spec = {
    suite("LanguageProfile")(
      test("every WordLanguage has a profile whose article map matches its own gender list") {
        assertTrue(
          WordLanguage.all.forall { language =>
            val profile = LanguageProfile.of(language)
            profile.definiteArticles.keySet == profile.genders.toSet &&
            profile.articleForms.values.forall(profile.genders.contains)
          }
        )
      },
      test("a genderless language displays and strips as plain text") {
        val profile = LanguageProfile.of(WordLanguage.En)
        assertTrue(
          !profile.hasGenders,
          profile.display("dog", None) == "dog",
          profile.strip("the dog") == ("the dog", None),
        )
      },
      test("German has three genders and capitalizes its nouns") {
        val profile = LanguageProfile.of(WordLanguage.De)
        assertTrue(
          profile.genders.toSet == Set(Gender.Masculine, Gender.Feminine, Gender.Neuter),
          profile.display("Hund", Some(Gender.Masculine)) == "der Hund",
          profile.strip("der Hund") == ("Hund", Some(Gender.Masculine)),
          profile.strip("die Katze") == ("Katze", Some(Gender.Feminine)),
          profile.capitalize("hund", Some(Gender.Masculine)) == "Hund",
        )
      },
      test("Spanish has two genders, does not capitalize, and its strip recognises the plural articles too") {
        val profile = LanguageProfile.of(WordLanguage.Es)
        assertTrue(
          profile.genders.toSet == Set(Gender.Masculine, Gender.Feminine),
          profile.display("perro", Some(Gender.Masculine)) == "el perro",
          profile.display("casa", Some(Gender.Feminine)) == "la casa",
          profile.strip("los perros") == ("perros", Some(Gender.Masculine)),
          profile.strip("las casas") == ("casas", Some(Gender.Feminine)),
          profile.capitalize("perro", Some(Gender.Masculine)) == "perro",
        )
      },
      test("stripping a lone article with nothing after it leaves the text unchanged") {
        val profile = LanguageProfile.of(WordLanguage.De)
        assertTrue(profile.strip("der") == ("der", None))
      },
      // The declension table from the issue's own worked examples: `Sache` [feminine], `Tisch` [masculine] and
      // `Futter` [neuter]. Only the definite column, which is the only one a game ever shows.
      test("German declines the definite article by case, number and gender") {
        val profile = LanguageProfile.of(WordLanguage.De)

        def article(gender: Gender, c: GrammaticalCase, n: GrammaticalNumber): String = {
          profile.declinedArticle(gender, FormSlot(c, n)).getOrElse("")
        }

        val feminine  = GrammaticalCase.all.map(c => article(Gender.Feminine, c, GrammaticalNumber.Singular))
        val masculine = GrammaticalCase.all.map(c => article(Gender.Masculine, c, GrammaticalNumber.Singular))
        val neuter    = GrammaticalCase.all.map(c => article(Gender.Neuter, c, GrammaticalNumber.Singular))
        val plural    = GrammaticalCase.all.map(c => article(Gender.Feminine, c, GrammaticalNumber.Plural))

        assertTrue(
          feminine == List("die", "der", "der", "die"),
          masculine == List("der", "des", "dem", "den"),
          neuter == List("das", "des", "dem", "das"),
          plural == List("die", "der", "den", "die"),
        )
      },
      test("a German plural takes the same article whatever the noun's gender is") {
        val profile = LanguageProfile.of(WordLanguage.De)
        val cells   = GrammaticalCase.all.map(c => profile.articlesFor(FormSlot(c, GrammaticalNumber.Plural)))
        assertTrue(cells == List(List("die"), List("der"), List("den"), List("die")))
      },
      test("the citation cell is what display and the article list already answered") {
        val profile = LanguageProfile.of(WordLanguage.De)
        assertTrue(
          profile.article(Gender.Feminine).contains("die"),
          profile.displayIn("Sache", Some(Gender.Feminine), FormSlot.citation) == "die Sache",
          profile.displayIn("Sachen", Some(Gender.Feminine), FormSlot(GrammaticalCase.Dative, GrammaticalNumber.Plural))
            == "den Sachen",
          profile.articlesFor(FormSlot.citation) == List("der", "die", "das"),
        )
      },
      test("All offers every German article once; a dative singular offers two") {
        val profile = LanguageProfile.of(WordLanguage.De)
        assertTrue(
          profile.allArticles.toSet == Set("der", "die", "das", "des", "dem", "den"),
          profile.allArticles.size == 6,
          profile.articlesFor(FormSlot(GrammaticalCase.Dative, GrammaticalNumber.Singular)) == List("dem", "der"),
        )
      },
      test("a word with no gender takes no article in any cell") {
        val profile = LanguageProfile.of(WordLanguage.De)
        assertTrue(
          FormSlot.all.forall(slot => profile.displayIn("laufen", None, slot) == "laufen")
        )
      },
      test("Spanish declines for number only, since its articles ignore case") {
        val profile = LanguageProfile.of(WordLanguage.Es)
        assertTrue(
          GrammaticalCase.all.forall(c =>
            profile.declinedArticle(Gender.Masculine, FormSlot(c, GrammaticalNumber.Singular)).contains("el")
          ),
          GrammaticalCase.all.forall(c =>
            profile.declinedArticle(Gender.Feminine, FormSlot(c, GrammaticalNumber.Plural)).contains("las")
          ),
          profile.allArticles.toSet == Set("el", "la", "los", "las"),
        )
      },
      test("a genderless language has no declension table at all") {
        val profile = LanguageProfile.of(WordLanguage.Hu)
        assertTrue(
          profile.allArticles.isEmpty,
          FormSlot.all.forall(slot => profile.articlesFor(slot).isEmpty),
        )
      },
    )
  }
}
