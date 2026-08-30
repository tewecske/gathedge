package gathedge.frontend.components

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.Tag
import gathedge.shared.dto.{TagExportFile, TagExportTag, TagImportChoice, TagImportResponse}
import gathedge.shared.i18n.UiKeys
import zio.json._

/** The "Import tags" modal, reached from `TagsPage`. Reads a JSON file the export produced, shows what it will do, and
  * — for any tag whose name the account already owns — asks whether to merge into the existing tag or import under a
  * new name, then commits in one call.
  *
  * Unlike `BulkUploadDialog` there is no preview round-trip: the file format is exact, so the only thing to resolve
  * before the write is a name clash, which the browser works out itself by intersecting the file's tag names with
  * `WordApiClient.listTags`.
  */
final class TagImportDialog(onImported: Observer[Unit]) {

  private val openVar                                      = Var(false)
  private val fileErrorVar: Var[Option[String]]            = Var(None)
  private val parsedVar: Var[Option[TagExportFile]]        = Var(None)
  private val ownedNormsVar: Var[Set[String]]              = Var(Set.empty)
  private val choiceVar: Var[Map[String, TagImportChoice]] = Var(Map.empty)
  private val renameTextVar: Var[Map[String, String]]      = Var(Map.empty)
  private val submittingVar                                = Var(false)
  private val resultVar: Var[Option[TagImportResponse]]    = Var(None)
  private val errorVar: Var[Option[String]]                = Var(None)

  private val loadTagsBus = new EventBus[Unit]()
  private val submitBus   = new EventBus[Map[String, TagImportChoice]]()

  /** The file tags whose (normalized) name the caller already owns — the ones that need a merge/rename decision. */
  private val clashesSignal: Signal[List[TagExportTag]] = {
    parsedVar.signal.combineWith(ownedNormsVar.signal).map { case (parsed, owned) =>
      parsed.toList.flatMap(file => TagImportDialog.clashes(file, owned))
    }
  }

  private def reset(): Unit = {
    Var.set(
      fileErrorVar  -> None,
      parsedVar     -> None,
      choiceVar     -> Map.empty[String, TagImportChoice],
      renameTextVar -> Map.empty[String, String],
      submittingVar -> false,
      resultVar     -> None,
      errorVar      -> None,
    )
  }

  private def open(): Unit = {
    reset()
    openVar.set(true)
    loadTagsBus.emit(())
  }

  private def close(): Unit = openVar.set(false)

  private def readFile(file: dom.File): Unit = {
    fileErrorVar.set(None)
    val reader = new dom.FileReader()
    reader.onload = { (_: dom.Event) =>
      TagImportDialog.parse(reader.result.asInstanceOf[String]) match {
        case Right(parsed) =>
          Var.set(parsedVar -> Some(parsed), resultVar -> None, errorVar -> None)
        case Left(_)       =>
          Var.set(parsedVar -> None, fileErrorVar -> Some(I18n.t(UiKeys.tagsImportBadFile)))
      }
    }
    reader.onerror = { (_: dom.Event) => fileErrorVar.set(Some(I18n.t(UiKeys.tagsImportBadFile))) }
    reader.readAsText(file)
  }

  /** Every clash has a choice, and every "rename" choice has a non-blank name. */
  private val readySignal: Signal[Boolean] = {
    clashesSignal
      .combineWith(choiceVar.signal, renameTextVar.signal, parsedVar.signal, submittingVar.signal)
      .map { case (clashes, choices, renames, parsed, submitting) =>
        !submitting && parsed.isDefined && clashes.forall { tag =>
          val norm = Tag.normalize(tag.name)
          choices.get(norm) match {
            case Some(TagImportChoice.Merge)     => true
            case Some(TagImportChoice.Rename(_)) => renames.getOrElse(norm, "").trim.nonEmpty
            case None                            => false
          }
        }
      }
  }

