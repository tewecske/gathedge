package gathedge.shared.domain

/** One table of a drill template, as the form drill offers it: its place in the template and the titles that name it.
  * `section` and `table` are indices into `FormTemplate.sections` and that section's `tables`.
  *
  * `column` is set when the table is too wide for one round (see [[FormDrill.maxColumns]]): the drill then asks only
  * that column, and `columnTitle` names it. `articles` says the table puts a definite article before its forms, so the
  * player may choose to place the articles too.
  */
final case class DrillTableRef(
  section: Int,
  table: Int,
  sectionTitle: Option[FormLabel],
  title: Option[FormLabel],
  column: Option[Int] = None,
  columnTitle: Option[FormLabel] = None,
  articles: Boolean = false,
)

/** One cell of a drill round.
  *
  *   - `Given` is shown filled in. It is the headword's own cell (`LemmaFill`), a form spelled like the headword, or a
  *     form spelled like an earlier cell of its row (the German subjunctive I `ich kaufe` beside the indicative). The
  *     player places no form there.
  *   - `Gap` is a cell the player fills. `answer` is the cell's text; where a cell holds two forms, they are joined
  *     with `" / "`, the way the word page shows them.
  *   - `Blank` is a cell the dictionary has no form for. It is no gap, since no chip could fill it.
  *
  * `prefix` is the article or pronoun the table puts before the form, still to be joined by `LanguageProfile.lead`.
  * `article` is set when the player chooses that prefix: it indexes `DrillRound.articles`, and the page hides the
  * prefix.
  */
enum DrillCell {
  case Given(text: String, prefix: Option[String], article: Option[Int] = None)
  case Gap(index: Int, answer: String, prefix: Option[String], article: Option[Int] = None)
  case Blank
}

final case class DrillRow(label: Option[FormLabel], cells: List[DrillCell])

/** One word's table with its gaps. `gaps` is the answer of each gap, in the order `DrillCell.Gap.index` counts them:
  * row by row, left to right. `articles` is the answer of each article the player chooses, counted the same way.
  */
final case class DrillRound(columns: List[Option[FormLabel]], rows: List[DrillRow], articles: List[String] = Nil) {

  val gaps: List[String] = rows.flatMap(_.cells).collect { case DrillCell.Gap(_, answer, _, _) => answer }

  /** How many things the player places: forms and articles. */
  def size: Int = gaps.size + articles.size
}

/** The form drill: a wordlist's noun or verb shown as one empty form table, filled by placing its own forms.
  *
  * The drill reuses the word page's layout (`FormTable.layout`), so a cell holds exactly what the word page shows
  * there. It asks only what is worth asking: a form the player can read off the page title or the cell beside it is
  * given, a wide table is asked one column at a time, and the Hungarian noun is cut to its main cases.
  */
object FormDrill {

  /** A table with fewer things to place than this is no puzzle, so the word is left out of a drill on that table. */
  val minGaps: Int = 3

  /** A table with more columns than this is drilled one column at a time. The Spanish indicative has five tenses of
    * seven persons each: 35 gaps in one round is a chore. Two columns stay together, so the German subjunctive I is
    * asked beside the indicative it mostly repeats.
    */
  val maxColumns: Int = 2

  /** The Hungarian cases the drill asks, of the eighteen the word page shows. The nominative holds the plural stem, the
    * accusative the linking vowel, the dative the two-way vowel harmony and the superessive the three-way one. The
    * inessive is the most common case of place.
    */
  val hungarianCases: List[String] = List("nominative", "accusative", "dative", "inessive", "superessive")

  /** The template the drill uses for `language` and `partOfSpeech`: the word page's own, but with the Hungarian noun's
    * declension cut to [[hungarianCases]].
    */
  def template(language: WordLanguage, partOfSpeech: PartOfSpeech): Option[FormTemplate] = {
    FormTemplates
      .of(language, partOfSpeech)
      .map(template => {
        if (language == WordLanguage.Hu && partOfSpeech == PartOfSpeech.Noun) mainCases(template) else template
      })
  }

  private def mainCases(template: FormTemplate): FormTemplate = {
    val keep = (row: FormAxis) => hungarianCases.exists(c => row.label.contains(FormLabel.tags(c)))
    template.copy(sections = template.sections.map(section => {
      section.copy(tables = section.tables.map(table => {
        if (table.rows.exists(keep)) table.copy(rows = table.rows.filter(keep)) else table
      }))
    }))
  }

