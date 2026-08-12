package gathedge.frontend.components

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.api.ApiClient
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.OAuthProvider
import gathedge.shared.i18n.UiKeys

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
        I18n.t(UiKeys.oauthLink, provider.display)
      else
        I18n.t(UiKeys.oauthContinueWith, provider.display)
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
      case "account_exists"                   =>
        I18n.t(UiKeys.oauthErrorAccountExists)
      case "already_linked"                   =>
        I18n.t(UiKeys.oauthErrorAlreadyLinked)
      case "link_requires_session"            =>
        I18n.t(UiKeys.oauthErrorLinkRequiresSession)
      case "state_mismatch" | "missing_state" =>
        I18n.t(UiKeys.oauthErrorStateMismatch)
      case "missing_code" | "failed"          =>
        I18n.t(UiKeys.oauthErrorFailed)
      case _                                  =>
        I18n.t(UiKeys.oauthErrorFailed)
    }
  }

  def linkedMessage(providerWireName: String): String = {
    val name = {
      OAuthProvider.fromString(providerWireName).map(_.display).getOrElse(I18n.t(UiKeys.oauthLinkedFallback))
    }
    I18n.t(UiKeys.oauthLinked, name)
  }
}
