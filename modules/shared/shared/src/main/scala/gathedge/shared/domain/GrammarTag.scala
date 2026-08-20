package gathedge.shared.domain

/** The broad group a `word_forms.relation` tag belongs to — how the word detail page's Forms section groups a lemma's
  * forms without needing a grammar-table layout for this pass. `Other` is the required fallback: `relation` is
  * deliberately not a closed enum (see `WordFormRow`'s doc comment), so a wiktextract dump can carry a tag this file
  * has never seen.
  */
enum GrammarCategory {
  case PluralCase, Tense, Comparison, Diminutive, AlternativeSpelling, Other
}

/** Categorizes the individual tags a `word_forms.relation` string is made of (e.g. `"dative,definite,plural"` is the
  * tags `dative`, `definite`, `plural`). Coverage is the tags that dominate real occurrence counts in the imported data
  * — Hungarian's full case system, German's declension/conjugation tags, English's tense/comparison tags, and the
  * commonest register/dialect labels. Anything unmapped falls to [[GrammarCategory.Other]]; that fallback is load-
  * bearing, not a gap to close, since a future dump can add a tag this map has never seen.
  */
object GrammarTag {

  private val known: Map[String, (GrammarCategory, Int)] = {
    Map(
      // Plural / case forms
      "plural"             -> (GrammarCategory.PluralCase, 10),
      "singular"           -> (GrammarCategory.PluralCase, 10),
      "definite"           -> (GrammarCategory.PluralCase, 10),
      "indefinite"         -> (GrammarCategory.PluralCase, 10),
      "nominative"         -> (GrammarCategory.PluralCase, 10),
      "accusative"         -> (GrammarCategory.PluralCase, 10),
      "dative"             -> (GrammarCategory.PluralCase, 10),
      "genitive"           -> (GrammarCategory.PluralCase, 10),
      "possessed-single"   -> (GrammarCategory.PluralCase, 10),
      "possessed-many"     -> (GrammarCategory.PluralCase, 10),
      "superessive"        -> (GrammarCategory.PluralCase, 10),
      "sublative"          -> (GrammarCategory.PluralCase, 10),
      "allative"           -> (GrammarCategory.PluralCase, 10),
      "ablative"           -> (GrammarCategory.PluralCase, 10),
      "instrumental"       -> (GrammarCategory.PluralCase, 10),
      "inessive"           -> (GrammarCategory.PluralCase, 10),
      "illative"           -> (GrammarCategory.PluralCase, 10),
      "elative"            -> (GrammarCategory.PluralCase, 10),
      "delative"           -> (GrammarCategory.PluralCase, 10),
      "adessive"           -> (GrammarCategory.PluralCase, 10),
      "translative"        -> (GrammarCategory.PluralCase, 10),
      "causal-final"       -> (GrammarCategory.PluralCase, 10),
      "terminative"        -> (GrammarCategory.PluralCase, 10),
      "essive-formal"      -> (GrammarCategory.PluralCase, 10),
      // Tenses / verb paradigm
      "past"               -> (GrammarCategory.Tense, 20),
      "present"            -> (GrammarCategory.Tense, 20),
      "future"             -> (GrammarCategory.Tense, 20),
      "preterite"          -> (GrammarCategory.Tense, 20),
      "participle"         -> (GrammarCategory.Tense, 20),
      "infinitive"         -> (GrammarCategory.Tense, 20),
      "infinitive-zu"      -> (GrammarCategory.Tense, 20),
      "subjunctive"        -> (GrammarCategory.Tense, 20),
      "subjunctive-i"      -> (GrammarCategory.Tense, 20),
      "subjunctive-ii"     -> (GrammarCategory.Tense, 20),
      "indicative"         -> (GrammarCategory.Tense, 20),
      "imperative"         -> (GrammarCategory.Tense, 20),
      "subordinate-clause" -> (GrammarCategory.Tense, 20),
      "first-person"       -> (GrammarCategory.Tense, 20),
      "second-person"      -> (GrammarCategory.Tense, 20),
      "third-person"       -> (GrammarCategory.Tense, 20),
      "auxiliary"          -> (GrammarCategory.Tense, 20),
      "causative"          -> (GrammarCategory.Tense, 20),
      "noun-from-verb"     -> (GrammarCategory.Tense, 20),
      // Comparison
      "comparative"        -> (GrammarCategory.Comparison, 30),
      "superlative"        -> (GrammarCategory.Comparison, 30),
      // Diminutives
      "diminutive"         -> (GrammarCategory.Diminutive, 40),
      // Alternative spellings / register / dialect
      "alternative"        -> (GrammarCategory.AlternativeSpelling, 50),
      "dialectal"          -> (GrammarCategory.AlternativeSpelling, 50),
      "nonstandard"        -> (GrammarCategory.AlternativeSpelling, 50),
      "colloquial"         -> (GrammarCategory.AlternativeSpelling, 50),
      "archaic"            -> (GrammarCategory.AlternativeSpelling, 50),
      "rare"               -> (GrammarCategory.AlternativeSpelling, 50),
      "dated"              -> (GrammarCategory.AlternativeSpelling, 50),
      "regional"           -> (GrammarCategory.AlternativeSpelling, 50),
      "poetic"             -> (GrammarCategory.AlternativeSpelling, 50),
      "proscribed"         -> (GrammarCategory.AlternativeSpelling, 50),
      "uncommon"           -> (GrammarCategory.AlternativeSpelling, 50),
      "obsolete"           -> (GrammarCategory.AlternativeSpelling, 50),
    )
  }

  /** The category of a whole `relation` string: the lowest-priority category among its constituent tags wins, so
    * `"dative,definite,plural"` reads as a plural/case form even though `definite`/`plural` alone would tie.
    */
  def categoryOf(relation: String): GrammarCategory = {
    relation
      .split(',')
      .toList
      .flatMap(known.get)
      .minByOption { case (_, priority) => priority }
      .map { case (category, _) => category }
      .getOrElse(GrammarCategory.Other)
  }

  /** The sort key `WordService.detailOf`'s Forms list orders by — same numbering [[categoryOf]] already uses, so the
    * two never disagree about which category comes first.
    */
  def priorityOf(category: GrammarCategory): Int = {
    category match {
      case GrammarCategory.PluralCase          => 10
      case GrammarCategory.Tense               => 20
      case GrammarCategory.Comparison          => 30
      case GrammarCategory.Diminutive          => 40
      case GrammarCategory.AlternativeSpelling => 50
      case GrammarCategory.Other               => 90
    }
  }
}
