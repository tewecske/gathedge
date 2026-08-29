package gathedge.shared.api

import gathedge.shared.dto.{GameResults, MyPlayPage, RedeemShareRequest, SharedViewer, SharedWithMe, ShareCodeResponse}
import zio.http.{Method, Status}
import zio.http.codec.{HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, outFailure, withCodecError}
import ApiSchemas.given

/** Letting one account read another's game history, on either side's own say-so — a "sharer" whose plays become visible
  * and a "viewer" who may read them, joined by a share code, never a role like "parent" or "teacher".
  *
  * The shape mirrors `AuthEndpoints`'s guest transfer code: minting is idempotent (the same code answers every call
  * until revoked) and redeeming does not consume it, so more than one viewer may redeem the same code. Unlike the guest
  * code, redeeming here never signs anyone in — both sides must already be signed in as themselves, so every endpoint
  * sits behind `authenticated`.
  */
object ProgressShareEndpoints {

  private val sharerUserId = PathCodec.long("sharerUserId")
  private val viewerUserId = PathCodec.long("viewerUserId")
  private val playId       = PathCodec.long("playId")

  private val gameIdQuery   = HttpCodec.query[Long]("gameId").optional
  private val pageQuery     = HttpCodec.query[Int]("page").optional
  private val pageSizeQuery = HttpCodec.query[Int]("pageSize").optional
  private val sortQuery     = HttpCodec.query[String]("sort").optional
  private val dirQuery      = HttpCodec.query[String]("dir").optional
  private val searchQuery   = HttpCodec.query[String]("q").optional

  private val noContent = HttpCodec.status(Status.NoContent)

  /** Mints the caller's own share code, or answers the one already minted — see `ProgressShareService.issueCode`. */
  val code = {
    Endpoint(Method.POST / "api" / "progress-shares" / "code")
      .out[ShareCodeResponse]
      .outFailure(failure.unauthorized)
  }

  /** Redeems a code, granting the caller read access to its owner's game history. 404 covers an unknown or revoked
    * code, the same "one answer either way" rule `AuthEndpoints.claimGuest` follows so the code space cannot be probed;
    * 400 covers redeeming one's own code; 409 covers a share that already exists; 429 covers the caller's own
    * `RateLimitKey.shareRedeem` budget, the same reason `claimGuest` has one for guessing.
    */
  val redeem = {
    Endpoint(Method.POST / "api" / "progress-shares" / "redeem")
      .in[RedeemShareRequest]
      .withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict, failure.tooManyRequests)
  }

  /** Every account that may currently read the caller's game history. */
  val viewers = {
    Endpoint(Method.GET / "api" / "progress-shares" / "viewers")
      .out[List[SharedViewer]]
      .outFailure(failure.unauthorized)
  }

  /** Every account whose game history the caller may currently read. */
  val sharedWithMe = {
    Endpoint(Method.GET / "api" / "progress-shares" / "shared-with-me")
      .out[List[SharedWithMe]]
      .outFailure(failure.unauthorized)
  }

  /** One page of `sharerUserId`'s plays across every game, for a viewer the sharer has granted access to. `q` narrows
    * to games whose name contains it, a case-insensitive substring, the same as `AdminEndpoints.userPlays`. 403 covers
    * a caller with no share from `sharerUserId`.
    */
  val sharerPlays = {
    Endpoint(Method.GET / "api" / "progress-shares" / sharerUserId / "plays")
      .query(gameIdQuery)
      .query(pageQuery)
      .query(pageSizeQuery)
      .query(sortQuery)
      .query(dirQuery)
      .query(searchQuery)
      .withCodecError
      .out[MyPlayPage]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden)
  }

  /** One of `sharerUserId`'s plays, with its score and full answer history — the share-scoped counterpart of
    * `AdminEndpoints.userPlayResults`, and of `GameEndpoints.results`, which is owner-only. 403 covers a caller with no
    * share from `sharerUserId`; `playId` must belong to `sharerUserId`, else 404, which is what stops a viewer reading
    * a play id belonging to somebody else.
    */
  val sharerPlayResults = {
    Endpoint(Method.GET / "api" / "progress-shares" / sharerUserId / "plays" / playId / "results").withCodecError
      .out[GameResults]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** Revokes one viewer's access to the caller's game history. Idempotent — revoking a viewer with no share answers the
    * same 204 as one that had one, so a stale page reloading this twice is not an error.
    */
  val revokeViewer = {
    Endpoint(Method.DELETE / "api" / "progress-shares" / "viewers" / viewerUserId)
      .outCodec(noContent)
      .outFailure(failure.unauthorized)
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] =
    List(code, redeem, viewers, sharedWithMe, sharerPlays, sharerPlayResults, revokeViewer)
}
