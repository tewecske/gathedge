package gathedge.backend.tools

import gathedge.backend.service.CommonsAudio
import gathedge.shared.domain.{Gender, LanguageProfile, PartOfSpeech, WordLanguage}
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
  final case class RawFormOf(word: Option[String] = None) derives JsonDecoder

  final case class RawSense(
    glosses: Option[List[String]] = None,
    tags: Option[List[String]] = None,
    form_of: Option[List[RawFormOf]] = None,
    alt_of: Option[List[RawFormOf]] = None,
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

  /** One row of `sounds[]`. Most rows are IPA or rhymes; only a row with `audio` names a recording, as the Wikimedia
    * Commons file name. `tags` is the accent (`US`, `Berlin`). The dump's own `ogg_url`/`mp3_url` are not read:
    * `CommonsAudio` derives both from the name.
    */
  final case class RawSound(audio: Option[String] = None, tags: Option[List[String]] = None) derives JsonDecoder

  final case class RawEntry(
    word: String,
    lang_code: Option[String] = None,
    pos: Option[String] = None,
    tags: Option[List[String]] = None,
    senses: Option[List[RawSense]] = None,
    translations: Option[List[RawTranslation]] = None,
    forms: Option[List[RawForm]] = None,
    sounds: Option[List[RawSound]] = None,
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

  /** One recording of a word: a Wikimedia Commons file name, and the accent tags comma-joined (`''` for none). */
  final case class ParsedAudio(word: ParsedWord, fileName: String, region: String)

  /** Both halves of one wiktextract line: the entry as a word, what it translates to, what forms it has, and how it
    * sounds.
    */
  final case class ParsedEntry(
    word: Option[ParsedWord],
    pairs: List[ParsedPair],
    forms: List[ParsedForm],
    audio: List[ParsedAudio] = Nil,
  )

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

  /** The gender a noun takes, from the entry's own tags. Read only for a noun in a language that has gender at all: an
    * English noun tagged `masculine` (they exist, for ships and countries) would otherwise become a second, unfindable
    * copy of itself.
    *
    * Most entries state it on the entry itself; some (`Apfelsaft`, no top-level `tags` at all) state it only on a
    * sense. [[wordOf]] passes both, entry tags first, so an entry-level tag still wins when both are present.
    *
    * Every language this app teaches with gender uses the same three wiktextract tag strings, so one map covers them
    * all; a noun-class label this map has never seen (`common-gender`, `masculine-personal`, …) yields no gender rather
    * than a guess.
    */
  def genderOf(language: WordLanguage, pos: PartOfSpeech, tags: List[String]): Option[Gender] = {
    if (!LanguageProfile.of(language).hasGenders || pos != PartOfSpeech.Noun)
      None
    else {
      tags.map(_.toLowerCase).collectFirst {
        case "masculine" =>
          Gender.Masculine
        case "feminine"  =>
          Gender.Feminine
        case "neuter"    =>
          Gender.Neuter
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
      senseTags = entry.senses.getOrElse(Nil).flatMap(_.tags.getOrElse(Nil))
    } yield ParsedWord(language, text, parsedPos, genderOf(language, parsedPos, entry.tags.getOrElse(Nil) ++ senseTags))
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

  /** Rows in `forms[]` that are template scaffolding or metadata rather than an actual word form: a
    * declension/conjugation table's own header cell (`table-tags`), the name of the inflection template that produced
    * the table (`inflection-template`), a Hungarian vowel-harmony/stem-class label (`class`) that names how the word
    * inflects rather than a form of the word itself, or (`auxiliary`) a German conjugation table's note of which verb
    * (`haben`/`sein`) forms that lemma's Perfekt tense — a fact about the lemma, not a form of it. Left unfiltered,
    * `auxiliary` rows make `haben`/`sein` a "form" of nearly every German verb. The composed tenses still teach the
    * auxiliary: `habe gekauft`, `bin gegangen`. `canonical` is the headword again, with an article or a note (`the
    * pip`).
    */
  private val metaFormTags: Set[String] = Set("table-tags", "inflection-template", "class", "auxiliary", "canonical")

  /** A form tagged with one of these is not the standard, current spelling a learner should be taught — a variant form
    * wiktextract records alongside the real one, not a distinct grammatical fact the way `plural`/`dative` are. Dropped
    * the same way `error-*` is: the whole row, since the rest of its tags describe a form of the word that happens to
    * be nonstandard/obsolete/alternative/archaic, not a form worth teaching under a different label.
    */
  private val nonStandardFormTags: Set[String] = Set("nonstandard", "obsolete", "alternative", "archaic")

  /** Tags that say how wiktextract read the cell, not what the form is. They are left out of the relation. */
  private val layoutTags: Set[String] = Set("multiword-construction")

  /** The most words a multi-word form may have. German's longest composed tense is three (`würde gekauft haben`), and a
    * Spanish reflexive adds one (`me habría quejado`). A longer cell is a note.
    */
  private val maxFormWords = 4

  /** Words that appear only in the English notes some tables leave in their cells (`definite forms are not used`,
    * `intransitive verb`, `older also: der`). No form in the four languages uses one of them.
    */
  private val noteWords: Set[String] = {
    Set(
      "e.g.",
      "verb",
      "verbs",
      "forms",
      "used",
      "not",
      "are",
      "intransitive",
      "transitive",
      "followed",
      "expressed",
      "same",
      "meaning",
      "older",
      "also",
      "see",
    )
  }

  private val formWord = "[\\p{L}\\p{M}'’-]+".r

  /** One cell can name more than one form: `adva (adván)`, `GUI-jaim (or GUI-im)`, `beleegyezhetve / beleegyezhetvén`,
    * and a cell that ends or starts with a bare `or` (`lennék or`, `or adnók`). Each alternative becomes a form of its
    * own, with the cell's tags. A cell with no such shape is its own single alternative.
    */
  def alternativesOf(text: String): List[String] = {
    val trimmed = text.trim.stripSuffix(" or").stripPrefix("or ").trim
    val parts   = {
      if (trimmed.contains(" / "))
        trimmed.split(" / ").toList
      else {
        trimmed match {
          case s"$first ($second)" if !first.contains("(") =>
            List(first, second.stripPrefix("or "))
          case other                                       =>
            List(other)
        }
      }
    }
    parts.map(_.trim).filter(_.nonEmpty)
  }

  /** Whether one alternative is a form to store. `"-"` is the dump's own placeholder for "this word has no such form"
    * (a mass noun with no plural, say).
    *
    * A one-word form passes as it always has: that keeps `dr.-ok` and `1.ª`, which the dump spells with punctuation. A
    * multi-word form must be words and nothing else, at most [[maxFormWords]] of them, with no word of a note in it.
    * That keeps the composed tenses (`habe gekauft`), a separable verb's main clause (`kaufe ein`), an adjective with
    * its article (`der freie`), a periphrastic comparison (`more free`, `am freiesten`) and a reflexive form (`me
    * quejo`). It keeps out the notes (`e.g. adni fog.`, `Future is expressed with …`).
    *
    * A multi-word predicative row is dropped: it is the lemma after a pronoun and a copula (`er ist frei`), which
    * teaches nothing the lemma does not.
    */
  private def isFormText(text: String, tags: List[String]): Boolean = {
    val words = text.split(' ').toList
    text.nonEmpty && text != "-" && (
      words.size == 1 || (
        words.size <= maxFormWords &&
          words.forall(word => formWord.matches(word)) &&
          !words.exists(word => noteWords.contains(word.toLowerCase)) &&
          !tags.contains("predicative")
      )
    )
  }

  /** A row the dump states cleanly. An `error-*` tag (`error-unrecognized-form`, `error-unknown-tag`, ...) is
    * wiktextract's own admission that it could not classify this table cell. The whole row is dropped, not just the
    * offending tag: the remaining tags on such a row describe an incomplete, unreliable grammatical fact. The one place
    * such rows are still read is [[HungarianTables]], which rebuilds the tags from the cell's position instead.
    */
  private def isCleanRow(tags: List[String]): Boolean = {
    tags.nonEmpty &&
    !tags.exists(metaFormTags.contains) &&
    !tags.exists(_.startsWith("error-")) &&
    !tags.exists(nonStandardFormTags.contains)
  }

  /** A Spanish reflexive form is written with its pronoun (`me quejo`), and the dump gives it the same tags as the
    * plain form (`compro`). The `reflexive` tag tells the two apart.
    */
  private def withReflexive(language: WordLanguage, text: String, tags: List[String]): List[String] = {
    val first = text.takeWhile(_ != ' ').toLowerCase
    if (text.contains(' ') && LanguageProfile.of(language).reflexivePronouns.contains(first)) tags :+ "reflexive"
    else tags
  }

  /** Every `(text, tags)` pair one lemma's `forms[]` array states, before either becomes a word. Tags are lower-cased.
    * A Hungarian table that wiktextract could not read is handed to [[HungarianTables]] whole; every other row is read
    * on its own.
    */
  private def formRowsOf(lemma: ParsedWord, forms: List[RawForm]): List[(String, List[String])] = {
    val rows =
      forms.map(raw => (raw, HungarianTables.canonicalTags(lemma.language, raw.tags.getOrElse(Nil).map(_.toLowerCase))))
    HungarianTables.split(lemma.language, rows).flatMap {
      case HungarianTables.Chunk.Rebuilt(table, potential) =>
        HungarianTables.conjugation(table, potential)
      case HungarianTables.Chunk.Row(raw, tags)            =>
        HungarianTables
          .possessor(lemma.language, raw, tags)
          .orElse(Option.when(isCleanRow(tags))(tags))
          .toList
          .flatMap { clean =>
            alternativesOf(raw.form)
              .filter(text => isFormText(text, clean))
              .map(text => (text, withReflexive(lemma.language, text, clean.filterNot(layoutTags.contains))))
          }
    }
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
        formRowsOf(lemma, entry.forms.getOrElse(Nil)).map { case (text, tags) =>
          val gender = genderOf(lemma.language, lemma.partOfSpeech, tags)
          val word   = ParsedWord(lemma.language, text, lemma.partOfSpeech, gender)
          ParsedForm(lemma, word, ParsedForm.relationOf(tags))
        }
    }
  }

  /** Tags that only say "this page is a form-of page" rather than naming which form -- the real relation, if any, sits
    * alongside them (`infinitive`, `plural`, ...).
    */
  private val formOfMarkerTags: Set[String] = Set("form-of", "inflection-of")

  /** `alt-of` marks a spelling variant (`daß` alt of `dass`) rather than a grammatical form, which is why it is not in
    * [[formOfMarkerTags]] by default -- [[skippedSenseTags]] already excludes it from ever being taught as its own
    * lemma. [[formOfPageOf]] only reads it when `includeAltOf` says so.
    */
  private val altOfMarkerTag: String = "alt-of"

  /** A form stated the other way around from [[formsOf]]: instead of the lemma's own `forms[]` naming this spelling,
    * the inflected word has its own page (`hozni`) whose sense names what it is a form of. This is the only path for a
    * form wiktextract's own table logic could not classify -- `hozni` as a row in `hoz`'s conjugation table carries
    * `error-unrecognized-form` and [[isCleanRow]] drops it, but `hozni`'s own page states the same fact cleanly.
    *
    * `includeAltOf` additionally treats `alt-of` (spelling variants, not grammatical forms) as a marker tag and reads
    * `sense.alt_of` alongside `sense.form_of`. Off by default: a spelling variant is a different kind of relation than
    * a grammatical form, and callers that want it opt in explicitly.
    *
    * The lemma's part of speech is assumed to match this page's own `pos` (true for every real case), and its gender is
    * never known here, so it is left `None`. That matches every real case except a German noun whose own `forms[]` also
    * failed to capture this spelling -- rare enough, and caught only as a missed link rather than a wrong one, since
    * [[DictionaryImport]] resolves a form's lemma by exact key and drops the edge on a mismatch.
    */
  def formOfPageOf(entry: RawEntry, includeAltOf: Boolean = false): List[ParsedForm] = {
    val markerTags = if (includeAltOf) formOfMarkerTags + altOfMarkerTag else formOfMarkerTags
    for {
      code      <- entry.lang_code.toList
      language  <- WordLanguage.fromString(code).toList
      pos       <- entry.pos.toList
      if isUsablePos(pos)
      text       = entry.word.trim
      if text.nonEmpty && !text.contains(" ")
      parsedPos  = partOfSpeechOf(pos)
      sense     <- entry.senses.getOrElse(Nil)
      tags       = sense.tags.getOrElse(Nil)
      if tags.exists(tag => markerTags.contains(tag.toLowerCase))
      // Same rule as `isCleanRow`: a form-of page tagged nonstandard/obsolete/alternative/archaic is a
      // spelling variant, not a grammatical fact worth teaching. `error-*` is deliberately not filtered here
      // -- this path is how a form wiktextract's own table logic could not classify still gets imported.
      if !tags.exists(tag => nonStandardFormTags.contains(tag.toLowerCase))
      relation   = tags.filterNot(tag => markerTags.contains(tag.toLowerCase))
      if relation.nonEmpty
      lemmaRow  <- sense.form_of.getOrElse(Nil) ++ sense.alt_of.getOrElse(Nil)
      lemmaText <- lemmaRow.word.map(_.trim).toList
      if lemmaText.nonEmpty && !lemmaText.contains(" ")
    } yield {
      val gender = genderOf(language, parsedPos, tags)
      val lemma  = ParsedWord(language, lemmaText, parsedPos, None)
      val form   = ParsedWord(language, text, parsedPos, gender)
      ParsedForm(lemma, form, ParsedForm.relationOf(relation))
    }
  }

  /** `word_audio`'s two text columns are this wide. */
  private val maxAudioColumn = 255

  /** The recordings this entry names, keyed to the entry as a word. `None` from [[wordOf]] means none: a form-of page
    * (`Häuser`) is not a word this application stores on its own.
    *
    * The name is stored in MediaWiki's normal form, so two spellings of one file (`en-us-x.ogg`, `En-us-x.ogg`) are one
    * row. A name that holds a tab or a line break could not survive the seed file, and one longer than the column could
    * not be stored. No real name is either, so such a name is dropped rather than escaped or cut.
    */
  def audioOf(entry: RawEntry): List[ParsedAudio] = {
    wordOf(entry) match {
      case None       =>
        Nil
      case Some(word) =>
        entry.sounds
          .getOrElse(Nil)
          .flatMap { sound =>
            sound.audio
              .map(CommonsAudio.normalise)
              .filter(name =>
                name.nonEmpty && name.length <= maxAudioColumn && !name.exists(c => c == '\t' || c == '\n')
              )
              .map { name =>
                val region = sound.tags.getOrElse(Nil).map(_.trim).filter(_.nonEmpty).mkString(", ")
                ParsedAudio(word, name, region.take(maxAudioColumn))
              }
          }
          .distinctBy(_.fileName)
    }
  }

  /** Both halves of one line: the entry as a word, and whatever it says about other languages and its own forms.
    *
    * A line that decodes to nothing usable is silently skipped rather than failing the import. The dump has millions of
    * entries and a handful of them are malformed; stopping on one would mean nobody ever finishes an import.
    */
  def parse(line: String, includeAltOf: Boolean = false): ParsedEntry = {
    line.fromJson[RawEntry] match {
      case Left(_)      =>
        ParsedEntry(None, Nil, Nil)
      case Right(entry) =>
        ParsedEntry(
          wordOf(entry),
          pairsOf(entry),
          formsOf(entry) ++ formOfPageOf(entry, includeAltOf),
          audioOf(entry),
        )
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
