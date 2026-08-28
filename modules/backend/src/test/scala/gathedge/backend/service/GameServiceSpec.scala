package gathedge.backend.service

import gathedge.backend.TestDataSource
import gathedge.backend.db.{GameRepository, GroupRepository, TextSearch, UserRepository, WordRepository, WordRow}
import gathedge.shared.domain.{AnswerOutcome, Gender, PartOfSpeech, WordLanguage, WordPreference}
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

  private def newUser(): RIO[UserRepository, Long] = UserRepository.insertGuest("light", "en", 0L).map(_.id)

  private def dictionaryWord(
    language: WordLanguage,
    text: String,
    rank: Int = 1,
    gender: Option[Gender] = None,
  ): WordRow = {
    WordRow(
      id = 0L,
      language = WordLanguage.code(language),
      text = text,
      textNorm = text.toLowerCase,
      partOfSpeech = PartOfSpeech.code(PartOfSpeech.Noun),
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
      tag    <- WordRepository.insertTag(ownerId, name, name, 0L)
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
      tag <- WordRepository.insertTag(ownerId, name, name, 0L)
      _   <- ZIO.foreachDiscard(0 until count) { i =>
               for {
                 source <- WordRepository.ensureWord(dictionaryWord(sourceLanguage, s"$name-source-$i"))
                 target <- WordRepository.ensureWord(dictionaryWord(targetLanguage, s"$name-target-$i"))
                 _      <- WordRepository.pairTranslation(source.id, tag.id, target.id, 0L)
               } yield ()
             }
    } yield tag.id
  }

  /** A tag owned by `ownerId` carrying two marked pairs whose source words differ but whose target word is the same
    * (`$name-target`) — the ambiguous collision [[GameServiceLive.dedupeSameTarget]] guards a limited play against.
    * Sources are `$name-source-0`/`$name-source-1`.
    */
  private def eligibleTagWithCollidingTarget(
    ownerId: Long,
    name: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
  ): RIO[WordRepository, Long] = {
    for {
      tag    <- WordRepository.insertTag(ownerId, name, name, 0L)
      target <- WordRepository.ensureWord(dictionaryWord(targetLanguage, s"$name-target"))
      _      <- ZIO.foreachDiscard(0 until 2) { i =>
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
      test("myGames answers only the caller's own games, with tag names and play counts") {
        for {
          owner      <- newUser()
          other      <- newUser()
          ownTagId   <- eligibleTagWithPairs(owner, "mine", WordLanguage.De, WordLanguage.Hu, count = 1)
          otherTagId <- eligibleTagWithPairs(other, "notMine", WordLanguage.De, WordLanguage.Hu, count = 1)
          ownGame    <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(ownTagId))
          _          <- GameService.createGame(other, WordLanguage.De, WordLanguage.Hu, List(otherTagId))
          unplayed   <- GameService.myGames(owner)
          firstPlay  <- GameService.startPlay(ownGame.slug, owner)
          _          <- playThrough(firstPlay.playId, "mine", owner)
          secondPlay <- GameService.startPlay(ownGame.slug, owner)
          _          <- playThrough(secondPlay.playId, "mine", owner)
          played     <- GameService.myGames(owner)
        } yield assertTrue(
          // Only the caller's own game comes back — the other account's game (and its tag) is invisible here.
          unplayed.map(_.slug) == List(ownGame.slug),
          unplayed.head.tagNames == List("mine"),
          unplayed.head.sourceLanguage == WordLanguage.De,
          unplayed.head.targetLanguage == WordLanguage.Hu,
          unplayed.head.playCount == 0L,
          played.head.playCount == 2L,
        )
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
      test("a limited play never draws two source words that share the same target translation") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithCollidingTarget(owner, "collision", WordLanguage.De, WordLanguage.Hu)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner, wordLimit = Some(1))
        } yield assertTrue(started.wordCount == 1)
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
      test("Unplayed preference fills the sample from never-answered words first, in this direction only") {
        for {
          owner      <- newUser()
          tagId      <- eligibleTagWithPairs(owner, "unplayedPref", WordLanguage.De, WordLanguage.Hu, count = 4)
          created    <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          warmup     <- GameService.startPlay(created.slug, owner, wordLimit = Some(1))
          _          <- playThrough(warmup.playId, "unplayedPref", owner)
          warmupWord <- GameService.getResults(warmup.playId, owner).map(_.answers.head.wordText)
          narrowed   <- GameService.startPlay(
                          created.slug,
                          owner,
                          wordLimit = Some(3),
                          wordPreference = WordPreference.Unplayed,
                        )
          results    <- GameService.getResults(narrowed.playId, owner)
        } yield assertTrue(
          // Three of the four eligible words are sampled; the one already answered by this player, in this
          // direction, is the one most likely left out — asserted as "never all four fit, and the previously
          // answered word is not required to reappear" rather than a flaky exact-set check, since ties among
          // the three never-played words are broken by shuffle.
          results.wordCount == 3,
          results.variant.wordPreference == WordPreference.Unplayed,
          !results.answers.exists(_.wordText == warmupWord),
        )
      },
      test("MostMistakes preference ranks by this player's wrong-answer count, in this direction only") {
        for {
          owner       <- newUser()
          tag         <- WordRepository.insertTag(owner, "mistakePref", "mistakePref", 0L)
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
          owner    <- newUser()
          tagId    <- eligibleTagWithPairs(owner, "previewAnon", WordLanguage.De, WordLanguage.Hu, count = 3)
          created  <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          anon     <- GameService.playSetupPreview(created.slug, None, swapDirection = false, WordPreference.All)
          unplayed <- GameService.playSetupPreview(created.slug, None, swapDirection = false, WordPreference.Unplayed)
        } yield assertTrue(anon.size == 3, unplayed.size == 3)
      },
      test("starting a play when the game's tags currently carry nothing eligible fails") {
        for {
          owner   <- newUser()
          tag     <- WordRepository.insertTag(owner, "emptied", "emptied", 0L)
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
      test("a gendered source word's prompt and results carry its article, an ungendered one is unaffected") {
        for {
          owner   <- newUser()
          tag     <- WordRepository.insertTag(owner, "genderedSource", "genderedSource", 0L)
          source  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Tisch", gender = Some(Gender.Der)))
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
          results.answers.head.expectedText == "asztal",
          results.answers.head.outcome == AnswerOutcome.Correct,
        )
      },
      test("a word with more than one marked translation accepts any of them") {
        for {
          owner    <- newUser()
          tag      <- WordRepository.insertTag(owner, "multi", "multi", 0L)
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
          // Whichever of the two marked translations is typed, it scores correct and the results show that one.
          results.answers.head.outcome == AnswerOutcome.Correct,
          results.answers.head.expectedText == "multi-target-2",
          results2.answers.head.outcome == AnswerOutcome.Correct,
          results2.answers.head.expectedText == "multi-target-1",
        )
      },
      test("a gendered expected answer requires its article to score as correct") {
        for {
          owner       <- newUser()
          tag         <- WordRepository.insertTag(owner, "genderedTarget", "genderedTarget", 0L)
          source      <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "asztal"))
          target      <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Tisch", gender = Some(Gender.Der)))
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
          bareResults.answers.head.expectedText == "der Tisch",
          // Typing the noun with its correct article scores correct.
          fullResults.answers.head.outcome == AnswerOutcome.Correct,
        )
      },
      test("includeDefiniteArticles defaults to true and, when false, strips the article everywhere") {
        for {
          owner          <- newUser()
          tag            <- WordRepository.insertTag(owner, "bareArticle", "bareArticle", 0L)
          source         <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "szekreny"))
          target         <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Schrank", gender = Some(Gender.Der)))
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
          results.answers.head.expectedText == "Schrank",
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
          mine   <- GameService.myPlays(player, None, 1, 20, None, false)
        } yield assertTrue(
          mine.total == 2L,
          mine.items.map(_.playId).toSet == Set(playA.playId, playB.playId),
          mine.items.map(_.gameSlug).toSet == Set(gameA.slug, gameB.slug),
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
          theirs  <- GameService.myPlays(other, None, 1, 20, None, false)
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
    ).provide(layer)
  }
}
