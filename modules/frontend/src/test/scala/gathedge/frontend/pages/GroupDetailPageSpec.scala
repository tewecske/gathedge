package gathedge.frontend.pages

import com.raquo.laminar.api.L
import gathedge.shared.domain.{GroupRef, Tag}
import org.scalajs.dom
import zio.test._

import scala.concurrent.Future

/** The attach dropdown's choice of tag. The page loads the group and the tags in parallel, so the tag the button sends
  * must not depend on which answer comes first (issue #126: with one wordlist, the button stayed disabled).
  */
object GroupDetailPageSpec extends ZIOSpecDefault {

  private def stubGenerateQr(text: String): Future[String] = {
    Future.failed(new RuntimeException("QR generation is not exercised in this spec"))
  }

  private val only     = Tag(1L, "only", 3L, ownedByMe = true)
  private val second   = Tag(2L, "second", 3L, ownedByMe = true)
  private val attached = Tag(3L, "attached", 3L, ownedByMe = true, group = Some(GroupRef(9L, "group")))
  private val theirs   = Tag(4L, "theirs", 3L, ownedByMe = false)

  def spec = {
    suite("GroupDetailPage")(
      test("mounts and unmounts cleanly for an unknown group") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, GroupDetailPage.render(-1L, stubGenerateQr))
        rootNode.unmount()
        dom.document.body.removeChild(container)
        assertTrue(true)
      },
      test("attachable offers only the reader's own tags that are in no group") {
        assertTrue(GroupDetailPage.attachable(List(theirs, attached, only)) == List(only))
      },
      test("attachTarget defaults to the single offered tag with no pick") {
        assertTrue(GroupDetailPage.attachTarget(List(only), None) == Some(1L))
      },
      test("attachTarget defaults to the first offered tag, skipping ones not offered") {
        assertTrue(GroupDetailPage.attachTarget(List(theirs, attached, second, only), None) == Some(2L))
      },
      test("attachTarget keeps the reader's pick while it is offered") {
        assertTrue(GroupDetailPage.attachTarget(List(only, second), Some(2L)) == Some(2L))
      },
      test("attachTarget drops a pick that is no longer offered") {
        assertTrue(GroupDetailPage.attachTarget(List(only, attached), Some(3L)) == Some(1L))
      },
      test("attachTarget is empty when nothing is offered") {
        assertTrue(GroupDetailPage.attachTarget(List(theirs, attached), Some(3L)).isEmpty)
      },
    )
  }
}
