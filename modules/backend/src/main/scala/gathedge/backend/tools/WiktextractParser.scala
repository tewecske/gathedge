package gathedge.backend.tools

import gathedge.shared.domain.{Gender, PartOfSpeech, WordLanguage}
import zio.json.*

/** Turns one line of a wiktextract dump into the words and translations this application stores.
  *
  * Pure, and deliberately in its own file: the dump is 22.9 GB and nobody is going to run the importer to find out
  * whether German gender was read correctly. `DictionaryImportSpec` drives these functions over a handful of real lines
  * instead.
  *
  * The source is `https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz`, one JSON object per line, extracted
  * from the English Wiktionary. Three of its fields carry everything needed here:
  *
  *   - `pos` and `tags` — the part of speech, and for a German noun its gender (`masculine` → `der`).
  *   - `senses[].glosses` — the English meaning, which is how a German or Hungarian entry gets its English side.
  *   - `translations[]` — present on *English* entries, and the only place `en → de` and `en → hu` pairs come from.
  *
  * There is no `de → hu` anywhere in the data, at any price: the pair is derived by pivoting on a shared English sense,
  * which is [[DictionaryImport]]'s job rather than this file's.
  */
object WiktextractParser {

  /** Only the fields used are decoded. The dump has dozens more per entry — etymology, pronunciation, inflection tables
    * — and decoding them would be most of the cost of reading it.
    */
  final case class RawSense(
    glosses: Option[List[String]] = None,
    tags: Option[List[String]] = None,
  ) derives JsonDecoder

  final case class RawTranslation(
    code: Option[String] = None,
    word: Option[String] = None,
    sense: Option[String] = None,
    tags: Option[List[String]] = None,
  ) derives JsonDecoder

  /** One row of `forms[]`: an inflected or declined spelling of the entry, tagged with what it is (`plural`,
    * `genitive`, `past`, one cell of a declension/conjugation table, ...). `source` names the table the row came from
    * when there is one (`"declension"`, `"conjugation"`); it is absent for a bare, untabled form.
    */
  final case class RawForm(
    form: String,
    tags: Option[List[String]] = None,
    source: Option[String] = None,
  ) derives JsonDecoder

  final case class RawEntry(
    word: String,
    lang_code: Option[String] = None,
    pos: Option[String] = None,
    tags: Option[List[String]] = None,
    senses: Option[List[RawSense]] = None,
    translations: Option[List[RawTranslation]] = None,
    forms: Option[List[RawForm]] = None,
  ) derives JsonDecoder

  /** A word as it will be stored, before it has an id. */
  final case class ParsedWord(
    language: WordLanguage,
    text: String,
    partOfSpeech: PartOfSpeech,
    gender: Option[Gender],
  ) {
    def key: (String, String, String, String) = {
      (WordLanguage.code(language), text.toLowerCase, PartOfSpeech.code(partOfSpeech), Gender.toColumn(gender))
    }
  }

  /** One direction of a pair, with the English sense it came from — which is what makes the German and Hungarian sides
    * of the same sense joinable later.
    */
  final case class ParsedPair(source: ParsedWord, target: ParsedWord, sense: Option[String])

  /** One form of a lemma, with the canonical tag string as its relation. */
  final case class ParsedForm(lemma: ParsedWord, form: ParsedWord, relation: String)

  object ParsedForm {

    /** Sorted, lower-cased, deduplicated, comma-joined -- so the same tag set decoded twice, or decoded on a re-import
      * months later, always produces the same string. This is what keeps a re-run idempotent without the importer
      * having to compare tag lists structurally.
      */
    def relationOf(tags: List[String]): String = {
      tags.map(_.trim.toLowerCase).filter(_.nonEmpty).distinct.sorted.mkString(",")
    }
  }

  /** Both halves of one wiktextract line: the entry as a word, what it translates to, and what forms it has. */
  final case class ParsedEntry(word: Option[ParsedWord], pairs: List[ParsedPair], forms: List[ParsedForm])

  /** Wiktionary's part-of-speech vocabulary is much wider than this application's five values; everything unmapped
    * becomes `Other`, and the handful that are not words at all are dropped by [[isUsablePos]].
    */
  private val posByName: Map[String, PartOfSpeech] = {
    Map(
      "noun"      -> PartOfSpeech.Noun,
      "verb"      -> PartOfSpeech.Verb,
      "adj"       -> PartOfSpeech.Adjective,
      "adjective" -> PartOfSpeech.Adjective,
      "adv"       -> PartOfSpeech.Adverb,
      "adverb"    -> PartOfSpeech.Adverb,
    )
  }

  /** Parts of speech that are not vocabulary: affixes, punctuation, characters, and proper nouns (a learner does not
    * need `Hamburg` in a word list, and the dump has a great many of them).
    */
  private val skippedPos: Set[String] = {
    Set("prefix", "suffix", "infix", "interfix", "circumfix", "punct", "character", "name", "abbrev", "symbol")
  }

  /** Sense tags that mark an entry as *not* a lemma, or as one nobody should be taught. A form-of entry is an
    * inflection with its own page (`Häuser`), and importing those would bury the lemma in the search box.
    */
  private val skippedSenseTags: Set[String] = {
    Set("form-of", "inflection-of", "obsolete", "archaic", "misspelling", "alt-of", "abbreviation")
  }

  def isUsablePos(pos: String): Boolean = !skippedPos.contains(pos.toLowerCase)

  def partOfSpeechOf(pos: String): PartOfSpeech = posByName.getOrElse(pos.toLowerCase, PartOfSpeech.Other)

