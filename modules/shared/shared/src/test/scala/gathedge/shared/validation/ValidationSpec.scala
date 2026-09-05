package gathedge.shared.validation

import zio.test.*

object ValidationSpec extends ZIOSpecDefault {

  def spec = {
    suite("Validation")(
      test("accepts a well-formed email") {
        assertTrue(Validation.isValidEmail("user@example.com"))
      },
      test("rejects an email without a domain") {
        assertTrue(!Validation.isValidEmail("user@"))
      },
      test("rejects a blank email") {
        assertTrue(Validation.validateEmail("  ").isLeft)
      },
      test("rejects a password shorter than 8 characters") {
        assertTrue(Validation.validatePassword("short1").isLeft)
      },
      test("accepts a password of exactly 8 characters") {
        assertTrue(Validation.validatePassword("exactly8").isRight)
      },
      // The three below keep user input inside the column widths in db/migration/*; without them an
      // over-long value reached the database and came back as a 500 instead of a field error.
      test("rejects a password longer than bcrypt's 72-byte limit") {
        assertTrue(Validation.validatePassword("a" * (Validation.maxPasswordLength + 1)).isLeft)
      },
      // The limit bcrypt actually enforces is on bytes, and it enforces it by truncating. Measuring it in
      // characters let a 72-character non-ASCII password through with half of it silently ignored.
      test("counts the limit in UTF-8 bytes, not characters") {
        val ascii     = "a" * Validation.maxPasswordLength
        // 36 two-byte characters is exactly 72 bytes; one more is 74 and must be refused even though the
        // string is 37 characters long.
        val atLimit   = "é" * 36
        val overLimit = "é" * 37
        assertTrue(
          Validation.utf8Length(ascii) == Validation.maxPasswordLength,
          Validation.utf8Length(atLimit) == Validation.maxPasswordLength,
          Validation.validatePassword(ascii).isRight,
          Validation.validatePassword(atLimit).isRight,
          Validation.validatePassword(overLimit).isLeft,
        )
      },
      test("counts a surrogate pair as the four bytes it encodes to") {
        // One emoji: two chars, one code point, four UTF-8 bytes. Eighteen of them are exactly 72.
        assertTrue(
          Validation.utf8Length("😀") == 4,
          Validation.validatePassword("😀" * 18).isRight,
          Validation.validatePassword("😀" * 19).isLeft,
        )
      },
      test("rejects an email longer than the column width") {
        val local = "a" * Validation.maxEmailLength
        assertTrue(Validation.validateEmail(s"$local@example.com").isLeft)
      },
      // A username is the other thing a sign-in may name an account by, so what it may contain decides what
      // `AuthService.login` can tell an address from: no `@`, ever.
      test("lowercases a username and accepts the characters a name may hold") {
        assertTrue(
          Validation.validateUsername("  Levente_01  ") == Right("levente_01"),
          Validation.validateUsername("a-b").isRight,
        )
      },
      test("refuses a username that could be mistaken for an address, or that hides its edges") {
        assertTrue(
          Validation.validateUsername("user@example.com").isLeft,
          Validation.validateUsername("-leading").isLeft,
          Validation.validateUsername("trailing-").isLeft,
          Validation.validateUsername("has space").isLeft,
        )
      },
      test("bounds a username at both ends") {
        assertTrue(
          Validation.validateUsername("a" * (Validation.minUsernameLength - 1)).isLeft,
          Validation.validateUsername("a" * Validation.minUsernameLength).isRight,
          Validation.validateUsername("a" * Validation.maxUsernameLength).isRight,
          Validation.validateUsername("a" * (Validation.maxUsernameLength + 1)).isLeft,
        )
      },
      test("keeps a display name as typed, and bounds it at the column width") {
        assertTrue(
          Validation.validateDisplayName("  Levente Nagy  ") == Right("Levente Nagy"),
          Validation.validateDisplayName("a" * Validation.maxNameLength).isRight,
          Validation.validateDisplayName("a" * (Validation.maxNameLength + 1)).isLeft,
        )
      },
      test("rejects text longer than the requested maximum but accepts it at the boundary") {
        assertTrue(
          Validation.validateNonBlank("a" * 2001, "Text", 2000).isLeft,
          Validation.validateNonBlank("a" * 2000, "Text", 2000).isRight,
        )
      },
    )
  }
}
