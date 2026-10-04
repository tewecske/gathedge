package gathedge.frontend.components

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import gathedge.shared.domain.{Gender, LanguageProfile, WordLanguage}
import org.scalajs.dom
import zio.test._

/** A select whose value is `""` must show its empty entry as selected.
  *
  * The bug: `option(value := "", label)` rendered as `<option>label</option>`. Laminar's `value` prop skips a write
  * equal to the DOM's current value, and an option with no text yet already has the value `""`. The label then became
  * the value, no option matched `""`, and the select showed nothing selected.
  */
object EmptyOptionSpec extends ZIOSpecDefault {

  private def mounted[A](element: HtmlElement)(use: dom.html.Select => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val root      = L.render(container, element)
    try use(container.querySelector("select").asInstanceOf[dom.html.Select])
    finally {
      root.unmount()
      dom.document.body.removeChild(container)
    }
  }

  def spec = {
    suite("EmptyOption")(
      test("the empty entry carries an empty value attribute") {
        mounted(select(EmptyOption("Any"))) { s =>
          val option = s.options(0)
          assertTrue(option.getAttribute("value") == "", option.value == "")
        }
      },
      test("a controlled select with the value \"\" selects the empty entry") {
        mounted(
          select(
            EmptyOption("Any"),
            option(value := "x", "X"),
            controlled(value <-- Val(""), onChange.mapToValue --> Observer.empty),
          )
        ) { s =>
          assertTrue(s.selectedIndex == 0, s.value == "")
        }
      },
      test("the article select selects its empty entry for a word with no gender") {
        val target = Var(Option.empty[Gender])
        val select = ArticleSelect.render(LanguageProfile.of(WordLanguage.De), target)
        mounted(select) { s =>
          assertTrue(s.selectedIndex == 0)
        }
      },
    )
  }
}
