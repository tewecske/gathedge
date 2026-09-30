package gathedge.frontend.components

import com.raquo.laminar.api.L
import gathedge.shared.domain.WordLanguage
import gathedge.shared.dto.WordAudio
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom
import zio.test._

/** The pronunciation row: which voice counts as the word's language, and what a recording renders as.
  *
  * The test runner has no speech API, so the fallback button is never drawn here. That is the case worth pinning too: a
  * browser without the API must get nothing rather than a button that does nothing.
  */
object PronunciationSpec extends ZIOSpecDefault {

  /** Every read goes into a `val` inside `read`: `assertTrue` evaluates its arguments lazily, after the element is
    * gone.
    */
  private def mounted[A](element: L.HtmlElement)(read: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val root      = L.render(container, element)
    try read(container)
    finally {
      root.unmount()
      dom.document.body.removeChild(container)
    }
  }

  private val gratis = {
    WordAudio(
      fileName = "De-gratis.ogg",
      region = "Germany, Berlin",
      playUrls = List("https://example.test/a.mp3", "https://example.test/a.ogg"),
      filePageUrl = "https://commons.wikimedia.org/wiki/File:De-gratis.ogg",
    )
  }

  def spec = {
    suite("Pronunciation")(
      test("a voice matches on its primary language subtag, however the browser writes the tag") {
        assertTrue(
          Pronunciation.speaks("de-DE", WordLanguage.De),
          Pronunciation.speaks("de_AT", WordLanguage.De),
          Pronunciation.speaks("HU", WordLanguage.Hu),
          !Pronunciation.speaks("en-US", WordLanguage.De),
          // A prefix is not a language: `es` must not claim a voice tagged `est` (Estonian).
          !Pronunciation.speaks("est-EE", WordLanguage.Es),
        )
      },
      test("a recording is a play button with its region, both sources, and a link to its file page") {
        mounted(Pronunciation.render("gratis", WordLanguage.De, List(gratis))) { root =>
          val link   = root.querySelector("a")
          val srcs   = root.querySelectorAll("audio source").map(_.getAttribute("src")).toList
          val label  = root.querySelector("button").textContent
          val href   = link.getAttribute("href")
          val target = link.getAttribute("target")
          val text   = link.textContent
          assertTrue(
            srcs == gratis.playUrls,
            label.contains("Germany, Berlin"),
            href == gratis.filePageUrl,
            target == "_blank",
            text == UiKeys.wordDetailAudioSource,
          )
        }
      },
      test("a recording with no region is labelled with the plain listen key") {
        mounted(Pronunciation.render("gratis", WordLanguage.De, List(gratis.copy(region = "")))) { root =>
          val label = root.querySelector("button").textContent
          assertTrue(label.contains(UiKeys.wordDetailAudioListen))
        }
      },
      test("no recording and no speech API draws no button") {
        mounted(Pronunciation.render("gratis", WordLanguage.De, Nil)) { root =>
          val buttons = root.querySelectorAll("button").length
          assertTrue(buttons == 0)
        }
      },
    )
  }
}
