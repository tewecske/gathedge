package gathedge.frontend.components

import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{AnswerOutcome, GrammarCategory, PartOfSpeech, TranslationFilter, WordLanguage, WordPreference}
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

  /** The word list's and bulk upload's shared translation-presence filter. Matched exhaustively, like [[language]] —
    * `TranslationFilter` is a fixed shared enum, not a stored code a newer build might widen.
    */
  def translationFilter(filter: TranslationFilter): String = {
    filter match {
      case TranslationFilter.All       =>
        I18n.t(UiKeys.wordsTranslationFilterAll)
      case TranslationFilter.HasTarget =>
        I18n.t(UiKeys.wordsTranslationFilterTarget)
      case TranslationFilter.HasAny    =>
        I18n.t(UiKeys.wordsTranslationFilterAny)
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

  /** One tag from a `word_forms.relation` string (e.g. the `"dative"` in `"dative,definite,plural"`), worded for the
    * reader. Falls back to a plain hyphens-to-spaces, title-cased rendering of the token itself for anything
    * `UiKeys.grammarTagPrefix` does not yet name — `relation` is deliberately not a closed enum (see `WordFormRow`'s
    * doc comment), and a future wiktextract dump can carry a tag this catalog has never seen. That fallback is a plain
    * string transform, not a translation: it calls `I18n.get` only the once `translatedOr` already does, so an unlisted
    * tag registers nothing in `UiKeys.all` and cannot trip `MessagesSpec`'s exhaustiveness checks.
    */
  def grammarTag(tag: String): String = {
    translatedOr(UiKeys.grammarTagPrefix + tag, humanizeTag(tag))
  }

  /** A whole `relation` string, each constituent tag resolved through [[grammarTag]] and joined the way the word list
    * and detail page both show it: `"dative,definite,plural"` becomes `"dative · definite · plural"`.
    */
  def grammarRelation(relation: String): String = {
    relation.split(',').iterator.filter(_.nonEmpty).map(grammarTag).mkString(" · ")
  }

  /** The heading over one group of a lemma's Forms section. Matched exhaustively, like [[language]] — `GrammarCategory`
    * is a fixed shared enum, not a stored code a newer build might widen.
    */
  def grammarCategory(category: GrammarCategory): String = {
    category match {
      case GrammarCategory.PluralCase          =>
        I18n.t(UiKeys.wordDetailFormsCategoryPluralCase)
      case GrammarCategory.Tense               =>
        I18n.t(UiKeys.wordDetailFormsCategoryTense)
      case GrammarCategory.Comparison          =>
        I18n.t(UiKeys.wordDetailFormsCategoryComparison)
      case GrammarCategory.Diminutive          =>
        I18n.t(UiKeys.wordDetailFormsCategoryDiminutive)
      case GrammarCategory.AlternativeSpelling =>
        I18n.t(UiKeys.wordDetailFormsCategoryAlternative)
      case GrammarCategory.Other               =>
        I18n.t(UiKeys.wordDetailFormsCategoryOther)
    }
  }

  /** The picker's own worded option text, reused here so the played variant reads identically on the results screens —
    * `GameInstancePage`'s inline `<select>` for [[WordPreference]] is the source this mirrors. Matched exhaustively,
    * like [[language]] — `WordPreference` is a fixed shared enum, not a stored code a newer build might widen.
    */
  def wordPreference(preference: WordPreference): String = {
    preference match {
      case WordPreference.All          =>
        I18n.t(UiKeys.gameInstancePreferenceAll)
      case WordPreference.Unplayed     =>
        I18n.t(UiKeys.gameInstancePreferenceUnplayed)
      case WordPreference.MostMistakes =>
        I18n.t(UiKeys.gameInstancePreferenceMostMistakes)
    }
  }

  private def humanizeTag(tag: String): String = {
    tag.split('-').filter(_.nonEmpty).map(_.capitalize).mkString(" ")
  }

  private def translatedOr(key: String, fallback: String): String = {
    I18n.get(key).getOrElse(fallback)
  }
}
