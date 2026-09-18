package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.ProvidersResponse
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.{Failure, Success}

/** Copy-link, Web Share, QR code and one button per share page for one URL — the row `GameInstancePage` and
  * `GroupDetailPage` both put next to whatever they are sharing (a quiz's own URL for the former, an invite link built
  * from a code for the latter).
  *
  * The per-network buttons are [[ShareTarget]]: each opens that site's own share page with the link already in it, so
  * nothing here talks to a network itself. The Web Share button stays in front of them, since a phone's own share sheet
  * reaches every app installed on it rather than the seven listed here.
  *
  * `link`/`shareTitle` are read fresh on every click rather than captured once, since a group's invite link can change
  * under the page (regenerating the code) the way a game's own URL never does — see [[resetQr]] for the same reason on
  * the QR cache.
  *
  * `generateQr` is threaded in by the caller rather than called directly — see `GameInstancePage.render`'s own doc
  * comment: it keeps the `qrcode` npm package's `@JSImport` out of this component's reachable graph under the Scala.js
  * test linker, which would otherwise fail every spec that renders either caller page at all.
  *
  * `notify` receives the already-translated "Link copied!" message on a successful copy — the two current callers both
  * feed it into their own page-level notice banner (`Alert.maybeInfo`), rather than this component owning one.
  */
final class ShareRow(
  link: () => String,
  shareTitle: () => String,
  generateQr: String => Future[String],
  notify: String => Unit,
) {

  private val qrOpenVar                         = Var(false)
  private val qrDataUriVar: Var[Option[String]] = Var(None)
  private val qrErrorVar: Var[Option[String]]   = Var(None)

  /** Clears the cached QR code — call this when `link` changes under the page (a group's invite-code regenerate), so
    * the next open re-fetches rather than showing a code for a now-dead link.
    */
  def resetQr(): Unit = Var.set(qrDataUriVar -> None, qrErrorVar -> None)

  def render(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-center gap-2 mt-3",
      button(
        cls := "btn btn-ghost btn-xs",
        typ := "button",
        I18n.t(UiKeys.shareCopyLink),
        onClick.mapToUnit --> Observer[Unit](_ => copyLink()),
      ),
      button(
        cls := "btn btn-ghost btn-xs",
        typ := "button",
        I18n.t(UiKeys.shareButton),
        onClick.mapToUnit --> Observer[Unit](_ => share()),
      ),
      button(
        cls := "btn btn-ghost btn-xs",
        typ := "button",
        I18n.t(UiKeys.shareQrGenerate),
        onClick.mapToUnit --> Observer[Unit](_ => openQr()),
      ),
      children <-- ShareRow.messengerAppIdVar.signal.map(ShareTarget.available(_).map(targetButton)),
      renderQrModal(),
      // Left alone on a failure rather than cleared: a row that cannot reach the server has no news about the app id,
      // and a cached one from an earlier page is still right. See [[ShareRow.messengerAppIdVar]].
      ApiClient.providers -->
        Observer[Either[ApiError, ProvidersResponse]] {
          case Right(res) =>
            ShareRow.messengerAppIdVar.set(res.messengerAppId)
          case Left(_)    =>
            ()
        },
    )
  }

  /** One share page, as an icon with its name on the tooltip — the shape `GameSetupPage.renderSwap` uses for an icon
    * button that has no room for a label.
    *
    * The URL is built in the click handler rather than put on an anchor's `href`, for the reason `link` is a function
    * at all: a group's invite link changes under the page when its code is regenerated, and an `href` written at render
    * time would go on pointing at the dead one.
    */
  private def targetButton(target: ShareTarget): HtmlElement = {
    val name             = ShareTarget.label(target)
    span(
      cls             := "tooltip",
      dataAttr("tip") := name,
      button(
        cls        := "btn btn-ghost btn-xs btn-square",
        typ        := "button",
        aria.label := name,
        ShareTarget.icon(target),
        onClick.mapToUnit --> Observer[Unit](_ => openTarget(target)),
      ),
    )
  }

  private def openTarget(target: ShareTarget): Unit = {
    ShareTarget
      .shareUrl(target, link(), shareTitle(), ShareRow.messengerAppIdVar.now())
      .foreach(ShareRow.openExternally)
  }

  /** Feature-checked: the Clipboard API is absent in jsdom (which the frontend specs run under) and on older browsers,
    * and a copy button that throws would take the page with it.
    */
  private def copyToClipboard(value: String): Boolean = {
    try {
      val clipboard = dom.window.navigator.asInstanceOf[js.Dynamic].clipboard
      if (!js.isUndefined(clipboard)) {
        clipboard.writeText(value)
        true
      } else {
        false
      }
    } catch { case _: Throwable => false }
  }

  private def copyLink(): Unit = {
    if (copyToClipboard(link())) {
      notify(I18n.t(UiKeys.shareCopied))
    }
  }

  /** `navigator.share` first, falling back to [[copyLink]] when the API is absent — mobile browsers overwhelmingly have
    * it, desktop ones mostly still don't. Feature-detected the same way as the clipboard call above rather than
    * declared against a `dom` facade, since Scala.js's own DOM bindings do not have it either.
    *
    * The share sheet's own promise is not awaited: it rejects on a plain user cancel (`AbortError`) exactly as often as
    * on a real failure, and there is nothing more useful to do with either outcome than nothing.
    */
  private def share(): Unit = {
    val nav = dom.window.navigator.asInstanceOf[js.Dynamic]
    if (js.typeOf(nav.share) != "undefined") {
      try {
        nav.share(js.Dynamic.literal(title = shareTitle(), url = link()))
        ()
      } catch { case _: Throwable => copyLink() }
    } else {
      copyLink()
    }
  }

  /** Opens the modal immediately and fills it in once the QR code is ready, rather than generating it up front — a
    * reader who never asks for the code never pays for it. Cached in [[qrDataUriVar]] until [[resetQr]] clears it.
    */
  private def openQr(): Unit = {
    Var.set(qrOpenVar -> true, qrErrorVar -> None)
    if (qrDataUriVar.now().isEmpty) {
      generateQr(link()).onComplete {
        case Success(uri) =>
          qrDataUriVar.set(Some(uri))
        case Failure(_)   =>
          qrErrorVar.set(Some(I18n.t(UiKeys.shareQrError)))
      }
    }
  }

  /** A `div.modal` with `modal-open` toggled off a `Var[Boolean]`, not `HTMLDialogElement.showModal` — that call is
    * unimplemented in jsdom, which the frontend specs run under.
    */
  private def renderQrModal(): HtmlElement = {
    div(
      cls := "modal",
      cls("modal-open") <-- qrOpenVar.signal,
      div(
        cls   := "modal-box",
        h3(cls := "font-semibold text-lg", I18n.t(UiKeys.shareQrTitle)),
        div(
          cls  := "flex justify-center py-6",
          child <-- qrDataUriVar.signal.map {
            case Some(uri) =>
              img(cls := "w-48 h-48", src := uri, alt := I18n.t(UiKeys.shareQrAlt))
            case None      =>
              span(cls := "loading loading-spinner")
          },
        ),
        child.maybe <-- qrErrorVar.signal.map(_.map(msg => p(cls := "text-error text-sm text-center", msg))),
        div(
          cls  := "modal-action",
          button(
            cls := "btn",
            typ := "button",
            I18n.t(UiKeys.shareQrClose),
            onClick.mapToUnit --> Observer[Unit](_ => qrOpenVar.set(false)),
          ),
        ),
      ),
      // Closes on an outside click, same as `AppShell.renderSignInConfirmModal`'s `modal-backdrop`.
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => qrOpenVar.set(false))),
    )
  }
}

