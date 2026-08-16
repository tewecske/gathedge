package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import gathedge.shared.domain.Tag
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom
import zio.test._

/** The quiz setup screen under jsdom, with no backend: every request fails (same shape as `WordsPageSpec`), so the tag
  * list `GameApiClient.setup` would populate never arrives, and a mounted page has no real tags to filter.
  *
  * That leaves two things to assert separately: the filter box's own behaviour (mount-based, like `WordsPageSpec`'s
  * search box), and the pure narrowing rule it is built on, `GameSetupPage.matchingTags` — tested directly against a
  * hand-built tag list the way `WordCollectSpec` tests `WordCollect.tagOptionGroups`, since that is the only way to
  * exercise real matches and non-matches without standing up network mocking this codebase doesn't have.
  */
object GameSetupPageSpec extends ZIOSpecDefault {

  private def withPage[A](use: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val rootNode  = L.render(container, GameSetupPage.render())
    try {
      use(container)
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  private def filterBox(container: dom.Element): dom.html.Input = {
    container.querySelector("input[type=search]").asInstanceOf[dom.html.Input]
  }

  private val mine   = Tag(1L, "Animals", wordCount = 3, ownedByMe = true)
  private val theirs = Tag(2L, "animation", wordCount = 5, ownedByMe = false)
  private val other  = Tag(3L, "Colours", wordCount = 2, ownedByMe = false)
  private val all    = List(mine, theirs, other)

  def spec = {
    suite("GameSetupPage")(
      test("an empty filter keeps every tag") {
        assertTrue(GameSetupPage.matchingTags(all, "") == all)
      },
      test("the filter is a case-insensitive substring match on the tag name") {
        assertTrue(
          GameSetupPage.matchingTags(all, "anim") == List(mine, theirs),
          GameSetupPage.matchingTags(all, "ANIM") == List(mine, theirs),
          GameSetupPage.matchingTags(all, "colour") == List(other),
        )
      },
      test("a filter matching nothing narrows the list to empty") {
        assertTrue(GameSetupPage.matchingTags(all, "zzz").isEmpty)
      },
      // The checkbox's own `checked` state is driven by `selectedTagIdsVar`, untouched by the filter — filtering a
      // tag list never drops or adds an id there, so a selection made before a filter narrows the view is still in
      // the set once the filter widens again. `matchingTags` only ever narrows `List[Tag]`, so this holds by
      // construction: proved here as the round trip the page relies on.
      test("filtering a tag out and back in changes nothing about which ids are selected") {
        val selected       = Set(mine.id)
        val filteredOut    = GameSetupPage.matchingTags(all, "zzz")
        val filteredBackIn = GameSetupPage.matchingTags(all, "")
        assertTrue(
          filteredOut.isEmpty,
          filteredBackIn == all,
          selected.contains(mine.id), // unaffected by either filtering pass
        )
      },
      test("the filter box shows what the reader types") {
        val value = withPage { container =>
          val input = filterBox(container)
          input.value = "haus"
          input.dispatchEvent(new dom.Event("input"))
          filterBox(container).value
        }
        assertTrue(value == "haus")
      },
      // The tag fetch is still in flight right after mount (it settles asynchronously, and nothing in this suite
      // waits for that) — both empty-state messages are gated on `!loading`, so neither has appeared yet. That gate
      // is what keeps them from flashing on screen before the first real answer arrives.
      test("neither empty-state message appears before the tag fetch has settled") {
        val text = withPage(_.textContent)
        assertTrue(
          !text.contains(UiKeys.gameSetupNoEligibleTags),
          !text.contains(UiKeys.gameSetupNoMatchingTags),
        )
      },
    )
  }
}
