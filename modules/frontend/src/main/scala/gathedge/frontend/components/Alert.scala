package gathedge.frontend.components

import com.raquo.laminar.api.L._

/** The banner every page uses to report something the user has to read.
  *
  * Was a private `renderError`/`renderAlert` copied into four pages; the administrator screens would have made it six.
  * `role := "alert"` is the part worth having in one place — it is what makes the message reach a screen reader when it
  * appears, and it is the easiest thing to leave out of a copy.
  */
object Alert {

  def error(message: String): HtmlElement = render("alert-error", message)

  def info(message: String): HtmlElement = render("alert-info", message)

  def warning(message: String): HtmlElement = render("alert-warning", message)

  def success(message: String): HtmlElement = render("alert-success", message)

  def render(kind: String, message: String): HtmlElement = {
    div(role := "alert", cls := s"alert $kind mb-4", span(message))
  }

  /** The common wiring: show the banner while the signal holds a message, nothing otherwise. */
  def maybeError(signal: Signal[Option[String]]): Modifier[HtmlElement] = {
    child.maybe <-- signal.map(_.map(error))
  }

  def maybeInfo(signal: Signal[Option[String]]): Modifier[HtmlElement] = {
    child.maybe <-- signal.map(_.map(info))
  }
}
