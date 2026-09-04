package gathedge.shared.parsing

import gathedge.shared.domain.{Gender, WordLanguage}
import zio.test.*

/** The collisions between four languages' abbreviations are the whole reason [[MarkerVocabulary.forPair]] exists, so
  * they are what this pins. Getting German's `w`/`s` wrong misreads gender on half of every German-written list.
  */
object MarkerVocabularySpec extends ZIOSpecDefault {

  private val germanFirst    = MarkerVocabulary.forPair(WordLanguage.De, WordLanguage.Hu)
  private val hungarianFirst = MarkerVocabulary.forPair(WordLanguage.Hu, WordLanguage.De)
  private val englishFirst   = MarkerVocabulary.forPair(WordLanguage.En, WordLanguage.De)

  def spec = {
    suite("MarkerVocabulary")(
      test("German's own abbreviations win on a German column") {
        // `w` is weiblich and `s` is sächlich. An English-only table would leave both unknown and read `f` instead.
        assertTrue(
          germanFirst.gender("w").contains(Gender.Feminine),
          germanFirst.gender("s").contains(Gender.Neuter),
          germanFirst.gender("m").contains(Gender.Masculine),
          germanFirst.gender("n").contains(Gender.Neuter),
        )
      },
      test("German still understands `f`, which it also writes as `feminin`") {
        assertTrue(germanFirst.gender("f").contains(Gender.Feminine))
      },
      test("`n` is neuter on a German column but feminine on a Hungarian one") {
        // German `n` is Neutrum; Hungarian `nn` is nőnem. The bare letters would collide if precedence did not decide.
        assertTrue(
          germanFirst.gender("n").contains(Gender.Neuter),
          hungarianFirst.gender("nn").contains(Gender.Feminine),
          hungarianFirst.gender("hn").contains(Gender.Masculine),
          hungarianFirst.gender("sn").contains(Gender.Neuter),
        )
      },
      test("a Hungarian abbreviation is understood on a German column") {
        // The case that motivates the whole design: a Hungarian learner marks a German word `hn`.
        assertTrue(
          germanFirst.gender("hn").contains(Gender.Masculine),
          germanFirst.gender("nn").contains(Gender.Feminine),
        )
      },
      test("a German abbreviation is understood on an English column") {
        assertTrue(englishFirst.gender("w").contains(Gender.Feminine))
      },
      test("a language neither side speaks still resolves through the last tier") {
        // Spanish is in neither pairing above, but `masculino` must not be a mystery.
        assertTrue(germanFirst.gender("masculino").contains(Gender.Masculine))
      },
      test("the symbolic government notation resolves under every pairing") {
        assertTrue(
          List(germanFirst, hungarianFirst, englishFirst).forall { markers =>
            markers.relation("g").contains("genitive") &&
            markers.relation("d").contains("dative") &&
            markers.relation("a").contains("accusative")
          }
        )
      },
      test("relations canonicalise to the word_forms vocabulary") {
        assertTrue(
          germanFirst.relation("dativ").contains("dative"),
          germanFirst.relation("genitiv").contains("genitive"),
          germanFirst.relation("akk").contains("accusative"),
          germanFirst.relation("es").contains("third-person"),
          hungarianFirst.relation("tsz").contains("plural"),
          hungarianFirst.relation("birtokos").contains("genitive"),
          englishFirst.relation("3rd").contains("third-person"),
        )
      },
      test("a trailing abbreviation dot is trimmed") {
        assertTrue(
          germanFirst.relation("Gen.").contains("genitive"),
          germanFirst.gender("M.").contains(Gender.Masculine),
        )
      },
      test("an unknown token is known to nobody") {
        assertTrue(
          !germanFirst.knows("Hund"),
          !germanFirst.knows(""),
          germanFirst.gender("Hund").isEmpty,
          germanFirst.relation("Hund").isEmpty,
        )
      },
      test("every language has an entry, and every relation it names is a GrammarTag the app can render") {
        // `Other` is a legitimate GrammarCategory, but a relation this file mints should never fall to it — that would
        // mean inventing a tag the rest of the app does not group or translate.
        import gathedge.shared.domain.{GrammarCategory, GrammarTag}
        assertTrue(
          WordLanguage.all.forall { language =>
            val vocabulary = MarkerVocabulary.of(language)
            vocabulary.relations.values.forall(GrammarTag.categoryOf(_) != GrammarCategory.Other)
          }
        )
      },
    )
  }
}