  /** Every table of `template`, in the order the word page draws them. A table wider than [[maxColumns]] gives one
    * entry per column.
    */
  def tables(template: FormTemplate): List[DrillTableRef] = {
    template.sections.zipWithIndex.flatMap { case (section, s) =>
      section.tables.zipWithIndex.flatMap { case (table, t) =>
        val ref = DrillTableRef(s, t, section.title, table.title, articles = table.prefix.contains(FormPrefix.Article))
        if (table.columns.size > maxColumns)
          table.columns.zipWithIndex.map { case (column, c) => ref.copy(column = Some(c), columnTitle = column.label) }
        else
          List(ref)
      }
    }
  }

  /** The round for one word on the table `ref` names, or `None` when that table gives fewer than [[minGaps]] things to
    * place.
    *
    * `text` is a form's spelling; `lemma` is the headword's, for the cells `LemmaFill` gives it. With `askArticles`, a
    * table that puts a definite article before its forms has the player choose each one.
    */
  def round[A](
    template: FormTemplate,
    ref: DrillTableRef,
    forms: List[A],
    lemma: String,
    prefixes: (FormPrefix, Set[String]) => Option[String] = (_, _) => None,
    askArticles: Boolean = false,
  )(
    relation: A => String,
    wordId: A => Long,
    text: A => String,
  ): Option[DrillRound] = {
    val chosen = for {
      section <- template.sections.lift(ref.section)
      table   <- section.tables.lift(ref.table)
      columns <- ref.column.fold(Option(table.columns))(c => table.columns.lift(c).map(List(_)))
    } yield {
      val cells = ref.column.fold(table.lemma)(c => table.lemma.collect { case ((r, `c`), fill) => (r, 0) -> fill })
      table.copy(columns = columns, lemma = cells)
    }
    for {
      single <- chosen
      alone   = FormTemplate(List(FormSection(None, List(single))))
      filled <- FormTable.layout(alone, forms, prefixes)(relation, wordId).sections.flatMap(_.tables).headOption
      built   = build(filled, lemma, text, askArticles && single.prefix.contains(FormPrefix.Article))
      if built.size >= minGaps
    } yield built
  }

  private def build[A](filled: FilledTable[A], lemma: String, text: A => String, askArticles: Boolean): DrillRound = {
    var next     = 0
    val articles = List.newBuilder[String]
    var asked    = 0
    val ask      = (prefix: Option[String]) => {
      prefix
        .filter(_ => askArticles)
        .map(answer => {
          articles += answer
          asked += 1
          asked - 1
        })
    }
    val rows     = filled.rows.map(row => {
      var seen  = Set.empty[String]
      val cells = row.cells.map(cell => {
        val texts  = (if (cell.lemma) List(lemma) else Nil) ++ cell.forms.map(text)
        val answer = texts.distinct.mkString(" / ")
        val known  = (cell.forms.isEmpty && cell.lemma) || answer == lemma || seen.contains(answer)
        seen += answer
        if (texts.isEmpty)
          DrillCell.Blank
        else if (known)
          DrillCell.Given(answer, cell.prefix, ask(cell.prefix))
        else {
          val gap = DrillCell.Gap(next, answer, cell.prefix, ask(cell.prefix))
          next += 1
          gap
        }
      })
      DrillRow(row.label, cells)
    })
    DrillRound(filled.columns, rows, articles.result())
  }
}

/** The one thing a hint revealed: a form's gap or an article. */
enum DrillHint {
  case Form(gap: Int)
  case Article(index: Int)
}

/** Where the player has put the chips and articles of one round.
  *
  * `chips` holds one chip per gap, each the text of some gap, in the order the bank shows them. `placed` maps a gap to
  * the chip in it. A chip fits a gap when their texts are equal, not only when it was cut from that gap: a form that
  * fills two cells (`kauft` for `er` and `ihr`) makes two chips that are interchangeable.
  *
  * `articles` are the answers of the articles to choose, and `chosen` maps each to the player's choice.
  *
  * `locked` and `articlesLocked` are what a check found right; they stay. `wrong` and `articlesWrong` are what the last
  * check found wrong; those chips went back to the bank and those choices were cleared. `firstTry` counts what was
  * right at the round's first check, which is the round's score. `hint` is what the round's one hint revealed. It is
  * locked at once, so it never scores.
  */
