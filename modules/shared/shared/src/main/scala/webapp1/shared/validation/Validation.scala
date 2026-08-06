package webapp1.shared.validation

/** Validation shared between the signup form (frontend) and the signup/create-user endpoints (backend), so the same
  * rules apply in both places per summary.md.
  */
object Validation {

  private val emailPattern = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r

  val minPasswordLength = 8

  /** jbcrypt ignores everything past the 72nd byte of a password, so a longer one would only look stronger than it is.
    * Reject it rather than silently truncating.
    */
  val maxPasswordLength = 72

  // The upper bounds below mirror the column widths in db/migration/*: exceeding one used to reach
  // the database and come back as a constraint violation (a 500), instead of a field error.
  val maxEmailLength = 255  // users.email, group_invitations.email
  val maxNameLength  = 255  // groups.name
  val maxTextLength  = 2000 // todo_items.text, group_pairs.source/target

  def isValidEmail(email: String): Boolean = {
    emailPattern.matches(email.trim) && email.trim.length <= maxEmailLength
  }

  def isValidPassword(password: String): Boolean = {
    password.length >= minPasswordLength && password.length <= maxPasswordLength
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
    else if (password.length > maxPasswordLength)
      Left(s"Password must be at most $maxPasswordLength characters")
    else
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
