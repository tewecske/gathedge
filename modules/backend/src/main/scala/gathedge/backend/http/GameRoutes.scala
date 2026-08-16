package gathedge.backend.http

import gathedge.backend.service.{AuthService, GameService}
import gathedge.shared.api.GameEndpoints
import gathedge.shared.domain.{User, WordLanguage}
import gathedge.shared.dto.{CreateGameRequest, GameCreated, RenameGameRequest, SubmitAnswerRequest}
import zio.*
import zio.http.*

/** Creating, reading and renaming a vocabulary quiz (the setup/detail/rename endpoints), plus playing one
  * (startPlay/nextPrompt/submitAnswer/results).
  *
  * `getRoute` is wrapped in `optionalUser` rather than `authenticated`, the same reasoning `WordRoutes` applies to the
  * dictionary reads: a shared game link must be viewable before any guest is minted. Unlike `WordRoutes.getRoute`, the
  * handler here does not consume `Option[User]` — `GameDetail` carries no owner-only data — but it still needs to sit
  * under an aspect that does not require a session.
  *
  * The aspects are on the `Routes` values, never on an individual `handler`: `getRoute`/`renameRoute` take a path
  * parameter, and attaching a context-providing aspect to one of those compiles and then throws `ClassCastException` at
  * request time, because the handler is handed a bare `Request` where it expects the `(param, Request)` tuple.
  */
object GameRoutes {

  /** The signed-in account. Supplied by `authenticated`. */
  private def userId: URIO[User, Long] = ZIO.service[User].map(_.id)

  /** Language codes are read leniently, the same as `WordRoutes.languageOf`: an unrecognised or missing one falls back
    * rather than failing the request.
    */
  private def languageOf(requested: Option[String]): WordLanguage = {
    requested.flatMap(WordLanguage.fromString).getOrElse(WordLanguage.En)
  }

  private val setupRoute = {
    GameEndpoints.setup.implementHandler(
      handler { (source: Option[String], target: Option[String]) =>
        userId.flatMap(id => GameService.eligibleTags(languageOf(source), languageOf(target), id))
      }
    )
  }

  private val createRoute = {
    GameEndpoints.create.implementHandler(
      handler { (body: CreateGameRequest) =>
        userId.flatMap(id => {
          GameService
            .createGame(id, body.sourceLanguage, body.targetLanguage, body.tagIds)
            .map(detail => GameCreated(detail.slug, detail.name))
            .mapError(ApiFailures.gameCreate)
        })
      }
    )
  }

  private val getRoute = {
    GameEndpoints.get.implementHandler(
      handler((slug: String) => GameService.getBySlug(slug).mapError(ApiFailures.game))
    )
  }

  private val renameRoute = {
    GameEndpoints.rename.implementHandler(
      handler { (slug: String, body: RenameGameRequest) =>
        userId.flatMap(id => GameService.rename(slug, body.name, id).mapError(ApiFailures.gameRename))
      }
    )
  }

  private val startPlayRoute = {
    GameEndpoints.startPlay.implementHandler(
      handler { (slug: String) =>
        userId.flatMap(id => GameService.startPlay(slug, id).mapError(ApiFailures.gameStartPlay))
      }
    )
  }

  private val nextPromptRoute = {
    GameEndpoints.nextPrompt.implementHandler(
      handler { (playId: Long) =>
        userId.flatMap(id => GameService.nextPrompt(playId, id).mapError(ApiFailures.gamePlay))
      }
    )
  }

  private val submitAnswerRoute = {
    GameEndpoints.submitAnswer.implementHandler(
      handler { (playId: Long, body: SubmitAnswerRequest) =>
        userId.flatMap(id =>
          GameService.submitAnswer(playId, body.wordId, body.answerText, id).mapError(ApiFailures.gamePlay)
        )
      }
    )
  }

  private val resultsRoute = {
    GameEndpoints.results.implementHandler(
      handler { (playId: Long) =>
        userId.flatMap(id => GameService.getResults(playId, id).mapError(ApiFailures.gamePlay))
      }
    )
  }

  private val publicRoutes = Routes(getRoute) @@ RouteSupport.optionalUser

  private val sessionRoutes = {
    Routes(setupRoute, createRoute, renameRoute, startPlayRoute, nextPromptRoute, submitAnswerRoute, resultsRoute)
      @@ RouteSupport.authenticated
  }

  val routes: Routes[AuthService & GameService, Response] = {
    (publicRoutes ++ sessionRoutes) @@ RouteSupport.csrf
  }
}
