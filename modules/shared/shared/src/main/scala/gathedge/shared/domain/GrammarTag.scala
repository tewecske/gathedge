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
      "plural"                     -> (GrammarCategory.PluralCase, 10),
      "singular"                   -> (GrammarCategory.PluralCase, 10),
      "definite"                   -> (GrammarCategory.PluralCase, 10),
      "indefinite"                 -> (GrammarCategory.PluralCase, 10),
      "nominative"                 -> (GrammarCategory.PluralCase, 10),
      "accusative"                 -> (GrammarCategory.PluralCase, 10),
      "dative"                     -> (GrammarCategory.PluralCase, 10),
      "genitive"                   -> (GrammarCategory.PluralCase, 10),
      "possessed-single"           -> (GrammarCategory.PluralCase, 10),
      "possessed-many"             -> (GrammarCategory.PluralCase, 10),
      "superessive"                -> (GrammarCategory.PluralCase, 10),
      "sublative"                  -> (GrammarCategory.PluralCase, 10),
      "allative"                   -> (GrammarCategory.PluralCase, 10),
      "ablative"                   -> (GrammarCategory.PluralCase, 10),
      "instrumental"               -> (GrammarCategory.PluralCase, 10),
      "inessive"                   -> (GrammarCategory.PluralCase, 10),
      "illative"                   -> (GrammarCategory.PluralCase, 10),
      "elative"                    -> (GrammarCategory.PluralCase, 10),
      "delative"                   -> (GrammarCategory.PluralCase, 10),
      "adessive"                   -> (GrammarCategory.PluralCase, 10),
      "translative"                -> (GrammarCategory.PluralCase, 10),
      "causal-final"               -> (GrammarCategory.PluralCase, 10),
      "terminative"                -> (GrammarCategory.PluralCase, 10),
      "essive-formal"              -> (GrammarCategory.PluralCase, 10),
      // Tenses / verb paradigm
      "past"                       -> (GrammarCategory.Tense, 5),
      "present"                    -> (GrammarCategory.Tense, 5),
      "future"                     -> (GrammarCategory.Tense, 5),
      "preterite"                  -> (GrammarCategory.Tense, 5),
      "participle"                 -> (GrammarCategory.Tense, 5),
      "infinitive"                 -> (GrammarCategory.Tense, 5),
      "infinitive-zu"              -> (GrammarCategory.Tense, 5),
      "subjunctive"                -> (GrammarCategory.Tense, 5),
      "subjunctive-i"              -> (GrammarCategory.Tense, 5),
      "subjunctive-ii"             -> (GrammarCategory.Tense, 5),
      "indicative"                 -> (GrammarCategory.Tense, 5),
      "imperative"                 -> (GrammarCategory.Tense, 5),
      "subordinate-clause"         -> (GrammarCategory.Tense, 5),
      "first-person"               -> (GrammarCategory.Tense, 20),
      "second-person"              -> (GrammarCategory.Tense, 20),
      "third-person"               -> (GrammarCategory.Tense, 20),
      "reflexive"                  -> (GrammarCategory.Tense, 20),
      "auxiliary"                  -> (GrammarCategory.Tense, 20),
      "causative"                  -> (GrammarCategory.Tense, 5),
      "noun-from-verb"             -> (GrammarCategory.Tense, 5),
      // Added with the full conjugation and declension tables
      "conditional"                -> (GrammarCategory.Tense, 5),
      "potential"                  -> (GrammarCategory.Tense, 5),
      "perfect"                    -> (GrammarCategory.Tense, 5),
      "pluperfect"                 -> (GrammarCategory.Tense, 5),
      "future-i"                   -> (GrammarCategory.Tense, 5),
      "future-ii"                  -> (GrammarCategory.Tense, 5),
      "imperfect"                  -> (GrammarCategory.Tense, 5),
      "imperfect-se"               -> (GrammarCategory.Tense, 5),
      "gerund"                     -> (GrammarCategory.Tense, 5),
      "combined-form"              -> (GrammarCategory.Tense, 5),
      "object-first-person"        -> (GrammarCategory.Tense, 5),
      "object-second-person"       -> (GrammarCategory.Tense, 5),
      "object-third-person"        -> (GrammarCategory.Tense, 5),
      "object-singular"            -> (GrammarCategory.Tense, 5),
      "object-plural"              -> (GrammarCategory.Tense, 5),
      "object-masculine"           -> (GrammarCategory.Tense, 5),
      "object-feminine"            -> (GrammarCategory.Tense, 5),
      "dependent"                  -> (GrammarCategory.Tense, 5),
      "adverbial"                  -> (GrammarCategory.Tense, 5),
      "formal"                     -> (GrammarCategory.Tense, 20),
      "informal"                   -> (GrammarCategory.Tense, 20),
      "second-person-semantically" -> (GrammarCategory.Tense, 20),
      "with-tú"                    -> (GrammarCategory.Tense, 20),
      "with-vos"                   -> (GrammarCategory.Tense, 20),
      "with-voseo"                 -> (GrammarCategory.Tense, 20),
      "vos-form"                   -> (GrammarCategory.Tense, 20),
      "includes-article"           -> (GrammarCategory.PluralCase, 10),
      "with-article"               -> (GrammarCategory.PluralCase, 10),
      "without-article"            -> (GrammarCategory.PluralCase, 10),
      "strong"                     -> (GrammarCategory.PluralCase, 10),
      "weak"                       -> (GrammarCategory.PluralCase, 10),
      "mixed"                      -> (GrammarCategory.PluralCase, 10),
      "masculine"                  -> (GrammarCategory.PluralCase, 10),
      "feminine"                   -> (GrammarCategory.PluralCase, 10),
      "neuter"                     -> (GrammarCategory.PluralCase, 10),
      "possessive"                 -> (GrammarCategory.PluralCase, 10),
      "possessor"                  -> (GrammarCategory.PluralCase, 10),
      "predicative"                -> (GrammarCategory.PluralCase, 10),
      "negative"                   -> (GrammarCategory.PluralCase, 10),
      "essive-modal"               -> (GrammarCategory.PluralCase, 10),
      // Comparison
      "comparative"                -> (GrammarCategory.Comparison, 30),
      "superlative"                -> (GrammarCategory.Comparison, 30),
      // Diminutives
      "diminutive"                 -> (GrammarCategory.Diminutive, 40),
      // Alternative spellings / register / dialect
      "alternative"                -> (GrammarCategory.AlternativeSpelling, 50),
      "dialectal"                  -> (GrammarCategory.AlternativeSpelling, 50),
      "nonstandard"                -> (GrammarCategory.AlternativeSpelling, 50),
      "colloquial"                 -> (GrammarCategory.AlternativeSpelling, 50),
      "archaic"                    -> (GrammarCategory.AlternativeSpelling, 50),
      "rare"                       -> (GrammarCategory.AlternativeSpelling, 50),
      "dated"                      -> (GrammarCategory.AlternativeSpelling, 50),
      "regional"                   -> (GrammarCategory.AlternativeSpelling, 50),
      "poetic"                     -> (GrammarCategory.AlternativeSpelling, 50),
      "proscribed"                 -> (GrammarCategory.AlternativeSpelling, 50),
      "uncommon"                   -> (GrammarCategory.AlternativeSpelling, 50),
      "obsolete"                   -> (GrammarCategory.AlternativeSpelling, 50),
    )
  }

  /** The category of a whole `relation` string: the lowest-priority category among its constituent tags wins, so
    * `"dative,definite,plural"` reads as a plural/case form even though `definite`/`plural` alone would tie.
    *
    * A mood, tense or conjugation tag has priority 5, below every plural/case tag. Only a verb form carries one, and a
    * verb form carries number too: without it `"first-person,indicative,plural,present"` would read as a plural/case
    * form and split every conjugation table across two headings. A person tag stays at 20, since a Hungarian possessive
    * noun form carries one as well.
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
