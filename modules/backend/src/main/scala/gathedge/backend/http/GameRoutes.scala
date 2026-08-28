package gathedge.backend.http

import gathedge.backend.service.{AuthService, GameService}
import gathedge.shared.api.GameEndpoints
import gathedge.shared.domain.{User, WordLanguage, WordPreference}
import gathedge.shared.dto.{
  CreateGameRequest,
  GameCreated,
  Paging,
  RenameGameRequest,
  SortDirection,
  StartPlayRequest,
  SubmitAnswerRequest,
}
import zio.*
import zio.http.*

/** Creating, reading and renaming a vocabulary quiz (the setup/detail/rename endpoints), plus playing one
  * (startPlay/nextPrompt/submitAnswer/results).
  *
  * `getRoute` and `playSetupRoute` are wrapped in `optionalUser` rather than `authenticated`, the same reasoning
  * `WordRoutes` applies to the dictionary reads: a shared game link, and the play-variant picker's preview it leads to,
  * must both be viewable before any guest is minted. `getRoute`'s handler does not consume `Option[User]` —
  * `GameDetail` carries no owner-only data — but `playSetupRoute` does, the same as `WordRoutes.listRoute`/`.getRoute`:
  * a signed-in caller's own play history still shapes the `Unplayed`/`MostMistakes` ordering, while an anonymous caller
  * simply has none.
  *
  * The aspects are on the `Routes` values, never on an individual `handler`: `getRoute`/`renameRoute`/`playSetupRoute`
  * take a path parameter, and attaching a context-providing aspect to one of those compiles and then throws
  * `ClassCastException` at request time, because the handler is handed a bare `Request` where it expects the
  * `(param, Request)` tuple.
  */
object GameRoutes {

  /** The signed-in account. Supplied by `authenticated`. */
  private def userId: URIO[User, Long] = ZIO.service[User].map(_.id)

  /** The reader, when there is one. Supplied by `optionalUser`. */
  private def reader: URIO[Option[User], Option[Long]] = ZIO.service[Option[User]].map(_.map(_.id))

  /** Language codes are read leniently, the same as `WordRoutes.languageOf`: an unrecognised or missing one falls back
    * rather than failing the request.
    */
  private def languageOf(requested: Option[String]): WordLanguage = {
    requested.flatMap(WordLanguage.fromString).getOrElse(WordLanguage.En)
  }

  /** A `wordPreference` query/body value, read leniently like [[languageOf]]: an unrecognised or missing one falls back
    * to [[WordPreference.All]] rather than failing the request.
    */
  private def preferenceOf(requested: Option[String]): WordPreference = {
    requested.flatMap(WordPreference.fromString).getOrElse(WordPreference.All)
  }

  /** `"1,2,3"` -> `[1, 2, 3]`, silently dropping anything that fails to parse — the setup screen never sends a
    * malformed id, and a stray one here is not worth a 400 over, the same leniency [[languageOf]] applies.
    */
  private def tagIdsOf(requested: Option[String]): List[Long] = {
    requested.toList.flatMap(_.split(",").toList).map(_.trim).flatMap(_.toLongOption)
  }

  /** An empty `q=` is the filter box after being cleared, which is not a filter — same rule `AdminRoutes.searchTerm`
    * follows.
    */
  private def searchTerm(requested: Option[String]): Option[String] = {
    requested.map(_.trim).filter(_.nonEmpty)
  }

  private val setupRoute = {
    GameEndpoints.setup.implementHandler(
      handler { (source: Option[String], target: Option[String]) =>
        userId.flatMap(id => GameService.eligibleTags(languageOf(source), languageOf(target), id))
      }
    )
  }

  private val setupWordsRoute = {
    GameEndpoints.setupWords.implementHandler(
      handler { (source: Option[String], target: Option[String], tagIds: Option[String]) =>
        userId.flatMap(_ => GameService.eligibleWords(languageOf(source), languageOf(target), tagIdsOf(tagIds)))
      }
    )
  }

  private val allGamesRoute = {
    GameEndpoints.allGames.implementHandler(
      handler {
        (
          page: Option[Int],
          pageSize: Option[Int],
          sort: Option[String],
          dir: Option[String],
          q: Option[String],
        ) =>
          userId.flatMap { _ =>
            GameService.allGames(
              searchTerm(q),
              Paging.boundedPage(page),
              Paging.boundedPageSize(pageSize),
              sort,
              SortDirection.isDescending(dir),
            )
          }
      }
    )
  }

  private val myPlaysRoute = {
    GameEndpoints.myPlays.implementHandler(
      handler {
        (
          gameId: Option[Long],
          page: Option[Int],
          pageSize: Option[Int],
          sort: Option[String],
          dir: Option[String],
          q: Option[String],
        ) =>
          userId.flatMap { id =>
            GameService.myPlays(
              id,
              gameId,
              searchTerm(q),
              Paging.boundedPage(page),
              Paging.boundedPageSize(pageSize),
              sort,
              SortDirection.isDescending(dir),
            )
          }
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
      handler { (slug: String, body: StartPlayRequest) =>
        userId.flatMap(id => {
          GameService
            .startPlay(slug, id, body.swapDirection, body.wordLimit, body.includeDefiniteArticles, body.wordPreference)
            .mapError(ApiFailures.gameStartPlay)
        })
      }
    )
  }

  private val playSetupRoute = {
    GameEndpoints.playSetup.implementHandler(
      handler { (slug: String, swapDirection: Option[Boolean], wordPreference: Option[String]) =>
        reader.flatMap(who => {
          GameService
            .playSetupPreview(slug, who, swapDirection.getOrElse(false), preferenceOf(wordPreference))
            .mapError(ApiFailures.game)
        })
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

  private val listPlaysRoute = {
    GameEndpoints.listPlays.implementHandler(
      handler {
        (
          slug: String,
          page: Option[Int],
          pageSize: Option[Int],
          sort: Option[String],
          dir: Option[String],
          q: Option[String],
        ) =>
          userId.flatMap(id => {
            GameService
              .listPlays(
                slug,
                id,
                Paging.boundedPage(page),
                Paging.boundedPageSize(pageSize),
                searchTerm(q),
                sort,
                SortDirection.isDescending(dir),
              )
              .mapError(ApiFailures.gameResults)
          })
      }
    )
  }

  private val playDetailRoute = {
    GameEndpoints.playDetail.implementHandler(
      handler { (slug: String, playId: Long) =>
        userId.flatMap(id => GameService.getPlayDetail(slug, playId, id).mapError(ApiFailures.gameResults))
      }
    )
  }

  private val publicRoutes = Routes(getRoute, playSetupRoute) @@ RouteSupport.optionalUser

  private val sessionRoutes = {
    Routes(
      setupRoute,
      setupWordsRoute,
      allGamesRoute,
      myPlaysRoute,
      createRoute,
      renameRoute,
      startPlayRoute,
      nextPromptRoute,
      submitAnswerRoute,
      resultsRoute,
      listPlaysRoute,
      playDetailRoute,
    ) @@ RouteSupport.authenticated
  }

  val routes: Routes[AuthService & GameService, Response] = {
    (publicRoutes ++ sessionRoutes) @@ RouteSupport.csrf
  }
}
