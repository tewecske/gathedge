package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.ocr.ImageOcr
import gathedge.shared.domain.{PartOfSpeech, Word, WordLanguage}
import gathedge.shared.dto.TagEntry
import gathedge.shared.i18n.UiKeys
import zio.test._

import scala.concurrent.Future

/** The unified tag editor under jsdom with no backend: every request fails, so the rendering assertions are on the
  * page's own furniture. With no catalog loaded a message resolves to its own key, so they are on `UiKeys` constants —
  * `MessagesSpec` owns whether those keys have copy in both languages.
  *
  * The `rowKey` / `isDuplicate` suite pins three regressions found by hand, all the same root cause — a row was keyed
  * by its source word alone, so a word with several translations had every one of its rows react at once:
  *   - clicking "edit" on one translation put them all into edit mode;
  *   - the duplicate-pair flash lit every translation row;
  *   - adding a second translation of a word was mistaken for a duplicate.
  */
object TagEditorPageSpec extends ZIOSpecDefault {

  private def withPage[A](use: dom.Element => A): A = {
    val container                 = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val noOcr: ImageOcr.Recognize = (_, _, _, _) => Future.successful("")
    val rootNode                  = L.render(container, new TagEditorPage(1L, noOcr).render())
    try use(container)
    finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  private def entry(
    sourceId: Long,
    targetId: Option[Long],
    imported: Boolean = false,
    exact: Boolean = false,
    createdByMe: Boolean = false,
    inMyOtherTags: Boolean = false,
  ): TagEntry = {
    TagEntry(
      source = Word(sourceId, WordLanguage.De, s"w$sourceId", PartOfSpeech.Noun, None),
      target = targetId.map(id => Word(id, WordLanguage.Hu, s"t$id", PartOfSpeech.Noun, None)),
      imported = imported,
      exact = exact,
      createdByMe = createdByMe,
      inMyOtherTags = inMyOtherTags,
      otherTranslations = Nil,
    )
  }

  def spec = {
    suite("TagEditorPage")(
      test("shows the three provenance filters plus the two new ones") {
        val text = withPage(_.textContent)
        assertTrue(
          text.contains(UiKeys.tagsEditorFilterExact),
          text.contains(UiKeys.tagsEditorFilterNonExact),
          text.contains(UiKeys.tagsEditorFilterUnmatched),
          text.contains(UiKeys.tagsEditorFilterImportedByMe),
          text.contains(UiKeys.tagsEditorFilterUniqueToTag),
        )
      },
      test("shows the empty-rows notice before any row loads") {
        val text = withPage(_.textContent)
        assertTrue(text.contains(UiKeys.tagsEditorEmpty))
      },
      test("renders the source and target language selects") {
        val selects = withPage(_.querySelectorAll("select.select-sm").toList)
        assertTrue(selects.size >= 2)
      },
      test("hides the editing controls until the tag proves editable") {
        // No backend answer, so `editableByMe` never becomes true and neither the add-row control nor the bulk panel
        // is rendered.
        val text = withPage(_.textContent)
        assertTrue(!text.contains(UiKeys.tagsEditorBulkButton), !text.contains(UiKeys.tagsEditorAddHeading))
      },
      suite("rowKey / isDuplicate")(
        test("a row is keyed by source AND target, so a word's translations are separate rows") {
          val a = entry(5, Some(10))
          val b = entry(5, Some(20))
          assertTrue(
            TagEditorPage.rowKey(a) == ((5L, Some(10L))),
            TagEditorPage.rowKey(a) != TagEditorPage.rowKey(b),
          )
        },
        test("editing one translation of a word does not match the word's other rows") {
          val editing = Option(TagEditorPage.rowKey(entry(5, Some(10))))
          assertTrue(
            editing.contains(TagEditorPage.rowKey(entry(5, Some(10)))),
            !editing.contains(TagEditorPage.rowKey(entry(5, Some(20)))),
          )
        },
        test("re-adding the same pair is a duplicate; another translation of the same word is not") {
          val existing = List(entry(5, Some(10)), entry(7, None))
          assertTrue(
            TagEditorPage.isDuplicate(existing, entry(5, Some(10))),
            !TagEditorPage.isDuplicate(existing, entry(5, Some(20))),
            !TagEditorPage.isDuplicate(existing, entry(9, Some(10))),
          )
        },
        test("a loose row duplicates only another loose row for the same source") {
          val existing = List(entry(7, None))
          assertTrue(
            TagEditorPage.isDuplicate(existing, entry(7, None)),
            !TagEditorPage.isDuplicate(existing, entry(7, Some(10))),
          )
        },
      ),
      suite("rowVisible")(
        test("no filter selected shows every row") {
          val row = entry(1, Some(2))
          assertTrue(TagEditorPage.rowVisible(row, Set.empty, importedByMe = false, uniqueToTag = false))
        },
        test("the buckets are OR'd; a row matches when its bucket is among the selected") {
          val exactRow     = entry(1, Some(2), imported = true, exact = true)
          val unmatchedRow = entry(3, None, imported = true)
          val selected     = Set(TagEditorPage.EntryFilter.Exact, TagEditorPage.EntryFilter.Unmatched)
          assertTrue(
            TagEditorPage.rowVisible(exactRow, selected, importedByMe = false, uniqueToTag = false),
            TagEditorPage.rowVisible(unmatchedRow, selected, importedByMe = false, uniqueToTag = false),
            !TagEditorPage.rowVisible(
              entry(4, Some(5), imported = true),
              selected,
              importedByMe = false,
              uniqueToTag = false,
            ),
          )
        },
        test("\"imported by me\" needs both createdByMe and imported, and ANDs with the buckets") {
          val mineImported = entry(1, None, imported = true, createdByMe = true)
          val mineByHand   = entry(2, None, imported = false, createdByMe = true)
          val theirsImport = entry(3, None, imported = true, createdByMe = false)
          assertTrue(
            TagEditorPage.rowVisible(mineImported, Set.empty, importedByMe = true, uniqueToTag = false),
            !TagEditorPage.rowVisible(mineByHand, Set.empty, importedByMe = true, uniqueToTag = false),
            !TagEditorPage.rowVisible(theirsImport, Set.empty, importedByMe = true, uniqueToTag = false),
            // still has to be in a selected bucket when one is active
            !TagEditorPage.rowVisible(
              mineImported,
              Set(TagEditorPage.EntryFilter.Exact),
              importedByMe = true,
              uniqueToTag = false,
            ),
          )
        },
        test("\"only in this tag\" keeps a row only when it is in none of my other tags") {
          val onlyHere  = entry(1, Some(2), inMyOtherTags = false)
          val alsoOther = entry(3, Some(4), inMyOtherTags = true)
          assertTrue(
            TagEditorPage.rowVisible(onlyHere, Set.empty, importedByMe = false, uniqueToTag = true),
            !TagEditorPage.rowVisible(alsoOther, Set.empty, importedByMe = false, uniqueToTag = true),
          )
        },
      ),
    )
  }
}
