package gathedge.shared.parsing

import gathedge.shared.domain.WordLanguage

/** The words a spreadsheet's first row uses to label its columns — `Német`, `German`, `Deutsch`, `Névelő`, `Artikel`.
  *
  * A header row is not data: importing it mints a `words` row for `Német` and pairs it with `Magyar`. The reader can
  * tick "first row is a header" by hand, but a row that names the very languages being imported is not ambiguous, so
  * the browser ticks it for them.
  *
  * '''A heading is written in the reader's language, not the column's''', the same rule [[MarkerVocabulary]] follows: a
  * Hungarian learner labels the German column `Német`. So every language's names for every language are recognised, on
  * any column, and no pairing has to be resolved first.
  */
object ColumnHeading {

  /** What each language calls each of the four, plus the endonym every list is as likely to use. Accent-stripped
    * variants are listed because a list typed on an English keyboard drops them.
    */
  private val languageNames: Map[String, WordLanguage] = {
    Map(
      WordLanguage.En -> List("english", "englisch", "angol", "inglés", "ingles"),
      WordLanguage.De -> List("german", "deutsch", "német", "nemet", "alemán", "aleman", "németül", "nemetul"),
      WordLanguage.Hu -> List("hungarian", "ungarisch", "magyar", "húngaro", "hungaro", "magyarul"),
      WordLanguage.Es -> List("spanish", "spanisch", "spanyol", "español", "espanol", "castellano"),
    ).flatMap { case (language, names) => names.map(name => name -> language) }
  }

  /** What each language calls the column holding an article, a gender or a grammatical note — the third column of the
    * list this exists for. Recognising these is what lets a header be spotted on a file whose language columns are
    * labelled something idiosyncratic.
    */
  private val extraNames: Set[String] = {
    Set(
      "article",
      "articles",
      "artikel",
      "névelő",
      "nevelo",
      "artículo",
      "articulo",
      "gender",
      "geschlecht",
      "genus",
      "nem",
      "género",
      "genero",
      "note",
      "notes",
      "notiz",
      "megjegyzés",
      "megjegyzes",
      "nota",
      "word",
      "words",
      "wort",
      "wörter",
      "woerter",
      "szó",
      "szo",
      "szavak",
      "palabra",
      "translation",
      "übersetzung",
      "uebersetzung",
      "fordítás",
      "forditas",
      "traducción",
      "traduccion",
    )
  }

  /** How a cell becomes a lookup key: trimmed, lower-cased, with any trailing punctuation dropped so `Német:` and
    * `Német` are one key.
    */
  private def normalize(cell: String): String = {
    cell.trim.toLowerCase.replaceAll("""[\s:.\-_]+$""", "")
  }

  /** The language this heading names, if it names one. */
  def language(cell: String): Option[WordLanguage] = languageNames.get(normalize(cell))

  /** True when `cell` is a column label rather than a word to import. */
  def isHeading(cell: String): Boolean = {
    val key = normalize(cell)
    key.nonEmpty && (languageNames.contains(key) || extraNames.contains(key))
  }

  /** True when `row` is a header row: at least two of its filled cells are column labels, and at least one of those
    * names a language.
    *
    * Two, not one, because a single matching cell is a real risk — `nem` is a Hungarian word, `note` an English one,
    * and a one-column coincidence must not silently delete a reader's first entry. Requiring a language name on top of
    * that is what keeps the rule to the case it was written for.
    */
  def isHeaderRow(row: List[String]): Boolean = {
    val filled = row.map(_.trim).filter(_.nonEmpty)
    filled.count(isHeading) >= 2 && filled.exists(cell => language(cell).isDefined)
  }
}