  /** The article a German noun takes, from the entry's own tags. Read only for German nouns: an English noun tagged
    * `masculine` (they exist, for ships and countries) would otherwise become a second, unfindable copy of itself.
    */
  def genderOf(language: WordLanguage, pos: PartOfSpeech, tags: List[String]): Option[Gender] = {
    if (language != WordLanguage.De || pos != PartOfSpeech.Noun)
      None
    else {
      tags.map(_.toLowerCase).collectFirst {
        case "masculine" =>
          Gender.Der
        case "feminine"  =>
          Gender.Die
        case "neuter"    =>
          Gender.Das
      }
    }
  }

  private def isLemma(entry: RawEntry): Boolean = {
    val senses = entry.senses.getOrElse(Nil)
    // No senses at all is a stub or a redirect. Every sense being an inflection means the lemma is elsewhere.
    senses.nonEmpty && senses.exists(sense => !sense.tags.getOrElse(Nil).exists(skippedSenseTags.contains))
  }

  /** The entry itself as a word, or `None` if it is not one this application stores. */
  def wordOf(entry: RawEntry): Option[ParsedWord] = {
    for {
      code     <- entry.lang_code
      language <- WordLanguage.fromString(code)
      pos      <- entry.pos
      if isUsablePos(pos)
      if isLemma(entry)
      text      = entry.word.trim
      if text.nonEmpty && !text.contains(" ")
      parsedPos = partOfSpeechOf(pos)
    } yield ParsedWord(language, text, parsedPos, genderOf(language, parsedPos, entry.tags.getOrElse(Nil)))
  }

  /** The pairs this entry asserts directly.
    *
    * Only English entries carry a `translations` array, and each of its rows names a language and a word. The target's
    * part of speech is taken from the English headword — Wiktionary does not repeat it in the translation table — and
    * its gender from the row's own tags, which is where `die Katze` gets its article.
    */
  def pairsOf(entry: RawEntry): List[ParsedPair] = {
    wordOf(entry) match {
      case None       =>
        Nil
      case Some(word) =>
        entry.translations.getOrElse(Nil).flatMap { row =>
          for {
            code     <- row.code
            language <- WordLanguage.fromString(code)
            if language != word.language
            text     <- row.word.map(_.trim)
            if text.nonEmpty && !text.contains(" ")
            gender    = genderOf(language, word.partOfSpeech, row.tags.getOrElse(Nil))
          } yield ParsedPair(word, ParsedWord(language, text, word.partOfSpeech, gender), row.sense)
        }
    }
  }

  /** Rows in `forms[]` that are template scaffolding rather than an actual word form: a declension/conjugation table's
    * own header cell (`table-tags`), the name of the inflection template that produced the table
    * (`inflection-template`), or a Hungarian vowel-harmony/stem-class label (`class`) that names how the word inflects
    * rather than a form of the word itself.
    */
  private val metaFormTags: Set[String] = Set("table-tags", "inflection-template", "class")

  /** `"-"` is the dump's own placeholder for "this word has no such form" (a mass noun with no plural, say), and a
    * space marks a periphrastic construction or, more often on inspection, a corrupted entry — the same rule [[wordOf]]
    * and [[pairsOf]] already apply to word text applies here too.
    */
  private def isUsableForm(form: RawForm): Boolean = {
    val tags = form.tags.getOrElse(Nil)
    val text = form.form.trim
    tags.nonEmpty &&
    !tags.exists(tag => metaFormTags.contains(tag.toLowerCase)) &&
    text.nonEmpty && text != "-" && !text.contains(" ")
  }

  /** The forms this entry's own `forms[]` array states, keyed to the entry as a lemma. `None` if the entry itself is
    * not a word this application stores ([[wordOf]] already decided that); a form-of page such as `Häuser` has no
    * meaningful `forms[]` of its own and is excluded the same way.
    *
    * Gender is read from each form's own tags rather than inherited from the lemma: a German plural form's tags never
    * repeat `masculine`/`feminine`/`neuter`, so inheriting the lemma's gender would wrongly stamp `das` onto `Häuser`,
    * which grammatically takes `die` in every case. Part of speech, on the other hand, is inherited — a plural is still
    * a noun, and the dump does not repeat it per form.
    */
  def formsOf(entry: RawEntry): List[ParsedForm] = {
    wordOf(entry) match {
      case None        =>
        Nil
      case Some(lemma) =>
        entry.forms.getOrElse(Nil).filter(isUsableForm).map { raw =>
          val tags   = raw.tags.getOrElse(Nil)
          val gender = genderOf(lemma.language, lemma.partOfSpeech, tags)
          val word   = ParsedWord(lemma.language, raw.form.trim, lemma.partOfSpeech, gender)
          ParsedForm(lemma, word, ParsedForm.relationOf(tags))
        }
    }
  }

  /** Both halves of one line: the entry as a word, and whatever it says about other languages and its own forms.
    *
    * A line that decodes to nothing usable is silently skipped rather than failing the import. The dump has millions of
    * entries and a handful of them are malformed; stopping on one would mean nobody ever finishes an import.
    */
  def parse(line: String): ParsedEntry = {
    line.fromJson[RawEntry] match {
      case Left(_)      =>
        ParsedEntry(None, Nil, Nil)
      case Right(entry) =>
        ParsedEntry(wordOf(entry), pairsOf(entry), formsOf(entry))
    }
  }

  /** A cheap test applied before the JSON parser, because the parser is the expensive part and 95% of the dump is
    * languages this application does not hold. A false positive costs one wasted decode; a false negative is
    * impossible, since the field is always present as this exact substring.
    */
  def mayConcern(line: String, languages: Set[WordLanguage]): Boolean = {
    languages.exists(language => line.contains("\"lang_code\": \"" + WordLanguage.code(language) + "\"")) ||
    languages.exists(language => line.contains("\"lang_code\":\"" + WordLanguage.code(language) + "\""))
  }
}
