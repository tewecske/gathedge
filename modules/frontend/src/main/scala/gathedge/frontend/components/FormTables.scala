package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.AppRouter
import gathedge.frontend.Page
import gathedge.shared.domain.{FilledCell, FilledSection, FilledTable, FormLayout, LanguageProfile, Word}
import gathedge.shared.dto.WordFormEntry

/** A word's forms as conjugation and declension tables (`FormTable.layout`). The caller draws `layout.rest`.
  *
  * A section with no title is drawn open. A titled one (the potential, the reflexive verb, a German adjective's
  * comparative) is a collapsed block, so a large template stays short. Each table scrolls on its own when it is wider
  * than the screen.
  */
object FormTables {

  def render(word: Word, layout: FormLayout[WordFormEntry]): HtmlElement = {
    div(cls := "flex flex-col gap-3 mt-2", layout.sections.map(section => renderSection(word, section)))
  }

  private def renderSection(word: Word, section: FilledSection[WordFormEntry]): HtmlElement = {
    val tables = div(cls := "flex flex-col gap-3", section.tables.map(table => renderTable(word, table)))
    section.title match {
      case None        =>
        tables
      case Some(title) =>
        detailsTag(
          cls := "collapse collapse-arrow border border-base-300 rounded-box",
          summaryTag(cls := "collapse-title text-sm font-medium", Labels.formLabel(title)),
          div(cls        := "collapse-content", tables),
        )
    }
  }

  private def renderTable(word: Word, filled: FilledTable[WordFormEntry]): HtmlElement = {
    val headed = filled.columns.exists(_.nonEmpty)
    val titled = filled.rows.exists(_.label.nonEmpty)
    div(
      filled.title.map(title => div(cls := "badge badge-ghost badge-sm", Labels.formLabel(title))),
      div(
        cls := "overflow-x-auto mt-1",
        table(
          cls := "table table-xs w-auto",
          Option.when(headed)(
            thead(
              tr(
                Option.when(titled)(th()),
                filled.columns.map(column => th(column.map(Labels.formLabel).getOrElse(""))),
              )
            )
          ),
          tbody(
            filled.rows.map(row => {
              tr(
                Option.when(titled)(th(cls := "font-normal opacity-70", row.label.map(Labels.formLabel).getOrElse(""))),
                row.cells.map(cell => td(renderCell(word, cell))),
              )
            })
          ),
        ),
      ),
    )
  }

  /** The headword and its own self-links are plain text; any other form links to its own page. The cell's prefix (an
    * article or a pronoun) goes before each form, dimmed and outside the link.
    */
  private def renderCell(word: Word, cell: FilledCell[WordFormEntry]): List[HtmlElement] = {
    val profile    = LanguageProfile.of(word.language)
    val withPrefix = (text: String, form: HtmlElement) => {
      span(cell.prefix.map(prefix => span(cls := "opacity-60", profile.lead(prefix, text))), form)
    }
    val lemma      = Option.when(cell.lemma)(withPrefix(word.text, span(word.text)))
    val forms      = cell.forms.map(entry => {
      if (entry.word.id == word.id)
        withPrefix(entry.word.text, span(entry.word.text))
      else {
        withPrefix(
          entry.word.text,
          a(cls := "link link-hover", AppRouter.router.navigateTo(Page.WordDetail(entry.word.id)), entry.word.text),
        )
      }
    })
    (lemma.toList ++ forms) match {
      case Nil      => List(span(cls := "opacity-40", "—"))
      case elements =>
        elements.head :: elements.tail.flatMap(element => List(span(cls := "opacity-40", " / "), element))
    }
  }
}
