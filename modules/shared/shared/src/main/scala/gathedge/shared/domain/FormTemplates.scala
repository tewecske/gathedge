package gathedge.shared.domain

import gathedge.shared.i18n.UiKeys

/** The form tables of each language and part of speech, the way Wiktionary draws its conjugation and declension tables.
  * Each one is built from the tag sets the import writes into `word_forms.relation`; `FormTemplatesSpec` pins them
  * against real words.
  *
  * Any other part of speech keeps the list.
  */
object FormTemplates {

  def of(language: WordLanguage, partOfSpeech: PartOfSpeech): Option[FormTemplate] = {
    (language, partOfSpeech) match {
      case (WordLanguage.De, PartOfSpeech.Noun)                                          => Some(germanNoun)
      case (WordLanguage.De, PartOfSpeech.Verb)                                          => Some(germanVerb)
      case (WordLanguage.De, PartOfSpeech.Adjective)                                     => Some(germanAdjective)
      case (WordLanguage.Hu, PartOfSpeech.Noun)                                          => Some(hungarianNoun)
      case (WordLanguage.Hu, PartOfSpeech.Verb)                                          => Some(hungarianVerb)
      case (WordLanguage.Es, PartOfSpeech.Verb)                                          => Some(spanishVerb)
      case (WordLanguage.Fr, PartOfSpeech.Verb)                                          => Some(frenchVerb)
      case (WordLanguage.Pt, PartOfSpeech.Verb)                                          => Some(portugueseVerb)
      case (WordLanguage.En, PartOfSpeech.Noun)                                          => Some(englishNoun)
      case (WordLanguage.En, PartOfSpeech.Verb)                                          => Some(englishVerb)
      case (WordLanguage.En, PartOfSpeech.Adjective)                                     => Some(englishAdjective)
      case (WordLanguage.Es | WordLanguage.Fr | WordLanguage.Pt, PartOfSpeech.Noun)      => Some(romanceNoun)
      case (WordLanguage.Es | WordLanguage.Fr | WordLanguage.Pt, PartOfSpeech.Adjective) => Some(romanceAdjective)
      case _                                                                             => None
    }
  }

  private def axis(tags: String*): FormAxis = FormAxis.of(tags*)

  private def table(title: FormLabel, base: FormMatch, columns: List[FormAxis], rows: List[FormAxis]): FormTable = {
    FormTable(Some(title), base, columns, rows)
  }

  /** A single unlabelled column: the table's rows say everything. */
  private val one: List[FormAxis] = List(FormAxis(None, FormMatch()))

  private val persons: List[FormAxis] = {
    List(
      axis("first-person", "singular"),
      axis("second-person", "singular"),
      axis("third-person", "singular"),
      axis("first-person", "plural"),
      axis("second-person", "plural"),
      axis("third-person", "plural"),
    )
  }

  private val numbers: List[FormAxis] = List(axis("singular"), axis("plural"))

  private val germanCases: List[FormAxis] =
    List(axis("nominative"), axis("genitive"), axis("dative"), axis("accusative"))

  private val noFuture: FormMatch = FormMatch(exclude = Set("future"))

  // --- German --------------------------------------------------------------------------------------------------------

  /** Four cases by two numbers. A noun declined like an adjective (`Beamte`) also has its strong, weak and mixed
    * tables; any other noun has no form for them, so they drop.
    */
  val germanNoun: FormTemplate = {
    val declensions = List("strong", "weak", "mixed")
    FormTemplate(
      List(
        FormSection(
          None,
          table(FormLabel.Key(UiKeys.wordFormsDeclension), FormMatch(exclude = declensions.toSet), numbers, germanCases)
            :: declensions.map(declension =>
              table(FormLabel.tags(declension), FormMatch(declension), numbers, germanCases)
            ),
        )
      )
    )
  }

