package gathedge.shared.domain

import zio.test.*

/** `FormTable.layout` and each template of `FormTemplates`, against real relation sets (`FormFixtures`).
  *
  * Each word's whole layout is pinned as text, so every cell, every gap and every form left below the tables is visible
  * in one place. A dash is an empty cell; each one is a gap in the dump itself:
  *
  *   - Spanish `vos` has only the present forms, and no `los`/`las` form that names the object's gender;
  *   - the subordinate clause of a German separable verb has subjunctive I forms only where they differ from the
  *     indicative's.
  *
  * Some odd cells are odd in the dump too: French `paʁl` (a pronunciation imported as a form) beside `parle`, and
  * English `be`'s person forms, which no principal part takes, in the list below the table.
  */
object FormTemplatesSpec extends ZIOSpecDefault {

  final case class Form(id: Long, text: String, relation: String)

  /** The fixture as `WordService.detailOf` hands it to the page: one entry per relation, one id per distinct word (the
    * headword is id 0), sorted by category and then text.
    */
  def forms(lemma: String, fixture: String): List[Form] = {
    val rows = fixture.linesIterator.map(_.split(';').toList).toList.collect { case List(text, pos, gender, relation) =>
      ((text, pos, gender), relation)
    }
    val ids  = rows.map(_._1).distinct.zipWithIndex.toMap
    rows
      .map { case (key @ (text, _, _), relation) =>
        Form(if (text == lemma) 0L else ids(key) + 1L, text, relation)
      }
      .sortBy(form => (GrammarTag.priorityOf(GrammarTag.categoryOf(form.relation)), form.text.toLowerCase))
  }

  def label(value: Option[FormLabel]): String = {
    value match {
      case Some(FormLabel.Tags(tags)) => tags.mkString(" ")
      case Some(FormLabel.Text(text)) => text
      case Some(FormLabel.Key(key))   => key
      case None                       => ""
    }
  }

  /** A layout as plain text: one block per table, one line per row, cells split by `|`, `-` for an empty cell, the
    * headword in brackets.
    */
  def render(lemma: String, layout: FormLayout[Form]): String = {
    val tables = for {
      section <- layout.sections
      table   <- section.tables
    } yield {
      val heading = s"## ${label(section.title)} / ${label(table.title)}"
      val columns = table.columns.map(label).mkString(" | ")
      val rows    = table.rows.map(row => {
        val cells = row.cells.map(cell => {
          val texts = ((if (cell.lemma) List(s"[$lemma]") else Nil) ++ cell.forms.map(_.text))
            .map(text => cell.article.fold(text)(article => s"$article $text"))
          if (texts.isEmpty) "-" else texts.mkString(" / ")
        })
        s"${label(row.label)}: ${cells.mkString(" | ")}"
      })
      (heading :: s"cols: $columns" :: rows).mkString("\n")
    }
    val rest   = layout.rest.map(form => s"${form.text} (${form.relation})").mkString(", ")
    (tables :+ s"## rest: $rest").mkString("\n").linesIterator.map(_.stripTrailing).mkString("\n")
  }

  /** As the word page lays it out: the articles come from the language and the noun's `gender`. */
  def laidOut(
    language: WordLanguage,
    pos: PartOfSpeech,
    lemma: String,
    fixture: String,
    gender: Option[Gender] = None,
  ): String = {
    val template = FormTemplates.of(language, pos).getOrElse(throw new IllegalArgumentException("no template"))
    val articles = (tags: Set[String]) => LanguageProfile.of(language).declinedArticle(gender, tags)
    render(lemma, FormTable.layout(template, forms(lemma, fixture), articles)(_.relation, _.id))
  }

  /** A one-table template over `forms` written as `id:relation`, for the rules on their own. */
  def single(table: FormTable, forms: String*): FormLayout[Form] = {
    val parsed = forms.toList.map(entry => {
      val (id, relation) = entry.splitAt(entry.indexOf(':'))
      Form(id.toLong, s"w$id", relation.drop(1))
    })
    FormTable.layout(FormTemplate(List(FormSection(None, List(table)))), parsed)(_.relation, _.id)
  }

  def cells(layout: FormLayout[Form]): List[List[List[Long]]] = {
    layout.sections.flatMap(_.tables).flatMap(_.rows).map(_.cells.map(_.forms.map(_.id)))
  }

  private val oneCell = List(FormAxis(None, FormMatch()))

