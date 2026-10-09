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
      test(
        "German declines its definite article by case and number, Spanish by number; a German plural fits every gender"
      ) {
        val profile = LanguageProfile.of(WordLanguage.De)
        assertTrue(
          profile.declinedArticle(Some(Gender.Masculine), Set("genitive", "singular")) == Some("des"),
          profile.declinedArticle(Some(Gender.Feminine), Set("dative", "singular")) == Some("der"),
          profile.declinedArticle(Some(Gender.Neuter), Set("weak", "accusative", "singular")) == Some("das"),
          profile.declinedArticle(Some(Gender.Feminine), Set("dative", "plural")) == Some("den"),
          profile.declinedArticle(None, Set("nominative", "plural")) == Some("die"),
          profile.declinedArticle(None, Set("nominative", "singular")).isEmpty,
          LanguageProfile.of(WordLanguage.Es).declinedArticle(Some(Gender.Masculine), Set("singular")) == Some("el"),
          LanguageProfile.of(WordLanguage.En).declinedArticle(None, Set("singular")).isEmpty,
        )
      },
      test(
        "each verb language puts its subject pronoun before a person's form; German adds `dass` in a subordinate clause"
      ) {
        val firstSingular = Set("first-person", "singular", "present")
        val pronoun       = (language: WordLanguage) => {
          LanguageProfile.of(language).prefix(FormPrefix.Pronoun, None, firstSingular)
        }
        assertTrue(
          pronoun(WordLanguage.De) == Some("ich"),
          pronoun(WordLanguage.Hu) == Some("én"),
          pronoun(WordLanguage.Es) == Some("yo"),
          pronoun(WordLanguage.Fr) == Some("je"),
          pronoun(WordLanguage.Pt) == Some("eu"),
          pronoun(WordLanguage.En).isEmpty,
          LanguageProfile
            .of(WordLanguage.De)
            .prefix(FormPrefix.SubordinatePronoun, None, Set("third-person", "plural")) == Some("dass sie"),
          LanguageProfile.of(WordLanguage.Es).subjectPronoun(Set("second-person", "singular", "vos-form")) == Some(
            "vos"
          ),
          LanguageProfile.of(WordLanguage.Es).subjectPronoun(Set("second-person", "singular")) == Some("tú"),
          LanguageProfile.of(WordLanguage.De).subjectPronoun(Set("imperative", "singular")).isEmpty,
        )
      },
      test("French `je` elides before a vowel or `h`, and only the last word of a prefix does") {
        val french = LanguageProfile.of(WordLanguage.Fr)
        assertTrue(
          french.lead("je", "aime") == "j'",
          french.lead("je", "habite") == "j'",
          french.lead("je", "parle") == "je ",
          french.lead("tu", "aimes") == "tu ",
          french.lead("que je", "aime") == "que j'",
          LanguageProfile.of(WordLanguage.De).lead("ich", "esse") == "ich ",
        )
      },
      test("French keeps `je` and `le` whole before an aspirated h, and Spanish gives `el` to a stressed-a feminine") {
        val french  = LanguageProfile.of(WordLanguage.Fr)
        val spanish = LanguageProfile.of(WordLanguage.Es)
        assertTrue(
          french.lead("je", "hais", "haïr") == "je ",
          french.lead("je", "habite", "habiter") == "j'",
          french.lead("le", "héros", "héros") == "le ",
          french.lead("la", "heure", "heure") == "l'",
          french.lead("le", "yaourt", "yaourt") == "le ",
          spanish.declinedArticle(Some(Gender.Feminine), Set("singular"), "agua") == Some("el"),
          spanish.declinedArticle(Some(Gender.Feminine), Set("plural"), "agua") == Some("las"),
          spanish.declinedArticle(Some(Gender.Feminine), Set("singular"), "harina") == Some("la"),
          spanish.declinedArticle(Some(Gender.Feminine), Set("singular"), "átame") == Some("el"),
          spanish.declinedArticle(Some(Gender.Feminine), Set("singular"), "hache") == Some("la"),
          spanish.declinedArticle(Some(Gender.Feminine), Set("singular"), "árbitra") == Some("la"),
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
