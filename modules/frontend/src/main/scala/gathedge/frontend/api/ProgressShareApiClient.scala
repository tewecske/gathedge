package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.dto.{GameResults, MyPlayPage, RedeemShareRequest, SharedViewer, SharedWithMe, ShareCodeResponse}
import zio.json._

import HttpClient.query

/** Progress sharing's calls, over [[HttpClient]] the same way [[GameApiClient]] is. Every call needs a session — there
  * is no public half, unlike the game catalog.
  */
object ProgressShareApiClient {

  /** The caller's own share code, minted on the first call and the same one answered again after. */
  def code(): EventStream[Either[ApiError, ShareCodeResponse]] = {
    HttpClient.post[ShareCodeResponse]("/api/progress-shares/code")
  }

  def redeem(code: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.POST, "/api/progress-shares/redeem", Some(RedeemShareRequest(code).toJson))
  }

  /** Every account currently sharing its game history with the caller. */
  def sharedWithMe(): EventStream[Either[ApiError, List[SharedWithMe]]] = {
    HttpClient.get[List[SharedWithMe]]("/api/progress-shares/shared-with-me")
  }

  /** Every account the caller currently shares its own game history with. */
  def viewers(): EventStream[Either[ApiError, List[SharedViewer]]] = {
    HttpClient.get[List[SharedViewer]]("/api/progress-shares/viewers")
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
    HttpClient.get[MyPlayPage](
      s"/api/progress-shares/$sharerUserId/plays${query(
          "gameId"   -> gameId,
          "page"     -> page,
          "pageSize" -> pageSize,
          "sort"     -> sort,
          "dir"      -> dir,
          "q"        -> search,
        )}"
    )
  }

  /** One of the sharer's plays in full — the share-scoped counterpart of `AdminApiClient.userPlayResults`. */
  def sharerPlayResults(sharerUserId: Long, playId: Long): EventStream[Either[ApiError, GameResults]] = {
    HttpClient.get[GameResults](s"/api/progress-shares/$sharerUserId/plays/$playId/results")
  }

  def revokeViewer(viewerUserId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/progress-shares/viewers/$viewerUserId")
  }
}
