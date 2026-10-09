package gathedge.shared.domain

/** What a header of a form table says.
  *
  *   - `Tags` names grammar tags. The browser shows each through its `ui.grammarTag.*` label, so the header follows the
  *     page's language.
  *   - `Text` is a literal: a suffix (`-é`) or a pronoun (`me`). It belongs to the word's language, so it is never
  *     translated.
  *   - `Key` is a `UiKeys` key, for a heading no tag names.
  */
enum FormLabel {
  case Tags(tags: List[String])
  case Text(text: String)
  case Key(key: String)
}

object FormLabel {

  def tags(tags: String*): FormLabel = FormLabel.Tags(tags.toList)
}

/** Which relations a cell takes, as a test on the relation's tags.
  *
  * Every `include` tag must be present and no `exclude` tag may be. Each `anyOf` group needs at least one of its tags.
  * Other tags are allowed, since the dump adds some unevenly (`definite` on some German plurals, `formal,rare` on the
  * German subjunctive II). They count against a relation when a cell picks its closest ones (see [[extra]]), except the
  * `optional` tags: Spanish `comprase` carries `imperfect-se` and still belongs beside `comprara`.
  */
final case class FormMatch(
  include: Set[String] = Set.empty,
  exclude: Set[String] = Set.empty,
  anyOf: List[Set[String]] = Nil,
  optional: Set[String] = Set.empty,
) {

  def accepts(tags: Set[String]): Boolean = {
    include.subsetOf(tags) && !exclude.exists(tags.contains) && anyOf.forall(_.exists(tags.contains))
  }

  /** The tags of a relation that this test does not name. The fewer, the closer the relation is to the cell. */
  def extra(tags: Set[String]): Int = {
    (tags -- include -- anyOf.flatten -- optional).size
  }

  def ++(other: FormMatch): FormMatch = {
    FormMatch(include ++ other.include, exclude ++ other.exclude, anyOf ++ other.anyOf, optional ++ other.optional)
  }
}

object FormMatch {

  def apply(tags: String*): FormMatch = FormMatch(include = tags.toSet)
}

/** One row or column header: its label and the test it adds to each of its cells. `label = None` draws no header. */
final case class FormAxis(label: Option[FormLabel], matcher: FormMatch)

object FormAxis {

  /** A header labelled by the tags it requires: the common case. */
  def of(tags: String*): FormAxis = FormAxis(Some(FormLabel.Tags(tags.toList)), FormMatch(include = tags.toSet))

  def apply(label: FormLabel, matcher: FormMatch): FormAxis = FormAxis(Some(label), matcher)
}

/** Whether a cell shows the word the page is about. The dump has no form row for the headword of a Spanish noun or
  * adjective, so its cell must be filled from the word itself.
  */
enum LemmaFill {
  case Always, WhenEmpty
}

/** What a table puts before each of its forms, chosen by the cell's tags (`LanguageProfile.prefix`).
  *
  *   - `Article` is the definite article, declined by case and number (`dem Haus`).
  *   - `Pronoun` is the subject pronoun (`ich rufe an`).
  *   - `SubordinatePronoun` is the pronoun after the language's subordinator (`dass ich anrufe`).
  */
enum FormPrefix {
  case Article, Pronoun, SubordinatePronoun
}

/** One table: rows times columns, each cell testing `base ++ row ++ column`. `lemma` names the cells the headword
  * fills, by row and column index. `prefix` names what goes before each form.
  */
final case class FormTable(
  title: Option[FormLabel],
  base: FormMatch,
  columns: List[FormAxis],
  rows: List[FormAxis],
  lemma: Map[(Int, Int), LemmaFill] = Map.empty,
  prefix: Option[FormPrefix] = None,
)

/** A titled group of tables. The page draws a section with a title as a collapsible block, so a large template (the
  * German adjective's three degrees) stays short until the reader opens it.
  */
final case class FormSection(title: Option[FormLabel], tables: List[FormTable])

final case class FormTemplate(sections: List[FormSection])

