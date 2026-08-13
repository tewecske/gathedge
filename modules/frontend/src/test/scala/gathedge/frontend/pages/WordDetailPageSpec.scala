package gathedge.frontend.pages

import com.raquo.laminar.api.L
import org.scalajs.dom
import gathedge.shared.i18n.UiKeys
import zio.test._

/** The word page under jsdom, with no backend: the fetch fails, so what is left to assert is the page's own shape — the
  * way back to the listing, and that the collecting controls belonging to an account are absent for a visitor.
  *
  * With no catalog loaded a message resolves to its own key, so the assertions are on `UiKeys` constants.
  */
object WordDetailPageSpec extends ZIOSpecDefault {

  private def withPage[A](id: Long)(use: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val rootNode  = L.render(container, WordDetailPage.render(id))
    try {
      use(container)
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  def spec = {
    suite("WordDetailPage")(
      test("a visitor with no session gets the page and none of the tag controls") {
        val text = withPage(12L)(_.textContent)
        assertTrue(
          text.contains(UiKeys.wordDetailBack),
          // Where ticks are filed belongs to an account, exactly as on the listing.
          !text.contains(UiKeys.wordsCollectLabel),
          !text.contains(UiKeys.wordsCollectHint),
          // So does adding a translation.
          !text.contains(UiKeys.wordDetailAddTitle),
        )
      },
      // The word never arrives under jsdom, so the card is absent — and the page has to say that rather than render an
      // empty card. A failed request is not a missing word, so it is the error, not the "no such word" line.
      test("a failed load leaves no half-rendered word behind") {
        val text = withPage(12L)(_.textContent)
        assertTrue(!text.contains(UiKeys.wordDetailTranslations), !text.contains(UiKeys.wordDetailTags))
      },
    )
  }
}
