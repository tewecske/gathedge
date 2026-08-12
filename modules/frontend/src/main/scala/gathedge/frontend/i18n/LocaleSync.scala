package gathedge.frontend.i18n

import org.scalajs.dom
import gathedge.shared.domain.Locale

/** Reconciles the language in the URL with the one stored on the account, once `/api/me` has answered.
  *
  * The precedence rule this implements, in full:
  *
  * {{{
  * explicit URL prefix  >  stored users.locale  >  localStorage  >  navigator.language  >  en
  * }}}
  *
  * The first `>` is the one that matters and the easy one to get backwards. A stored preference must '''never''' win
  * over a prefix the visitor actually typed or followed: opening `/en/settings` deliberately, signing in, and being
  * thrown to `/hu/settings` is a bug, not a feature. So the account's language only decides anything when the URL had
  * no prefix and the boot script had to guess — which is exactly what `CurrentLocale.wasImplicit` records.
  *
  * When the URL *was* explicit and disagrees with the account, the URL wins and becomes the new stored preference. That
  * keeps the picker, `localStorage` and the column from drifting apart after someone switches language by editing the
  * address bar.
  */
object LocaleSync {

  /** What to do about a disagreement. Separated from the doing so the rule above can be stated as a table in a test. */
  enum Action derives CanEqual {

    /** URL and account agree; there is nothing to reconcile. */
    case Keep

    /** The URL did not say, so the account decides: reload under its language. */
    case Navigate(to: Locale)

    /** The URL said, so it wins and is adopted as the account's new preference. */
    case Persist(to: Locale)
  }

  def decide(url: Locale, account: Locale, urlWasImplicit: Boolean): Action = {
    if (url == account) {
      Action.Keep
    } else if (urlWasImplicit) {
      Action.Navigate(account)
    } else {
      Action.Persist(url)
    }
  }

  /** Mirrors the account's language into `localStorage` and returns what else needs doing.
    *
    * The mirror is unconditional because it is what the boot script reads on the *next* first visit — it is the only
    * way a prefix-less URL can land in the right language before any request has been made.
    */
  def reconcile(account: Locale): Action = {
    val action = decide(CurrentLocale.value, account, CurrentLocale.wasImplicit)
    action match {
      case Action.Keep            =>
        CurrentLocale.store(account)
      case Action.Navigate(to)    =>
        CurrentLocale.store(to)
        // `replace`, not `assign`: the guessed-wrong URL should not be a back-button stop.
        dom.window.location.replace(CurrentLocale.urlUnder(to))
      case Action.Persist(chosen) =>
        CurrentLocale.store(chosen)
    }
    action
  }
}
