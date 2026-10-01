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
