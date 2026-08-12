package gathedge.backend.service

import gathedge.backend.TestDataSource
import gathedge.backend.db.{WordRepository, WordRow}
import gathedge.shared.domain.{Gender, PartOfSpeech, WordLanguage}
import gathedge.shared.dto.{CreateWordRequest, NewTranslation, Paging, WordSort}
import zio._
import zio.test._

/** The vocabulary against SQLite, which is where everything but referential integrity is exercised.
  *
  * The service takes an `Option[Long]` reader throughout, and half of what is worth asserting here is what happens when
  * it is `None`: a visitor with no session sees the same words and none of the tag marks.
  */
object WordServiceSpec extends ZIOSpecDefault {

  private val layer = (TestDataSource.sqlite >>> WordRepository.test) >+> WordService.live

  /** A dictionary row, as the importer would write it: no author, and a rank that decides where it lands in a search.
    */
  private def dictionaryWord(
    language: WordLanguage,
    text: String,
    pos: PartOfSpeech = PartOfSpeech.Noun,
    gender: Option[Gender] = None,
    rank: Int = 1,
  ): WordRow = {
    WordRow(
      id = 0L,
      language = WordLanguage.code(language),
      text = text,
      textNorm = text.toLowerCase,
      partOfSpeech = PartOfSpeech.code(pos),
      gender = Gender.toColumn(gender),
      frequencyRank = rank,
      source = WordService.dictionarySource,
      createdBy = None,
      createdAt = 0L,
    )
  }