  /** One table per tense, with its moods as columns. A separable verb's subordinate-clause forms (`dass ich einkaufe`)
    * get a section of their own, so they never share a cell with the main clause's `kaufe ein`.
    */
  val germanVerb: FormTemplate = {
    val mainClause  = FormMatch(exclude = Set("subordinate-clause", "dependent"))
    val indicative  = (tense: String) => FormAxis.of("indicative", tense)
    val tenses      = List(
      table(
        FormLabel.tags("present"),
        mainClause,
        List(indicative("present"), FormAxis(FormLabel.tags("subjunctive-i"), FormMatch("subjunctive-i") ++ noFuture)),
        persons,
      ),
      table(
        FormLabel.tags("preterite"),
        mainClause,
        List(
          indicative("preterite"),
          FormAxis(FormLabel.tags("subjunctive-ii"), FormMatch("subjunctive-ii") ++ noFuture),
        ),
        persons,
      ),
      table(
        FormLabel.tags("perfect"),
        mainClause,
        List(indicative("perfect"), FormAxis.of("subjunctive", "perfect")),
        persons,
      ),
      table(
        FormLabel.tags("pluperfect"),
        mainClause,
        List(indicative("pluperfect"), FormAxis.of("subjunctive", "pluperfect")),
        persons,
      ),
      table(
        FormLabel.tags("future-i"),
        mainClause,
        List(
          indicative("future-i"),
          FormAxis.of("subjunctive-i", "future-i"),
          FormAxis.of("subjunctive-ii", "future-i"),
        ),
        persons,
      ),
      table(
        FormLabel.tags("future-ii"),
        mainClause,
        List(
          indicative("future-ii"),
          FormAxis.of("subjunctive-i", "future-ii"),
          FormAxis.of("subjunctive-ii", "future-ii"),
        ),
        persons,
      ),
      table(FormLabel.tags("imperative"), mainClause ++ FormMatch("imperative"), one, numbers),
      table(
        FormLabel.Key(UiKeys.wordFormsNonFinite),
        mainClause,
        one,
        List(
          FormAxis(
            FormLabel.tags("infinitive"),
            FormMatch(include = Set("infinitive"), exclude = Set("future", "infinitive-zu")),
          ),
          axis("infinitive-zu"),
          axis("participle", "present"),
          axis("participle", "past"),
          axis("future-i", "infinitive"),
          axis("future-ii", "infinitive"),
        ),
      ),
    )
    val subordinate = table(
      FormLabel.tags("subordinate-clause"),
      FormMatch("subordinate-clause"),
      List(axis("present"), axis("preterite"), axis("subjunctive-i")),
      persons,
    )
    FormTemplate(
      List(FormSection(None, tenses), FormSection(Some(FormLabel.tags("subordinate-clause")), List(subordinate)))
    )
  }

  /** Strong, weak and mixed declension, for each degree in a section of its own. The strong table holds the bare
    * adjective (`freier`); weak and mixed hold it with its article (`der freie`, `ein freier`), as Wiktionary does.
    */
  val germanAdjective: FormTemplate = {
    val genders                                             = List(
      FormAxis.of("masculine", "singular"),
      FormAxis.of("feminine", "singular"),
      FormAxis.of("neuter", "singular"),
      FormAxis.of("plural"),
    )
    def degree(title: String, base: FormMatch): FormSection = {
      FormSection(
        Some(FormLabel.tags(title)),
        List(
          table(FormLabel.tags("strong"), base ++ FormMatch("strong", "without-article"), genders, germanCases),
          table(FormLabel.tags("weak"), base ++ FormMatch("weak", "includes-article"), genders, germanCases),
          table(FormLabel.tags("mixed"), base ++ FormMatch("mixed", "includes-article"), genders, germanCases),
          table(FormLabel.tags("predicative"), base ++ FormMatch("predicative"), one, List(FormAxis(None, FormMatch()))),
        ),
      )
    }
    FormTemplate(
      List(
        degree("positive", FormMatch(exclude = Set("comparative", "superlative"))),
        degree("comparative", FormMatch("comparative")),
        degree("superlative", FormMatch("superlative")),
      )
    )
  }