  def spec = {
    suite("FormTemplates")(
      suite("FormTable.layout")(
        test("a cell keeps only the relations with the fewest tags it does not name") {
          val layout =
            single(FormTable(None, FormMatch("imperative"), oneCell, oneCell), "1:imperative", "2:imperative,informal")
          assertTrue(cells(layout) == List(List(List(1L))), layout.rest.map(_.id) == List(2L))
        },
        test("an optional tag does not count against a relation") {
          val matcher = FormMatch(include = Set("imperfect"), optional = Set("imperfect-se"))
          val layout  = single(FormTable(None, matcher, oneCell, oneCell), "1:imperfect", "2:imperfect,imperfect-se")
          assertTrue(cells(layout) == List(List(List(1L, 2L))))
        },
        test("exclude and anyOf decide which relations pass at all") {
          val matcher =
            FormMatch(include = Set("singular"), exclude = Set("rare"), anyOf = List(Set("vos-form", "with-voseo")))
          val layout  = single(
            FormTable(None, matcher, oneCell, oneCell),
            "1:singular,vos-form",
            "2:singular,with-voseo",
            "3:singular",
            "4:singular,vos-form,rare",
          )
          assertTrue(cells(layout) == List(List(List(1L, 2L))), layout.rest.map(_.id) == List(3L, 4L))
        },
        test("a word reached through two relations is shown once, and not repeated below") {
          val table  = FormTable(None, FormMatch(), List(FormAxis.of("present")), List(FormAxis.of("singular")))
          val layout = single(table, "1:present,singular", "1:indicative,present,singular", "1:past")
          assertTrue(cells(layout) == List(List(List(1L))), layout.rest.isEmpty)
        },
        test("an empty row drops, the headword keeps its row, and a table holding only the headword drops") {
          val rows   = List(FormAxis.of("singular"), FormAxis.of("plural"), FormAxis.of("dual"))
          val table  = FormTable(None, FormMatch(), oneCell, rows, lemma = Map((0, 0) -> LemmaFill.Always))
          val filled = single(table, "1:plural")
          val bare   = single(table)
          assertTrue(
            filled.sections.head.tables.head.rows.map(_.label) ==
              List(Some(FormLabel.tags("singular")), Some(FormLabel.tags("plural"))),
            bare.sections.isEmpty,
          )
        },
        test("the headword fills a WhenEmpty cell only while no form does") {
          val rows  = List(FormAxis.of("masculine"), FormAxis.of("feminine"))
          val table = FormTable(
            None,
            FormMatch(),
            List(FormAxis(None, FormMatch(exclude = Set("plural"))), FormAxis.of("plural")),
            rows,
            lemma = Map((1, 0) -> LemmaFill.WhenEmpty),
          )
          val buena = single(table, "1:feminine", "2:feminine,plural")
          val libre = single(table, "2:feminine,plural")
          assertTrue(
            buena.sections.head.tables.head.rows.head.cells.head.lemma == false,
            libre.sections.head.tables.head.rows.head.cells.head.lemma,
          )
        },
      ),
      suite("templates against real words")(
        test("German noun") {
          val masculine = Some(Gender.Masculine)
          val neuter    = Some(Gender.Neuter)
          assertTrue(
            laidOut(WordLanguage.De, PartOfSpeech.Noun, "Künstler", FormFixtures.deKunstler, masculine) ==
              Expected.deKunstler,
            laidOut(WordLanguage.De, PartOfSpeech.Noun, "Haus", FormFixtures.deHaus, neuter) == Expected.deHaus,
          )
        },
        test("German noun declined like an adjective: only the weak table takes the definite article") {
          val fixture = List(
            "Beamter;noun;masculine;strong,nominative,singular",
            "Beamten;noun;masculine;strong,genitive,singular",
            "Beamte;noun;masculine;weak,nominative,singular",
            "Beamten;noun;masculine;weak,dative,plural",
            "Beamter;noun;masculine;mixed,nominative,singular",
          ).mkString("\n")
          assertTrue(
            laidOut(WordLanguage.De, PartOfSpeech.Noun, "Beamte", fixture, Some(Gender.Masculine)) == Expected.deBeamte
          )
        },
        test("a German noun with no gender takes only the plural's article") {
          val fixture = "Leute;noun;;nominative,plural\nLeuten;noun;;dative,plural"
          assertTrue(
            laidOut(WordLanguage.De, PartOfSpeech.Noun, "Leute", fixture) ==
              """##  / ui.word.forms.table.declension
                 |cols: singular | plural
                 |nominative: - | die Leute
                 |dative: - | den Leuten
                 |## rest:""".stripMargin
          )
        },
        test("German verb, and a separable one") {
          assertTrue(
            laidOut(WordLanguage.De, PartOfSpeech.Verb, "kaufen", FormFixtures.deKaufen) == Expected.deKaufen,
            laidOut(WordLanguage.De, PartOfSpeech.Verb, "einkaufen", FormFixtures.deEinkaufen) == Expected.deEinkaufen,
          )
        },
        test("German adjective") {
          assertTrue(laidOut(WordLanguage.De, PartOfSpeech.Adjective, "frei", FormFixtures.deFrei) == Expected.deFrei)
        },
        test("Hungarian noun") {
          assertTrue(laidOut(WordLanguage.Hu, PartOfSpeech.Noun, "ház", FormFixtures.huHaz) == Expected.huHaz)
        },
        test("Hungarian verb") {
          assertTrue(laidOut(WordLanguage.Hu, PartOfSpeech.Verb, "ad", FormFixtures.huAd) == Expected.huAd)
        },
        test("Spanish verb") {
          assertTrue(
            laidOut(WordLanguage.Es, PartOfSpeech.Verb, "comprar", FormFixtures.esComprar) == Expected.esComprar
          )
        },
        test("Spanish noun") {
          assertTrue(
            laidOut(WordLanguage.Es, PartOfSpeech.Noun, "casa", FormFixtures.esCasa) == Expected.esCasa,
            laidOut(WordLanguage.Es, PartOfSpeech.Noun, "niño", FormFixtures.esNino) == Expected.esNino,
          )
        },
        test("Spanish adjective") {
          assertTrue(
            laidOut(WordLanguage.Es, PartOfSpeech.Adjective, "bueno", FormFixtures.esBueno) == Expected.esBueno,
            laidOut(WordLanguage.Es, PartOfSpeech.Adjective, "libre", FormFixtures.esLibre) == Expected.esLibre,
          )
        },
        test("English noun, verb and adjective") {
          assertTrue(
            laidOut(WordLanguage.En, PartOfSpeech.Verb, "go", FormFixtures.enGo) == Expected.enGo,
            laidOut(WordLanguage.En, PartOfSpeech.Verb, "be", FormFixtures.enBe) == Expected.enBe,
            laidOut(WordLanguage.En, PartOfSpeech.Noun, "child", FormFixtures.enChild) == Expected.enChild,
            laidOut(WordLanguage.En, PartOfSpeech.Adjective, "good", FormFixtures.enGood) == Expected.enGood,
          )
        },
        test("French verb, adjective and noun") {
          assertTrue(
            laidOut(WordLanguage.Fr, PartOfSpeech.Verb, "parler", FormFixtures.frParler) == Expected.frParler,
            laidOut(WordLanguage.Fr, PartOfSpeech.Adjective, "grand", FormFixtures.frGrand) == Expected.frGrand,
            laidOut(WordLanguage.Fr, PartOfSpeech.Adjective, "beau", FormFixtures.frBeau) == Expected.frBeau,
            laidOut(WordLanguage.Fr, PartOfSpeech.Noun, "chat", FormFixtures.frChat) == Expected.frChat,
          )
        },
        test("Portuguese verb, adjective and noun") {
          assertTrue(
            laidOut(WordLanguage.Pt, PartOfSpeech.Verb, "falar", FormFixtures.ptFalar) == Expected.ptFalar,
            laidOut(WordLanguage.Pt, PartOfSpeech.Adjective, "bom", FormFixtures.ptBom) == Expected.ptBom,
            laidOut(WordLanguage.Pt, PartOfSpeech.Adjective, "feliz", FormFixtures.ptFeliz) == Expected.ptFeliz,
            laidOut(WordLanguage.Pt, PartOfSpeech.Noun, "menino", FormFixtures.ptMenino) == Expected.ptMenino,
          )
        },
        test("an adverb, a phrase or an other word has no template, so it keeps the list") {
          assertTrue(
            WordLanguage.values.toList.forall(language => {
              List(PartOfSpeech.Adverb, PartOfSpeech.Phrase, PartOfSpeech.Other).forall(
                FormTemplates.of(language, _).isEmpty
              )
            })
          )
        },
      ),
    )
  }

