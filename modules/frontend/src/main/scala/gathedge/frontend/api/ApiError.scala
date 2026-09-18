package gathedge.frontend.api

/** A failed call, as the pages see it: the status, a message to show, and per-field messages for a form.
  *
  * The messages are already worded in the reader's language. The server sends catalog keys rather than prose (see
  * `shared.api.ApiFailure`), and `HttpClient` resolves them at the seam — so a page renders `err.message` exactly as it
  * always did and needs to know nothing about i18n.
  *
  * Status `0` is the one value no server produces — it marks a call that never got an answer (offline, a dead socket, a
  * response nothing could decode).
  */
final case class ApiError(status: Int, message: String, fieldErrors: Map[String, String])
