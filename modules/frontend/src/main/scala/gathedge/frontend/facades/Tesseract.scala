package gathedge.frontend.facades

import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait TesseractPage extends js.Object {
  def text: String = js.native
}

@js.native
trait TesseractResult extends js.Object {
  def data: TesseractPage = js.native
}

@js.native
trait TesseractWorker extends js.Object {
  def recognize(image: dom.File): js.Promise[TesseractResult] = js.native
  def terminate(): js.Promise[Unit]                           = js.native
}

/** Thin facade over the `tesseract.js` npm package (pinned in `web/package.json`, self-hosted core/worker/language
  * assets copied into `web/public/tesseract` by `web/scripts/copy-tesseract-assets.mjs`) — see [[ImageOcr]] for
  * language-code mapping, asset paths, and progress wiring built on top of this.
  *
  * `Namespace` rather than `Default`, for the same reason [[QRCode]]'s doc comment gives: the package ships as plain
  * CommonJS, and a namespace import survives both of Vite's bundling paths without depending on either one's
  * named-export detection.
  *
  * Only [[createWorker]] is declared here — the only call this app makes to reach a [[TesseractWorker]]. `createWorker`
  * in this package's current major version already returns a fully initialized worker (languages loaded, engine ready),
  * so no separate `load`/`loadLanguage`/`initialize` step is needed or declared.
  */
@js.native
@JSImport("tesseract.js", JSImport.Namespace)
object Tesseract extends js.Object {

  /** `langs` is one or more tesseract language codes, `+`-joined (e.g. `"deu+hun"`) — this app always asks for both of
    * a bulk upload's declared languages at once, the same "try both" policy `WordService.bulkUploadPreview` already
    * applies to exact dictionary matching. `oem` is an OCR engine mode from `tesseract.js`'s `OEM` constants;
    * [[ImageOcr]] always passes `1` (`LSTM_ONLY`), the package's own recommended default.
    */
  def createWorker(langs: String, oem: Int, options: js.Object): js.Promise[TesseractWorker] = js.native
}