  object Expected {

    val deKunstler: String = {
      """##  / ui.word.forms.table.declension
         |cols: singular | plural
         |nominative: der Künstler | die Künstler
         |genitive: des Künstlers | der Künstler
         |dative: dem Künstler | den Künstlern
         |accusative: den Künstler | die Künstler
         |## rest:""".stripMargin
    }

    val deHaus: String = {
      """##  / ui.word.forms.table.declension
         |cols: singular | plural
         |nominative: das Haus | die Häuser
         |genitive: des Hauses | der Häuser
         |dative: dem Haus / dem Hause | den Häusern
         |accusative: das Haus | die Häuser
         |## rest:""".stripMargin
    }

    val deBeamte: String = {
      """##  / strong
         |cols: singular | plural
         |nominative: Beamter | -
         |genitive: Beamten | -
         |##  / weak
         |cols: singular | plural
         |nominative: der Beamte | -
         |dative: - | den Beamten
         |##  / mixed
         |cols: singular | plural
         |nominative: Beamter | -
         |## rest:""".stripMargin
    }

    val deKaufen: String = {
      """##  / present
         |cols: indicative present | subjunctive-i
         |first-person singular: kaufe | kaufe
         |second-person singular: kaufst | kaufest
         |third-person singular: kauft | kaufe
         |first-person plural: kaufen | kaufen
         |second-person plural: kauft | kaufet
         |third-person plural: kaufen | kaufen
         |##  / preterite
         |cols: indicative preterite | subjunctive-ii
         |first-person singular: kaufte | kaufte
         |second-person singular: kauftest | kauftest
         |third-person singular: kaufte | kaufte
         |first-person plural: kauften | kauften
         |second-person plural: kauftet | kauftet
         |third-person plural: kauften | kauften
         |##  / perfect
         |cols: indicative perfect | subjunctive perfect
         |first-person singular: habe gekauft | habe gekauft
         |second-person singular: hast gekauft | habest gekauft
         |third-person singular: hat gekauft | habe gekauft
         |first-person plural: haben gekauft | haben gekauft
         |second-person plural: habt gekauft | habet gekauft
         |third-person plural: haben gekauft | haben gekauft
         |##  / pluperfect
         |cols: indicative pluperfect | subjunctive pluperfect
         |first-person singular: hatte gekauft | hätte gekauft
         |second-person singular: hattest gekauft | hättest gekauft
         |third-person singular: hatte gekauft | hätte gekauft
         |first-person plural: hatten gekauft | hätten gekauft
         |second-person plural: hattet gekauft | hättet gekauft
         |third-person plural: hatten gekauft | hätten gekauft
         |##  / future-i
         |cols: indicative future-i | subjunctive-i future-i | subjunctive-ii future-i
         |first-person singular: werde kaufen | werde kaufen | würde kaufen
         |second-person singular: wirst kaufen | werdest kaufen | würdest kaufen
         |third-person singular: wird kaufen | werde kaufen | würde kaufen
         |first-person plural: werden kaufen | werden kaufen | würden kaufen
         |second-person plural: werdet kaufen | werdet kaufen | würdet kaufen
         |third-person plural: werden kaufen | werden kaufen | würden kaufen
         |##  / future-ii
         |cols: indicative future-ii | subjunctive-i future-ii | subjunctive-ii future-ii
         |first-person singular: werde gekauft haben | werde gekauft haben | würde gekauft haben
         |second-person singular: wirst gekauft haben | werdest gekauft haben | würdest gekauft haben
         |third-person singular: wird gekauft haben | werde gekauft haben | würde gekauft haben
         |first-person plural: werden gekauft haben | werden gekauft haben | würden gekauft haben
         |second-person plural: werdet gekauft haben | werdet gekauft haben | würdet gekauft haben
         |third-person plural: werden gekauft haben | werden gekauft haben | würden gekauft haben
         |##  / imperative
         |cols:
         |singular: kauf / kaufe
         |plural: kauft
         |##  / ui.word.forms.table.nonFinite
         |cols:
         |infinitive: kaufen
         |participle present: kaufend
         |participle past: gekauft
         |future-i infinitive: kaufen werden
         |future-ii infinitive: gekauft haben werden
         |## rest:""".stripMargin
    }

