package gathedge.frontend.components

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import scala.util.control.NonFatal

/** The app's one tooltip: a short line of text beside a control, shown on hover, on keyboard focus, and on a tap.
  *
  * It replaces two things. daisyUI's `.tooltip` draws its bubble in CSS, centred on the element, so near a screen edge
  * the bubble runs off the page — on a phone, most of it. The browser's own `title` tooltip stays on screen, but looks
  * different on every system and never shows on a touch screen.
  *
  * One bubble serves the whole page. It lives in the top layer (a `popover="manual"` element), so no `overflow` or
  * `z-index` can clip it, and it is placed in script: it flips to the other side when the preferred one has no room,
  * then it is clamped inside the viewport. Its width is capped at the viewport's, so a long text wraps.
  *
  * The bubble is decoration for sighted readers. It is not in the accessibility tree while hidden, and its text is
  * cleared on hide, so it never makes a second match for a text lookup. A caller whose element has no visible text
  * gives it an `aria-label` too.
  */
object Tooltip {

  // Laminar has no keys for these. Unlike `focus`/`blur` they bubble, so focus on a control inside the element counts.
  private val onFocusIn  = eventProp[dom.FocusEvent]("focusin")
  private val onFocusOut = eventProp[dom.FocusEvent]("focusout")

  enum Placement {
    case Top, Bottom, Left, Right
  }

  enum Tone {
    case Neutral, Error
  }

  /** A fixed text. */
  def apply(text: String, placement: Placement = Placement.Top, tone: Tone = Tone.Neutral): Modifier[HtmlElement] = {
    signal(Val(text), placement, tone)
  }

  /** A text that changes. An empty text means "no tooltip now" — a lock hint that shows only while locked, say. */
  def signal(
    text: Signal[String],
    placement: Placement = Placement.Top,
    tone: Tone = Tone.Neutral,
  ): Modifier[HtmlElement] = {
    inContext { (el: HtmlElement) =>
      var latest       = ""
      val anchor       = el.ref
      def open(): Unit = {
        if (latest.nonEmpty) {
          show(anchor, latest, placement, tone)
        }
      }
      def ownsBubble   = owner.exists(_ eq anchor)
      Seq(
        text --> Observer[String] { value =>
          latest = value
          if (ownsBubble) {
            if (value.isEmpty) hide() else show(anchor, value, placement, tone)
          }
        },
        onPointerEnter --> Observer[dom.PointerEvent] { event =>
          if (event.pointerType != "touch") open()
        },
        onPointerLeave --> Observer[dom.PointerEvent] { event =>
          if (event.pointerType != "touch" && ownsBubble) hide()
        },
        // A tap has no hover, so it opens the bubble; the next tap anywhere else closes it (see `listen`). A mouse click
        // closes it, as it closes the browser's own tooltip: the click opens a menu or changes the page under it.
        onPointerDown --> Observer[dom.PointerEvent] { event =>
          if (event.pointerType == "touch") open() else if (ownsBubble) hide()
        },
        // Keyboard focus only: a mouse click also focuses, and the bubble must not then stay after the pointer left.
        onFocusIn --> Observer[dom.FocusEvent] { _ =>
          if (focusVisibleWithin(anchor)) open()
        },
        onFocusOut --> Observer[dom.FocusEvent] { _ =>
          if (ownsBubble) hide()
        },
        // A click that re-renders the page can take the element away under the pointer, and then no leave comes.
        onUnmountCallback { _ =>
          if (ownsBubble) hide()
        },
      )
    }
  }

  /** The gap between the element and the bubble, and the least room kept between the bubble and a screen edge. */
  private val gap    = 6.0
  private val margin = 8.0

  private val baseCls = {
    "fixed inset-auto m-0 border-0 overflow-visible pointer-events-none w-max max-w-[min(20rem,calc(100vw-1rem))] " +
      "rounded-field px-2 py-1 text-sm leading-snug font-normal text-start whitespace-normal break-words shadow-sm"
  }

  private def toneCls(tone: Tone): String = {
    tone match {
      case Tone.Neutral => "bg-neutral text-neutral-content"
      case Tone.Error   => "bg-error text-error-content"
    }
  }

  /** The element the bubble now belongs to. */
  private var owner: Option[dom.Element] = None

  private var bubbleElement: Option[dom.html.Element] = None

  private var listening = false

  /** The one bubble, made on first use. A spec that empties `body` takes it away, so it is put back when missing. */
  private def bubble(): dom.html.Element = {
    val element = bubbleElement.getOrElse {
      val made = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      made.setAttribute("role", "tooltip")
      if (popoverSupported(made)) {
        made.setAttribute("popover", "manual")
      } else {
        made.style.display = "none"
      }
      bubbleElement = Some(made)
      made
    }
    if (!dom.document.body.contains(element)) {
      dom.document.body.appendChild(element)
    }
    element
  }

