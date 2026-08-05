package webapp1.shared.domain

import zio.json.*

/** Wire-safe projection of a user account. Never carries a password hash.
  *
  * `emailVerified` says whether the address has been proven, not whether the account is usable — whether an unverified
  * account may sign in at all is the backend's `app.require-email-verification`. The frontend reads it only to decide
  * whether to nag.
  */
final case class User(
  id: Long,
  email: String,
  isAdmin: Boolean,
  theme: Theme,
  createdAt: String,
  emailVerified: Boolean,
) derives JsonCodec
