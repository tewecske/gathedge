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

/** One "Share" button for one URL, and the popup it opens: copy-link, Web Share, QR code and one button per share page.
  * `GameInstancePage` and `GroupDetailPage` both put it next to whatever they share (a quiz's own URL for the former,
  * an invite link built from a code for the latter).
  *
  * The button comes in two shapes: [[render]] draws a labelled button with its popup in one block, and
  * [[renderIconButton]] draws the icon alone for a title row, with [[renderPopup]] placed elsewhere.
  *
  * The options sit in a popup, not in a row on the page. Sharing is not what either page is for, so the page shows one
  * button and nothing more. The popup is a bottom sheet on a narrow screen and a centred box from `sm` up, and its
  * buttons wrap, so it fits a phone.
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
  * A successful copy shows a toast in the corner. A toast floats over the page, so the content does not move, which an
  * alert banner at the top of the page did.
  */
final class ShareRow(
  link: () => String,
  shareTitle: () => String,
  generateQr: String => Future[String],
) {

  private val openVar                           = Var(false)
  private val qrOpenVar                         = Var(false)
  private val qrDataUriVar: Var[Option[String]] = Var(None)
  private val qrErrorVar: Var[Option[String]]   = Var(None)
  private val toastVar: Var[Option[String]]     = Var(None)

  /** Clears the cached QR code — call this when `link` changes under the page (a group's invite-code regenerate), so
    * the next open re-fetches rather than showing a code for a now-dead link.
    */
  def resetQr(): Unit = Var.set(qrDataUriVar -> None, qrErrorVar -> None)

  /** The labelled Share button with its popup, in one block — `GroupDetailPage`'s shape. */
  def render(): HtmlElement = {
    div(
      cls := "mt-3",
      button(
        cls := "btn btn-sm",
        typ := "button",
        shareMark(),
        I18n.t(UiKeys.shareButton),
        onClick.mapToUnit --> Observer[Unit](_ => openVar.set(true)),
      ),
      renderPopup(),
    )
  }

  /** The share mark alone, with "Share" on its tooltip — the shape of the pencil and trash icons it sits beside in
    * `GameInstancePage`'s title. Pair it with [[renderPopup]], placed outside the title: the title's `h1` type would
    * otherwise style the popup's text too.
    */
  def renderIconButton(): HtmlElement = {
    InlineRename.iconButton(
      I18n.t(UiKeys.shareButton),
      shareMark(),
      onClick.mapToUnit --> Observer[Unit](_ => openVar.set(true)),
    )
  }

  /** The popup, the copy toast, and the Messenger app-id fetch — everything but the button that opens the popup. */
  def renderPopup(): HtmlElement = {
    div(
      renderModal(),
      child.maybe <-- toastVar.signal.map(
        _.map(msg => {
          div(
            cls := "toast toast-top toast-end z-[1000]",
            div(cls := "alert alert-success", span(msg)),
          )
        })
      ),
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

  /** A `div.modal` with `modal-open` toggled off a `Var[Boolean]`, not `HTMLDialogElement.showModal` — that call is
    * unimplemented in jsdom, which the frontend specs run under. The QR code opens inside the same box, under the
    * buttons, rather than in a second modal on top of this one.
    */
  private def renderModal(): HtmlElement = {
    div(
      cls := "modal modal-bottom sm:modal-middle",
      cls("modal-open") <-- openVar.signal,
      div(
        cls   := "modal-box flex flex-col gap-4",
        h3(cls := "font-semibold text-lg", I18n.t(UiKeys.shareButton)),
        div(
          cls  := "flex flex-wrap gap-2",
          actionButton(I18n.t(UiKeys.shareCopyLink), () => copyLink()),
          actionButton(I18n.t(UiKeys.shareDevice), () => share()),
          actionButton(I18n.t(UiKeys.shareQrGenerate), () => openQr()),
        ),
        div(
          cls  := "grid grid-cols-2 sm:grid-cols-3 gap-2",
          children <-- ShareRow.messengerAppIdVar.signal.map(ShareTarget.available(_).map(targetButton)),
        ),
        child.maybe <-- qrOpenVar.signal.map(Option.when(_)(renderQr())),
        div(
          cls  := "modal-action mt-0",
          button(
            cls := "btn",
            typ := "button",
            I18n.t(UiKeys.shareQrClose),
            onClick.mapToUnit --> Observer[Unit](_ => close()),
          ),
        ),
      ),
      // Closes on an outside click, same as `AppShell.renderSignInConfirmModal`'s `modal-backdrop`.
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => close())),
    )
  }

  private def close(): Unit = Var.set(openVar -> false, qrOpenVar -> false)

  private def actionButton(label: String, action: () => Unit): HtmlElement = {
    button(
      cls := "btn btn-sm",
      typ := "button",
      label,
      onClick.mapToUnit --> Observer[Unit](_ => action()),
    )
  }

  /** One share page, as its icon and its name. The popup has room for the name, so it is text on the button rather than
    * a tooltip.
    *
    * The URL is built in the click handler rather than put on an anchor's `href`, for the reason `link` is a function
    * at all: a group's invite link changes under the page when its code is regenerated, and an `href` written at render
    * time would go on pointing at the dead one.
    */
  private def targetButton(target: ShareTarget): HtmlElement = {
    button(
      cls        := "btn btn-ghost btn-sm justify-start",
      typ        := "button",
      aria.label := ShareTarget.label(target),
      ShareTarget.icon(target),
      ShareTarget.displayName(target),
      onClick.mapToUnit --> Observer[Unit](_ => openTarget(target)),
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
      showToast(I18n.t(UiKeys.shareCopied))
    }
  }

  /** Shows `message` in the corner for [[ShareRow.toastMs]]. A second copy inside that time restarts nothing; the first
    * timer clears it, which is soon enough for a line that only confirms.
    */
  private def showToast(message: String): Unit = {
    toastVar.set(Some(message))
    dom.window.setTimeout(() => toastVar.set(None), ShareRow.toastMs)
    ()
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

  /** Shows the QR block at once and fills it in once the code is ready, rather than generating it up front — a reader
    * who never asks for the code never pays for it. Cached in [[qrDataUriVar]] until [[resetQr]] clears it.
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

  private def renderQr(): HtmlElement = {
    div(
      cls := "flex flex-col items-center gap-2",
      h4(cls := "font-semibold", I18n.t(UiKeys.shareQrTitle)),
      child <-- Signal.combine(qrDataUriVar.signal, qrErrorVar.signal).map {
        case (Some(uri), _)  =>
          img(cls := "w-48 h-48", src := uri, alt := I18n.t(UiKeys.shareQrAlt))
        case (None, Some(_)) =>
          emptyNode
        case (None, None)    =>
          span(cls := "loading loading-spinner")
      },
      child.maybe <-- qrErrorVar.signal.map(_.map(msg => p(cls := "text-error text-sm text-center", msg))),
    )
  }

  /** Heroicons' outline "share" mark, the icon set `HelpIcon` and the page chrome already draw from. */
  private def shareMark(): SvgElement = {
    svg.svg(
      svg.cls            := "h-4 w-4",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "1.5",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(
        svg.d :=
          "M7.217 10.907a2.25 2.25 0 1 0 0 2.186m0-2.186c.18.324.283.696.283 1.093s-.103.77-.283 1.093m0-2.186 9.566-5.314m-9.566 7.5 9.566 5.314m0 0a2.25 2.25 0 1 0 3.935 2.186 2.25 2.25 0 0 0-3.935-2.186Zm0-12.814a2.25 2.25 0 1 0 3.933-2.185 2.25 2.25 0 0 0-3.933 2.185Z"
      ),
    )
  }
}

object ShareRow {

  /** How long the "Link copied!" toast stays up. */
  private val toastMs = 3000

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
