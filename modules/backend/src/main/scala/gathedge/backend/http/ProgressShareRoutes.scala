package gathedge.backend.http

import gathedge.backend.service.{AuthService, GameService, ProgressShareService}
import gathedge.shared.api.ProgressShareEndpoints
import gathedge.shared.domain.User
import gathedge.shared.dto.{Paging, RedeemShareRequest, ShareCodeResponse, SortDirection}
import zio.*
import zio.http.*

/** Letting one account read another's game history — minting/redeeming a share code, listing either side of a share,
  * and reading a sharer's play history once a share exists. See `shared.api.ProgressShareEndpoints`.
  */
object ProgressShareRoutes {

  private def userId: URIO[User, Long] = ZIO.service[User].map(_.id)

  /** An empty `q=` is the filter box after the viewer has cleared it, which is not a filter — the same rule
    * `AdminRoutes` applies to the administrator's copy of this listing.
    */
  private def searchTerm(requested: Option[String]): Option[String] = {
    requested.map(_.trim).filter(_.nonEmpty)
  }

  private val codeRoute = {
    ProgressShareEndpoints.code.implementHandler(
      handler((_: Unit) => userId.flatMap(ProgressShareService.issueCode).map(ShareCodeResponse.apply))
    )
  }

  private val redeemRoute = {
    ProgressShareEndpoints.redeem.implementHandler(
      handler { (body: RedeemShareRequest) =>
        userId.flatMap(id => ProgressShareService.redeem(id, body.code).mapError(ApiFailures.progressShareRedeem))
      }
    )
  }

  private val viewersRoute = {
    ProgressShareEndpoints.viewers.implementHandler(
      handler((_: Unit) => userId.flatMap(ProgressShareService.viewersOf))
    )
  }

  private val sharedWithMeRoute = {
    ProgressShareEndpoints.sharedWithMe.implementHandler(
      handler((_: Unit) => userId.flatMap(ProgressShareService.sharersOf))
    )
  }

  private val sharerPlaysRoute = {
    ProgressShareEndpoints.sharerPlays.implementHandler(
      handler {
        (
          sharerUserId: Long,
          gameId: Option[Long],
          page: Option[Int],
          pageSize: Option[Int],
          sort: Option[String],
          dir: Option[String],
          q: Option[String],
        ) =>
          userId.flatMap { viewerId =>
            ProgressShareService.requireShareAccess(viewerId, sharerUserId).mapError(ApiFailures.progressShareAccess) *>
              GameService.playsOf(
                sharerUserId,
                gameId,
                Paging.boundedPage(page),
                Paging.boundedPageSize(pageSize),
                sort,
                SortDirection.isDescending(dir),
                searchTerm(q),
              )
          }
      }
    )
  }

  /** One of the sharer's plays in full. Two checks, not one: the share itself, and `resultsForPlayer`'s own "this play
    * belongs to that account" rule — the second is what stops a viewer walking play ids that are not the sharer's.
    */
  private val sharerPlayResultsRoute = {
    ProgressShareEndpoints.sharerPlayResults.implementHandler(
      handler { (sharerUserId: Long, playId: Long) =>
        userId.flatMap { viewerId =>
          ProgressShareService.requireShareAccess(viewerId, sharerUserId).mapError(ApiFailures.progressShareAccess) *>
            GameService.resultsForPlayer(playId, sharerUserId).mapError(ApiFailures.game)
        }
      }
    )
  }

  private val revokeViewerRoute = {
    ProgressShareEndpoints.revokeViewer.implementHandler(
      handler((viewerUserId: Long) => userId.flatMap(sharerId => ProgressShareService.revoke(sharerId, viewerUserId)))
    )
  }

  val routes: Routes[AuthService & GameService & ProgressShareService, Response] = {
    Routes(
      codeRoute,
      redeemRoute,
      viewersRoute,
      sharedWithMeRoute,
      sharerPlaysRoute,
      sharerPlayResultsRoute,
      revokeViewerRoute,
    ) @@ RouteSupport.authenticated @@ RouteSupport.csrf
  }
}
