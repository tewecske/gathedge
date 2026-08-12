package webapp1.frontend

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.i18n.{CurrentLocale, I18n}
import webapp1.frontend.state.AppState
import webapp1.shared.Branding

import scala.concurrent.ExecutionContext.Implicits.global

object Main {
  def main(args: Array[String]): Unit = {
    Option(dom.document.getElementById("app")) match {
      case Some(container) =>
        // Before anything renders, for the same reason the locale is settled before anything renders:
        // a visitor who chose dark on the sign-in page should not get a flash of light on the next
        // load while `/api/me` decides whether there is an account to read a preference off.
        AppState.initTheme()
        // The catalog is loaded *before* the app is mounted, which is what lets `I18n.t` be an
        // ordinary synchronous call from anywhere in the page tree. It is only possible because the
        // locale comes from the URL and so is known without asking the server anything.
        // `load` never fails — a catalog that 404s renders its own keys rather than nothing.
        I18n.load(CurrentLocale.value).foreach(_ => render(container, App.render()))
      case None            =>
        // index.html and this id are shipped together, so this only fires if the host page is wrong.
        dom.console.error(s"${Branding.slug}: no #app element to mount into")
    }
  }
}
