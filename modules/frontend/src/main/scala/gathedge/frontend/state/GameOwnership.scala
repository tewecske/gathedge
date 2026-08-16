package gathedge.frontend.state

import org.scalajs.dom

/** Which games this browser created, so `GameInstancePage` knows to offer the rename control.
  *
  * `GameDetail` deliberately carries no owner field — see `GameRoutes`'s doc comment: "a shared game link is readable
  * by anybody" and a reader's ownership is not something a shared link should leak. So there is no clean signal to ask
  * the server for. This is the same shape of problem `WordCollect`'s collect tag solves, and the same fix: remember it
  * in this browser rather than asking the server to tell every reader who owns what.
  *
  * This is a hint, not a security boundary — the same account signed in on a second device sees no edit control, and
  * clearing browser storage forgets it. The actual check still happens server-side on every `PATCH`
  * (`GameFailure.NotOwner` -> 403); [[forget]] is called when that 403 arrives, so a stale or wrong hint does not keep
  * offering a control that never works.
  */
object GameOwnership {

  private val storageKeyPrefix = "games.owner."

  private def storageKey(slug: String): String = storageKeyPrefix + slug

  /** Wrapped like `AppState`'s theme access and `WordCollect`'s collect tag, for the same reason: the storage API
    * throws rather than returns null when the browser has it disabled, and this is only ever a UI hint.
    */
  def markOwned(slug: String): Unit = {
    try dom.window.localStorage.setItem(storageKey(slug), "1")
    catch { case _: Throwable => () }
  }

  def isOwned(slug: String): Boolean = {
    try Option(dom.window.localStorage.getItem(storageKey(slug))).isDefined
    catch { case _: Throwable => false }
  }

  def forget(slug: String): Unit = {
    try dom.window.localStorage.removeItem(storageKey(slug))
    catch { case _: Throwable => () }
  }
}
