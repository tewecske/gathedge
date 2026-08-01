package webapp1.shared.validation

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
      test("rejects an email longer than the column width") {
        val local = "a" * Validation.maxEmailLength
        assertTrue(Validation.validateEmail(s"$local@example.com").isLeft)
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
