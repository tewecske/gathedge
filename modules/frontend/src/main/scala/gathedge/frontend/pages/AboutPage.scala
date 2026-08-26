package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.Page
import gathedge.frontend.components.AppShell
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.shared.Branding
import gathedge.shared.domain.Theme
import gathedge.shared.i18n.UiKeys

/** What the site is for, who runs it, and how it is licensed.
  *
  * Public, like [[gathedge.frontend.AppRouter.Page.Games]] — the navbar links to it whatever the session says, so it
  * must render for a signed-out visitor too.
  */
object AboutPage {

  /** The GitHub mark. The supplied artwork is a fixed-colour file, so the light and dark variants swap with the theme:
    * `size-5` matches the navbar's icons.
    */
  private def githubIcon(): HtmlElement = {
    img(
      cls := "size-5",
      alt := I18n.t(UiKeys.aboutGitHubLabel),
      src <-- AppState.themeSignal.map {
        case Theme.Dark  => "/images/GitHub_Invertocat_White.svg"
        case Theme.Light => "/images/GitHub_Invertocat_Black.svg"
      },
    )
  }

  /** A letter envelope, paired with the contact link. Same `size-5`/`currentColor` convention as [[githubIcon]]. */
  private def emailIcon(): SvgElement = {
    svg.svg(
      svg.cls            := "size-5",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "2",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(svg.d := "M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z"),
      svg.path(svg.d := "M22 6l-10 7L2 6"),
    )
  }

  def render(): HtmlElement = {
    AppShell.render(
      Page.About,
      div(
        cls := "max-w-2xl mx-auto",
        div(
          cls := "card bg-base-100 shadow-xl",
          div(
            cls := "card-body",
            h1(cls   := "card-title", I18n.t(UiKeys.aboutTitle)),
            p(I18n.t(UiKeys.aboutIntro)),
            h2(cls   := "card-title text-lg mt-4", I18n.t(UiKeys.aboutNameTitle)),
            p(I18n.t(UiKeys.aboutNameBody)),
            h2(cls   := "card-title text-lg mt-4", I18n.t(UiKeys.aboutGoalTitle)),
            p(I18n.t(UiKeys.aboutGoalBody)),
            h2(cls   := "card-title text-lg mt-4", I18n.t(UiKeys.aboutSourceTitle)),
            p(I18n.t(UiKeys.aboutSourceBody)),
            a(
              cls    := "link link-primary",
              href   := Branding.sourceLicenseUrl,
              target := "_blank",
              rel    := "noopener noreferrer",
              I18n.t(UiKeys.aboutSourceLicenseLabel),
            ),
            h2(cls   := "card-title text-lg mt-4", I18n.t(UiKeys.aboutCodeTitle)),
            p(I18n.t(UiKeys.aboutCodeBody)),
            a(
              cls    := "link link-primary",
              href   := Branding.licenseUrl,
              target := "_blank",
              rel    := "noopener noreferrer",
              I18n.t(UiKeys.aboutCodeLicenseLabel),
            ),
            h2(cls   := "card-title text-lg mt-4", I18n.t(UiKeys.aboutLinksTitle)),
            a(
              cls    := "link link-primary inline-flex items-center gap-2",
              href   := Branding.githubUrl,
              target := "_blank",
              rel    := "noopener noreferrer",
              githubIcon(),
              I18n.t(UiKeys.aboutGitHubLabel),
            ),
            h2(cls   := "card-title text-lg mt-4", I18n.t(UiKeys.aboutAuthorTitle)),
            p(I18n.t(UiKeys.aboutAuthorBody)),
            a(
              cls    := "link link-primary inline-flex items-center gap-2",
              href   := s"mailto:${Branding.authorEmail}",
              emailIcon(),
              I18n.t(UiKeys.aboutContactLabel),
            ),
          ),
        ),
      ),
    )
  }
}
