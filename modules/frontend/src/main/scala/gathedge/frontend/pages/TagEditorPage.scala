package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, InlineRename, Labels, WordPicker}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.ocr.ImageOcr
import gathedge.shared.domain.{PartOfSpeech, Tag, Word, WordLanguage}
import gathedge.shared.dto.{
  BulkImportResponse,
  ColumnLanguageCheckResponse,
  ColumnLanguageGuess,
  ColumnSample,
  LanguageCheckResponse,
  TabularImportResponse,
  TabularRow,
  TagEntry,
  TagPairInput,
  TagPairWord,
  TagResponse,
}
import gathedge.shared.i18n.{MessageKeys, UiKeys}
import gathedge.shared.parsing.{ColumnHeading, DelimitedText}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** The one screen for creating and editing a tag. Every add, edit and delete is its own API call the moment it is made,
  * so a half-built tag is an ordinary state, and arriving here to edit an existing tag is the same code path as
  * arriving here from `/tags/new`.
  *
  * Rows are shown the plain way `TagWordsList` showed them, with an edit icon beside the delete icon. Editing a row
  * swaps its two cells for the same [[WordPicker]] the add-a-row control uses; committing it calls `replacePair`. The
  * three filters (exact / non-exact / unmatched) narrow the list by each row's import provenance; with none selected
  * every row shows. The bulk-import panel feeds straight into this list — it writes every token as a row, in the pasted
  * order, and the reader sorts them out here.
  */
object TagEditorPage {
  def render(tagId: Long, recognize: ImageOcr.Recognize): HtmlElement = {
    AppShell.render(Page.TagDetail(tagId), new TagEditorPage(tagId, recognize).render())
  }

  /** A row's identity in the editor. One source word can carry more than one translation row, so the target id is part
    * of the key — keying on the source alone makes "edit" and the duplicate flash hit every row of that word.
    */
  private[pages] def rowKey(entry: TagEntry): (Long, Option[Long]) = (entry.source.id, entry.target.map(_.id))

  /** `addPair` is idempotent, so re-adding an existing pair returns a row already on the list. Same source *and* same
    * target — a different translation of the same word is not a duplicate.
    */
  private[pages] def isDuplicate(existing: List[TagEntry], entry: TagEntry): Boolean =
    existing.exists(row => rowKey(row) == rowKey(entry))

  /** The three mutually-exclusive provenance buckets, read off a row's import flags. */
  private[pages] enum EntryFilter { case Exact, NonExact, Unmatched }

  private[pages] def stateOf(entry: TagEntry): Option[EntryFilter] = {
    if (entry.exact) Some(EntryFilter.Exact)
    else if (entry.imported && entry.target.isDefined) Some(EntryFilter.NonExact)
    else if (entry.imported) Some(EntryFilter.Unmatched)
    else None
  }

