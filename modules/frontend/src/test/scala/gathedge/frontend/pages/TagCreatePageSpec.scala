package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.shared.i18n.UiKeys
import zio.test._

/** The tag-creation page under jsdom, with no backend: every request fails, so what is left to assert is the page's own
  * furniture. With no catalog loaded a message resolves to its own key, so the assertions are on `UiKeys` constants —
  * that the keys have real copy behind them in both languages is `MessagesSpec`'s job.
  */
object TagCreatePageSpec extends ZIOSpecDefault {

  private def withPage[A](use: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val rootNode  = L.render(container, new TagCreatePage().render())
    try {
      use(container)
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  def spec = {
    suite("TagCreatePage")(
      test("renders the heading, name field, pairs area and save control") {
        val text = withPage { container =>
          container.textContent
        }
        assertTrue(
          text.contains(UiKeys.tagsCreate),
          text.contains(UiKeys.tagsName),
          text.contains(UiKeys.tagsPairs),
          text.contains(UiKeys.commonCreate),
        )
      },
      test("offers a source and a target word input") {
        val inputs = withPage { container =>
          container.querySelectorAll("input").toList.map(_.asInstanceOf[dom.html.Input])
        }
        assertTrue(inputs.size >= 2)
      },
      test("offers a joined part-of-speech radio selector above the inputs") {
        val posRadios = withPage { container =>
          container
            .querySelectorAll("input[name='pos-selector']")
            .toList
            .map(_.asInstanceOf[dom.html.Input])
        }
        assertTrue(posRadios.size == 5)
      },
    )
  }
}