  // --- Hungarian -----------------------------------------------------------------------------------------------------

  private val hungarianCases: List[String] = {
    List(
      "nominative",
      "accusative",
      "dative",
      "instrumental",
      "causal-final",
      "translative",
      "terminative",
      "essive-formal",
      "essive-modal",
      "inessive",
      "superessive",
      "adessive",
      "illative",
      "sublative",
      "allative",
      "elative",
      "delative",
      "ablative",
    )
  }

  /** The eighteen cases and the two possessor forms (`házé`, `házéi`) by number, then the possessive forms by person.
    * The plural column of the possessive table is "many things possessed", not the possessor's number: that is in the
    * row.
    */
  val hungarianNoun: FormTemplate = {
    val cases       = table(
      FormLabel.Key(UiKeys.wordFormsDeclension),
      FormMatch(exclude = Set("possessive")),
      numbers,
      hungarianCases.map(axis(_)) ++ List(
        FormAxis(FormLabel.Text("-é"), FormMatch("possessor", "possessed-single")),
        FormAxis(FormLabel.Text("-éi"), FormMatch("possessor", "possessed-many")),
      ),
    )
    val possessives = table(
      FormLabel.tags("possessive"),
      FormMatch(include = Set("possessive"), exclude = Set("possessor", "predicative")),
      List(axis("possessed-single"), axis("possessed-many")),
      persons,
    )
    FormTemplate(List(FormSection(None, List(cases, possessives))))
  }

  /** Each mood and tense by person, indefinite and definite side by side. The `-lak` form (`adlak`, "I give you") is a
    * first person singular with a second person object, so it has a table of its own. The potential (`adhat`) repeats
    * every table in a section of its own.
    */
  val hungarianVerb: FormTemplate = {
    val conjugations                             = List(axis("indefinite"), axis("definite"))
    def tables(base: FormMatch): List[FormTable] = {
      val finite = base ++ FormMatch(exclude = Set("object-second-person"))
      List(
        table(
          FormLabel.tags("present", "indicative"),
          finite ++ FormMatch("indicative", "present"),
          conjugations,
          persons,
        ),
        table(FormLabel.tags("past", "indicative"), finite ++ FormMatch("indicative", "past"), conjugations, persons),
        table(FormLabel.tags("conditional"), finite ++ FormMatch("conditional", "present"), conjugations, persons),
        table(FormLabel.tags("subjunctive"), finite ++ FormMatch("subjunctive", "present"), conjugations, persons),
        table(
          FormLabel.tags("first-person", "singular", "object-second-person"),
          base ++ FormMatch("first-person", "singular", "object-second-person"),
          one,
          List(
            axis("present", "indicative"),
            axis("past", "indicative"),
            axis("conditional"),
            axis("subjunctive"),
          ),
        ),
        table(FormLabel.tags("personal", "infinitive"), base ++ FormMatch("infinitive"), one, persons),
        table(
          FormLabel.Key(UiKeys.wordFormsNonFinite),
          base ++ FormMatch(exclude = Set("first-person", "second-person", "third-person")),
          one,
          List(
            axis("infinitive"),
            axis("participle", "present"),
            axis("participle", "past"),
            axis("participle", "future"),
            axis("participle", "adverbial"),
          ),
        ),
      )
    }
    FormTemplate(
      List(
        FormSection(None, tables(FormMatch(exclude = Set("potential")))),
        FormSection(Some(FormLabel.tags("potential")), tables(FormMatch("potential"))),
      )
    )
  }

  // --- Spanish -------------------------------------------------------------------------------------------------------

  private val vos: Set[String] = Set("with-voseo", "vos-form", "with-vos")

