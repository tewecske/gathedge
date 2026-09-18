package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiClient, ApiError, GameApiClient, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, HelpIcon, InlineRename, Labels, Pagination, WordPicker}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.{AllGameQuery, TagEntryQuery}
import gathedge.frontend.ocr.ImageOcr
import gathedge.frontend.state.{AppState, GameOwnership}
import gathedge.frontend.util.Download
import gathedge.shared.domain.{EntryBucket, PairMatch, PartOfSpeech, Tag, User, Word, WordLanguage}
import gathedge.shared.dto.{
  BulkImportResponse,
  ColumnLanguageCheckResponse,
  ColumnLanguageGuess,
  ColumnSample,
  GameCreated,
  LanguageCheckResponse,
  TabularImportResponse,
  TabularRow,
  TagEntry,
  TagEntryPage,
  TagExportFile,
  TagPairInput,
  TagPairWord,
  TagResponse,
  TagWordInput,
}
import gathedge.shared.i18n.{MessageKeys, UiKeys}
import gathedge.shared.parsing.{ColumnHeading, DelimitedText}
import org.scalajs.dom
import zio.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** The one screen for creating and editing a tag. Every add, edit and delete is its own API call the moment it is made,
  * so a half-built tag is an ordinary state, and arriving here to edit an existing tag is the same code path as
  * arriving here from `/tags/new`.
  *
  * Rows are shown the plain way `TagWordsList` showed them, with an edit icon beside the delete icon. Editing a row
  * swaps its two cells for the same [[WordPicker]] the add-a-row control uses; committing it calls `replacePair`. The
  * four filters (verified / paired / other / unmatched) narrow the list by each row's import provenance; with none
  * selected every row shows. The bulk-import panel feeds straight into this list — it writes every token as a row, in
  * the pasted order, and the reader sorts them out here.
  */
object TagEditorPage {

  /** @param query
    *   which page of the rows to draw, from the URL — see [[gathedge.frontend.listing.TagEntryQuery]].
    * @param onQuery
    *   where a page turn goes: the address bar, not a local `Var`.
    */
  def render(
    tagId: Long,
    recognize: ImageOcr.Recognize,
    query: Signal[TagEntryQuery],
    onQuery: Observer[TagEntryQuery],
  ): HtmlElement = {
    AppShell.render(Page.TagDetail(tagId), new TagEditorPage(tagId, recognize, query, onQuery).render())
  }

  /** A row's identity in the editor. One source word can carry more than one translation row, so the target id is part
    * of the key — keying on the source alone makes "edit" and the duplicate flash hit every row of that word.
    */
  private[pages] def rowKey(entry: TagEntry): (Long, Option[Long]) = (entry.source.id, entry.target.map(_.id))

  /** A word earns the "New word" badge when this reader minted it (`createdByMe`) and it is in no other tag of theirs
    * (`!inMyOtherTags`) — the dictionary did not have it before. Read per side: `sourceIsNew` off the row's own flags,
    * `targetIsNew` off the `target*` mirror, which is `false` for a row with no answer.
    */
  private[pages] def sourceIsNew(entry: TagEntry): Boolean = entry.createdByMe && !entry.inMyOtherTags

  private[pages] def targetIsNew(entry: TagEntry): Boolean =
    entry.target.isDefined && entry.targetCreatedByMe && !entry.targetInMyOtherTags

  /** One rendered word cell: the word, the reader's note beside it, and whether it earns the "New word" badge. */
  private[pages] final case class Side(word: Word, comment: Option[String], isNew: Boolean)

  /** The two words of a row, placed in the columns the editor currently shows — `left` for language `left`, `right` for
    * language `right`. The tag's stored order does not come into it: each word goes to the column that matches its own
    * language, which is what lets the swap button reorder the view with no server call. A lone word (no answer) lands
    * in its own language's column; the other cell stays empty.
    */
  private[pages] def orient(
    entry: TagEntry,
    left: WordLanguage,
    right: WordLanguage,
  ): (Option[Side], Option[Side]) = {
    val source = Side(entry.source, entry.comment, sourceIsNew(entry))
    val target = entry.target.map(word => Side(word, entry.targetComment, targetIsNew(entry)))
    target match {
      case Some(answer) =>
        if (entry.source.language == right && answer.word.language == left) (Some(answer), Some(source))
        else (Some(source), target)
      case None         =>
        if (entry.source.language == right && left != right) (None, Some(source))
        else (Some(source), None)
    }
  }

  // -- Tabular import --------------------------------------------------------------------------

  /** What one column of a delimited paste is used for. The two "extra" roles carry the gender and grammar markers
    * belonging to a specific word, which is why there is one per side rather than one for the row.
    */
  private[pages] enum ColumnRole derives CanEqual {
    case Ignore, Source, Target, SourceExtra, TargetExtra
  }

  private[pages] object ColumnRole {
    val all: List[ColumnRole] = List(Ignore, Source, Target, SourceExtra, TargetExtra)
  }

  /** How many rows the mapping step shows. Enough to recognise which column is which, few enough that a two-thousand
    * row paste does not render as a two-thousand row table.
    */
  private[pages] val previewRows = 5

  /** Pre-fills the roles from what the server made of each column: the column that looks most like the tag's source
    * language becomes the word column, the one that looks most like its target the translation. Everything else starts
    * ignored, including a second column of the same language — guessing further would be worse than letting the reader
    * say.
    *
    * A column with no guess at all is ordinary, not an error: a hand-written list of words the dictionary has never
    * seen scores zero everywhere. When neither language is recognised anywhere, the first two columns are offered as a
    * starting point, since a two-column paste is overwhelmingly word-then-translation.
    */
  private[pages] def suggestRoles(
    guesses: List[ColumnLanguageGuess],
    columnCount: Int,
    source: WordLanguage,
    target: WordLanguage,
  ): Map[Int, ColumnRole] = {
    def bestFor(language: WordLanguage): Option[Int] = {
      guesses
        .filter(_.best.contains(language))
        .maxByOption(guess => guess.hits.find(_.language == language).map(_.matched).getOrElse(0))
        .map(_.index)
    }

    val sourceColumn = bestFor(source)
    val targetColumn = bestFor(target).filterNot(sourceColumn.contains)

    (sourceColumn, targetColumn) match {
      case (Some(s), Some(t)) =>
        Map(s -> ColumnRole.Source, t -> ColumnRole.Target)
      case (Some(s), None)    =>
        val fallback = (0 until columnCount).find(_ != s)
        Map(s -> ColumnRole.Source) ++ fallback.map(_ -> ColumnRole.Target)
      case (None, Some(t))    =>
        val fallback = (0 until columnCount).find(_ != t)
        Map(t -> ColumnRole.Target) ++ fallback.map(_ -> ColumnRole.Source)
      case (None, None)       =>
        if (columnCount >= 2) Map(0 -> ColumnRole.Source, 1 -> ColumnRole.Target) else Map.empty
    }
  }

  /** Turns the grid and the reader's role assignment into the rows to post. A row whose word cell is blank is dropped
    * here rather than sent for the server to skip, so the reported count matches what was asked for.
    */
  private[pages] def rowsFor(grid: List[List[String]], roles: Map[Int, ColumnRole]): List[TabularRow] = {
    def column(role: ColumnRole): Option[Int] = roles.collectFirst { case (index, `role`) => index }

    val sourceColumn = column(ColumnRole.Source)
    val targetColumn = column(ColumnRole.Target)

    (sourceColumn, targetColumn) match {
      case (Some(source), Some(target)) =>
        val sourceExtra                                 = column(ColumnRole.SourceExtra)
        val targetExtra                                 = column(ColumnRole.TargetExtra)
        def cell(row: List[String], index: Int): String = row.lift(index).getOrElse("").trim
        grid
          .map(row => {
            TabularRow(
              source = cell(row, source),
              target = cell(row, target),
              sourceExtra = sourceExtra.map(cell(row, _)).filter(_.nonEmpty),
              targetExtra = targetExtra.map(cell(row, _)).filter(_.nonEmpty),
            )
          })
          .filter(_.source.nonEmpty)
      case _                            =>
        Nil
    }
  }
}

