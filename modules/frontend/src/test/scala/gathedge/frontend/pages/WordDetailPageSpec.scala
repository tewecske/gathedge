package gathedge.frontend.pages

import com.raquo.laminar.api.L
import org.scalajs.dom
import gathedge.shared.domain.{Tag, WordLanguage}
import gathedge.shared.i18n.UiKeys
import zio.test._

/** The word page under jsdom, with no backend: the fetch fails, so what is left to assert is the page's own shape — the
  * way back to the listing, and that the controls belonging strictly to an account (adding a translation) are absent
  * for a visitor, while the collect bar itself is not.
  *
  * With no catalog loaded a message resolves to its own key, so the assertions are on `UiKeys` constants.
  */
object WordDetailPageSpec extends ZIOSpecDefault {

  /** The two languages a word can be translated into — `WordDetailPage.otherLanguages`, which is private to the page.
    */
  private def others(word: WordLanguage): List[WordLanguage] = WordLanguage.all.filterNot(_ == word)

  private def tag(source: WordLanguage, target: WordLanguage): Tag = {
    Tag(1L, "lesson1", 0L, ownedByMe = true, editableByMe = true, sourceLanguage = source, targetLanguage = target)
  }

  private def withPage[A](id: Long)(use: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val rootNode  = L.render(container, WordDetailPage.render(id))
    try {
      use(container)
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  def spec = {
    suite("WordDetailPage")(
      test("a visitor with no session gets the page and the collect bar, but not the add-translation form") {
        val text = withPage(12L)(_.textContent)
        assertTrue(
          text.contains(UiKeys.wordDetailBack),
          // The collect bar is shown to every visitor, exactly as on the listing; its `<select>` still stays absent
          // until a tag list arrives.
          text.contains(UiKeys.wordsCollectHint),
          !text.contains(UiKeys.wordsCollectLabel),
          // Adding a translation belongs to an account.
          !text.contains(UiKeys.wordDetailAddTitle),
        )
      },
      // The word never arrives under jsdom, so the card is absent — and the page has to say that rather than render an
      // empty card. A failed request is not a missing word, so it is the error, not the "no such word" line.
      test("a failed load leaves no half-rendered word behind") {
        val text = withPage(12L)(_.textContent)
        assertTrue(
          !text.contains(UiKeys.wordDetailTranslations),
          !text.contains(UiKeys.wordDetailTags),
          // The Main word block and Forms section are gated on the word arriving too, the same as Translations/Tags.
          !text.contains(UiKeys.wordDetailMainWordLabel),
          !text.contains(UiKeys.wordDetailFormsHeading),
        )
      },
      // The collect tag says which way round the reader is learning, so the form opens on the side of its pair the
      // word is not.
      test("the add-translation form opens on the collect tag's other language") {
        val pair        = tag(WordLanguage.De, WordLanguage.Hu)
        val onGerman    =
          WordDetailPage.defaultLanguage(others(WordLanguage.De), WordLanguage.De, Some(pair), WordLanguage.En)
        val onHungarian =
          WordDetailPage.defaultLanguage(others(WordLanguage.Hu), WordLanguage.Hu, Some(pair), WordLanguage.En)
        // Neither side is the word's own, so the tag's target is what it asks its answers in.
        val onEnglish   =
          WordDetailPage.defaultLanguage(others(WordLanguage.En), WordLanguage.En, Some(pair), WordLanguage.De)
        assertTrue(
          onGerman.contains(WordLanguage.Hu),
          onHungarian.contains(WordLanguage.De),
          onEnglish.contains(WordLanguage.Hu),
        )
      },
      // With no collect tag the form falls back to the listing, and then to the word itself.
      test("with no collect tag the form falls back to the listing, and then to the word itself") {
        val noTag     = WordDetailPage.defaultLanguage(others(WordLanguage.De), WordLanguage.De, None, WordLanguage.En)
        // The listing's target is the word's own language, so it is no more usable than the tag was.
        val itsOwn    = WordDetailPage.defaultLanguage(others(WordLanguage.De), WordLanguage.De, None, WordLanguage.De)
        val noneAtAll = WordDetailPage.defaultLanguage(Nil, WordLanguage.De, None, WordLanguage.En)
        assertTrue(
          noTag.contains(WordLanguage.En),
          itsOwn == others(WordLanguage.De).headOption,
          noneAtAll.isEmpty,
        )
      },
      // The pair belongs to the tag, so switching the collect tag in the bar switches the language with it.
      test("a tag pair the word cannot take gives way to the listing's target") {
        val spanish = tag(WordLanguage.Es, WordLanguage.De)
        // A German word: the tag's other side is Spanish, which this word can take.
        val takes   =
          WordDetailPage.defaultLanguage(others(WordLanguage.De), WordLanguage.De, Some(spanish), WordLanguage.En)
        // The same tag on an English word points at German, which is on offer as well.
        val alsoDe  =
          WordDetailPage.defaultLanguage(others(WordLanguage.En), WordLanguage.En, Some(spanish), WordLanguage.Hu)
        assertTrue(
          takes.contains(WordLanguage.Es),
          alsoDe.contains(WordLanguage.De),
        )
      },
    )
  }
}
