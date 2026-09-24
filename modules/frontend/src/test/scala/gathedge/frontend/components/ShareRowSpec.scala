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

  /** The shape both pages use: the icon inside the title, the dropdown beside it. */
  private def titled(row: ShareRow): HtmlElement = div(h1(row.renderIconButton()), row.renderPopup())

  private def trigger(c: dom.Element): dom.Element = {
    c.querySelector(s"h1 button[aria-label='${UiKeys.shareButton}']")
  }

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
      test("the Share icon opens the dropdown, anchored to it") {
        withRendered(titled(newRow())) { c =>
          val opens    = targetOf(c, trigger(c)) == menu(c)
          val anchor   = trigger(c).getAttribute("style").stripPrefix("anchor-name:")
          val anchored = menu(c).getAttribute("style") == s"position-anchor:$anchor"
          assertTrue(opens, anchored, anchor.nonEmpty)
        }
      },
      test("the dropdown holds copy-link, device share, QR code and the share pages") {
        withRendered(titled(newRow())) { c =>
          val box     = menu(c)
          val copy    = buttonsWithText(box, UiKeys.shareCopyLink).size
          val device  = buttonsWithText(box, UiKeys.shareDevice).size
          val qr      = buttonsWithText(box, UiKeys.shareQrGenerate).size
          val targets = ShareTarget.available(None).map(t => buttonsWithText(box, ShareTarget.displayName(t)).size)
          assertTrue(copy == 1, device == 1, qr == 1, targets.forall(_ == 1))
        }
      },
      test("the icon has no text, and the dropdown sits outside the title") {
        withRendered(titled(newRow())) { c =>
          val text    = trigger(c).textContent.trim
          val inTitle = c.querySelector("h1 [popover]") != null
          assertTrue(text.isEmpty, !inTitle)
        }
      },
      test("the QR entry opens a modal outside the dropdown, and the X closes it") {
        withRendered(titled(newRow())) { c =>
          def qrOpen = c.querySelector(".modal").classList.contains("modal-open")
          val before = qrOpen
          buttonsWithText(menu(c), UiKeys.shareQrGenerate).head.click()
          val opened = qrOpen
          val inMenu = menu(c).querySelector(".modal") != null
          c.querySelector(s"button[aria-label='${UiKeys.shareQrClose}']").asInstanceOf[dom.html.Button].click()
          val afterX = qrOpen
          assertTrue(!before, opened, !inMenu, !afterX)
        }
      },
      test("a click on the backdrop closes the QR modal") {
        withRendered(titled(newRow())) { c =>
          buttonsWithText(menu(c), UiKeys.shareQrGenerate).head.click()
          c.querySelector(".modal-backdrop").asInstanceOf[dom.html.Element].click()
          val open = c.querySelector(".modal").classList.contains("modal-open")
          assertTrue(!open)
        }
      },
    )
  }
}
