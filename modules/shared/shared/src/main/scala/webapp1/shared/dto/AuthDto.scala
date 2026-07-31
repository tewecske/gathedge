package webapp1.shared.dto

import zio.json.*
import webapp1.shared.domain.{Theme, User}

final case class SignupRequest(email: String, password: String) derives JsonCodec
final case class LoginRequest(email: String, password: String) derives JsonCodec
final case class UpdateThemeRequest(theme: Theme) derives JsonCodec

final case class AuthResponse(user: User) derives JsonCodec

/** RFC-7807-flavored problem response for validation/auth errors. */
final case class ErrorResponse(message: String, fieldErrors: Map[String, String] = Map.empty) derives JsonCodec
