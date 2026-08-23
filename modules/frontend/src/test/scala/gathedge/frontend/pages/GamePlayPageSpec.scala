package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import zio.test._

/** Mount/unmount safety only, the same split `GameInstancePageSpec` draws — with no `PendingPlay` hand-off set (as is
  * true of every jsdom spec run, since nothing here calls `PendingPlay.set` first), [[GamePlayPage.render]] takes its
  * `None` branch and redirects back to the picker rather than building the play loop; the loop's own interactive
  * behaviour is exercised end-to-end by `e2e/tests/game.spec.ts` instead.
  */
object GamePlayPageSpec extends ZIOSpecDefault {

  def spec = {
    suite("GamePlayPage")(
      test("mounts and unmounts cleanly with no pending hand-off") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, GamePlayPage.render("no-such-slug", 1L))
        rootNode.unmount()
        dom.document.body.removeChild(container)
        assertTrue(true)
      }
    )
  }
}
