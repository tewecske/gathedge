package gathedge.shared.dto

import zio.json.*

/** `POST /api/progress-shares/code`'s answer: the caller's own share code, minted on the first call and the same one
  * answered again on every call after — see `ProgressShareService.issueCode`.
  */
final case class ShareCodeResponse(code: String) derives JsonCodec

/** Redeems a share code, granting the caller (the viewer) access to the code's owner's (the sharer's) game history.
  */
final case class RedeemShareRequest(code: String) derives JsonCodec

/** One row of `GET /api/progress-shares/viewers` — an account that may currently read the caller's game history. */
final case class SharedViewer(userId: Long, email: Option[String], isGuest: Boolean, sharedAt: Long) derives JsonCodec

/** One row of `GET /api/progress-shares/shared-with-me` — an account whose game history the caller may currently read.
  */
final case class SharedWithMe(sharerUserId: Long, email: Option[String], isGuest: Boolean, sharedAt: Long)
    derives JsonCodec