  private def buildResolutions(): Map[String, TagImportChoice] = {
    val choices = choiceVar.now()
    val renames = renameTextVar.now()
    choices.map {
      case (norm, TagImportChoice.Rename(_)) => norm -> TagImportChoice.Rename(renames.getOrElse(norm, "").trim)
      case (norm, other)                     => norm -> other
    }
  }

  def renderButton(): HtmlElement = {
    button(
      cls := "btn btn-sm",
      typ := "button",
      I18n.t(UiKeys.tagsImportButton),
      onClick.mapToUnit --> Observer[Unit](_ => open()),
    )
  }

  def renderModal(): HtmlElement = {
    div(
      cls := "modal",
      cls("modal-open") <-- openVar.signal,
      div(
        cls   := "modal-box w-full max-w-lg",
        h3(cls := "font-bold text-lg", I18n.t(UiKeys.tagsImportTitle)),
        Alert.maybeError(errorVar.signal),
        child.maybe <-- resultVar.signal.map(_.map(renderResult)),
        child.maybe <-- resultVar.signal.map(r => Option.when(r.isEmpty)(renderForm())),
        div(
          cls  := "modal-action",
          button(
            cls := "btn btn-sm",
            typ := "button",
            child.text <-- resultVar.signal.map(r =>
              if (r.isDefined) I18n.t(UiKeys.tagsImportDone) else I18n.t(UiKeys.commonCancel)
            ),
            onClick.mapToUnit --> Observer[Unit](_ => close()),
          ),
          child.maybe <-- resultVar.signal.map(r => {
            Option.when(r.isEmpty)(
              button(
                cls := "btn btn-sm btn-primary",
                typ := "button",
                disabled <-- readySignal.map(!_),
                I18n.t(UiKeys.tagsImportSubmit),
                onClick.mapToUnit --> Observer[Unit](_ => submitBus.emit(buildResolutions())),
              )
            )
          }),
        ),
      ),
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => close())),
      loadTagsBus.events.flatMapSwitch(_ => WordApiClient.listTags) --> Observer[Either[ApiError, List[Tag]]] {
        case Right(tags) => ownedNormsVar.set(tags.filter(_.ownedByMe).map(t => Tag.normalize(t.name)).toSet)
        case Left(err)   => errorVar.set(Some(err.message))
      },
      submitBus.events
        .withCurrentValueOf(parsedVar.signal)
        .collect { case (resolutions, Some(file)) => (file, resolutions) }
        .flatMapSwitch { case (file, resolutions) =>
          submittingVar.set(true)
          WordApiClient.importTags(file, resolutions)
        } --> Observer[Either[ApiError, TagImportResponse]] {
        case Right(response) =>
          Var.set(submittingVar -> false, resultVar -> Some(response))
          onImported.onNext(())
        case Left(err)       =>
          Var.set(submittingVar -> false, errorVar -> Some(err.message))
      },
    )
  }

  private def renderForm(): HtmlElement = {
    div(
      child.maybe <-- fileErrorVar.signal.map(_.map(Alert.error)),
      label(
        cls := "form-control w-full",
        span(cls := "label-text", I18n.t(UiKeys.tagsImportChooseFile)),
        input(
          typ    := "file",
          cls    := "file-input file-input-bordered file-input-sm w-full",
          accept := ".json,application/json",
          onChange --> Observer[dom.Event] { event =>
            val target = event.target.asInstanceOf[dom.html.Input]
            Option(target.files).filter(_.length > 0).map(_.item(0)).foreach(readFile)
          },
        ),
      ),
      child.maybe <-- parsedVar.signal.map(_.map(renderSummary)),
      child.maybe <-- clashesSignal.map(clashes => Option.when(clashes.nonEmpty)(renderConflicts(clashes))),
    )
  }

  private def renderSummary(file: TagExportFile): HtmlElement = {
    val tagCount  = file.tags.size.toLong
    val wordCount = file.tags.flatMap(_.entries).size.toLong
    val markCount = file.tags.flatMap(_.entries).flatMap(_.marked).size.toLong
    p(
      cls := "text-sm opacity-80 mt-3",
      List(
        I18n.plural(UiKeys.tagsImportSummaryTags, tagCount),
        I18n.plural(UiKeys.tagsImportSummaryWords, wordCount),
        I18n.plural(UiKeys.tagsImportSummaryMarks, markCount),
      ).mkString(" · "),
    )
  }

  private def renderConflicts(clashes: List[TagExportTag]): HtmlElement = {
    div(
      cls := "mt-4 space-y-3",
      p(cls := "text-sm", I18n.t(UiKeys.tagsImportConflictIntro)),
      clashes.map(renderConflictRow),
    )
  }

  private def renderConflictRow(tag: TagExportTag): HtmlElement = {
    val norm = Tag.normalize(tag.name)
    div(
      cls := "border border-base-300 rounded p-2",
      p(cls := "font-medium text-sm", tag.name),
      div(
        cls := "flex flex-col gap-1 mt-1",
        label(
          cls         := "flex items-center gap-2 text-sm",
          input(
            typ      := "radio",
            cls      := "radio radio-sm",
            nameAttr := s"import-choice-$norm",
            checked <-- choiceVar.signal.map(_.get(norm).contains(TagImportChoice.Merge)),
            onChange.mapToUnit --> Observer[Unit](_ => choiceVar.update(_ + (norm -> TagImportChoice.Merge))),
          ),
          I18n.t(UiKeys.tagsImportMerge),
        ),
        label(
          cls         := "flex items-center gap-2 text-sm",
          input(
            typ      := "radio",
            cls      := "radio radio-sm",
            nameAttr := s"import-choice-$norm",
            checked <-- choiceVar.signal.map(_.get(norm).exists(_.isInstanceOf[TagImportChoice.Rename])),
            onChange.mapToUnit --> Observer[Unit](_ =>
              choiceVar.update(_ + (norm -> TagImportChoice.Rename(renameTextVar.now().getOrElse(norm, ""))))
            ),
          ),
          I18n.t(UiKeys.tagsImportRename),
        ),
        input(
          typ         := "text",
          cls         := "input input-bordered input-sm w-full",
          placeholder := I18n.t(UiKeys.tagsImportNewName),
          disabled <-- choiceVar.signal.map(!_.get(norm).exists(_.isInstanceOf[TagImportChoice.Rename])),
          controlled(
            value <-- renameTextVar.signal.map(_.getOrElse(norm, "")),
            onInput.mapToValue --> Observer[String](text => renameTextVar.update(_ + (norm -> text))),
          ),
        ),
      ),
    )
  }

  private def renderResult(response: TagImportResponse): HtmlElement = {
    div(
      cls := "mt-3 space-y-1",
      response.results.map { r =>
        val key = if (r.created) UiKeys.tagsImportResultCreated else UiKeys.tagsImportResultMerged
        p(cls := "text-sm", I18n.plural(key, r.wordsAdded.toLong, r.tagName))
      },
    )
  }
}

object TagImportDialog {

  /** Decodes an uploaded file, accepting only the current schema version. `Left` is any failure — bad JSON, wrong
    * version — since the reader is shown one message for all of them.
    */
  def parse(json: String): Either[Unit, TagExportFile] = {
    json.fromJson[TagExportFile].left.map(_ => ()).flatMap { file =>
      if (file.version == TagExportFile.currentVersion) Right(file) else Left(())
    }
  }

  /** The file's tags whose normalized name is one the account already owns — the ones a merge/rename decision is needed
    * for.
    */
  def clashes(file: TagExportFile, ownedNorms: Set[String]): List[TagExportTag] = {
    file.tags.filter(tag => ownedNorms.contains(Tag.normalize(tag.name)))
  }
}
