package gathedge.shared.validation

import gathedge.shared.domain.{Group, Tag, WordLanguage}
import gathedge.shared.i18n.{MessageKeys, MessageRef}

/** Validation shared between the signup form (frontend) and the signup/create-user endpoints (backend), so the same
  * rules apply in both places per summary.md.
  *
  * Failures are [[MessageRef]]s rather than rendered strings, so the same check produces the same message in whatever
  * language the caller is reading — whether it ran in the browser before submitting or on the server afterwards. It
  * also means a field's *label* is a catalog key chosen by the caller, which is what finally stopped the two sides
  * disagreeing about what a field is called.
  */
object Validation {

  private val emailPattern = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r

  /** What a username may be made of, once [[normalizeUsername]] has lowercased it: letters, digits, hyphen and
    * underscore, starting and ending on a letter or a digit.
    *
    * No `@`, which is what lets a sign-in tell an address from a username by looking for one rather than by trying both
    * lookups. No leading or trailing separator, so two usernames cannot differ only by a character nobody can see.
    */
  private val usernamePattern = "^[a-z0-9]([a-z0-9_-]*[a-z0-9])?$".r

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
  val maxEmailLength = 255 // users.email

  val minUsernameLength = 3
  val maxUsernameLength = 32 // users.username

  // No column in the skeleton's own schema is this wide; both are here as the conventional ceilings a
  // feature's VARCHAR should be declared at, so a form and its column agree from the start.
  val maxNameLength = 255
  val maxTextLength = 2000

  def isValidEmail(email: String): Boolean = {
    emailPattern.matches(email.trim) && email.trim.length <= maxEmailLength
  }

  /** The minimum stays in characters — it is a statement to the user about how much to type — while the maximum is in
    * bytes, because it is a statement about what bcrypt will read.
    */
  def isValidPassword(password: String): Boolean = {
    password.length >= minPasswordLength && utf8Length(password) <= maxPasswordLength
  }

  def validateEmail(email: String): Either[MessageRef, String] = {
    if (email.trim.isEmpty)
      Left(MessageRef(MessageKeys.emailRequired))
    else if (email.trim.length > maxEmailLength)
      Left(MessageRef(MessageKeys.emailTooLong, List(maxEmailLength.toString)))
    else if (!isValidEmail(email))
      Left(MessageRef(MessageKeys.emailInvalid))
    else
      Right(email.trim)
  }

  /** The stored form of a username: trimmed and lowercased, the same rule an address follows.
    *
    * Casing is not part of the identity — `Levente` and `levente` are one account — so folding it here is what makes
    * the unique index and every lookup case-insensitive with no `lower()` in the SQL. What the reader wanted to see is
    * the display name, which nothing normalises.
    */
  def normalizeUsername(username: String): String = {
    username.trim.toLowerCase
  }

  /** A username as typed, answering the normalised form. Blank is *not* accepted here: clearing a username is the
    * caller passing `None`, not passing an empty string.
    */
  def validateUsername(username: String): Either[MessageRef, String] = {
    val normalized = normalizeUsername(username)
    if (normalized.isEmpty)
      Left(MessageRef(MessageKeys.fieldRequired, List(MessageRef.keyArg(MessageKeys.fieldUsername))))
    else if (normalized.length < minUsernameLength)
      Left(MessageRef(MessageKeys.usernameTooShort, List(minUsernameLength.toString)))
    else if (normalized.length > maxUsernameLength)
      Left(MessageRef(MessageKeys.usernameTooLong, List(maxUsernameLength.toString)))
    else if (!usernamePattern.matches(normalized))
      Left(MessageRef(MessageKeys.usernameInvalid))
    else
      Right(normalized)
  }

  /** The name an account is called by on screen. Bounded by `users.display_name VARCHAR(255)`, and otherwise anything:
    * a name is not an identifier, nothing matches on it, and telling people how to spell their own name is not this
    * application's business.
    */
  def validateDisplayName(name: String): Either[MessageRef, String] = {
    validateNonBlank(name, MessageKeys.fieldName, maxNameLength)
  }

