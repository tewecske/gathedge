package webapp1.frontend.components

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.api.ApiClient
import webapp1.shared.domain.OAuthProvider

import OAuthProvider.display

/** The "Continue with …" buttons, and the copy for the error codes the OAuth callback redirects with.
  *
  * Shared by the sign-in and sign-up forms (where the buttons start a login) and the settings page (where they link a
  * provider to the account already signed in), because the two differ only in `link` and in the surrounding text.
  */
object OAuthButtons {

  /** Plain anchors, never `ApiClient` calls. The flow is a chain of top-level redirects through the provider, so the
    * document itself has to navigate; a `fetch` would follow the redirect to the provider's HTML and get nowhere. It
    * also means these carry no `X-Requested-With`, which is why the callback route is exempt from the CSRF aspect and
    * leans on the `oauth_state` cookie instead.
    */
  def render(providers: Signal[List[OAuthProvider]], link: Boolean = false): HtmlElement = {
    div(cls := "flex flex-col gap-2", children <-- providers.map(_.map(button(_, link))))
  }

  private def button(provider: OAuthProvider, link: Boolean): HtmlElement = {
    val label = {
      if (link)
        s"Link ${provider.display}"
      else
        s"Continue with ${provider.display}"
    }
    a(cls := "btn btn-outline w-full", href := ApiClient.oauthStartUrl(provider, link), label)
  }
}

/** Turns the `?error=` and `?linked=` codes the OAuth callback redirects with into something a person can read.
  *
  * The codes are short and opaque on purpose — `AuthRoutes.oauthErrorRedirect` puts them in the address bar, where an
  * exception message has no business being — so the wording lives here, on the side that renders it.
  */
object OAuthMessages {

  /** Reads a query parameter off the current URL. The OAuth callback lands on a full page load rather than a Waypoint
    * navigation, so these arrive in `location.search` rather than through the router.
    */
  def queryParam(name: String): Option[String] = {
    val params = new dom.URLSearchParams(dom.window.location.search)
    Option(params.get(name)).filter(_.nonEmpty)
  }

  def errorMessage(code: String): String = {
    code match {
      case "account_exists" =>
        "An account with this email already exists. Sign in with your password, then link the provider from Settings."
      case "already_linked" =>
        "That account is already linked — to this login or to another one."
      case "link_requires_session" =>
        "Your session expired before the link completed. Sign in again and retry."
      case "state_mismatch" | "missing_state" =>
        "That sign-in attempt could not be verified. Please start again."
      case "missing_code" | "failed" =>
        "Sign-in failed. Please try again."
      case _ =>
        "Sign-in failed. Please try again."
    }
  }

  def linkedMessage(providerWireName: String): String = {
    val name = OAuthProvider.fromString(providerWireName).map(_.display).getOrElse("That account")
    s"$name is now linked to your account."
  }
}
