package gathedge.frontend.components

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import zio.test._

import scala.scalajs.js

/** The shared tooltip: when the bubble shows, what it holds, and where it goes.
  *
  * jsdom has no Popover API and no layout, so the DOM tests check the fallback's `display` and the text, and the
  * placement is pinned through the pure `layout` against boxes given by hand.
  *
  * Each test reads the DOM into a `val` before `assertTrue`: the macro evaluates its expression later, after the
  * element has already been unmounted.
  */
object TooltipSpec extends ZIOSpecDefault {

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

  private def pointer(target: dom.Element, kind: String, pointerType: String): Unit = {
    val init = js.Dynamic.literal(pointerType = pointerType, bubbles = kind == "pointerdown")
    target.dispatchEvent(new dom.PointerEvent(kind, init.asInstanceOf[dom.PointerEventInit]))
  }

  private def bubble(): Option[dom.html.Element] = {
    Option(dom.document.querySelector("[role=tooltip]")).map(_.asInstanceOf[dom.html.Element])
  }

  private def shownText(): String = {
    bubble().filter(_.style.display != "none").map(_.textContent).getOrElse("")
  }

  private def trigger(c: dom.Element): dom.Element = c.querySelector("button")

  // The tests below place a bubble 100 wide and 30 high in a 400 × 800 viewport.
  private val viewportWidth  = 400.0
  private val viewportHeight = 800.0

  private def at(box: Tooltip.Box, placement: Tooltip.Placement): (Double, Double) = {
    Tooltip.layout(box, 100, 30, viewportWidth, viewportHeight, placement)
  }

  def spec = {
    suite("Tooltip")(
      suite("in the page")(
        test("the pointer entering shows the text, and leaving hides and empties it") {
          withRendered(button("x", Tooltip("Swap the languages"))) { c =>
            pointer(trigger(c), "pointerenter", "mouse")
            val onEnter = shownText()
            pointer(trigger(c), "pointerleave", "mouse")
            val onLeave = bubble().map(_.textContent).getOrElse("")
            assertTrue(onEnter == "Swap the languages", onLeave == "")
          }
        },
        test("a mouse click closes it, as it closes the browser's own tooltip") {
          withRendered(button("x", Tooltip("Swap the languages"))) { c =>
            pointer(trigger(c), "pointerenter", "mouse")
            pointer(trigger(c), "pointerdown", "mouse")
            val afterClick = shownText()
            assertTrue(afterClick == "")
          }
        },
        test("a tap opens it, and a tap somewhere else closes it") {
          withRendered(div(button("x", Tooltip("Swap the languages")), span("elsewhere"))) { c =>
            pointer(trigger(c), "pointerdown", "touch")
            val onTap     = shownText()
            pointer(c.querySelector("span"), "pointerdown", "touch")
            val elsewhere = shownText()
            assertTrue(onTap == "Swap the languages", elsewhere == "")
          }
        },
        test("an empty text shows nothing, and the text follows the signal") {
          val text = Var("")
          withRendered(button("x", Tooltip.signal(text.signal))) { c =>
            pointer(trigger(c), "pointerenter", "mouse")
            val whileEmpty = shownText()
            pointer(trigger(c), "pointerleave", "mouse")
            text.set("Locked")
            pointer(trigger(c), "pointerenter", "mouse")
            val whileSet   = shownText()
            text.set("Still locked")
            val changed    = shownText()
            text.set("")
            val cleared    = shownText()
            assertTrue(whileEmpty == "", whileSet == "Locked", changed == "Still locked", cleared == "")
          }
        },
        test("the bubble closes when its element leaves the page under the pointer") {
          val present = Var(true)
          withRendered(div(child.maybe <-- present.signal.map(on => Option.when(on)(button("x", Tooltip("Tip")))))) {
            c =>
              pointer(trigger(c), "pointerenter", "mouse")
              val before = shownText()
              present.set(false)
              val after  = shownText()
              assertTrue(before == "Tip", after == "")
          }
        },
        test("Escape closes it") {
          withRendered(button("x", Tooltip("Tip"))) { c =>
            pointer(trigger(c), "pointerenter", "mouse")
            dom.document.dispatchEvent(
              new dom.KeyboardEvent("keydown", js.Dynamic.literal(key = "Escape").asInstanceOf[dom.KeyboardEventInit])
            )
            val afterEscape = shownText()
            assertTrue(afterEscape == "")
          }
        },
      ),
      suite("placement")(
        test("above the element, centred, where there is room") {
          assertTrue(at(Tooltip.Box(150, 400, 100, 20), Tooltip.Placement.Top) == (150.0, 364.0))
        },
        test("below instead, when the top edge leaves no room above") {
          assertTrue(at(Tooltip.Box(150, 10, 100, 20), Tooltip.Placement.Top) == (150.0, 36.0))
        },
        // The daisyUI tooltip's fault: a bubble centred on an element at the screen's edge ran off it.
        test("kept inside the left and the right edge") {
          val nearLeft  = at(Tooltip.Box(0, 400, 20, 20), Tooltip.Placement.Top)
          val nearRight = at(Tooltip.Box(380, 400, 20, 20), Tooltip.Placement.Top)
          assertTrue(nearLeft == (8.0, 364.0), nearRight == (292.0, 364.0))
        },
        test("a side that has no room gives way to the other side, then to above") {
          val flipped = at(Tooltip.Box(300, 400, 20, 20), Tooltip.Placement.Right)
          val above   = at(Tooltip.Box(100, 400, 200, 20), Tooltip.Placement.Right)
          assertTrue(flipped == (194.0, 395.0), above == (150.0, 364.0))
        },
      ),
    )
  }
}
