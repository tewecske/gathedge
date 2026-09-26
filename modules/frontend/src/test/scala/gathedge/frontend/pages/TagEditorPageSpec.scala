package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.listing.TagEntryQuery
import gathedge.frontend.ocr.ImageOcr
import gathedge.shared.domain.{PairMatch, PartOfSpeech, Word, WordLanguage}
import gathedge.shared.dto.{ColumnLanguageGuess, LanguageHit, TabularRow, TagEntry}
import gathedge.shared.i18n.UiKeys
import zio.test._

import scala.concurrent.Future

/** The unified tag editor under jsdom with no backend: every request fails, so the rendering assertions are on the
  * page's own furniture. With no catalog loaded a message resolves to its own key, so they are on `UiKeys` constants —
  * `MessagesSpec` owns whether those keys have copy in both languages.
  *
  * The `rowKey` suite pins two regressions found by hand, both the same root cause — a row was keyed by its source word
  * alone, so a word with several translations had every one of its rows react at once:
  *   - clicking "edit" on one translation put them all into edit mode;
  *   - the duplicate-pair flash lit every translation row.
  *
  * The filter rules that used to be pinned here moved to `shared.domain.TagEntryFilterSpec` when they moved to the
  * server: the database narrows the page now, and the browser only draws what it is sent.
  */
object TagEditorPageSpec extends ZIOSpecDefault {

  private def withPage[A](use: dom.Element => A): A = {
    val container                 = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val noOcr: ImageOcr.Recognize = (_, _, _, _) => Future.successful("")
    val rootNode                  =
      L.render(container, new TagEditorPage(1L, noOcr, Val(TagEntryQuery.default), Observer.empty).render())
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
    matchKind: PairMatch = PairMatch.Manual,
    createdByMe: Boolean = false,
    inMyOtherTags: Boolean = false,
    targetCreatedByMe: Boolean = false,
    targetInMyOtherTags: Boolean = false,
    comment: Option[String] = None,
    targetComment: Option[String] = None,
    sourceLang: WordLanguage = WordLanguage.De,
    targetLang: WordLanguage = WordLanguage.Hu,
  ): TagEntry = {
    TagEntry(
      source = Word(sourceId, sourceLang, s"w$sourceId", PartOfSpeech.Noun, None),
      target = targetId.map(id => Word(id, targetLang, s"t$id", PartOfSpeech.Noun, None)),
      imported = imported,
      matchKind = matchKind,
      createdByMe = createdByMe,
      inMyOtherTags = inMyOtherTags,
      targetCreatedByMe = targetCreatedByMe,
      targetInMyOtherTags = targetInMyOtherTags,
      otherTranslations = Nil,
      comment = comment,
      targetComment = targetComment,
    )
  }

  private def sideOf(e: TagEntry): TagEditorPage.Side =
    TagEditorPage.Side(e.source, e.comment, TagEditorPage.sourceIsNew(e))

