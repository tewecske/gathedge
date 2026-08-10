package webapp1.backend.i18n

import webapp1.shared.domain.Locale
import webapp1.shared.domain.Locale.code
import webapp1.shared.i18n.{MessageCatalog, MessageKeys, MessageRef, UiKeys}
import zio.test.*

/** What makes the stringly-typed catalog safe to build on.
  *
  * The catalog was chosen as JSON so a translator can edit it without touching Scala, and the cost of that choice is
  * that a missing key, a typo'd key and a placeholder that moved are all runtime bugs rather than compile errors. This
  * spec buys the guarantee back: it is the reason `MessageKeys` exists as constants at all, and the reason `build.sbt`
  * puts `web/public/locales` on the backend classpath.
  */
object MessagesSpec extends ZIOSpecDefault {

  /** Placeholder indices a template refers to, e.g. `"{0} of {1}"` -> `Set(0, 1)`. */
  private def placeholders(template: String): Set[Int] = {
    "\\{(\\d+)\\}".r.findAllMatchIn(template).map(_.group(1).nn.toInt).toSet
  }

  private val catalogs: Map[Locale, MessageCatalog] = {
    Locale.all.map { locale =>
      val path    = Messages.resourcePath(locale)
      val stream  = Option(getClass.getResourceAsStream(path))
        .getOrElse(throw new RuntimeException(s"Message catalog '$path' is not on the classpath"))
      val text    = {
        try new String(stream.readAllBytes().nn, java.nio.charset.StandardCharsets.UTF_8)
        finally stream.close()
      }
      val entries = zio.json
        .JsonDecoder[Map[String, String]]
        .decodeJson(text)
        .fold(error => throw new RuntimeException(s"Message catalog '$path' is not valid JSON: $error"), identity)
      locale -> MessageCatalog(locale, entries)
    }.toMap
  }

  def spec = {
    suite("Messages")(
      test("every locale has a catalog with entries in it") {
        assertTrue(
          catalogs.keySet == Locale.all.toSet,
          catalogs.values.forall(_.entries.nonEmpty),
        )
      },
      // Equality both ways: a key only in `en` is an untranslated string that would silently render
      // as its own key for a Hungarian reader, and a key only in `hu` is one English dropped without
      // its translation being cleaned up.
      test("all catalogs define exactly the same keys") {
        val reference = catalogs(Locale.default).entries.keySet
        val diffs     = catalogs.map { case (locale, catalog) =>
          locale.code -> ((reference -- catalog.entries.keySet) ++ (catalog.entries.keySet -- reference))
        }
        assertTrue(diffs.forall(_._2.isEmpty)) ?? s"key set differences per locale: $diffs"
      },
      // The half the key-set comparison cannot see: both catalogs agreeing on a key the *server*
      // never mints, or missing one it does. Without this, renaming a code in `ApiFailures` would
      // put a raw `auth.invalidCredentials` in front of a user in every language at once.
      test("every key the server can mint exists in every catalog") {
        val missing = catalogs.map { case (locale, catalog) =>
          locale.code -> MessageKeys.all.filterNot(catalog.contains)
        }
        assertTrue(missing.forall(_._2.isEmpty)) ?? s"keys missing from a catalog: $missing"
      },
      // The same guarantee for the other half of the catalog: the page copy. Nothing on the JVM
      // renders these, which is exactly why the check has to be here — the frontend has no test
      // that loads a catalog, so without this a mistyped `ui.` key would reach a screen as itself.
      test("every key a page renders exists in every catalog") {
        val missing = catalogs.map { case (locale, catalog) =>
          locale.code -> UiKeys.all.filterNot(catalog.contains)
        }
        assertTrue(missing.forall(_._2.isEmpty)) ?? s"UI keys missing from a catalog: $missing"
      },
      // The reverse, and only for the `ui.` namespace: a key the catalogs carry that no constant
      // claims is copy nothing renders any more, and it would otherwise sit there being translated.
      // `MessageKeys` gets no such check — the server's half is also read by `curl` and the OpenAPI
      // examples, so an unreferenced key there is not necessarily dead.
      test("no ui. key in the catalogs is unregistered") {
        val known   = UiKeys.all
        val orphans = catalogs(Locale.default).entries.keySet
          .filter(_.startsWith("ui."))
          .filterNot(known)
        assertTrue(orphans.isEmpty) ?? s"catalog keys no UiKeys constant registers: $orphans"
      },
      // A translation that drops or invents a placeholder renders either a stray `{1}` or silently
      // loses the number/name the sentence was about.
      test("a key uses the same placeholders in every locale") {
        val reference  = catalogs(Locale.default).entries
        val mismatched = catalogs.toList.flatMap { case (locale, catalog) =>
          catalog.entries.collect {
            case (key, template)
                if reference.get(key).exists(refTemplate => placeholders(refTemplate) != placeholders(template)) =>
              s"${locale.code}:$key"
          }
        }
        assertTrue(mismatched.isEmpty) ?? s"placeholder mismatches: $mismatched"
      },
      // Not a catalog property but the contract the catalog is read through: a `.one`/`.other` pair
      // must be complete wherever either half exists, or a count of 1 renders as its own key.
      test("plural keys come in complete one/other pairs") {
        val incomplete = catalogs.toList.flatMap { case (locale, catalog) =>
          catalog.entries.keySet.collect {
            case key if key.endsWith(".one") && !catalog.contains(key.stripSuffix(".one") + ".other")   =>
              s"${locale.code}:$key"
            case key if key.endsWith(".other") && !catalog.contains(key.stripSuffix(".other") + ".one") =>
              s"${locale.code}:$key"
          }
        }
        assertTrue(incomplete.isEmpty) ?? s"incomplete plural pairs: $incomplete"
      },
      // The email templates are the only ones the *server* renders, so a broken placeholder there
      // reaches a real inbox rather than a screen someone can reload.
      test("the email templates resolve with their arguments substituted") {
        val hu   = catalogs(Locale.Hu)
        val body =
          hu.resolve(MessageRef(MessageKeys.emailVerifyBody, List("https://example.test/hu/verify-email/t", "24")))
        assertTrue(
          body.contains("https://example.test/hu/verify-email/t"),
          body.contains("24"),
          !body.contains("{0}"),
          !body.contains("{1}"),
        )
      },
      // The `@` sentinel: a field label is itself translated before being spliced in, which is what
      // lets one `validation.field.required` serve every field in both languages.
      test("a key argument is translated before substitution") {
        val hu  = catalogs(Locale.Hu)
        val en  = catalogs(Locale.default)
        val ref = MessageRef(MessageKeys.fieldRequired, List(MessageRef.keyArg(MessageKeys.fieldGroupName)))
        assertTrue(
          en.resolve(ref) == "Group name is required",
          hu.resolve(ref) == "Kötelező mező: Csoportnév",
        )
      },
      test("a literal argument is substituted verbatim") {
        val en = catalogs(Locale.default)
        assertTrue(en.resolve(MessageRef(MessageKeys.oauthFailed, List("token expired"))).contains("token expired"))
      },
    )
  }
}