  /** Whether the filters show a row: the selected buckets are OR'd (none = every bucket), and "imported by me" / "only
    * in this tag" AND on top of that.
    */
  private[pages] def rowVisible(
    entry: TagEntry,
    buckets: Set[EntryFilter],
    importedByMe: Boolean,
    uniqueToTag: Boolean,
  ): Boolean = {
    val bucketOk = buckets.isEmpty || stateOf(entry).exists(buckets.contains)
    val mineOk   = !importedByMe || (entry.createdByMe && entry.imported)
    val uniqueOk = !uniqueToTag || !entry.inMyOtherTags
    bucketOk && mineOk && uniqueOk
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

private final class TagEditorPage(tagId: Long, recognize: ImageOcr.Recognize) {

  /** Ceiling on a bulk-import file, matching `BulkUploadDialog.maxBytes`. */
  private val maxBulkBytes = 2 * 1024 * 1024

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

  private val reloadBus     = new EventBus[Unit]()
  private val entriesBus    = new EventBus[Unit]()
  private val deleteOpenVar = Var(false)
  private val deleteBus     = new EventBus[Unit]()

  private val inlineRename = new InlineRename[TagResponse](name => WordApiClient.renameTag(tagId, name))

  /** Content editing — the owner or any member of the tag's group. Rename/delete stay owner-only, like `TagDetailPage`.
    */
  private val canEditSignal: Signal[Boolean] = tagVar.signal.map(_.exists(_.editableByMe)).distinct
  private val ownedSignal: Signal[Boolean]   = tagVar.signal.map(_.exists(_.ownedByMe)).distinct

  private val tagNameSignal: Signal[String] =
    tagVar.signal.map(_.map(_.name).getOrElse(I18n.t(UiKeys.tagDetailTitle))).distinct

  // The tag's mandatory language pair. It is set at creation and stays editable here only while the tag has no practice
  // pair (`TagEntry.target` present on some row); a change is saved through `WordApiClient.setTagLanguages`.
  private val sourceLangVar                = Var(WordLanguage.De)
  private val targetLangVar                = Var(WordLanguage.Hu)
  private val langsLocked: Signal[Boolean] = entriesVar.signal.map(_.exists(_.target.isDefined)).distinct
  private val langBus                      = new EventBus[(WordLanguage, WordLanguage)]()

  // -- Filters --------------------------------------------------------------------------------

  import TagEditorPage.EntryFilter

  // Three mutually-exclusive buckets read off the import flags, OR'd within the set: none selected shows every bucket.
  private val filtersVar = Var(Set.empty[EntryFilter])

  // Two independent predicates that AND on top of the buckets. "Imported by me" — a word this reader minted (never in
  // the dictionary) that a bulk import wrote. "Only in this tag" — the source word is in no other tag the reader owns.
  private val importedByMeVar = Var(false)
  private val uniqueToTagVar  = Var(false)

  private val visibleEntries: Signal[List[TagEntry]] = {
    Signal
      .combine(entriesVar.signal, filtersVar.signal, importedByMeVar.signal, uniqueToTagVar.signal)
      .map { case (entries, buckets, importedByMe, uniqueToTag) =>
        if (buckets.isEmpty && !importedByMe && !uniqueToTag) entries
        else entries.filter(entry => TagEditorPage.rowVisible(entry, buckets, importedByMe, uniqueToTag))
      }
  }

  // -- Add-a-row control --------------------------------------------------------------------

  private val addSourceVar = Var(Option.empty[TagPairWord])
  private val addSourcePos = Var(Option.empty[PartOfSpeech])

  /** The id of the committed source word, when it is a dictionary word — what the target picker offers translations of.
    */
  private def existingId(ref: Option[TagPairWord]): Option[Long] = ref match {
    case Some(TagPairWord.Existing(id)) => Some(id)
    case _                              => None
  }

  private lazy val addSourcePicker: WordPicker = new WordPicker(
    language = sourceLangVar.signal,
    partOfSpeech = Val(None),
    onCommit = Observer[TagPairWord] { ref =>
      addSourceVar.set(Some(ref))
      addSourcePos.set(posOf(ref))
      dom.window.setTimeout(() => addTargetPicker.focus(), 0)
    },
    // A dictionary pick settles the pair's part of speech; the target search is then held to it.
    onCommitWord = Observer[Option[Word]](_.foreach(w => addSourcePos.set(Some(w.partOfSpeech)))),
    placeholderSignal = sourceLangVar.signal.map(l => I18n.t(UiKeys.tagsSourcePlaceholder, Labels.language(l))),
  )

  private lazy val addTargetPicker: WordPicker = new WordPicker(
    language = targetLangVar.signal,
    partOfSpeech = addSourcePos.signal,
    onCommit = Observer[TagPairWord] { ref =>
      addSourceVar.now() match {
        case Some(source) => submitAdd(source, ref)
        case None         => addSourcePicker.focus()
      }
    },
    placeholderSignal = targetLangVar.signal.map(l => I18n.t(UiKeys.tagsTargetPlaceholder, Labels.language(l))),
    translateFrom = addSourceVar.signal.map(existingId),
  )

  private def submitAdd(source: TagPairWord, target: TagPairWord): Unit = {
    addRowBus.emit(TagPairInput(source, target))
  }
  private val addRowBus                                                 = new EventBus[TagPairInput]()

  // -- Row editing -------------------------------------------------------------------------

  /** `(sourceWordId, oldTargetWordId)` of the row being edited, or `None`. */
  private val editingVar    = Var(Option.empty[(Long, Option[Long])])
  private val editSourceVar = Var(Option.empty[TagPairWord])
  private val editTargetVar = Var(Option.empty[TagPairWord])
  private val editSourcePos = Var(Option.empty[PartOfSpeech])

  private lazy val editSourcePicker: WordPicker = new WordPicker(
    language = sourceLangVar.signal,
    partOfSpeech = Val(None),
    onCommit = Observer[TagPairWord] { ref =>
      editSourceVar.set(Some(ref)); editSourcePos.set(posOf(ref)); editTargetPicker.focus()
    },
    onCommitWord = Observer[Option[Word]](_.foreach(w => editSourcePos.set(Some(w.partOfSpeech)))),
    placeholderSignal = sourceLangVar.signal.map(l => I18n.t(UiKeys.tagsSourcePlaceholder, Labels.language(l))),
  )

  private lazy val editTargetPicker: WordPicker = new WordPicker(
    language = targetLangVar.signal,
    partOfSpeech = editSourcePos.signal,
    // Committing the answer is the whole edit: save it and leave edit mode, the same way Enter on the add row's target
    // adds the pair. The explicit Save button stays for a mouse-only edit and does the same thing.
    onCommit = Observer[TagPairWord] { ref => editTargetVar.set(Some(ref)); replaceBus.emit(()) },
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

  /** The keys of the rows the filters currently show — kept in step with [[visibleEntries]] so "Select all" can read
    * them synchronously (a `Signal` has no public `now`).
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
    editSourceVar.set(Some(TagPairWord.Existing(entry.source.id)))
    editTargetVar.set(entry.target.map(w => TagPairWord.Existing(w.id)))
    editSourcePos.set(Some(entry.source.partOfSpeech))
    editSourcePicker.setText(Word.display(entry.source))
    editTargetPicker.setText(entry.target.map(Word.display).getOrElse(""))
  }

  private def cancelEdit(): Unit = {
    editingVar.set(None)
    editSourceVar.set(None); editTargetVar.set(None); editSourcePos.set(None)
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
            ownedSignal,
            I18n.t(UiKeys.wordsTagRenameButton),
            I18n.t(UiKeys.wordsTagRenameLabel),
            "input text-xl",
            deleteIcon(),
          ),
          child.maybe <-- canEditSignal.map(can =>
            Option.unless(can)(p(cls := "text-sm opacity-70 mt-2", I18n.t(UiKeys.tagsEditorReadOnly)))
          ),
          child.maybe <-- tagVar.signal.map(_.map(renderDeleteModal)),
          child.maybe <-- canEditSignal.map(Option.when(_)(renderBulkDeleteModal())),
          child.maybe <-- canEditSignal.map(Option.when(_)(renderDeleteWordsModal())),
          renderLanguages(),
          renderFilters(),
          child.maybe <-- canEditSignal.map(Option.when(_)(renderSelectionBar())),
          renderRows(),
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
      // -- data --
      reloadBus.events.flatMapSwitch(_ => WordApiClient.listTags) --> Observer[Either[ApiError, List[Tag]]] {
        case Right(tags) =>
          val found = tags.find(_.id == tagId)
          tagVar.set(found)
          found.foreach(applyLangsFrom)
        case Left(err)   => errorVar.set(Some(err.message))
      },
      entriesBus.events
        .flatMapSwitch(_ => WordApiClient.tagEntries(tagId)) --> Observer[Either[ApiError, List[TagEntry]]] {
        case Right(rows) => entriesVar.set(rows)
        case Left(err)   => errorVar.set(Some(err.message))
      },
      // Saving a language change while the tag has no pair; a failure (someone raced a pair in) reverts the selects.
      langBus.events.flatMapSwitch { case (source, target) =>
        WordApiClient.setTagLanguages(tagId, source, target)
      } --> Observer[Either[ApiError, TagResponse]] {
        case Right(response) =>
          errorVar.set(None)
          tagVar.set(Some(response.tag))
          applyLangsFrom(response.tag)
        case Left(err)       =>
          errorVar.set(Some(err.message))
          tagVar.now().foreach(applyLangsFrom)
      },
      inlineRename.bindings(onSaved = Observer[TagResponse](response => tagVar.set(Some(response.tag)))),
      deleteBus.events.flatMapSwitch(_ => WordApiClient.deleteTag(tagId)) --> Observer[Either[ApiError, Unit]] {
        case Right(_)  => AppRouter.router.pushState(Page.Tags)
        case Left(err) => Var.set(deleteOpenVar -> false, errorVar -> Some(err.message))
      },
      addRowBus.events.flatMapSwitch(input => WordApiClient.addPair(tagId, input)) --> Observer[
        Either[ApiError, gathedge.shared.dto.TagEntryResponse]
      ] {
        case Right(response) =>
          val entry = response.entry
          // `addPair` is idempotent, so an exact repeat comes back as a row already on the list. Refuse it the way the
          // old create screen did: a toast, and a flash on the row that is already there.
          if (TagEditorPage.isDuplicate(entriesVar.now(), entry)) {
            // Leave both inputs as they are — the reader edits one side rather than retyping the whole pair.
            showToast(
              I18n.t(
                UiKeys.tagsDuplicatePair,
                Word.display(entry.source),
                entry.target.map(Word.display).getOrElse(""),
              )
            )
            flashRow(TagEditorPage.rowKey(entry))
          } else {
            entriesVar.update(_ :+ entry)
            warningVar.set(response.warning.map(I18n.resolve))
            Var.set(addSourceVar -> None, addSourcePos -> None)
            addSourcePicker.clear(); addTargetPicker.clear(); addSourcePicker.focus()
          }
        case Left(err)       =>
          errorVar.set(Some(err.message))
      },
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
              // Drop only the row that was removed. A source word with several marked translations keeps its other
              // rows; an answer-less row (`targetWordId` empty) takes the whole source word with it.
              entriesVar.update(_.filterNot { row =>
                row.source.id == key._1 && (key._2.isEmpty || row.target.map(_.id) == key._2)
              })
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
                result.exactPairs.toString,
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
      // A selected row that a filter change hides drops out of the selection, so "Select all" only ever holds visible
      // rows and the bulk delete cannot touch a row the reader can't see.
      visibleEntries --> Observer[List[TagEntry]] { rows =>
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
    child.maybe <-- ownedSignal.map(
      Option.when(_)(
        InlineRename.iconButton(
          I18n.t(UiKeys.wordsTagDeleteButton),
          trashMark(),
          onClick.mapToUnit --> Observer[Unit](_ => deleteOpenVar.set(true)),
        )
      )
    )
  }

  private def renderLanguages(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-end gap-3 mt-3",
      languageSelect(UiKeys.wordsLanguageLabel, sourceLangVar),
      span("→"),
      languageSelect(UiKeys.wordsTargetLabel, targetLangVar),
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

  private def renderFilters(): HtmlElement = {
    def chip(f: EntryFilter, labelKey: String)       = {
      button(
        typ := "button",
        cls := "btn btn-xs",
        cls("btn-primary") <-- filtersVar.signal.map(_.contains(f)),
        I18n.t(labelKey),
        onClick.mapToUnit --> Observer[Unit](_ => filtersVar.update(s => if (s.contains(f)) s - f else s + f)),
      )
    }
    def toggle(flag: Var[Boolean], labelKey: String) = {
      button(
        typ := "button",
        cls := "btn btn-xs",
        cls("btn-primary") <-- flag.signal,
        I18n.t(labelKey),
        onClick.mapToUnit --> Observer[Unit](_ => flag.update(!_)),
      )
    }
    div(
      cls := "flex flex-wrap items-center gap-2 mt-3",
      span(cls := "text-sm opacity-70", I18n.t(UiKeys.tagsEditorFilterHeading)),
      chip(EntryFilter.Exact, UiKeys.tagsEditorFilterExact),
      chip(EntryFilter.NonExact, UiKeys.tagsEditorFilterNonExact),
      chip(EntryFilter.Unmatched, UiKeys.tagsEditorFilterUnmatched),
      toggle(importedByMeVar, UiKeys.tagsEditorFilterImportedByMe),
      toggle(uniqueToTagVar, UiKeys.tagsEditorFilterUniqueToTag),
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

  private def renderRows(): HtmlElement = {
    div(
      cls := "mt-4",
      child <-- visibleEntries.map { rows =>
        if (rows.isEmpty) p(cls := "opacity-60 text-sm", I18n.t(UiKeys.tagsEditorEmpty))
        else {
          val visibleKeys = rows.map(TagEditorPage.rowKey).toSet
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
                        checked <-- selectedVar.signal.map(s => visibleKeys.nonEmpty && visibleKeys.subsetOf(s)),
                        onInput.mapToChecked --> Observer[Boolean](on =>
                          selectedVar.update(s => if (on) s ++ visibleKeys else s -- visibleKeys)
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
        child <-- isEditing.map {
          case true  => editSourcePicker.render()
          case false => span(Word.display(entry.source), renderComment(entry.comment))
        }
      ),
      td(
        child <-- isEditing.map {
          case true  => editTargetPicker.render()
          case false =>
            entry.target match {
              case Some(w) => span(Word.display(w), renderComment(entry.targetComment))
              case None    => span(cls := "opacity-40", I18n.t(UiKeys.tagsEditorNoAnswer))
            }
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
          Option.when(entry.exact)(
            span(cls := "badge badge-success badge-xs", I18n.t(UiKeys.wordsBulkUploadExactBadge))
          ),
          Option.when(entry.imported && !entry.exact)(
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
      h2(cls := "text-lg font-semibold", I18n.t(UiKeys.tagsEditorAddHeading)),
      div(
        cls  := "grid grid-cols-2 gap-4 items-start",
        addSourcePicker.render(),
        addTargetPicker.render(),
      ),
    )
  }

  private def renderBulkPanel(): HtmlElement = {
    div(
      cls := "mt-6",
      button(
        typ := "button",
        cls := "btn btn-sm",
        I18n.t(UiKeys.tagsEditorBulkButton),
        onClick.mapToUnit --> Observer[Unit](_ => bulkOpenVar.update(!_)),
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
    svg.strokeWidth    := "2",
    svg.strokeLineCap  := "round",
    svg.strokeLineJoin := "round",
    svg.path(svg.d := "M4 7h16"),
    svg.path(svg.d := "M9 7V4h6v3"),
    svg.path(svg.d := "M6 7l1 13h10l1-13"),
  )

  private def pencilMark(): SvgElement = svg.svg(
    svg.cls            := "h-4 w-4",
    svg.viewBox        := "0 0 24 24",
    svg.fill           := "none",
    svg.stroke         := "currentColor",
    svg.strokeWidth    := "2",
    svg.strokeLineCap  := "round",
    svg.strokeLineJoin := "round",
    svg.path(svg.d := "M12 20h9"),
    svg.path(svg.d := "M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"),
  )
}