    val deEinkaufen: String = {
      """##  / present
         |cols: indicative present | subjunctive-i
         |first-person singular: kaufe ein | kaufe ein
         |second-person singular: kaufst ein | kaufest ein
         |third-person singular: kauft ein | kaufe ein
         |first-person plural: kaufen ein | kaufen ein
         |second-person plural: kauft ein | kaufet ein
         |third-person plural: kaufen ein | kaufen ein
         |##  / preterite
         |cols: indicative preterite | subjunctive-ii
         |first-person singular: kaufte ein | kaufte ein
         |second-person singular: kauftest ein | kauftest ein
         |third-person singular: kaufte ein | kaufte ein
         |first-person plural: kauften ein | kauften ein
         |second-person plural: kauftet ein | kauftet ein
         |third-person plural: kauften ein | kauften ein
         |##  / perfect
         |cols: indicative perfect | subjunctive perfect
         |first-person singular: habe eingekauft | habe eingekauft
         |second-person singular: hast eingekauft | habest eingekauft
         |third-person singular: hat eingekauft | habe eingekauft
         |first-person plural: haben eingekauft | haben eingekauft
         |second-person plural: habt eingekauft | habet eingekauft
         |third-person plural: haben eingekauft | haben eingekauft
         |##  / pluperfect
         |cols: indicative pluperfect | subjunctive pluperfect
         |first-person singular: hatte eingekauft | hätte eingekauft
         |second-person singular: hattest eingekauft | hättest eingekauft
         |third-person singular: hatte eingekauft | hätte eingekauft
         |first-person plural: hatten eingekauft | hätten eingekauft
         |second-person plural: hattet eingekauft | hättet eingekauft
         |third-person plural: hatten eingekauft | hätten eingekauft
         |##  / future-i
         |cols: indicative future-i | subjunctive-i future-i | subjunctive-ii future-i
         |first-person singular: werde einkaufen | werde einkaufen | würde einkaufen
         |second-person singular: wirst einkaufen | werdest einkaufen | würdest einkaufen
         |third-person singular: wird einkaufen | werde einkaufen | würde einkaufen
         |first-person plural: werden einkaufen | werden einkaufen | würden einkaufen
         |second-person plural: werdet einkaufen | werdet einkaufen | würdet einkaufen
         |third-person plural: werden einkaufen | werden einkaufen | würden einkaufen
         |##  / future-ii
         |cols: indicative future-ii | subjunctive-i future-ii | subjunctive-ii future-ii
         |first-person singular: werde eingekauft haben | werde eingekauft haben | würde eingekauft haben
         |second-person singular: wirst eingekauft haben | werdest eingekauft haben | würdest eingekauft haben
         |third-person singular: wird eingekauft haben | werde eingekauft haben | würde eingekauft haben
         |first-person plural: werden eingekauft haben | werden eingekauft haben | würden eingekauft haben
         |second-person plural: werdet eingekauft haben | werdet eingekauft haben | würdet eingekauft haben
         |third-person plural: werden eingekauft haben | werden eingekauft haben | würden eingekauft haben
         |##  / imperative
         |cols:
         |singular: kauf ein / kaufe ein
         |plural: kauft ein
         |##  / ui.word.forms.table.nonFinite
         |cols:
         |infinitive: einkaufen
         |infinitive-zu: einzukaufen
         |participle present: einkaufend
         |participle past: eingekauft
         |future-i infinitive: einkaufen werden
         |future-ii infinitive: eingekauft haben werden
         |## subordinate-clause / subordinate-clause
         |cols: present | preterite | subjunctive-i
         |first-person singular: einkaufe | einkaufte | einkaufe
         |second-person singular: einkaufst | einkauftest | einkaufest
         |third-person singular: einkauft | einkaufte | einkaufe
         |first-person plural: einkaufen | einkauften | einkaufen
         |second-person plural: einkauft | einkauftet | einkaufet
         |third-person plural: einkaufen | einkauften | einkaufen
         |## rest:""".stripMargin
    }

