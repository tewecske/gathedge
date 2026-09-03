package gathedge.frontend.components

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import gathedge.shared.domain.{LanguageProfile, WordLanguage}
import org.scalajs.dom
import zio.test._

/** Switching der/die/das must *replace* the article at the front of the field, never stack a second one in front.
  *
  * The bug: `LanguageProfile.strip` deliberately leaves a lone article untouched ("der" alone is not a gendered word),
  * so a field holding only "der" turned into "die der" when the reader picked a different article. Fixed in the picker,
  * pinned here.
  */
object ArticlePickerSpec extends ZIOSpecDefault {

  private val german = LanguageProfile.of(WordLanguage.De)

  private def withPicker[A](start: String)(use: (dom.Element, Var[String]) => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val textVar   = Var(start)
    val root      = L.render(container, ArticlePicker.render("grp", german, textVar, () => ()))
    try use(container, textVar)
    finally {
      root.unmount()
      dom.document.body.removeChild(container)
    }
  }

  private def clickArticle(c: dom.Element, article: String): Unit =
    c.querySelector(s"input[aria-label='$article']").asInstanceOf[dom.html.Input].click()

  def spec = {
    suite("ArticlePicker")(
      test("switching article on a field that holds only an article replaces it") {
        withPicker("der") { (c, textVar) =>
          clickArticle(c, "die")
          assertTrue(textVar.now() == "die ")
        }
      },
      test("switching article keeps the word after it") {
        withPicker("der Hund") { (c, textVar) =>
          clickArticle(c, "die")
          assertTrue(textVar.now() == "die Hund")
        }
      },
      test("picking an article on an empty field just sets the prefix") {
        withPicker("") { (c, textVar) =>
          clickArticle(c, "das")
          assertTrue(textVar.now() == "das ")
        }
      },
      test("a declined article form is still recognised and replaced, not stacked") {
        withPicker("den Hund") { (c, textVar) =>
          clickArticle(c, "die")
          assertTrue(textVar.now() == "die Hund")
        }
      },
    )
  }
}
