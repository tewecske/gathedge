package gathedge.frontend.components

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import gathedge.shared.domain.{PartOfSpeech, WordLanguage}
import gathedge.shared.dto.TagPairWord
import org.scalajs.dom
import zio.test._

import scala.collection.mutable

/** The regressions this pins, all found by hand in the tag editor:
  *   - the widget owns no language control — the parent passes the language in;
  *   - only Enter or a click commits; Tab and blur just leave the field, so a misclick or a tab-past adds nothing;
  *   - the autocomplete dropdown is closed once a value is taken.
  *
  * jsdom, no backend: the live dictionary search fails, so every typed word offers only the "+ create" row — which is
  * all these cases need.
  */
object WordPickerSpec extends ZIOSpecDefault {

  private final case class Harness(
    container: dom.Element,
    commits: mutable.Buffer[TagPairWord],
    close: () => Unit,
  )

  private def mount(): Harness = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val commits   = mutable.Buffer.empty[TagPairWord]
    val picker    = new WordPicker(
      language = Val(WordLanguage.De),
      partOfSpeech = Val(None),
      onCommit = Observer[TagPairWord](commits += _),
      placeholderSignal = Val("type a word"),
    )
    val root      = L.render(container, picker.render())
    Harness(container, commits, () => { root.unmount(); dom.document.body.removeChild(container) })
  }

  private def field(c: dom.Element): dom.html.Input =
    c.querySelector("input[type=text]").asInstanceOf[dom.html.Input]

  private def typeInto(input: dom.html.Input, text: String): Unit = {
    input.value = text
    input.dispatchEvent(new dom.Event("input"))
  }

  private def press(input: dom.html.Input, k: String): Unit =
    input.dispatchEvent(new dom.KeyboardEvent("keydown", new dom.KeyboardEventInit { key = k }))

  def spec = {
    suite("WordPicker")(
      test("renders no language selector — the language is a parameter, not a field") {
        val h = mount()
        try assertTrue(h.container.querySelectorAll("select").length == 0)
        finally h.close()
      },
      test("Enter commits the typed word") {
        val h = mount()
        try {
          val f = field(h.container)
          typeInto(f, "Hausboot")
          press(f, "Enter")
          assertTrue(
            h.commits.toList == List(TagPairWord.New(WordLanguage.De, "Hausboot", PartOfSpeech.Other, None))
          )
        } finally h.close()
      },
      test("Tab does not commit — it just leaves the field") {
        val h = mount()
        try {
          val f        = field(h.container)
          typeInto(f, "Hausboot")
          press(f, "Tab")
          val afterTab = h.commits.toList
          press(f, "Enter") // still works afterwards
          assertTrue(afterTab.isEmpty, h.commits.size == 1)
        } finally h.close()
      },
      test("blur does not commit — a misclick away keeps the pair unadded") {
        val h = mount()
        try {
          val f = field(h.container)
          typeInto(f, "Hausboot")
          f.dispatchEvent(new dom.Event("blur"))
          assertTrue(h.commits.isEmpty)
        } finally h.close()
      },
      test("the dropdown is open while typing and closed once Enter takes a value") {
        val h = mount()
        try {
          val f           = field(h.container)
          typeInto(f, "Hausboot")
          val whileTyping = h.container.querySelector("ul.menu") != null
          press(f, "Enter")
          val afterEnter  = h.container.querySelector("ul.menu") != null
          assertTrue(whileTyping, !afterEnter)
        } finally h.close()
      },
    )
  }
}