  /** The six persons, with `vos` as a row of its own after `tú`. */
  private val spanishPersons: List[FormAxis] = {
    List(
      axis("first-person", "singular"),
      FormAxis(
        FormLabel.tags("second-person", "singular"),
        FormMatch(include = Set("second-person", "singular"), exclude = vos),
      ),
      FormAxis(FormLabel.Text("vos"), FormMatch(include = Set("second-person", "singular"), anyOf = List(vos))),
      axis("third-person", "singular"),
      axis("first-person", "plural"),
      axis("second-person", "plural"),
      axis("third-person", "plural"),
    )
  }

  /** The persons an imperative takes. `usted` and `ustedes` are third person in the dump. */
  private val imperativePersons: List[FormAxis] = spanishPersons.tail

  private val objects: Set[String] = {
    Set("object-first-person", "object-second-person", "object-third-person", "combined-form")
  }

  /** The indicative, subjunctive and imperative by person, then the same for the reflexive verb (`me compro`), then the
    * infinitive, gerund and imperative with an object pronoun attached (`cómprame`).
    *
    * The `-ra` and `-se` imperfect subjunctive share a column: the dump's head-line rows give both under one tag set,
    * so a cell could not keep them apart.
    *
    * The pronoun table takes the plain rows and the `combined-form` rows alike. They repeat each other, but only the
    * combined rows have `vos` and the `te`/`os` forms of the first person plural (`comprémoste`).
    */
  val spanishVerb: FormTemplate = {
    def moods(base: FormMatch): List[FormTable] = {
      List(
        table(
          FormLabel.tags("indicative"),
          base ++ FormMatch("indicative"),
          List(axis("present"), axis("imperfect"), axis("preterite"), axis("future"), axis("conditional")),
          spanishPersons,
        ),
        table(
          FormLabel.tags("subjunctive"),
          base ++ FormMatch("subjunctive"),
          List(
            axis("present"),
            FormAxis(
              FormLabel.tags("imperfect"),
              FormMatch(include = Set("imperfect"), optional = Set("imperfect-se")),
            ),
            axis("future"),
          ),
          spanishPersons,
        ),
        table(
          FormLabel.tags("imperative"),
          base ++ FormMatch(include = Set("imperative"), exclude = objects),
          List(
            FormAxis(FormLabel.tags("affirmative"), FormMatch(exclude = Set("negative"))),
            axis("negative"),
          ),
          imperativePersons,
        ),
      )
    }
    val plain                                   = FormMatch(exclude = Set("reflexive"))
    val nonFinite                               = table(
      FormLabel.Key(UiKeys.wordFormsNonFinite),
      FormMatch(exclude = objects ++ Set("first-person", "second-person", "third-person", "reflexive")),
      one,
      List(
        axis("infinitive"),
        axis("gerund"),
        axis("participle", "past", "masculine", "singular"),
        axis("participle", "past", "feminine", "singular"),
        axis("participle", "past", "masculine", "plural"),
        axis("participle", "past", "feminine", "plural"),
      ),
    )
    val reflexive                               = moods(FormMatch("reflexive")) :+ table(
      FormLabel.Key(UiKeys.wordFormsNonFinite),
      FormMatch("reflexive"),
      one,
      List(axis("infinitive"), axis("gerund")),
    )
    val pronoun                                 = (text: String, tags: Set[String]) => FormAxis(FormLabel.Text(text), FormMatch(include = tags))
    val withPronouns                            = table(
      FormLabel.tags("combined-form"),
      FormMatch(exclude = Set("reflexive", "negative", "formal")),
      List(
        pronoun("me", Set("object-first-person", "object-singular")),
        pronoun("te", Set("object-second-person", "object-singular")),
        pronoun("lo", Set("object-masculine", "object-singular")),
        pronoun("la", Set("object-feminine", "object-singular")),
        pronoun("le", Set("dative", "object-third-person", "object-singular")),
        pronoun("nos", Set("object-first-person", "object-plural")),
        pronoun("os", Set("object-second-person", "object-plural")),
        pronoun("los", Set("object-masculine", "object-plural")),
        pronoun("las", Set("object-feminine", "object-plural")),
        pronoun("les", Set("dative", "object-third-person", "object-plural")),
      ),
      List(axis("infinitive"), axis("gerund")) ++ imperativePersons.map(person => {
        FormAxis(person.label, person.matcher ++ FormMatch("imperative"))
      }),
    )
    FormTemplate(
      List(
        FormSection(None, moods(plain) :+ nonFinite),
        FormSection(Some(FormLabel.tags("reflexive")), reflexive),
        FormSection(Some(FormLabel.tags("combined-form")), List(withPronouns)),
      )
    )
  }

