package gathedge.frontend.components

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import gathedge.shared.domain.{Tag, WordLanguage}
import org.scalajs.dom
import zio.test._

/** Two kinds of coverage. [[TagPicker.rank]] is pure — no server, no DOM — and gets exercised directly for the
  * filtering/ranking rules its own doc comment describes. The DOM specs below cover only what does not need a live
  * search to land: hydration, clearing, and the empty-box rule. `TagPicker` now calls the real `GET /api/tags/page` as
  * the reader types, and jsdom has no backend to answer it — the same reason `WordPickerSpec` never exercises a
  * populated dropdown either; a real search is covered by manual/e2e verification instead.
  */
object TagPickerSpec extends ZIOSpecDefault {

  private def tag(id: Long, name: String, source: WordLanguage, target: WordLanguage, count: Long = 0L): Tag =
    Tag(id = id, name = name, wordCount = count, ownedByMe = true, sourceLanguage = source, targetLanguage = target)

  private val lesson1 = tag(1L, "lesson1", WordLanguage.De, WordLanguage.Hu)
  private val lesson2 = tag(2L, "lesson2", WordLanguage.En, WordLanguage.Hu)

  private final case class Harness(container: dom.Element, picker: TagPicker, close: () => Unit)

  private def mount(
    languages: Signal[List[WordLanguage]] = Val(Nil),
    selectedTag: Signal[Option[Tag]] = Val(None),
  ): Harness = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val picker    = new TagPicker(
      languages = languages,
      selectedTag = selectedTag,
      onSelect = Observer.empty[Tag],
      placeholderText = "filter by wordlist",
    )
    val root      = L.render(container, picker.render())
    Harness(container, picker, () => { root.unmount(); dom.document.body.removeChild(container) })
  }

  private def field(c: dom.Element): dom.html.Input = c.querySelector("input[type=text]").asInstanceOf[dom.html.Input]

  private def typeInto(input: dom.html.Input, text: String): Unit = {
    input.value = text
    input.dispatchEvent(new dom.Event("input"))
  }

  def spec = {
    suite("TagPicker")(
      suite("rank")(
        test("narrows to tags whose pair contains every chosen language") {
          val result = TagPicker.rank(List(lesson1, lesson2), "", List(WordLanguage.En))
          assertTrue(result == List(lesson2))
        },
        test("zero or one chosen language leaves the pool as it is") {
          assertTrue(
            TagPicker.rank(List(lesson1, lesson2), "", Nil).toSet == Set(lesson1, lesson2),
            TagPicker.rank(List(lesson1, lesson2), "", List(WordLanguage.Hu)).toSet == Set(lesson1, lesson2),
          )
        },
        test("two chosen languages neither tag's pair carries match nothing") {
          assertTrue(TagPicker.rank(List(lesson1, lesson2), "", List(WordLanguage.De, WordLanguage.En)).isEmpty)
        },
        test("an exact name match ranks first, then a prefix match, then the rest") {
          val exact  = tag(1L, "hau", WordLanguage.De, WordLanguage.Hu)
          val prefix = tag(2L, "haus", WordLanguage.De, WordLanguage.Hu)
          val other  = tag(3L, "boot", WordLanguage.De, WordLanguage.Hu)
          val result = TagPicker.rank(List(other, prefix, exact), "hau", Nil)
          assertTrue(result == List(exact, prefix, other))
        },
        test("two chosen languages rank the tag reading in that order first") {
          // Named so alphabetical order alone would put the reversed one first — the check is only meaningful because
          // the order-match rank overrides that.
          val forward  = tag(1L, "zzz-forward", WordLanguage.De, WordLanguage.Hu)
          val reversed = tag(2L, "aaa-reversed", WordLanguage.Hu, WordLanguage.De)
          val result   = TagPicker.rank(List(reversed, forward), "", List(WordLanguage.De, WordLanguage.Hu))
          assertTrue(result == List(forward, reversed))
        },
        test("ties fall back to alphabetical order") {
          val zebra  = tag(1L, "zebra", WordLanguage.De, WordLanguage.Hu)
          val apple  = tag(2L, "apple", WordLanguage.De, WordLanguage.Hu)
          val result = TagPicker.rank(List(zebra, apple), "", Nil)
          assertTrue(result == List(apple, zebra))
        },
      ),
      test("the dropdown stays closed while the box is empty, even once focused") {
        val h = mount()
        try {
          field(h.container).dispatchEvent(new dom.Event("focus"))
          assertTrue(h.container.querySelector("ul.menu") == null)
        } finally h.close()
      },
      test("the box reflects what was typed, even with no dropdown to show for it") {
        val h = mount()
        try {
          val f        = field(h.container)
          typeInto(f, "lesson1")
          val dropdown = h.container.querySelector("ul.menu")
          assertTrue(f.value == "lesson1", dropdown == null)
        } finally h.close()
      },
      test("hydrates from an already-chosen tag without waiting for a keystroke") {
        val h = mount(selectedTag = Val(Some(lesson2)))
        try {
          val text = field(h.container).value
          assertTrue(text == "lesson2")
        } finally h.close()
      },
      test("clear empties the box") {
        val h = mount()
        try {
          val f    = field(h.container)
          typeInto(f, "lesson1")
          h.picker.clear()
          val text = field(h.container).value
          assertTrue(text == "")
        } finally h.close()
      },
    )
  }
}