private final class TagEditorPage(
  tagId: Long,
  recognize: ImageOcr.Recognize,
  pageQuery: Signal[TagEntryQuery],
  onQuery: Observer[TagEntryQuery],
) {

  /** Ceiling on a bulk-import file, matching `BulkUploadDialog.maxBytes`. */
  private val maxBulkBytes = 2 * 1024 * 1024

  // -- Paging and filtering ------------------------------------------------------------------
  // Which page of which rows, from the URL — `GET /api/tags/{tagId}/entries/page` is asked exactly this, so the
  // address and the request say the same thing. The database cuts the page and applies the chips; nothing here
  // filters rows itself, which is why the four provenance chips and the two toggles are in the query rather than in
  // `Var`s of their own.

  /** `.distinct` because every reader of this treats an emission as "ask the server again". */
  private val querySignal = pageQuery.distinct

  private val pageSignal     = querySignal.map(_.page).distinct
  private val pageSizeSignal = querySignal.map(_.pageSize).distinct

  /** The query as a value that can be read synchronously — a `Signal` has no public `now`, and a write that has just
    * landed needs to know which page it should leave the reader on. Kept in step by a subscription in [[render]], the
    * mirror idiom `TagsPage.readerVar` uses.
    */
  private val queryVar = Var(TagEntryQuery.default)

  /** Every way this page asks for a different listing, as edits applied to whatever the address bar says — the
    * arrangement `TagsPage`/`WordsPage` use, and for the same reason: the state is in the URL, not in a local `Var`.
    */
  private val changeBus = new EventBus[TagEntryQuery => TagEntryQuery]()

  private def change(edit: TagEntryQuery => TagEntryQuery): Unit = changeBus.emit(edit)

  /** Those edits resolved against the query in the address bar — what [[render]] hands back to the router. */
  private val queryChanges: EventStream[TagEntryQuery] =
    changeBus.events.withCurrentValueOf(querySignal).map { case (edit, current) => edit(current) }

  /** An empty page with rows behind it: the address named a page past the end, or the last row of the last page has
    * just been deleted. The server does not clamp — it cannot know the total before it has counted — so this is the
    * browser correcting itself, which is what the honest total is for.
    *
    * `replaceState`, not `pushState`: nobody chose the page that is not there, so the back button must not offer to
    * return to it.
    */
  private def correctPagePastTheEnd(page: TagEntryPage): Unit = {
    val current = queryVar.now()
    if (page.items.isEmpty && page.total > 0L) {
      val last = Pagination.lastPage(page.total, current.pageSize)
      if (last != current.page) {
        AppRouter.router.replaceState(Page.TagDetail(tagId, current.copy(page = last)))
      }
    }
  }

  /** Re-reads the rows after a write, on the page the reader should end up on.
    *
    * Two ways to the same place, and the difference matters: a *different* page goes through the address bar, whose
    * change is itself what reloads; the *same* page has no address change to ride on, so it asks again directly. Doing
    * both would race — the query change and the reload would each sample a query the other was about to replace.
    */
  private def reloadRows(pageAfter: TagEntryQuery => Int = _.page): Unit = {
    val current = queryVar.now()
    val wanted  = current.copy(page = pageAfter(current))
    if (wanted == current) entriesBus.emit(()) else change(_ => wanted)
  }

  private val tagVar: Var[Option[Tag]]        = Var(None)
  private val entriesVar: Var[List[TagEntry]] = Var(List.empty[TagEntry])
  private val errorVar: Var[Option[String]]   = Var(None)
  private val warningVar: Var[Option[String]] = Var(None)

  /** A rejected add — the pair is already a row. The toast shows the message; the matching row lights up for a second.
    */
  private val toastVar: Var[Option[String]] = Var(None)

  /** The one row to light up, keyed by `(sourceWordId, targetWordId)` — a source word can hold several translation
    * rows, so the source id alone would flash all of them.
    */
  private val flashRowVar: Var[Option[(Long, Option[Long])]] = Var(None)

  /** How long the duplicate-pair toast stays up and the matching row stays lit. */
  private val duplicateNoticeMs = 3000

  private def showToast(message: String): Unit = {
    toastVar.set(Some(message))
    dom.window.setTimeout(() => toastVar.set(None), duplicateNoticeMs)
  }

  private def flashRow(key: (Long, Option[Long])): Unit = {
    flashRowVar.set(Some(key))
    dom.window.setTimeout(
      () => flashRowVar.update(cur => if (cur.contains(key)) None else cur),
      duplicateNoticeMs,
    )
  }

  /** The answer from `addPair` and `attachWord` alike: a row was written, or — since both writes are idempotent — the
    * wordlist already held it.
    *
    * `alreadyPresent` is the server's word for it. The editor used to work this out by looking down the whole list; it
    * holds one page now, and a row it cannot see is not a row that is not there. A written row is appended, so the
    * reader is taken to the last page to see it land; an already-present one leaves the listing alone and says so with
    * a toast, lighting the row up when it happens to be on the page in front of them.
    */
  private def onEntryAdded(result: Either[ApiError, gathedge.shared.dto.TagEntryResponse]): Unit = {
    result match {
      case Right(response) =>
        val entry = response.entry
        if (response.alreadyPresent) {
          // Leave both inputs as they are — the reader edits one side rather than retyping the whole thing.
          showToast(
            I18n.t(
              UiKeys.tagsDuplicatePair,
              Word.display(entry.source),
              entry.target.map(Word.display).getOrElse(""),
            )
          )
          flashRow(TagEditorPage.rowKey(entry))
        } else {
          warningVar.set(response.warning.map(I18n.resolve))
          Var.set(addSourceVar -> None, addTargetVar -> None, addSourcePos -> None, addTargetPos -> None)
          addSourcePicker.clear(); addTargetPicker.clear(); addSourcePicker.focus()
          // One more word than the count the page was drawn from — enough to know which page the new row is on
          // without asking twice.
          reloadRows(query => Pagination.lastPage(totalVar.now() + 1, query.pageSize))
        }
      case Left(err)       =>
        errorVar.set(Some(err.message))
    }
  }

  private val reloadBus  = new EventBus[Unit]()
  private val entriesBus = new EventBus[Unit]()

  /** Every reason to ask for a page of rows: the address bar names a different one, or a write changed what is on this
    * one. Both carry the query to ask for, so there is one request shape and one place it is built.
    */
  private val listRequests: EventStream[TagEntryQuery] =
    EventStream.merge(querySignal.updates, entriesBus.events.sample(querySignal))

  private val deleteOpenVar = Var(false)
  private val deleteBus     = new EventBus[Unit]()

  /** "Export" — the whole tag as a portable JSON file the browser saves. Open to any reader, editor or not, the same as
    * `WordApiClient.exportTag` and the import/export pair on `TagsPage`.
    */
  private val exportBus = new EventBus[Unit]()

  /** "Create game"/"View games" — the same pair of buttons `TagsPage` offers on every row, copied here since this page
    * *is* one row's detail. Offered to every reader, editor or not, for the same reason `TagsPage` offers them
    * unconditionally: a game is built from the wordlist, not owned through it, and the catalog it links to is public.
    */
  private val createGameBus   = new EventBus[Tag]()
  private val creatingGameVar = Var(false)

  /** Mirrors who the reader is at the moment "Create game" is pressed — signals cannot be read outside a subscription,
    * and the guest detour needs `.now()`. Copied from `TagsPage.readerVar`.
    */
  private val readerVar = Var(Option.empty[User])

  private val inlineRename = new InlineRename[TagResponse](name => WordApiClient.renameTag(tagId, name))

  /** Content editing — the owner, any member of the tag's group, or a global administrator, all three folded into
    * `editableByMe` by the server.
    */
  private val canEditSignal: Signal[Boolean] = tagVar.signal.map(_.exists(_.editableByMe)).distinct

  /** Renaming the wordlist, deleting it, and relanguaging it: the owner alone — plus a global administrator, who may do
    * it to anybody's (`WordService.requireOwnTag`). `ownedByMe` keeps saying who owns the row, so the admin half is
    * ORed in here rather than folded into the mark.
    */
  private val mayManageSignal: Signal[Boolean] = {
    Signal
      .combine(tagVar.signal, AppState.isGlobalAdminSignal)
      .map { case (tag, globalAdmin) => tag.exists(_.ownedByMe) || (tag.isDefined && globalAdmin) }
      .distinct
  }

  private val tagNameSignal: Signal[String] =
    tagVar.signal.map(_.map(_.name).getOrElse(I18n.t(UiKeys.tagDetailTitle))).distinct

  // The tag's mandatory language pair, set at creation. The two selects relanguage it through
  // `WordApiClient.setTagLanguages`, and stay editable only while the tag has no practice pair (`TagEntry.target`
  // present on some row). The swap button only trades the two selects locally — a view choice, no request: the
  // headings, the rows (`TagEditorPage.orient`) and the add/edit boxes all follow these two, and the server accepts a
  // pair whichever way round, so add and edit stay live in either orientation.
  private val sourceLangVar = Var(WordLanguage.De)
  private val targetLangVar = Var(WordLanguage.Hu)

  /** Whether the wordlist holds any practice pair at all — the answer comes with each page (`TagEntryPage.hasPairs`)
    * rather than from the rows on it, since the pair that locks the selects may be on another page. The server refuses
    * a relanguage on a locked wordlist whatever this says; the lock is what stops the reader being refused.
    */
  private val hasPairsVar                  = Var(false)
  private val langsLocked: Signal[Boolean] = hasPairsVar.signal.distinct
  private val langBus                      = new EventBus[(WordLanguage, WordLanguage)]()

  // -- Filters --------------------------------------------------------------------------------
  // Four mutually-exclusive provenance buckets, OR'd within the set (none selected shows every bucket), and two
  // predicates that AND on top: "imported by me" — a word this reader minted that a bulk import wrote — and "only in
  // this wordlist". They live in the query rather than in `Var`s here because the database applies them: it decides
  // which words the page holds, and `TagEntryFilter.matches` which of a word's rows are drawn. See
  // `shared.domain.TagEntryFilter`.

  private val bucketsSignal     = querySignal.map(_.buckets).distinct
  private val importedBySignal  = querySignal.map(_.importedByMe).distinct
  private val uniqueToTagSignal = querySignal.map(_.uniqueToTag).distinct

  /** How many words the filter admits, across every page — what the page control counts its buttons off. */
  private val totalVar                  = Var(0L)
  private val totalSignal: Signal[Long] = totalVar.signal.distinct

  private val loadingVar                     = Var(false)
  private val loadingSignal: Signal[Boolean] = loadingVar.signal.distinct

  // -- Add-a-row control --------------------------------------------------------------------

  // Either box may be filled first. When both hold a word the pair is submitted; Enter on an empty box, with the other
  // one filled, adds that one word alone — on whichever side it was typed, so its language is the side's language.
  // Each box's search is held to the *other* box's committed part of speech and offers that word's translations.
  private val addSourceVar = Var(Option.empty[TagPairWord])
  private val addTargetVar = Var(Option.empty[TagPairWord])
  private val addSourcePos = Var(Option.empty[PartOfSpeech])
  private val addTargetPos = Var(Option.empty[PartOfSpeech])

  /** The id of a committed word, when it is a dictionary word — what the opposite picker offers translations of. */
  private def existingId(ref: Option[TagPairWord]): Option[Long] = ref match {
    case Some(TagPairWord.Existing(id)) => Some(id)
    case _                              => None
  }

  private lazy val addSourcePicker: WordPicker = new WordPicker(
    language = sourceLangVar.signal,
    partOfSpeech = addTargetPos.signal,
    onCommit = Observer[TagPairWord] { ref =>
      addSourceVar.set(Some(ref))
      addSourcePos.set(posOf(ref))
      addTargetVar.now() match {
        case Some(target) => submitAdd(ref, target)
        case None         => dom.window.setTimeout(() => addTargetPicker.focus(), 0)
      }
    },
    // A dictionary pick settles the pair's part of speech; the other box's search is then held to it.
    onCommitWord = Observer[Option[Word]](_.foreach(w => addSourcePos.set(Some(w.partOfSpeech)))),
    // Enter on the empty word box, with an answer already committed: add that answer on its own.
    onEmptyCommit = Observer[Unit](_ => addTargetVar.now().foreach(target => addWordBus.emit(target))),
    placeholderSignal = sourceLangVar.signal.map(l => I18n.t(UiKeys.tagsSourcePlaceholder, Labels.language(l))),
    translateFrom = addTargetVar.signal.map(existingId),
  )

  private lazy val addTargetPicker: WordPicker = new WordPicker(
    language = targetLangVar.signal,
    partOfSpeech = addSourcePos.signal,
    onCommit = Observer[TagPairWord] { ref =>
      addTargetVar.set(Some(ref))
      addTargetPos.set(posOf(ref))
      addSourceVar.now() match {
        case Some(source) => submitAdd(source, ref)
        case None         => addSourcePicker.focus()
      }
    },
    onCommitWord = Observer[Option[Word]](_.foreach(w => addTargetPos.set(Some(w.partOfSpeech)))),
    // Enter on the empty answer box, with a word already committed: add that word on its own.
    onEmptyCommit = Observer[Unit](_ => addSourceVar.now().foreach(source => addWordBus.emit(source))),
    placeholderSignal = targetLangVar.signal.map(l => I18n.t(UiKeys.tagsTargetPlaceholder, Labels.language(l))),
    translateFrom = addSourceVar.signal.map(existingId),
  )

  private def submitAdd(source: TagPairWord, target: TagPairWord): Unit = {
    addRowBus.emit(TagPairInput(source, target))
  }
  private val addRowBus                                                 = new EventBus[TagPairInput]()
  private val addWordBus                                                = new EventBus[TagPairWord]()

  // -- Row editing -------------------------------------------------------------------------

  /** `(sourceWordId, oldTargetWordId)` of the row being edited, or `None`. */
  private val editingVar    = Var(Option.empty[(Long, Option[Long])])
  private val editSourceVar = Var(Option.empty[TagPairWord])
  private val editTargetVar = Var(Option.empty[TagPairWord])
  private val editSourcePos = Var(Option.empty[PartOfSpeech])
  private val editTargetPos = Var(Option.empty[PartOfSpeech])

  // Each edit box, like the add boxes, is held to the *other* box's committed part of speech and offers that word's
  // translations — so editing one side keeps searching in step with the side that is staying.
  private lazy val editSourcePicker: WordPicker = new WordPicker(
    language = sourceLangVar.signal,
    partOfSpeech = editTargetPos.signal,
    onCommit = Observer[TagPairWord] { ref =>
      editSourceVar.set(Some(ref)); editSourcePos.set(posOf(ref)); editTargetPicker.focus()
    },
    onCommitWord = Observer[Option[Word]](_.foreach(w => editSourcePos.set(Some(w.partOfSpeech)))),
    placeholderSignal = sourceLangVar.signal.map(l => I18n.t(UiKeys.tagsSourcePlaceholder, Labels.language(l))),
    translateFrom = editTargetVar.signal.map(existingId),
  )

  private lazy val editTargetPicker: WordPicker = new WordPicker(
    language = targetLangVar.signal,
    partOfSpeech = editSourcePos.signal,
    // Committing the answer is the whole edit: save it and leave edit mode, the same way Enter on the add row's target
    // adds the pair. The explicit Save button stays for a mouse-only edit and does the same thing.
    onCommit = Observer[TagPairWord] { ref =>
      editTargetVar.set(Some(ref)); editTargetPos.set(posOf(ref)); replaceBus.emit(())
    },
    onCommitWord = Observer[Option[Word]](_.foreach(w => editTargetPos.set(Some(w.partOfSpeech)))),
    placeholderSignal = targetLangVar.signal.map(l => I18n.t(UiKeys.tagsTargetPlaceholder, Labels.language(l))),
    translateFrom = editSourceVar.signal.map(existingId),
  )

  private val replaceBus   = new EventBus[Unit]()
  private val deleteRowBus = new EventBus[(Long, Option[Long])]()

  // -- Multiselect ---------------------------------------------------------------------------

  /** Rows the reader has ticked, keyed by [[TagEditorPage.rowKey]]. Pruned to the visible set whenever a filter
    * changes, so "Select all" stays scoped to what the filters show and a hidden row can never be bulk-deleted.
    */
  private val selectedVar    = Var(Set.empty[(Long, Option[Long])])
  private val bulkDeleteOpen = Var(false)
  private val bulkDeleteBus  = new EventBus[Unit]()

  /** "Delete selected words" — a hard delete of `words` rows, not just untagging. Only selected rows the reader minted
    * (`createdByMe`) that this tag alone holds (`!inMyOtherTags`) qualify; the server re-checks and skips the rest.
    */
  private val deleteWordsOpen = Var(false)
  private val deleteWordsBus  = new EventBus[Unit]()

  private def eligibleWordIdsFrom(entries: List[TagEntry], selected: Set[(Long, Option[Long])]): List[Long] = {
    entries
      .filter(e => selected.contains(TagEditorPage.rowKey(e)) && e.createdByMe && !e.inMyOtherTags)
      .map(_.source.id)
      .distinct
  }

  private val eligibleWordIds: Signal[List[Long]] = {
    entriesVar.signal.combineWith(selectedVar.signal).map { case (entries, selected) =>
      eligibleWordIdsFrom(entries, selected)
    }
  }

  /** The keys of the rows on the page, kept in step with the rows themselves so "Select all" can read them
    * synchronously (a `Signal` has no public `now`).
    *
    * One page, not the whole wordlist: the browser holds a page now, and the two bulk deletes behind this send the keys
    * they were given. "Select all" therefore means "all of these", which is also the only promise the editor can keep —
    * a row on another page is one the reader has not seen.
    */
  private val visibleKeysVar = Var(List.empty[(Long, Option[Long])])

  /** Rows with a delete request in flight. The row is greyed out and shows a spinner instead of its buttons, and a
    * second click on the same row is ignored — the old behaviour let a fast clicker fire one request per click, every
    * one of them hitting the server.
    */
  private val deletingRowsVar = Var(Set.empty[(Long, Option[Long])])

  private def requestRowDelete(entry: TagEntry): Unit = {
    val key = TagEditorPage.rowKey(entry)
    if (!deletingRowsVar.now().contains(key)) {
      deletingRowsVar.update(_ + key)
      deleteRowBus.emit(key)
    }
  }

  private def toggleSelected(key: (Long, Option[Long])): Unit =
    selectedVar.update(s => if (s.contains(key)) s - key else s + key)

  private def startEdit(entry: TagEntry): Unit = {
    editingVar.set(Some(TagEditorPage.rowKey(entry)))
    // Load the two boxes in the order the row is shown, so each word sits in a box searching its own language.
    val (left, right) = TagEditorPage.orient(entry, sourceLangVar.now(), targetLangVar.now())
    editSourceVar.set(left.map(s => TagPairWord.Existing(s.word.id)))
    editTargetVar.set(right.map(s => TagPairWord.Existing(s.word.id)))
    // A pair shares one part of speech; a lone word carries its own. Seed both sides so each picker's search is held to
    // the other box from the first keystroke, even when only one side has a word.
    editSourcePos.set(left.orElse(right).map(_.word.partOfSpeech))
    editTargetPos.set(right.orElse(left).map(_.word.partOfSpeech))
    editSourcePicker.setText(left.map(s => Word.display(s.word)).getOrElse(""))
    editTargetPicker.setText(right.map(s => Word.display(s.word)).getOrElse(""))
    // The two cells become pickers on the next render; focus the first once it is mounted.
    dom.window.setTimeout(() => editSourcePicker.focus(), 0)
  }

  private def cancelEdit(): Unit = {
    editingVar.set(None)
    editSourceVar.set(None); editTargetVar.set(None); editSourcePos.set(None); editTargetPos.set(None)
    editSourcePicker.clear(); editTargetPicker.clear()
  }

  // -- Bulk import -----------------------------------------------------------------------

  private val bulkOpenVar   = Var(false)
  private val bulkTextVar   = Var("")
  private val bulkResultVar = Var(Option.empty[String])
  private val bulkBusy      = Var(false)
  private val bulkBus       = new EventBus[String]()

  /** Text the server's language check flagged and that is waiting for the reader to confirm, and the warning shown for
    * it.
    */
  private val bulkPendingVar  = Var(Option.empty[String])
  private val bulkLangWarnVar = Var(Option.empty[String])

  /** Feeds [[WordApiClient.checkLanguage]] before an import runs. */
  private val langCheckBus = new EventBus[String]()

  // -- Tabular import --------------------------------------------------------------------------
  // Set only once a paste or file turns out to be delimited; while `gridVar` is empty the panel behaves exactly as it
  // did before, so ordinary prose never meets the mapping step.

  private val gridVar     = Var(Option.empty[List[List[String]]])
  private val headerVar   = Var(false)
  private val rolesVar    = Var(Map.empty[Int, TagEditorPage.ColumnRole])
  private val guessVar    = Var(List.empty[ColumnLanguageGuess])
  private val colCheckBus = new EventBus[List[List[String]]]()
  private val tabularBus  = new EventBus[List[TabularRow]]()

  /** A mapped column whose assigned language disagrees with what the dictionary suggests, held until the reader
    * confirms — the tabular counterpart of [[bulkPendingVar]]/[[bulkLangWarnVar]].
    */
  private val tablePendingVar  = Var(Option.empty[List[TabularRow]])
  private val tableLangWarnVar = Var(Option.empty[String])

  /** The rows the grid currently maps to, minus the heading row when the reader ticked that box. */
  private def mappedRows(): List[TabularRow] = {
    val body = gridVar.now().getOrElse(Nil).drop(if (headerVar.now()) 1 else 0)
    TagEditorPage.rowsFor(body, rolesVar.now())
  }

  /** Abandons the table and puts the raw text back on the free-text path — the escape hatch for a file that is
    * technically delimited but is not really a word table.
    */
  private def dismissTable(): Unit = {
    Var.set(
      gridVar          -> None,
      guessVar         -> List.empty[ColumnLanguageGuess],
      rolesVar         -> Map.empty[Int, TagEditorPage.ColumnRole],
      headerVar        -> false,
      tablePendingVar  -> None,
      tableLangWarnVar -> None,
    )
  }

  /** Splits the text if it is a table and opens the mapping step, or answers false so the caller falls through to the
    * free-text import. The one decision that keeps the old path intact.
    */
  private def offerTable(text: String): Boolean = {
    DelimitedText.sniff(text) match {
      case Some(delimiter) =>
        val grid             = DelimitedText.parse(text, delimiter)
        // A first row that names the languages being imported is not a row of words: importing it mints a word called
        // `Német` and pairs it with `Magyar`. The reader can still untick it.
        val heading          = grid.headOption.exists(ColumnHeading.isHeaderRow)
        Var.set(
          gridVar          -> Some(grid),
          headerVar        -> heading,
          rolesVar         -> Map.empty[Int, TagEditorPage.ColumnRole],
          guessVar         -> List.empty[ColumnLanguageGuess],
          tablePendingVar  -> None,
          tableLangWarnVar -> None,
          bulkResultVar    -> None,
          errorVar         -> None,
          bulkLangWarnVar  -> None,
          bulkBusy         -> true,
        )
        colCheckBus.emit(grid)
        true
      case None            =>
        false
    }
  }

  /** The warning for a column mapped to a language the dictionary disagrees with. `{0}` is what the reader chose, `{1}`
    * what was detected.
    */
  private def tableWarning(chosen: WordLanguage, detected: WordLanguage): String = {
    I18n.t(UiKeys.tagsEditorBulkTableLangMismatch, Labels.language(chosen), Labels.language(detected))
  }

  /** The mapped word and translation columns whose detected language contradicts the role they were given. A column the
    * dictionary did not recognise at all says nothing either way and is not a disagreement.
    */
  private def tableMismatch(): Option[String] = {
    val roles   = rolesVar.now()
    val guesses = guessVar.now()

    def check(role: TagEditorPage.ColumnRole, chosen: WordLanguage): Option[String] = {
      for {
        index    <- roles.collectFirst { case (column, `role`) => column }
        guess    <- guesses.find(_.index == index)
        detected <- guess.best
        if detected != chosen
      } yield tableWarning(chosen, detected)
    }

    check(TagEditorPage.ColumnRole.Source, sourceLangVar.now())
      .orElse(check(TagEditorPage.ColumnRole.Target, targetLangVar.now()))
  }

  /** The language guard, kept but moved to the mapping step: a disagreement stops here with confirm-or-cancel, and
    * anything else goes straight to the import.
    */
  private def gateTabularImport(): Unit = {
    val rows = mappedRows()
    if (rows.nonEmpty) {
      tableMismatch() match {
        case Some(warning) =>
          Var.set(tablePendingVar -> Some(rows), tableLangWarnVar -> Some(warning))
        case None          =>
          Var.set(tablePendingVar -> None, tableLangWarnVar -> None)
          tabularBus.emit(rows)
      }
    }
  }

  /** Sends the text to the server's language check before importing. A recognised sample goes straight to [[bulkBus]];
    * a poorly-recognised one stops here with a confirm-or-cancel warning; a failed check is not the reader's problem,
    * so it falls through to the import.
    */
  private def gateBulkImport(text: String): Unit = {
    if (text.trim.nonEmpty && !offerTable(text)) {
      Var.set(bulkLangWarnVar -> None, bulkPendingVar -> None, bulkBusy -> true)
      langCheckBus.emit(text)
    }
  }

  /** The free-text import, with the table detection deliberately skipped — what "import as plain text" runs after the
    * reader has seen the mapping step and decided the file is not a table after all.
    */
  private def forceTextImport(text: String): Unit = {
    dismissTable()
    if (text.trim.nonEmpty) {
      Var.set(bulkLangWarnVar -> None, bulkPendingVar -> None, bulkBusy -> true)
      langCheckBus.emit(text)
    }
  }

  /** The warning copy for a flagged sample: how many of the sampled words matched neither dictionary, and which two
    * languages were checked.
    */
  private def langWarning(result: LanguageCheckResponse): String = {
    val languages = List(sourceLangVar.now(), targetLangVar.now()).map(Labels.language).mkString(" / ")
    I18n.t(
      UiKeys.tagsEditorBulkLangMismatch,
      result.unrecognized.toString,
      result.sampled.toString,
      languages,
    )
  }

  private def clearBulkWarning(): Unit = {
    if (bulkLangWarnVar.now().isDefined) Var.set(bulkLangWarnVar -> None, bulkPendingVar -> None)
    // Editing the textarea invalidates a grid parsed from its previous contents; the next import re-sniffs it.
    if (gridVar.now().isDefined) dismissTable()
  }

  /** One file input for the panel: a photo is read with OCR in the browser first, anything else is read as plain text.
    * Either way the text goes through [[gateBulkImport]], the same as a pasted list.
    */
  private def handleBulkFile(file: dom.File): Unit = {
    if (file.size > maxBulkBytes) {
      errorVar.set(Some(I18n.t(UiKeys.wordsBulkUploadSizeError)))
    } else if (file.`type`.startsWith("image/")) {
      Var.set(bulkBusy -> true, bulkResultVar -> None, errorVar -> None, bulkLangWarnVar -> None)
      recognize(file, sourceLangVar.now(), targetLangVar.now(), _ => ()).onComplete {
        case Success(text) => bulkBusy.set(false); bulkTextVar.set(text); gateBulkImport(text)
        case Failure(_)    => bulkBusy.set(false); errorVar.set(Some(I18n.t(UiKeys.wordsBulkUploadImageError)))
      }
    } else {
      Var.set(bulkResultVar -> None, errorVar -> None, bulkLangWarnVar -> None)
      val reader = new dom.FileReader()
      reader.onload = { (_: dom.Event) =>
        val text = reader.result.asInstanceOf[String]
        bulkTextVar.set(text)
        gateBulkImport(text)
      }
      reader.onerror = { (_: dom.Event) => errorVar.set(Some(I18n.t(MessageKeys.requestFailed))) }
      reader.readAsText(file)
    }
  }

  private def posOf(ref: TagPairWord): Option[PartOfSpeech] = ref match {
    case TagPairWord.New(_, _, pos, _) => Some(pos)
    case _                             => None
  }

  /** A write with the guest detour in front of it — copied from `TagsPage.asReader`/`GameSetupPage.asReader`.
    * `POST /api/games` needs a session and this page is readable signed out, so a guest is minted first and the call is
    * retried against the session that creates. Signed in, the mint is skipped entirely.
    */
  private def asReader[A](write: () => EventStream[Either[ApiError, A]]): EventStream[Either[ApiError, A]] = {
    readerVar.now() match {
      case Some(_) =>
        write()
      case None    =>
        ApiClient.createGuest.flatMapSwitch {
          case Right(response) =>
            AppState.setUser(response.user)
            write()
          case Left(err)       =>
            EventStream.fromValue(Left(err))
        }
    }
  }

  /** Puts the two language selects on the tag's own stored pair — the tag is the source of truth now, not the rows. */
  private def applyLangsFrom(tag: Tag): Unit = {
    sourceLangVar.set(tag.sourceLanguage)
    targetLangVar.set(tag.targetLanguage)
  }

  def render(): HtmlElement = {
    div(
      cls := "max-w-3xl mx-auto",
      Alert.maybeError(errorVar.signal),
      Alert.maybeWarning(warningVar.signal),
      div(
        cls := "card bg-base-100 shadow mt-4",
        div(
          cls := "card-body",
          inlineRename.renderTitle(
            tagNameSignal,
            mayManageSignal,
            I18n.t(UiKeys.wordsTagRenameButton),
            I18n.t(UiKeys.wordsTagRenameLabel),
            "input text-xl",
            deleteIcon(),
          ),
          child.maybe <-- canEditSignal.map(can =>
            Option.unless(can)(p(cls := "text-sm opacity-70 mt-2", I18n.t(UiKeys.tagsEditorReadOnly)))
          ),
          child.maybe <-- tagVar.signal.map(_.map(renderActionButtons)),
          child.maybe <-- tagVar.signal.map(_.map(renderDeleteModal)),
          child.maybe <-- canEditSignal.map(Option.when(_)(renderBulkDeleteModal())),
          child.maybe <-- canEditSignal.map(Option.when(_)(renderDeleteWordsModal())),
          renderLanguages(),
          renderFilters(),
          child.maybe <-- canEditSignal.map(Option.when(_)(renderSelectionBar())),
          renderRows(),
          renderPagination(),
          child.maybe <-- canEditSignal.map(Option.when(_)(renderAddRow())),
          child.maybe <-- canEditSignal.map(Option.when(_)(renderBulkPanel())),
        ),
      ),
      child.maybe <-- toastVar.signal.map(
        _.map(msg => {
          div(
            cls := "toast toast-top toast-end z-50",
            div(cls := "alert alert-warning", span(msg)),
          )
        })
      ),
      // -- paging --
      queryChanges --> onQuery,
      querySignal --> queryVar.writer,
      // -- data --
      AppState.currentUserSignal --> readerVar.writer,
      createGameBus.events --> Observer[Tag](_ => Var.set(creatingGameVar -> true, errorVar -> None)),
      createGameBus.events.flatMapSwitch(tag =>
        asReader(() => GameApiClient.create(tag.sourceLanguage, tag.targetLanguage, List(tag.id)))
      ) -->
        Observer[Either[ApiError, GameCreated]] {
          case Right(created) =>
            creatingGameVar.set(false)
            // This browser is the one that created it, so it is offered the rename control — the same mark
            // `GameSetupPage`/`TagsPage` make after their own create.
            GameOwnership.markOwned(created.slug)
            AppRouter.router.pushState(Page.GameInstance(created.slug))
          case Left(err)      =>
            Var.set(creatingGameVar -> false, errorVar -> Some(err.message))
        },
      reloadBus.events.flatMapSwitch(_ => WordApiClient.getTag(tagId)) --> Observer[Either[ApiError, Tag]] {
        case Right(tag) =>
          tagVar.set(Some(tag))
          applyLangsFrom(tag)
        case Left(err)  => errorVar.set(Some(err.message))
      },
      // One page of rows: whenever the address bar says a different one, and whenever a write has changed what is on
      // this one. `flatMapSwitch`, so a reader turning two pages quickly is answered by the second.
      listRequests --> Observer[TagEntryQuery](_ => Var.set(loadingVar -> true, errorVar -> None)),
      listRequests.flatMapSwitch(query => WordApiClient.tagEntriesPage(tagId, query)) -->
        Observer[Either[ApiError, TagEntryPage]] {
          case Right(page) =>
            Var.set(
              entriesVar  -> page.items,
              totalVar    -> page.total,
              hasPairsVar -> page.hasPairs,
              loadingVar  -> false,
              errorVar    -> None,
            )
            correctPagePastTheEnd(page)
          case Left(err)   =>
            Var.set(loadingVar -> false, errorVar -> Some(err.message))
        },
      // Saving a relanguage: one of the two selects changed while the tag has no practice pair. A failure (someone
      // raced a pair in) reverts the selects; success re-seeds them and re-fetches the rows. The swap button does
      // not come through here — it only trades the selects locally.
      langBus.events.flatMapSwitch { case (source, target) =>
        WordApiClient.setTagLanguages(tagId, source, target)
      } --> Observer[Either[ApiError, TagResponse]] {
        case Right(response) =>
          errorVar.set(None)
          tagVar.set(Some(response.tag))
          applyLangsFrom(response.tag)
          entriesBus.emit(())
        case Left(err)       =>
          errorVar.set(Some(err.message))
          tagVar.now().foreach(applyLangsFrom)
      },
      inlineRename.bindings(onSaved = Observer[TagResponse](response => tagVar.set(Some(response.tag)))),
      deleteBus.events.flatMapSwitch(_ => WordApiClient.deleteTag(tagId)) --> Observer[Either[ApiError, Unit]] {
        case Right(_)  => AppRouter.router.pushState(Page.Tags())
        case Left(err) => Var.set(deleteOpenVar -> false, errorVar -> Some(err.message))
      },
      exportBus.events.flatMapSwitch(_ => WordApiClient.exportTag(tagId)) -->
        Observer[Either[ApiError, TagExportFile]] {
          case Right(file) =>
            val name = tagVar.now().map(_.name).getOrElse("tag")
            Download.text(s"${Download.slug(name)}.tag.json", file.toJson)
          case Left(err)   =>
            errorVar.set(Some(err.message))
        },
      // `addPair` is idempotent, so an exact repeat comes back as a row already on the list; `onEntryAdded` refuses it
      // with a toast and a flash on the row that is already there.
      addRowBus.events.flatMapSwitch(input => WordApiClient.addPair(tagId, input)) -->
        Observer[Either[ApiError, gathedge.shared.dto.TagEntryResponse]](onEntryAdded),
      // Enter on the empty answer box: add the committed source word on its own, no answer marked.
      addWordBus.events.flatMapSwitch(word => WordApiClient.attachWord(tagId, TagWordInput(word))) -->
        Observer[Either[ApiError, gathedge.shared.dto.TagEntryResponse]](onEntryAdded),
      replaceBus.events
        .sample(editingVar.signal, editSourceVar.signal, editTargetVar.signal)
        .collect { case (Some((oldSource, oldTarget)), Some(src), Some(tgt)) => (oldSource, oldTarget, src, tgt) }
        .flatMapSwitch { case (oldSource, oldTarget, src, tgt) =>
          WordApiClient.replacePair(tagId, oldSource, oldTarget, TagPairInput(src, tgt))
        } --> Observer[Either[ApiError, gathedge.shared.dto.TagEntryResponse]] {
        case Right(response) =>
          warningVar.set(response.warning.map(I18n.resolve))
          cancelEdit()
          entriesBus.emit(())
        case Left(err)       =>
          errorVar.set(Some(err.message))
      },
      // `flatMapMerge`, not `flatMapSwitch`: deletes of different rows run to completion side by side rather than the
      // newer one cancelling the older. A repeat of the *same* row cannot reach here — `requestRowDelete` gates it.
      deleteRowBus.events.flatMapMerge { case key @ (sourceWordId, targetWordId) =>
        WordApiClient.deletePair(tagId, sourceWordId, targetWordId).map(result => key -> result)
      } -->
        Observer[((Long, Option[Long]), Either[ApiError, Unit])] { case (key, result) =>
          deletingRowsVar.update(_ - key)
          result match {
            case Right(_)  =>
              // Drop only the row that was removed, so it goes at once. A source word with several marked
              // translations keeps its other rows; an answer-less row (`targetWordId` empty) takes the whole source
              // word with it. Then re-read the page: a page is a cut of a longer list, and the row that has just left
              // it is a place for the next one to move up into.
              entriesVar.update(_.filterNot { row =>
                row.source.id == key._1 && (key._2.isEmpty || row.target.map(_.id) == key._2)
              })
              reloadRows()
            case Left(err) => errorVar.set(Some(err.message))
          }
        },
      langCheckBus.events
        .filter(_.trim.nonEmpty)
        .flatMapSwitch { text =>
          WordApiClient
            .checkLanguage(text, sourceLangVar.now(), targetLangVar.now())
            .map(text -> _)
        } --> Observer[(String, Either[ApiError, LanguageCheckResponse])] {
        case (text, Right(result)) if result.acceptable =>
          Var.set(bulkLangWarnVar -> None, bulkPendingVar -> None)
          bulkBus.emit(text)
        case (text, Right(result))                      =>
          Var.set(bulkBusy -> false, bulkPendingVar -> Some(text), bulkLangWarnVar -> Some(langWarning(result)))
        case (text, Left(_))                            =>
          // A failed check must not block the reader — import as if it had passed.
          Var.set(bulkLangWarnVar -> None, bulkPendingVar -> None)
          bulkBus.emit(text)
      },
      bulkBus.events
        .filter(_.trim.nonEmpty)
        .flatMapSwitch { text =>
          bulkBusy.set(true)
          WordApiClient.bulkImport(tagId, text, sourceLangVar.now(), targetLangVar.now())
        } --> Observer[Either[ApiError, BulkImportResponse]] {
        case Right(result) =>
          bulkBusy.set(false)
          bulkResultVar.set(
            Some(
              I18n.t(
                UiKeys.tagsEditorBulkResult,
                result.added.toString,
                result.verifiedPairs.toString,
                result.unmatched.toString,
              )
            )
          )
          Var.set(bulkTextVar -> "", bulkLangWarnVar -> None, bulkPendingVar -> None)
          entriesBus.emit(())
        case Left(err)     =>
          bulkBusy.set(false)
          errorVar.set(Some(err.message))
      },
      colCheckBus.events
        .flatMapSwitch { grid =>
          // Every column is offered, including ones the reader will ignore — the suggestion is only useful if it can
          // speak about a column nobody has assigned yet. A recognised heading row is dropped from the sample, since
          // `Német` is not a German word and would score against the very column it labels.
          val body    = if (grid.headOption.exists(ColumnHeading.isHeaderRow)) grid.drop(1) else grid
          val columns = body.headOption.getOrElse(Nil).indices.toList.map { index =>
            ColumnSample(index, body.map(_.lift(index).getOrElse("")))
          }
          WordApiClient.checkColumnLanguages(columns)
        } --> Observer[Either[ApiError, ColumnLanguageCheckResponse]] {
        case Right(response) =>
          val columns = gridVar.now().flatMap(_.headOption).map(_.size).getOrElse(0)
          bulkBusy.set(false)
          guessVar.set(response.columns)
          rolesVar.set(
            TagEditorPage.suggestRoles(response.columns, columns, sourceLangVar.now(), targetLangVar.now())
          )
        case Left(_)         =>
          // A failed check must not block the reader, the same rule the free-text guard follows: the mapping step
          // still opens, just with nothing suggested.
          val columns = gridVar.now().flatMap(_.headOption).map(_.size).getOrElse(0)
          bulkBusy.set(false)
          guessVar.set(Nil)
          rolesVar.set(TagEditorPage.suggestRoles(Nil, columns, sourceLangVar.now(), targetLangVar.now()))
      },
      tabularBus.events
        .filter(_.nonEmpty)
        .flatMapSwitch { rows =>
          bulkBusy.set(true)
          WordApiClient.tabularImport(tagId, rows, sourceLangVar.now(), targetLangVar.now())
        } --> Observer[Either[ApiError, TabularImportResponse]] {
        case Right(result) =>
          bulkBusy.set(false)
          bulkResultVar.set(
            Some(
              I18n.t(
                UiKeys.tagsEditorBulkTableResult,
                result.rows.toString,
                result.pairs.toString,
                result.newWords.toString,
                result.forms.toString,
              )
            )
          )
          bulkTextVar.set("")
          dismissTable()
          entriesBus.emit(())
        case Left(err)     =>
          bulkBusy.set(false)
          Var.set(tablePendingVar -> None, tableLangWarnVar -> None)
          errorVar.set(Some(err.message))
      },
      // A selected row that leaves the page — a turn, a filter change, a delete — drops out of the selection, so
      // "Select all" only ever holds rows the reader can see and the bulk delete cannot touch one they cannot.
      entriesVar.signal --> Observer[List[TagEntry]] { rows =>
        val shown = rows.map(TagEditorPage.rowKey)
        visibleKeysVar.set(shown)
        selectedVar.update(_.intersect(shown.toSet))
      },
      bulkDeleteBus.events
        .flatMapSwitch(_ => WordApiClient.deletePairsBulk(tagId, selectedVar.now().toList)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(selectedVar -> Set.empty[(Long, Option[Long])], bulkDeleteOpen -> false)
            entriesBus.emit(())
          case Left(err) =>
            bulkDeleteOpen.set(false)
            errorVar.set(Some(err.message))
        },
      deleteWordsBus.events
        .flatMapSwitch(_ =>
          WordApiClient.deleteWords(tagId, eligibleWordIdsFrom(entriesVar.now(), selectedVar.now()))
        ) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(selectedVar -> Set.empty[(Long, Option[Long])], deleteWordsOpen -> false)
            entriesBus.emit(())
          case Left(err) =>
            deleteWordsOpen.set(false)
            errorVar.set(Some(err.message))
        },
      onMountCallback(_ => { reloadBus.emit(()); entriesBus.emit(()) }),
    )
  }

  private def deleteIcon(): Modifier[HtmlElement] = {
    child.maybe <-- mayManageSignal.map(
      Option.when(_)(
        InlineRename.iconButton(
          I18n.t(UiKeys.wordsTagDeleteButton),
          trashMark(),
          onClick.mapToUnit --> Observer[Unit](_ => deleteOpenVar.set(true)),
        )
      )
    )
  }

  /** The row of buttons under the title: "Export" (saves the whole tag as a JSON file through [[exportBus]]), "Create
    * game" (a game over this wordlist alone, in its own declared language pair, straight to the game's own page — the
    * same shortcut `TagsPage.renderCreateGameCell` offers per row) and "View games" (the [[Page.AllGames]] catalog
    * pre-filtered to this wordlist, `TagsPage.renderViewGamesCell`'s counterpart). All three are shown to every reader,
    * since none of them is gated by ownership.
    */
  private def renderActionButtons(tag: Tag): HtmlElement = {
    div(
      cls := "flex flex-wrap gap-2 mt-2",
      button(
        cls := "btn btn-sm",
        typ := "button",
        I18n.t(UiKeys.tagsExportButton),
        onClick.mapToUnit --> exportBus.writer,
      ),
      button(
        typ := "button",
        cls := "btn btn-sm btn-soft",
        disabled <-- creatingGameVar.signal,
        child.maybe <-- creatingGameVar.signal.map(creating =>
          Option.when(creating)(span(cls := "loading loading-spinner loading-xs"))
        ),
        I18n.t(UiKeys.tagsListCreateGame),
        onClick.mapToUnit --> Observer[Unit](_ => createGameBus.emit(tag)),
      ),
      a(
        cls := "btn btn-sm btn-soft",
        AppRouter.router.navigateTo(Page.AllGames(AllGameQuery.default.copy(tagId = Some(tag.id)))),
        I18n.t(UiKeys.tagsListViewGames),
      ),
    )
  }

  private def renderLanguages(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-end gap-3 mt-3",
      languageSelect(UiKeys.wordsLanguageLabel, sourceLangVar),
      child <-- canEditSignal.map(can => if (can) renderLangSwap() else span(cls := "pb-2", "→")),
      languageSelect(UiKeys.wordsTargetLabel, targetLangVar),
    )
  }

  /** Trades the two language selects. This is a view choice, not a change to the tag: nothing is saved and no request
    * is made. The headings, the rows ([[TagEditorPage.orient]]) and the add/edit boxes all follow the two selects, and
    * the server places a pair by language rather than by order, so add and edit keep working. Live even once the pair
    * is locked — the swap never touches the stored pair.
    */
  private def renderLangSwap(): HtmlElement = {
    span(
      cls             := "tooltip pb-1",
      dataAttr("tip") := I18n.t(UiKeys.wordsSwapLanguages),
      button(
        typ        := "button",
        cls        := "btn btn-ghost btn-sm btn-square",
        aria.label := I18n.t(UiKeys.wordsSwapLanguages),
        swapMark(),
        onClick.mapToUnit --> Observer[Unit] { _ =>
          val source = targetLangVar.now()
          val target = sourceLangVar.now()
          sourceLangVar.set(source)
          targetLangVar.set(target)
          errorVar.set(None)
        },
      ),
    )
  }

  private def swapMark(): SvgElement = {
    svg.svg(
      svg.cls            := "h-4 w-4",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "2",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(svg.d := "M7.5 21 3 16.5m0 0L7.5 12M3 16.5h13.5m0-13.5L21 7.5m0 0L16.5 12M21 7.5H7.5"),
    )
  }

  private def languageSelect(labelKey: String, langVar: Var[WordLanguage]): HtmlElement = {
    label(
      cls := "flex flex-col gap-1 tooltip",
      dataAttr("tip") <-- langsLocked.map(locked => if (locked) I18n.t(UiKeys.wordsLanguagesLockedHint) else ""),
      span(cls := "label-text text-xs", I18n.t(labelKey)),
      select(
        cls    := "select select-sm w-28",
        disabled <-- langsLocked,
        WordLanguage.all.map(l => option(value := WordLanguage.code(l), Labels.language(l))),
        controlled(
          value <-- langVar.signal.map(WordLanguage.code),
          onChange.mapToValue --> Observer[String] { code =>
            langVar.set(WordLanguage.fromString(code).getOrElse(WordLanguage.En))
            langBus.emit((sourceLangVar.now(), targetLangVar.now()))
          },
        ),
      ),
    )
  }

  /** The chip row. Every chip writes to the address bar rather than to a `Var` of its own, and every one of them starts
    * the listing again at the first page — page 4 of the wider list says nothing about the narrowed one, the rule
    * `TagEntryQuery.reset` carries for every listing here.
    */
  private def renderFilters(): HtmlElement = {
    def chip(bucket: EntryBucket, labelKey: String) = {
      button(
        typ := "button",
        cls := "btn btn-xs",
        cls("btn-primary") <-- bucketsSignal.map(_.contains(bucket)),
        I18n.t(labelKey),
        onClick.mapToUnit --> Observer[Unit](_ => change(_.toggleBucket(bucket))),
      )
    }

    def toggle(on: Signal[Boolean], labelKey: String, edit: TagEntryQuery => TagEntryQuery) = {
      button(
        typ := "button",
        cls := "btn btn-xs",
        cls("btn-primary") <-- on,
        I18n.t(labelKey),
        onClick.mapToUnit --> Observer[Unit](_ => change(_.reset(edit))),
      )
    }

    div(
      cls := "flex flex-wrap items-center gap-2 mt-3",
      span(cls := "text-sm opacity-70", I18n.t(UiKeys.tagsEditorFilterHeading)),
      chip(EntryBucket.Verified, UiKeys.tagsEditorFilterVerified),
      chip(EntryBucket.Paired, UiKeys.tagsEditorFilterPaired),
      chip(EntryBucket.Other, UiKeys.tagsEditorFilterOther),
      chip(EntryBucket.Unmatched, UiKeys.tagsEditorFilterUnmatched),
      toggle(importedBySignal, UiKeys.tagsEditorFilterImportedByMe, q => q.copy(importedByMe = !q.importedByMe)),
      toggle(uniqueToTagSignal, UiKeys.tagsEditorFilterUniqueToTag, q => q.copy(uniqueToTag = !q.uniqueToTag)),
    )
  }

  /** Select all / deselect all over the visible rows, and the bulk-delete trigger. Shown only to an editor. */
  private def renderSelectionBar(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-center gap-2 mt-3",
      button(
        typ := "button",
        cls := "btn btn-xs",
        I18n.t(UiKeys.tagsEditorSelectAll),
        onClick.mapToUnit --> Observer[Unit](_ => selectedVar.set(visibleKeysVar.now().toSet)),
      ),
      button(
        typ := "button",
        cls := "btn btn-xs",
        disabled <-- selectedVar.signal.map(_.isEmpty),
        I18n.t(UiKeys.tagsEditorDeselectAll),
        onClick.mapToUnit --> Observer[Unit](_ => selectedVar.set(Set.empty)),
      ),
      button(
        typ := "button",
        cls := "btn btn-xs btn-error",
        disabled <-- selectedVar.signal.map(_.isEmpty),
        child.text <-- selectedVar.signal.map(s => I18n.t(UiKeys.tagsEditorDeleteSelected, s.size.toString)),
        onClick.mapToUnit --> Observer[Unit](_ => bulkDeleteOpen.set(true)),
      ),
      button(
        typ := "button",
        cls := "btn btn-xs btn-error btn-outline",
        disabled <-- eligibleWordIds.map(_.isEmpty),
        child.text <-- eligibleWordIds.map(ids => I18n.t(UiKeys.tagsEditorDeleteWords, ids.size.toString)),
        onClick.mapToUnit --> Observer[Unit](_ => deleteWordsOpen.set(true)),
      ),
    )
  }

  /** The page control under the rows, hidden while the filter admits none — the table already says so in words, and a
    * row of buttons over an empty table offers nothing to press. Built once and kept, rather than rebuilt with every
    * row change: the boolean it follows is `.distinct`.
    *
    * `total` counts the words the filter admits, not the rows drawn: a word with two marked answers brings both, so a
    * page can hold a row or two more than its size. The count and the wording agree on that — "121 words".
    */
  private def renderPagination(): HtmlElement = {
    div(
      child.maybe <-- totalSignal
        .map(_ > 0L)
        .distinct
        .map(
          Option.when(_)(
            Pagination.render(
              page = pageSignal,
              total = totalSignal,
              pageSize = pageSizeSignal,
              onPage = Observer[Int](page => change(_.copy(page = page))),
              onPageSize = Observer[Int](size => change(_.reset(_.copy(pageSize = size)))),
              summary = totalSignal.map(total => I18n.plural(UiKeys.tagsEditorCount, total)).distinct,
              busy = loadingSignal,
            )
          )
        )
    )
  }

  private def renderRows(): HtmlElement = {
    div(
      cls := "mt-4",
      child <-- entriesVar.signal.map { rows =>
        if (rows.isEmpty) p(cls := "opacity-60 text-sm", I18n.t(UiKeys.tagsEditorEmpty))
        else {
          // This page's rows, which is what the heading's tick box acts on — the selection bar's "Select all" is the
          // wider one, over every row the filters show. A reader ticking the heading means "these", and the rows they
          // can see are the ones they are answering about.
          val pageKeys = rows.map(TagEditorPage.rowKey).toSet
          table(
            cls := "table table-sm",
            thead(
              tr(
                th(
                  cls := "w-4",
                  child.maybe <-- canEditSignal.map(
                    Option.when(_)(
                      input(
                        typ := "checkbox",
                        cls := "checkbox checkbox-xs",
                        checked <-- selectedVar.signal.map(s => pageKeys.nonEmpty && pageKeys.subsetOf(s)),
                        onInput.mapToChecked --> Observer[Boolean](on =>
                          selectedVar.update(s => if (on) s ++ pageKeys else s -- pageKeys)
                        ),
                      )
                    )
                  ),
                ),
                th(child.text <-- sourceLangVar.signal.map(Labels.language)),
                th(child.text <-- targetLangVar.signal.map(Labels.language)),
                th(I18n.t(UiKeys.wordsColPos)),
                th(""),
                th(""),
              )
            ),
            tbody(
              rows.map(renderRow)
            ),
          )
        }
      },
    )
  }

  /** The reader's own note beside a word — the `(növény)` of `levél (növény)`. Muted and after the word, since it says
    * which sense was meant and is not part of the word itself.
    */
  private def renderComment(comment: Option[String]): Option[HtmlElement] = {
    comment.map(note => span(cls := "opacity-50 text-xs ml-1", s"($note)"))
  }

  /** The "New word" badge, shown beside a source or answer word this reader minted that no other tag of theirs holds.
    */
  private def newBadge(): HtmlElement =
    span(cls := "badge badge-accent badge-xs ml-1", I18n.t(UiKeys.tagsEditorNewBadge))

  /** One word column of a row: the word with its note and badge, or the "no answer" placeholder when the row has
    * nothing on this side.
    */
  private def renderWordCell(side: Option[TagEditorPage.Side]): HtmlElement = side match {
    case Some(s) =>
      span(Word.display(s.word), renderComment(s.comment), Option.when(s.isNew)(newBadge()))
    case None    =>
      span(cls := "opacity-40", I18n.t(UiKeys.tagsEditorNoAnswer))
  }

  private def renderRow(entry: TagEntry): HtmlElement = {
    val rowKey     = TagEditorPage.rowKey(entry)
    val isEditing  = editingVar.signal.map(_.contains(rowKey)).distinct
    val isDeleting = deletingRowsVar.signal.map(_.contains(rowKey)).distinct
    tr(
      cls("bg-base-200") <-- isEditing,
      cls("bg-warning/30 transition-colors duration-500") <-- flashRowVar.signal.map(_.contains(rowKey)),
      // While a delete is in flight the whole row is greyed out and stops taking clicks.
      cls("opacity-50 pointer-events-none") <-- isDeleting,
      td(
        cls := "w-4",
        child.maybe <-- canEditSignal.map(
          Option.when(_)(
            input(
              typ := "checkbox",
              cls := "checkbox checkbox-xs",
              checked <-- selectedVar.signal.map(_.contains(rowKey)),
              onClick.mapToUnit --> Observer[Unit](_ => toggleSelected(rowKey)),
            )
          )
        ),
      ),
      td(
        child <-- Signal.combine(isEditing, sourceLangVar.signal, targetLangVar.signal).map {
          case (true, _, _)         => editSourcePicker.render()
          case (false, left, right) => renderWordCell(TagEditorPage.orient(entry, left, right)._1)
        }
      ),
      td(
        child <-- Signal.combine(isEditing, sourceLangVar.signal, targetLangVar.signal).map {
          case (true, _, _)         => editTargetPicker.render()
          case (false, left, right) => renderWordCell(TagEditorPage.orient(entry, left, right)._2)
        }
      ),
      // One part of speech per row — a pair's two words share it. Hidden while the row is being edited, where the
      // pickers own it.
      td(
        child <-- isEditing.map {
          case true  => span()
          case false => span(cls := "opacity-70 text-xs", Labels.partOfSpeech(entry.source.partOfSpeech))
        }
      ),
      td(
        div(
          cls := "flex gap-1",
          Option.when(entry.matchKind == PairMatch.Verified)(
            span(cls := "badge badge-success badge-xs", I18n.t(UiKeys.tagsEditorVerifiedBadge))
          ),
          Option.when(entry.matchKind == PairMatch.Paired)(
            span(cls := "badge badge-info badge-xs", I18n.t(UiKeys.tagsEditorPairedBadge))
          ),
          Option.when(entry.imported && entry.matchKind == PairMatch.Manual)(
            span(cls := "badge badge-ghost badge-xs", I18n.t(UiKeys.tagsEditorImportedBadge))
          ),
        )
      ),
      td(
        child <-- Signal.combine(isEditing, canEditSignal, isDeleting).map {
          case (_, false, _)        => span()
          case (false, true, true)  =>
            // Button-shaped wrapper so the row keeps the exact height it has with the edit/delete buttons.
            div(
              cls := "flex gap-1",
              button(
                typ      := "button",
                cls      := "btn btn-ghost btn-sm",
                disabled := true,
                span(cls := "loading loading-spinner loading-xs", role := "status"),
              ),
            )
          case (true, true, _)      =>
            div(
              cls := "flex gap-1",
              button(
                typ := "button",
                cls := "btn btn-primary btn-xs",
                disabled <-- editSourceVar.signal.combineWith(editTargetVar.signal).map { case (s, t) =>
                  s.isEmpty || t.isEmpty
                },
                I18n.t(UiKeys.tagsEditorSaveRow),
                onClick.mapToUnit --> Observer[Unit](_ => replaceBus.emit(())),
              ),
              button(
                typ := "button",
                cls := "btn btn-ghost btn-xs",
                I18n.t(UiKeys.commonCancel),
                onClick.mapToUnit --> Observer[Unit](_ => cancelEdit()),
              ),
            )
          case (false, true, false) =>
            div(
              cls := "flex gap-1",
              InlineRename.iconButton(
                I18n.t(UiKeys.tagsEditorEditRow),
                pencilMark(),
                onClick.mapToUnit --> Observer[Unit](_ => startEdit(entry)),
              ),
              InlineRename.iconButton(
                I18n.t(UiKeys.tagsRemovePair),
                trashMark(),
                onClick.mapToUnit --> Observer[Unit](_ => requestRowDelete(entry)),
              ),
            )
        }
      ),
    )
  }

  private def renderAddRow(): HtmlElement = {
    div(
      cls := "mt-6 flex flex-col gap-2",
      h2(cls               := "text-lg font-semibold", I18n.t(UiKeys.tagsEditorAddHeading)),
      div(
        // One column below `sm`: side by side, the two boxes are too narrow to type a word in on a phone. `items-end`
        // is what keeps the two fields on one line side by side — only a gendered side carries article buttons above
        // its input, so aligning on the top would leave the other side's field a row higher.
        cls                := "grid grid-cols-1 sm:grid-cols-2 gap-4 items-end",
        dataAttr("testid") := "tag-add-row",
        addSourcePicker.render(),
        addTargetPicker.render(),
      ),
      p(cls                := "text-xs opacity-70", I18n.t(UiKeys.tagsEditorAddWordOnlyHint)),
    )
  }

  private def renderBulkPanel(): HtmlElement = {
    div(
      cls := "mt-6",
      div(
        cls := "flex items-center gap-1",
        button(
          typ := "button",
          cls := "btn btn-sm",
          I18n.t(UiKeys.tagsEditorBulkButton),
          onClick.mapToUnit --> Observer[Unit](_ => bulkOpenVar.update(!_)),
        ),
        HelpIcon.render(I18n.t(UiKeys.helpBulkImport)),
      ),
      child.maybe <-- bulkOpenVar.signal.map(
        Option.when(_)(
          div(
            cls := "mt-2 flex flex-col gap-2",
            input(
              typ         := "file",
              cls         := "file-input file-input-bordered file-input-sm w-full",
              accept      := "image/*,.txt,.csv,.tsv,text/plain,text/csv,text/tab-separated-values",
              disabled <-- bulkBusy.signal,
              onChange --> Observer[dom.Event] { event =>
                val target = event.target.asInstanceOf[dom.html.Input]
                Option(target.files).filter(_.length > 0).map(_.item(0)).foreach(handleBulkFile)
              },
            ),
            p(cls         := "text-xs opacity-70", I18n.t(UiKeys.tagsEditorBulkFileHint)),
            textArea(
              cls         := "textarea textarea-bordered w-full",
              rows        := 6,
              placeholder := I18n.t(UiKeys.tagsEditorBulkPlaceholder),
              controlled(
                value <-- bulkTextVar.signal,
                onInput.mapToValue --> Observer[String] { value => bulkTextVar.set(value); clearBulkWarning() },
              ),
            ),
            child.maybe <-- bulkLangWarnVar.signal.map(_.map { message =>
              div(
                cls := "alert alert-warning flex flex-col items-start gap-2 text-sm",
                span(message),
                div(
                  cls := "flex gap-2",
                  button(
                    typ := "button",
                    cls := "btn btn-xs btn-warning",
                    I18n.t(UiKeys.tagsEditorBulkImportAnyway),
                    onClick.mapToUnit --> Observer[Unit] { _ =>
                      bulkPendingVar.now().foreach(bulkBus.emit)
                      Var.set(bulkLangWarnVar -> None, bulkPendingVar -> None)
                    },
                  ),
                  button(
                    typ := "button",
                    cls := "btn btn-xs btn-ghost",
                    I18n.t(UiKeys.commonCancel),
                    onClick.mapToUnit --> Observer[Unit](_ => Var.set(bulkLangWarnVar -> None, bulkPendingVar -> None)),
                  ),
                ),
              )
            }),
            child.maybe <-- gridVar.signal.map(_.map(renderTableMapping)),
            div(
              cls         := "flex items-center gap-2",
              // Hidden while the mapping step is up: that step has its own two buttons, and offering a third that
              // would re-sniff the same text is only confusing.
              child.maybe <-- gridVar.signal.map(grid => {
                Option.when(grid.isEmpty)(
                  button(
                    typ := "button",
                    cls := "btn btn-primary btn-sm",
                    disabled <-- bulkBusy.signal.combineWith(bulkTextVar.signal).map { case (busy, text) =>
                      busy || text.trim.isEmpty
                    },
                    I18n.t(UiKeys.tagsEditorBulkSubmit),
                    onClick.mapToUnit --> Observer[Unit](_ => gateBulkImport(bulkTextVar.now())),
                  )
                )
              }),
              child.maybe <-- bulkResultVar.signal.map(_.map(text => span(cls := "text-sm opacity-70", text))),
            ),
          )
        )
      ),
    )
  }

  /** The column-mapping step: a preview of the first rows with a role picker above each column, the heading-row toggle,
    * and the two ways out — import the rows, or fall back to the free-text path.
    */
  private def renderTableMapping(grid: List[List[String]]): HtmlElement = {
    val columns = grid.headOption.map(_.size).getOrElse(0)
    val preview = grid.take(TagEditorPage.previewRows)

    div(
      cls := "border border-base-300 rounded-lg p-3 flex flex-col gap-3",
      h3(cls := "font-semibold", I18n.t(UiKeys.tagsEditorBulkTableHeading)),
      p(cls  := "text-xs opacity-70", I18n.t(UiKeys.tagsEditorBulkTableHint)),
      label(
        cls  := "label cursor-pointer justify-start gap-2 p-0",
        input(
          typ    := "checkbox",
          cls    := "checkbox checkbox-sm",
          controlled(
            checked <-- headerVar.signal,
            onInput.mapToChecked --> Observer[Boolean](headerVar.set),
          ),
        ),
        span(cls := "label-text text-sm", I18n.t(UiKeys.tagsEditorBulkTableFirstRowHeader)),
      ),
      div(
        cls  := "overflow-x-auto",
        table(
          cls := "table table-xs",
          thead(
            tr(
              (0 until columns).toList.map(index => {
                th(
                  cls := "align-top",
                  div(
                    cls := "flex flex-col gap-1",
                    select(
                      cls    := "select select-bordered select-xs",
                      TagEditorPage.ColumnRole.all.map(role =>
                        option(value := role.toString, roleLabel(role), selected <-- roleSignal(index, role))
                      ),
                      onChange.mapToValue --> Observer[String](value => {
                        TagEditorPage.ColumnRole.all
                          .find(_.toString == value)
                          .foreach(role => assignRole(index, role))
                      }),
                    ),
                    span(cls := "text-xs font-normal opacity-60", child.text <-- detectedLabel(index)),
                  ),
                )
              })
            )
          ),
          tbody(
            preview.zipWithIndex.map { case (row, rowIndex) =>
              tr(
                // The heading row is dimmed rather than hidden, so ticking the box visibly does something.
                cls("opacity-40") <-- headerVar.signal.map(_ && rowIndex == 0),
                (0 until columns).toList.map(index => td(row.lift(index).getOrElse(""))),
              )
            }
          ),
        ),
      ),
      child.maybe <-- tableLangWarnVar.signal.map(_.map { message =>
        div(
          cls := "alert alert-warning flex flex-col items-start gap-2 text-sm",
          span(message),
          div(
            cls := "flex gap-2",
            button(
              typ := "button",
              cls := "btn btn-xs btn-warning",
              I18n.t(UiKeys.tagsEditorBulkImportAnyway),
              onClick.mapToUnit --> Observer[Unit] { _ =>
                tablePendingVar.now().foreach(tabularBus.emit)
                Var.set(tableLangWarnVar -> None, tablePendingVar -> None)
              },
            ),
            button(
              typ := "button",
              cls := "btn btn-xs btn-ghost",
              I18n.t(UiKeys.commonCancel),
              onClick.mapToUnit --> Observer[Unit](_ => Var.set(tableLangWarnVar -> None, tablePendingVar -> None)),
            ),
          ),
        )
      }),
      child.maybe <-- readySignal.map(ready =>
        Option.unless(ready)(p(cls := "text-xs text-warning", I18n.t(UiKeys.tagsEditorBulkTableNeedBoth)))
      ),
      div(
        cls  := "flex items-center gap-2",
        button(
          typ := "button",
          cls := "btn btn-primary btn-sm",
          disabled <-- bulkBusy.signal.combineWith(readySignal).map { case (busy, ready) => busy || !ready },
          I18n.t(UiKeys.tagsEditorBulkTableSubmit),
          onClick.mapToUnit --> Observer[Unit](_ => gateTabularImport()),
        ),
        button(
          typ := "button",
          cls := "btn btn-ghost btn-sm",
          disabled <-- bulkBusy.signal,
          I18n.t(UiKeys.tagsEditorBulkTableAsText),
          onClick.mapToUnit --> Observer[Unit](_ => forceTextImport(bulkTextVar.now())),
        ),
      ),
    )
  }

  /** True once a word column and a translation column are both chosen — the minimum a row needs to mean anything. */
  private val readySignal: Signal[Boolean] = {
    rolesVar.signal.map(roles => {
      roles.values.exists(_ == TagEditorPage.ColumnRole.Source) &&
      roles.values.exists(_ == TagEditorPage.ColumnRole.Target)
    })
  }

  private def roleSignal(index: Int, role: TagEditorPage.ColumnRole): Signal[Boolean] = {
    rolesVar.signal.map(roles => roles.getOrElse(index, TagEditorPage.ColumnRole.Ignore) == role)
  }

  /** Assigns a role, taking it off whichever column held it. Every role but `Ignore` is unique, so picking "Word" for a
    * second column moves it rather than leaving two columns claiming to be the same thing.
    */
  private def assignRole(index: Int, role: TagEditorPage.ColumnRole): Unit = {
    rolesVar.update { roles =>
      val cleared = roles - index
      val freed   =
        if (role == TagEditorPage.ColumnRole.Ignore) cleared else cleared.filterNot { case (_, held) => held == role }
      if (role == TagEditorPage.ColumnRole.Ignore) freed else freed + (index -> role)
    }
    // The reader has overridden the mapping, so a warning raised against the old one no longer applies.
    Var.set(tableLangWarnVar -> None, tablePendingVar -> None)
  }

  private def roleLabel(role: TagEditorPage.ColumnRole): String = {
    role match {
      case TagEditorPage.ColumnRole.Ignore      =>
        I18n.t(UiKeys.tagsEditorBulkTableRoleIgnore)
      case TagEditorPage.ColumnRole.Source      =>
        I18n.t(UiKeys.tagsEditorBulkTableRoleSource)
      case TagEditorPage.ColumnRole.Target      =>
        I18n.t(UiKeys.tagsEditorBulkTableRoleTarget)
      case TagEditorPage.ColumnRole.SourceExtra =>
        I18n.t(UiKeys.tagsEditorBulkTableRoleSourceExtra)
      case TagEditorPage.ColumnRole.TargetExtra =>
        I18n.t(UiKeys.tagsEditorBulkTableRoleTargetExtra)
    }
  }

  private def detectedLabel(index: Int): Signal[String] = {
    guessVar.signal.map(guesses => {
      guesses.find(_.index == index).flatMap(_.best) match {
        case Some(language) =>
          I18n.t(UiKeys.tagsEditorBulkTableDetected, Labels.language(language))
        case None           =>
          I18n.t(UiKeys.tagsEditorBulkTableDetectedNone)
      }
    })
  }

  private def renderDeleteModal(tag: Tag): HtmlElement = {
    div(
      cls := "modal",
      cls("modal-open") <-- deleteOpenVar.signal,
      div(
        cls   := "modal-box w-full max-w-sm",
        h3(cls := "font-bold text-lg", I18n.t(UiKeys.wordsTagDeleteTitle)),
        p(cls  := "py-4", I18n.t(UiKeys.wordsTagDeleteConfirm, tag.name)),
        div(
          cls  := "modal-action",
          button(
            cls := "btn btn-sm",
            typ := "button",
            I18n.t(UiKeys.commonCancel),
            onClick.mapToUnit --> Observer[Unit](_ => deleteOpenVar.set(false)),
          ),
          button(
            cls := "btn btn-sm btn-error",
            typ := "button",
            I18n.t(UiKeys.wordsTagDeleteButton),
            onClick.mapToUnit --> deleteBus.writer,
          ),
        ),
      ),
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => deleteOpenVar.set(false))),
    )
  }

  private def renderBulkDeleteModal(): HtmlElement = {
    div(
      cls := "modal",
      cls("modal-open") <-- bulkDeleteOpen.signal,
      div(
        cls   := "modal-box w-full max-w-sm",
        h3(cls := "font-bold text-lg", I18n.t(UiKeys.tagsEditorBulkDeleteTitle)),
        p(
          cls  := "py-4",
          child.text <-- selectedVar.signal.map(s => I18n.t(UiKeys.tagsEditorBulkDeleteConfirm, s.size.toString)),
        ),
        div(
          cls  := "modal-action",
          button(
            cls := "btn btn-sm",
            typ := "button",
            I18n.t(UiKeys.commonCancel),
            onClick.mapToUnit --> Observer[Unit](_ => bulkDeleteOpen.set(false)),
          ),
          button(
            cls := "btn btn-sm btn-error",
            typ := "button",
            child.text <-- selectedVar.signal.map(s => I18n.t(UiKeys.tagsEditorDeleteSelected, s.size.toString)),
            onClick.mapToUnit --> bulkDeleteBus.writer,
          ),
        ),
      ),
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => bulkDeleteOpen.set(false))),
    )
  }

  private def renderDeleteWordsModal(): HtmlElement = {
    div(
      cls := "modal",
      cls("modal-open") <-- deleteWordsOpen.signal,
      div(
        cls   := "modal-box w-full max-w-md",
        h3(cls := "font-bold text-lg", I18n.t(UiKeys.tagsEditorDeleteWordsTitle)),
        p(
          cls  := "mt-3 text-error font-semibold",
          child.text <-- eligibleWordIds.map(ids => I18n.t(UiKeys.tagsEditorDeleteWordsWarning, ids.size.toString)),
        ),
        p(cls  := "py-3 text-sm opacity-80", I18n.t(UiKeys.tagsEditorDeleteWordsNote)),
        div(
          cls  := "modal-action",
          button(
            cls := "btn btn-sm",
            typ := "button",
            I18n.t(UiKeys.commonCancel),
            onClick.mapToUnit --> Observer[Unit](_ => deleteWordsOpen.set(false)),
          ),
          button(
            cls := "btn btn-sm btn-error",
            typ := "button",
            child.text <-- eligibleWordIds.map(ids => I18n.t(UiKeys.tagsEditorDeleteWords, ids.size.toString)),
            onClick.mapToUnit --> deleteWordsBus.writer,
          ),
        ),
      ),
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => deleteWordsOpen.set(false))),
    )
  }

  private def trashMark(): SvgElement = svg.svg(
    svg.cls            := "h-4 w-4",
    svg.viewBox        := "0 0 24 24",
    svg.fill           := "none",
    svg.stroke         := "currentColor",
    svg.strokeWidth    := "1.5",
    svg.strokeLineCap  := "round",
    svg.strokeLineJoin := "round",
    svg.path(
      svg.d := "m14.74 9-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 0 1-2.244 2.077H8.084a2.25 2.25 0 0 1-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 0 0-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 0 1 3.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 0 0-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 0 0-7.5 0"
    ),
  )

  private def pencilMark(): SvgElement = svg.svg(
    svg.cls            := "h-4 w-4",
    svg.viewBox        := "0 0 24 24",
    svg.fill           := "none",
    svg.stroke         := "currentColor",
    svg.strokeWidth    := "1.5",
    svg.strokeLineCap  := "round",
    svg.strokeLineJoin := "round",
    svg.path(
      svg.d := "m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L6.832 19.82a4.5 4.5 0 0 1-1.897 1.13l-2.685.8.8-2.685a4.5 4.5 0 0 1 1.13-1.897L16.863 4.487Zm0 0L19.5 7.125"
    ),
  )
}
