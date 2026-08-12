package gathedge.frontend.components

import gathedge.frontend.i18n.I18n
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

  private def translatedOr(key: String, fallback: String): String = {
    I18n.get(key).getOrElse(fallback)
  }
}
