package gathedge.backend.db

import gathedge.backend.TestDataSource
import gathedge.shared.domain.{Gender, PartOfSpeech, WordLanguage}
import zio._
import zio.test._

object GameRepositorySpec extends ZIOSpecDefault {

  private val layer = TestDataSource.sqlite >>> (UserRepository.test ++ WordRepository.test ++ GameRepository.test)

  private def newUser(): RIO[UserRepository, Long] = UserRepository.insertGuest("light", "en", 0L, None).map(_.id)

  /** A dictionary word, shaped like `GameServiceSpec.dictionaryWord` — the distractor cases below need real `words`
    * rows to read back by id and by text.
    */
  private def dictionaryWord(language: WordLanguage, text: String, gender: Option[Gender] = None): WordRow = {
    WordRow(
      id = 0L,
      language = WordLanguage.code(language),
      text = text,
      textNorm = text.toLowerCase,
      partOfSpeech = PartOfSpeech.code(PartOfSpeech.Noun),
      gender = Gender.toColumn(gender),
      frequencyRank = 1,
      source = "dictionary",
      createdBy = None,
      createdAt = 0L,
      textSearch = TextSearch.fold(text.toLowerCase),
    )
  }

  def spec = {
    suite("GameRepository")(
      test("answers only the given player's answers, in the given direction, for the given game") {
        for {
          owner            <- newUser()
          other            <- newUser()
          game             <-
            GameRepository.insertGame(GameRow(0L, owner.toLong, "repo-slug", "Repo Game", "de", "hu", 0L, 0L), Nil)
          otherGame        <-
            GameRepository.insertGame(GameRow(0L, owner.toLong, "repo-slug-2", "Repo Game 2", "de", "hu", 0L, 0L), Nil)
          play             <- GameRepository.insertPlay(
                                GamePlayRow(
                                  0L,
                                  game.id,
                                  owner,
                                  0,
                                  2,
                                  1,
                                  0L,
                                  None,
                                  sourceLanguage = "de",
                                  targetLanguage = "hu",
                                ),
                                Nil,
                              )
          reverse          <- GameRepository.insertPlay(
                                GamePlayRow(
                                  0L,
                                  game.id,
                                  owner,
                                  0,
                                  2,
                                  1,
                                  0L,
                                  None,
                                  sourceLanguage = "hu",
                                  targetLanguage = "de",
                                ),
                                Nil,
                              )
          otherPlayersPlay <-
            GameRepository.insertPlay(
              GamePlayRow(0L, game.id, other, 0, 2, 1, 0L, None, sourceLanguage = "de", targetLanguage = "hu"),
              Nil,
            )
          // Same owner, same word, same direction as `play` above, but under `otherGame` — exercises the
          // `gameId` filter's exclusion for real: if it were broken, this answer's "wrong" outcome would show up
          // alongside `play`'s "correct" one in `deRows` below, for the same word id.
          otherGamePlay    <-
            GameRepository.insertPlay(
              GamePlayRow(0L, otherGame.id, owner, 0, 2, 1, 0L, None, sourceLanguage = "de", targetLanguage = "hu"),
              Nil,
            )
          _                <- GameRepository.recordAnswer(
                                GamePlayAnswerRow(0L, play.id, 1L, 2L, 1, "x", "correct", 2, 0L),
                                2,
                                Some(0L),
                              )
          _                <- GameRepository.recordAnswer(
                                GamePlayAnswerRow(0L, reverse.id, 1L, 2L, 1, "y", "wrong", 0, 0L),
                                0,
                                Some(0L),
                              )
          _                <- GameRepository.recordAnswer(
                                GamePlayAnswerRow(0L, otherPlayersPlay.id, 1L, 2L, 1, "z", "wrong", 0, 0L),
                                0,
                                Some(0L),
                              )
          _                <- GameRepository.recordAnswer(
                                GamePlayAnswerRow(0L, otherGamePlay.id, 1L, 2L, 1, "w", "wrong", 0, 0L),
                                0,
                                Some(0L),
                              )
          deRows           <- GameRepository.answerOutcomesFor(game.id, owner, "de", "hu")
          huRows           <- GameRepository.answerOutcomesFor(game.id, owner, "hu", "de")
        } yield assertTrue(
          deRows == List((1L, "correct")),
          huRows == List((1L, "wrong")),
        )
      },
      test("relatedWords answers both directions of word_forms, and nothing unlinked") {
        for {
          lemma     <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "related-Hund", Some(Gender.Masculine)))
          plural    <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "related-Hunde", Some(Gender.Feminine)))
          unlinked  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "related-Katze", Some(Gender.Feminine)))
          _         <- WordRepository.insertForms(List(WordFormRow(0L, lemma.id, plural.id, "plural", 0L)))
          fromLemma <- GameRepository.relatedWords(List(lemma.id))
          fromForm  <- GameRepository.relatedWords(List(plural.id))
          fromOther <- GameRepository.relatedWords(List(unlinked.id))
          fromNone  <- GameRepository.relatedWords(Nil)
        } yield assertTrue(
          fromLemma.map(_.id) == List(plural.id),
          fromForm.map(_.id) == List(lemma.id),
          fromOther.isEmpty,
          fromNone.isEmpty,
        )
      },
      test("wordsByTexts answers every gendered spelling of a word, in that language only") {
        for {
          der     <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "texts-See", Some(Gender.Masculine)))
          die     <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "texts-See", Some(Gender.Feminine)))
          _       <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "texts-See"))
          german  <- GameRepository.wordsByTexts("de", List("texts-See"))
          missing <- GameRepository.wordsByTexts("de", List("texts-nothing"))
          none    <- GameRepository.wordsByTexts("de", Nil)
        } yield assertTrue(
          german.map(_.id).toSet == Set(der.id, die.id),
          german.map(_.gender).toSet == Set("masculine", "feminine"),
          missing.isEmpty,
          none.isEmpty,
        )
      },
    ).provide(layer)
  }
}
