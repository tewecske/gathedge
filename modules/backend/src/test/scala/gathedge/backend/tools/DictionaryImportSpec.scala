package gathedge.backend.tools

import gathedge.backend.tools.WiktextractParser.{ParsedForm, ParsedWord}
import gathedge.shared.domain.{Gender, PartOfSpeech, WordLanguage}
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
      |{"code":"fr","lang":"French","word":"maison","sense":"building"}]}""".stripMargin.replace("\n", "")
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

  private val prefixLine = {
    """{"word":"un-","lang_code":"de","lang":"German","pos":"prefix","senses":[{"glosses":["un-"]}]}"""
  }

  /** One `forms[]` array exercising every filter `WiktextractParser.formsOf` applies: a real plural, the dump's own "no
    * such form" placeholder (`"-"`), a periphrastic construction (a space), two flavours of template scaffolding
    * (`table-tags`, `inflection-template`), a Hungarian-style stem-class label (`class`), a form spelled identically to
    * its own lemma (real linguistic fact, not noise -- excluded at store time instead, see
    * `DictionaryImport.formEdges`), and two flavours of wiktextract's own "could not classify this cell" marker
    * (`error-unrecognized-form`, `error-unknown-tag`) -- each row dropped whole, not salvaged for its other tags.
    */
  private val formsLine = {
    """{"word":"Beispiel","lang_code":"de","lang":"German","pos":"noun","tags":["neuter"],
      |"senses":[{"glosses":["example"]}],
      |"forms":[
      |{"form":"Beispiele","tags":["plural"]},
      |{"form":"-","tags":["genitive"]},
      |{"form":"zum Beispiel","tags":["idiom"]},
      |{"form":"Beispiel","tags":["nominative","singular"]},
      |{"form":"strong","tags":["table-tags"],"source":"declension"},
      |{"form":"de-ndecl","tags":["inflection-template"],"source":"declension"},
      |{"form":"back harmony","tags":["class"]},
      |{"form":"Beispielen","tags":["error-unrecognized-form","dative","plural"]},
      |{"form":"Beispielem","tags":["error-unknown-tag","dative"]}
      |]}""".stripMargin.replace("\n", "")
  }

  def spec = {
    suite("DictionaryImport")(
      test("a German noun keeps its article, and everything else keeps none") {
        val haus   = WiktextractParser.parse(hausLine).word
        val teller = WiktextractParser.parse(plateLine).word
        assertTrue(
          haus.map(_.text).contains("Haus"),
          haus.flatMap(_.gender).contains(Gender.Das),
          haus.map(_.partOfSpeech).contains(PartOfSpeech.Noun),
          haus.map(_.language).contains(WordLanguage.De),
          teller.flatMap(_.gender).contains(Gender.Der),
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
          // French is in the line and dropped: the parser keeps only the three languages this application holds.
          entry.pairs.map(_.target.text).toSet == Set("Haus", "ház"),
          german.flatMap(_.target.gender).contains(Gender.Das),
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
      test("a language not being imported contributes nothing, and a malformed line is skipped") {
        val french = """{"word":"maison","lang_code":"fr","pos":"noun","senses":[{"glosses":["house"]}]}"""
        assertTrue(
          !WiktextractParser.mayConcern(french, WordLanguage.all.toSet),
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
        "a meta/template row, the '-' placeholder, and a form with a space are dropped; a self-spelled form still parses"
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
      test("formEdges resolves ids via the id map and drops a form spelled identically to its own lemma") {
        // English "put"'s past tense is "put" -- a real fact, kept by the parser -- while "went" is a distinct word.
        val put   = ParsedWord(WordLanguage.En, "put", PartOfSpeech.Verb, None)
        val went  = ParsedWord(WordLanguage.En, "went", PartOfSpeech.Verb, None)
        val ids   = Map(put -> 1L, went -> 2L)
        val edges = DictionaryImport.formEdges(
          List(ParsedForm(put, put, "past"), ParsedForm(put, went, "past")),
          ids,
        )
        assertTrue(edges == List((1L, 2L, "past")))
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
        val haus      = ParsedWord(WordLanguage.De, "Haus", PartOfSpeech.Noun, Some(Gender.Das))
        val haeuser   = ParsedWord(WordLanguage.De, "Häuser", PartOfSpeech.Noun, None)
        val teller    = ParsedWord(WordLanguage.De, "Teller", PartOfSpeech.Noun, Some(Gender.Der))
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
      test("the committed seed file parses, and holds both halves of der/die See") {
        val lines      = {
          val source = Source.fromFile("data/dictionary/seed.tsv", StandardCharsets.UTF_8.name)
          try source.getLines().toList
          finally source.close()
        }
        val collected  = DictionaryImport.SeedFormat.decode(lines)
        val german     = collected.words.keySet.filter(_.language == WordLanguage.De)
        val seeEntries = german.filter(_.text == "See").flatMap(_.gender)
        assertTrue(
          collected.words.size > 100,
          collected.pairs.size > 100,
          collected.forms.size > 100,
          // wiktextract's own "could not classify this cell" marker never survives the import.
          !collected.forms.exists(_.relation.split(",").exists(_.startsWith("error-"))),
          seeEntries == Set(Gender.Der, Gender.Die),
          // Every pair is stated from English, since that is the only direction any source has.
          collected.pairs.forall(_.source.language == WordLanguage.En),
          DictionaryImport.pivot(collected.pairs).nonEmpty,
        )
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
        val haus      = ParsedWord(WordLanguage.De, "Haus", PartOfSpeech.Noun, Some(Gender.Das))
        // Same spelling, different article: two words, and the dedupe must not touch them.
        val seeLake   = ParsedWord(WordLanguage.De, "See", PartOfSpeech.Noun, Some(Gender.Der))
        val seeSea    = ParsedWord(WordLanguage.De, "See", PartOfSpeech.Noun, Some(Gender.Die))
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
