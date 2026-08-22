package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import zio.test._

import scala.concurrent.Future

/** The play-variant picker under jsdom, with no backend — the same shape `GameSetupPageSpec` uses: every request
  * fails, so the game never finishes loading and the picker (gated on a loaded game) never mounts. That leaves
  * only [[GameInstancePage.render]]'s ability to mount and unmount cleanly to assert here; the picker's own
  * interactive behaviour (swap arrow, word-limit mutual exclusion, preference select) is exercised end-to-end by
  * the `e2e/tests/game.spec.ts` suite instead, the same split this codebase draws elsewhere between jsdom unit
  * tests (pure logic, mount/unmount safety) and Playwright (real interaction against a real backend).
  */
object GameInstancePageSpec extends ZIOSpecDefault {

  /** Never actually called here — nothing in this spec opens the QR modal — but `GameInstancePage.render` takes it
    * as an ordinary parameter rather than reaching for `QRCode` itself, precisely so a real `qrcode` import is never
    * part of what this spec links. See [[GameInstancePage.render]]'s own doc comment, copied in spirit from
    * `WordsPageSpec.stubRecognize`/[[gathedge.frontend.ocr.ImageOcr.Recognize]].
    */
  private def stubGenerateQr(text: String): Future[String] = {
    Future.failed(new RuntimeException("QR generation is not exercised in this spec"))
  }

  def spec = {
    suite("GameInstancePage")(
      test("mounts and unmounts cleanly for an unknown slug") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, GameInstancePage.render("no-such-slug", stubGenerateQr))
        rootNode.unmount()
        dom.document.body.removeChild(container)
        assertTrue(true)
      }
    )
  }
}