  private def show(anchor: dom.Element, text: String, placement: Placement, tone: Tone): Unit = {
    listen()
    val element = bubble()
    owner = Some(anchor)
    element.className = s"$baseCls ${toneCls(tone)}"
    element.textContent = text
    element.style.left = "0px"
    element.style.top = "0px"
    if (popoverSupported(element)) {
      if (!element.matches(":popover-open")) {
        element.asInstanceOf[js.Dynamic].showPopover()
      }
    } else {
      element.style.display = "block"
    }
    place(anchor, element, placement)
  }

  private def hide(): Unit = {
    owner = None
    bubbleElement.foreach { element =>
      if (popoverSupported(element)) {
        if (element.matches(":popover-open")) {
          element.asInstanceOf[js.Dynamic].hidePopover()
        }
      } else {
        element.style.display = "none"
      }
      element.textContent = ""
    }
  }

  private def place(anchor: dom.Element, element: dom.html.Element, placement: Placement): Unit = {
    val rect        = anchor.getBoundingClientRect()
    val (left, top) = layout(
      Box(rect.left, rect.top, rect.width, rect.height),
      element.offsetWidth.toDouble,
      element.offsetHeight.toDouble,
      dom.document.documentElement.clientWidth.toDouble,
      dom.document.documentElement.clientHeight.toDouble,
      placement,
    )
    element.style.left = s"${left}px"
    element.style.top = s"${top}px"
  }

  /** An element's box in viewport pixels. */
  private[components] final case class Box(left: Double, top: Double, width: Double, height: Double) {
    def right: Double  = left + width
    def bottom: Double = top + height
  }

  /** Where the bubble's top-left corner goes. It takes the preferred side of `box`, or the opposite side when only that
    * one has room; then the whole bubble is kept inside the viewport.
    */
  private[components] def layout(
    box: Box,
    width: Double,
    height: Double,
    viewportWidth: Double,
    viewportHeight: Double,
    placement: Placement,
  ): (Double, Double) = {
    val roomAbove = box.top - gap - height >= margin
    val roomBelow = box.bottom + gap + height <= viewportHeight - margin
    val roomLeft  = box.left - gap - width >= margin
    val roomRight = box.right + gap + width <= viewportWidth - margin

    val side = placement match {
      case Placement.Top    => if (roomAbove || !roomBelow) Placement.Top else Placement.Bottom
      case Placement.Bottom => if (roomBelow || !roomAbove) Placement.Bottom else Placement.Top
      case Placement.Left   =>
        if (roomLeft) Placement.Left
        else if (roomRight) Placement.Right
        else if (roomAbove) Placement.Top
        else Placement.Bottom
      case Placement.Right  =>
        if (roomRight) Placement.Right
        else if (roomLeft) Placement.Left
        else if (roomAbove) Placement.Top
        else Placement.Bottom
    }

    val centreX     = box.left + box.width / 2 - width / 2
    val centreY     = box.top + box.height / 2 - height / 2
    val (left, top) = side match {
      case Placement.Top    => (centreX, box.top - gap - height)
      case Placement.Bottom => (centreX, box.bottom + gap)
      case Placement.Left   => (box.left - gap - width, centreY)
      case Placement.Right  => (box.right + gap, centreY)
    }

    (clamp(left, margin, viewportWidth - margin - width), clamp(top, margin, viewportHeight - margin - height))
  }

  /** `value` kept inside `[low, high]`; the low edge wins when the bubble is wider than the room. */
  private def clamp(value: Double, low: Double, high: Double): Double = {
    math.max(low, math.min(value, high))
  }

  /** The page-wide listeners that close the bubble, added once: a tap outside its element, Escape, and a scroll (the
    * bubble is fixed, so it would stay where the element was).
    */
  private def listen(): Unit = {
    if (!listening) {
      listening = true
      dom.document.addEventListener(
        "pointerdown",
        (event: dom.PointerEvent) => {
          event.target match {
            case target: dom.Node if owner.exists(_.contains(target)) => ()
            case _                                                    => if (owner.isDefined) hide()
          }
        },
        true,
      )
      dom.document.addEventListener(
        "keydown",
        (event: dom.KeyboardEvent) => {
          if (event.key == "Escape" && owner.isDefined) hide()
        },
      )
      dom.window.addEventListener(
        "scroll",
        (_: dom.Event) => {
          if (owner.isDefined) hide()
        },
        true,
      )
    }
  }

  /** Whether keyboard focus is on `anchor` or inside it. jsdom (the frontend specs' DOM) may not know `:has` or
    * `:focus-visible`, so a selector it cannot parse counts as "no".
    */
  private def focusVisibleWithin(anchor: dom.Element): Boolean = {
    try {
      anchor.matches(":focus-visible") || anchor.querySelector(":focus-visible") != null
    } catch {
      case NonFatal(_) => false
    }
  }

  /** scalajs-dom has no binding for the popover API, and jsdom may not implement it — hence the dynamic check. */
  private def popoverSupported(element: dom.Element): Boolean = {
    js.typeOf(element.asInstanceOf[js.Dynamic].showPopover) == "function"
  }
}
