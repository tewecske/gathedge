package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.ProgressSharePaths
import gathedge.shared.dto.{GameResults, MyPlayPage, RedeemShareRequest, SharedViewer, SharedWithMe, ShareCodeResponse}
import zio.json._

import HttpClient.query

/** Progress sharing's calls, over [[HttpClient]]. Every call needs a session — there is no public half, unlike the game
  * catalog. Each method and path comes from `ProgressSharePaths`, the object the backend builds its routes from.
  */
object ProgressShareApiClient {

  /** The caller's own share code, minted on the first call and the same one answered again after. */
  def code(): EventStream[Either[ApiError, ShareCodeResponse]] = {
    HttpClient.call[ShareCodeResponse](ProgressSharePaths.code())
  }

  def redeem(code: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(ProgressSharePaths.redeem(), Some(RedeemShareRequest(code).toJson))
  }

  /** Every account currently sharing its game history with the caller. */
  def sharedWithMe(): EventStream[Either[ApiError, List[SharedWithMe]]] = {
    HttpClient.call[List[SharedWithMe]](ProgressSharePaths.sharedWithMe())
  }

  /** Every account the caller currently shares its own game history with. */
  def viewers(): EventStream[Either[ApiError, List[SharedViewer]]] = {
    HttpClient.call[List[SharedViewer]](ProgressSharePaths.viewers())
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
    HttpClient.call[MyPlayPage](
      ProgressSharePaths
        .sharerPlays(sharerUserId)
        .withQuery(
          query(
            "gameId"   -> gameId,
            "page"     -> page,
            "pageSize" -> pageSize,
            "sort"     -> sort,
            "dir"      -> dir,
            "q"        -> search,
          )
        )
    )
  }

  /** One of the sharer's plays in full — the share-scoped counterpart of `AdminApiClient.userPlayResults`. */
  def sharerPlayResults(sharerUserId: Long, playId: Long): EventStream[Either[ApiError, GameResults]] = {
    HttpClient.call[GameResults](ProgressSharePaths.sharerPlayResults(sharerUserId, playId))
  }

  def revokeViewer(viewerUserId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(ProgressSharePaths.revokeViewer(viewerUserId))
  }
}
