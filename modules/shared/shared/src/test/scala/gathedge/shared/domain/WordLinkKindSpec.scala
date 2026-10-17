package gathedge.shared.domain

import zio.test.*

object WordLinkKindSpec extends ZIOSpecDefault {

  import WordLinkKind.*

  def spec = {
    suite("WordLinkKind")(
      test("every kind has a code that reads back") {
        assertTrue(values.forall(kind => fromCode(code(kind)).contains(kind)), fromCode("cousin").isEmpty)
      },
      test("a diminutive and an augmentative read back as what they are of, and the other way round") {
        assertTrue(
          inverse(Diminutive, None) == DiminutiveOf,
          inverse(DiminutiveOf, Some(Gender.Neuter)) == Diminutive,
          inverse(Augmentative, None) == AugmentativeOf,
          inverse(AugmentativeOf, None) == Augmentative,
        )
      },
      test("a counterpart reads back as the base word's own gender, and guesses one only where it is not known") {
        assertTrue(
          inverse(Feminine, Some(Gender.Masculine)) == Masculine,
          inverse(Feminine, Some(Gender.Neuter)) == Neuter,
          inverse(Masculine, Some(Gender.Feminine)) == Feminine,
          inverse(Feminine, None) == Masculine,
          inverse(Masculine, None) == Feminine,
        )
      },
      test("a counterpart is offered for a noun only, and only of a gender its language has") {
        val all = List(Feminine, Masculine, Neuter, Diminutive, Augmentative, DiminutiveOf, AugmentativeOf)
        assertTrue(
          offered(WordLanguage.De, PartOfSpeech.Noun) == all,
          offered(WordLanguage.Es, PartOfSpeech.Noun) == all.filterNot(_ == Neuter),
          offered(WordLanguage.Hu, PartOfSpeech.Noun) == List(Diminutive, Augmentative, DiminutiveOf, AugmentativeOf),
          offered(WordLanguage.De, PartOfSpeech.Verb) == List(Diminutive, Augmentative, DiminutiveOf, AugmentativeOf),
        )
      },
    )
  }
}
