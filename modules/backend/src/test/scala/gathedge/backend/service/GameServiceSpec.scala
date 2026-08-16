package gathedge.backend.service

import gathedge.backend.TestDataSource
import gathedge.backend.db.{GameRepository, UserRepository, WordRepository, WordRow}
import gathedge.shared.domain.{Gender, PartOfSpeech, WordLanguage}
import zio._
import zio.test._

/** The game service against SQLite, the same dialect every other `*ServiceSpec` runs against per the dual-dialect
  * strategy. Referential integrity is real here (unlike `WordServiceSpec`, which never needs a `users` row):
  * `GameRepository.insertGame` reads back its own `RETURNING id`/`GENERATED` value and `games.owner_user_id` is
  * exercised for real by [[UserRepository.insertGuest]]-minted rows.
  */
object GameServiceSpec extends ZIOSpecDefault {

  /** Deliberately tiny — four possible `adjective-noun` combinations — so the collision-retry and numeric-fallback
    * paths in [[GameServiceLive]] are actually reachable by a test, not just trusted by inspection.
    */
  private val fixedWordList = GameWordListLive(List("brave", "calm"), List("otter", "fox"))

  private val layer = {
    (TestDataSource.sqlite >>> (WordRepository.test ++ UserRepository.test ++ GameRepository.test)) ++
      ZLayer.succeed(fixedWordList: GameWordList) >+> GameService.live
  }

  private def newUser(): RIO[UserRepository, Long] = UserRepository.insertGuest("light", "en", 0L).map(_.id)

  private def dictionaryWord(language: WordLanguage, text: String, rank: Int = 1): WordRow = {
    WordRow(
      id = 0L,
      language = WordLanguage.code(language),
      text = text,
      textNorm = text.toLowerCase,
      partOfSpeech = PartOfSpeech.code(PartOfSpeech.Noun),
      gender = Gender.toColumn(None),
      frequencyRank = rank,
      source = WordService.dictionarySource,
      createdBy = None,
      createdAt = 0L,
    )
  }

  /** A tag owned by `ownerId`, carrying one marked pair translating `sourceLanguage` into `targetLanguage` — the shape
    * [[GameRepository.eligibleTags]] looks for.
    */
  private def eligibleTag(
    ownerId: Long,
    name: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
  ): RIO[WordRepository, Long] = {
    for {
      tag    <- WordRepository.insertTag(ownerId, name, name, 0L)
      source <- WordRepository.ensureWord(dictionaryWord(sourceLanguage, s"$name-source"))
      target <- WordRepository.ensureWord(dictionaryWord(targetLanguage, s"$name-target"))
      _      <- WordRepository.pairTranslation(source.id, tag.id, target.id, 0L)
    } yield tag.id
  }

  def spec = {
    suite("GameService")(
      test("eligible tags are only the ones with a pair spanning both languages, own tag first") {
        // `WordRepository.pairTranslation` writes both directions of a mark, so a tag eligible for
        // German -> Hungarian is automatically eligible for Hungarian -> German too — there is no way to
        // build a tag that is eligible one way round and not the other. What actually excludes a tag is a
        // pair that doesn't span both requested languages at all, which is what "wrongLanguage" is.
        for {
          owner  <- newUser()
          other  <- newUser()
          mine   <- eligibleTag(owner, "mine", WordLanguage.De, WordLanguage.Hu)
          theirs <- eligibleTag(other, "theirs", WordLanguage.De, WordLanguage.Hu)
          _      <- eligibleTag(owner, "wrongLanguage", WordLanguage.De, WordLanguage.En)
          tags   <- GameService.eligibleTags(WordLanguage.De, WordLanguage.Hu, owner)
        } yield assertTrue(
          tags.map(_.id).toSet == Set(mine, theirs),
          tags.head.id == mine,
          tags.head.ownedByMe,
        )
      },
      test("creating a game with an eligible tag succeeds and its tag round-trips") {
        for {
          owner  <- newUser()
          tagId  <- eligibleTag(owner, "lesson1", WordLanguage.De, WordLanguage.Hu)
          detail <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
        } yield assertTrue(
          Set("brave-otter", "brave-fox", "calm-otter", "calm-fox").contains(detail.slug),
          detail.tagNames == List("lesson1"),
          detail.sourceLanguage == WordLanguage.De,
          detail.targetLanguage == WordLanguage.Hu,
        )
      },
      test("creating a game with a tag not eligible for the language pair fails") {
        for {
          owner  <- newUser()
          tagId  <- eligibleTag(owner, "wrongLanguage", WordLanguage.De, WordLanguage.En)
          result <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId)).either
        } yield assertTrue(result == Left(GameFailure.TagNotEligible))
      },
      test("creating a game with no tags fails") {
        for {
          owner  <- newUser()
          result <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, Nil).either
        } yield assertTrue(result == Left(GameFailure.NoTagsSelected))
      },
      test("once every adjective-noun combination is taken, a new game still gets a slug, with a numeric suffix") {
        for {
          owner <- newUser()
          tagId <- eligibleTag(owner, "lesson1", WordLanguage.De, WordLanguage.Hu)
          create = GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          first <- ZIO.foreach(1 to 4)(_ => create)
          fifth <- create
        } yield assertTrue(
          first.map(_.slug).toSet.size == 4,
          fifth.slug.matches("(brave|calm)-(otter|fox)-\\d+"),
        )
      },
      test("an unknown slug is not found") {
        for {
          result <- GameService.getBySlug("no-such-game").either
        } yield assertTrue(result == Left(GameFailure.NotFound))
      },
      test("a known slug answers its languages and tag names") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTag(owner, "lesson1", WordLanguage.De, WordLanguage.Hu)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          found   <- GameService.getBySlug(created.slug)
        } yield assertTrue(found == created)
      },
      test("only the owner may rename a game") {
        for {
          owner   <- newUser()
          other   <- newUser()
          tagId   <- eligibleTag(owner, "lesson1", WordLanguage.De, WordLanguage.Hu)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          blocked <- GameService.rename(created.slug, "New name", other).either
          renamed <- GameService.rename(created.slug, "New name", owner)
        } yield assertTrue(
          blocked == Left(GameFailure.NotOwner),
          renamed.name == "New name",
          renamed.slug == created.slug,
        )
      },
      test("renaming to a blank name fails validation") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTag(owner, "lesson1", WordLanguage.De, WordLanguage.Hu)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          result  <- GameService.rename(created.slug, "   ", owner).either
        } yield assertTrue(result.left.exists(_.isInstanceOf[GameFailure.ValidationError]))
      },
    ).provide(layer)
  }
}
