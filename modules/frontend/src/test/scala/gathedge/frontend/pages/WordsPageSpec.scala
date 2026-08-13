package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.listing.WordQuery
import gathedge.shared.domain.{Gender, PartOfSpeech, Word, WordLanguage}
import gathedge.shared.dto.{TaggedPair, TranslationOption, WordSummary}
import gathedge.shared.i18n.UiKeys
import zio.test._

/** The vocabulary listing under jsdom, with no backend: every request fails, so the page settles into its empty state
  * and what is left to assert is the part that is the page's own — the search box following the URL, and the controls
  * that appear only with a session.
  *
  * With no catalog loaded a message resolves to its own key, so the assertions are on `UiKeys` constants. That the keys
  * have real copy behind them in both languages is `MessagesSpec`'s job.
  */
object WordsPageSpec extends ZIOSpecDefault {

  /** The page reads its listing state from the URL and writes it back; a `Var` stands in for the router. */
  private def withPage[A](query: WordQuery)(use: (dom.Element, Var[WordQuery]) => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val queryVar  = Var(query)
    val rootNode  = L.render(container, WordsPage.render(queryVar.signal, queryVar.writer))
    try {
      use(container, queryVar)
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  /** Mounts it the way the application does: **inside a transaction**, because the real mount happens when `/api/me`
    * opens `App`'s gate, which is a `Var` write.
    *
    * This is the helper that can see the fault [[withPage]] cannot — a `Var` written on mount applies at once outside a
    * transaction and is queued inside one, which is how a bookmarked `?q=hau` came to clear its own filter on the user
    * list. The vocabulary page follows the same shape, so it gets the same guard.
    */
  private def withPageMountedInTransaction[A](query: WordQuery)(use: (dom.Element, Var[WordQuery]) => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val queryVar  = Var(query)
    val mountVar  = Var(false)
    val rootNode  = {
      L.render(
        container,
        div(
          child <--
            mountVar.signal.map { mounted =>
              if (mounted)
                WordsPage.render(queryVar.signal, queryVar.writer)
              else
                emptyNode
            }
        ),
      )
    }
    try {
      mountVar.set(true)
      use(container, queryVar)
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  private def searchBox(container: dom.Element): dom.html.Input = {
    container.querySelector("input[type=search]").asInstanceOf[dom.html.Input]
  }

  private def selects(container: dom.Element): List[dom.html.Select] = {
    container.querySelectorAll("select").toList.map(_.asInstanceOf[dom.html.Select])
  }

  def spec = {
    suite("WordsPage")(
      test("mounting a filtered listing leaves the filter alone") {
        val (query, box) = withPageMountedInTransaction(WordQuery(page = 2, search = "hau")) { (container, queryVar) =>
          (queryVar.now(), searchBox(container).value)
        }
        assertTrue(query == WordQuery(page = 2, search = "hau"), box == "hau")
      },
      test("the search box shows what the reader types") {
        val box = withPage(WordQuery()) { (container, _) =>
          val input = searchBox(container)
          input.value = "haus"
          input.dispatchEvent(new dom.Event("input"))
          searchBox(container).value
        }
        assertTrue(box == "haus")
      },
      // The direction is the state that makes this listing different from the admin ones, and it is in the URL.
      test("the language selects show what the query says") {
        val values = withPage(WordQuery(language = WordLanguage.En, target = WordLanguage.De)) { (container, _) =>
          selects(container).map(_.value)
        }
        assertTrue(
          values.headOption.contains(WordLanguage.code(WordLanguage.En)),
          values.lift(1).contains(WordLanguage.code(WordLanguage.De)),
        )
      },
      // The tag controls belong to an account, and this page renders for a visitor with none: `AppState` has no user
      // under jsdom, so the tag bar and the "only my words" toggle must be absent rather than broken.
      test("a visitor with no session gets the words and none of the tag controls") {
        val text = withPage(WordQuery()) { (container, _) =>
          container.textContent
        }
        assertTrue(
          text.contains(UiKeys.wordsTitle),
          // Required by the licence the dictionary data is under, so it is not optional page furniture.
          text.contains(UiKeys.wordsAttribution),
          // Both halves of the tag machinery belong to an account: where ticks are filed, and what the listing is
          // narrowed to.
          !text.contains(UiKeys.wordsCollectLabel),
          !text.contains(UiKeys.wordsCollectHint),
          !text.contains(UiKeys.wordsFilterTagLabel),
          // The shell's theme control is a checkbox too, so this asks about the toggle by name rather than by counting
          // inputs.
          !text.contains(UiKeys.wordsOnlyMine),
        )
      },
      // The rows themselves cannot be reached under jsdom — every request fails, so the table is empty and a mounted
      // chip does not exist. What can be wrong is the rule behind it, which is stated here as a table.
      test("a chip is selected by the collect tag, not by any tag the reader has") {
        val summary = WordSummary(
          word = Word(1L, WordLanguage.De, "Haus", PartOfSpeech.Noun, Some(Gender.Das)),
          translations = List(TranslationOption(2L, "ház"), TranslationOption(3L, "otthon")),
          tagIds = List(10L, 11L),
          pairs = List(TaggedPair(10L, 2L), TaggedPair(11L, 3L)),
        )
        assertTrue(
          WordsPage.selectedTranslationIds(summary, Some(10L)) == Set(2L),
          WordsPage.selectedTranslationIds(summary, Some(11L)) == Set(3L),
          // A tag the word is not marked under shows nothing marked — reading "any tag I have" is the mistake that
          // made a filtered listing look fully collected.
          WordsPage.selectedTranslationIds(summary, Some(12L)) == Set.empty[Long],
          // Only before the tag list arrives, or for a reader with no tags at all.
          WordsPage.selectedTranslationIds(summary, None) == Set(2L, 3L),
        )
      },
      // Every request fails under jsdom, which is the same shape as a listing that matched nothing.
      test("with nothing loaded the listing says so rather than showing a broken table") {
        val text = withPage(WordQuery()) { (container, _) =>
          container.textContent
        }
        assertTrue(text.contains(UiKeys.wordsEmpty), text.contains(UiKeys.wordsColWord))
      },
    )
  }
}
