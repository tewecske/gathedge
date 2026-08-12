package gathedge.frontend.i18n

import gathedge.shared.domain.Locale
import zio.test._

/** The precedence rule, as a table.
  *
  * This is the single most breakable decision in the whole feature, and the failure mode is quiet: getting it backwards
  * means someone who deliberately opens `/en/groups` is thrown to `/hu/groups` the moment they sign in, and nothing
  * errors. `decide` is pure precisely so every row of the rule can be stated here.
  */
object LocaleSyncSpec extends ZIOSpecDefault {

  import LocaleSync.Action

  def spec = {
    suite("LocaleSync.decide")(
      test("agreement needs no action, however the URL got its locale") {
        assertTrue(
          LocaleSync.decide(Locale.Hu, account = Locale.Hu, urlWasImplicit = false) == Action.Keep,
          LocaleSync.decide(Locale.Hu, account = Locale.Hu, urlWasImplicit = true) == Action.Keep,
        )
      },
      // The account decides only when the app had to guess — a new browser landing on a bare `/`.
      test("a guessed URL locale gives way to the account's") {
        assertTrue(
          LocaleSync.decide(Locale.En, account = Locale.Hu, urlWasImplicit = true) == Action.Navigate(Locale.Hu),
          LocaleSync.decide(Locale.Hu, account = Locale.En, urlWasImplicit = true) == Action.Navigate(Locale.En),
        )
      },
      // The rule that is worth the whole enum: an explicit prefix is the visitor's stated choice.
      // Being redirected out of the URL you deliberately opened is a bug, so this is never `Navigate`.
      test("an explicit URL locale wins over the account's, and is adopted as the new preference") {
        assertTrue(
          LocaleSync.decide(Locale.En, account = Locale.Hu, urlWasImplicit = false) == Action.Persist(Locale.En),
          LocaleSync.decide(Locale.Hu, account = Locale.En, urlWasImplicit = false) == Action.Persist(Locale.Hu),
        )
      },
      test("no disagreement is ever resolved by navigating away from an explicit prefix") {
        val outcomes = {
          for {
            url     <- Locale.all
            account <- Locale.all
          } yield LocaleSync.decide(url, account, urlWasImplicit = false)
        }
        assertTrue(outcomes.forall {
          case Action.Navigate(_) =>
            false
          case _                  =>
            true
        })
      },
    )
  }
}