    val deFrei: String = {
      """## positive / strong
         |cols: masculine singular | feminine singular | neuter singular | plural
         |nominative: freier | freie | freies | freie
         |genitive: freien | freier | freien | freier
         |dative: freiem | freier | freiem | freien
         |accusative: freien | freie | freies | freie
         |## positive / weak
         |cols: masculine singular | feminine singular | neuter singular | plural
         |nominative: der freie | die freie | das freie | die freien
         |genitive: des freien | der freien | des freien | der freien
         |dative: dem freien | der freien | dem freien | den freien
         |accusative: den freien | die freie | das freie | die freien
         |## positive / mixed
         |cols: masculine singular | feminine singular | neuter singular | plural
         |nominative: ein freier | eine freie | ein freies | keine freien
         |genitive: eines freien | einer freien | eines freien | keiner freien
         |dative: einem freien | einer freien | einem freien | keinen freien
         |accusative: einen freien | eine freie | ein freies | keine freien
         |## positive / predicative
         |cols:
         |: frei
         |## comparative / strong
         |cols: masculine singular | feminine singular | neuter singular | plural
         |nominative: freierer | freiere | freieres | freiere
         |genitive: freieren | freierer | freieren | freierer
         |dative: freierem | freierer | freierem | freieren
         |accusative: freieren | freiere | freieres | freiere
         |## comparative / weak
         |cols: masculine singular | feminine singular | neuter singular | plural
         |nominative: der freiere | die freiere | das freiere | die freieren
         |genitive: des freieren | der freieren | des freieren | der freieren
         |dative: dem freieren | der freieren | dem freieren | den freieren
         |accusative: den freieren | die freiere | das freiere | die freieren
         |## comparative / mixed
         |cols: masculine singular | feminine singular | neuter singular | plural
         |nominative: ein freierer | eine freiere | ein freieres | keine freieren
         |genitive: eines freieren | einer freieren | eines freieren | keiner freieren
         |dative: einem freieren | einer freieren | einem freieren | keinen freieren
         |accusative: einen freieren | eine freiere | ein freieres | keine freieren
         |## comparative / predicative
         |cols:
         |: freier
         |## superlative / strong
         |cols: masculine singular | feminine singular | neuter singular | plural
         |nominative: freiester / freister | freieste / freiste | freiestes / freistes | freieste / freiste
         |genitive: freiesten / freisten | freiester / freister | freiesten / freisten | freiester / freister
         |dative: freiestem / freistem | freiester / freister | freiestem / freistem | freiesten / freisten
         |accusative: freiesten / freisten | freieste / freiste | freiestes / freistes | freieste / freiste
         |## superlative / weak
         |cols: masculine singular | feminine singular | neuter singular | plural
         |nominative: der freieste / der freiste | die freieste / die freiste | das freieste / das freiste | die freiesten / die freisten
         |genitive: des freiesten / des freisten | der freiesten / der freisten | des freiesten / des freisten | der freiesten / der freisten
         |dative: dem freiesten / dem freisten | der freiesten / der freisten | dem freiesten / dem freisten | den freiesten / den freisten
         |accusative: den freiesten / den freisten | die freieste / die freiste | das freieste / das freiste | die freiesten / die freisten
         |## superlative / mixed
         |cols: masculine singular | feminine singular | neuter singular | plural
         |nominative: ein freiester / ein freister | eine freieste / eine freiste | ein freiestes / ein freistes | keine freiesten / keine freisten
         |genitive: eines freiesten / eines freisten | einer freiesten / einer freisten | eines freiesten / eines freisten | keiner freiesten / keiner freisten
         |dative: einem freiesten / einem freisten | einer freiesten / einer freisten | einem freiesten / einem freisten | keinen freiesten / keinen freisten
         |accusative: einen freiesten / einen freisten | eine freieste / eine freiste | ein freiestes / ein freistes | keine freiesten / keine freisten
         |## rest: am freiesten (superlative), am freisten (superlative)""".stripMargin
    }

    val huHaz: String = {
      """##  / ui.word.forms.table.declension
         |cols: singular | plural
         |nominative: ház | házak
         |accusative: házat | házakat
         |dative: háznak | házaknak
         |instrumental: házzal | házakkal
         |causal-final: házért | házakért
         |translative: házzá | házakká
         |terminative: házig | házakig
         |essive-formal: házként | házakként
         |inessive: házban | házakban
         |superessive: házon | házakon
         |adessive: háznál | házaknál
         |illative: házba | házakba
         |sublative: házra | házakra
         |allative: házhoz | házakhoz
         |elative: házból | házakból
         |delative: házról | házakról
         |ablative: háztól | házaktól
         |-é: házé | házaké
         |-éi: házéi | házakéi
         |##  / possessive
         |cols: possessed-single | possessed-many
         |first-person singular: házam | házaim
         |second-person singular: házad | házaid
         |third-person singular: háza | házai
         |first-person plural: házunk | házaink
         |second-person plural: házatok | házaitok
         |third-person plural: házuk | házaik
         |## rest:""".stripMargin
    }

