package gathedge.shared.domain

import zio.json.*

/** Wire-safe projection of a user account. Never carries a password hash.
  *
  * `emailVerified` says whether the address has been proven, not whether the account is usable — whether an unverified
  * account may sign in at all is the backend's `app.require-email-verification`. The frontend reads it only to decide
  * whether to nag.
  *
  * `email` is absent exactly when `isGuest` is true. A guest is an account minted without credentials the first time a
  * visitor tags a word, so that the vocabulary needs no sign-up; it holds an ordinary session and is an ordinary user
  * to every route and repository. It gains an address and a password — in place, keeping everything it owns — by
  * upgrading, which is what clears `isGuest`.
  */
final case class User(
  id: Long,
  email: Option[String],
  isAdmin: Boolean,
  theme: Theme,
  locale: Locale,
  createdAt: String,
  emailVerified: Boolean,
  isGuest: Boolean,
) derives JsonCodec
