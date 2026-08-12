package gathedge.frontend.components

import com.raquo.laminar.api.L._
import com.raquo.laminar.codecs.Codec
import org.scalajs.dom
import scala.scalajs.js

/** The plumbing behind daisyUI's popover-API dropdown, shared by every dropdown in the app.
  *
  * Laminar 17 has no keys for the popover API, and the pattern needs three things that are easy to get subtly wrong
  * once, let alone twice: the two attributes, a pair of unique names, and a dismiss that survives a DOM without the
  * API. Keeping them here is what lets [[AppShell]] and [[LanguagePicker]] build the same dropdown rather than two
  * lookalikes.
  */
object Popover {

  val popoverAttr = htmlAttr("popover", Codec.stringAsIs)

  val targetAttr = htmlAttr("popovertarget", Codec.stringAsIs)

  // `child <--` can briefly hold the outgoing and incoming element in the DOM at once, and a
  // duplicate `id` would make `popovertarget` resolve to the wrong one.
  private var instanceCounter = 0

  /** A fresh `(id, anchorName)` pair for one dropdown: the `id` ties `popovertarget` to the popover, the anchor name
    * ties `anchor-name` on the trigger to `position-anchor` on the popover, which is what places it.
    */
  def nextIds(name: String): (String, String) = {
    instanceCounter += 1
    (s"$name-$instanceCounter", s"--$name-$instanceCounter")
  }

  /** Closes a popover by id.
    *
    * A popover is light-dismissed by clicks *outside* it, never by one on its own item, so an item that does not
    * navigate away has to close it explicitly. scalajs-dom has no binding for `hidePopover`, and jsdom (the frontend
    * specs' DOM) may not implement it at all — hence the cast and the feature check.
    */
  def hide(id: String): Unit = {
    Option(dom.document.getElementById(id)).foreach { element =>
      val dyn = element.asInstanceOf[js.Dynamic]
      if (!js.isUndefined(dyn.hidePopover)) {
        dyn.hidePopover()
      }
    }
  }
}