  private def seed: RIO[WordRepository, Unit] = {
    for {
      haus <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Haus", gender = Some(Gender.Das), rank = 1))
      haz  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "ház", rank = 1))
      hau  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Haufen", gender = Some(Gender.Der), rank = 900))
      _    <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "hauen", pos = PartOfSpeech.Verb, rank = 500))
      _    <- WordRepository.insertTranslationPair(haus.id, haz.id, WordService.dictionaryOrigin, None, 0L)
      _     = hau
    } yield ()
  }

  private def list(
    search: Option[String] = None,
    reader: Option[Long] = None,
    tagId: Option[Long] = None,
    mine: Boolean = false,
  ) = {
    WordService.list(
      page = Paging.firstPage,
      pageSize = 20,
      language = Some(WordLanguage.De),
      search = search,
      partOfSpeech = None,
      tagId = tagId,
      mine = mine,
      target = WordLanguage.Hu,
      sort = None,
      descending = false,
      reader = reader,
    )
  }

  def spec = {
    suite("WordService (SQLite)")(
      test("a search is a prefix of the word, commonest first, and carries its translations") {
        for {
          _    <- seed
          page <- list(search = Some("hau"))
        } yield assertTrue(
          page.total == 3L,
          // Rank decides the order, which is what makes a two-letter search useful.
          page.items.map(_.word.text) == List("Haus", "hauen", "Haufen"),
          page.items.head.translations == List("ház"),
          // No reader, so no tags — and no failure either.
          page.items.forall(_.tagIds.isEmpty),
        )
      },
      test("a German noun keeps its article, and two words differing only by it are two words") {
        for {
          lake <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "See", gender = Some(Gender.Der)))
          sea  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "See", gender = Some(Gender.Die)))
          page <- list(search = Some("see"))
        } yield assertTrue(
          lake.id != sea.id,
          page.total == 2L,
          page.items.flatMap(_.word.gender).toSet == Set(Gender.Der, Gender.Die),
        )
      },
      test("adding a word that already exists answers the existing one, with everybody's translations on it") {
        for {
          _      <- seed
          before <- list(search = Some("haus"))
          detail <- WordService.create(
                      CreateWordRequest(
                        WordLanguage.De,
                        "Haus",
                        PartOfSpeech.Noun,
                        Some(Gender.Das),
                        List(NewTranslation(WordLanguage.Hu, "otthon", None, None)),
                        Nil,
                      ),
                      userId = 7L,
                    )
          after  <- list(search = Some("haus"))
        } yield assertTrue(
          // One row before and one after: the word was found, not created a second time.
          before.total == 1L,
          after.total == 1L,
          detail.word.id == before.items.head.word.id,
          // The dictionary's translation and the caller's, in that order.
          detail.translations.map(_.word.text) == List("ház", "otthon"),
          detail.translations.map(_.origin) == List(WordService.dictionaryOrigin, WordService.userOrigin),
        )
      },
      test("the same translation twice from the same reader is a conflict; from another reader it is not") {
        for {
          _       <- seed
          word    <- list(search = Some("haus")).map(_.items.head.word)
          _       <- WordService.addTranslation(word.id, NewTranslation(WordLanguage.Hu, "otthon", None, None), 1L)
          again   <- WordService.addTranslation(word.id, NewTranslation(WordLanguage.Hu, "otthon", None, None), 1L).either
          another <- WordService.addTranslation(word.id, NewTranslation(WordLanguage.Hu, "otthon", None, None), 2L)
        } yield assertTrue(
          again == Left(WordFailure.DuplicateTranslation),
          another.translations.count(_.word.text == "otthon") == 2,
        )
      },
      test("tagging is idempotent, and untagging something untagged is nothing to do") {
        for {
          _     <- seed
          tag   <- WordService.createTag("lesson1", 1L)
          word  <- list(search = Some("haus")).map(_.items.head.word)
          _     <- WordService.tagWord(word.id, tag.id, 1L)
          _     <- WordService.tagWord(word.id, tag.id, 1L)
          after <- list(reader = Some(1L))
          _     <- WordService.untagWord(word.id, tag.id, 1L)
          _     <- WordService.untagWord(word.id, tag.id, 1L)
          gone  <- list(reader = Some(1L))
          tags  <- WordService.listTags(1L)
        } yield assertTrue(
          after.items.count(_.tagIds.contains(tag.id)) == 1,
          gone.items.forall(_.tagIds.isEmpty),
          tags.map(_.name) == List("lesson1"),
        )
      },
      test("somebody else's tag answers the same as one that does not exist") {
        for {
          _      <- seed
          tag    <- WordService.createTag("theirs", 1L)
          word   <- list(search = Some("haus")).map(_.items.head.word)
          denied <- WordService.tagWord(word.id, tag.id, 2L).either
          absent <- WordService.tagWord(word.id, 9999L, 2L).either
        } yield assertTrue(denied == Left(WordFailure.TagNotFound), absent == Left(WordFailure.TagNotFound))
      },
      test("a tag name is unique per account, case-insensitively, and may not be one the practice screen reserves") {
        for {
          _         <- WordService.createTag("Lesson1", 1L)
          duplicate <- WordService.createTag("lesson1", 1L).either
          otherUser <- WordService.createTag("lesson1", 2L).either
          reserved  <- WordService.createTag("all_unknown", 1L).either
          blank     <- WordService.createTag("   ", 1L).either
        } yield assertTrue(
          duplicate == Left(WordFailure.DuplicateTag),
          otherUser.isRight,
          reserved.isLeft,
          blank.isLeft,
        )
      },
      test("'only mine' with no session is an empty answer rather than the whole dictionary") {
        for {
          _         <- seed
          anonymous <- list(mine = true)
          reader    <- list(mine = true, reader = Some(1L))
        } yield assertTrue(anonymous.total == 0L, anonymous.items.isEmpty, reader.total == 0L)
      },
      test("only what the reader tagged is theirs, whoever else tagged it") {
        for {
          _     <- seed
          mine  <- WordService.createTag("mine", 1L)
          yours <- WordService.createTag("yours", 2L)
          words <- list().map(_.items.map(_.word.id))
          _     <- WordService.tagWord(words.head, mine.id, 1L)
          _     <- WordService.tagWord(words(1), yours.id, 2L)
          first <- list(mine = true, reader = Some(1L))
          other <- list(mine = true, reader = Some(2L))
        } yield assertTrue(
          first.items.map(_.word.id) == List(words.head),
          other.items.map(_.word.id) == List(words(1)),
        )
      },
      test("a word that is not there is a NotFound rather than an empty detail") {
        for {
          missing <- WordService.detail(4242L, None).either
        } yield assertTrue(missing == Left(WordFailure.NotFound))
      },
      test("a reader may remove their own translation and nobody else's") {
        for {
          _       <- seed
          word    <- list(search = Some("haus")).map(_.items.head.word)
          detail  <- WordService.addTranslation(word.id, NewTranslation(WordLanguage.Hu, "lak", None, None), 1L)
          own      = detail.translations.find(_.word.text == "lak").map(_.id).getOrElse(0L)
          imported = detail.translations.find(_.word.text == "ház").map(_.id).getOrElse(0L)
          theirs  <- WordService.removeTranslation(word.id, own, 2L).either
          dict    <- WordService.removeTranslation(word.id, imported, 1L).either
          _       <- WordService.removeTranslation(word.id, own, 1L)
          after   <- WordService.detail(word.id, Some(1L))
        } yield assertTrue(
          theirs == Left(WordFailure.NotFound),
          dict == Left(WordFailure.NotFound),
          after.translations.map(_.word.text) == List("ház"),
        )
      },
      test("an unknown sort column falls back to the listing's own order rather than failing") {
        for {
          _       <- seed
          unknown <- WordService.list(
                       page = Paging.firstPage,
                       pageSize = 20,
                       language = Some(WordLanguage.De),
                       search = None,
                       partOfSpeech = None,
                       tagId = None,
                       mine = false,
                       target = WordLanguage.Hu,
                       sort = Some("nonsense"),
                       descending = false,
                       reader = None,
                     )
          byText  <- WordService.list(
                       page = Paging.firstPage,
                       pageSize = 20,
                       language = Some(WordLanguage.De),
                       search = None,
                       partOfSpeech = None,
                       tagId = None,
                       mine = false,
                       target = WordLanguage.Hu,
                       sort = Some(WordSort.text),
                       descending = true,
                       reader = None,
                     )
        } yield assertTrue(
          unknown.items.map(_.word.text) == List("Haus", "hauen", "Haufen"),
          byText.items.map(_.word.text).headOption.contains("Haus"),
        )
      },
    ).provide(layer) @@ TestAspect.timeout(60.seconds)
  }
}
