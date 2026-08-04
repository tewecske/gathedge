package webapp1.shared.dto

import zio.json.*
import webapp1.shared.domain.{OAuthProvider, Theme, User}

final case class SignupRequest(email: String, password: String) derives JsonCodec
final case class LoginRequest(email: String, password: String) derives JsonCodec
final case class UpdateThemeRequest(theme: Theme) derives JsonCodec

final case class AuthResponse(user: User) derives JsonCodec

/** One social account linked to the signed-in user. `email` is whatever the provider reported when the link was made,
  * shown so the user can tell two accounts at the same provider apart; it is never what a sign-in matches on.
  */
final case class LinkedIdentity(provider: OAuthProvider, email: Option[String], createdAt: String) derives JsonCodec

/** Everything the settings page needs to decide what it may offer.
  *
  * `hasPassword` is false for an account created through a social sign-in, which is what turns the password form into
  * "set a password" instead of "change password". `available` is the providers the server actually has credentials for,
  * so the page never shows a button whose route would 404.
  */
final case class IdentitiesResponse(
  identities: List[LinkedIdentity],
  hasPassword: Boolean,
  available: List[OAuthProvider],
) derives JsonCodec

/** `currentPassword` is required only when the account already has one — a social-only account is setting its first. */
final case class SetPasswordRequest(currentPassword: Option[String], newPassword: String) derives JsonCodec

/** The social providers this deployment has credentials for. Read by the sign-in and sign-up forms, which have no
  * session yet and so cannot get the same list from [[IdentitiesResponse]].
  */
final case class ProvidersResponse(providers: List[OAuthProvider]) derives JsonCodec

/** RFC-7807-flavored problem response for validation/auth errors. */
final case class ErrorResponse(message: String, fieldErrors: Map[String, String] = Map.empty) derives JsonCodec
