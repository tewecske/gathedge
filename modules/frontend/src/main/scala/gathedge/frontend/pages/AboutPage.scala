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
      svg.strokeWidth    := "1.5",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(
        svg.d :=
          "M21.75 6.75v10.5a2.25 2.25 0 0 1-2.25 2.25h-15a2.25 2.25 0 0 1-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0 0 19.5 4.5h-15a2.25 2.25 0 0 0-2.25 2.25m19.5 0v.243a2.25 2.25 0 0 1-1.07 1.916l-7.5 4.615a2.25 2.25 0 0 1-2.36 0L3.32 8.91a2.25 2.25 0 0 1-1.07-1.916V6.75"
      ),
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
            h3(cls   := "card-title text-sm mt-2", I18n.t(UiKeys.aboutSourceReferencesTitle)),
            ul(
              cls    := "list-disc pl-5 space-y-1",
              li(
                a(
                  cls    := "link link-primary",
                  href   := Branding.sourceFrequencyWordsUrl,
                  target := "_blank",
                  rel    := "noopener noreferrer",
                  I18n.t(UiKeys.aboutSourceFrequencyWords),
                )
              ),
              li(
                a(
                  cls    := "link link-primary",
                  href   := Branding.sourceWiktextractUrl,
                  target := "_blank",
                  rel    := "noopener noreferrer",
                  I18n.t(UiKeys.aboutSourceWiktextract),
                )
              ),
              li(
                a(
                  cls    := "link link-primary",
                  href   := Branding.sourceWiktionaryUrl,
                  target := "_blank",
                  rel    := "noopener noreferrer",
                  I18n.t(UiKeys.aboutSourceWiktionary),
                )
              ),
              li(
                a(
                  cls    := "link link-primary",
                  href   := Branding.sourceKaikkiUrl,
                  target := "_blank",
                  rel    := "noopener noreferrer",
                  I18n.t(UiKeys.aboutSourceKaikki),
                )
              ),
            ),
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
            h2(cls   := "card-title text-lg mt-4", I18n.t(UiKeys.aboutIconsTitle)),
            p(I18n.t(UiKeys.aboutIconsBody)),
            div(
              cls    := "flex flex-col gap-1",
              a(
                cls    := "link link-primary",
                href   := Branding.iconsUrl,
                target := "_blank",
                rel    := "noopener noreferrer",
                "Heroicons",
              ),
              a(
                cls    := "link link-primary",
                href   := Branding.iconsLicenseUrl,
                target := "_blank",
                rel    := "noopener noreferrer",
                I18n.t(UiKeys.aboutIconsLicenseLabel),
              ),
            ),
            h2(cls   := "card-title text-lg mt-4", I18n.t(UiKeys.aboutUiTitle)),
            p(I18n.t(UiKeys.aboutUiBody)),
            div(
              cls    := "flex flex-col gap-1",
              a(
                cls    := "link link-primary",
                href   := Branding.daisyUiUrl,
                target := "_blank",
                rel    := "noopener noreferrer",
                "daisyUI",
              ),
              a(
                cls    := "link link-primary",
                href   := Branding.daisyUiLicenseUrl,
                target := "_blank",
                rel    := "noopener noreferrer",
                I18n.t(UiKeys.aboutUiLicenseLabel),
              ),
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