  def spec = {
    suite("TagEditorPage")(
      test("shows the four provenance filters plus the two new ones") {
        val text = withPage(_.textContent)
        assertTrue(
          text.contains(UiKeys.tagsEditorFilterVerified),
          text.contains(UiKeys.tagsEditorFilterPaired),
          text.contains(UiKeys.tagsEditorFilterOther),
          text.contains(UiKeys.tagsEditorFilterUnmatched),
          text.contains(UiKeys.tagsEditorFilterImportedByMe),
          text.contains(UiKeys.tagsEditorFilterUniqueToTag),
        )
      },
      test("shows the empty-rows notice before any row loads") {
        val text = withPage(_.textContent)
        assertTrue(text.contains(UiKeys.tagsEditorEmpty))
      },
      // The rows arrive in one answer and the browser cuts them, so an empty list means no page control at all — a row
      // of buttons over the "no words yet" notice offers nothing to press.
      test("the page control stays away while the wordlist shows no rows") {
        val text = withPage(_.textContent)
        assertTrue(!text.contains(UiKeys.commonRowsPerPage))
      },
      test("renders the source and target language selects") {
        val selects = withPage(_.querySelectorAll("select.select-sm").toList)
        assertTrue(selects.size >= 2)
      },
      test("hides the editing controls until the tag proves editable") {
        // No backend answer, so `editableByMe` never becomes true and neither the add-row control nor the bulk panel
        // is rendered.
        val text = withPage(_.textContent)
        assertTrue(
          !text.contains(UiKeys.tagsEditorBulkButton),
          !text.contains(UiKeys.tagsEditorAddHeading),
          !text.contains(UiKeys.tagsEditorAddWordOnlyHint),
        )
      },
      suite("rowKey")(
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
      ),
      suite("New word badge")(
        test("a side is new when the reader minted the word and no other tag of theirs holds it") {
          assertTrue(
            TagEditorPage.sourceIsNew(entry(1, Some(2), createdByMe = true, inMyOtherTags = false)),
            !TagEditorPage.sourceIsNew(entry(1, Some(2), createdByMe = true, inMyOtherTags = true)),
            !TagEditorPage.sourceIsNew(entry(1, Some(2), createdByMe = false, inMyOtherTags = false)),
            TagEditorPage.targetIsNew(entry(1, Some(2), targetCreatedByMe = true, targetInMyOtherTags = false)),
            !TagEditorPage.targetIsNew(entry(1, Some(2), targetCreatedByMe = true, targetInMyOtherTags = true)),
          )
        },
        test("a row with no answer never marks its target new") {
          assertTrue(!TagEditorPage.targetIsNew(entry(1, None, targetCreatedByMe = true)))
        },
      ),
      suite("suggestRoles")(
        test("each language's best-scoring column takes the matching role") {
          val guesses = List(
            guess(0, WordLanguage.Hu, 8),
            guess(1, WordLanguage.De, 9),
          )
          assertTrue(
            TagEditorPage.suggestRoles(guesses, 2, WordLanguage.De, WordLanguage.Hu) == Map(
              1 -> TagEditorPage.ColumnRole.Source,
              0 -> TagEditorPage.ColumnRole.Target,
            )
          )
        },
        test("a third column of markers is left for the reader to assign") {
          val guesses = List(guess(0, WordLanguage.De, 9), guess(1, WordLanguage.Hu, 8))
          val roles   = TagEditorPage.suggestRoles(guesses, 3, WordLanguage.De, WordLanguage.Hu)
          assertTrue(roles.get(2).isEmpty, roles.size == 2)
        },
        test("with only one language recognised, the other role falls back to a free column") {
          val roles =
            TagEditorPage.suggestRoles(List(guess(1, WordLanguage.De, 9)), 2, WordLanguage.De, WordLanguage.Hu)
          assertTrue(
            roles == Map(1 -> TagEditorPage.ColumnRole.Source, 0 -> TagEditorPage.ColumnRole.Target)
          )
        },
        test("a hand-written list nothing recognises still gets the obvious two-column guess") {
          // Scoring zero everywhere is ordinary for words the dictionary has never seen, not an error.
          assertTrue(
            TagEditorPage.suggestRoles(Nil, 2, WordLanguage.De, WordLanguage.Hu) == Map(
              0 -> TagEditorPage.ColumnRole.Source,
              1 -> TagEditorPage.ColumnRole.Target,
            )
          )
        },
        test("one column cannot be both roles when both languages point at it") {
          val guesses = List(guess(0, WordLanguage.De, 9))
          val roles   = TagEditorPage.suggestRoles(guesses, 2, WordLanguage.De, WordLanguage.De)
          assertTrue(roles(0) == TagEditorPage.ColumnRole.Source, roles(1) == TagEditorPage.ColumnRole.Target)
        },
      ),
      suite("rowsFor")(
        test("maps the chosen columns and ignores the rest") {
          val grid  = List(List("der Hund", "kutya", "hn"), List("Katze", "macska", "w"))
          val roles = Map(
            0 -> TagEditorPage.ColumnRole.Source,
            1 -> TagEditorPage.ColumnRole.Target,
            2 -> TagEditorPage.ColumnRole.SourceExtra,
          )
          assertTrue(
            TagEditorPage.rowsFor(grid, roles) == List(
              TabularRow("der Hund", "kutya", Some("hn"), None),
              TabularRow("Katze", "macska", Some("w"), None),
            )
          )
        },
        test("the columns may be in any order, and an unmapped one is dropped") {
          val grid  = List(List("kutya", "skip", "der Hund"))
          val roles = Map(2 -> TagEditorPage.ColumnRole.Source, 0 -> TagEditorPage.ColumnRole.Target)
          assertTrue(
            TagEditorPage.rowsFor(grid, roles) == List(TabularRow("der Hund", "kutya", None, None))
          )
        },
        test("a blank extra cell is sent as nothing rather than as an empty string") {
          val grid  = List(List("Hund", "kutya", ""))
          val roles = Map(
            0 -> TagEditorPage.ColumnRole.Source,
            1 -> TagEditorPage.ColumnRole.Target,
            2 -> TagEditorPage.ColumnRole.TargetExtra,
          )
          assertTrue(TagEditorPage.rowsFor(grid, roles) == List(TabularRow("Hund", "kutya", None, None)))
        },
        test("a row with no word is dropped before it is sent") {
          val grid  = List(List("", "kutya"), List("Hund", "kutya"))
          val roles = Map(0 -> TagEditorPage.ColumnRole.Source, 1 -> TagEditorPage.ColumnRole.Target)
          assertTrue(TagEditorPage.rowsFor(grid, roles).map(_.source) == List("Hund"))
        },
        test("nothing is sent until both the word and translation columns are chosen") {
          val grid = List(List("Hund", "kutya"))
          assertTrue(
            TagEditorPage.rowsFor(grid, Map(0 -> TagEditorPage.ColumnRole.Source)).isEmpty,
            TagEditorPage.rowsFor(grid, Map.empty).isEmpty,
          )
        },
      ),
      suite("withNote")(
        test("a saved note reaches every row that shows the word, on whichever side it is") {
          val rows    = List(entry(1, Some(2)), entry(1, Some(3)), entry(2, Some(4)), entry(5, None))
          val updated = TagEditorPage.withNote(rows, 2L, Some("n"))
          assertTrue(
            updated.map(r => (r.comment, r.targetComment)) == List(
              (None, Some("n")),
              (None, None),
              (Some("n"), None),
              (None, None),
            )
          )
        },
        test("clearing a note clears it everywhere the word is shown") {
          val rows = List(entry(1, Some(2), comment = Some("a")), entry(1, None, comment = Some("a")))
          assertTrue(TagEditorPage.withNote(rows, 1L, None).forall(_.comment.isEmpty))
        },
      ),
      suite("posChanges")(
        test("a change touches only the words not already at that part of speech") {
          val row = entry(1, Some(2), createdByMe = true, targetCreatedByMe = true)
            .copy(target = Some(Word(2L, WordLanguage.Hu, "t2", PartOfSpeech.Other, None)))
          assertTrue(
            TagEditorPage.posChanges(row, PartOfSpeech.Noun, admin = false).map(_.map(_.word.id)).contains(List(2L))
          )
        },
        test("a word the reader may not change refuses the whole change, unless they are an administrator") {
          val dictionary = entry(1, Some(2), targetCreatedByMe = true)
            .copy(fromDictionary = true, target = Some(Word(2L, WordLanguage.Hu, "t2", PartOfSpeech.Other, None)))
          assertTrue(
            TagEditorPage.posChanges(dictionary, PartOfSpeech.Verb, admin = false).isEmpty,
            TagEditorPage.posChanges(dictionary, PartOfSpeech.Verb, admin = true).map(_.size).contains(2),
          )
        },
        test("a dictionary word already at the chosen part of speech needs no permission") {
          val row = entry(1, Some(2), targetCreatedByMe = true)
            .copy(fromDictionary = true, target = Some(Word(2L, WordLanguage.Hu, "t2", PartOfSpeech.Other, None)))
          assertTrue(
            TagEditorPage.posChanges(row, PartOfSpeech.Noun, admin = false).map(_.map(_.word.id)).contains(List(2L))
          )
        },
      ),
      suite("withWord")(
        test("a changed word replaces itself on every row that shows it, on whichever side") {
          val rows    = List(entry(1, Some(2)), entry(2, Some(3)), entry(4, None))
          val changed = Word(2L, WordLanguage.Hu, "t2", PartOfSpeech.Verb, None)
          val updated = TagEditorPage.withWord(rows, changed)
          assertTrue(
            updated(0).target.contains(changed),
            updated(1).source == changed,
            updated(2) == rows(2),
          )
        }
      ),
      suite("TagEntryDetails")(
        test("opens on the word's current note, with a main-word search and no select until a main word is picked") {
          val container = dom.document.createElement("div")
          dom.document.body.appendChild(container)
          val word      = Word(1L, WordLanguage.De, "Haus", PartOfSpeech.Noun, None)
          val rootNode  = {
            L.render(
              container,
              new TagEntryDetails(1L, word, PartOfSpeech.Noun, Some("Gebäude"), Observer.empty).render(),
            )
          }
          try {
            // Read before `assertTrue`, which evaluates lazily — after `finally` has unmounted the panel.
            val inputs  = container.querySelectorAll("input").toList.map(_.asInstanceOf[dom.html.Input])
            val selects = container.querySelectorAll("select").toList.map(_.asInstanceOf[dom.html.Select])
            val text    = container.textContent
            assertTrue(
              inputs.headOption.map(_.value).contains("Gebäude"),
              inputs.exists(_.placeholder == UiKeys.tagsEditorMainWordSearch),
              // The part of speech is the row's, and the form types wait for a main word.
              selects.isEmpty,
              text.contains(UiKeys.tagsEditorNoteLabel),
              text.contains(UiKeys.tagsEditorFormOfLabel),
            )
          } finally {
            rootNode.unmount()
            dom.document.body.removeChild(container)
          }
        }
      ),
      suite("orient")(
        test("a pair sits with each word under its own language's column") {
          val row           = entry(1, Some(2))
          val (left, right) = TagEditorPage.orient(row, WordLanguage.De, WordLanguage.Hu)
          assertTrue(left.exists(_.word.id == 1L), right.exists(_.word.id == 2L))
        },
        test("with the columns swapped the pair trades sides; comment and new-word flag follow the word") {
          val row           = entry(
            1,
            Some(2),
            createdByMe = true,
            targetCreatedByMe = true,
            targetInMyOtherTags = true,
            comment = Some("src note"),
            targetComment = Some("tgt note"),
          )
          val (left, right) = TagEditorPage.orient(row, WordLanguage.Hu, WordLanguage.De)
          assertTrue(
            left.exists(_.word.id == 2L),
            left.exists(_.comment.contains("tgt note")),
            left.exists(!_.isNew),
            right.exists(_.word.id == 1L),
            right.exists(_.comment.contains("src note")),
            right.exists(_.isNew),
          )
        },
        test("a lone word sits under its own language's column, left or right") {
          val loneDe = entry(7, None, sourceLang = WordLanguage.De)
          val loneHu = entry(9, None, sourceLang = WordLanguage.Hu)
          assertTrue(
            TagEditorPage.orient(loneDe, WordLanguage.De, WordLanguage.Hu) == ((Some(sideOf(loneDe)), None)),
            TagEditorPage.orient(loneDe, WordLanguage.Hu, WordLanguage.De) == ((None, Some(sideOf(loneDe)))),
            TagEditorPage.orient(loneHu, WordLanguage.De, WordLanguage.Hu) == ((None, Some(sideOf(loneHu)))),
          )
        },
      ),
    )
  }

  private def guess(index: Int, language: WordLanguage, matched: Int): ColumnLanguageGuess = {
    ColumnLanguageGuess(
      index = index,
      sampled = 10,
      hits = WordLanguage.all.map(l => LanguageHit(l, if (l == language) matched else 0)),
      best = Some(language),
    )
  }
}
