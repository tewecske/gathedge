package gathedge.shared.domain

import zio.json.*

/** A language a word can be learned in.
  *
  * Deliberately not [[Locale]]: that enum is the set of languages the *interface* is translated into, and German is not
  * one of them. A page rendered in Hungarian teaching German vocabulary needs both vocabularies at once, so conflating
  * them would make adding a fourth study language a translation project.
  *
  * Everything a language does *grammatically* — whether it has genders, which article each takes, whether its nouns are
  * capitalized — lives in [[LanguageProfile]] rather than in a `match` on this enum. Adding a language is a case here
  * plus an entry there.
  */
enum WordLanguage derives JsonCodec, CanEqual {
  case En,
    De,
    Es,
    Hu
}

object WordLanguage {

  val all: List[WordLanguage] = List(En, De, Es, Hu)

  /** Lower-case ISO 639-1 form: what `words.language` stores and what a query parameter carries. Written out rather
    * than derived from `toString`, so renaming a case cannot silently orphan every stored row — the rule
    * [[Locale.languageCode]] follows for the same reason.
    */
  def code(language: WordLanguage): String = {
    language match {
      case En =>
        "en"
      case De =>
        "de"
      case Es =>
        "es"
      case Hu =>
        "hu"
    }
  }

  def fromString(value: String): Option[WordLanguage] = {
    value.toLowerCase match {
      case "en" =>
        Some(En)
      case "de" =>
        Some(De)
      case "es" =>
        Some(Es)
      case "hu" =>
        Some(Hu)
      case _    =>
        None
    }
  }

  extension (language: WordLanguage) {
    def wireCode: String = code(language)
  }
}

/** What kind of word this is.
  *
  * Two words spelled the same with different parts of speech are two entries — `laufen` the verb and `Laufen` the noun
  * are not one row with two meanings — which is what keeps a translation attached to the sense it belongs to.
  *
  * [[Phrase]] is the odd one: not a part of speech at all, but the same kind of discriminator, and it earns its place
  * here rather than in a column of its own because `part_of_speech` is already in `words`' identity key. An imported
  * `guten Tag` is therefore one entry that cannot collide with the words it is made of. A tabular import assigns it
  * (see `shared.parsing.WordCell`); nothing derives it from a word already stored.
  */
enum PartOfSpeech derives JsonCodec, CanEqual {
  case Noun,
    Verb,
    Adjective,
    Adverb,
    Phrase,
    Other
}

object PartOfSpeech {

  val all: List[PartOfSpeech] = List(Noun, Verb, Adjective, Adverb, Phrase, Other)

  /** What `words.part_of_speech` stores. Also what the dictionary importer maps wiktextract's much longer vocabulary
    * onto, everything it does not recognise becoming [[Other]].
    */
  def code(pos: PartOfSpeech): String = {
    pos match {
      case Noun      =>
        "noun"
      case Verb      =>
        "verb"
      case Adjective =>
        "adjective"
      case Adverb    =>
        "adverb"
      case Phrase    =>
        "phrase"
      case Other     =>
        "other"
    }
  }

  def fromString(value: String): Option[PartOfSpeech] = {
    all.find(pos => code(pos) == value.toLowerCase)
  }

  extension (pos: PartOfSpeech) {
    def wireCode: String = code(pos)
  }
}

/** Which words a listing shows, narrowed by whether they carry a recorded translation.
  *
  * `HasTarget` and `HasAny` answer two different questions: whether the word is fully useful for the direction being
  * browsed right now, or whether it has been translated into *something* — the wider check a reader chasing dictionary
  * gaps wants, since a word translated only into a third language is still worth reviewing before adding one more.
  */
enum TranslationFilter derives JsonCodec, CanEqual {
  case All, HasTarget, HasAny
}

object TranslationFilter {

  val all: List[TranslationFilter] = List(All, HasTarget, HasAny)

