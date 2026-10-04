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
      test("French has two genders, and its strip leaves the genderless plural and the elided article alone") {
        val profile = LanguageProfile.of(WordLanguage.Fr)
        assertTrue(
          profile.genders.toSet == Set(Gender.Masculine, Gender.Feminine),
          profile.display("chien", Some(Gender.Masculine)) == "le chien",
          profile.strip("la maison") == ("maison", Some(Gender.Feminine)),
          profile.strip("les chiens") == ("les chiens", None),
          profile.strip("l'homme") == ("l'homme", None),
          profile.capitalize("chien", Some(Gender.Masculine)) == "chien",
        )
      },
      test("Portuguese has two genders, and its strip recognises the plural articles too") {
        val profile = LanguageProfile.of(WordLanguage.Pt)
        assertTrue(
          profile.genders.toSet == Set(Gender.Masculine, Gender.Feminine),
          profile.display("cão", Some(Gender.Masculine)) == "o cão",
          profile.display("casa", Some(Gender.Feminine)) == "a casa",
          profile.strip("os cães") == ("cães", Some(Gender.Masculine)),
          profile.strip("as casas") == ("casas", Some(Gender.Feminine)),
          profile.capitalize("casa", Some(Gender.Feminine)) == "casa",
        )
      },
      test("stripping a lone article with nothing after it leaves the text unchanged") {
        val profile = LanguageProfile.of(WordLanguage.De)
        assertTrue(profile.strip("der") == ("der", None))
      },
    )
  }
}
