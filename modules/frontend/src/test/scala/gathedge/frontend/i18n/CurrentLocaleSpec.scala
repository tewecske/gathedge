package gathedge.frontend.i18n

import gathedge.shared.domain.Locale
import zio.test._

/** Reading the locale off the URL. Everything downstream — which catalog is fetched, which `basePath` every Waypoint
  * route is built with, which language an email link lands in — is decided by this one parse.
  */
object CurrentLocaleSpec extends ZIOSpecDefault {

  def spec = {
    suite("CurrentLocale.fromPath")(
      test("reads the locale out of the first path segment") {
        assertTrue(
          CurrentLocale.fromPath("/hu") == Some(Locale.Hu),
          CurrentLocale.fromPath("/hu/") == Some(Locale.Hu),
          CurrentLocale.fromPath("/en/groups/7/members") == Some(Locale.En),
        )
      },
      test("a path with no locale prefix names none") {
        assertTrue(
          CurrentLocale.fromPath("/") == None,
          CurrentLocale.fromPath("/groups") == None,
          CurrentLocale.fromPath("") == None,
        )
      },
      // Only the *first* segment counts. A group could be named "hu", and a token could start with
      // it; neither may change the page's language.
      test("a locale-shaped segment anywhere but the front is ignored") {
        assertTrue(
          CurrentLocale.fromPath("/groups/hu") == None,
          CurrentLocale.fromPath("/en/hu/groups") == Some(Locale.En),
        )
      },
      test("an unknown language is not a locale") {
        assertTrue(CurrentLocale.fromPath("/de/groups") == None)
      },
    )
  }
}