final case class DrillBoard(
  gaps: List[String],
  chips: List[String],
  placed: Map[Int, Int] = Map.empty,
  locked: Set[Int] = Set.empty,
  wrong: Set[Int] = Set.empty,
  firstTry: Option[Int] = None,
  articles: List[String] = Nil,
  chosen: Map[Int, String] = Map.empty,
  articlesLocked: Set[Int] = Set.empty,
  articlesWrong: Set[Int] = Set.empty,
  hint: Option[DrillHint] = None,
) {

  /** The chips not in any gap, by index into `chips`. */
  def bank: List[Int] = {
    val used = placed.values.toSet
    chips.indices.filterNot(used.contains).toList
  }

  /** What the round gives points for: each gap and each article. */
  def maxScore: Int = gaps.size + articles.size

  def isFull: Boolean = placed.size == gaps.size && chosen.size == articles.size

  def isSolved: Boolean = locked.size == gaps.size && articlesLocked.size == articles.size

  def canHint: Boolean = hint.isEmpty && !isSolved

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

  /** Chooses `text` as the article `index`, or clears the choice when `text` is empty. A locked article keeps its
    * choice.
    */
  def choose(index: Int, text: String): DrillBoard = {
    if (articlesLocked.contains(index) || !articles.indices.contains(index)) this
    else if (text.isEmpty) copy(chosen = chosen - index)
    else copy(chosen = chosen + (index -> text), articlesWrong = articlesWrong - index)
  }

  /** Marks every filled gap and chosen article right or wrong. Right ones lock; wrong ones go back. The first check of
    * a round sets [[firstTry]]. What was locked before it (the hint) does not count.
    */
  def check: DrillBoard = {
    val (right, bad)       = placed.partition { case (gap, chip) => chips(chip) == gaps(gap) }
    val (rightArt, badArt) = chosen.partition { case (index, text) => articles(index) == text }
    val score              = (right.keySet -- locked).size + (rightArt.keySet -- articlesLocked).size
    copy(
      placed = placed -- bad.keys,
      locked = locked ++ right.keys,
      wrong = bad.keys.toSet,
      chosen = chosen -- badArt.keys,
      articlesLocked = articlesLocked ++ rightArt.keys,
      articlesWrong = badArt.keys.toSet,
      firstTry = firstTry.orElse(Some(score)),
    )
  }

  /** Reveals one thing, once a round, and locks it.
    *
    * With a chip selected, the chip goes into a gap it fits. Otherwise the first gap without its right chip gets one.
    * When every gap is solved, the first article not yet locked is chosen instead.
    */
  def reveal(selected: Option[Int]): DrillBoard = {
    val lockedChips = locked.flatMap(placed.get)
    val open        = gaps.indices.filterNot(locked.contains).toList
    val unsolved    = open.filterNot(gap => placed.get(gap).exists(chip => chips(chip) == gaps(gap)))
    val target      = selected.filterNot(lockedChips.contains) match {
      case Some(chip) =>
        val fits = open.filter(gap => gaps(gap) == chips(chip))
        fits.find(unsolved.contains).orElse(fits.headOption).map(gap => (gap, chip))
      case None       =>
        unsolved.headOption
          .orElse(open.headOption)
          .flatMap(gap => {
            val fitting = chips.indices.filter(chip => chips(chip) == gaps(gap) && !lockedChips.contains(chip)).toList
            val inPlace = placed.get(gap).filter(fitting.contains)
            val free    = fitting.find(chip => !placed.values.exists(_ == chip))
            inPlace.orElse(free).orElse(fitting.headOption).map(chip => (gap, chip))
          })
    }
    (target, articles.indices.find(!articlesLocked.contains(_))) match {
      case _ if !canHint          =>
        this
      case (Some((gap, chip)), _) =>
        val moved = place(chip, gap)
        moved.copy(locked = moved.locked + gap, hint = Some(DrillHint.Form(gap)))
      case (None, Some(index))    =>
        copy(
          chosen = chosen + (index -> articles(index)),
          articlesLocked = articlesLocked + index,
          articlesWrong = articlesWrong - index,
          hint = Some(DrillHint.Article(index)),
        )
      case (None, None)           =>
        this
    }
  }
}

object DrillBoard {

  /** A fresh board for `round`, its chips in the order `shuffle` puts them. */
  def of(round: DrillRound, shuffle: List[String] => List[String]): DrillBoard = {
    DrillBoard(round.gaps, shuffle(round.gaps), articles = round.articles)
  }
}