  // --- Spanish, French and Portuguese nouns and adjectives ------------------------------------------------------------

  /** Singular and plural. The dump has no row for the headword itself, so the word fills its own cell. A gender
    * counterpart's plural (`niñas` of `niño`) is not this word's plural, so it stays out.
    */
  val romanceNoun: FormTemplate = {
    FormTemplate(
      List(
        FormSection(
          None,
          List(
            FormTable(
              Some(FormLabel.Key(UiKeys.wordFormsDeclension)),
              FormMatch(exclude = Set("masculine", "feminine")),
              numbers,
              List(FormAxis(None, FormMatch())),
              lemma = Map((0, 0) -> LemmaFill.Always),
            )
          ),
        )
      )
    )
  }

  /** Gender by number, then the comparative and superlative. The headword is the masculine singular. The dump tags a
    * feminine singular `feminine` alone (`buena`), so the singular column is "not plural". An adjective with one form
    * for both genders (`libre`) has no feminine singular row, so the headword fills that cell too. French `bel`, the
    * form before a vowel, shares the masculine singular cell with `beau`.
    */
  val romanceAdjective: FormTemplate = {
    FormTemplate(
      List(
        FormSection(
          None,
          List(
            FormTable(
              Some(FormLabel.Key(UiKeys.wordFormsDeclension)),
              FormMatch(exclude = Set("comparative", "superlative")),
              List(
                FormAxis(FormLabel.tags("singular"), FormMatch(exclude = Set("plural"))),
                axis("plural"),
              ),
              List(axis("masculine"), axis("feminine")),
              lemma = Map((0, 0) -> LemmaFill.Always, (1, 0) -> LemmaFill.WhenEmpty),
            ),
            table(
              FormLabel.Key(UiKeys.wordFormsComparison),
              FormMatch(),
              one,
              List(axis("comparative"), axis("superlative")),
            ),
          ),
        )
      )
    )
  }

  /** A column for one indicative tense, labelled by the tense alone: the table's title already says "indicative". */
  private def indicativeTense(tense: String): FormAxis = {
    FormAxis(FormLabel.tags(tense), FormMatch("indicative", tense))
  }

  // --- French --------------------------------------------------------------------------------------------------------

  /** The indicative tenses and the conditional by person, then the subjunctive, the imperative and the non-finite
    * forms. The dump has no compound tenses (`j'ai parlé`).
    */
  val frenchVerb: FormTemplate = {
    FormTemplate(
      List(
        FormSection(
          None,
          List(
            table(
              FormLabel.tags("indicative"),
              FormMatch(),
              List(
                indicativeTense("present"),
                indicativeTense("imperfect"),
                axis("historic", "past"),
                indicativeTense("future"),
                axis("conditional"),
              ),
              persons,
            ),
            table(
              FormLabel.tags("subjunctive"),
              FormMatch("subjunctive"),
              List(axis("present"), axis("imperfect")),
              persons,
            ),
            table(
              FormLabel.tags("imperative"),
              FormMatch("imperative"),
              one,
              List(axis("second-person", "singular"), axis("first-person", "plural"), axis("second-person", "plural")),
            ),
            table(
              FormLabel.Key(UiKeys.wordFormsNonFinite),
              FormMatch(),
              one,
              List(axis("infinitive"), axis("participle", "present"), axis("participle", "past")),
            ),
          ),
        )
      )
    )
  }

  // --- Portuguese ----------------------------------------------------------------------------------------------------

