package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.listing.WordQuery
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{Locale, Theme, TranslationFilter, User, WordLanguage}
import gathedge.shared.i18n.UiKeys
import zio.test._

import scala.concurrent.Future

/** The vocabulary listing under jsdom, with no backend: every request fails, so the page settles into its empty state
  * and what is left to assert is the part that is the page's own — the search box following the URL, and the controls
  * that appear only with a session.
  *
  * With no catalog loaded a message resolves to its own key, so the assertions are on `UiKeys` constants. That the keys
  * have real copy behind them in both languages is `MessagesSpec`'s job.
  */
object WordsPageSpec extends ZIOSpecDefault {

  /** Never actually called here — nothing in this spec opens the image input — but `WordsPage.render` takes it as an
    * ordinary parameter rather than reaching for `ImageOcr` itself, precisely so a real `tesseract.js` import is never
    * part of what this spec links. See [[gathedge.frontend.ocr.ImageOcr.Recognize]].
    */
  private def stubRecognize(
    file: dom.File,
    source: WordLanguage,
    target: WordLanguage,
    onProgress: Double => Unit,
  ): Future[String] = {
    Future.failed(new RuntimeException("OCR is not exercised in this spec"))
  }

  /** The page reads its listing state from the URL and writes it back; a `Var` stands in for the router. */
  private def withPage[A](query: WordQuery)(use: (dom.Element, Var[WordQuery]) => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val queryVar  = Var(query)
    val rootNode  = L.render(container, WordsPage.render(queryVar.signal, queryVar.writer, stubRecognize))
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
                WordsPage.render(queryVar.signal, queryVar.writer, stubRecognize)
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

  /** Runs `body` with a session in place, since two of this page's controls exist only for an account. `AppState` is a
    * global, so the session is cleared again whatever happens.
    */
  private def signedIn[A](body: => A): A = {
    AppState.setUser(
      User(
        id = 1L,
        email = Some("reader@example.com"),
        isAdmin = false,
        theme = Theme.Light,
        locale = Locale.En,
        createdAt = "2026-01-01T00:00:00Z",
        emailVerified = true,
        isGuest = false,
      )
    )
    try body
    finally AppState.clearUser()
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
      // The translation filter is a plain listing narrowing like part-of-speech, not an account feature, so it is
      // visible and live for a visitor with no session too.
      test("the translation filter select shows what the query says and narrows it on change") {
        val (initial, changed) = {
          withPage(WordQuery(translationFilter = TranslationFilter.HasTarget)) { (container, queryVar) =>
            val select = selects(container)(3)
            val before = select.value
            select.value = TranslationFilter.code(TranslationFilter.HasAny)
            select.dispatchEvent(new dom.Event("change"))
            (before, queryVar.now().translationFilter)
          }
        }
        assertTrue(
          initial == TranslationFilter.code(TranslationFilter.HasTarget),
          changed == TranslationFilter.HasAny,
        )
      },
      // The collect bar (where a tick files, and the way to make a tag) is shown to every visitor, session or none —
      // the first tick mints a guest through the same detour a tag list would need a session to load. The *filter*
      // half of the tag machinery, and "only my words", still belong to an account and stay absent.
      test("a visitor with no session gets the words and the collect bar, but no tag filter") {
        val text = withPage(WordQuery()) { (container, _) =>
          container.textContent
        }
        assertTrue(
          text.contains(UiKeys.wordsTitle),
          // Required by the licence the dictionary data is under, so it is not optional page furniture.
          text.contains(UiKeys.wordsAttribution),
          // The collect bar's own hint is unconditional; its `<select>` stays absent regardless of session until a
          // tag list arrives to populate it — under jsdom, a signed-out visitor never gets one.
          text.contains(UiKeys.wordsCollectHint),
          !text.contains(UiKeys.wordsCollectLabel),
          !text.contains(UiKeys.wordsFilterTagLabel),
          // The shell's theme control is a checkbox too, so this asks about the toggle by name rather than by counting
          // inputs.
          !text.contains(UiKeys.wordsOnlyMine),
        )
      },
      // The button orders by the tick that filed each word under the narrowed tag, so it has nothing to order by
      // until the tag filter holds one.
      test("the newest-in-tag button appears only once a tag narrows the listing") {
        val withoutTag = signedIn(withPage(WordQuery())((container, _) => container.textContent))
        val withTag    = signedIn(withPage(WordQuery(tagId = Some(4L)))((container, _) => container.textContent))
        val visitor    = withPage(WordQuery(tagId = Some(4L)))((container, _) => container.textContent)

        assertTrue(
          !withoutTag.contains(UiKeys.wordsSortRecentInTag),
          withTag.contains(UiKeys.wordsSortRecentInTag),
          // It is half of the tag machinery, so it stays with the tag filter: absent with no session.
          !visitor.contains(UiKeys.wordsSortRecentInTag),
        )
      },
      // Every request fails under jsdom, which is the same shape as a listing that matched nothing.
      test("with nothing loaded the listing says so rather than showing a broken table") {
        val text = withPage(WordQuery()) { (container, _) =>
          container.textContent
        }
        assertTrue(text.contains(UiKeys.wordsEmpty), text.contains(UiKeys.wordsColWord))
      },
      // The three form/variant columns render as headers regardless of whether any row ever populates them.
      test("the table always carries the form/variant columns") {
        val text = withPage(WordQuery()) { (container, _) =>
          container.textContent
        }
        assertTrue(
          text.contains(UiKeys.wordsColMainWord),
          text.contains(UiKeys.wordsColVariantType),
          text.contains(UiKeys.wordsColVariants),
        )
      },
    )
  }
}
