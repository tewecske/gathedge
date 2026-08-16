package gathedge.shared.domain

import zio.test.*

object GameScoringSpec extends ZIOSpecDefault {

  def spec = {
    suite("GameScoring")(
      suite("levenshtein")(
        test("identical strings are zero apart") {
          assertTrue(GameScoring.levenshtein("hello", "hello") == 0)
        },
        test("an empty string is as far as the other string is long") {
          assertTrue(GameScoring.levenshtein("", "abc") == 3, GameScoring.levenshtein("abc", "") == 3)
        },
        test("one insertion, deletion, or substitution is distance one") {
          assertTrue(
            GameScoring.levenshtein("haus", "hause") == 1, // insertion
            GameScoring.levenshtein("hause", "haus") == 1, // deletion
            GameScoring.levenshtein("haus", "hous") == 1,  // substitution
          )
        },
        test("unrelated words are more than one apart") {
          assertTrue(GameScoring.levenshtein("haus", "katze") > 1)
        },
      ),
      suite("score")(
        test("an exact match is worth the maximum") {
          assertTrue(GameScoring.score("Haus", "Haus") == ScoredAnswer(AnswerOutcome.Correct, 2))
        },
        test("case differences alone still count as an exact match") {
          assertTrue(GameScoring.score("Haus", "haus") == ScoredAnswer(AnswerOutcome.Correct, 2))
        },
        test("surrounding whitespace alone still counts as an exact match") {
          assertTrue(GameScoring.score("Haus", "  haus  ") == ScoredAnswer(AnswerOutcome.Correct, 2))
        },
        test("a one-letter insertion typo is worth one point") {
          assertTrue(GameScoring.score("Haus", "Hause") == ScoredAnswer(AnswerOutcome.Typo, 1))
        },
        test("a one-letter deletion typo is worth one point") {
          assertTrue(GameScoring.score("Haus", "Hau") == ScoredAnswer(AnswerOutcome.Typo, 1))
        },
        test("a one-letter substitution typo is worth one point") {
          assertTrue(GameScoring.score("Haus", "Haus".replace('H', 'K')) == ScoredAnswer(AnswerOutcome.Typo, 1))
        },
        test("an unrelated word is worth nothing") {
          assertTrue(GameScoring.score("Haus", "Katze") == ScoredAnswer(AnswerOutcome.Wrong, 0))
        },
      ),
    )
  }
}
