package gathedge.frontend.ocr

import gathedge.frontend.facades.Tesseract
import gathedge.shared.domain.WordLanguage
import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters._

/** In-browser OCR for the bulk-upload image input — owns the `tesseract.js` worker's lifecycle, language-code mapping,
  * and self-hosted asset paths, so [[gathedge.frontend.components.BulkUploadDialog]] never touches a `js.Promise`
  * directly. The image itself never leaves the browser: only [[recognize]]'s resolved text is sent anywhere, over the
  * same `contentBus` a pasted or a `.txt`-file upload already feeds.
  */
object ImageOcr {

  /** The shape of [[recognize]] with its implicit [[ExecutionContext]] resolved — what
    * [[gathedge.frontend.components.BulkUploadDialog]] actually takes, as an injected constructor parameter, rather
    * than importing this object directly. `App` is the only place that supplies the real function (`ImageOcr.recognize`
    * itself, wired to a live `tesseract.js` worker); tests supply a stub instead. This keeps `tesseract.js`'s
    * `@JSImport` out of `BulkUploadDialog`'s own reachable graph, which matters because `Test / scalaJSLinkerConfig` is
    * `NoModule` (see `build.sbt`) — any spec that renders the dialog, even without ever triggering the image input,
    * would otherwise fail to link.
    */
  type Recognize = (dom.File, WordLanguage, WordLanguage, Double => Unit) => Future[String]

  /** Where `web/scripts/copy-tesseract-assets.mjs` puts the worker script and the WASM core variants — self-hosted
    * rather than `tesseract.js`'s CDN default, so a bulk upload works offline-first and never depends on a third-party
    * CDN being reachable.
    */
  private val corePath   = "/tesseract/core"
  private val workerPath = "/tesseract/core/worker.min.js"

  /** Where the committed `*.traineddata.gz` files live — see `web/public/tesseract/lang/README.md` for their source and
    * licence.
    */
  private val langPath = "/tesseract/lang"

  /** `LSTM_ONLY` — `tesseract.js`'s own recommended default engine mode, and the only one this app's self-hosted core
    * assets carry (see the LSTM-only core variants `copy-tesseract-assets.mjs` copies).
    */
  private val oemLstmOnly = 1

  /** [[WordLanguage]] to the ISO 639-2/T code `tesseract.js`'s traineddata files are named for. */
  private def code(language: WordLanguage): String = {
    language match {
      case WordLanguage.En => "eng"
      case WordLanguage.De => "deu"
      case WordLanguage.Hu => "hun"
    }
  }

  /** Runs OCR over `file` using both `source` and `target`'s trained data at once — the same "try both declared
    * languages" policy [[gathedge.shared.dto.BulkUploadPreviewRequest]]'s two-language scan already applies to exact
    * dictionary matching, so a photo of either declared language is read without asking which one it is first.
    *
    * `onProgress` receives `0.0`-`1.0` from `tesseract.js`'s own `logger` callback, once per phase (language load,
    * engine init, recognition) — phases run in sequence, so the value the reader sees only moves forward in practice
    * even though each phase resets it to `0.0` internally.
    *
    * Resolves to the recognized raw text. The worker is always terminated afterward, success or failure, since a worker
    * is only ever spun up for one image here.
    */
  def recognize(file: dom.File, source: WordLanguage, target: WordLanguage, onProgress: Double => Unit)(implicit
    ec: ExecutionContext
  ): Future[String] = {
    val langs   = List(source, target).map(code).distinct.mkString("+")
    val options = js.Dynamic.literal(
      corePath = corePath,
      workerPath = workerPath,
      langPath = langPath,
      logger = (msg: js.Dynamic) => onProgress(msg.progress.asInstanceOf[Double]),
    )

    Tesseract.createWorker(langs, oemLstmOnly, options.asInstanceOf[js.Object]).toFuture.flatMap { worker =>
      worker.recognize(file).toFuture.map(_.data.text).andThen { case _ => worker.terminate() }
    }
  }
}
