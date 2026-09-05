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
  *
  * `username` and `name` are both optional and both the account's own. The username is a second thing a sign-in may
  * name the account by (`AuthService.login` takes either one), stored lowercased so matching it is an equality test;
  * the name is display only and nothing ever matches on it. A guest is minted with a random username and no name, so
  * that an account with no address still has something to be called.
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
  username: Option[String] = None,
  name: Option[String] = None,
) derives JsonCodec

object User {

  /** What to call this account on screen: the name it chose, else the username it signs in with, else its address.
    *
    * `None` only for a guest that has somehow lost its username, which is what leaves the caller to render its own
    * "guest" wording rather than an empty label.
    */
  extension (user: User) {
    def displayLabel: Option[String] = user.name.orElse(user.username).orElse(user.email)
  }
}