    val huAd: String = {
      """##  / present indicative
         |cols: indefinite | definite
         |first-person singular: adok | adom
         |second-person singular: adsz | adod
         |third-person singular: ad | adja
         |first-person plural: adunk | adjuk
         |second-person plural: adtok | adjátok
         |third-person plural: adnak | adják
         |##  / past indicative
         |cols: indefinite | definite
         |first-person singular: adtam | adtam
         |second-person singular: adtál | adtad
         |third-person singular: adott | adta
         |first-person plural: adtunk | adtuk
         |second-person plural: adtatok | adtátok
         |third-person plural: adtak | adták
         |##  / conditional
         |cols: indefinite | definite
         |first-person singular: adnék | adnám
         |second-person singular: adnál | adnád
         |third-person singular: adna | adná
         |first-person plural: adnánk | adnánk / adnók
         |second-person plural: adnátok | adnátok
         |third-person plural: adnának | adnák
         |##  / subjunctive
         |cols: indefinite | definite
         |first-person singular: adjak | adjam
         |second-person singular: adj / adjál | add
         |third-person singular: adjon | adja
         |first-person plural: adjunk | adjuk
         |second-person plural: adjatok | adjátok
         |third-person plural: adjanak | adják
         |##  / first-person singular object-second-person
         |cols:
         |present indicative: adlak
         |past indicative: adtalak
         |conditional: adnálak
         |subjunctive: adjalak
         |##  / personal infinitive
         |cols:
         |first-person singular: adnom
         |second-person singular: adnod
         |third-person singular: adnia
         |first-person plural: adnunk
         |second-person plural: adnotok
         |third-person plural: adniuk
         |##  / ui.word.forms.table.nonFinite
         |cols:
         |infinitive: adni
         |participle present: adó
         |participle past: adott
         |participle future: adandó
         |participle adverbial: adva / adván
         |## potential / present indicative
         |cols: indefinite | definite
         |first-person singular: adhatok | adhatom
         |second-person singular: adhatsz | adhatod
         |third-person singular: adhat | adhatja
         |first-person plural: adhatunk | adhatjuk
         |second-person plural: adhattok | adhatjátok
         |third-person plural: adhatnak | adhatják
         |## potential / past indicative
         |cols: indefinite | definite
         |first-person singular: adhattam | adhattam
         |second-person singular: adhattál | adhattad
         |third-person singular: adhatott | adhatta
         |first-person plural: adhattunk | adhattuk
         |second-person plural: adhattatok | adhattátok
         |third-person plural: adhattak | adhatták
         |## potential / conditional
         |cols: indefinite | definite
         |first-person singular: adhatnék | adhatnám
         |second-person singular: adhatnál | adhatnád
         |third-person singular: adhatna | adhatná
         |first-person plural: adhatnánk | adhatnánk / adhatnók
         |second-person plural: adhatnátok | adhatnátok
         |third-person plural: adhatnának | adhatnák
         |## potential / subjunctive
         |cols: indefinite | definite
         |first-person singular: adhassak | adhassam
         |second-person singular: adhass / adhassál | adhasd / adhassad
         |third-person singular: adhasson | adhassa
         |first-person plural: adhassunk | adhassuk
         |second-person plural: adhassatok | adhassátok
         |third-person plural: adhassanak | adhassák
         |## potential / first-person singular object-second-person
         |cols:
         |present indicative: adhatlak
         |past indicative: adhattalak
         |conditional: adhatnálak
         |subjunctive: adhassalak
         |## potential / personal infinitive
         |cols:
         |first-person singular: adhatnom
         |second-person singular: adhatnod
         |third-person singular: adhatnia
         |first-person plural: adhatnunk
         |second-person plural: adhatnotok
         |third-person plural: adhatniuk
         |## potential / ui.word.forms.table.nonFinite
         |cols:
         |infinitive: adhatni
         |## rest: adat (causative), adat (causative,transitive), adás (noun-from-verb), adatik (intransitive,passive)""".stripMargin
    }

