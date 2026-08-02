package webapp1.frontend.api

/** A failed call, as the pages see it: the status, a message to show, and per-field messages for a form.
  *
  * Status `0` is the one value no server produces — it marks a call that never got an answer (offline, a dead socket, a
  * response nothing could decode).
  */
final case class ApiError(status: Int, message: String, fieldErrors: Map[String, String])
