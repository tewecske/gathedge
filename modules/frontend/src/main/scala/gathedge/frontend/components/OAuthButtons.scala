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
      case OAuthProvider.Discord   =>
        "bg-[#5865f2] text-white border-[#5865f2]"
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
      case OAuthProvider.Discord   =>
        svg.svg(
          svg.cls     := "size-4",
          svg.viewBox := "0 0 24 24",
          svg.path(
            svg.fill := "#fff",
            svg.d    := (
              "M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152.0741.0741 0 00-.0785.0371c-.211.3753-.4447.8648" +
                "-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 00" +
                "-.0785-.037 19.7363 19.7363 0 00-4.8852 1.515.0699.0699 0 00-.0321.0277C.5334 9.0458-.319 13.5799" +
                ".0992 18.0578a.0824.0824 0 00.0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 00" +
                ".0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 00-.0416-.1057c-.6528-.2476-1.2743" +
                "-.5495-1.8722-.8923a.077.077 0 01-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 01" +
                ".0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 01.0785.0095c.1202.099.246.1981.3728" +
                ".2924a.077.077 0 01-.0066.1276 12.2986 12.2986 0 01-1.873.8914.0766.0766 0 00-.0407.1067c.3604.698" +
                ".7719 1.3628 1.225 1.9932a.076.076 0 00.0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077" +
                " 0 00.0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 00-.0312-.0286zM8.02 15.3312c" +
                "-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952" +
                " 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0" +
                "-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568" +
                " 2.4189z"
            ),
          ),
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
