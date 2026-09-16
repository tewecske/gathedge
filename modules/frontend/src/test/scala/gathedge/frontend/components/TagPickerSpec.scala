package gathedge.frontend.components

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import gathedge.shared.domain.{Tag, WordLanguage}
import org.scalajs.dom
import zio.test._

import scala.collection.mutable

/** [[TagPicker]] never calls the server — unlike `WordPicker`, the pool it searches is a plain `Signal` the test hands
  * it directly, so these run deterministic and offline the same way that sibling spec does for its own widget.
  */
object TagPickerSpec extends ZIOSpecDefault {

  private def tag(id: Long, name: String, source: WordLanguage, target: WordLanguage, count: Long = 0L): Tag =
    Tag(id = id, name = name, wordCount = count, ownedByMe = true, sourceLanguage = source, targetLanguage = target)

  private final case class Harness(
    container: dom.Element,
    picker: TagPicker,
    picks: mutable.Buffer[Option[Long]],
    close: () => Unit,
  )

  private def mount(
    tags: List[Tag],
    languages: Signal[List[WordLanguage]] = Val(Nil),
    selected: Signal[Option[Long]] = Val(None),
  ): Harness = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val picks     = mutable.Buffer.empty[Option[Long]]
    val picker    = new TagPicker(
      tags = Val(tags),
      languages = languages,
      selected = selected,
      onSelect = Observer[Option[Long]](picks += _),
      placeholderText = "filter by wordlist",
    )
    val root      = L.render(container, picker.render())
    Harness(container, picker, picks, () => { root.unmount(); dom.document.body.removeChild(container) })
  }

  private def field(c: dom.Element): dom.html.Input = c.querySelector("input[type=text]").asInstanceOf[dom.html.Input]

  private def typeInto(input: dom.html.Input, text: String): Unit = {
    input.value = text
    input.dispatchEvent(new dom.Event("input"))
  }

  private def press(input: dom.html.Input, k: String): Unit =
    input.dispatchEvent(new dom.KeyboardEvent("keydown", new dom.KeyboardEventInit { key = k }))

  private def rowLabels(c: dom.Element): List[String] =
    c.querySelectorAll("ul.menu li").toList.map(_.textContent)

  def spec = {
    val lesson1 = tag(1L, "lesson1", WordLanguage.De, WordLanguage.Hu)
    val lesson2 = tag(2L, "lesson2", WordLanguage.En, WordLanguage.Hu)

    suite("TagPicker")(
      test("typing narrows to tags whose name contains it, case-insensitively") {
        val h = mount(List(lesson1, lesson2))
        try {
          typeInto(field(h.container), "LESSON1")
          val result = rowLabels(h.container)
          assertTrue(result.size == 1)
        } finally h.close()
      },
      test("clicking a row commits its id and closes the dropdown") {
        val h = mount(List(lesson1, lesson2))
        try {
          typeInto(field(h.container), "lesson1")
          h.container.querySelector("ul.menu li a").asInstanceOf[dom.html.Element].click()
          val dropdownAfter = h.container.querySelector("ul.menu")
          assertTrue(h.picks.toList == List(Some(1L)), dropdownAfter == null)
        } finally h.close()
      },
      test("Enter commits the highlighted row") {
        val h = mount(List(lesson1))
        try {
          val f = field(h.container)
          typeInto(f, "lesson")
          press(f, "Enter")
          assertTrue(h.picks.toList == List(Some(1L)))
        } finally h.close()
      },
      test("a chosen language narrows the pool to tags whose pair contains it") {
        val h = mount(List(lesson1, lesson2), languages = Val(List(WordLanguage.En)))
        try {
          typeInto(field(h.container), "lesson")
          val labels = rowLabels(h.container)
          assertTrue(labels.exists(_.contains("lesson2")), !labels.exists(_.contains("lesson1")))
        } finally h.close()
      },
      test("two chosen languages rank the tag reading in that order first") {
        // Named so alphabetical order alone would put the reversed one first — the ordering check is only meaningful
        // because the order-match rank overrides that.
        val forward  = tag(1L, "zzz-forward", WordLanguage.De, WordLanguage.Hu)
        val reversed = tag(2L, "aaa-reversed", WordLanguage.Hu, WordLanguage.De)
        val h        = mount(List(reversed, forward), languages = Val(List(WordLanguage.De, WordLanguage.Hu)))
        try {
          val f = field(h.container)
          press(f, "ArrowDown") // opens the dropdown on an empty query, without committing anything
          val labels = rowLabels(h.container)
          assertTrue(labels.headOption.exists(_.contains("zzz-forward")))
        } finally h.close()
      },
      test("hydrates from an already-chosen id without waiting for a keystroke") {
        val h = mount(List(lesson1, lesson2), selected = Val(Some(2L)))
        try {
          val text = field(h.container).value
          assertTrue(text == "lesson2")
        } finally h.close()
      },
      test("clear empties the box") {
        val h = mount(List(lesson1))
        try {
          val f    = field(h.container)
          typeInto(f, "lesson1")
          press(f, "Enter")
          h.picker.clear()
          val text = field(h.container).value
          assertTrue(text == "")
        } finally h.close()
      },
    )
  }
}
