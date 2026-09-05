package gathedge.backend.service

import gathedge.backend.TestDataSource
import gathedge.backend.db.{
  GameRepository,
  GroupRepository,
  TextSearch,
  UserRepository,
  WordFormRow,
  WordRepository,
  WordRow,
}
import gathedge.shared.domain.{AnswerOutcome, GameMode, Gender, PartOfSpeech, WordLanguage, WordPreference}
import gathedge.shared.dto.AllGameSort
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
    (TestDataSource.sqlite >>> (WordRepository.test ++ UserRepository.test ++ GameRepository.test ++
      GroupRepository.test)) ++
      ZLayer.succeed(fixedWordList: GameWordList) >+> GameService.live
  }

  private def newUser(): RIO[UserRepository, Long] = UserRepository.insertGuest("light", "en", 0L, None).map(_.id)

  private def dictionaryWord(
    language: WordLanguage,
    text: String,
    rank: Int = 1,
    gender: Option[Gender] = None,
    partOfSpeech: PartOfSpeech = PartOfSpeech.Noun,
  ): WordRow = {
    WordRow(
      id = 0L,
      language = WordLanguage.code(language),
      text = text,
      textNorm = text.toLowerCase,
      partOfSpeech = PartOfSpeech.code(partOfSpeech),
      gender = Gender.toColumn(gender),
      frequencyRank = rank,
      source = WordService.dictionarySource,
      createdBy = None,
      createdAt = 0L,
      textSearch = TextSearch.fold(text.toLowerCase),
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
      tag    <- WordRepository.insertTag(ownerId, name, name, 0L, "de", "hu")
      source <- WordRepository.ensureWord(dictionaryWord(sourceLanguage, s"$name-source"))
      target <- WordRepository.ensureWord(dictionaryWord(targetLanguage, s"$name-target"))
      _      <- WordRepository.pairTranslation(source.id, tag.id, target.id, 0L)
    } yield tag.id
  }

  /** A tag owned by `ownerId` carrying `count` marked pairs, named `$name-source-$i` -> `$name-target-$i` for `i` in
    * `0 until count` — enough eligible words for a real play-through, with the index recoverable from the prompt text
    * alone so a test can decide what to answer without a second lookup.
    */
  private def eligibleTagWithPairs(
    ownerId: Long,
    name: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    count: Int,
  ): RIO[WordRepository, Long] = {
    for {
      tag <- WordRepository.insertTag(ownerId, name, name, 0L, "de", "hu")
      _   <- ZIO.foreachDiscard(0 until count) { i =>
               for {
                 source <- WordRepository.ensureWord(dictionaryWord(sourceLanguage, s"$name-source-$i"))
                 target <- WordRepository.ensureWord(dictionaryWord(targetLanguage, s"$name-target-$i"))
                 _      <- WordRepository.pairTranslation(source.id, tag.id, target.id, 0L)
               } yield ()
             }
    } yield tag.id
  }

  /** A tag owned by `ownerId` carrying `count` marked pairs whose source words differ but whose target word is the same
    * (`$name-target`) — the collision a play draws through untouched, limited or not. Sources are
    * `$name-source-0`..`$name-source-${count - 1}`.
    */
  private def eligibleTagWithCollidingTarget(
    ownerId: Long,
    name: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    count: Int = 2,
  ): RIO[WordRepository, Long] = {
    for {
      tag    <- WordRepository.insertTag(ownerId, name, name, 0L, "de", "hu")
      target <- WordRepository.ensureWord(dictionaryWord(targetLanguage, s"$name-target"))
      _      <- ZIO.foreachDiscard(0 until count) { i =>
                  for {
                    source <- WordRepository.ensureWord(dictionaryWord(sourceLanguage, s"$name-source-$i"))
                    _      <- WordRepository.pairTranslation(source.id, tag.id, target.id, 0L)
                  } yield ()
                }
    } yield tag.id
  }

  /** Plays `playId` to completion, answering each prompt per the scenario its index (the trailing digit of its source
    * text, per [[eligibleTagWithPairs]]) selects: index 0 answers exactly, index 1 with a one-letter typo, index 2 (and
    * any beyond) wrong. Returns the scenario picked for each prompt answered, in the order they came back — the random
    * order [[GameService.nextPrompt]] itself picks, not the tag's insertion order.
    */
  private def playThrough(
    playId: Long,
    tagName: String,
    userId: Long,
    acc: List[Int] = Nil,
  ): ZIO[GameService, GameFailure, List[Int]] = {
    GameService.nextPrompt(playId, userId).flatMap { prompt =>
      if (prompt.finished)
        ZIO.succeed(acc)
      else {
        val wordText = prompt.wordText.get
        val index    = wordText.stripPrefix(s"$tagName-source-").toInt
        val expected = s"$tagName-target-$index"
        val scenario = index % 3
        val answer   = scenario match {
          case 0 => expected
          case 1 => expected + "x"
          case _ => "totally-unrelated"
        }
        GameService.submitAnswer(playId, prompt.wordId.get, answer, userId) *>
          playThrough(playId, tagName, userId, acc :+ scenario)
      }
    }
  }

  /** Answers the play's next prompt, wrong when it is `wrongFor` and correct otherwise — the words are
    * `$tagName-source-$i`/`$tagName-target-$i`, the pairs [[eligibleTagWithPairs]] makes. The branch is what puts a
    * mistake against one named word without knowing which word the sample drew first.
    */
  private def answerOnce(
    playId: Long,
    tagName: String,
    userId: Long,
    wrongFor: String,
  ): ZIO[GameService, GameFailure, Unit] = {
    GameService.nextPrompt(playId, userId).flatMap { prompt =>
      val wordText = prompt.wordText.get
      val index    = wordText.stripPrefix(s"$tagName-source-")
      val answer   = {
        if (wordText == wrongFor)
          "totally-unrelated"
        else
          s"$tagName-target-$index"
      }
      GameService.submitAnswer(playId, prompt.wordId.get, answer, userId).unit
    }
  }

  /** Marks `src -> tgt` (both directions, per `WordRepository.pairTranslation`) inside `tagId`. The building block for
    * the "same word under more than one tag" cases, where the helpers above — each assuming a single tag — do not fit.
    */
  private def markPair(tagId: Long, src: WordRow, tgt: WordRow): RIO[WordRepository, Unit] =
    WordRepository.pairTranslation(src.id, tagId, tgt.id, 0L).unit

  /** Answers every remaining prompt of `playId` with the same text, until the play reports finished. Unlike
    * [[playThrough]] it does not vary the answer by prompt — the combined-tags cases below fix one correct answer and
    * check it scores for every prompt.
    */
  private def answerEach(playId: Long, userId: Long, answer: String): ZIO[GameService, GameFailure, Unit] = {
    GameService.nextPrompt(playId, userId).flatMap { prompt =>
      if (prompt.finished)
        ZIO.unit
      else
        GameService.submitAnswer(playId, prompt.wordId.get, answer, userId) *> answerEach(playId, userId, answer)
    }
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
          detail.tags.map(_.name) == List("lesson1"),
          detail.tags.forall(_.id == tagId),
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
      test("allGames answers every account's games, with tag names and play counts") {
        // The listing is no longer owner-scoped, so other tests' games share the DB. Both games here carry a unique
        // token in their name and the assertions filter on it, the same scoping trick the name-filter test uses.
        for {
          owner      <- newUser()
          other      <- newUser()
          ownTagId   <- eligibleTagWithPairs(owner, "mine", WordLanguage.De, WordLanguage.Hu, count = 1)
          otherTagId <- eligibleTagWithPairs(other, "notMine", WordLanguage.De, WordLanguage.Hu, count = 1)
          ownGame    <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(ownTagId))
          otherGame  <- GameService.createGame(other, WordLanguage.De, WordLanguage.Hu, List(otherTagId))
          _          <- GameService.rename(ownGame.slug, "Zzyzx Own", owner)
          _          <- GameService.rename(otherGame.slug, "Zzyzx Other", other)
          unplayed   <- GameService.allGames(owner, Some("zzyzx"), false, 1, 20, None, false)
          firstPlay  <- GameService.startPlay(ownGame.slug, owner)
          _          <- playThrough(firstPlay.playId, "mine", owner)
          secondPlay <- GameService.startPlay(ownGame.slug, owner)
          _          <- playThrough(secondPlay.playId, "mine", owner)
          played     <- GameService.allGames(owner, Some("zzyzx"), false, 1, 20, None, false)
        } yield {
          val ownRow    = unplayed.items.find(_.slug == ownGame.slug).get
          val playedOwn = played.items.find(_.slug == ownGame.slug).get
          assertTrue(
            // Every account's game comes back now, not just the caller's own.
            unplayed.total == 2L,
            unplayed.items.map(_.slug).toSet == Set(ownGame.slug, otherGame.slug),
            ownRow.tags.map(_.name) == List("mine"),
            ownRow.tags.forall(_.id > 0L),
            ownRow.sourceLanguage == WordLanguage.De,
            ownRow.targetLanguage == WordLanguage.Hu,
            ownRow.playCount == 0L,
            playedOwn.playCount == 2L,
          )
        }
      },
      test("allGames narrows to games whose name contains the filter, case-insensitively") {
        for {
          owner <- newUser()
          tagId <- eligibleTagWithPairs(owner, "lesson", WordLanguage.De, WordLanguage.Hu, count = 1)
          gameA <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          gameB <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          // A unique token: the listing is not owner-scoped, so another test's "Alpha Quiz" must not collide.
          _     <- GameService.rename(gameA.slug, "Qwerty Alpha", owner)
          _     <- GameService.rename(gameB.slug, "Qwerty Beta", owner)
          hit   <- GameService.allGames(owner, Some("QWERTY ALPHA"), false, 1, 20, None, false)
          miss  <- GameService.allGames(owner, Some("qwerty gamma"), false, 1, 20, None, false)
        } yield assertTrue(
          hit.total == 1L,
          hit.items.map(_.name) == List("Qwerty Alpha"),
          miss.total == 0L,
        )
      },
      test("favoriteGame drives the like count, the my-heart state, the favorites filter and the like-count sort") {
        // Unique name token so the shared DB's other games do not leak into the filtered assertions.
        for {
          owner      <- newUser()
          other      <- newUser()
          tagId      <- eligibleTagWithPairs(owner, "mine", WordLanguage.De, WordLanguage.Hu, count = 1)
          liked      <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          plain      <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          _          <- GameService.rename(liked.slug, "Frobnitz Liked", owner)
          _          <- GameService.rename(plain.slug, "Frobnitz Plain", owner)
          _          <- GameService.favoriteGame(liked.slug, owner)
          _          <- GameService.favoriteGame(liked.slug, other)
          _          <- GameService.favoriteGame(liked.slug, owner) // idempotent — still one row for owner
          listed     <- GameService.allGames(owner, Some("frobnitz"), false, 1, 20, None, false)
          byLikes    <- GameService.allGames(owner, Some("frobnitz"), false, 1, 20, Some(AllGameSort.likeCount), true)
          ownerMine  <- GameService.allGames(owner, Some("frobnitz"), true, 1, 20, None, false)
          otherMine  <- GameService.allGames(other, Some("frobnitz"), true, 1, 20, None, false)
          _          <- GameService.unfavoriteGame(liked.slug, other)
          afterUnfav <- GameService.allGames(owner, Some("frobnitz"), false, 1, 20, None, false)
          missing    <- GameService.favoriteGame("no-such-game", owner).either
        } yield {
          val likedRow = listed.items.find(_.slug == liked.slug).get
          val plainRow = listed.items.find(_.slug == plain.slug).get
          assertTrue(
            likedRow.likeCount == 2L,
            likedRow.favoritedByMe,
            plainRow.likeCount == 0L,
            !plainRow.favoritedByMe,
            byLikes.items.map(_.slug) == List(liked.slug, plain.slug),
            ownerMine.items.map(_.slug) == List(liked.slug),
            otherMine.items.map(_.slug) == List(liked.slug),
            afterUnfav.items.find(_.slug == liked.slug).get.likeCount == 1L,
            missing == Left(GameFailure.NotFound),
          )
        }
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
      test("a full playthrough scores exact, typo and wrong answers, and results match what was submitted") {
        for {
          owner     <- newUser()
          tagId     <- eligibleTagWithPairs(owner, "playthrough", WordLanguage.De, WordLanguage.Hu, count = 3)
          created   <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started   <- GameService.startPlay(created.slug, owner)
          scenarios <- playThrough(started.playId, "playthrough", owner)
          results   <- GameService.getResults(started.playId, owner)
        } yield assertTrue(
          started.wordCount == 3,
          started.maxScore == 6,
          scenarios.sorted == List(0, 1, 2), // one of each scenario, order decided by nextPrompt's own randomness
          results.wordCount == 3,
          results.maxScore == 6,
          results.score == 3,                // one exact match (2) + one typo (1) + one wrong (0)
          results.answers.size == 3,
          results.answers.map(_.outcome).toSet == Set(AnswerOutcome.Correct, AnswerOutcome.Typo, AnswerOutcome.Wrong),
        )
      },
      test("a limited play's word set is fixed at start and stays consistent across the whole playthrough") {
        for {
          owner     <- newUser()
          tagId     <- eligibleTagWithPairs(owner, "sampled", WordLanguage.De, WordLanguage.Hu, count = 5)
          created   <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started   <- GameService.startPlay(created.slug, owner, wordLimit = Some(2))
          scenarios <- playThrough(started.playId, "sampled", owner)
          results   <- GameService.getResults(started.playId, owner)
        } yield assertTrue(
          scenarios.size == 2,
          results.wordCount == 2,
          results.answers.size == 2,
          results.answers.map(_.wordText).distinct.size == 2,
        )
      },
      test("a game with no word limit still uses every eligible word, exactly as before this setting existed") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "unlimited", WordLanguage.De, WordLanguage.Hu, count = 4)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner)
        } yield assertTrue(started.wordCount == 4, started.maxScore == 8)
      },
      test("a limited play still draws source words that share the same target translation") {
        for {
          owner   <- newUser()
          // Three sources, one shared target: a play limited to two must draw two of them, not collapse the pool to
          // the single collision-free pair it once did.
          tagId   <- eligibleTagWithCollidingTarget(owner, "collision", WordLanguage.De, WordLanguage.Hu, count = 3)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner, wordLimit = Some(2))
        } yield assertTrue(started.wordCount == 2)
      },
      test("a game with no word limit still asks source words that share a target translation") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithCollidingTarget(owner, "collisionAll", WordLanguage.De, WordLanguage.Hu)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner)
        } yield assertTrue(started.wordCount == 2)
      },
      test("swapDirection reverses the resolved direction and records it on the play") {
        for {
          owner          <- newUser()
          tagId          <- eligibleTagWithPairs(owner, "swap", WordLanguage.De, WordLanguage.Hu, count = 2)
          created        <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          normal         <- GameService.startPlay(created.slug, owner)
          swapped        <- GameService.startPlay(created.slug, owner, swapDirection = true)
          normalResults  <- GameService.getResults(normal.playId, owner)
          swappedResults <- GameService.getResults(swapped.playId, owner)
        } yield assertTrue(
          normalResults.variant.sourceLanguage == WordLanguage.De,
          normalResults.variant.targetLanguage == WordLanguage.Hu,
          swappedResults.variant.sourceLanguage == WordLanguage.Hu,
          swappedResults.variant.targetLanguage == WordLanguage.De,
        )
      },
      test("a play-time word limit samples the play, without ever touching the game itself") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "playLimit", WordLanguage.De, WordLanguage.Hu, count = 5)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner, wordLimit = Some(2))
          results <- GameService.getResults(started.playId, owner)
        } yield assertTrue(
          started.wordCount == 2,
          started.maxScore == 4,
          results.variant.wordLimit.contains(2),
        )
      },
      test("an out-of-range play-time word limit fails validation") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "playLimitInvalid", WordLanguage.De, WordLanguage.Hu, count = 2)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          result  <- GameService.startPlay(created.slug, owner, wordLimit = Some(0)).either
        } yield assertTrue(result.left.exists(_.isInstanceOf[GameFailure.ValidationError]))
      },
      test("a play-time word limit at or above the eligible pool fails validation") {
        for {
          owner     <- newUser()
          tagId     <- eligibleTagWithPairs(owner, "playLimitFull", WordLanguage.De, WordLanguage.Hu, count = 4)
          created   <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          atPool    <- GameService.startPlay(created.slug, owner, wordLimit = Some(4)).either
          overPool  <- GameService.startPlay(created.slug, owner, wordLimit = Some(5)).either
          underPool <- GameService.startPlay(created.slug, owner, wordLimit = Some(3))
        } yield assertTrue(
          atPool.left.exists(_.isInstanceOf[GameFailure.ValidationError]),
          overPool.left.exists(_.isInstanceOf[GameFailure.ValidationError]),
          underPool.wordCount == 3,
        )
      },
      test("LeastPlayed preference fills the sample from never-answered words first, in this direction only") {
        for {
          owner      <- newUser()
          tagId      <- eligibleTagWithPairs(owner, "leastPlayedPref", WordLanguage.De, WordLanguage.Hu, count = 4)
          created    <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          warmup     <- GameService.startPlay(created.slug, owner, wordLimit = Some(1))
          _          <- playThrough(warmup.playId, "leastPlayedPref", owner)
          warmupWord <- GameService.getResults(warmup.playId, owner).map(_.answers.head.wordText)
          narrowed   <- GameService.startPlay(
                          created.slug,
                          owner,
                          wordLimit = Some(3),
                          wordPreference = WordPreference.LeastPlayed,
                        )
          // Played through, not merely started: `getResults` lists answers, so an unplayed play answers an
          // empty list and every assertion about which words it drew would hold vacuously.
          _          <- playThrough(narrowed.playId, "leastPlayedPref", owner)
          results    <- GameService.getResults(narrowed.playId, owner)
        } yield assertTrue(
          // Three of the four eligible words are sampled. The three never answered each score zero and the
          // warmup word scores one, so the warmup word is the one left out — deterministic, whichever way the
          // shuffle breaks the tie among the three.
          results.wordCount == 3,
          results.variant.wordPreference == WordPreference.LeastPlayed,
          !results.answers.exists(_.wordText == warmupWord),
        )
      },
      test("LeastPlayed preference ranks by answer count, not merely by whether a word was answered at all") {
        // The state the old never-answered-first rule could not express: every word has been answered, so a
        // "have I played this?" flag ties them all and the order falls back to the pool's own. Asserted through
        // the picker's preview, which orders by the same rule without the play draw's shuffle, so the ranking
        // itself is what is under test.
        //
        // The word answered twice is deliberately the alphabetically *first* one: the preview breaks ties
        // alphabetically, so a rule that cannot tell one answer from two would leave it first and pass by
        // accident.
        val first  = "leastCount-source-0"
        val second = "leastCount-source-1"
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "leastCount", WordLanguage.De, WordLanguage.Hu, count = 2)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          warmup  <- GameService.startPlay(created.slug, owner)
          // Both words answered once, and `first` answered wrong — which is what lets the next play draw it
          // deterministically rather than by shuffle.
          _       <- answerOnce(warmup.playId, "leastCount", owner, wrongFor = first)
          _       <- answerOnce(warmup.playId, "leastCount", owner, wrongFor = first)
          extra   <- GameService.startPlay(
                       created.slug,
                       owner,
                       wordLimit = Some(1),
                       wordPreference = WordPreference.MostMistakes,
                     )
          drawn   <- GameService.nextPrompt(extra.playId, owner).map(_.wordText.get)
          _       <- playThrough(extra.playId, "leastCount", owner)
          preview <- GameService.playSetupPreview(
                       created.slug,
                       Some(owner),
                       swapDirection = false,
                       WordPreference.LeastPlayed,
                     )
        } yield assertTrue(
          // MostMistakes drew the only word with a mistake, so that word now carries two answers to the other's
          // one.
          drawn == first,
          preview.map(_.text) == List(second, first),
        )
      },
      test("MostMistakes preference ranks by this player's wrong-answer count, in this direction only") {
        for {
          owner       <- newUser()
          tag         <- WordRepository.insertTag(owner, "mistakePref", "mistakePref", 0L, "de", "hu")
          mistakeWord <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "mistake-source"))
          mistakeTgt  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "mistake-target"))
          _           <- WordRepository.pairTranslation(mistakeWord.id, tag.id, mistakeTgt.id, 0L)
          cleanWord   <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "clean-source"))
          cleanTgt    <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "clean-target"))
          _           <- WordRepository.pairTranslation(cleanWord.id, tag.id, cleanTgt.id, 0L)
          created     <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tag.id))
          warmup      <- GameService.startPlay(created.slug, owner)
          prompt1     <- GameService.nextPrompt(warmup.playId, owner)
          firstText    = prompt1.wordText.get
          _           <- GameService.submitAnswer(
                           warmup.playId,
                           prompt1.wordId.get,
                           if (firstText.contains("mistake")) "totally-unrelated" else "clean-target",
                           owner,
                         )
          prompt2     <- GameService.nextPrompt(warmup.playId, owner)
          secondText   = prompt2.wordText.get
          _           <- GameService.submitAnswer(
                           warmup.playId,
                           prompt2.wordId.get,
                           if (secondText.contains("mistake")) "totally-unrelated" else "clean-target",
                           owner,
                         )
          narrowed    <- GameService.startPlay(
                           created.slug,
                           owner,
                           wordLimit = Some(1),
                           wordPreference = WordPreference.MostMistakes,
                         )
          prompt3     <- GameService.nextPrompt(narrowed.playId, owner)
          _           <- GameService.submitAnswer(narrowed.playId, prompt3.wordId.get, "mistake-target", owner)
          results     <- GameService.getResults(narrowed.playId, owner)
        } yield assertTrue(
          results.wordCount == 1,
          results.variant.wordPreference == WordPreference.MostMistakes,
          // mistake-source is answered wrong regardless of prompt order (the branches above always answer
          // whichever prompt is the mistake word incorrectly and the clean word correctly), so it's the only
          // word with a recorded mistake — a correct MostMistakes ranking must pick it deterministically.
          results.answers.head.wordText == "mistake-source",
        )
      },
      test("playSetupPreview answers the resolved-direction pool without starting a play") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "previewPref", WordLanguage.De, WordLanguage.Hu, count = 3)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          preview <- GameService.playSetupPreview(created.slug, Some(owner), swapDirection = false, WordPreference.All)
          swapped <- GameService.playSetupPreview(created.slug, Some(owner), swapDirection = true, WordPreference.All)
        } yield assertTrue(preview.size == 3, swapped.size == 3)
      },
      test("playSetupPreview answers the same pool for an anonymous caller, with no play history to prefer by") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "previewAnon", WordLanguage.De, WordLanguage.Hu, count = 3)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          anon    <- GameService.playSetupPreview(created.slug, None, swapDirection = false, WordPreference.All)
          least   <- GameService.playSetupPreview(
                       created.slug,
                       None,
                       swapDirection = false,
                       WordPreference.LeastPlayed,
                     )
        } yield assertTrue(anon.size == 3, least.size == 3)
      },
      test("starting a play when the game's tags currently carry nothing eligible fails") {
        for {
          owner   <- newUser()
          tag     <- WordRepository.insertTag(owner, "emptied", "emptied", 0L, "de", "hu")
          source  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "emptied-source"))
          target  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "emptied-target"))
          _       <- WordRepository.pairTranslation(source.id, tag.id, target.id, 0L)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tag.id))
          _       <- WordRepository.unpairTranslation(source.id, tag.id, target.id)
          result  <- GameService.startPlay(created.slug, owner).either
        } yield assertTrue(result == Left(GameFailure.NoEligibleWords))
      },
      test("a play belongs to the player who started it — another user is refused at every step") {
        for {
          owner         <- newUser()
          other         <- newUser()
          tagId         <- eligibleTagWithPairs(owner, "guarded", WordLanguage.De, WordLanguage.Hu, count = 1)
          created       <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started       <- GameService.startPlay(created.slug, owner)
          promptResult  <- GameService.nextPrompt(started.playId, other).either
          submitResult  <- GameService.submitAnswer(started.playId, 0L, "anything", other).either
          resultsResult <- GameService.getResults(started.playId, other).either
        } yield assertTrue(
          promptResult == Left(GameFailure.NotOwner),
          submitResult == Left(GameFailure.NotOwner),
          resultsResult == Left(GameFailure.NotOwner),
        )
      },
      test("submitAnswer answers with the graded row, the same one the results screen later shows") {
        for {
          owner   <- newUser()
          tag     <- WordRepository.insertTag(owner, "feedback", "feedback", 0L, "de", "hu")
          source  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "feedback-source"))
          target  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "feedback-target"))
          _       <- WordRepository.pairTranslation(source.id, tag.id, target.id, 0L)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tag.id))
          missed  <- GameService.startPlay(created.slug, owner)
          prompt  <- GameService.nextPrompt(missed.playId, owner)
          wrong   <- GameService.submitAnswer(missed.playId, prompt.wordId.get, "nothing-like-it", owner)
          results <- GameService.getResults(missed.playId, owner)
          restart <- GameService.startPlay(created.slug, owner)
          prompt2 <- GameService.nextPrompt(restart.playId, owner)
          right   <- GameService.submitAnswer(restart.playId, prompt2.wordId.get, "feedback-target", owner)
        } yield assertTrue(
          // A mistake is named as one and carries what the game would have accepted, so the player is told at once.
          wrong.outcome == AnswerOutcome.Wrong,
          wrong.expectedTexts == List("feedback-target"),
          wrong.givenText == "nothing-like-it",
          wrong.wordText == "feedback-source",
          // The very row the finished play shows: both go through `answerResultsOf`, so they cannot disagree.
          results.answers.head == wrong,
          right.outcome == AnswerOutcome.Correct,
        )
      },
      test("a gendered source word's prompt and results carry its article, an ungendered one is unaffected") {
        for {
          owner   <- newUser()
          tag     <- WordRepository.insertTag(owner, "genderedSource", "genderedSource", 0L, "de", "hu")
          source  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Tisch", gender = Some(Gender.Masculine)))
          target  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "asztal"))
          _       <- WordRepository.pairTranslation(source.id, tag.id, target.id, 0L)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tag.id))
          started <- GameService.startPlay(created.slug, owner)
          prompt  <- GameService.nextPrompt(started.playId, owner)
          _       <- GameService.submitAnswer(started.playId, prompt.wordId.get, "asztal", owner)
          results <- GameService.getResults(started.playId, owner)
        } yield assertTrue(
          // The prompt is the gendered German source word: it shows its article.
          prompt.wordText.contains("der Tisch"),
          results.answers.head.wordText == "der Tisch",
          // The expected answer is the ungendered Hungarian target: it is unaffected.
          results.answers.head.expectedTexts == List("asztal"),
          results.answers.head.outcome == AnswerOutcome.Correct,
        )
      },
      test("a word with more than one marked translation accepts any of them") {
        for {
          owner    <- newUser()
          tag      <- WordRepository.insertTag(owner, "multi", "multi", 0L, "de", "hu")
          source   <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "multi-source"))
          target1  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "multi-target-1"))
          target2  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "multi-target-2"))
          _        <- WordRepository.pairTranslation(source.id, tag.id, target1.id, 0L)
          _        <- WordRepository.pairTranslation(source.id, tag.id, target2.id, 0L)
          created  <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tag.id))
          started  <- GameService.startPlay(created.slug, owner)
          prompt   <- GameService.nextPrompt(started.playId, owner)
          _        <- GameService.submitAnswer(started.playId, prompt.wordId.get, "multi-target-2", owner)
          results  <- GameService.getResults(started.playId, owner)
          restart  <- GameService.startPlay(created.slug, owner)
          prompt2  <- GameService.nextPrompt(restart.playId, owner)
          _        <- GameService.submitAnswer(restart.playId, prompt2.wordId.get, "multi-target-1", owner)
          results2 <- GameService.getResults(restart.playId, owner)
        } yield assertTrue(
          // Whichever of the two marked translations is typed, it scores correct; the results list both as accepted.
          results.answers.head.outcome == AnswerOutcome.Correct,
          results.answers.head.expectedTexts == List("multi-target-1", "multi-target-2"),
          results2.answers.head.outcome == AnswerOutcome.Correct,
          results2.answers.head.expectedTexts == List("multi-target-1", "multi-target-2"),
        )
      },
      test("two words spelled alike are asked separately, each carrying its own part of speech") {
        for {
          owner   <- newUser()
          tag     <- WordRepository.insertTag(owner, "homonym", "homonym", 0L, "de", "hu")
          // `words` is unique on (language, text_norm, part_of_speech, gender), so these are two rows, not one.
          noun    <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "lauf"))
          verb    <-
            WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "lauf", partOfSpeech = PartOfSpeech.Verb))
          nounHu  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "futas"))
          verbHu  <-
            WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "fuss", partOfSpeech = PartOfSpeech.Verb))
          _       <- markPair(tag.id, noun, nounHu)
          _       <- markPair(tag.id, verb, verbHu)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tag.id))
          preview <- GameService.playSetupPreview(created.slug, Some(owner), swapDirection = false, WordPreference.All)
          started <- GameService.startPlay(created.slug, owner)
          prompt1 <- GameService.nextPrompt(started.playId, owner)
          _       <- GameService.submitAnswer(started.playId, prompt1.wordId.get, "futas", owner)
          prompt2 <- GameService.nextPrompt(started.playId, owner)
          _       <- GameService.submitAnswer(started.playId, prompt2.wordId.get, "fuss", owner)
          results <- GameService.getResults(started.playId, owner)
        } yield assertTrue(
          started.wordCount == 2,
          prompt1.wordText.contains("lauf"),
          prompt2.wordText.contains("lauf"),
          // The spelling is the same in both prompts; the part of speech is the only thing telling them apart.
          Set(prompt1.partOfSpeech, prompt2.partOfSpeech) == Set(Some(PartOfSpeech.Noun), Some(PartOfSpeech.Verb)),
          results.answers.map(_.wordText).toSet == Set("lauf"),
          results.answers.flatMap(_.partOfSpeech).toSet == Set(PartOfSpeech.Noun, PartOfSpeech.Verb),
          // The study list the setup screen shows carries it too.
          preview.map(_.text).toSet == Set("lauf"),
          preview.flatMap(_.partOfSpeech).toSet == Set(PartOfSpeech.Noun, PartOfSpeech.Verb),
        )
      },
      test("a gendered expected answer requires its article to score as correct") {
        for {
          owner       <- newUser()
          tag         <- WordRepository.insertTag(owner, "genderedTarget", "genderedTarget", 0L, "de", "hu")
          source      <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "asztal"))
          target      <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Tisch", gender = Some(Gender.Masculine)))
          _           <- WordRepository.pairTranslation(source.id, tag.id, target.id, 0L)
          // Hungarian -> German: the *expected* answer is now the gendered German word.
          created     <- GameService.createGame(owner, WordLanguage.Hu, WordLanguage.De, List(tag.id))
          started     <- GameService.startPlay(created.slug, owner)
          prompt      <- GameService.nextPrompt(started.playId, owner)
          _           <- GameService.submitAnswer(started.playId, prompt.wordId.get, "Tisch", owner)
          bareResults <- GameService.getResults(started.playId, owner)
          restarted   <- GameService.startPlay(created.slug, owner)
          prompt2     <- GameService.nextPrompt(restarted.playId, owner)
          _           <- GameService.submitAnswer(restarted.playId, prompt2.wordId.get, "der Tisch", owner)
          fullResults <- GameService.getResults(restarted.playId, owner)
        } yield assertTrue(
          // The ungendered Hungarian source word is unaffected.
          prompt.wordText.contains("asztal"),
          // Typing the noun without its article is nowhere near a one-letter typo of "der Tisch", so it scores wrong.
          bareResults.answers.head.outcome == AnswerOutcome.Wrong,
          bareResults.answers.head.expectedTexts == List("der Tisch"),
          // Typing the noun with its correct article scores correct.
          fullResults.answers.head.outcome == AnswerOutcome.Correct,
        )
      },
      test("includeDefiniteArticles defaults to true and, when false, strips the article everywhere") {
        for {
          owner          <- newUser()
          tag            <- WordRepository.insertTag(owner, "bareArticle", "bareArticle", 0L, "de", "hu")
          source         <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "szekreny"))
          target         <-
            WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Schrank", gender = Some(Gender.Masculine)))
          _              <- WordRepository.pairTranslation(source.id, tag.id, target.id, 0L)
          created        <- GameService.createGame(owner, WordLanguage.Hu, WordLanguage.De, List(tag.id))
          default        <- GameService.startPlay(created.slug, owner)
          defaultResults <- GameService.getResults(default.playId, owner)
          bare           <- GameService.startPlay(created.slug, owner, includeDefiniteArticles = false)
          prompt         <- GameService.nextPrompt(bare.playId, owner)
          _              <- GameService.submitAnswer(bare.playId, prompt.wordId.get, "Schrank", owner)
          results        <- GameService.getResults(bare.playId, owner)
        } yield assertTrue(
          defaultResults.variant.includeDefiniteArticles,
          !results.variant.includeDefiniteArticles,
          results.answers.head.outcome == AnswerOutcome.Correct,
          results.answers.head.expectedTexts == List("Schrank"),
        )
      },
      test("listPlays/getPlayDetail are refused to anyone but the game's owner") {
        for {
          owner   <- newUser()
          other   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "trackedGuard", WordLanguage.De, WordLanguage.Hu, count = 1)
          created <-
            GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner)
          _       <- playThrough(started.playId, "trackedGuard", owner)
          listed  <- GameService.listPlays(created.slug, other, 1, 20, None, None, false).either
          detail  <- GameService.getPlayDetail(created.slug, started.playId, other).either
        } yield assertTrue(listed == Left(GameFailure.NotOwner), detail == Left(GameFailure.NotOwner))
      },
      test(
        "listPlays returns every play with player identity, paged, filtered and counted, and getPlayDetail " +
          "answers one play's full history"
      ) {
        for {
          owner    <- newUser()
          tagId    <- eligibleTagWithPairs(owner, "listed", WordLanguage.De, WordLanguage.Hu, count = 1)
          created  <-
            GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          alice    <- UserRepository.insert("alice@example.com", Some("hash"), false, "light", "en", 0L, None).map(_.id)
          guest    <- newUser()
          playA    <- GameService.startPlay(created.slug, alice)
          _        <- playThrough(playA.playId, "listed", alice)
          playG    <- GameService.startPlay(created.slug, guest)
          _        <- playThrough(playG.playId, "listed", guest)
          all      <- GameService.listPlays(created.slug, owner, 1, 20, None, None, false)
          filtered <- GameService.listPlays(created.slug, owner, 1, 20, Some("alice"), None, false)
          detail   <- GameService.getPlayDetail(created.slug, playA.playId, owner)
        } yield assertTrue(
          all.total == 2L,
          all.items.map(_.playId).toSet == Set(playA.playId, playG.playId),
          all.items.find(_.playId == playA.playId).exists(_.playerEmail.contains("alice@example.com")),
          all.items.find(_.playId == playG.playId).exists(_.playerIsGuest),
          filtered.total == 1L,
          filtered.items.map(_.playId) == List(playA.playId),
          detail.playerEmail.contains("alice@example.com"),
          detail.answers.size == 1,
        )
      },
      test("getPlayDetail refuses a playId that belongs to a different game") {
        for {
          owner  <- newUser()
          tagId  <- eligibleTagWithPairs(owner, "crossGame", WordLanguage.De, WordLanguage.Hu, count = 1)
          gameA  <-
            GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          gameB  <-
            GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          playA  <- GameService.startPlay(gameA.slug, owner)
          result <- GameService.getPlayDetail(gameB.slug, playA.playId, owner).either
        } yield assertTrue(result == Left(GameFailure.NotFound))
      },
      test("myPlays answers the caller's own plays across every game") {
        for {
          player <- newUser()
          tagA   <- eligibleTagWithPairs(player, "myPlaysA", WordLanguage.De, WordLanguage.Hu, count = 1)
          tagB   <- eligibleTagWithPairs(player, "myPlaysB", WordLanguage.De, WordLanguage.Hu, count = 1)
          gameA  <- GameService.createGame(player, WordLanguage.De, WordLanguage.Hu, List(tagA))
          gameB  <- GameService.createGame(player, WordLanguage.De, WordLanguage.Hu, List(tagB))
          playA  <- GameService.startPlay(gameA.slug, player)
          _      <- playThrough(playA.playId, "myPlaysA", player)
          playB  <- GameService.startPlay(gameB.slug, player)
          _      <- playThrough(playB.playId, "myPlaysB", player)
          mine   <- GameService.myPlays(player, None, None, 1, 20, None, false)
        } yield assertTrue(
          mine.total == 2L,
          mine.items.map(_.playId).toSet == Set(playA.playId, playB.playId),
          mine.items.map(_.gameSlug).toSet == Set(gameA.slug, gameB.slug),
        )
      },
      test("myPlays narrows to games whose name contains the filter, case-insensitively") {
        for {
          player <- newUser()
          tagA   <- eligibleTagWithPairs(player, "myPlaysFilterA", WordLanguage.De, WordLanguage.Hu, count = 1)
          tagB   <- eligibleTagWithPairs(player, "myPlaysFilterB", WordLanguage.De, WordLanguage.Hu, count = 1)
          gameA  <- GameService.createGame(player, WordLanguage.De, WordLanguage.Hu, List(tagA))
          gameB  <- GameService.createGame(player, WordLanguage.De, WordLanguage.Hu, List(tagB))
          _      <- GameService.rename(gameA.slug, "Alpha Quiz", player)
          _      <- GameService.rename(gameB.slug, "Beta Quiz", player)
          playA  <- GameService.startPlay(gameA.slug, player)
          _      <- playThrough(playA.playId, "myPlaysFilterA", player)
          playB  <- GameService.startPlay(gameB.slug, player)
          _      <- playThrough(playB.playId, "myPlaysFilterB", player)
          hit    <- GameService.myPlays(player, None, Some("ALPHA"), 1, 20, None, false)
          miss   <- GameService.myPlays(player, None, Some("gamma"), 1, 20, None, false)
        } yield assertTrue(
          hit.total == 1L,
          hit.items.map(_.playId) == List(playA.playId),
          hit.items.map(_.gameName) == List("Alpha Quiz"),
          miss.total == 0L,
          miss.items.isEmpty,
        )
      },
      test("myPlays never answers another account's plays") {
        for {
          player  <- newUser()
          other   <- newUser()
          tagId   <- eligibleTagWithPairs(player, "myPlaysGuard", WordLanguage.De, WordLanguage.Hu, count = 1)
          created <- GameService.createGame(player, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, player)
          _       <- playThrough(started.playId, "myPlaysGuard", player)
          theirs  <- GameService.myPlays(other, None, None, 1, 20, None, false)
        } yield assertTrue(theirs.total == 0L, theirs.items.isEmpty)
      },
      test("playsOf answers the target user's plays across every game") {
        for {
          player   <- newUser()
          tagA     <- eligibleTagWithPairs(player, "playsOfA", WordLanguage.De, WordLanguage.Hu, count = 1)
          tagB     <- eligibleTagWithPairs(player, "playsOfB", WordLanguage.De, WordLanguage.Hu, count = 1)
          gameA    <- GameService.createGame(player, WordLanguage.De, WordLanguage.Hu, List(tagA))
          gameB    <- GameService.createGame(player, WordLanguage.De, WordLanguage.Hu, List(tagB))
          playA    <- GameService.startPlay(gameA.slug, player)
          _        <- playThrough(playA.playId, "playsOfA", player)
          playB    <- GameService.startPlay(gameB.slug, player)
          _        <- playThrough(playB.playId, "playsOfB", player)
          answered <- GameService.playsOf(player, None, 1, 20, None, false)
        } yield assertTrue(
          answered.total == 2L,
          answered.items.map(_.playId).toSet == Set(playA.playId, playB.playId),
        )
      },
      test("playsOf narrows to games whose name contains nameContains, case-insensitively") {
        for {
          player <- newUser()
          tagA   <- eligibleTagWithPairs(player, "playsOfFilterA", WordLanguage.De, WordLanguage.Hu, count = 1)
          tagB   <- eligibleTagWithPairs(player, "playsOfFilterB", WordLanguage.De, WordLanguage.Hu, count = 1)
          gameA  <- GameService.createGame(player, WordLanguage.De, WordLanguage.Hu, List(tagA))
          gameB  <- GameService.createGame(player, WordLanguage.De, WordLanguage.Hu, List(tagB))
          _      <- GameService.rename(gameA.slug, "Alpha Quiz", player)
          _      <- GameService.rename(gameB.slug, "Beta Quiz", player)
          playA  <- GameService.startPlay(gameA.slug, player)
          _      <- playThrough(playA.playId, "playsOfFilterA", player)
          playB  <- GameService.startPlay(gameB.slug, player)
          _      <- playThrough(playB.playId, "playsOfFilterB", player)
          hit    <- GameService.playsOf(player, None, 1, 20, None, false, Some("ALPHA"))
          miss   <- GameService.playsOf(player, None, 1, 20, None, false, Some("gamma"))
        } yield assertTrue(
          hit.total == 1L,
          hit.items.map(_.playId) == List(playA.playId),
          hit.items.map(_.gameName) == List("Alpha Quiz"),
          miss.total == 0L,
          miss.items.isEmpty,
        )
      },
      test("resultsForPlayer answers a play when it belongs to the given user, NotFound otherwise") {
        for {
          owner   <- newUser()
          other   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "resultsForPlayer", WordLanguage.De, WordLanguage.Hu, count = 1)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner)
          _       <- playThrough(started.playId, "resultsForPlayer", owner)
          mine    <- GameService.getResults(started.playId, owner)
          asAdmin <- GameService.resultsForPlayer(started.playId, owner)
          wrongId <- GameService.resultsForPlayer(started.playId, other).either
          missing <- GameService.resultsForPlayer(started.playId + 9999L, owner).either
        } yield assertTrue(
          asAdmin == mine,
          wrongId == Left(GameFailure.NotFound),
          missing == Left(GameFailure.NotFound),
        )
      },
      test("same word under two tags with different translations: one prompt, either translation accepted") {
        for {
          owner <- newUser()
          w     <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "haus"))
          t1    <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "haz"))
          t2    <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "otthon"))
          tagA  <- WordRepository.insertTag(owner, "combA", "combA", 0L, "de", "hu").map(_.id)
          tagB  <- WordRepository.insertTag(owner, "combB", "combB", 0L, "de", "hu").map(_.id)
          _     <- markPair(tagA, w, t1)
          _     <- markPair(tagB, w, t2)

          // Forward De -> Hu: the word sits under both tags, so it is prompted once.
          fwd     <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagA, tagB))
          play1   <- GameService.startPlay(fwd.slug, owner)
          prompt1 <- GameService.nextPrompt(play1.playId, owner)
          _       <- GameService.submitAnswer(play1.playId, prompt1.wordId.get, "otthon", owner)
          after1  <- GameService.nextPrompt(play1.playId, owner)
          res1    <- GameService.getResults(play1.playId, owner)
          play2   <- GameService.startPlay(fwd.slug, owner)
          prompt2 <- GameService.nextPrompt(play2.playId, owner)
          _       <- GameService.submitAnswer(play2.playId, prompt2.wordId.get, "haz", owner)
          res2    <- GameService.getResults(play2.playId, owner)

          // Swapped Hu -> De: two distinct source words that share the one answer, both asked (no word limit).
          swp   <- GameService.createGame(owner, WordLanguage.Hu, WordLanguage.De, List(tagA, tagB))
          play3 <- GameService.startPlay(swp.slug, owner)
          _     <- answerEach(play3.playId, owner, "haus")
          res3  <- GameService.getResults(play3.playId, owner)
        } yield assertTrue(
          play1.wordCount == 1,
          play1.maxScore == 2,
          prompt1.wordText.contains("haus"),
          after1.finished,
          res1.answers.size == 1,
          res1.answers.head.outcome == AnswerOutcome.Correct,
          // The one prompt lists both tags' translations as accepted.
          res1.answers.head.expectedTexts == List("haz", "otthon"),
          res2.answers.head.outcome == AnswerOutcome.Correct,
          res2.answers.head.expectedTexts == List("haz", "otthon"),
          play3.wordCount == 2,
          play3.maxScore == 4,
          res3.answers.map(_.outcome).toSet == Set(AnswerOutcome.Correct),
          res3.answers.flatMap(_.expectedTexts).toSet == Set("haus"),
          res3.answers.map(_.wordText).toSet == Set("haz", "otthon"),
        )
      },
      test("same word under two tags with the same translation: plain distinct, one prompt each direction") {
        for {
          owner <- newUser()
          w     <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "buch"))
          t     <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "konyv"))
          tagA  <- WordRepository.insertTag(owner, "dupA", "dupA", 0L, "de", "hu").map(_.id)
          tagB  <- WordRepository.insertTag(owner, "dupB", "dupB", 0L, "de", "hu").map(_.id)
          _     <- markPair(tagA, w, t)
          _     <- markPair(tagB, w, t)

          fwd     <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagA, tagB))
          play1   <- GameService.startPlay(fwd.slug, owner)
          prompt1 <- GameService.nextPrompt(play1.playId, owner)
          _       <- GameService.submitAnswer(play1.playId, prompt1.wordId.get, "konyv", owner)
          after1  <- GameService.nextPrompt(play1.playId, owner)
          res1    <- GameService.getResults(play1.playId, owner)

          swp     <- GameService.createGame(owner, WordLanguage.Hu, WordLanguage.De, List(tagA, tagB))
          play2   <- GameService.startPlay(swp.slug, owner)
          prompt2 <- GameService.nextPrompt(play2.playId, owner)
          _       <- GameService.submitAnswer(play2.playId, prompt2.wordId.get, "buch", owner)
          res2    <- GameService.getResults(play2.playId, owner)
        } yield assertTrue(
          play1.wordCount == 1,
          play1.maxScore == 2,
          after1.finished,
          res1.answers.size == 1,
          res1.answers.head.outcome == AnswerOutcome.Correct,
          res1.answers.head.expectedTexts == List("konyv"),
          play2.wordCount == 1,
          play2.maxScore == 2,
          res2.answers.size == 1,
          res2.answers.head.outcome == AnswerOutcome.Correct,
          res2.answers.head.expectedTexts == List("buch"),
        )
      },
      test("same word across three tags with overlapping translation sets: one prompt, every translation accepted") {
        for {
          owner <- newUser()
          w     <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "tag"))
          a     <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "nap"))
          b     <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "napszak"))
          c     <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "datum"))
          tagA  <- WordRepository.insertTag(owner, "triA", "triA", 0L, "de", "hu").map(_.id)
          tagB  <- WordRepository.insertTag(owner, "triB", "triB", 0L, "de", "hu").map(_.id)
          tagC  <- WordRepository.insertTag(owner, "triC", "triC", 0L, "de", "hu").map(_.id)
          _     <- markPair(tagA, w, a)
          _     <- markPair(tagB, w, a)
          _     <- markPair(tagB, w, b)
          _     <- markPair(tagC, w, a)
          _     <- markPair(tagC, w, c)

          fwd     <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagA, tagB, tagC))
          playA   <- GameService.startPlay(fwd.slug, owner)
          promptA <- GameService.nextPrompt(playA.playId, owner)
          _       <- GameService.submitAnswer(playA.playId, promptA.wordId.get, "nap", owner)
          afterA  <- GameService.nextPrompt(playA.playId, owner)
          resA    <- GameService.getResults(playA.playId, owner)
          playB   <- GameService.startPlay(fwd.slug, owner)
          promptB <- GameService.nextPrompt(playB.playId, owner)
          _       <- GameService.submitAnswer(playB.playId, promptB.wordId.get, "napszak", owner)
          resB    <- GameService.getResults(playB.playId, owner)
          playC   <- GameService.startPlay(fwd.slug, owner)
          promptC <- GameService.nextPrompt(playC.playId, owner)
          _       <- GameService.submitAnswer(playC.playId, promptC.wordId.get, "datum", owner)
          resC    <- GameService.getResults(playC.playId, owner)

          swp   <- GameService.createGame(owner, WordLanguage.Hu, WordLanguage.De, List(tagA, tagB, tagC))
          play3 <- GameService.startPlay(swp.slug, owner)
          _     <- answerEach(play3.playId, owner, "tag")
          res3  <- GameService.getResults(play3.playId, owner)
        } yield assertTrue(
          playA.wordCount == 1,
          playA.maxScore == 2,
          afterA.finished,
          resA.answers.head.outcome == AnswerOutcome.Correct,
          resB.answers.head.outcome == AnswerOutcome.Correct,
          resC.answers.head.outcome == AnswerOutcome.Correct,
          // The single forward prompt lists the union of all three tags' translations.
          resA.answers.head.expectedTexts == List("datum", "nap", "napszak"),
          play3.wordCount == 3,
          play3.maxScore == 6,
          res3.answers.map(_.outcome).toSet == Set(AnswerOutcome.Correct),
          res3.answers.flatMap(_.expectedTexts).toSet == Set("tag"),
          res3.answers.map(_.wordText).toSet == Set("nap", "napszak", "datum"),
        )
      },
      test("a clicked play is worth one point a word, and its prompts carry the options") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "click", WordLanguage.De, WordLanguage.Hu, 6)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner, mode = GameMode.MultipleChoice)
          prompt  <- GameService.nextPrompt(started.playId, owner)
          index    = prompt.wordText.get.stripPrefix("click-source-").toInt
        } yield assertTrue(
          started.wordCount == 6,
          started.maxScore == 6,
          prompt.options.size == 4,
          prompt.options.contains(s"click-target-$index"),
          prompt.options.distinct == prompt.options,
        )
      },
      test("a typed play carries no options at all") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "typed", WordLanguage.De, WordLanguage.Hu, 6)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner)
          prompt  <- GameService.nextPrompt(started.playId, owner)
        } yield assertTrue(prompt.options.isEmpty)
      },
      test("clicking the right option scores, clicking a near-miss does not") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "near", WordLanguage.De, WordLanguage.Hu, 1)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          missed  <- GameService.startPlay(created.slug, owner, mode = GameMode.MultipleChoice)
          promptA <- GameService.nextPrompt(missed.playId, owner)
          _       <- GameService.submitAnswer(missed.playId, promptA.wordId.get, "near-target-0x", owner)
          resA    <- GameService.getResults(missed.playId, owner)
          hit     <- GameService.startPlay(created.slug, owner, mode = GameMode.MultipleChoice)
          promptB <- GameService.nextPrompt(hit.playId, owner)
          _       <- GameService.submitAnswer(hit.playId, promptB.wordId.get, "near-target-0", owner)
          resB    <- GameService.getResults(hit.playId, owner)
        } yield assertTrue(
          // One edit away is a typo when typed; clicked, it is simply the wrong button.
          resA.answers.head.outcome == AnswerOutcome.Wrong,
          resA.score == 0,
          resB.answers.head.outcome == AnswerOutcome.Correct,
          resB.score == 1,
          resB.maxScore == 1,
        )
      },
      test("a thin pool answers with fewer options rather than words the game does not teach") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "thin", WordLanguage.De, WordLanguage.Hu, 2)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner, mode = GameMode.MultipleChoice)
          prompt  <- GameService.nextPrompt(started.playId, owner)
        } yield assertTrue(
          prompt.options.size == 2,
          prompt.options.forall(option => option.startsWith("thin-target-")),
        )
      },
      test("a second accepted translation is never offered as a distractor") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "both", WordLanguage.De, WordLanguage.Hu, 3)
          source  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "both-source-0"))
          other   <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "both-target-other"))
          _       <- markPair(tagId, source, other)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner, mode = GameMode.MultipleChoice)
          prompts <- ZIO.foreach(0 until 3)(_ => GameService.nextPrompt(started.playId, owner))
          forWord0 = prompts.filter(_.wordText.contains("both-source-0"))
        } yield assertTrue(
          forWord0.nonEmpty,
          // Both "both-target-0" and "both-target-other" would be graded correct, so at most one of them
          // can be on screen -- the accepted one. Neither may show up as somebody else's distractor either.
          forWord0.forall(prompt => !prompt.options.contains("both-target-other")),
          prompts.forall(prompt => prompt.options.count(_.startsWith("both-target-")) >= 1),
        )
      },
      test("a German answer offers its own forms and its other articles") {
        for {
          owner   <- newUser()
          tag     <- WordRepository.insertTag(owner, "hunde", "hunde", 0L, "de", "hu")
          source  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "kutya"))
          lemma   <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Hund", gender = Some(Gender.Masculine)))
          plural  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Hunde", gender = Some(Gender.Feminine)))
          _       <- WordRepository.pairTranslation(source.id, tag.id, lemma.id, 0L)
          _       <- WordRepository.insertForms(List(WordFormRow(0L, lemma.id, plural.id, "plural", 0L)))
          created <- GameService.createGame(owner, WordLanguage.Hu, WordLanguage.De, List(tag.id))
          started <- GameService.startPlay(created.slug, owner, mode = GameMode.MultipleChoice)
          prompt  <- GameService.nextPrompt(started.playId, owner)
        } yield assertTrue(
          prompt.wordText.contains("kutya"),
          prompt.options.contains("der Hund"),
          prompt.options.contains("die Hunde"),
          // The pool holds nothing else, so the remaining slots are the same noun under its other articles.
          prompt.options.size == 4,
          prompt.options.forall(option => option.endsWith(" Hund") || option.endsWith(" Hunde")),
        )
      },
      test("the mode a play ran under is on its variant") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "variant", WordLanguage.De, WordLanguage.Hu, 2)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          clicked <- GameService.startPlay(created.slug, owner, mode = GameMode.MultipleChoice)
          typed   <- GameService.startPlay(created.slug, owner)
          resC    <- GameService.getResults(clicked.playId, owner)
          resT    <- GameService.getResults(typed.playId, owner)
        } yield assertTrue(
          resC.variant.mode == GameMode.MultipleChoice,
          resT.variant.mode == GameMode.Typing,
        )
      },
    ).provide(layer)
  }
}
