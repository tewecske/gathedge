package gathedge.backend.tools

import gathedge.backend.tools.WiktextractParser.{ParsedAudio, ParsedForm, ParsedWord}
import gathedge.shared.domain.{Gender, PartOfSpeech, WordLanguage, WordLinkKind}
import zio.ZIO
import zio.json._
import zio.test._

import scala.io.Source
import java.nio.charset.StandardCharsets

/** The dictionary pipeline's parsing half, on real wiktextract lines.
  *
  * Nobody is going to re-run a 22.9 GB import to find out whether German gender was read correctly, which is why
  * everything here is pure: `WiktextractParser` and `DictionaryImport`'s selection and pivot are functions, and this
  * drives them over a handful of lines shaped exactly like the dump's.
  */
object DictionaryImportSpec extends ZIOSpecDefault {

  private val hausLine = {
    """{"word":"Haus","lang_code":"de","lang":"German","pos":"noun","tags":["neuter"],
      |"senses":[{"glosses":["house"]}],"forms":[{"form":"Häuser","tags":["plural"]}]}""".stripMargin.replace("\n", "")
  }

  private val houseLine = {
    """{"word":"house","lang_code":"en","lang":"English","pos":"noun",
      |"senses":[{"glosses":["a building for people to live in"]}],
      |"translations":[{"code":"de","lang":"German","word":"Haus","tags":["neuter"],"sense":"building"},
      |{"code":"hu","lang":"Hungarian","word":"ház","sense":"building"},
      |{"code":"it","lang":"Italian","word":"casa","sense":"building"}]}""".stripMargin.replace("\n", "")
  }

  private val plateLine = {
    """{"word":"Teller","lang_code":"de","lang":"German","pos":"noun","tags":["masculine"],
      |"senses":[{"glosses":["plate"]}]}""".stripMargin.replace("\n", "")
  }

  /** An inflected form with a page of its own. Importing these would bury the lemma in the search box. */
  private val inflectedLine = {
    """{"word":"Häuser","lang_code":"de","lang":"German","pos":"noun","tags":["neuter"],
      |"senses":[{"glosses":["plural of Haus"],"tags":["form-of","plural"]}]}""".stripMargin.replace("\n", "")
  }

  /** A `sounds[]` array as the dump writes it: IPA rows, two recordings (one without tags), the first file again under
    * a lower-case spelling, and the dump's own URLs, which the parser does not read.
    */
  private val soundsLine = {
    """{"word":"gratis","lang_code":"de","lang":"German","pos":"adv","senses":[{"glosses":["free of charge"]}],
      |"sounds":[{"ipa":"/ˈɡʁaːtɪs/"},
      |{"audio":"De-gratis.ogg","tags":["Germany","Berlin"],
      |"ogg_url":"https://upload.wikimedia.org/wikipedia/commons/8/88/De-gratis.ogg"},
      |{"audio":"LL-Q188 (deu)-Sebastian Wallroth-gratis.wav"},
      |{"audio":"de-gratis.ogg","tags":["Austria"]}]}""".stripMargin.replace("\n", "")
  }

  private val prefixLine = {
    """{"word":"un-","lang_code":"de","lang":"German","pos":"prefix","senses":[{"glosses":["un-"]}]}"""
  }

  /** One `forms[]` array exercising every filter `WiktextractParser.formsOf` applies: a real plural, the dump's own "no
    * such form" placeholder (`"-"`), a note left in a cell (`e.g. …`), two flavours of template scaffolding
    * (`table-tags`, `inflection-template`), a Hungarian-style stem-class label (`class`), a German conjugation table's
    * auxiliary-verb note (`auxiliary`), a form spelled identically to its own lemma (real linguistic fact, not noise --
    * excluded at store time instead, see `DictionaryImport.formEdges`), two flavours of wiktextract's own "could not
    * classify this cell" marker (`error-unrecognized-form`, `error-unknown-tag`), and four flavours of "not the
    * standard, current spelling" (`nonstandard`, `obsolete`, `alternative`, `archaic`) -- each row dropped whole, not
    * salvaged for its other tags.
    */
  private val formsLine = {
    """{"word":"Beispiel","lang_code":"de","lang":"German","pos":"noun","tags":["neuter"],
      |"senses":[{"glosses":["example"]}],
      |"forms":[
      |{"form":"Beispiele","tags":["plural"]},
      |{"form":"-","tags":["genitive"]},
      |{"form":"e.g. zum Beispiel","tags":["idiom"]},
      |{"form":"Beispiel","tags":["nominative","singular"]},
      |{"form":"strong","tags":["table-tags"],"source":"declension"},
      |{"form":"de-ndecl","tags":["inflection-template"],"source":"declension"},
      |{"form":"back harmony","tags":["class"]},
      |{"form":"haben","tags":["auxiliary"]},
      |{"form":"Beispielen","tags":["error-unrecognized-form","dative","plural"]},
      |{"form":"Beispielem","tags":["error-unknown-tag","dative"]},
      |{"form":"Beyspiel","tags":["nonstandard","nominative","singular"]},
      |{"form":"Bexempel","tags":["obsolete","nominative","singular"]},
      |{"form":"Beyspil","tags":["alternative","nominative","singular"]},
      |{"form":"Exempel","tags":["archaic","nominative","singular"]}
      |]}""".stripMargin.replace("\n", "")
  }

  /** A German verb's forms, cut down to one row of each kind the importer meets: a separable verb's main-clause cells
    * (two words), a subordinate-clause cell (one word), composed tenses (`multiword-construction`), the head line's
    * untabled summary, and three multi-word rows that are not forms — a predicative row, a canonical row and a note.
    */
  private val einkaufenLine = {
    """{"word":"einkaufen","lang_code":"de","lang":"German","pos":"verb","senses":[{"glosses":["to shop"]}],
      |"forms":[
      |{"form":"kauft ein","tags":["present","singular","third-person"]},
      |{"form":"kaufe ein","tags":["first-person","indicative","present","singular"],"source":"conjugation"},
      |{"form":"kaufest ein","tags":["second-person","singular","subjunctive","subjunctive-i"],"source":"conjugation"},
      |{"form":"kauf ein","tags":["imperative","second-person","singular"],"source":"conjugation"},
      |{"form":"einkaufe","tags":["first-person","indicative","present","singular","subordinate-clause"],
      |"source":"conjugation"},
      |{"form":"habe eingekauft","tags":["first-person","indicative","multiword-construction","perfect","singular"],
      |"source":"conjugation"},
      |{"form":"würde eingekauft haben","tags":["first-person","future","future-ii","multiword-construction",
      |"singular","subjunctive","subjunctive-ii"],"source":"conjugation"},
      |{"form":"er ist eingekauft","tags":["masculine","predicative","singular"],"source":"declension"},
      |{"form":"the einkaufen","tags":["canonical"]},
      |{"form":"definite forms are not used","tags":["definite","indicative","present"],"source":"conjugation"},
      |{"form":"one two three four five","tags":["present","plural"],"source":"conjugation"}
      |]}""".stripMargin.replace("\n", "")
  }

