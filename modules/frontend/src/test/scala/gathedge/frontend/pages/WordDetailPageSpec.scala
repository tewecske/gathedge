package gathedge.frontend.pages

import com.raquo.laminar.api.L
import org.scalajs.dom
import gathedge.shared.i18n.UiKeys
import zio.test._

/** The word page under jsdom, with no backend: the fetch fails, so what is left to assert is the page's own shape — the
  * way back to the listing, and that the controls belonging strictly to an account (adding a translation) are absent
  * for a visitor, while the collect bar itself is not.
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
      test("a visitor with no session gets the page and the collect bar, but not the add-translation form") {
        val text = withPage(12L)(_.textContent)
        assertTrue(
          text.contains(UiKeys.wordDetailBack),
          // The collect bar is shown to every visitor, exactly as on the listing; its `<select>` still stays absent
          // until a tag list arrives.
          text.contains(UiKeys.wordsCollectHint),
          !text.contains(UiKeys.wordsCollectLabel),
          // Adding a translation belongs to an account.
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