  def validatePassword(password: String): Either[MessageRef, String] = {
    if (password.isEmpty)
      Left(MessageRef(MessageKeys.passwordRequired))
    else if (password.length < minPasswordLength)
      Left(MessageRef(MessageKeys.passwordTooShort, List(minPasswordLength.toString)))
    else if (utf8Length(password) > maxPasswordLength) {
      // Not "at most 72 characters": the limit is 72 bytes, so a password of accented or non-Latin
      // characters trips it well before its 72nd character, and quoting a number would misdescribe it.
      // The catalog entry carries no number for the same reason.
      Left(MessageRef(MessageKeys.passwordTooLong))
    } else
      Right(password)
  }

  /** A word as typed into the add-a-word form. `words.text` is `VARCHAR(255)`, hence [[maxNameLength]]. */
  def validateWordText(text: String): Either[MessageRef, String] = {
    validateNonBlank(text, MessageKeys.fieldWord, maxNameLength)
  }

  /** A tag name, which is bounded by `tags.name` and additionally may not be one of the names the practice screen will
    * compute for itself (`ALL`, `ALL_UNKNOWN`, …). Refusing them now is what stops a reader creating a tag today that
    * collides with a built-in set later; the check is case-insensitive, since tag names are matched that way.
    */
  def validateTagName(name: String): Either[MessageRef, String] = {
    validateNonBlank(name, MessageKeys.fieldTag, Tag.maxNameLength).flatMap { trimmed =>
      if (Tag.isReserved(trimmed))
        Left(MessageRef(MessageKeys.wordTagReserved, List(trimmed)))
      else
        Right(trimmed)
    }
  }

  /** A tag's mandatory language pair: the two must differ. Same check on both sides of the wire — the create form and
    * the create/set-languages endpoints.
    */
  def validateTagLanguages(
    source: WordLanguage,
    target: WordLanguage,
  ): Either[MessageRef, (WordLanguage, WordLanguage)] = {
    if (source == target)
      Left(MessageRef(MessageKeys.wordTagLanguagesEqual))
    else
      Right((source, target))
  }

  /** A group's display name. Unlike [[validateTagName]], no reserved-name check and no per-account uniqueness — several
    * groups may legitimately share a name (two classes working from the same book), so [[Group.normalize]] is for
    * sorted/case-insensitive listing only.
    */
  def validateGroupName(name: String): Either[MessageRef, String] = {
    validateNonBlank(name, MessageKeys.fieldGroupName, Group.maxNameLength)
  }

  /** @param fieldKey
    *   the *catalog key* of the field's label (e.g. `MessageKeys.fieldEmail`), not the label itself. It is passed as a
    *   `MessageRef.keyArg` so the label is translated before being spliced into the sentence around it.
    */
  /** A game's display name. Bounded by `games.name VARCHAR(255)`, the same ceiling as [[maxNameLength]]. */
  def validateGameName(name: String): Either[MessageRef, String] = {
    validateNonBlank(name, MessageKeys.fieldGameName, maxNameLength)
  }

  /** How many words a play may draw from its eligible pool, when the creator asks for a fixed count rather than "select
    * all" — see `games.word_limit`. No column or quota naturally bounds this the way a `VARCHAR` width bounds
    * [[maxNameLength]], so [[maxWordLimit]] is a plain literal, picked generous enough for any real quiz while keeping
    * play-start sampling and per-prompt random selection cheap.
    */
  val maxWordLimit = 500

  def validateWordLimit(limit: Int): Either[MessageRef, Int] = {
    if (limit < 1 || limit > maxWordLimit)
      Left(MessageRef(MessageKeys.gameWordLimitInvalid, List(maxWordLimit.toString)))
    else
      Right(limit)
  }

  def validateNonBlank(value: String, fieldKey: String, maxLength: Int = maxTextLength): Either[MessageRef, String] = {
    val trimmed = value.trim
    if (trimmed.isEmpty)
      Left(MessageRef(MessageKeys.fieldRequired, List(MessageRef.keyArg(fieldKey))))
    else if (trimmed.length > maxLength)
      Left(MessageRef(MessageKeys.fieldTooLong, List(MessageRef.keyArg(fieldKey), maxLength.toString)))
    else
      Right(trimmed)
  }
}
