package webapp1.shared.domain

import zio.json.*

/** Wire-safe projection of a user account. Never carries a password hash. */
final case class User(
  id: Long,
  email: String,
  isAdmin: Boolean,
  theme: Theme,
  createdAt: String,
) derives JsonCodec
