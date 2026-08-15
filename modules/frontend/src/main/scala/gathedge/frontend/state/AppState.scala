package gathedge.frontend.state

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.shared.domain.{Theme, User}

/** Client-side session state. The server is the source of truth (cookie-based session); this just mirrors the
  * last-known `/api/me` result so pages don't all need to re-fetch it.
  */
object AppState {
  // Kept private so the only writes go through setUser/clearUser (which also keep the
  // document theme in sync); everything else reads the signal.
  private val currentUserVar: Var[Option[User]] = Var(None)
  val currentUserSignal: Signal[Option[User]]   = currentUserVar.signal

  /** Where an anonymous visitor's theme lives, for the same reason `CurrentLocale` mirrors the language: the sign-in
    * page has no account to read a preference off, and a visitor who picked dark should not be handed light again on
    * every visit.
    */
  private val storageKey = "theme"

  /** The theme in force, whoever is looking. Signed in it is the account's — [[setUser]] writes it here off every
    * `/api/me` and every successful profile write; signed out it is whatever this browser last chose. One `Var` rather
    * than a signal derived from the user, so the navbar's theme control reads the same value in both states.
    */
  private val themeVar: Var[Theme]      = Var(storedTheme.getOrElse(Theme.Light))
  val themeSignal: Signal[Theme]        = themeVar.signal.distinct
  val isSignedInSignal: Signal[Boolean] = currentUserSignal.map(_.isDefined).distinct

  /** The theme in force right now, read outside a subscription — for the one caller that needs it synchronously rather
    * than reactively: minting a guest has to send this browser's current preference along with the request, before
    * there is anywhere to `-->` a stream into.
    */
  def currentTheme: Theme = themeVar.now()

  private def themeName(theme: Theme): String = {
    theme match {
      case Theme.Light =>
        "light"
      case Theme.Dark  =>
        "dark"
    }
  }

  /** Applies the theme to the whole document immediately (summary.md). Mirroring it into the current-user state is
    * [[setUser]]'s job, since the server response is what makes it authoritative.
    */
  def applyTheme(theme: Theme): Unit = {
    dom.document.documentElement.setAttribute("data-theme", themeName(theme))
  }

  /** The one write for "this is the theme now": remembers it, mirrors it to the document, and leaves it where the next
    * page load can find it before any request is made.
    *
    * For a signed-in user this is called with what the *server* answered, never optimistically — see the note on the
    * theme control in [[gathedge.frontend.components.AppShell]] about a failed write having to leave the old theme
    * standing.
    */
  def setTheme(theme: Theme): Unit = {
    themeVar.set(theme)
    storeTheme(theme)
    applyTheme(theme)
  }

  /** Puts the remembered theme on the document before the first render, so a dark-theme visitor does not get a flash of
    * light while `/api/me` is in flight (or forever, on a page they reach signed out).
    */
  def initTheme(): Unit = {
    applyTheme(themeVar.now())
  }

  def setUser(user: User): Unit = {
    currentUserVar.set(Some(user))
    setTheme(user.theme)
  }

  def clearUser(): Unit = {
    currentUserVar.set(None)
  }

  // Storage access is wrapped for the same reason `CurrentLocale`'s is: both APIs throw rather than
  // return null when the browser has storage disabled, and a theme preference is not worth failing a
  // page load over.
  private def storedTheme: Option[Theme] = {
    try Option(dom.window.localStorage.getItem(storageKey)).flatMap(Theme.fromString)
    catch { case _: Throwable => None }
  }

  private def storeTheme(theme: Theme): Unit = {
    try dom.window.localStorage.setItem(storageKey, themeName(theme))
    catch { case _: Throwable => () }
  }
}
