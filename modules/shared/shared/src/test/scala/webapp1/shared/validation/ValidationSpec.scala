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
    )
  }
}