    val esComprar: String = {
      """##  / indicative
         |cols: present | imperfect | preterite | future | conditional
         |first-person singular: compro | compraba | compré | compraré | compraría
         |second-person singular: compras | comprabas | compraste | comprarás | comprarías
         |vos: comprás | - | - | - | -
         |third-person singular: compra | compraba | compró | comprará | compraría
         |first-person plural: compramos | comprábamos | compramos | compraremos | compraríamos
         |second-person plural: compráis | comprabais | comprasteis | compraréis | compraríais
         |third-person plural: compran | compraban | compraron | comprarán | comprarían
         |##  / subjunctive
         |cols: present | imperfect | future
         |first-person singular: compre | comprara / comprase | comprare
         |second-person singular: compres | compraras / comprases | comprares
         |vos: comprés | - | -
         |third-person singular: compre | comprara / comprase | comprare
         |first-person plural: compremos | compráramos / comprásemos | compráremos
         |second-person plural: compréis | comprarais / compraseis | comprareis
         |third-person plural: compren | compraran / comprasen | compraren
         |##  / imperative
         |cols: affirmative | negative
         |second-person singular: compra | compres
         |vos: comprá | -
         |third-person singular: compre / cómprese | compre
         |first-person plural: compremos / comprémonos | compremos
         |second-person plural: comprad / compraos | compréis
         |third-person plural: compren / cómprense | compren
         |##  / ui.word.forms.table.nonFinite
         |cols:
         |infinitive: comprar / comprarse
         |gerund: comprando / comprándose
         |participle past masculine singular: comprado
         |participle past feminine singular: comprada
         |participle past masculine plural: comprados
         |participle past feminine plural: compradas
         |## reflexive / indicative
         |cols: present | imperfect | preterite | future | conditional
         |first-person singular: me compro | me compraba | me compré | me compraré | me compraría
         |second-person singular: te compras | te comprabas | te compraste | te comprarás | te comprarías
         |vos: te comprás | - | - | - | -
         |third-person singular: se compra | se compraba | se compró | se comprará | se compraría
         |first-person plural: nos compramos | nos comprábamos | nos compramos | nos compraremos | nos compraríamos
         |second-person plural: os compráis | os comprabais | os comprasteis | os compraréis | os compraríais
         |third-person plural: se compran | se compraban | se compraron | se comprarán | se comprarían
         |## reflexive / subjunctive
         |cols: present | imperfect | future
         |first-person singular: me compre | me comprara / me comprase | me comprare
         |second-person singular: te compres | te compraras / te comprases | te comprares
         |vos: te comprés | - | -
         |third-person singular: se compre | se comprara / se comprase | se comprare
         |first-person plural: nos compremos | nos compráramos / nos comprásemos | nos compráremos
         |second-person plural: os compréis | os comprarais / os compraseis | os comprareis
         |third-person plural: se compren | se compraran / se comprasen | se compraren
         |## reflexive / imperative
         |cols: affirmative | negative
         |second-person singular: - | te compres
         |third-person singular: cómprese | se compre
         |first-person plural: - | nos compremos
         |second-person plural: - | os compréis
         |third-person plural: cómprense | se compren
         |## reflexive / ui.word.forms.table.nonFinite
         |cols:
         |infinitive: comprarse
         |gerund: comprándose
         |## combined-form / combined-form
         |cols: me | te | lo | la | le | nos | os | los | las | les
         |infinitive: comprarme | comprarte | comprarlo | comprarla | comprarle | comprarnos | compraros | comprarlos | comprarlas | comprarles
         |gerund: comprándome | comprándote | comprándolo | comprándola | comprándole | comprándonos | comprándoos | comprándolos | comprándolas | comprándoles
         |second-person singular: cómprame | cómprate | cómpralo | cómprala | cómprale | cómpranos | - | cómpralos | cómpralas | cómprales
         |vos: comprame | comprate | compralo | comprala | comprale | compranos | - | - | - | comprales
         |third-person singular: cómpreme | - | cómprelo | cómprela | cómprele | cómprenos | - | cómprelos | cómprelas | cómpreles
         |first-person plural: - | comprémoste | comprémoslo | comprémosla | comprémosle | comprémonos | comprémoos | comprémoslos | comprémoslas | comprémosles
         |second-person plural: compradme | - | compradlo | compradla | compradle | compradnos | compraos | compradlos | compradlas | compradles
         |third-person plural: cómprenme | - | cómprenlo | cómprenla | cómprenle | cómprennos | - | cómprenlos | cómprenlas | cómprenles
         |## rest: compralas (accusative,combined-form,imperative,informal,object-plural,object-third-person,second-person,singular,with-vos), compralos (accusative,combined-form,imperative,informal,object-plural,object-third-person,second-person,singular,with-vos)""".stripMargin
    }

    val esCasa: String = {
      """##  / ui.word.forms.table.declension
         |cols: singular | plural
         |: [casa] | casas
         |## rest:""".stripMargin
    }

    val esNino: String = {
      """##  / ui.word.forms.table.declension
         |cols: singular | plural
         |: [niño] | niños
         |## rest: niñas (feminine,plural)""".stripMargin
    }

    val esBueno: String = {
      """##  / ui.word.forms.table.declension
         |cols: singular | plural
         |masculine: [bueno] / before a noun buen | buenos
         |feminine: buena | buenas
         |##  / ui.word.forms.table.comparison
         |cols:
         |comparative: mejor / más bueno
         |superlative: buenísimo / óptimo
         |## rest: bonísimo (dated,formal,superlative)""".stripMargin
    }

    val esLibre: String = {
      """##  / ui.word.forms.table.declension
         |cols: singular | plural
         |masculine: [libre] | libres
         |feminine: [libre] | libres
         |##  / ui.word.forms.table.comparison
         |cols:
         |superlative: libérrimo
         |## rest:""".stripMargin
    }

    val enGo: String = {
      """##  / ui.word.forms.table.principalParts
       |cols:
       |infinitive: [go]
       |present third-person singular: goes
       |past: went
       |participle present: going
       |participle past: gone
       |## rest: geaux (defective,humorous,informal,louisiana,mainly), gwin (dialectal,informal,participle,present)""".stripMargin
    }

    val enBe: String = {
      """##  / ui.word.forms.table.principalParts
       |cols:
       |infinitive: be
       |present third-person singular: 's / is
       |participle present: being
       |participle past: been
       |## rest: 'm (first-person,present,singular), 're (plural,present), 're (present,second-person,singular), 'rt (present,second-person,singular), am (first-person,indicative,present,singular), am (first-person,present,singular), are (east,midlands,present,yorkshire), are (first-person,plural,present), are (plural,present), are (plural,present,second-person), are (plural,present,third-person), are (present,second-person,singular), art (present,second-person,singular), bes (dialectal,indicative,present,singular,third-person), iz (indicative,present,singular,third-person), was (dialectal,past,plural), was (first-person,indicative,past,singular), was (first-person,past,singular), was (indicative,past,singular,third-person), was (past,singular,third-person), were (dialectal,first-person,past,singular), were (dialectal,past,singular,third-person), were (first-person,indicative,ireland,multicultural-london-english,northern-england,past,singular,third-person), were (indicative,past,plural), were (indicative,past,second-person,singular), were (past,plural), were (past,second-person,singular), were (past,subjunctive)""".stripMargin
    }

