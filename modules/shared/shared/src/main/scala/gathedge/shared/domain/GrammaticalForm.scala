package gathedge.shared.domain

import zio.json.JsonCodec

/** The grammatical case a noun form stands in.
  *
  * Only the four German cases are named, because only a language whose articles decline needs this enum at all — a
  * `LanguageProfile` without a declension table never reads it. Hungarian's eighteen cases are absent on purpose:
  * Hungarian has no article that changes with case, so its `word_forms` rows need no slot.
  */
enum GrammaticalCase derives JsonCodec, CanEqual {
  case Nominative,
    Genitive,
    Dative,
    Accusative
}

object GrammaticalCase {

  /** The order a relation naming several cases is read in — the citation case first, then the grammar book's own order.
    * `"accusative,genitive,nominative,plural"` is one real relation string, and without a fixed order the article it
    * produces would depend on how the tags happen to be sorted.
    *
    * Leading with the nominative is load-bearing twice over: `GrammarTag.slotOf` reads a multi-case relation by this
    * order, and [[FormSlot.preferred]] picks which of a form's cells a game asks by it. Reordering moves both.
    */
  val all: List[GrammaticalCase] = List(Nominative, Genitive, Dative, Accusative)

  /** The `word_forms.relation` tag that names each case, which is also what this stores and reads. */
  def code(value: GrammaticalCase): String = {
    value match {
      case Nominative =>
        "nominative"
      case Genitive   =>
        "genitive"
      case Dative     =>
        "dative"
      case Accusative =>
        "accusative"
    }
  }

  def fromString(value: String): Option[GrammaticalCase] = {
    all.find(c => code(c) == value.toLowerCase)
  }
}

/** Singular or plural. Named apart from [[GrammaticalCase]] because German's plural article ignores gender entirely —
  * `die`/`der`/`den`/`die` for every gender — so the two halves of a slot are read by different rules.
  */
enum GrammaticalNumber derives JsonCodec, CanEqual {
  case Singular,
    Plural
}

object GrammaticalNumber {

  val all: List[GrammaticalNumber] = List(Singular, Plural)

  def code(value: GrammaticalNumber): String = {
    value match {
      case Singular =>
        "singular"
      case Plural   =>
        "plural"
    }
  }

  def fromString(value: String): Option[GrammaticalNumber] = {
    all.find(n => code(n) == value.toLowerCase)
  }
}

/** One cell of a declension table: the case and number a noun form stands in, which together with the noun's gender
  * decide its definite article.
  *
  * This is what a `word_forms.relation` string means once the tags that do not bear on the article are dropped, and it
  * is the key [[LanguageProfile.declinedArticles]] is read by. A lemma — a word with no `word_forms` row naming it as
  * the form side — stands in [[FormSlot.citation]], which is what makes `der Tisch` and `die Sache` fall out of the
  * same table the declined forms use rather than out of a second rule.
  */
final case class FormSlot(grammaticalCase: GrammaticalCase, number: GrammaticalNumber) derives JsonCodec, CanEqual

object FormSlot {

  /** Where a lemma stands, and the default for a relation that names no case and no number. Nominative singular is the
    * form a dictionary lists a noun under, so a relation this build cannot read falls back to the citation article
    * rather than to no article at all.
    */
  val citation: FormSlot = FormSlot(GrammaticalCase.Nominative, GrammaticalNumber.Singular)

  /** Every cell a declension table can hold, in reading order. */
  val all: List[FormSlot] = {
    for {
      number          <- GrammaticalNumber.all
      grammaticalCase <- GrammaticalCase.all
    } yield FormSlot(grammaticalCase, number)
  }

  /** How one cell is chosen when a word stands in several — the nominative first, and the singular first within a case.
    *
    * The case dominates the number, which is the whole point of the ordering. `Wege` is the plural of `der Weg` and
    * reaches four cells: nominative, accusative and genitive plural, plus the archaic dative singular `dem Wege`. A
    * number-first ordering picks that dative singular and asks a plural word in the singular; a case-first one picks
    * the nominative plural, which is what `Wege` is.
    *
    * Reads [[GrammaticalCase.all]]'s order directly, which already leads with the nominative — the second consumer of
    * that ordering, after `GrammarTag.slotOf`. Reordering it moves both.
    */
  private def preferenceKey(slot: FormSlot): (Int, Int) = {
    (GrammaticalCase.all.indexOf(slot.grammaticalCase), GrammaticalNumber.all.indexOf(slot.number))
  }

  /** The one cell to ask a word in, out of every cell it stands in.
    *
    * '''Never a draw.''' A word is paired with its translation for one reading, not for all of them: somebody who
    * marked `éjszakák` against `Nächte` meant the plural, and asking the same row as a genitive plural (`der Nächte`)
    * on one play and a nominative plural (`die Nächte`) on the next tests a pairing nobody recorded. The nominative is
    * what a dictionary cites a form under, so that is what gets asked.
    *
    * A form with no nominative cell at all keeps its own — `Tisches` is only ever a genitive singular, and `des
    * Tisches` is right where the nominative `der Tisches` would be wrong. [[citation]] for a word standing in no cell,
    * which is every lemma.
    */
  def preferred(slots: List[FormSlot]): FormSlot = {
    slots.minByOption(preferenceKey).getOrElse(citation)
  }
}