  /** The indicative tenses and the conditional by person, the subjunctive, the imperative, then the personal infinitive
    * (`falarmos`) and the non-finite forms. A Brazilian and a European spelling of one cell are both shown (`falamos /
    * falámos`).
    */
  val portugueseVerb: FormTemplate = {
    FormTemplate(
      List(
        FormSection(
          None,
          List(
            table(
              FormLabel.tags("indicative"),
              FormMatch(),
              List(
                indicativeTense("present"),
                indicativeTense("preterite"),
                indicativeTense("imperfect"),
                indicativeTense("pluperfect"),
                indicativeTense("future"),
                axis("conditional"),
              ),
              persons,
            ),
            table(
              FormLabel.tags("subjunctive"),
              FormMatch("subjunctive"),
              List(axis("present"), axis("imperfect"), axis("future")),
              persons,
            ),
            table(
              FormLabel.tags("imperative"),
              FormMatch("imperative"),
              List(FormAxis(FormLabel.tags("affirmative"), FormMatch(exclude = Set("negative"))), axis("negative")),
              persons.tail,
            ),
            table(
              FormLabel.tags("personal", "infinitive"),
              FormMatch(include = Set("infinitive"), exclude = Set("impersonal")),
              one,
              persons,
            ),
            table(
              FormLabel.Key(UiKeys.wordFormsNonFinite),
              FormMatch(exclude = Set("first-person", "second-person", "third-person")),
              one,
              List(
                axis("infinitive"),
                axis("gerund"),
                axis("participle", "past", "masculine", "singular"),
                axis("participle", "past", "feminine", "singular"),
                axis("participle", "past", "masculine", "plural"),
                axis("participle", "past", "feminine", "plural"),
              ),
            ),
          ),
        )
      )
    )
  }

  // --- English -------------------------------------------------------------------------------------------------------

  /** No relation carries this tag, so a row testing for it takes no form and holds only the headword. */
  private val headwordOnly: FormMatch = FormMatch("headword-only")

  /** Singular and plural. The headword is the singular. */
  val englishNoun: FormTemplate = {
    FormTemplate(
      List(
        FormSection(
          None,
          List(
            FormTable(
              Some(FormLabel.Key(UiKeys.wordFormsDeclension)),
              FormMatch(),
              List(FormAxis(FormLabel.tags("singular"), headwordOnly), axis("plural")),
              List(FormAxis(None, FormMatch())),
              lemma = Map((0, 0) -> LemmaFill.Always),
            )
          ),
        )
      )
    )
  }

  /** The principal parts. The past row takes only a plain past, with no person, number or mood, so `be` keeps `was` and
    * `were` for the list below rather than one of them standing for both.
    */
  val englishVerb: FormTemplate = {
    val personal = Set("first-person", "second-person", "third-person", "singular", "plural")
    FormTemplate(
      List(
        FormSection(
          None,
          List(
            FormTable(
              Some(FormLabel.Key(UiKeys.wordFormsPrincipalParts)),
              FormMatch(),
              one,
              List(
                axis("infinitive"),
                axis("present", "third-person", "singular"),
                FormAxis(
                  FormLabel.tags("past"),
                  FormMatch(include = Set("past"), exclude = personal ++ Set("participle", "subjunctive")),
                ),
                axis("participle", "present"),
                axis("participle", "past"),
              ),
              lemma = Map((0, 0) -> LemmaFill.WhenEmpty),
            )
          ),
        )
      )
    )
  }

  /** The three degrees. The headword is the positive. */
  val englishAdjective: FormTemplate = {
    FormTemplate(
      List(
        FormSection(
          None,
          List(
            FormTable(
              Some(FormLabel.Key(UiKeys.wordFormsComparison)),
              FormMatch(),
              one,
              List(FormAxis(FormLabel.tags("positive"), headwordOnly), axis("comparative"), axis("superlative")),
              lemma = Map((0, 0) -> LemmaFill.Always),
            )
          ),
        )
      )
    )
  }
}
