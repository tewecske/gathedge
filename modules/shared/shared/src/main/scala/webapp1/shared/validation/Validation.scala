package webapp1.shared.validation

/** Validation shared between the signup form (frontend) and the signup/create-user endpoints (backend), so the same
  * rules apply in both places per summary.md.
  */
object Validation {

  private val emailPattern = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r

  val minPasswordLength = 8

  /** jbcrypt ignores everything past the 72nd byte of a password, so a longer one would only look stronger than it is.
    * Reject it rather than silently truncating.
    *
    * '''Bytes, not characters.''' This was measured against `String.length` — UTF-16 code units — which let a 72
    * *character* password containing anything outside ASCII through to bcrypt, where the tail past the 72nd byte was
    * dropped exactly as this limit exists to prevent. A password of 72 accented letters is 144 bytes, of which half was
    * silently ignored.
    */
  val maxPasswordLength = 72

  /** How many bytes `value` occupies when encoded as UTF-8, which is what bcrypt counts.
    *
    * Written out rather than `getBytes(UTF_8).length` so it costs no allocation and makes no assumption about which
    * charset APIs the Scala.js side links in — this file is cross-compiled, and the frontend runs the same check.
    *
    * A surrogate counts as two because a well-formed pair (two chars) encodes to four bytes. A lone surrogate would
    * really encode as a three-byte replacement character; it is off by one byte for input that is already malformed,
    * which does not matter to a length cap.
    */
  def utf8Length(value: String): Int = {
    var total = 0
    var index = 0
    while (index < value.length) {
      val ch = value.charAt(index)
      if (ch < 0x80)
        total += 1
      else if (ch < 0x800 || ch.isSurrogate)
        total += 2
      else
        total += 3
      index += 1
    }
    total
  }

  // The upper bounds below mirror the column widths in db/migration/*: exceeding one used to reach
  // the database and come back as a constraint violation (a 500), instead of a field error.
  val maxEmailLength = 255  // users.email, group_invitations.email
  val maxNameLength  = 255  // groups.name
  val maxTextLength  = 2000 // todo_items.text, group_pairs.source/target

  def isValidEmail(email: String): Boolean = {
    emailPattern.matches(email.trim) && email.trim.length <= maxEmailLength
  }

  /** The minimum stays in characters — it is a statement to the user about how much to type — while the maximum is in
    * bytes, because it is a statement about what bcrypt will read.
    */
  def isValidPassword(password: String): Boolean = {
    password.length >= minPasswordLength && utf8Length(password) <= maxPasswordLength
  }

  def validateEmail(email: String): Either[String, String] = {
    if (email.trim.isEmpty)
      Left("Email is required")
    else if (email.trim.length > maxEmailLength)
      Left(s"Email must be at most $maxEmailLength characters")
    else if (!isValidEmail(email))
      Left("Invalid email format")
    else
      Right(email.trim)
  }

  def validatePassword(password: String): Either[String, String] = {
    if (password.isEmpty)
      Left("Password is required")
    else if (password.length < minPasswordLength)
      Left(s"Password must be at least $minPasswordLength characters")
    else if (utf8Length(password) > maxPasswordLength) {
      // Not "at most 72 characters": the limit is 72 bytes, so a password of accented or non-Latin
      // characters trips it well before its 72nd character, and quoting a number would misdescribe it.
      Left("Password is too long")
    } else
      Right(password)
  }

  def validateNonBlank(value: String, fieldName: String, maxLength: Int = maxTextLength): Either[String, String] = {
    val trimmed = value.trim
    if (trimmed.isEmpty)
      Left(s"$fieldName is required")
    else if (trimmed.length > maxLength)
      Left(s"$fieldName must be at most $maxLength characters")
    else
      Right(trimmed)
  }
}
