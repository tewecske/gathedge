package gathedge.frontend.facades

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Thin facade over the `canvas-confetti` npm package (ISC, pinned in `web/package.json`). It draws on a canvas of its
  * own that it puts over the page, and it removes nothing from the page.
  *
  * `Default`, not `Namespace` as in [[QRCode]]: Vite reads the package's `module` field, an ES module whose default
  * export is the function itself.
  *
  * Only the two calls this app makes are declared. Calling the object fires one burst. [[reset]] stops every burst and
  * clears the canvas. See [[gathedge.frontend.celebration.Fireworks]] for the options this app passes.
  */
@js.native
@JSImport("canvas-confetti", JSImport.Default)
object Confetti extends js.Object {

  /** Fires one burst. `options` is a plain object of the package's options (`particleCount`, `origin`, …). */
  def apply(options: js.Object): Unit = js.native

  def reset(): Unit = js.native
}
