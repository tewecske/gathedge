package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.GameEndpoints
import gathedge.shared.domain.{Tag, WordLanguage}
import gathedge.shared.dto.{CreateGameRequest, GameCreated}

import EndpointClient.{executor, run}

/** The game catalog's calls, generated from `GameEndpoints` the same way [[WordApiClient]] is from `WordEndpoints`.
  *
  * Both calls require a session — see `GameSetupPage`'s guest detour, which sits in front of each.
  */
object GameApiClient {

  /** The tags eligible for a quiz between `source` and `target`, own tags first — see `Tag.sorted`. */
  def setup(source: WordLanguage, target: WordLanguage): EventStream[Either[ApiError, List[Tag]]] = {
    run(executor(GameEndpoints.setup(Some(WordLanguage.code(source)), Some(WordLanguage.code(target)))))
  }

  def create(
    source: WordLanguage,
    target: WordLanguage,
    tagIds: List[Long],
  ): EventStream[Either[ApiError, GameCreated]] = {
    run(executor(GameEndpoints.create(CreateGameRequest(source, target, tagIds))))
  }
}
