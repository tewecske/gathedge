package gathedge.backend.service

import gathedge.backend.TestDataSource
import gathedge.backend.db.{
  AchievementRepository,
  GamePlayAnswerRow,
  GamePlayRow,
  GameRepository,
  GameRow,
  StreakRepository,
  UserRepository,
  UserStreakRow,
  WordRepository,
  WordRow,
}
import gathedge.shared.domain.{Achievements, AnswerOutcome, GameMode, Levels}
import gathedge.shared.dto.AchievementUnlock
import zio.*
import zio.test.*

import java.util.concurrent.atomic.AtomicLong

/** Achievement evaluation against Postgres. Plays are written straight through `GameRepository`, finished or not, so
  * each case states exactly the history it needs.
  */
object AchievementServiceSpec extends ZIOSpecDefault {

  private val layer = {
    (TestDataSource.postgres >>> (UserRepository.live ++ GameRepository.live ++ StreakRepository.live ++
      AchievementRepository.live ++ WordRepository.live)) >+> AchievementService.live
  }

  private val millisPerDay = 86_400_000L

  private val slugs = new AtomicLong(0L)

  private def newUser(): RIO[UserRepository, Long] = UserRepository.insertGuest("light", "en", 0L, None).map(_.id)

  private def newGame(ownerId: Long): RIO[GameRepository, Long] = {
    val slug = s"ach-${slugs.incrementAndGet()}"
    GameRepository.insertGame(GameRow(0L, ownerId, slug, slug, "de", "hu", 0L, 0L), Nil).map(_.id)
  }

  /** One play of `words` words on `day`, finished unless `finished` is false. Perfect unless `score` says otherwise. */
  private def play(
    userId: Long,
    gameId: Long,
    words: Int,
    day: Long = 10L,
    finished: Boolean = true,
    score: Option[Int] = None,
    mode: GameMode = GameMode.Typing,
    source: String = "de",
    target: String = "hu",
  ): RIO[GameRepository, Long] = {
    val at = day * millisPerDay + 3_600_000L
    GameRepository
      .insertPlay(
        GamePlayRow(
          id = 0L,
          gameId = gameId,
          playerUserId = userId,
          score = score.getOrElse(words),
          maxScore = words,
          wordCount = words,
          startedAt = at,
          finishedAt = Option.when(finished)(at),
          sourceLanguage = source,
          targetLanguage = target,
          mode = GameMode.code(mode),
        ),
        Nil,
      )
      .map(_.id)
  }

  /** A word for answer rows to point at. Its text is unique, so every call makes a new row. */
  private def newWord(): RIO[WordRepository, Long] = {
    val text = s"ach-word-${slugs.incrementAndGet()}"
    WordRepository
      .ensureWord(WordRow(0L, "de", text, text, "noun", "", 1, "dictionary", None, 0L, text))
      .map(_.id)
  }

  /** One answer row per outcome in `playId`, all for the same word pair. The play stays finished on `day`. */
  private def answers(
    playId: Long,
    outcomes: List[AnswerOutcome],
    day: Long = 10L,
  ): RIO[GameRepository & WordRepository, Unit] = {
    val at = day * millisPerDay + 3_600_000L
    for {
      word        <- newWord()
      translation <- newWord()
      _           <- ZIO.foreachDiscard(outcomes.zipWithIndex) { case (outcome, position) =>
                       GameRepository.recordAnswer(
                         GamePlayAnswerRow(0L, playId, word, translation, position, "x", AnswerOutcome.code(outcome), 0, at),
                         newScore = outcomes.size,
                         finishedAt = Some(at),
                       )
                     }
    } yield ()
  }

  /** The tiers an evaluation unlocked. Its play id names no play, so only the tiers are of interest. */
  private def unlocksOf(user: Long): RIO[AchievementService, List[AchievementUnlock]] = {
    AchievementService.evaluate(user, 0L).map(_.unlocks)
  }

  private def player(): RIO[UserRepository & GameRepository, (Long, Long)] = {
    for {
      user <- newUser()
      game <- newGame(user)
    } yield (user, game)
  }

  private def codes(unlocks: List[AchievementUnlock]): Set[(String, Int)] = unlocks.map(u => (u.code, u.tier)).toSet

  private val typing         = Achievements.gamesPlayedIn(GameMode.Typing).code
  private val multipleChoice = Achievements.gamesPlayedIn(GameMode.MultipleChoice).code

