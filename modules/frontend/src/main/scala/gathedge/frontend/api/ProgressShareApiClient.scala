package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.ProgressShareEndpoints
import gathedge.shared.dto.{GameResults, MyPlayPage, RedeemShareRequest, SharedViewer, SharedWithMe, ShareCodeResponse}

import EndpointClient.{executor, run}

/** Progress sharing's calls, generated from `ProgressShareEndpoints` the same way [[GameApiClient]] is from
  * `GameEndpoints`. Every call needs a session — there is no public half, unlike the game catalog.
  */
object ProgressShareApiClient {

  /** The caller's own share code, minted on the first call and the same one answered again after. */
  def code(): EventStream[Either[ApiError, ShareCodeResponse]] = {
    run(executor(ProgressShareEndpoints.code(())))
  }

  def redeem(code: String): EventStream[Either[ApiError, Unit]] = {
    run(executor(ProgressShareEndpoints.redeem(RedeemShareRequest(code))))
  }

  /** Every account currently sharing its game history with the caller. */
  def sharedWithMe(): EventStream[Either[ApiError, List[SharedWithMe]]] = {
    run(executor(ProgressShareEndpoints.sharedWithMe(())))
  }

  /** Every account the caller currently shares its own game history with. */
  def viewers(): EventStream[Either[ApiError, List[SharedViewer]]] = {
    run(executor(ProgressShareEndpoints.viewers(())))
  }

  /** One page of `sharerUserId`'s plays, for a viewer that sharer has granted access to. */
  def sharerPlays(
    sharerUserId: Long,
    gameId: Option[Long] = None,
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
    search: Option[String] = None,
  ): EventStream[Either[ApiError, MyPlayPage]] = {
    run(executor(ProgressShareEndpoints.sharerPlays(sharerUserId, gameId, page, pageSize, sort, dir, search)))
  }

  /** One of the sharer's plays in full — the share-scoped counterpart of `AdminApiClient.userPlayResults`. */
  def sharerPlayResults(sharerUserId: Long, playId: Long): EventStream[Either[ApiError, GameResults]] = {
    run(executor(ProgressShareEndpoints.sharerPlayResults(sharerUserId, playId)))
  }

  def revokeViewer(viewerUserId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(ProgressShareEndpoints.revokeViewer(viewerUserId)))
  }
}
