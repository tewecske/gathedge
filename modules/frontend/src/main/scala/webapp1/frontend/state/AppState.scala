package webapp1.frontend.state

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.shared.domain.{Theme, User}

/** Client-side session state. The server is the source of truth (cookie-based session); this just mirrors the
  * last-known `/api/me` result so pages don't all need to re-fetch it.
  */
object AppState {
  // Kept private so the only writes go through setUser/clearUser (which also keep the
  // document theme in sync); everything else reads the signal.
  private val currentUserVar: Var[Option[User]] = Var(None)
  val currentUserSignal: Signal[Option[User]]   = currentUserVar.signal

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

  def setUser(user: User): Unit = {
    currentUserVar.set(Some(user))
    applyTheme(user.theme)
  }

  def clearUser(): Unit = {
    currentUserVar.set(None)
  }
}
