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
    val label  = {
      if (link)
        I18n.t(UiKeys.oauthLink, provider.display)
      else
        I18n.t(UiKeys.oauthContinueWith, provider.display)
    }
    a(
      cls  := s"btn btn-block ${brandClasses(provider)}",
      href := ApiClient.oauthStartUrl(provider, link),
      brandIcon(provider),
      label,
    )
  }

  /** Fixed brand colours, on purpose. These follow each provider's sign-in button guidance and stay the same in every
    * theme, so `btn-outline` and the semantic palette do not apply here.
    */
  private def brandClasses(provider: OAuthProvider): String = {
    provider match {
      case OAuthProvider.Google    =>
        "bg-white text-black border-[#e5e5e5]"
      case OAuthProvider.Microsoft =>
        "bg-[#2f2f2f] text-white border-[#2f2f2f]"
    }
  }

  private def brandIcon(provider: OAuthProvider): SvgElement = {
    provider match {
      case OAuthProvider.Google    =>
        svg.svg(
          svg.cls     := "size-4",
          svg.viewBox := "0 0 512 512",
          svg.g(
            svg.path(svg.fill := "#fff", svg.d    := "m0 0H512V512H0"),
            svg.path(svg.fill := "#34a853", svg.d := "M153 292c30 82 118 95 171 60h62v48A192 192 0 0190 341"),
            svg.path(svg.fill := "#4285f4", svg.d := "m386 400a140 140 0 0053-179H260v74h102q-7 37-38 57"),
            svg.path(svg.fill := "#fbbc02", svg.d := "m90 341a208 208 0 010-171l63 49q-12 37 0 73"),
            svg.path(svg.fill := "#ea4335", svg.d := "m153 219c22-69 116-109 179-50l55-54c-78-75-230-72-297 55"),
          ),
        )
      case OAuthProvider.Microsoft =>
        svg.svg(
          svg.cls     := "size-4",
          svg.viewBox := "0 0 512 512",
          svg.path(svg.fill := "#f24f23", svg.d := "M96 96H247V247H96"),
          svg.path(svg.fill := "#7eba03", svg.d := "M265 96H416V247H265"),
          svg.path(svg.fill := "#3ca4ef", svg.d := "M96 265H247V416H96"),
          svg.path(svg.fill := "#f9ba00", svg.d := "M265 265H416V416H265"),
        )
    }
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
