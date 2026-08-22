package gathedge.backend.db

import gathedge.backend.TestDataSource
import zio._
import zio.test._

object GameRepositorySpec extends ZIOSpecDefault {

  private val layer = TestDataSource.sqlite >>> (UserRepository.test ++ WordRepository.test ++ GameRepository.test)

  private def newUser(): RIO[UserRepository, Long] = UserRepository.insertGuest("light", "en", 0L).map(_.id)

  def spec = {
    suite("GameRepository.answerOutcomesFor")(
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
      }
    ).provide(layer)
  }
}
