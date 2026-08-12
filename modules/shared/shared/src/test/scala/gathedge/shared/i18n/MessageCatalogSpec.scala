package gathedge.shared.i18n

import gathedge.shared.domain.Locale
import zio.test.*

object MessageCatalogSpec extends ZIOSpecDefault {

  private def catalog(locale: Locale, entries: (String, String)*): MessageCatalog = {
    MessageCatalog(locale, entries.toMap)
  }

  def spec = {
    suite("MessageCatalog")(
      test("a missing key resolves to the key itself") {
        val en = catalog(Locale.En)
        assertTrue(en.resolve(MessageRef("nope.not.here")) == "nope.not.here")
      },
      test("substitutes positional placeholders in any order") {
        assertTrue(
          MessageCatalog.substitute("{1} then {0}", List("a", "b")) == "b then a",
          MessageCatalog.substitute("{0}{0}", List("x")) == "xx",
        )
      },
      // A single left-to-right pass. Without it, an argument whose own text is "{0}" would splice
      // itself back into the sentence — user input is an argument, and arguments are not templates.
      test("does not re-scan a substituted value for placeholders") {
        assertTrue(MessageCatalog.substitute("name: {0}, id: {1}", List("{1}", "7")) == "name: {1}, id: 7")
      },
      // Showing the raw brace is the point: it says the catalog and the call site disagree, and
      // which slot they disagree about.
      test("leaves a placeholder with no argument as written") {
        assertTrue(
          MessageCatalog.substitute("{0} and {2}", List("a", "b")) == "a and {2}",
          MessageCatalog.substitute("{} {x} {", List("a")) == "{} {x} {",
        )
      },
      test("leaves a template alone when there are no arguments") {
        assertTrue(MessageCatalog.substitute("{0} untouched", Nil) == "{0} untouched")
      },
      test("translates an @-marked argument and leaves a literal one verbatim") {
        val en = catalog(
          Locale.En,
          "validation.field.required" -> "{0} is required",
          "field.email"               -> "Email",
        )
        assertTrue(
          en.resolve(MessageRef("validation.field.required", List(MessageRef.keyArg("field.email")))) ==
            "Email is required",
          en.resolve(MessageRef("validation.field.required", List("field.email"))) ==
            "field.email is required",
        )
      },
      // English distinguishes 1 from everything else; Hungarian takes the singular after any
      // numeral, so it renders the `.other` template for a count of 1 too.
      test("selects the plural form per language") {
        val en = catalog(Locale.En, "n.one" -> "{0} session", "n.other" -> "{0} sessions")
        val hu = catalog(Locale.Hu, "n.one" -> "{0} munkamenet", "n.other" -> "{0} munkamenet")
        assertTrue(
          en.plural("n", 1) == "1 session",
          en.plural("n", 0) == "0 sessions",
          en.plural("n", 5) == "5 sessions",
          hu.plural("n", 1) == "1 munkamenet",
          hu.plural("n", 5) == "5 munkamenet",
        )
      },
    )
  }
}
