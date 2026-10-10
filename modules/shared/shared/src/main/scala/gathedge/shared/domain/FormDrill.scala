package gathedge.shared.domain

/** One table of a [[FormTemplate]], as the form drill offers it: its place in the template and the two titles that name
  * it. `section` and `table` are indices into `FormTemplate.sections` and that section's `tables`.
  */
final case class DrillTableRef(
  section: Int,
  table: Int,
  sectionTitle: Option[FormLabel],
  title: Option[FormLabel],
)

/** One cell of a drill round.
  *
  *   - `Given` is shown filled in: the headword's own cell (`LemmaFill`). The player places nothing there.
  *   - `Gap` is a cell the player fills. `answer` is the cell's text; where a cell holds two forms, they are joined
  *     with `" / "`, the way the word page shows them.
  *   - `Blank` is a cell the dictionary has no form for. It is no gap, since no chip could fill it.
  *
  * `prefix` is the article or pronoun the table puts before the form, still to be joined by `LanguageProfile.lead`.
  */
enum DrillCell {
  case Given(text: String, prefix: Option[String])
  case Gap(index: Int, answer: String, prefix: Option[String])
  case Blank
}

final case class DrillRow(label: Option[FormLabel], cells: List[DrillCell])

/** One word's table with its gaps. `gaps` is the answer of each gap, in the order `DrillCell.Gap.index` counts them:
  * row by row, left to right.
  */
final case class DrillRound(columns: List[Option[FormLabel]], rows: List[DrillRow]) {

  val gaps: List[String] = rows.flatMap(_.cells).collect { case DrillCell.Gap(_, answer, _) => answer }
}

/** The form drill: a wordlist's noun or verb shown as one empty form table, filled by placing its own forms.
  *
  * The drill reuses the word page's layout (`FormTable.layout`), so a cell holds exactly what the word page shows
  * there.
  */
object FormDrill {

  /** A table with fewer gaps than this is no puzzle, so the word is left out of a drill on that table. */
  val minGaps: Int = 3

  /** Every table of `template`, in the order the word page draws them. */
  def tables(template: FormTemplate): List[DrillTableRef] = {
    template.sections.zipWithIndex.flatMap { case (section, s) =>
      section.tables.zipWithIndex.map { case (table, t) => DrillTableRef(s, t, section.title, table.title) }
    }
  }

  /** The round for one word on the table `ref` names, or `None` when that table gives fewer than [[minGaps]] gaps.
    *
    * `text` is a form's spelling; `lemma` is the headword's, for the cells `LemmaFill` gives it.
    */
  def round[A](
    template: FormTemplate,
    ref: DrillTableRef,
    forms: List[A],
    lemma: String,
    prefixes: (FormPrefix, Set[String]) => Option[String] = (_, _) => None,
  )(
    relation: A => String,
    wordId: A => Long,
    text: A => String,
  ): Option[DrillRound] = {
    val chosen = for {
      section <- template.sections.lift(ref.section)
      table   <- section.tables.lift(ref.table)
    } yield FormTemplate(List(FormSection(None, List(table))))
    for {
      single <- chosen
      filled <- FormTable.layout(single, forms, prefixes)(relation, wordId).sections.flatMap(_.tables).headOption
      built   = build(filled, lemma, text)
      if built.gaps.size >= minGaps
    } yield built
  }

  private def build[A](filled: FilledTable[A], lemma: String, text: A => String): DrillRound = {
    var next = 0
    val rows = filled.rows.map(row => {
      val cells = row.cells.map(cell => {
        val texts = (if (cell.lemma) List(lemma) else Nil) ++ cell.forms.map(text)
        if (cell.forms.isEmpty && cell.lemma)
          DrillCell.Given(lemma, cell.prefix)
        else if (texts.isEmpty)
          DrillCell.Blank
        else {
          val gap = DrillCell.Gap(next, texts.distinct.mkString(" / "), cell.prefix)
          next += 1
          gap
        }
      })
      DrillRow(row.label, cells)
    })
    DrillRound(filled.columns, rows)
  }
}

/** Where the player has put the chips of one round.
  *
  * `chips` holds one chip per gap, each the text of some gap, in the order the bank shows them. `placed` maps a gap to
  * the chip in it. A chip fits a gap when their texts are equal, not only when it was cut from that gap: a form that
  * fills two cells (`kauft` for `er` and `ihr`) makes two chips that are interchangeable.
  *
  * `locked` are the gaps a check found right; their chips stay. `wrong` are the gaps the last check found wrong; their
  * chips went back to the bank. `firstTry` counts the gaps right at the round's first check, which is the round's
  * score.
  */
final case class DrillBoard(
  gaps: List[String],
  chips: List[String],
  placed: Map[Int, Int] = Map.empty,
  locked: Set[Int] = Set.empty,
  wrong: Set[Int] = Set.empty,
  firstTry: Option[Int] = None,
) {

  /** The chips not in any gap, by index into `chips`. */
  def bank: List[Int] = {
    val used = placed.values.toSet
    chips.indices.filterNot(used.contains).toList
  }

  def isFull: Boolean = placed.size == gaps.size

  def isSolved: Boolean = locked.size == gaps.size

  /** Puts `chip` into `gap`. A chip that was in another gap leaves it; a chip already in `gap` goes back to the bank. A
    * locked gap takes nothing, and a locked chip does not move.
    */
  def place(chip: Int, gap: Int): DrillBoard = {
    val lockedChips = locked.flatMap(placed.get)
    if (
      locked.contains(gap) || lockedChips.contains(chip) || !gaps.indices.contains(gap) || !chips.indices.contains(chip)
    )
      this
    else {
      val freed = placed.filterNot { case (g, c) => c == chip || g == gap }
      copy(placed = freed + (gap -> chip), wrong = wrong - gap)
    }
  }

  /** Sends the chip in `gap` back to the bank. A locked gap keeps its chip. */
  def clear(gap: Int): DrillBoard = {
    if (locked.contains(gap)) this else copy(placed = placed - gap)
  }

  /** Marks every filled gap right or wrong. Right ones lock; wrong ones give their chip back. The first check of a
    * round sets [[firstTry]].
    */
  def check: DrillBoard = {
    val (right, bad) = placed.partition { case (gap, chip) => chips(chip) == gaps(gap) }
    copy(
      placed = placed -- bad.keys,
      locked = locked ++ right.keys,
      wrong = bad.keys.toSet,
      firstTry = firstTry.orElse(Some(right.size)),
    )
  }
}

object DrillBoard {

  /** A fresh board for `round`, its chips in the order `shuffle` puts them. */
  def of(round: DrillRound, shuffle: List[String] => List[String]): DrillBoard = {
    DrillBoard(round.gaps, shuffle(round.gaps))
  }
}