  def spec = {
    suite("AchievementService")(
      test("a nine-word play earns nothing") {
        for {
          ids         <- player()
          (user, game) = ids
          _           <- play(user, game, words = 9)
          unlocked    <- unlocksOf(user)
        } yield assertTrue(unlocked.isEmpty)
      },
      test("an unfinished play earns nothing") {
        for {
          ids         <- player()
          (user, game) = ids
          _           <- play(user, game, words = 10, finished = false)
          unlocked    <- unlocksOf(user)
        } yield assertTrue(unlocked.isEmpty)
      },
      test("a ten-word perfect play earns tier 1 of games played, its game type, and perfect games") {
        for {
          ids         <- player()
          (user, game) = ids
          _           <- play(user, game, words = 10)
          unlocked    <- unlocksOf(user)
        } yield assertTrue(
          codes(unlocked) == Set(
            (Achievements.gamesPlayed.code, 1),
            (typing, 1),
            (Achievements.perfectGames.code, 1),
          )
        )
      },
      test("a play short of the full score is not perfect") {
        for {
          ids         <- player()
          (user, game) = ids
          _           <- play(user, game, words = 10, score = Some(9))
          unlocked    <- unlocksOf(user)
        } yield assertTrue(!unlocked.exists(_.code == Achievements.perfectGames.code), unlocked.nonEmpty)
      },
      test("a per-type count ignores the other game types") {
        for {
          ids         <- player()
          (user, game) = ids
          _           <- ZIO.foreachDiscard(1 to 5)(_ => play(user, game, words = 10, mode = GameMode.MultipleChoice))
          overview    <- AchievementService.overview(user)
          byCode       = overview.achievements.map(a => a.code -> a).toMap
        } yield assertTrue(
          byCode(multipleChoice).count == 5,
          byCode(multipleChoice).tier == 2,
          byCode(multipleChoice).nextThreshold == Some(10),
          byCode(typing).count == 0,
          byCode(typing).tier == 0,
          byCode(typing).nextThreshold == Some(1),
          byCode(Achievements.gamesPlayed.code).count == 5,
        )
      },
      test("the first evaluation backfills past plays and the streak") {
        for {
          ids         <- player()
          (user, game) = ids
          _           <- ZIO.foreachDiscard(1 to 25)(i => play(user, game, words = 10, day = i.toLong, score = Some(0)))
          _           <- StreakRepository.upsert(UserStreakRow(user, 2, 7, 10, 25L, 0L))
          unlocked    <- unlocksOf(user)
        } yield assertTrue(
          codes(unlocked) ==
            (1 to 4).map(tier => (Achievements.gamesPlayed.code, tier)).toSet ++
            (1 to 4).map(tier => (typing, tier)).toSet ++
            Set(
              (Achievements.longestStreak.code, 1),
              (Achievements.longestStreak.code, 2),
              (Achievements.daysPlayed.code, 1),
            )
        )
      },
      test("a repeat evaluation writes no duplicate row") {
        for {
          ids         <- player()
          (user, game) = ids
          _           <- play(user, game, words = 10)
          first       <- unlocksOf(user)
          second      <- unlocksOf(user)
          rows        <- AchievementRepository.forUser(user)
        } yield assertTrue(
          first.size == 3,
          second.isEmpty,
          rows.size == 3,
          rows.map(row => (row.code, row.tier)).distinct.size == rows.size,
        )
      },
      test("a later play unlocks only the tiers it reaches") {
        for {
          ids         <- player()
          (user, game) = ids
          _           <- ZIO.foreachDiscard(1 to 4)(_ => play(user, game, words = 10, score = Some(0)))
          _           <- unlocksOf(user)
          _           <- play(user, game, words = 10, score = Some(0))
          fifth       <- unlocksOf(user)
          _           <- play(user, game, words = 10, score = Some(0))
          sixth       <- unlocksOf(user)
        } yield assertTrue(
          codes(fifth) == Set((Achievements.gamesPlayed.code, 2), (typing, 2)),
          sixth.isEmpty,
        )
      },
      test("marathon needs one qualifying play of fifty words") {
        for {
          ids         <- player()
          (user, game) = ids
          _           <- play(user, game, words = 49, score = Some(0))
          before      <- unlocksOf(user)
          _           <- play(user, game, words = 50, score = Some(0))
          after       <- unlocksOf(user)
        } yield assertTrue(
          !before.exists(_.code == Achievements.marathon.code),
          after.exists(u => u.code == Achievements.marathon.code && u.tier == 1),
        )
      },
      test("polyglot needs ten qualifying plays in each of two language pairs, either direction") {
        for {
          ids         <- player()
          (user, game) = ids
          // Five plays each way make one pair of ten.
          _           <- ZIO.foreachDiscard(1 to 5)(_ => play(user, game, words = 10, source = "de", target = "hu"))
          _           <- ZIO.foreachDiscard(1 to 5)(_ => play(user, game, words = 10, source = "hu", target = "de"))
          onePair     <- unlocksOf(user)
          _           <- ZIO.foreachDiscard(1 to 9)(_ => play(user, game, words = 10, source = "en", target = "de"))
          nine        <- unlocksOf(user)
          _           <- play(user, game, words = 10, source = "de", target = "en")
          twoPairs    <- unlocksOf(user)
        } yield assertTrue(
          !onePair.exists(_.code == Achievements.polyglot.code),
          !nine.exists(_.code == Achievements.polyglot.code),
          twoPairs.exists(u => u.code == Achievements.polyglot.code && u.tier == 1),
        )
      },
      test("comeback is a qualifying play after one or two missed days, never after three") {
        for {
          ids         <- player()
          (user, game) = ids
          _           <- play(user, game, words = 10, day = 10L, score = Some(0))
          _           <- play(user, game, words = 10, day = 11L, score = Some(0))
          _           <- play(user, game, words = 10, day = 15L, score = Some(0))
          lapsed      <- unlocksOf(user)
          // A short play keeps the streak but does not qualify, so day 18 is no comeback either.
          _           <- play(user, game, words = 3, day = 18L, score = Some(0))
          short       <- unlocksOf(user)
          _           <- play(user, game, words = 10, day = 21L, score = Some(0))
          resumed     <- unlocksOf(user)
        } yield assertTrue(
          !lapsed.exists(_.code == Achievements.comeback.code),
          !short.exists(_.code == Achievements.comeback.code),
          resumed.exists(u => u.code == Achievements.comeback.code && u.tier == 1),
        )
      },
      test("the overview lists every achievement, started or not, with the date of the current tier") {
        for {
          ids         <- player()
          (user, game) = ids
          _           <- TestClock.setTime(java.time.Instant.ofEpochMilli(5_000L))
          _           <- play(user, game, words = 10)
          overview    <- AchievementService.overview(user)
          byCode       = overview.achievements.map(a => a.code -> a).toMap
        } yield assertTrue(
          overview.achievements.map(_.code) == Achievements.all.map(_.code),
          byCode(Achievements.gamesPlayed.code).tier == 1,
          byCode(Achievements.gamesPlayed.code).unlockedAt == Some(5_000L),
          byCode(Achievements.gamesPlayed.code).nextThreshold == Some(5),
          byCode(Achievements.marathon.code).tier == 0,
          byCode(Achievements.marathon.code).unlockedAt.isEmpty,
          byCode(Achievements.marathon.code).nextThreshold == Some(1),
        )
      },
      test(
        "counts: a perfect play needs a non-zero maximum, and a stored mode code it cannot read counts only overall"
      ) {
        val base   = GamePlayRow(0L, 1L, 1L, 10, 10, 10, 0L, Some(0L))
        val counts = AchievementService.counts(List(base, base.copy(mode = "unknown")), None)
        assertTrue(
          counts(Achievements.gamesPlayed.code) == 2,
          counts(typing) == 1,
          counts(Achievements.perfectGames.code) == 2,
          counts(Achievements.longestStreak.code) == 0,
        )
      },
      test("a nine-word play gives no XP, not even for its correct answers") {
        for {
          ids         <- player()
          (user, game) = ids
          playId      <- play(user, game, words = 9)
          _           <- answers(playId, List.fill(9)(AnswerOutcome.Correct))
          reward      <- AchievementService.evaluate(user, playId)
        } yield assertTrue(
          reward.xp.gained == 0L,
          reward.xp.level == Levels.progress(0L),
        )
      },
      test("a ten-word play gives its play XP, its answers' XP, and the XP of the tiers it unlocked") {
        val outcomes = List.fill(7)(AnswerOutcome.Correct) ++ List.fill(2)(AnswerOutcome.Typo) :+ AnswerOutcome.Wrong
        for {
          ids         <- player()
          (user, game) = ids
          playId      <- play(user, game, words = 10)
          _           <- answers(playId, outcomes)
          reward      <- AchievementService.evaluate(user, playId)
          overview    <- AchievementService.overview(user)
        } yield {
          // 25 for the play; 7 correct (4 each), 2 typos (3 each), 1 wrong (1); and tier 1 of games played (25),
          // typing (25) and perfect games (100).
          val expected = 25L + 28L + 6L + 1L + 25L + 25L + 100L
          assertTrue(
            reward.xp.gained == expected,
            reward.xp.level.totalXp == expected,
            reward.xp.levelBefore == 1,
            reward.xp.level.level == 2,
            overview.level.totalXp == expected,
            overview.level.level == 2,
          )
        }
      },
      test("a play that crosses a level boundary reports the level before it") {
        for {
          ids         <- player()
          (user, game) = ids
          _           <- ZIO.foreachDiscard(1 to 4)(_ => play(user, game, words = 10))
          before      <- unlocksOf(user)
          fifth       <- play(user, game, words = 10)
          reward      <- AchievementService.evaluate(user, fifth)
        } yield {
          // Four plays: 4 × 25 play XP + tier 1 of games played (25), typing (25) and perfect games (100) = 250.
          // The fifth: 25 play XP + tier 2 of each: 50 + 50 + 200 = 325. Level 3 starts at 300, level 4 at 600.
          assertTrue(
            before.nonEmpty,
            reward.xp.gained == 325L,
            reward.xp.level.totalXp == 575L,
            reward.xp.levelBefore == 2,
            reward.xp.level.level == 3,
          )
        }
      },
    ).provide(layer)
  }
}