    val enChild: String = {
      """##  / ui.word.forms.table.declension
       |cols: singular | plural
       |: [child] | children
       |## rest: childer (ireland,plural)""".stripMargin
    }

    val enGood: String = {
      """##  / ui.word.forms.table.comparison
       |cols:
       |positive: [good]
       |comparative: better
       |superlative: best
       |## rest:""".stripMargin
    }

    val frParler: String = {
      """##  / indicative
       |cols: present | imperfect | historic past | future | conditional
       |first-person singular: parle / paʁl | parlais | parlai | parlerai | parlerais
       |second-person singular: parles | parlais | parlas | parleras | parlerais
       |third-person singular: parle | parlait | parla | parlera | parlerait
       |first-person plural: parlons | parlions | parlâmes | parlerons | parlerions
       |second-person plural: parlez | parliez | parlâtes | parlerez | parleriez
       |third-person plural: parlent | parlaient | parlèrent | parleront | parleraient
       |##  / subjunctive
       |cols: present | imperfect
       |first-person singular: parle / paʁl | parlasse
       |second-person singular: parles | parlasses
       |third-person singular: parle | parlât
       |first-person plural: parlions | parlassions
       |second-person plural: parliez | parlassiez
       |third-person plural: parlent | parlassent
       |##  / imperative
       |cols:
       |second-person singular: parle
       |first-person plural: parlons
       |second-person plural: parlez
       |##  / ui.word.forms.table.nonFinite
       |cols:
       |infinitive: parler
       |participle present: parlant
       |participle past: parlé
       |## rest:""".stripMargin
    }

    val frGrand: String = {
      """##  / ui.word.forms.table.declension
       |cols: singular | plural
       |masculine: [grand] | grands
       |feminine: grande | grandes
       |## rest:""".stripMargin
    }

    val frBeau: String = {
      """##  / ui.word.forms.table.declension
       |cols: singular | plural
       |masculine: [beau] / bel | beaux
       |feminine: belle | belles
       |## rest:""".stripMargin
    }

    val frChat: String = {
      """##  / ui.word.forms.table.declension
       |cols: singular | plural
       |: [chat] | chats
       |## rest:""".stripMargin
    }

    val ptFalar: String = {
      """##  / indicative
       |cols: present | preterite | imperfect | pluperfect | future | conditional
       |first-person singular: falo | falei | falava | falara | falarei | falaria
       |second-person singular: falas | falaste | falavas | falaras | falarás | falarias
       |third-person singular: fala | falou | falava | falara | falará | falaria
       |first-person plural: falamos | falamos / falámos | falávamos | faláramos | falaremos | falaríamos
       |second-person plural: falais | falastes | faláveis | faláreis | falareis | falaríeis
       |third-person plural: falam | falaram | falavam | falaram | falarão | falariam
       |##  / subjunctive
       |cols: present | imperfect | future
       |first-person singular: fale | falasse | falar
       |second-person singular: fales | falasses | falares
       |third-person singular: fale | falasse | falar
       |first-person plural: falemos | falássemos | falarmos
       |second-person plural: faleis | falásseis | falardes
       |third-person plural: falem | falassem | falarem
       |##  / imperative
       |cols: affirmative | negative
       |second-person singular: fala | não fales
       |third-person singular: fale | não fale
       |first-person plural: falemos | não falemos
       |second-person plural: falai | não faleis
       |third-person plural: falem | não falem
       |##  / personal infinitive
       |cols:
       |first-person singular: falar
       |second-person singular: falares
       |third-person singular: falar
       |first-person plural: falarmos
       |second-person plural: falardes
       |third-person plural: falarem
       |##  / ui.word.forms.table.nonFinite
       |cols:
       |infinitive: falar
       |gerund: falando
       |participle past masculine singular: falado
       |participle past feminine singular: falada
       |participle past masculine plural: falados
       |participle past feminine plural: faladas
       |## rest:""".stripMargin
    }

    val ptBom: String = {
      """##  / ui.word.forms.table.declension
       |cols: singular | plural
       |masculine: [bom] | bons
       |feminine: boa | boas
       |##  / ui.word.forms.table.comparison
       |cols:
       |comparative: melhor
       |superlative: boníssimo / o melhor / ótimo
       |## rest:""".stripMargin
    }

    val ptFeliz: String = {
      """##  / ui.word.forms.table.declension
       |cols: singular | plural
       |masculine: [feliz] | felizes
       |feminine: [feliz] | felizes
       |##  / ui.word.forms.table.comparison
       |cols:
       |comparative: mais feliz
       |superlative: felicíssimo / o mais feliz
       |## rest:""".stripMargin
    }

    val ptMenino: String = {
      """##  / ui.word.forms.table.declension
       |cols: singular | plural
       |: [menino] | meninos
       |## rest: meninas (feminine,plural)""".stripMargin
    }
  }
}
