package webapp1.shared.dto

import zio.json.*

final case class CreateUserRequest(email: String, password: String, isAdmin: Boolean) derives JsonCodec

/** `password` is left blank in the form to keep the existing password. */
final case class UpdateUserRequest(email: String, isAdmin: Boolean, password: Option[String]) derives JsonCodec