object ShareRow {

  /** The Facebook app id [[ShareTarget.Messenger]] needs, cached here rather than in each row: it is the same for every
    * reader and for the life of the deployment, so a second share row can draw its Messenger button at once while the
    * one unauthenticated `GET /api/auth/providers` that confirms it is still in flight.
    *
    * `None` until the first answer arrives, and on a deployment with no Facebook credentials it stays that way — which
    * is exactly when the Messenger button must not be offered.
    */
  private val messengerAppIdVar: Var[Option[String]] = Var(None)

  /** Opens a share page. A hidden anchor is clicked rather than `window.open` called, the way `util.Download` hands the
    * browser a file: a click-driven anchor gets past a popup blocker that would refuse a scripted window, and it is
    * what lets `mailto:` reach the mail client instead of leaving an empty tab behind.
    *
    * Only an `http(s)` target gets a new tab. `rel` drops both the referrer and the opener, since every one of these
    * pages belongs to somebody else.
    */
  private def openExternally(url: String): Unit = {
    val anchor = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
    anchor.href = url
    if (!url.startsWith("mailto:")) {
      anchor.setAttribute("target", "_blank")
      anchor.setAttribute("rel", "noopener noreferrer")
    }
    anchor.style.display = "none"
    dom.document.body.appendChild(anchor)
    anchor.click()
    dom.document.body.removeChild(anchor)
  }
}
