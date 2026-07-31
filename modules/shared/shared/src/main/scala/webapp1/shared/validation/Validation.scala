package webapp1.shared.validation

/** Validation shared between the signup form (frontend) and the signup/create-user
  * endpoints (backend), so the same rules apply in both places per summary.md.
  */
object Validation {

  private val emailPattern = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r

  val minPasswordLength = 8

  def isValidEmail(email: String): Boolean = {
    emailPattern.matches(email.trim)
  }

  def isValidPassword(password: String): Boolean = {
    password.length >= minPasswordLength
  }

  def validateEmail(email: String): Either[String, String] = {
    if (email.trim.isEmpty) Left("Email is required")
    else if (!isValidEmail(email)) Left("Invalid email format")
    else Right(email.trim)
  }

  def validatePassword(password: String): Either[String, String] = {
    if (password.isEmpty) Left("Password is required")
    else if (!isValidPassword(password)) Left(s"Password must be at least $minPasswordLength characters")
    else Right(password)
  }

  def validateNonBlank(value: String, fieldName: String): Either[String, String] = {
    if (value.trim.isEmpty) Left(s"$fieldName is required")
    else Right(value.trim)
  }
}
