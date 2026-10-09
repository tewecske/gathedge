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
  def render(lemma: String, layout: FormLayout[Form], profile: LanguageProfile): String = {
    val tables = for {
      section <- layout.sections
      table   <- section.tables
    } yield {
      val heading = s"## ${label(section.title)} / ${label(table.title)}"
      val columns = table.columns.map(label).mkString(" | ")
      val rows    = table.rows.map(row => {
        val cells = row.cells.map(cell => {
          val texts = ((if (cell.lemma) List(s"[$lemma]") else Nil) ++ cell.forms.map(_.text))
            .map(text => cell.prefix.fold(text)(prefix => profile.lead(prefix, text.stripPrefix("["), lemma) + text))
          if (texts.isEmpty) "-" else texts.mkString(" / ")
        })
        s"${label(row.label)}: ${cells.mkString(" | ")}"
      })
      (heading :: s"cols: $columns" :: rows).mkString("\n")
    }
    val rest   = layout.rest.map(form => s"${form.text} (${form.relation})").mkString(", ")
    (tables :+ s"## rest: $rest").mkString("\n").linesIterator.map(_.stripTrailing).mkString("\n")
  }

  /** As the word page lays it out: the articles and pronouns come from the language, the articles also from `gender`.
    */
  def laidOut(
    language: WordLanguage,
    pos: PartOfSpeech,
    lemma: String,
    fixture: String,
    gender: Option[Gender] = None,
  ): String = {
    val template = FormTemplates.of(language, pos).getOrElse(throw new IllegalArgumentException("no template"))
    val profile  = LanguageProfile.of(language)
    val prefixes = (kind: FormPrefix, tags: Set[String]) => profile.prefix(kind, gender, tags, lemma)
    render(lemma, FormTable.layout(template, forms(lemma, fixture), prefixes)(_.relation, _.id), profile)
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
            laidOut(
              WordLanguage.Es,
              PartOfSpeech.Noun,
              "casa",
              FormFixtures.esCasa,
              Some(Gender.Feminine),
            ) == Expected.esCasa,
            laidOut(
              WordLanguage.Es,
              PartOfSpeech.Noun,
              "niño",
              FormFixtures.esNino,
              Some(Gender.Masculine),
            ) == Expected.esNino,
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
            laidOut(
              WordLanguage.Fr,
              PartOfSpeech.Noun,
              "chat",
              FormFixtures.frChat,
              Some(Gender.Masculine),
            ) == Expected.frChat,
          )
        },
        test("Portuguese verb, adjective and noun") {
          assertTrue(
            laidOut(WordLanguage.Pt, PartOfSpeech.Verb, "falar", FormFixtures.ptFalar) == Expected.ptFalar,
            laidOut(WordLanguage.Pt, PartOfSpeech.Adjective, "bom", FormFixtures.ptBom) == Expected.ptBom,
            laidOut(WordLanguage.Pt, PartOfSpeech.Adjective, "feliz", FormFixtures.ptFeliz) == Expected.ptFeliz,
            laidOut(
              WordLanguage.Pt,
              PartOfSpeech.Noun,
              "menino",
              FormFixtures.ptMenino,
              Some(Gender.Masculine),
            ) == Expected.ptMenino,
          )
        },
        test("a Romance noun's article: `el agua` but `las aguas`, `l'homme`, but `le héros` before an aspirated h") {
          val noun = (language: WordLanguage, lemma: String, plural: String, gender: Gender) => {
            laidOut(language, PartOfSpeech.Noun, lemma, s"$plural;noun;;plural", Some(gender)).linesIterator.toList(2)
          }
          assertTrue(
            noun(WordLanguage.Es, "agua", "aguas", Gender.Feminine) == ": el [agua] | las aguas",
            noun(WordLanguage.Es, "abeja", "abejas", Gender.Feminine) == ": la [abeja] | las abejas",
            noun(WordLanguage.Fr, "homme", "hommes", Gender.Masculine) == ": l'[homme] | les hommes",
            noun(WordLanguage.Fr, "héros", "héros", Gender.Masculine) == ": le [héros] | les héros",
            noun(WordLanguage.Fr, "haine", "haines", Gender.Feminine) == ": la [haine] | les haines",
            noun(WordLanguage.Pt, "água", "águas", Gender.Feminine) == ": a [água] | as águas",
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
         |first-person singular: ich kaufe | ich kaufe
         |second-person singular: du kaufst | du kaufest
         |third-person singular: er kauft | er kaufe
         |first-person plural: wir kaufen | wir kaufen
         |second-person plural: ihr kauft | ihr kaufet
         |third-person plural: sie kaufen | sie kaufen
         |##  / preterite
         |cols: indicative preterite | subjunctive-ii
         |first-person singular: ich kaufte | ich kaufte
         |second-person singular: du kauftest | du kauftest
         |third-person singular: er kaufte | er kaufte
         |first-person plural: wir kauften | wir kauften
         |second-person plural: ihr kauftet | ihr kauftet
         |third-person plural: sie kauften | sie kauften
         |##  / perfect
         |cols: indicative perfect | subjunctive perfect
         |first-person singular: ich habe gekauft | ich habe gekauft
         |second-person singular: du hast gekauft | du habest gekauft
         |third-person singular: er hat gekauft | er habe gekauft
         |first-person plural: wir haben gekauft | wir haben gekauft
         |second-person plural: ihr habt gekauft | ihr habet gekauft
         |third-person plural: sie haben gekauft | sie haben gekauft
         |##  / pluperfect
         |cols: indicative pluperfect | subjunctive pluperfect
         |first-person singular: ich hatte gekauft | ich hätte gekauft
         |second-person singular: du hattest gekauft | du hättest gekauft
         |third-person singular: er hatte gekauft | er hätte gekauft
         |first-person plural: wir hatten gekauft | wir hätten gekauft
         |second-person plural: ihr hattet gekauft | ihr hättet gekauft
         |third-person plural: sie hatten gekauft | sie hätten gekauft
         |##  / future-i
         |cols: indicative future-i | subjunctive-i future-i | subjunctive-ii future-i
         |first-person singular: ich werde kaufen | ich werde kaufen | ich würde kaufen
         |second-person singular: du wirst kaufen | du werdest kaufen | du würdest kaufen
         |third-person singular: er wird kaufen | er werde kaufen | er würde kaufen
         |first-person plural: wir werden kaufen | wir werden kaufen | wir würden kaufen
         |second-person plural: ihr werdet kaufen | ihr werdet kaufen | ihr würdet kaufen
         |third-person plural: sie werden kaufen | sie werden kaufen | sie würden kaufen
         |##  / future-ii
         |cols: indicative future-ii | subjunctive-i future-ii | subjunctive-ii future-ii
         |first-person singular: ich werde gekauft haben | ich werde gekauft haben | ich würde gekauft haben
         |second-person singular: du wirst gekauft haben | du werdest gekauft haben | du würdest gekauft haben
         |third-person singular: er wird gekauft haben | er werde gekauft haben | er würde gekauft haben
         |first-person plural: wir werden gekauft haben | wir werden gekauft haben | wir würden gekauft haben
         |second-person plural: ihr werdet gekauft haben | ihr werdet gekauft haben | ihr würdet gekauft haben
         |third-person plural: sie werden gekauft haben | sie werden gekauft haben | sie würden gekauft haben
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
         |first-person singular: ich kaufe ein | ich kaufe ein
         |second-person singular: du kaufst ein | du kaufest ein
         |third-person singular: er kauft ein | er kaufe ein
         |first-person plural: wir kaufen ein | wir kaufen ein
         |second-person plural: ihr kauft ein | ihr kaufet ein
         |third-person plural: sie kaufen ein | sie kaufen ein
         |##  / preterite
         |cols: indicative preterite | subjunctive-ii
         |first-person singular: ich kaufte ein | ich kaufte ein
         |second-person singular: du kauftest ein | du kauftest ein
         |third-person singular: er kaufte ein | er kaufte ein
         |first-person plural: wir kauften ein | wir kauften ein
         |second-person plural: ihr kauftet ein | ihr kauftet ein
         |third-person plural: sie kauften ein | sie kauften ein
         |##  / perfect
         |cols: indicative perfect | subjunctive perfect
         |first-person singular: ich habe eingekauft | ich habe eingekauft
         |second-person singular: du hast eingekauft | du habest eingekauft
         |third-person singular: er hat eingekauft | er habe eingekauft
         |first-person plural: wir haben eingekauft | wir haben eingekauft
         |second-person plural: ihr habt eingekauft | ihr habet eingekauft
         |third-person plural: sie haben eingekauft | sie haben eingekauft
         |##  / pluperfect
         |cols: indicative pluperfect | subjunctive pluperfect
         |first-person singular: ich hatte eingekauft | ich hätte eingekauft
         |second-person singular: du hattest eingekauft | du hättest eingekauft
         |third-person singular: er hatte eingekauft | er hätte eingekauft
         |first-person plural: wir hatten eingekauft | wir hätten eingekauft
         |second-person plural: ihr hattet eingekauft | ihr hättet eingekauft
         |third-person plural: sie hatten eingekauft | sie hätten eingekauft
         |##  / future-i
         |cols: indicative future-i | subjunctive-i future-i | subjunctive-ii future-i
         |first-person singular: ich werde einkaufen | ich werde einkaufen | ich würde einkaufen
         |second-person singular: du wirst einkaufen | du werdest einkaufen | du würdest einkaufen
         |third-person singular: er wird einkaufen | er werde einkaufen | er würde einkaufen
         |first-person plural: wir werden einkaufen | wir werden einkaufen | wir würden einkaufen
         |second-person plural: ihr werdet einkaufen | ihr werdet einkaufen | ihr würdet einkaufen
         |third-person plural: sie werden einkaufen | sie werden einkaufen | sie würden einkaufen
         |##  / future-ii
         |cols: indicative future-ii | subjunctive-i future-ii | subjunctive-ii future-ii
         |first-person singular: ich werde eingekauft haben | ich werde eingekauft haben | ich würde eingekauft haben
         |second-person singular: du wirst eingekauft haben | du werdest eingekauft haben | du würdest eingekauft haben
         |third-person singular: er wird eingekauft haben | er werde eingekauft haben | er würde eingekauft haben
         |first-person plural: wir werden eingekauft haben | wir werden eingekauft haben | wir würden eingekauft haben
         |second-person plural: ihr werdet eingekauft haben | ihr werdet eingekauft haben | ihr würdet eingekauft haben
         |third-person plural: sie werden eingekauft haben | sie werden eingekauft haben | sie würden eingekauft haben
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
         |cols: present | preterite | subjunctive-i | subjunctive-ii
         |first-person singular: dass ich einkaufe | dass ich einkaufte | dass ich einkaufe | dass ich einkaufte
         |second-person singular: dass du einkaufst | dass du einkauftest | dass du einkaufest | dass du einkauftest
         |third-person singular: dass er einkauft | dass er einkaufte | dass er einkaufe | dass er einkaufte
         |first-person plural: dass wir einkaufen | dass wir einkauften | dass wir einkaufen | dass wir einkauften
         |second-person plural: dass ihr einkauft | dass ihr einkauftet | dass ihr einkaufet | dass ihr einkauftet
         |third-person plural: dass sie einkaufen | dass sie einkauften | dass sie einkaufen | dass sie einkauften
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
         |first-person singular: én adok | én adom
         |second-person singular: te adsz | te adod
         |third-person singular: ő ad | ő adja
         |first-person plural: mi adunk | mi adjuk
         |second-person plural: ti adtok | ti adjátok
         |third-person plural: ők adnak | ők adják
         |##  / past indicative
         |cols: indefinite | definite
         |first-person singular: én adtam | én adtam
         |second-person singular: te adtál | te adtad
         |third-person singular: ő adott | ő adta
         |first-person plural: mi adtunk | mi adtuk
         |second-person plural: ti adtatok | ti adtátok
         |third-person plural: ők adtak | ők adták
         |##  / conditional
         |cols: indefinite | definite
         |first-person singular: én adnék | én adnám
         |second-person singular: te adnál | te adnád
         |third-person singular: ő adna | ő adná
         |first-person plural: mi adnánk | mi adnánk / mi adnók
         |second-person plural: ti adnátok | ti adnátok
         |third-person plural: ők adnának | ők adnák
         |##  / subjunctive
         |cols: indefinite | definite
         |first-person singular: én adjak | én adjam
         |second-person singular: te adj / te adjál | te add
         |third-person singular: ő adjon | ő adja
         |first-person plural: mi adjunk | mi adjuk
         |second-person plural: ti adjatok | ti adjátok
         |third-person plural: ők adjanak | ők adják
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
         |first-person singular: én adhatok | én adhatom
         |second-person singular: te adhatsz | te adhatod
         |third-person singular: ő adhat | ő adhatja
         |first-person plural: mi adhatunk | mi adhatjuk
         |second-person plural: ti adhattok | ti adhatjátok
         |third-person plural: ők adhatnak | ők adhatják
         |## potential / past indicative
         |cols: indefinite | definite
         |first-person singular: én adhattam | én adhattam
         |second-person singular: te adhattál | te adhattad
         |third-person singular: ő adhatott | ő adhatta
         |first-person plural: mi adhattunk | mi adhattuk
         |second-person plural: ti adhattatok | ti adhattátok
         |third-person plural: ők adhattak | ők adhatták
         |## potential / conditional
         |cols: indefinite | definite
         |first-person singular: én adhatnék | én adhatnám
         |second-person singular: te adhatnál | te adhatnád
         |third-person singular: ő adhatna | ő adhatná
         |first-person plural: mi adhatnánk | mi adhatnánk / mi adhatnók
         |second-person plural: ti adhatnátok | ti adhatnátok
         |third-person plural: ők adhatnának | ők adhatnák
         |## potential / subjunctive
         |cols: indefinite | definite
         |first-person singular: én adhassak | én adhassam
         |second-person singular: te adhass / te adhassál | te adhasd / te adhassad
         |third-person singular: ő adhasson | ő adhassa
         |first-person plural: mi adhassunk | mi adhassuk
         |second-person plural: ti adhassatok | ti adhassátok
         |third-person plural: ők adhassanak | ők adhassák
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
         |first-person singular: yo compro | yo compraba | yo compré | yo compraré | yo compraría
         |second-person singular: tú compras | tú comprabas | tú compraste | tú comprarás | tú comprarías
         |vos: vos comprás | - | - | - | -
         |third-person singular: él compra | él compraba | él compró | él comprará | él compraría
         |first-person plural: nosotros compramos | nosotros comprábamos | nosotros compramos | nosotros compraremos | nosotros compraríamos
         |second-person plural: vosotros compráis | vosotros comprabais | vosotros comprasteis | vosotros compraréis | vosotros compraríais
         |third-person plural: ellos compran | ellos compraban | ellos compraron | ellos comprarán | ellos comprarían
         |##  / subjunctive
         |cols: present | imperfect | future
         |first-person singular: yo compre | yo comprara / yo comprase | yo comprare
         |second-person singular: tú compres | tú compraras / tú comprases | tú comprares
         |vos: vos comprés | - | -
         |third-person singular: él compre | él comprara / él comprase | él comprare
         |first-person plural: nosotros compremos | nosotros compráramos / nosotros comprásemos | nosotros compráremos
         |second-person plural: vosotros compréis | vosotros comprarais / vosotros compraseis | vosotros comprareis
         |third-person plural: ellos compren | ellos compraran / ellos comprasen | ellos compraren
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
         |first-person singular: yo me compro | yo me compraba | yo me compré | yo me compraré | yo me compraría
         |second-person singular: tú te compras | tú te comprabas | tú te compraste | tú te comprarás | tú te comprarías
         |vos: vos te comprás | - | - | - | -
         |third-person singular: él se compra | él se compraba | él se compró | él se comprará | él se compraría
         |first-person plural: nosotros nos compramos | nosotros nos comprábamos | nosotros nos compramos | nosotros nos compraremos | nosotros nos compraríamos
         |second-person plural: vosotros os compráis | vosotros os comprabais | vosotros os comprasteis | vosotros os compraréis | vosotros os compraríais
         |third-person plural: ellos se compran | ellos se compraban | ellos se compraron | ellos se comprarán | ellos se comprarían
         |## reflexive / subjunctive
         |cols: present | imperfect | future
         |first-person singular: yo me compre | yo me comprara / yo me comprase | yo me comprare
         |second-person singular: tú te compres | tú te compraras / tú te comprases | tú te comprares
         |vos: vos te comprés | - | -
         |third-person singular: él se compre | él se comprara / él se comprase | él se comprare
         |first-person plural: nosotros nos compremos | nosotros nos compráramos / nosotros nos comprásemos | nosotros nos compráremos
         |second-person plural: vosotros os compréis | vosotros os comprarais / vosotros os compraseis | vosotros os comprareis
         |third-person plural: ellos se compren | ellos se compraran / ellos se comprasen | ellos se compraren
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
         |: la [casa] | las casas
         |## rest:""".stripMargin
    }

    val esNino: String = {
      """##  / ui.word.forms.table.declension
         |cols: singular | plural
         |: el [niño] | los niños
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
         |first-person singular: je parle / je paʁl | je parlais | je parlai | je parlerai | je parlerais
         |second-person singular: tu parles | tu parlais | tu parlas | tu parleras | tu parlerais
         |third-person singular: il parle | il parlait | il parla | il parlera | il parlerait
         |first-person plural: nous parlons | nous parlions | nous parlâmes | nous parlerons | nous parlerions
         |second-person plural: vous parlez | vous parliez | vous parlâtes | vous parlerez | vous parleriez
         |third-person plural: ils parlent | ils parlaient | ils parlèrent | ils parleront | ils parleraient
         |##  / subjunctive
         |cols: present | imperfect
         |first-person singular: je parle / je paʁl | je parlasse
         |second-person singular: tu parles | tu parlasses
         |third-person singular: il parle | il parlât
         |first-person plural: nous parlions | nous parlassions
         |second-person plural: vous parliez | vous parlassiez
         |third-person plural: ils parlent | ils parlassent
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
         |: le [chat] | les chats
         |## rest:""".stripMargin
    }

    val ptFalar: String = {
      """##  / indicative
         |cols: present | preterite | imperfect | pluperfect | future | conditional
         |first-person singular: eu falo | eu falei | eu falava | eu falara | eu falarei | eu falaria
         |second-person singular: tu falas | tu falaste | tu falavas | tu falaras | tu falarás | tu falarias
         |third-person singular: ele fala | ele falou | ele falava | ele falara | ele falará | ele falaria
         |first-person plural: nós falamos | nós falamos / nós falámos | nós falávamos | nós faláramos | nós falaremos | nós falaríamos
         |second-person plural: vós falais | vós falastes | vós faláveis | vós faláreis | vós falareis | vós falaríeis
         |third-person plural: eles falam | eles falaram | eles falavam | eles falaram | eles falarão | eles falariam
         |##  / subjunctive
         |cols: present | imperfect | future
         |first-person singular: eu fale | eu falasse | eu falar
         |second-person singular: tu fales | tu falasses | tu falares
         |third-person singular: ele fale | ele falasse | ele falar
         |first-person plural: nós falemos | nós falássemos | nós falarmos
         |second-person plural: vós faleis | vós falásseis | vós falardes
         |third-person plural: eles falem | eles falassem | eles falarem
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
         |: o [menino] | os meninos
         |## rest: meninas (feminine,plural)""".stripMargin
    }
  }
}
