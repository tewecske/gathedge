package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, InlineRename, Labels, WordPicker}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.ocr.ImageOcr
import gathedge.shared.domain.{PartOfSpeech, Tag, Word, WordLanguage}
import gathedge.shared.dto.{BulkImportResponse, LanguageCheckResponse, TagEntry, TagPairInput, TagPairWord, TagResponse}
import gathedge.shared.i18n.{MessageKeys, UiKeys}
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

  // The language pair is inferred from the rows once there are any, and locked then; an empty tag lets the reader
  // choose, and the first added pair persists the choice server-side.
  private val sourceLangVar                = Var(WordLanguage.De)
  private val targetLangVar                = Var(WordLanguage.Hu)
  private val langsLocked: Signal[Boolean] = entriesVar.signal.map(_.nonEmpty)

  // -- Filters --------------------------------------------------------------------------------

  private enum EntryFilter { case Exact, NonExact, Unmatched }
  private val filtersVar = Var(Set.empty[EntryFilter])

  private def stateOf(entry: TagEntry): Option[EntryFilter] = {
    if (entry.exact) Some(EntryFilter.Exact)
    else if (entry.imported && entry.target.isDefined) Some(EntryFilter.NonExact)
    else if (entry.imported) Some(EntryFilter.Unmatched)
    else None
  }

  private val visibleEntries: Signal[List[TagEntry]] = {
    entriesVar.signal.combineWith(filtersVar.signal).map { case (entries, filters) =>
      if (filters.isEmpty) entries
      else entries.filter(entry => stateOf(entry).exists(filters.contains))
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

  /** Sends the text to the server's language check before importing. A recognised sample goes straight to [[bulkBus]];
    * a poorly-recognised one stops here with a confirm-or-cancel warning; a failed check is not the reader's problem,
    * so it falls through to the import.
    */
  private def gateBulkImport(text: String): Unit = {
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

  private def clearBulkWarning(): Unit =
    if (bulkLangWarnVar.now().isDefined) Var.set(bulkLangWarnVar -> None, bulkPendingVar -> None)

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

  private def applyLangsFrom(entries: List[TagEntry]): Unit = {
    entries.headOption.foreach(e => sourceLangVar.set(e.source.language))
    entries.flatMap(_.target).headOption.foreach(w => targetLangVar.set(w.language))
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
          renderLanguages(),
          renderFilters(),
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
        case Right(tags) => tagVar.set(tags.find(_.id == tagId))
        case Left(err)   => errorVar.set(Some(err.message))
      },
      entriesBus.events
        .flatMapSwitch(_ => WordApiClient.tagEntries(tagId)) --> Observer[Either[ApiError, List[TagEntry]]] {
        case Right(rows) => entriesVar.set(rows); applyLangsFrom(rows)
        case Left(err)   => errorVar.set(Some(err.message))
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
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(labelKey)),
      select(
        cls    := "select select-sm w-28",
        disabled <-- langsLocked,
        WordLanguage.all.map(l => option(value := WordLanguage.code(l), Labels.language(l))),
        controlled(
          value <-- langVar.signal.map(WordLanguage.code),
          onChange.mapToValue --> Observer[String](code =>
            langVar.set(WordLanguage.fromString(code).getOrElse(WordLanguage.En))
          ),
        ),
      ),
    )
  }

  private def renderFilters(): HtmlElement = {
    def chip(f: EntryFilter, labelKey: String) = {
      button(
        typ := "button",
        cls := "btn btn-xs",
        cls("btn-primary") <-- filtersVar.signal.map(_.contains(f)),
        I18n.t(labelKey),
        onClick.mapToUnit --> Observer[Unit](_ => filtersVar.update(s => if (s.contains(f)) s - f else s + f)),
      )
    }
    div(
      cls := "flex flex-wrap items-center gap-2 mt-3",
      span(cls := "text-sm opacity-70", I18n.t(UiKeys.tagsEditorFilterHeading)),
      chip(EntryFilter.Exact, UiKeys.tagsEditorFilterExact),
      chip(EntryFilter.NonExact, UiKeys.tagsEditorFilterNonExact),
      chip(EntryFilter.Unmatched, UiKeys.tagsEditorFilterUnmatched),
    )
  }

  private def renderRows(): HtmlElement = {
    div(
      cls := "mt-4",
      child <-- visibleEntries.map { rows =>
        if (rows.isEmpty) p(cls := "opacity-60 text-sm", I18n.t(UiKeys.tagsEditorEmpty))
        else {
          table(
            cls := "table table-sm",
            thead(
              tr(
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
        child <-- isEditing.map {
          case true  => editSourcePicker.render()
          case false => span(Word.display(entry.source))
        }
      ),
      td(
        child <-- isEditing.map {
          case true  => editTargetPicker.render()
          case false =>
            entry.target match {
              case Some(w) => span(Word.display(w))
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
              accept      := "image/*,.txt,text/plain",
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
            div(
              cls         := "flex items-center gap-2",
              button(
                typ := "button",
                cls := "btn btn-primary btn-sm",
                disabled <-- bulkBusy.signal.combineWith(bulkTextVar.signal).map { case (busy, text) =>
                  busy || text.trim.isEmpty
                },
                I18n.t(UiKeys.tagsEditorBulkSubmit),
                onClick.mapToUnit --> Observer[Unit](_ => gateBulkImport(bulkTextVar.now())),
              ),
              child.maybe <-- bulkResultVar.signal.map(_.map(text => span(cls := "text-sm opacity-70", text))),
            ),
          )
        )
      ),
    )
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
