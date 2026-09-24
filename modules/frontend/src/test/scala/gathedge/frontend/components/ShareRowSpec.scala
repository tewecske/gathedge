package gathedge.frontend.components

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom
import zio.test._

import scala.concurrent.Future

/** The share options sit behind one button: the page shows only "Share", and the button opens a dropdown that holds the
  * rest. jsdom has no Popover API, so these specs check the wiring the browser opens it by — `popovertarget` on the
  * button names the dropdown's `id` — rather than clicking it open. The providers request fails under jsdom, so the
  * Messenger entry is absent and the other six are drawn.
  *
  * Each test reads the DOM into a `val` before `assertTrue`: the macro evaluates its expression later, after the row
  * has already been unmounted.
  */
object ShareRowSpec extends ZIOSpecDefault {

  private def stubGenerateQr(text: String): Future[String] = Future.successful("data:image/png;base64,")

  private def newRow(): ShareRow = new ShareRow(() => "https://example.test/g/abc", () => "Quiz", stubGenerateQr)

  private def withRendered[A](element: HtmlElement)(use: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val root      = L.render(container, element)
    try use(container)
    finally {
      root.unmount()
      dom.document.body.removeChild(container)
    }
  }

  private def menu(c: dom.Element): dom.Element = c.querySelector("[popover]")

  /** The dropdown the button opens, found by the id its `popovertarget` names. */
  private def targetOf(c: dom.Element, trigger: dom.Element): dom.Element = {
    c.querySelector(s"[id='${trigger.getAttribute("popovertarget")}']")
  }

  private def buttonsWithText(c: dom.Element, text: String): List[dom.html.Button] = {
    val all = c.querySelectorAll("button")
    (0 until all.length).map(all(_).asInstanceOf[dom.html.Button]).filter(_.textContent.trim == text).toList
  }

  def spec = {
    suite("ShareRow")(
      test("the Share button opens the dropdown, anchored to it") {
        withRendered(newRow().render()) { c =>
          val trigger  = buttonsWithText(c, UiKeys.shareButton).head
          val opens    = targetOf(c, trigger) == menu(c)
          val anchor   = trigger.getAttribute("style").stripPrefix("anchor-name:")
          val anchored = menu(c).getAttribute("style") == s"position-anchor:$anchor"
          assertTrue(opens, anchored, anchor.nonEmpty)
        }
      },
      test("the dropdown holds copy-link, device share, QR code and the share pages") {
        withRendered(newRow().render()) { c =>
          val box     = menu(c)
          val copy    = buttonsWithText(box, UiKeys.shareCopyLink).size
          val device  = buttonsWithText(box, UiKeys.shareDevice).size
          val qr      = buttonsWithText(box, UiKeys.shareQrGenerate).size
          val targets = ShareTarget.available(None).map(t => buttonsWithText(box, ShareTarget.displayName(t)).size)
          assertTrue(copy == 1, device == 1, qr == 1, targets.forall(_ == 1))
        }
      },
      test("the icon button has no text, and it opens a dropdown placed outside the title") {
        val row = newRow()
        withRendered(div(h1(row.renderIconButton()), row.renderPopup())) { c =>
          val trigger = c.querySelector(s"h1 button[aria-label='${UiKeys.shareButton}']")
          val text    = trigger.textContent.trim
          val inTitle = c.querySelector("h1 [popover]") != null
          val opens   = targetOf(c, trigger) == menu(c)
          assertTrue(text.isEmpty, !inTitle, opens)
        }
      },
      test("the QR entry opens the QR block inside the dropdown") {
        // The code itself lands a tick later, when the `Future` completes; the block and its heading appear at once.
        withRendered(newRow().render()) { c =>
          def qrHeadings = menu(c).querySelectorAll("h4").length
          val before     = qrHeadings
          buttonsWithText(c, UiKeys.shareQrGenerate).head.click()
          val after      = qrHeadings
          assertTrue(before == 0, after == 1)
        }
      },
    )
  }
}
