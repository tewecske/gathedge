package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.listing.WordQuery
import gathedge.shared.domain.WordLanguage
import gathedge.shared.i18n.UiKeys
import scala.scalajs.js
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

  /** The two word boxes, in source-then-target order: every other text input on the page is the tag's name, which is
    * `max-w-sm` and sits above them.
    */
  private def wordInputs(container: dom.Element): List[dom.html.Input] = {
    container
      .querySelectorAll("input.input-sm[type='text']")
      .toList
      .map(_.asInstanceOf[dom.html.Input])
  }

  private def typeInto(box: dom.html.Input, text: String): Unit = {
    box.value = text
    box.dispatchEvent(new dom.Event("input"))
  }

  /** `key` is what the page's handlers match on, and scalajs-dom has no typed builder for it. */
  private def pressEnter(box: dom.html.Input): Unit = {
    val init = js.Dynamic.literal(key = "Enter", bubbles = true).asInstanceOf[dom.KeyboardEventInit]
    box.dispatchEvent(new dom.KeyboardEvent("keydown", init))
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
      test("starts the caret in the name field and hands it to the source word on Enter") {
        val (onMount, afterEnter) = withPage { container =>
          val name    = container.querySelector("input.input-bordered").asInstanceOf[dom.html.Input]
          val started = dom.document.activeElement == name
          pressEnter(name)
          (started, dom.document.activeElement == wordInputs(container).head)
        }
        assertTrue(onMount, afterEnter)
      },
      test("the listing a saved tag lands on shows exactly that tag, pointed the way it was written") {
        val query = TagCreatePage.landingQuery(WordLanguage.En, WordLanguage.De, 7L)
        assertTrue(
          query.language == WordLanguage.En,
          query.target == WordLanguage.De,
          query.tagId.contains(7L),
          // Not the default: `WordsPage` overrides a *bare* arrival with the filter this browser remembers, and the
          // reader must see what they just built instead.
          query != WordQuery.default,
          // Nothing else is carried: a page number or a search term would hide part of it.
          query == WordQuery.default.copy(language = WordLanguage.En, target = WordLanguage.De, tagId = Some(7L)),
        )
      },
      test("refuses a pair the list already holds") {
        // No backend, so the autocomplete never answers and Enter takes the "create this word" path both times — which
        // is exactly the pair of keystrokes a reader repeats when they forget they have already entered a word.
        val (rows, warning) = withPage { container =>
          def commitPair(): Unit = {
            val boxes = wordInputs(container)
            typeInto(boxes(0), "Katze")
            pressEnter(boxes(0))
            typeInto(boxes(1), "macska")
            pressEnter(boxes(1))
          }
          commitPair()
          commitPair()
          val bodyRows           = container.querySelectorAll("tbody tr").toList.size
          val alert              = Option(container.querySelector(".alert-warning")).map(_.textContent).getOrElse("")
          (bodyRows, alert)
        }
        assertTrue(rows == 1, warning.contains(UiKeys.tagsDuplicatePair))
      },
      test("offers a source and a target word input") {
        val inputs = withPage { container =>
          container.querySelectorAll("input").toList.map(_.asInstanceOf[dom.html.Input])
        }
        assertTrue(inputs.size >= 2)
      },
      test("asks for no part of speech before a word has been typed") {
        // The reader picking one used to be the first control on the page, and it filtered the source autocomplete —
        // so `laufen` the verb was unreachable to anybody who had answered "noun". Picking a word settles it now.
        val posRadios  = withPage { container =>
          container.querySelectorAll("input[name='pos-selector']").toList
        }
        val posSelects = withPage { container =>
          container.querySelectorAll("select[name='pending-pos']").toList
        }
        assertTrue(posRadios.isEmpty, posSelects.isEmpty)
      },
      test("offers the der/die/das picker for a German input whatever the part of speech") {
        val articles = withPage { container =>
          container
            .querySelectorAll("input[name='target-gender']")
            .toList
            .map(_.asInstanceOf[dom.html.Input])
        }
        // German is the default target language, and the picker no longer waits on a part of speech being chosen.
        assertTrue(articles.size == 3)
      },
    )
  }
}
