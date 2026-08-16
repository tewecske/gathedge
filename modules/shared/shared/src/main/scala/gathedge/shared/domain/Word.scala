package gathedge.shared.domain

import zio.json.*

/** A language a word can be learned in.
  *
  * Deliberately not [[Locale]]: that enum is the set of languages the *interface* is translated into, and German is not
  * one of them. A page rendered in Hungarian teaching German vocabulary needs both vocabularies at once, so conflating
  * them would make adding a fourth study language a translation project.
  */
enum WordLanguage derives JsonCodec, CanEqual {
  case En,
    De,
    Hu
}

object WordLanguage {

  val all: List[WordLanguage] = List(En, De, Hu)

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
  */
enum PartOfSpeech derives JsonCodec, CanEqual {
  case Noun,
    Verb,
    Adjective,
    Adverb,
    Other
}

object PartOfSpeech {

  val all: List[PartOfSpeech] = List(Noun, Verb, Adjective, Adverb, Other)

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

/** The definite article a German noun takes, which is the half of the word a learner actually has to memorise.
  *
  * Only German nouns carry one. It is part of a word's identity, so `der See` (the lake) and `die See` (the sea) are
  * two entries rather than one ambiguous row.
  */
enum Gender derives JsonCodec, CanEqual {
  case Der,
    Die,
    Das
}

object Gender {

  val all: List[Gender] = List(Der, Die, Das)

  /** The article itself, which is also what `words.gender` stores. The column is `NOT NULL` with `''` for "no gender",
    * rather than nullable: a NULL counts as distinct in a UNIQUE index on both dialects, which would let the same word
    * be inserted twice.
    */
  def article(gender: Gender): String = {
    gender match {
      case Der =>
        "der"
      case Die =>
        "die"
      case Das =>
        "das"
    }
  }

  def fromString(value: String): Option[Gender] = {
    all.find(gender => article(gender) == value.toLowerCase)
  }

  /** How the column is read: `''` is no gender at all, anything unrecognised likewise. */
  def fromColumn(value: String): Option[Gender] = {
    if (value.isEmpty)
      None
    else
      fromString(value)
  }

  /** How the column is written. */
  def toColumn(gender: Option[Gender]): String = {
    gender.map(article).getOrElse("")
  }

  extension (gender: Gender) {
    def word: String = article(gender)
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

  /** How a word is written on screen: a German noun with its article, anything else as it stands. Takes the raw text
    * and the raw `gender` column value directly, for callers holding a DB row (e.g. `WordRow`) rather than a full
    * [[Word]] — the game feature is the first of these, needing no other field to show or score a word.
    */
  def displayText(text: String, genderColumn: String): String = {
    Gender.fromColumn(genderColumn).map(gender => Gender.article(gender) + " " + text).getOrElse(text)
  }

  /** How a word is written on screen: a German noun with its article, anything else as it stands. */
  def display(word: Word): String = {
    displayText(word.text, Gender.toColumn(word.gender))
  }

  extension (word: Word) {
    def displayText: String = display(word)
  }
}
