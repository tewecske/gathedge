package webapp1.frontend.i18n

import org.scalajs.dom
import webapp1.shared.domain.Locale
import webapp1.shared.domain.Locale.urlPrefix

/** Which language this page load is in — read off the URL, synchronously, before anything renders.
  *
  * That it is derivable from the URL alone is what makes the whole frontend side of i18n simple. The locale is known
  * before the first element is built, so the catalog can be fetched up front and `I18n.t` can be an ordinary
  * synchronous function; there is no `localeSignal`, no `text <--` binding for static copy, and no need to widen
  * `App`'s deliberately narrow render gate. Switching language is a navigation to the other prefix, not a re-render.
  *
  * It also means initialisation order does not matter: this depends on nothing but `location`, which is always there.
  *
  * The prefix is normally guaranteed by the boot script in `index.html`, which redirects a prefix-less URL before the
  * bundle loads. [[value]] still falls back rather than failing, since a page can be reached with script disabled
  * mid-flight, and a default language beats a blank screen.
  */
object CurrentLocale {

  /** Set by the boot script when it had to *choose* a locale rather than read one out of the URL. That distinction is
    * the whole of the precedence rule: an explicit prefix is the user's stated choice and outranks the account's stored
    * preference, while a prefix this app picked is only a guess and gives way to it. See [[LocaleSync]].
    */
  private val implicitFlagKey = "localeImplicit"

  /** Where the account's preference is mirrored, so the boot script can act on it before any request is made. */
  private[i18n] val storageKey = "locale"

  /** The locale named by the first path segment, if it names one. Pure, so both branches are testable. */
  def fromPath(pathname: String): Option[Locale] = {
    pathname.split('/').lift(1).flatMap(Locale.fromString)
  }

  val value: Locale = fromPath(dom.window.location.pathname).getOrElse(Locale.default)

  /** `/en` or `/hu` — what Waypoint wants as a route's `basePath`. */
  val prefix: String = value.urlPrefix

  /** True when the boot script chose this locale rather than finding it in the URL. */
  def wasImplicit: Boolean = {
    readSession(implicitFlagKey).contains("1")
  }

  /** The locale last mirrored out of an account, if any. */
  def stored: Option[Locale] = {
    readLocal(storageKey).flatMap(Locale.fromString)
  }

  def store(locale: Locale): Unit = {
    writeLocal(storageKey, Locale.languageCode(locale))
  }

  /** The same path under a different language. Used by the picker and by [[LocaleSync]]'s redirect; a full navigation
    * rather than a Waypoint `pushState`, because every route's `basePath` was fixed when the router was built.
    */
  def urlUnder(locale: Locale): String = {
    val location = dom.window.location
    val segments = location.pathname.split('/').toList.filter(_.nonEmpty)
    // Drop the current prefix if there is one: "/hu/admin/users" -> List("admin", "users"), "/hu" -> Nil.
    val rest     = {
      if (fromPath(location.pathname).isDefined)
        segments.drop(1)
      else
        segments
    }
    // The root keeps a trailing slash — `/hu/`, not `/hu` — because that is the form Waypoint builds
    // for the home route and the form `index.html`'s boot script redirects to. Both spellings happen
    // to match, but leaving two in circulation for the same page is how a later `toHaveURL` or a
    // cache key ends up disagreeing with itself.
    val tail     = {
      if (rest.isEmpty)
        "/"
      else
        rest.map("/" + _).mkString
    }
    s"${locale.urlPrefix}$tail${location.search}${location.hash}"
  }

  // Storage access is wrapped because both APIs throw rather than return null when the browser has
  // storage disabled (Safari's private mode historically, an iframe with third-party storage
  // blocked today). A language preference is not worth failing a page load over.
  private def readLocal(key: String): Option[String] = {
    try Option(dom.window.localStorage.getItem(key))
    catch { case _: Throwable => None }
  }

  private def writeLocal(key: String, value: String): Unit = {
    try dom.window.localStorage.setItem(key, value)
    catch { case _: Throwable => () }
  }

  private def readSession(key: String): Option[String] = {
    try Option(dom.window.sessionStorage.getItem(key))
    catch { case _: Throwable => None }
  }
}
