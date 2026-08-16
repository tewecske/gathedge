package gathedge.frontend.components

import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{AnswerOutcome, PartOfSpeech, WordLanguage}
import gathedge.shared.i18n.UiKeys

/** How the enums and stored codes that reach a screen get worded.
  *
  * These used to be an `AuditAction` constant printed raw and a `LoginOutcome.display` — identifiers leaking into the
  * interface, untranslated in every language. They live here rather than in `shared` because wording one needs the
  * catalog, and the catalog is the browser's. A feature that puts an enum on a screen adds its match here.
  *
  * '''Only the label is translated.''' Wherever one of these sits in a `<select>`, the `option`'s `value` stays the
  * enum's `toString` or the stored code: that is what `controlled(...)` round-trips and what the API is sent.
  */
object Labels {

  /** Keyed off the stored string rather than matched exhaustively, because `login_attempts.outcome` is a plain column
    * and a row written by a newer build must still render. An unknown code falls back to itself, which is the same rule
    * the `LoginOutcome.display` this replaced followed.
    */
  def loginOutcome(outcome: String): String = {
    translatedOr(UiKeys.loginOutcomePrefix + outcome, outcome)
  }

  /** Same arrangement for `audit_log.action`, and for the same reason. */
  def auditAction(action: String): String = {
    translatedOr(UiKeys.auditActionPrefix + action, action)
  }

  /** The study languages. Matched exhaustively rather than by suffix, since these are an enum in `shared` rather than a
    * stored code that a newer build might widen.
    *
    * Note what is *not* here: the German article. `der`/`die`/`das` is part of the word being learned, so it is
    * rendered as written and never translated.
    */
  def language(language: WordLanguage): String = {
    I18n.t(UiKeys.languagePrefix + WordLanguage.code(language))
  }

  def partOfSpeech(pos: PartOfSpeech): String = {
    pos match {
      case PartOfSpeech.Noun      =>
        I18n.t(UiKeys.posNoun)
      case PartOfSpeech.Verb      =>
        I18n.t(UiKeys.posVerb)
      case PartOfSpeech.Adjective =>
        I18n.t(UiKeys.posAdjective)
      case PartOfSpeech.Adverb    =>
        I18n.t(UiKeys.posAdverb)
      case PartOfSpeech.Other     =>
        I18n.t(UiKeys.posOtherKind)
    }
  }

  /** Where a translation came from: what the dictionary states, what was inferred through English, what a reader typed.
    * Keyed off the stored string, so a row written by a newer build still renders.
    */
  def translationOrigin(origin: String): String = {
    translatedOr(UiKeys.originPrefix + origin, origin)
  }

  /** How one answer on a game results screen turned out. Matched exhaustively rather than by suffix, like [[language]]
    * — `AnswerOutcome` is a fixed shared enum, not a stored code a newer build might widen.
    */
  def gameOutcome(outcome: AnswerOutcome): String = {
    outcome match {
      case AnswerOutcome.Correct =>
        I18n.t(UiKeys.gameInstanceOutcomeCorrect)
      case AnswerOutcome.Typo    =>
        I18n.t(UiKeys.gameInstanceOutcomeTypo)
      case AnswerOutcome.Wrong   =>
        I18n.t(UiKeys.gameInstanceOutcomeWrong)
    }
  }

  private def translatedOr(key: String, fallback: String): String = {
    I18n.get(key).getOrElse(fallback)
  }
}