  def code(filter: TranslationFilter): String = {
    filter match {
      case All       =>
        "all"
      case HasTarget =>
        "target"
      case HasAny    =>
        "any"
    }
  }

  def fromString(value: String): Option[TranslationFilter] = {
    all.find(filter => code(filter) == value.toLowerCase)
  }
}

/** The grammatical gender of a noun — the half of the word a learner actually has to memorise.
  *
  * This names the gender, never the article: `der` is what German does with [[Masculine]] and `el` is what Spanish
  * does, and both belong to [[LanguageProfile]]. Which of these cases a language actually uses is likewise the
  * profile's answer, not this enum's — [[all]] is the union across every language and is the wrong list to render a
  * picker from.
  *
  * Gender is part of a word's identity, so `der See` (the lake) and `die See` (the sea) are two entries rather than one
  * ambiguous row.
  */
enum Gender derives JsonCodec, CanEqual {
  case Masculine,
    Feminine,
    Neuter
}

object Gender {

  /** Every gender any supported language has. A per-language list is `LanguageProfile.of(language).genders`. */
  val all: List[Gender] = List(Masculine, Feminine, Neuter)

  /** What `words.gender` stores. Written out rather than derived from `toString`, the same rule [[WordLanguage.code]]
    * follows: renaming a case must not silently orphan every stored row.
    *
    * The column is `NOT NULL` with `''` for "no gender", rather than nullable: a NULL counts as distinct in a UNIQUE
    * index on both dialects, which would let the same word be inserted twice.
    */
  def code(gender: Gender): String = {
    gender match {
      case Masculine =>
        "masculine"
      case Feminine  =>
        "feminine"
      case Neuter    =>
        "neuter"
    }
  }

  def fromString(value: String): Option[Gender] = {
    all.find(gender => code(gender) == value.toLowerCase)
  }

  /** How the column is read: `''` is no gender at all, anything unrecognised likewise.
    *
    * The three German articles are accepted as well, since that is what the column held before genders were named and
    * what a seed file exported before then still carries. Nothing writes them any more.
    */
  def fromColumn(value: String): Option[Gender] = {
    value.toLowerCase match {
      case ""    =>
        None
      case "der" =>
        Some(Masculine)
      case "die" =>
        Some(Feminine)
      case "das" =>
        Some(Neuter)
      case other =>
        fromString(other)
    }
  }

  /** How the column is written. */
  def toColumn(gender: Option[Gender]): String = {
    gender.map(code).getOrElse("")
  }

  extension (gender: Gender) {
    def wireCode: String = code(gender)
  }
}

/** One lexical unit, as the browser sees it.
  *
  * Carries no ownership: who added a word and who has tagged it are separate questions, answered by the tags a listing
  * joins in per reader. A word typed by one user is visible to every other, which is the whole point of a shared
  * dictionary — what is private is which words somebody has *collected*.
  */
final case class Word(
  id: Long,
  language: WordLanguage,
  text: String,
  partOfSpeech: PartOfSpeech,
  gender: Option[Gender],
) derives JsonCodec

object Word {

  /** How a word is written on screen: a gendered noun with the article its language gives that gender, anything else as
    * it stands. Takes the raw `language`, `text` and `gender` column values directly, for callers holding a DB row
    * (e.g. `WordRow`) rather than a full [[Word]] — the game feature is the first of these, needing no other field to
    * show or score a word.
    */
  def displayText(languageColumn: String, text: String, genderColumn: String): String = {
    val language = WordLanguage.fromString(languageColumn).getOrElse(WordLanguage.En)
    LanguageProfile.of(language).display(text, Gender.fromColumn(genderColumn))
  }

  /** How a word is written on screen: a gendered noun with its article, anything else as it stands. */
  def display(word: Word): String = {
    LanguageProfile.of(word.language).display(word.text, word.gender)
  }

  extension (word: Word) {
    def displayText: String = display(word)
  }
}
