package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.Page
import gathedge.frontend.components.{AppShell, Formats}
import gathedge.frontend.i18n.I18n
import gathedge.shared.Branding
import gathedge.shared.i18n.UiKeys
import gathedge.shared.i18n.UiKeys.LegalSection

/** The privacy policy and the terms of use.
  *
  * Public, like [[AboutPage]]: a sign-in provider reviews both pages with no session, and every page's footer links to
  * them. The text is in the catalogs, one key per paragraph; [[UiKeys.privacySections]] and [[UiKeys.termsSections]]
  * say how many of each a section has.
  */
object LegalPage {

  /** When either text last changed: noon UTC, so the date is the same in every time zone a reader is in. Change it with
    * the text.
    */
  private val updatedAt: Long = 1791806400000L

  def privacy(): HtmlElement = render(Page.Privacy, UiKeys.privacyTitle, UiKeys.privacySections)

  def terms(): HtmlElement = render(Page.Terms, UiKeys.termsTitle, UiKeys.termsSections)

  private def render(page: Page, titleKey: String, sections: List[LegalSection]): HtmlElement = {
    AppShell.render(
      page,
      div(
        cls := "max-w-2xl mx-auto",
        div(
          cls := "card bg-base-100 shadow-xl",
          div(
            cls := "card-body",
            h1(cls := "card-title", I18n.t(titleKey)),
            p(cls  := "text-sm opacity-70", I18n.t(UiKeys.legalUpdated, Formats.date(updatedAt))),
            sections.map(renderSection),
          ),
        ),
      ),
    )
  }

  private def renderSection(section: LegalSection): HtmlElement = {
    div(
      // The anchor `#delete` is the URL a sign-in provider asks for: the data deletion instructions.
      idAttr := section.name,
      cls    := "flex flex-col gap-2",
      section.title.map(key => h2(cls := "card-title text-lg mt-4", I18n.t(key))),
      section.paragraphKeys.map(key => p(linked(text(key)))),
      Option.when(section.items > 0)(
        ul(cls := "list-disc pl-5 space-y-1", section.itemKeys.map(key => li(linked(text(key)))))
      ),
      section.afterKeys.map(key => p(linked(text(key)))),
    )
  }

  private def text(key: String): String = I18n.t(key, Branding.authorName, Branding.authorEmail, Branding.appName)

  private val linkPattern = """https?://[^\s)]+|[\w.+-]+@[\w-]+\.[\w.-]*\w""".r

  /** The text, with each web address and email address in it made a link. */
  private def linked(value: String): List[Modifier[HtmlElement]] = {
    val matches = linkPattern.findAllMatchIn(value).toList
    val starts  = 0 :: matches.map(_.end)
    val texts   = {
      starts
        .zip(matches.map(_.start) :+ value.length)
        .map[Modifier[HtmlElement]]((from, until) => value.substring(from, until))
    }
    texts.head :: matches
      .map(found => link(value.substring(found.start, found.end)))
      .zip(texts.tail)
      .flatMap((link, rest) => List(link, rest))
  }

  private def link(address: String): HtmlElement = {
    if (address.startsWith("http"))
      a(cls := "link link-primary", href := address, target := "_blank", rel := "noopener noreferrer", address)
    else
      a(cls := "link link-primary", href := s"mailto:$address", address)
  }
}