/** One cell as drawn: the forms it holds, whether the headword goes first, and what goes before each form. */
final case class FilledCell[A](forms: List[A], lemma: Boolean, prefix: Option[String] = None) {

  def isEmpty: Boolean = forms.isEmpty && !lemma
}

final case class FilledRow[A](label: Option[FormLabel], cells: List[FilledCell[A]])

final case class FilledTable[A](title: Option[FormLabel], columns: List[Option[FormLabel]], rows: List[FilledRow[A]])

final case class FilledSection[A](title: Option[FormLabel], tables: List[FilledTable[A]])

/** A word's forms laid out by a template. `rest` holds every form whose word no cell shows, so nothing is lost. */
final case class FormLayout[A](sections: List[FilledSection[A]], rest: List[A])

object FormTable {

  /** Places `forms` into `template`'s cells.
    *
    * A cell takes every form that passes its test. Where several relations pass, it keeps only those with the fewest
    * tags the test does not name: the dump repeats a table's forms in head-line rows with fewer tags, and a head-line
    * row of Spanish `compra` also names the reflexive `cómprate`. A word that passes through two relations is shown
    * once.
    *
    * A row with nothing in it is dropped. A table with no form in any cell is dropped, then a section with no table
    * left. The headword's own cell keeps its row (`go` beside `went`), but not a table: alone it says nothing the page
    * title does not.
    *
    * A form goes to `rest` when its word is in no cell. A word in a cell is not repeated below for its other relations:
    * German `kaufte` (`past`) is already in the preterite cells.
    *
    * A table with a `prefix` gets each cell's prefix from `prefixes`, given the tags the cell requires.
    */
  def layout[A](
    template: FormTemplate,
    forms: List[A],
    prefixes: (FormPrefix, Set[String]) => Option[String] = (_, _) => None,
  )(
    relation: A => String,
    wordId: A => Long,
  ): FormLayout[A] = {
    val tagged   = forms.map(form => (form, relation(form).split(',').iterator.filter(_.nonEmpty).toSet))
    val sections = template.sections.flatMap(section => {
      val tables = section.tables.flatMap(table => fill(table, tagged, wordId, prefixes))
      Option.when(tables.nonEmpty)(FilledSection(section.title, tables))
    })
    val shown    = (for {
      section <- sections
      table   <- section.tables
      row     <- table.rows
      cell    <- row.cells
      form    <- cell.forms
    } yield wordId(form)).toSet
    FormLayout(sections, forms.filterNot(form => shown.contains(wordId(form))))
  }

  private def fill[A](
    table: FormTable,
    tagged: List[(A, Set[String])],
    wordId: A => Long,
    prefixes: (FormPrefix, Set[String]) => Option[String],
  ): Option[FilledTable[A]] = {
    val rows    = table.rows.zipWithIndex.flatMap { case (row, r) =>
      val cells = table.columns.zipWithIndex.map { case (column, c) =>
        val matcher = table.base ++ row.matcher ++ column.matcher
        val forms   = cellForms(matcher, tagged, wordId)
        val lemma   = table.lemma.get((r, c)) match {
          case Some(LemmaFill.Always)    => true
          case Some(LemmaFill.WhenEmpty) => forms.isEmpty
          case None                      => false
        }
        val tags    = matcher.include ++ matcher.anyOf.flatten
        FilledCell(forms, lemma, table.prefix.flatMap(kind => prefixes(kind, tags)))
      }
      Option.when(cells.exists(!_.isEmpty))(FilledRow(row.label, cells))
    }
    val anyForm = rows.exists(_.cells.exists(_.forms.nonEmpty))
    Option.when(anyForm)(FilledTable(table.title, table.columns.map(_.label), rows))
  }

  private def cellForms[A](matcher: FormMatch, tagged: List[(A, Set[String])], wordId: A => Long): List[A] = {
    val passing = tagged.filter { case (_, tags) => matcher.accepts(tags) }
    passing.map { case (_, tags) => matcher.extra(tags) }.minOption match {
      case None       => Nil
      case Some(best) =>
        passing.collect { case (form, tags) if matcher.extra(tags) == best => form }.distinctBy(wordId)
    }
  }
}