  /** Rows whose cell names two forms, and rows in two other languages that write a form in more than one word. */
  private val alternativesLine = {
    """{"word":"GUI","lang_code":"hu","lang":"Hungarian","pos":"noun","senses":[{"glosses":["GUI"]}],
      |"forms":[
      |{"form":"GUI-jaim (or GUI-im)","tags":["first-person","possessed-many","possessive","singular"],
      |"source":"declension"},
      |{"form":"GUI-k / GUI-ek","tags":["nominative","plural"],"source":"declension"},
      |{"form":"GUI-é","tags":["error-unrecognized-form","singular"],"source":"declension"},
      |{"form":"GUI-kéi","tags":["error-unrecognized-form","plural"],"source":"declension"}
      |]}""".stripMargin.replace("\n", "")
  }

  private val comprarLine = {
    """{"word":"comprar","lang_code":"es","lang":"Spanish","pos":"verb","senses":[{"glosses":["to buy"]}],
      |"forms":[
      |{"form":"compra","tags":["indicative","present","singular","third-person"],"source":"conjugation"},
      |{"form":"se compra","tags":["indicative","present","singular","third-person"],"source":"conjugation"},
      |{"form":"nos compramos","tags":["first-person","indicative","plural","present"],"source":"conjugation"}
      |]}""".stripMargin.replace("\n", "")
  }

  private val freeLine = {
    """{"word":"free","lang_code":"en","lang":"English","pos":"adj","senses":[{"glosses":["unconstrained"]}],
      |"forms":[{"form":"freer","tags":["comparative"]},{"form":"more free","tags":["comparative"]},
      |{"form":"most free","tags":["superlative"]}]}""".stripMargin.replace("\n", "")
  }

  /** Real dump lines for three Hungarian verbs, cut down to the fields the parser reads: `ad` (a regular transitive
    * verb), `megy` (irregular and intransitive: no definite forms) and `dolgozik` (an `-ik` verb, with a second form in
    * several cells and a rare `*` form).
    */
  private lazy val hungarianVerbs: Map[String, String] = {
    val source = Source.fromResource("wiktextract-hungarian-verbs.jsonl", getClass.getClassLoader)(using
      scala.io.Codec.UTF8
    )
    try source.getLines().toList.map(line => line.fromJson[WiktextractParser.RawEntry].toOption.get.word -> line).toMap
    finally source.close()
  }

  /** One verb's forms by relation. A lookup's tags may be written in any order. */
  private final case class Paradigm(byRelation: Map[String, Set[String]]) {
    def get(relation: String): Option[Set[String]] = byRelation.get(ParsedForm.relationOf(relation.split(',').toList))
    def keys: Iterable[String]                     = byRelation.keys
    def values: Iterable[Set[String]]              = byRelation.values
  }

  private def hungarianForms(word: String): Paradigm = {
    Paradigm(
      WiktextractParser
        .parse(hungarianVerbs(word))
        .forms
        .groupMap(_.relation)(_.form.text)
        .view
        .mapValues(_.toSet)
        .toMap
    )
  }

  /** A small dump for the export: `house` translated with the article, `home` translated to a genderless `Haus` that
    * the dedupe moves onto `das Haus`, a genderless `Haus` entry whose form follows it, and an untranslated
    * interjection `haus` that the dedupe drops. `Teller` is outside the cut; `gratis` is inside it, with recordings.
    */
  private val exportLines = List(
    houseLine,
    """{"word":"home","lang_code":"en","pos":"noun","senses":[{"glosses":["dwelling"]}],""" +
      """"translations":[{"code":"de","word":"Haus","sense":"dwelling"},{"code":"hu","word":"otthon","sense":"dwelling"}]}""",
    """{"word":"example","lang_code":"en","pos":"noun","senses":[{"glosses":["instance"]}],""" +
      """"translations":[{"code":"de","word":"Beispiel","tags":["neuter"],"sense":"instance"}]}""",
    hausLine,
    """{"word":"Haus","lang_code":"de","pos":"noun","senses":[{"glosses":["house"]}],""" +
      """"forms":[{"form":"Hauses","tags":["genitive"]}]}""",
    """{"word":"haus","lang_code":"de","pos":"intj","senses":[{"glosses":["a call"]}]}""",
    plateLine,
    formsLine,
    soundsLine,
  )

