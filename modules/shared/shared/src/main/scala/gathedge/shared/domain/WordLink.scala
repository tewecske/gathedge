package gathedge.shared.domain

import zio.json.*

/** How one word relates to another word that is not one of its forms: a noun's counterpart of another gender
  * (`Künstler` and `Künstlerin`), or a diminutive or augmentative (`Haus` and `Häuschen`). Each is a word in its own
  * right, with its own plural and translations, so neither is hidden as a form of the other.
  *
  * A kind names what the *linked* word is to the word it is read from. `Künstler` links to `Künstlerin` as
  * [[Feminine]], and `Künstlerin` links back to `Künstler` as [[Masculine]]. `Haus` links to `Häuschen` as
  * [[Diminutive]], and `Häuschen` links back to `Haus` as [[DiminutiveOf]].
  */
enum WordLinkKind derives JsonCodec, CanEqual {
  case Feminine, Masculine, Neuter, Diminutive, Augmentative, DiminutiveOf, AugmentativeOf
}

object WordLinkKind {

  /** The `word_links.kind` column value. */
  def code(kind: WordLinkKind): String = {
    kind match {
      case Feminine       =>
        "feminine"
      case Masculine      =>
        "masculine"
      case Neuter         =>
        "neuter"
      case Diminutive     =>
        "diminutive"
      case Augmentative   =>
        "augmentative"
      case DiminutiveOf   =>
        "diminutive-of"
      case AugmentativeOf =>
        "augmentative-of"
    }
  }

  def fromCode(value: String): Option[WordLinkKind] = values.find(kind => code(kind) == value)

  /** The gender a counterpart kind names; `None` for a diminutive or augmentative. */
  def genderOf(kind: WordLinkKind): Option[Gender] = {
    kind match {
      case Feminine  =>
        Some(Gender.Feminine)
      case Masculine =>
        Some(Gender.Masculine)
      case Neuter    =>
        Some(Gender.Neuter)
      case _         =>
        None
    }
  }

  /** The kind read back from the linked word: `Häuschen` is the [[Diminutive]] of `Haus`, so `Haus` is what `Häuschen`
    * is the [[DiminutiveOf]]. A counterpart reads back as the gender of the word it is read from (`wordGender`). Where
    * that is not known, a feminine counterpart's base is masculine, and any other's is feminine.
    */
  def inverse(kind: WordLinkKind, wordGender: Option[Gender]): WordLinkKind = {
    kind match {
      case Diminutive     =>
        DiminutiveOf
      case DiminutiveOf   =>
        Diminutive
      case Augmentative   =>
        AugmentativeOf
      case AugmentativeOf =>
        Augmentative
      case counterpart    =>
        val base = if (counterpart == Feminine) Gender.Masculine else Gender.Feminine
        ofGender(wordGender.getOrElse(base))
    }
  }

  /** The kinds a reader may link a word of `language` and `partOfSpeech` with. A counterpart is a noun's, and only of a
    * gender the language has. A diminutive or an augmentative may be of any word.
    */
  def offered(language: WordLanguage, partOfSpeech: PartOfSpeech): List[WordLinkKind] = {
    val genders = LanguageProfile.of(language).genders
    values.toList.filter(kind => {
      genderOf(kind) match {
        case Some(gender) => partOfSpeech == PartOfSpeech.Noun && genders.contains(gender)
        case None         => true
      }
    })
  }

  /** The counterpart kind for a gender: the linked word is a feminine noun, so the link is [[Feminine]]. */
  def ofGender(gender: Gender): WordLinkKind = {
    gender match {
      case Gender.Masculine =>
        Masculine
      case Gender.Feminine  =>
        Feminine
      case Gender.Neuter    =>
        Neuter
    }
  }
}
