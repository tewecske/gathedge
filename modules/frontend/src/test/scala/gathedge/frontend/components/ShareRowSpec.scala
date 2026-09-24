package gathedge.frontend.components

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom
import zio.test._

import scala.concurrent.Future

/** The share options sit behind one button: the page shows only "Share", and a click opens the popup that holds the
  * rest. The providers request fails under jsdom, so the Messenger button is absent and the other six are drawn.
  *
  * Each test reads the DOM into a `val` before `assertTrue`: the macro evaluates its expression later, after `withRow`
  * has already unmounted the row.
  */
object ShareRowSpec extends ZIOSpecDefault {

  private def stubGenerateQr(text: String): Future[String] = Future.successful("data:image/png;base64,")

  private def withRow[A](use: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val row       = new ShareRow(() => "https://example.test/g/abc", () => "Quiz", stubGenerateQr)
    val root      = L.render(container, row.render())
    try use(container)
    finally {
      root.unmount()
      dom.document.body.removeChild(container)
    }
  }

  private def modal(c: dom.Element): dom.Element = c.querySelector(".modal")

  private def buttonsWithText(c: dom.Element, text: String): List[dom.html.Button] = {
    val all = c.querySelectorAll("button")
    (0 until all.length).map(all(_).asInstanceOf[dom.html.Button]).filter(_.textContent.trim == text).toList
  }

  def spec = {
    suite("ShareRow")(
      test("the popup starts closed, and the Share button opens it") {
        withRow { c =>
          val closedAtFirst  = !modal(c).classList.contains("modal-open")
          buttonsWithText(c, UiKeys.shareButton).head.click()
          val openAfterClick = modal(c).classList.contains("modal-open")
          assertTrue(closedAtFirst, openAfterClick)
        }
      },
      test("the popup holds copy-link, device share, QR code and the share pages") {
        withRow { c =>
          val box     = modal(c)
          val copy    = buttonsWithText(box, UiKeys.shareCopyLink).size
          val device  = buttonsWithText(box, UiKeys.shareDevice).size
          val qr      = buttonsWithText(box, UiKeys.shareQrGenerate).size
          val targets = ShareTarget.available(None).map(t => buttonsWithText(box, ShareTarget.displayName(t)).size)
          assertTrue(copy == 1, device == 1, qr == 1, targets.forall(_ == 1))
        }
      },
      test("the icon button has no text, and it opens a popup placed elsewhere") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val row       = new ShareRow(() => "https://example.test/g/abc", () => "Quiz", stubGenerateQr)
        val root      = L.render(container, div(h1(row.renderIconButton()), row.renderPopup()))
        try {
          val trigger = container.querySelector(s"h1 button[aria-label='${UiKeys.shareButton}']")
          val text    = trigger.textContent.trim
          val inTitle = container.querySelector("h1 .modal") != null
          trigger.asInstanceOf[dom.html.Button].click()
          val openNow = modal(container).classList.contains("modal-open")
          assertTrue(text.isEmpty, !inTitle, openNow)
        } finally {
          root.unmount()
          dom.document.body.removeChild(container)
        }
      },
      test("the close button closes the popup") {
        withRow { c =>
          buttonsWithText(c, UiKeys.shareButton).head.click()
          buttonsWithText(c, UiKeys.shareQrClose).head.click()
          val open = modal(c).classList.contains("modal-open")
          assertTrue(!open)
        }
      },
    )
  }
}