  private def writeGzip(path: java.nio.file.Path, lines: List[String]): Unit = {
    val out = new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(path))
    try out.write(lines.map(_ + "\n").mkString.getBytes(StandardCharsets.UTF_8))
    finally out.close()
  }

  private def readGzip(path: java.nio.file.Path): List[String] = {
    val in = new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(path))
    try new String(in.readAllBytes(), StandardCharsets.UTF_8).split('\n').toList.filter(_.nonEmpty)
    finally in.close()
  }

  def spec = {
    suite("DictionaryImport")(
      test("a German noun keeps its article, and everything else keeps none") {
        val haus   = WiktextractParser.parse(hausLine).word
        val teller = WiktextractParser.parse(plateLine).word
        assertTrue(
          haus.map(_.text).contains("Haus"),
          haus.flatMap(_.gender).contains(Gender.Neuter),
          haus.map(_.partOfSpeech).contains(PartOfSpeech.Noun),
          haus.map(_.language).contains(WordLanguage.De),
          teller.flatMap(_.gender).contains(Gender.Masculine),
        )
      },
      test("inflected forms and affixes are not vocabulary") {
        assertTrue(
          WiktextractParser.parse(inflectedLine).word.isEmpty,
          WiktextractParser.parse(prefixLine).word.isEmpty,
        )
      },
      test("an English entry's translation table is where the pairs come from, gender included") {
        val entry  = WiktextractParser.parse(houseLine)
        val german = entry.pairs.find(_.target.language == WordLanguage.De)
        assertTrue(
          entry.word.map(_.text).contains("house"),
          // Italian is in the line and dropped: the parser keeps only the languages this application holds.
          entry.pairs.map(_.target.text).toSet == Set("Haus", "ház"),
          german.flatMap(_.target.gender).contains(Gender.Neuter),
          // The target takes the headword's part of speech: Wiktionary does not repeat it per row.
          german.map(_.target.partOfSpeech).contains(PartOfSpeech.Noun),
          german.flatMap(_.sense).contains("building"),
        )
      },
      test("German and Hungarian are joined through the English sense they share") {
        val pairs    = WiktextractParser.parse(houseLine).pairs
        val inferred = DictionaryImport.pivot(pairs)
        assertTrue(
          inferred.map(pair => (pair.source.text, pair.target.text)) == List(("Haus", "ház")),
          // Only ever de -> hu: the pivot answers the one pair no source states directly.
          inferred.forall(pair => pair.source.language == WordLanguage.De && pair.target.language == WordLanguage.Hu),
        )
      },
      test("pivot skips a pronoun or article: a shared sense there is too generic to trust") {
        val which = ParsedWord(WordLanguage.En, "which", PartOfSpeech.Other, None)
        val die   = ParsedWord(WordLanguage.De, "die", PartOfSpeech.Other, None)
        val mik   = ParsedWord(WordLanguage.Hu, "mik", PartOfSpeech.Other, None)
        val pairs = List(
          WiktextractParser.ParsedPair(which, die, Some("interrogative")),
          WiktextractParser.ParsedPair(which, mik, Some("interrogative")),
        )
        assertTrue(DictionaryImport.pivot(pairs).isEmpty)
      },
      test("a language not being imported contributes nothing, and a malformed line is skipped") {
        val italian = """{"word":"casa","lang_code":"it","pos":"noun","senses":[{"glosses":["house"]}]}"""
        assertTrue(
          !WiktextractParser.mayConcern(italian, WordLanguage.all.toSet),
          WiktextractParser.mayConcern(hausLine, Set(WordLanguage.De)),
          !WiktextractParser.mayConcern(hausLine, Set(WordLanguage.Hu)),
          WiktextractParser.parse("{ not json").word.isEmpty,
        )
      },
      test("a lemma's own forms array becomes its inflected words, ungendered even when the lemma is gendered") {
        val forms = WiktextractParser.parse(hausLine).forms
        assertTrue(
          forms.map(_.form.text) == List("Häuser"),
          forms.head.relation == "plural",
          forms.head.form.partOfSpeech == PartOfSpeech.Noun,
          forms.head.form.gender.isEmpty,
          forms.head.lemma.text == "Haus",
        )
      },
      test(
        "a meta/template row, the '-' placeholder, and a note are dropped; a self-spelled form still parses"
      ) {
        val forms = WiktextractParser.parse(formsLine).forms
        assertTrue(
          forms.map(_.form.text).toSet == Set("Beispiele", "Beispiel"),
          forms.find(_.form.text == "Beispiele").map(_.relation).contains("plural"),
          forms.find(_.form.text == "Beispiel").map(_.relation).contains("nominative,singular"),
        )
      },
      test(
        "a form wiktextract itself could not classify (error-*) is dropped entirely, not just the offending tag"
      ) {
        val forms = WiktextractParser.parse(formsLine).forms
        assertTrue(
          !forms.exists(_.form.text == "Beispielen"),
          !forms.exists(_.form.text == "Beispielem"),
        )
      },
      test("a nonstandard, obsolete, alternative-spelling, or archaic form is not imported") {
        val forms = WiktextractParser.parse(formsLine).forms
        assertTrue(
          !forms.exists(_.form.text == "Beyspiel"),
          !forms.exists(_.form.text == "Bexempel"),
          !forms.exists(_.form.text == "Beyspil"),
          !forms.exists(_.form.text == "Exempel"),
        )
      },
      test("a form-of page's own sense tags are filtered the same way: an archaic reading is not a form") {
        // `dünkt`'s own page states it is an archaic third-person present of `dünken`. The lemma's `forms[]`
        // array would drop this row; the form-of page must drop it too, or the archaic spelling slips in.
        val archaicPage = {
          """{"word":"deuchte","lang_code":"de","lang":"German","pos":"verb",""" +
            """"senses":[{"glosses":["archaic form of dünkte"],""" +
            """"tags":["form-of","archaic","indicative","past","third-person","singular"],""" +
            """"form_of":[{"word":"dünkte"}]}]}"""
        }
        val plainPage   = {
          """{"word":"gedünkt","lang_code":"de","lang":"German","pos":"verb",""" +
            """"senses":[{"glosses":["past participle of dünken"],""" +
            """"tags":["form-of","participle","past"],"form_of":[{"word":"dünken"}]}]}"""
        }
        assertTrue(
          WiktextractParser.parse(archaicPage).forms.isEmpty,
          WiktextractParser.parse(plainPage).forms.map(_.form.text) == List("gedünkt"),
        )
      },
      test(
        "a German conjugation table's auxiliary-verb note is not imported as a form: it would make 'haben'/'sein' a " +
          "form of nearly every German verb"
      ) {
        val forms = WiktextractParser.parse(formsLine).forms
        assertTrue(!forms.exists(_.form.text == "haben"))
      },
      test("a separable verb's main clause and the composed tenses are imported, each with its relation") {
        val forms    = WiktextractParser.parse(einkaufenLine).forms
        val relation = forms.map(form => form.form.text -> form.relation).toMap
        assertTrue(
          forms.map(_.form.text).toSet == Set(
            "kauft ein",
            "kaufe ein",
            "kaufest ein",
            "kauf ein",
            "einkaufe",
            "habe eingekauft",
            "würde eingekauft haben",
          ),
          relation.get("kaufe ein").contains("first-person,indicative,present,singular"),
          relation.get("kaufest ein").contains("second-person,singular,subjunctive,subjunctive-i"),
          relation.get("kauf ein").contains("imperative,second-person,singular"),
          // `multiword-construction` says how the cell was read, not what the form is.
          relation.get("habe eingekauft").contains("first-person,indicative,perfect,singular"),
          relation
            .get("würde eingekauft haben")
            .contains(
              "first-person,future,future-ii,singular,subjunctive,subjunctive-ii"
            ),
          forms.forall(_.form.partOfSpeech == PartOfSpeech.Verb),
          forms.forall(_.lemma.text == "einkaufen"),
        )
      },
      test("a multi-word row that is not a form stays out: a predicative, a canonical row, a note, a long cell") {
        val texts = WiktextractParser.parse(einkaufenLine).forms.map(_.form.text).toSet
        assertTrue(
          !texts.contains("er ist eingekauft"),
          !texts.contains("the einkaufen"),
          !texts.contains("definite forms are not used"),
          !texts.contains("one two three four five"),
        )
      },
      test("a cell that names two forms becomes two forms with the same relation") {
        val forms = WiktextractParser.parse(alternativesLine).forms.groupMap(_.relation)(_.form.text)
        assertTrue(
          forms.get("first-person,possessed-many,possessive,singular").contains(List("GUI-jaim", "GUI-im")),
          forms.get("nominative,plural").contains(List("GUI-k", "GUI-ek")),
          WiktextractParser.alternativesOf("lennék or") == List("lennék"),
          WiktextractParser.alternativesOf("or adnók") == List("adnók"),
          WiktextractParser.alternativesOf("adva (adván)") == List("adva", "adván"),
        )
      },
      test("a Hungarian noun's -é and -éi forms are read from their ending, though the dump marks them an error") {
        val forms = WiktextractParser.parse(alternativesLine).forms.map(form => form.form.text -> form.relation).toMap
        assertTrue(
          forms.get("GUI-é").contains("possessed-single,possessor,singular"),
          forms.get("GUI-kéi").contains("plural,possessed-many,possessor"),
        )
      },
      test("a Spanish reflexive form is imported and tagged reflexive; the plain form is not") {
        val forms = WiktextractParser.parse(comprarLine).forms.map(form => form.form.text -> form.relation).toMap
        assertTrue(
          forms.get("compra").contains("indicative,present,singular,third-person"),
          forms.get("se compra").contains("indicative,present,reflexive,singular,third-person"),
          forms.get("nos compramos").contains("first-person,indicative,plural,present,reflexive"),
        )
      },
      test("an English periphrastic comparison is a form like the inflected one") {
        val forms = WiktextractParser.parse(freeLine).forms.map(form => form.form.text -> form.relation).toMap
        assertTrue(
          forms == Map("freer" -> "comparative", "more free" -> "comparative", "most free" -> "superlative")
        )
      },
      test("a Hungarian conjugation table is rebuilt from cell positions: every tense, person and conjugation") {
        val ad                                   = hungarianForms("ad")
        def is(relation: String, forms: String*) = ad.get(relation).contains(forms.toSet)
        assertTrue(
          is("first-person,indicative,indefinite,present,singular", "adok"),
          is("indicative,indefinite,present,singular,third-person", "ad"),
          is("indicative,indefinite,plural,present,third-person", "adnak"),
          is("indicative,definite,present,singular,third-person", "adja"),
          is("first-person,indicative,definite,plural,present", "adjuk"),
          is("first-person,indicative,object-second-person,present,singular", "adlak"),
          is("first-person,indicative,indefinite,past,singular", "adtam"),
          is("indicative,indefinite,past,singular,third-person", "adott"),
          is("indicative,definite,past,plural,third-person", "adták"),
          is("conditional,first-person,indefinite,present,singular", "adnék"),
          is("conditional,first-person,definite,plural,present", "adnánk", "adnók"),
          is("indefinite,present,second-person,singular,subjunctive", "adj", "adjál"),
          is("indefinite,present,singular,subjunctive,third-person", "adjon"),
          is("infinitive", "adni"),
          is("first-person,infinitive,singular", "adnom"),
          is("noun-from-verb", "adás"),
          is("participle,present", "adó"),
          is("participle,past", "adott"),
          is("adverbial,participle", "adva", "adván"),
        )
      },
      test("a verb's second table is its potential, and the archaic tenses and the notes are left out") {
        val ad    = hungarianForms("ad")
        val texts = ad.values.flatten.toSet
        assertTrue(
          ad.get("first-person,indicative,indefinite,potential,present,singular").contains(Set("adhatok")),
          ad.get("conditional,definite,potential,present,singular,third-person").contains(Set("adhatná")),
          // The archaic past (`adék`) and archaic future (`adandok`) are not taught.
          !texts.contains("adék"),
          !texts.contains("adandok"),
          !texts.exists(_.contains(" ")),
          // No row keeps the dump's own, shifted person tags.
          !ad.keys.exists(_.contains("second-person-semantically")),
        )
      },
      test("an intransitive verb has no definite forms, and a wrong-shaped block is dropped rather than guessed") {
        val megy     = hungarianForms("megy")
        val dolgozik = hungarianForms("dolgozik")
        assertTrue(
          megy.get("first-person,indicative,indefinite,present,singular").contains(Set("megyek")),
          megy.get("indicative,indefinite,present,second-person,singular").contains(Set("mész")),
          megy.get("indicative,indefinite,past,singular,third-person").contains(Set("ment")),
          megy.get("indefinite,present,singular,subjunctive,third-person").contains(Set("menjen")),
          !megy.keys.exists(_.split(",").contains("definite")),
          // An -ik verb writes two forms in some cells; both are kept, in one slot.
          dolgozik
            .get("first-person,indicative,indefinite,present,singular")
            .contains(
              Set("dolgozom", "dolgozok")
            ),
          dolgozik
            .get("indefinite,present,singular,subjunctive,third-person")
            .contains(
              Set("dolgozzon", "dolgozzék")
            ),
        )
      },
      test("formEdges resolves ids via the id map and keeps a form spelled like its own lemma as a self-link") {
        // English "put"'s past tense is "put": a real fact, and how the word page knows it. "went" is a distinct word.
        val put   = ParsedWord(WordLanguage.En, "put", PartOfSpeech.Verb, None)
        val went  = ParsedWord(WordLanguage.En, "went", PartOfSpeech.Verb, None)
        val ids   = Map(put -> 1L, went -> 2L)
        val edges = DictionaryImport.formEdges(
          List(ParsedForm(put, put, "past"), ParsedForm(put, went, "past")),
          ids,
        )
        assertTrue(edges == List((1L, 1L, "past"), (1L, 2L, "past")))
      },
      test("a noun's gender counterpart and a diminutive are links, not forms; a counterpart's plural is a form") {
        val artist  = ParsedWord(WordLanguage.De, "Künstler", PartOfSpeech.Noun, Some(Gender.Masculine))
        val female  = ParsedWord(WordLanguage.De, "Künstlerin", PartOfSpeech.Noun, Some(Gender.Feminine))
        val females = ParsedWord(WordLanguage.De, "Künstlerinnen", PartOfSpeech.Noun, None)
        val house   = ParsedWord(WordLanguage.De, "Haus", PartOfSpeech.Noun, Some(Gender.Neuter))
        val small   = ParsedWord(WordLanguage.De, "Häuschen", PartOfSpeech.Noun, Some(Gender.Neuter))
        val bonito  = ParsedWord(WordLanguage.Es, "bonito", PartOfSpeech.Adjective, None)
        val bonita  = ParsedWord(WordLanguage.Es, "bonita", PartOfSpeech.Adjective, None)
        val lawyer  = ParsedWord(WordLanguage.Es, "abogado", PartOfSpeech.Noun, None)
        val lawyerF = ParsedWord(WordLanguage.Es, "abogada", PartOfSpeech.Noun, None)
        val forms   = List(
          ParsedForm(artist, female, "feminine"),
          ParsedForm(artist, females, "feminine,plural"),
          ParsedForm(house, small, "diminutive,neuter"),
          ParsedForm(bonito, bonita, "feminine"),
          ParsedForm(lawyer, lawyerF, "feminine,rare"),
        )
        val ids     = Map(
          artist  -> 1L,
          female  -> 2L,
          females -> 3L,
          house   -> 4L,
          small   -> 5L,
          bonito  -> 6L,
          bonita  -> 7L,
          lawyer  -> 8L,
          lawyerF -> 9L,
        )
        assertTrue(
          WordLinks.of(forms(0)).contains((WordLinkKind.Feminine, WordLinkKind.Masculine)),
          WordLinks.of(forms(1)).isEmpty,
          WordLinks.of(forms(2)).contains((WordLinkKind.Diminutive, WordLinkKind.DiminutiveOf)),
          // An adjective's feminine is an inflection.
          WordLinks.of(forms(3)).isEmpty,
          // `rare` does not change what a row is, and a lemma with no gender takes the opposite one.
          WordLinks.of(forms(4)).contains((WordLinkKind.Feminine, WordLinkKind.Masculine)),
          DictionaryImport.formEdges(forms, ids).toSet == Set((1L, 3L, "feminine,plural"), (6L, 7L, "feminine")),
          DictionaryImport.linkEdges(forms, ids).toSet == Set(
            (1L, 2L, "feminine"),
            (2L, 1L, "masculine"),
            (4L, 5L, "diminutive"),
            (5L, 4L, "diminutive-of"),
            (8L, 9L, "feminine"),
            (9L, 8L, "masculine"),
          ),
        )
      },
      test("formPairs pairs no self-form") {
        // `Künstler`'s plural is spelled `Künstler`. Pairing it would give `artists` (2) the masculine singular row (3).
        val forms = List(
          DictionaryImport.LemmaForm(1L, "plural", 2L, "artists"),
          DictionaryImport.LemmaForm(3L, "plural", 3L, "Künstler"),
        )
        assertTrue(DictionaryImport.formPairs(List((1L, 3L)), forms).isEmpty)
      },
      test("a form's translation is inferred through its lemma's own translation, matched on relation") {
        // house (1) -> houses (2); Haus (3) -> Häuser (4), and a second spelling of the same cell that sorts later.
        val forms = List(
          DictionaryImport.LemmaForm(1L, "plural", 2L, "houses"),
          DictionaryImport.LemmaForm(3L, "plural", 5L, "Häusern"),
          DictionaryImport.LemmaForm(3L, "plural", 4L, "Häuser"),
          // Teller (6) has no translation pair, so its plural contributes no edge.
          DictionaryImport.LemmaForm(6L, "plural", 7L, "Teller"),
        )
        assertTrue(DictionaryImport.formPairs(List((1L, 3L)), forms) == List((2L, 4L)))
      },
      test("formPairs finds nothing when a relation is not shared, or a lemma has no pair") {
        val forms = List(
          DictionaryImport.LemmaForm(1L, "plural", 2L, "houses"),
          DictionaryImport.LemmaForm(3L, "genitive", 4L, "Hauses"),
        )
        assertTrue(
          DictionaryImport.formPairs(List((1L, 3L)), forms).isEmpty,
          DictionaryImport.formPairs(List((1L, 9L)), forms).isEmpty,
        )
      },
      test("a limit keeps the commonest words, and whatever they translate to") {
        val entry       = WiktextractParser.parse(houseLine)
        val collected   = entry.pairs
          .foldLeft(DictionaryImport.Collected.empty)((acc, pair) =>
            acc.withWord(pair.source, 0).withWord(pair.target, 0)
          )
          .copy(pairs = entry.pairs)
        val frequencies = Map(WordLanguage.En -> Map("house" -> 1))
        val selected    = DictionaryImport.select(collected, frequencies, limit = 10)
        assertTrue(
          entry.word.isDefined,
          // The English word is inside the cut; the German and Hungarian ones are outside it and kept anyway, because
          // dropping them would leave the translations pointing at nothing.
          selected.words.keySet.map(_.text).contains("house"),
          selected.words.keySet.map(_.text).contains("Haus"),
          selected.pairs.nonEmpty,
        )
      },
      test("select keeps a lemma's forms only when the lemma itself survives, by rank or as a pair partner") {
        val house     = ParsedWord(WordLanguage.En, "house", PartOfSpeech.Noun, None)
        val houses    = ParsedWord(WordLanguage.En, "houses", PartOfSpeech.Noun, None)
        val haus      = ParsedWord(WordLanguage.De, "Haus", PartOfSpeech.Noun, Some(Gender.Neuter))
        val haeuser   = ParsedWord(WordLanguage.De, "Häuser", PartOfSpeech.Noun, None)
        val teller    = ParsedWord(WordLanguage.De, "Teller", PartOfSpeech.Noun, Some(Gender.Masculine))
        val tellers   = ParsedWord(WordLanguage.De, "Tellers", PartOfSpeech.Noun, None)
        val collected = DictionaryImport.Collected(
          words = Map(house -> 1, haus -> 999999999, teller -> 999999999),
          pairs = List(gathedge.backend.tools.WiktextractParser.ParsedPair(house, haus, Some("building"))),
          forms = List(
            ParsedForm(house, houses, "plural"),
            ParsedForm(haus, haeuser, "plural"),
            ParsedForm(teller, tellers, "plural"),
          ),
        )
        val selected  = DictionaryImport.select(collected, Map(WordLanguage.En -> Map("house" -> 1)), limit = 10)
        assertTrue(
          // house made the frequency cut directly; Haus is kept only because house translates to it -- both
          // still get their forms imported.
          selected.forms.map(_.form.text).toSet == Set("houses", "Häuser"),
          selected.words.keySet.map(_.text).contains("houses"),
          selected.words.keySet.map(_.text).contains("Häuser"),
          // Teller made neither cut, so its plural is dropped along with it.
          !selected.words.keySet.exists(_.text == "Tellers"),
        )
      },
      // The committed sample is data, and a line of it going malformed would be discovered by a developer with an
      // empty search box rather than by a test. This reads the real file.
      //
      // `data/` is gitignored: the seed is a dev-machine artifact from scripts/build-dictionary-seed.sh, so the
      // test no-ops where the file is absent (a fresh clone, CI). Every check reduces the decoded structure to a
      // scalar or a five-item sample BEFORE `assertTrue` sees it -- `collected.forms` alone is ~2M entries, and
      // letting zio-test pretty-print it on a failure is an OutOfMemoryError, not a diff.
      //
      // Which words survive depends on the `--limit` the seed was built with, so this keys its homograph check
      // on `Tag` -- `der Tag` (day) is among the commonest German nouns and present at any limit, and the dump
      // also carries the neuter `das Tag` (a mine's surface level). Same spelling, two genders, two rows: the
      // dedupe must keep both, the same guarantee the synthetic `der`/`die See` test above makes.
      test("the committed seed file parses, and keeps a gender homograph split") {
        val path = java.nio.file.Path.of("data/dictionary/seed.tsv")
        if (!java.nio.file.Files.exists(path)) {
          assertCompletes
        } else {
          val lines                             = {
            val source = Source.fromFile(path.toFile, StandardCharsets.UTF_8.name)
            try source.getLines().toList
            finally source.close()
          }
          val collected                         = DictionaryImport.SeedFormat.decode(lines)
          val german                            = collected.words.keySet.filter(_.language == WordLanguage.De)
          val tagGenders                        = german.filter(_.text == "Tag").flatMap(_.gender)
          val wordCount                         = collected.words.size
          val pairCount                         = collected.pairs.size
          val formCount                         = collected.forms.size
          def sampleForms(p: String => Boolean) = {
            collected.forms.iterator
              .filter(_.relation.split(",").exists(p))
              .map(f => s"${f.lemma.text} -> ${f.form.text} (${f.relation})")
              .take(5)
              .toList
          }
          val errorForms                        = sampleForms(_.startsWith("error-"))
          val badSpelling                       = sampleForms(Set("nonstandard", "obsolete", "alternative", "archaic"))
          val foreignPairs                      = collected.pairs.iterator
            .filterNot(_.source.language == WordLanguage.En)
            .map(p => s"${p.source.language} ${p.source.text}")
            .take(5)
            .toList
          val pivotCount                        = DictionaryImport.pivot(collected.pairs).size
          // A plain `--seed` streams the file and skips the dedupe, which `--export` already ran before writing it.
          val deduped                           = DictionaryImport.dedupeHomographs(collected)
          assertTrue(
            wordCount > 100,
            pairCount > 100,
            formCount > 100,
            // wiktextract's own "could not classify this cell" marker never survives the import.
            errorForms == Nil,
            // Nor does a nonstandard/obsolete/alternative-spelling/archaic form.
            badSpelling == Nil,
            tagGenders == Set(Gender.Masculine, Gender.Neuter),
            // Every pair is stated from English, since that is the only direction any source has.
            foreignPairs == Nil,
            pivotCount > 0,
            deduped.words.keySet == collected.words.keySet,
            deduped.pairs.toSet == collected.pairs.toSet,
            deduped.forms.toSet == collected.forms.toSet,
          )
        }
      },
      test("the seed format round-trips") {
        val pairs     = WiktextractParser.parse(houseLine).pairs
        val collected = pairs
          .foldLeft(DictionaryImport.Collected.empty)((acc, pair) =>
            acc.withWord(pair.source, 3).withWord(pair.target, 7)
          )
          .copy(pairs = pairs)
        val decoded   = DictionaryImport.SeedFormat.decode(DictionaryImport.SeedFormat.encode(collected))
        assertTrue(
          decoded.words.keySet == collected.words.keySet,
          decoded.pairs.toSet == collected.pairs.toSet,
          decoded.words.get(collected.words.keys.find(_.text == "house").get).contains(3),
        )
      },
      test("the seed format round-trips form relations too") {
        val house     = ParsedWord(WordLanguage.En, "house", PartOfSpeech.Noun, None)
        val houses    = ParsedWord(WordLanguage.En, "houses", PartOfSpeech.Noun, None)
        val collected = DictionaryImport.Collected.empty
          .withWord(house, 3)
          .withWord(houses, 999999999)
          .copy(forms = List(ParsedForm(house, houses, "plural")))
        val decoded   = DictionaryImport.SeedFormat.decode(DictionaryImport.SeedFormat.encode(collected))
        assertTrue(
          decoded.forms.toSet == collected.forms.toSet,
          decoded.words.keySet == collected.words.keySet,
        )
      },
      test("a streamed seed reads the same records as the whole file decoded") {
        val house     = ParsedWord(WordLanguage.En, "house", PartOfSpeech.Noun, None)
        val houses    = ParsedWord(WordLanguage.En, "houses", PartOfSpeech.Noun, None)
        val haus      = ParsedWord(WordLanguage.De, "Haus", PartOfSpeech.Noun, Some(Gender.Neuter))
        val collected = DictionaryImport.Collected.empty
          .withWord(house, 3)
          .withWord(houses, 3)
          .withWord(haus, 5)
          .copy(
            pairs = List(WiktextractParser.ParsedPair(house, haus, Some("building"))),
            forms = List(ParsedForm(house, houses, "plural")),
            audio = List(ParsedAudio(haus, "De-Haus.ogg", "")),
          )
        val lines     = "# a comment" :: DictionaryImport.SeedFormat.encode(collected)
        for {
          path   <- ZIO.attemptBlocking {
                      val file = java.nio.file.Files.createTempFile("seed", ".tsv")
                      java.nio.file.Files.writeString(file, lines.mkString("\n"))
                      file
                    }
          seed    = DictionaryImport.Records.seed(path.toString)
          words  <- seed.words.runCollect
          pairs  <- seed.pairs.runCollect
          forms  <- seed.forms.runCollect
          audio  <- seed.audio.runCollect
          _      <- ZIO.attemptBlocking(java.nio.file.Files.delete(path))
          decoded = DictionaryImport.SeedFormat.decode(lines)
        } yield assertTrue(
          words.toMap == decoded.words,
          pairs.toSet == decoded.pairs.toSet,
          forms.toSet == decoded.forms.toSet,
          audio.toSet == decoded.audio.toSet,
        )
      },
      test("a recording is read from sounds[], keyed to the entry, with its accent tags joined") {
        val entry = WiktextractParser.parse(soundsLine)
        val word  = ParsedWord(WordLanguage.De, "gratis", PartOfSpeech.Adverb, None)
        assertTrue(
          entry.audio == List(
            ParsedAudio(word, "De-gratis.ogg", "Germany, Berlin"),
            ParsedAudio(word, "LL-Q188_(deu)-Sebastian_Wallroth-gratis.wav", ""),
          )
        )
      },
      test("a form-of page brings no recording: it is not a word of its own") {
        val line = inflectedLine.dropRight(1) + ""","sounds":[{"audio":"De-Häuser.ogg"}]}"""
        assertTrue(WiktextractParser.parse(line).audio == Nil)
      },
      test("select keeps a recording only when its word survives") {
        val house     = ParsedWord(WordLanguage.En, "house", PartOfSpeech.Noun, None)
        val rare      = ParsedWord(WordLanguage.En, "haubitze", PartOfSpeech.Noun, None)
        val collected = DictionaryImport.Collected.empty
          .withWord(house, 0)
          .withWord(rare, 0)
          .copy(audio = List(ParsedAudio(house, "En-us-house.ogg", "US"), ParsedAudio(rare, "En-haubitze.ogg", "")))
        val selected  = DictionaryImport.select(collected, Map(WordLanguage.En -> Map("house" -> 1)), limit = 10)
        assertTrue(selected.audio == List(ParsedAudio(house, "En-us-house.ogg", "US")))
      },
      test("the seed format round-trips recordings, empty region included") {
        val gratis    = ParsedWord(WordLanguage.De, "gratis", PartOfSpeech.Adverb, None)
        val audio     = List(ParsedAudio(gratis, "De-gratis.ogg", "Germany, Berlin"), ParsedAudio(gratis, "LL-x.wav", ""))
        val collected = DictionaryImport.Collected.empty.withWord(gratis, 4).copy(audio = audio)
        val decoded   = DictionaryImport.SeedFormat.decode(DictionaryImport.SeedFormat.encode(collected))
        assertTrue(decoded.audio.toSet == audio.toSet, decoded.words.get(gratis).contains(4))
      },
      test("dedupeHomographs moves the genderless twin's recording onto the gendered survivor") {
        val frauGenderless = ParsedWord(WordLanguage.De, "Frau", PartOfSpeech.Noun, None)
        val frauGendered   = ParsedWord(WordLanguage.De, "Frau", PartOfSpeech.Noun, Some(Gender.Feminine))
        val deduped        = DictionaryImport.dedupeHomographs(
          DictionaryImport.Collected(
            words = Map(frauGenderless -> 1, frauGendered -> 1),
            pairs = Nil,
            forms = Nil,
            audio = List(ParsedAudio(frauGenderless, "De-Frau.ogg", "")),
          )
        )
        assertTrue(deduped.audio == List(ParsedAudio(frauGendered, "De-Frau.ogg", "")))
      },
      test("arguments are read, and the two modes are exclusive") {
        assertTrue(
          DictionaryImport.parseArgs(List("--seed")).map(_.seed) == Right(true),
          DictionaryImport.parseArgs(List("--raw", "dump.gz", "--limit", "10")).map(_.limit) == Right(10),
          DictionaryImport.parseArgs(List("--languages", "de,hu")).isLeft,
          DictionaryImport.parseArgs(List("--seed", "--raw", "dump.gz")).isLeft,
          DictionaryImport.parseArgs(Nil).isLeft,
          DictionaryImport.parseArgs(List("--nonsense")).isLeft,
        )
      },
      test("words differing only in case are one row, and the commonest reading wins") {
        val grammy    = ParsedWord(WordLanguage.En, "Grammy", PartOfSpeech.Noun, None)
        val grammyLc  = ParsedWord(WordLanguage.En, "grammy", PartOfSpeech.Noun, None)
        val haus      = ParsedWord(WordLanguage.De, "Haus", PartOfSpeech.Noun, Some(Gender.Neuter))
        // Same spelling, different article: two words, and the dedupe must not touch them.
        val seeLake   = ParsedWord(WordLanguage.De, "See", PartOfSpeech.Noun, Some(Gender.Masculine))
        val seeSea    = ParsedWord(WordLanguage.De, "See", PartOfSpeech.Noun, Some(Gender.Feminine))
        val deduped   = DictionaryImport.dedupeByKey(
          List((grammyLc, 17940), (grammy, 17940), (haus, 12), (seeLake, 900), (seeSea, 901))
        )
        val byKeyOnce = deduped.map { case (word, _) => word.key }
        assertTrue(
          byKeyOnce.distinct.size == byKeyOnce.size,
          deduped.map { case (word, _) => word.text }.contains("Grammy"),
          !deduped.map { case (word, _) => word.text }.contains("grammy"),
          deduped.exists { case (word, _) => word == seeLake },
          deduped.exists { case (word, _) => word == seeSea },
          // A rarer casing does not drag the word's rank down with it.
          DictionaryImport
            .dedupeByKey(List((grammy, 999999999), (grammyLc, 17940)))
            .map { case (_, rank) => rank } == List(17940),
        )
      },
      test("dedupeHomographs keeps only the gendered noun, and drops an untranslated Other twin") {
        val english        = ParsedWord(WordLanguage.En, "woman", PartOfSpeech.Noun, None)
        val frauGenderless = ParsedWord(WordLanguage.De, "Frau", PartOfSpeech.Noun, None)
        val frauGendered   = ParsedWord(WordLanguage.De, "Frau", PartOfSpeech.Noun, Some(Gender.Feminine))
        val frauOther      = ParsedWord(WordLanguage.De, "frau", PartOfSpeech.Other, None)
        val collected      = DictionaryImport.Collected(
          words = Map(english -> 1, frauGenderless -> 1, frauGendered -> 1, frauOther -> 1),
          pairs = List(
            gathedge.backend.tools.WiktextractParser.ParsedPair(english, frauGenderless, Some("person")),
            gathedge.backend.tools.WiktextractParser.ParsedPair(english, frauGendered, Some("person")),
          ),
          forms = Nil,
        )
        val deduped        = DictionaryImport.dedupeHomographs(collected)
        assertTrue(
          deduped.words.keySet == Set(english, frauGendered),
          deduped.pairs == List(
            gathedge.backend.tools.WiktextractParser.ParsedPair(english, frauGendered, Some("person"))
          ),
        )
      },
      test("dedupeHomographs merges the genderless twin's forms onto the gendered survivor instead of dropping them") {
        val frauGenderless = ParsedWord(WordLanguage.De, "Frau", PartOfSpeech.Noun, None)
        val frauGendered   = ParsedWord(WordLanguage.De, "Frau", PartOfSpeech.Noun, Some(Gender.Feminine))
        val frauen         = ParsedWord(WordLanguage.De, "Frauen", PartOfSpeech.Noun, None)
        val deduped        = DictionaryImport.dedupeHomographs(
          DictionaryImport.Collected(
            words = Map(frauGenderless -> 1, frauGendered -> 1, frauen -> 1),
            pairs = Nil,
            forms = List(ParsedForm(frauGenderless, frauen, "plural")),
          )
        )
        assertTrue(
          deduped.words.keySet == Set(frauGendered, frauen),
          deduped.forms == List(ParsedForm(frauGendered, frauen, "plural")),
        )
      },
      test("dedupeHomographs leaves an Other entry alone when it has its own translation, or nothing else translates") {
        val english         = ParsedWord(WordLanguage.En, "man", PartOfSpeech.Noun, None)
        val mannNoun        = ParsedWord(WordLanguage.De, "Mann", PartOfSpeech.Noun, Some(Gender.Masculine))
        val manOther        = ParsedWord(WordLanguage.De, "man", PartOfSpeech.Other, None)
        val translatedOther = DictionaryImport.dedupeHomographs(
          DictionaryImport.Collected(
            words = Map(english -> 1, mannNoun -> 1, manOther -> 1),
            pairs = List(
              gathedge.backend.tools.WiktextractParser.ParsedPair(english, mannNoun, Some("adult male")),
              gathedge.backend.tools.WiktextractParser.ParsedPair(english, manOther, Some("indefinite pronoun")),
            ),
            forms = Nil,
          )
        )
        val onlyOther       = DictionaryImport.dedupeHomographs(
          DictionaryImport.Collected(words = Map(manOther -> 1), pairs = Nil, forms = Nil)
        )
        assertTrue(
          translatedOther.words.keySet == Set(english, mannNoun, manOther),
          onlyOther.words.keySet == Set(manOther),
        )
      },
      test("dedupeHomographs never touches der/die See -- gender, not case, decides identity") {
        val seeLake = ParsedWord(WordLanguage.De, "See", PartOfSpeech.Noun, Some(Gender.Masculine))
        val seeSea  = ParsedWord(WordLanguage.De, "See", PartOfSpeech.Noun, Some(Gender.Feminine))
        val deduped = DictionaryImport.dedupeHomographs(
          DictionaryImport.Collected(words = Map(seeLake -> 1, seeSea -> 1), pairs = Nil, forms = Nil)
        )
        assertTrue(deduped.words.keySet == Set(seeLake, seeSea))
      },
      test("--shards is a third mode, and --extract needs --raw and no --export") {
        assertTrue(
          DictionaryImport.parseArgs(List("--shards", "x")).map(_.shards) == Right(Some("x")),
          DictionaryImport.parseArgs(List("--raw", "d.gz", "--extract", "x")).map(_.extractTo) == Right(Some("x")),
          DictionaryImport.parseArgs(List("--raw", "d.gz", "--shards", "x")).isLeft,
          DictionaryImport.parseArgs(List("--shards", "x", "--extract", "y")).isLeft,
          DictionaryImport.parseArgs(List("--raw", "d.gz", "--extract", "x", "--export", "s.tsv")).isLeft,
        )
      },
      test("languageOf reads the entry's own language, though a translation row names another first") {
        val translationFirst = {
          """{"translations":[{"code":"de","lang_code":"de","word":"Haus"}],"word":"house","lang_code":"en",""" +
            """"pos":"noun","senses":[{"glosses":["building"]}]}"""
        }
        assertTrue(
          WiktextractParser.languageOf(translationFirst).contains(WordLanguage.En),
          WiktextractParser.languageOf(hausLine).contains(WordLanguage.De),
          WiktextractParser.languageOf("""{"word":"casa","lang_code":"it"}""").isEmpty,
          WiktextractParser.languageOf("not json").isEmpty,
        )
      },
      test("extract writes one shard per language, holding the lines whose entry is in it") {
        val dump = List(houseLine, hausLine, plateLine, """{"word":"casa","lang_code":"it","pos":"noun"}""")
        for {
          directory <- ZIO.attemptBlocking(java.nio.file.Files.createTempDirectory("shards"))
          dumpPath   = directory.resolve("dump.jsonl.gz")
          _         <- ZIO.attemptBlocking(writeGzip(dumpPath, dump))
          _         <- DictionaryImport.extract(dumpPath.toString, directory.toString, Set(WordLanguage.En, WordLanguage.De))
          en        <- ZIO.attemptBlocking(readGzip(directory.resolve("en.jsonl.gz")))
          de        <- ZIO.attemptBlocking(readGzip(directory.resolve("de.jsonl.gz")))
          leftovers <- ZIO.attemptBlocking(directory.toFile.list().toList.filterNot(_ == "dump.jsonl.gz").sorted)
        } yield assertTrue(
          en == List(houseLine),
          de == List(hausLine, plateLine),
          // The `.part` files are renamed away, and a language not asked for gets no shard.
          leftovers == List("de.jsonl.gz", "en.jsonl.gz"),
        )
      },
      test("the per-language export writes what select and dedupeHomographs make of the whole dump at once") {
        val frequencies                                   = Map(
          WordLanguage.En -> Map("house" -> 1, "home" -> 2, "example" -> 3),
          WordLanguage.De -> Map("haus" -> 4, "gratis" -> 5),
        )
        val options                                       = DictionaryImport.Options(raw = Some("unused"), limit = 10)
        // The old pipeline: every line read into one Collected, then cut, then deduped.
        val whole                                         = {
          exportLines.map(WiktextractParser.parse(_)).foldLeft(DictionaryImport.Collected.empty) { (collected, entry) =>
            val withWord = entry.word.fold(collected)(collected.withWord(_, 999999999))
            entry.pairs
              .foldLeft(withWord)((acc, pair) => acc.withWord(pair.source, 999999999).withWord(pair.target, 999999999))
              .copy(
                pairs = entry.pairs ++ collected.pairs,
                forms = entry.forms ++ collected.forms,
                audio = entry.audio ++ collected.audio,
              )
          }
        }
        val expected                                      = DictionaryImport.dedupeHomographs(DictionaryImport.select(whole, frequencies, limit = 10))
        val dumpSource                                    = new DictionaryImport.DumpSource {
          def lines(languages: Set[WordLanguage]) = {
            zio.stream.ZStream.fromIterable(exportLines.filter(WiktextractParser.mayConcern(_, languages)))
          }
        }
        val shardSource                                   = new DictionaryImport.DumpSource {
          def lines(languages: Set[WordLanguage]) = {
            zio.stream.ZStream.fromIterable(
              exportLines.filter(line => WiktextractParser.languageOf(line).exists(languages.contains))
            )
          }
        }
        def exported(source: DictionaryImport.DumpSource) = {
          for {
            path  <- ZIO.attemptBlocking(java.nio.file.Files.createTempFile("seed", ".tsv"))
            _     <- DictionaryImport.exportDump(source, options, frequencies, path.toString)
            lines <- ZIO.attemptBlocking(java.nio.file.Files.readAllLines(path).toArray(Array.empty[String]).toList)
            _     <- ZIO.attemptBlocking(java.nio.file.Files.delete(path))
          } yield DictionaryImport.SeedFormat.decode(lines)
        }
        for {
          fromDump   <- exported(dumpSource)
          fromShards <- exported(shardSource)
        } yield assertTrue(
          // The fixture exercises what the split has to get right: a redirect, a drop, and a cross-language pair
          // that points at the redirected word.
          expected.words.keySet.exists(_.text == "Hauses"),
          !expected.words.keySet.exists(word => word.text == "haus"),
          expected.pairs.exists(pair => pair.source.text == "home" && pair.target.gender.contains(Gender.Neuter)),
          fromDump.words == expected.words,
          fromDump.pairs.toSet == expected.pairs.toSet,
          fromDump.forms.toSet == expected.forms.toSet,
          fromDump.audio.toSet == expected.audio.toSet,
          fromShards.words == fromDump.words,
          fromShards.pairs.toSet == fromDump.pairs.toSet,
          fromShards.forms.toSet == fromDump.forms.toSet,
          fromShards.audio.toSet == fromDump.audio.toSet,
        )
      },
      test("--seed takes an optional path, and does not swallow the option after it") {
        assertTrue(
          DictionaryImport.parseArgs(List("--seed")).map(_.seedPath) == Right(None),
          DictionaryImport.parseArgs(List("--seed", "/tmp/x.tsv.gz")).map(_.seedPath) == Right(Some("/tmp/x.tsv.gz")),
          // Without the guard this would read "--raw" as the seed path, and the two modes would stop
          // being exclusive.
          DictionaryImport.parseArgs(List("--seed", "--raw", "dump.gz")).isLeft,
          DictionaryImport.parseArgs(List("--seed", "/tmp/x.tsv", "--limit", "10")).map(_.limit) == Right(10),
        )
      },
    )
  }
}
