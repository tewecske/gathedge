package gathedge.shared.domain

import zio.json.*

/** How much of a language's definite-article system one play asks for. Stored on `game_plays.article_mode`, picked
  * fresh on the setup screen like every other [[gathedge.shared.dto.GameVariantDto]] field.
  *
  * The mode decides what the article picker '''offers''', never what the game '''accepts''': in both [[All]] and
  * [[FormSpecific]] the answer a play grades against is the article the word's own declension cell takes, so the same
  * dative plural is `den Sachen` either way. What changes is how much help the picker gives — the full keyboard, or
  * only the buttons that cell allows.
  *
  * Replaces the `includeDefiniteArticles` boolean this column grew out of: [[All]] is what `true` meant and [[Off]]
  * what `false` did, with [[FormSpecific]] the new middle setting.
  */
enum ArticleMode derives JsonCodec, CanEqual {
  case All,
    FormSpecific,
    Off
}

object ArticleMode {

  /** What a play gets when the caller names no mode — the same article behaviour every play had before this setting
    * existed.
    */
  val default: ArticleMode = All

  val all: List[ArticleMode] = List(All, FormSpecific, Off)

  /** What `game_plays.article_mode` stores. Written out rather than derived from `toString`, the same reasoning
    * [[WordPreference.code]] follows.
    *
    * [[Off]] is spelled `"none"` because that is the word the setup screen shows. The case is not called `None`, which
    * would shadow `scala.None` in every file that matches on this enum.
    */
  def code(mode: ArticleMode): String = {
    mode match {
      case All          =>
        "all"
      case FormSpecific =>
        "form"
      case Off          =>
        "none"
    }
  }

  def fromString(value: String): Option[ArticleMode] = {
    all.find(mode => code(mode) == value.toLowerCase)
  }

  /** Whether a play under this mode shows an article at all — the one question [[Off]] answers differently from the
    * other two.
    */
  def showsArticles(mode: ArticleMode): Boolean = {
    mode match {
      case Off                =>
        false
      case All | FormSpecific =>
        true
    }
  }
}
