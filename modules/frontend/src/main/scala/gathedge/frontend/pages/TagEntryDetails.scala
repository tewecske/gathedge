package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.Labels
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{GrammarTag, Word}
import gathedge.shared.dto.{TagEntryFormResponse, WordDetail, WordFormEntry}
import gathedge.shared.i18n.UiKeys

/** The panel a wordlist row opens under itself, for one of its words: the reader's note beside the word, and the forms
  * filed under it.
  *
  * An import writes both from the file — the note from a cell's parenthesised group, the forms from an extra column.
  * This is the same two writes by hand, so a word the reader typed in can carry them too.
  *
  * The note belongs to this wordlist's membership, so a save hands the new note to `onNoteSaved` and the page redraws
  * the cell. A form is a dictionary fact about the word, so the panel reads the word's forms itself and reads them
  * again after each add.
  */
private[pages] final class TagEntryDetails(
  tagId: Long,
  word: Word,
  comment: Option[String],
  onNoteSaved: Observer[Option[String]],
) {

  private val noteVar     = Var(comment.getOrElse(""))
  private val formsVar    = Var(Option.empty[List[WordFormEntry]])
  private val formTextVar = Var("")
  private val relationVar = Var(GrammarTag.pickable.headOption.getOrElse(""))
  private val errorVar    = Var(Option.empty[String])
  private val statusVar   = Var(Option.empty[String])
  private val busyVar     = Var(false)

  private val saveNoteBus  = new EventBus[Unit]()
  private val addFormBus   = new EventBus[Unit]()
  private val loadFormsBus = new EventBus[Unit]()

  private def fail(err: ApiError): Unit = {
    Var.set(busyVar -> false, statusVar -> None, errorVar -> Some(err.message))
  }

  def render(): HtmlElement = {
    div(
      cls                := "flex flex-col gap-3 min-w-0",
      dataAttr("testid") := s"entry-details-${word.id}",
      div(cls := "font-semibold", Word.display(word)),
      renderNote(),
      renderForms(),
      child.maybe <-- errorVar.signal.map(_.map(msg => p(cls := "text-error text-xs", msg))),
      child.maybe <-- statusVar.signal.map(_.map(msg => p(cls := "text-xs opacity-70", msg))),
      saveNoteBus.events
        .sample(noteVar.signal)
        .map(text => Option(text.trim).filter(_.nonEmpty))
        .flatMapSwitch(note => {
          Var.set(busyVar -> true, errorVar -> None)
          WordApiClient.setEntryNote(tagId, word.id, note).map(_.map(_ => note))
        }) --> Observer[Either[ApiError, Option[String]]] {
        case Right(note) =>
          busyVar.set(false)
          onNoteSaved.onNext(note)
        case Left(err)   =>
          fail(err)
      },
      addFormBus.events
        .sample(formTextVar.signal, relationVar.signal)
        .filter { case (text, _) => text.trim.nonEmpty }
        .flatMapSwitch { case (text, relation) =>
          Var.set(busyVar -> true, errorVar -> None, statusVar -> None)
          WordApiClient.addEntryForm(tagId, word.id, text.trim, relation)
        } --> Observer[Either[ApiError, TagEntryFormResponse]] {
        case Right(response) =>
          Var.set(busyVar -> false, formTextVar -> "")
          if (response.alreadyPresent)
            statusVar.set(Some(I18n.t(UiKeys.tagsEditorFormExists, Word.display(response.form))))
          loadFormsBus.emit(())
        case Left(err)       =>
          fail(err)
      },
      loadFormsBus.events.flatMapSwitch(_ => WordApiClient.get(word.id)) --> Observer[Either[ApiError, WordDetail]] {
        case Right(detail) => formsVar.set(Some(detail.forms))
        case Left(err)     => fail(err)
      },
      onMountCallback(_ => loadFormsBus.emit(())),
    )
  }

  private def renderNote(): HtmlElement = {
    form(
      cls        := "flex flex-col gap-1",
      noValidate := true,
      onSubmit.preventDefault.mapToUnit --> saveNoteBus.writer,
      span(cls := "label-text text-xs", I18n.t(UiKeys.tagsEditorNoteLabel)),
      div(
        cls    := "flex flex-wrap gap-2",
        input(
          typ         := "text",
          cls         := "input input-sm flex-1 min-w-0",
          maxLength   := 255,
          placeholder := I18n.t(UiKeys.tagsEditorNotePlaceholder),
          aria.label  := I18n.t(UiKeys.tagsEditorNoteLabel),
          controlled(value <-- noteVar.signal, onInput.mapToValue --> noteVar.writer),
        ),
        button(
          typ         := "submit",
          cls         := "btn btn-sm",
          disabled <-- busyVar.signal,
          I18n.t(UiKeys.tagsEditorSaveNote),
        ),
      ),
    )
  }

  private def renderForms(): HtmlElement = {
    div(
      cls := "flex flex-col gap-1",
      span(cls     := "label-text text-xs", I18n.t(UiKeys.tagsEditorFormsLabel)),
      child <-- formsVar.signal.map {
        case None                         => span(cls := "loading loading-spinner loading-xs", role := "status")
        case Some(forms) if forms.isEmpty => p(cls := "text-xs opacity-60", I18n.t(UiKeys.tagsEditorNoForms))
        case Some(forms)                  =>
          ul(
            cls := "flex flex-col gap-0.5 text-sm",
            forms.map(entry => {
              li(
                Word.display(entry.word),
                span(cls := "text-xs opacity-60 ml-2", Labels.grammarRelation(entry.relation)),
              )
            }),
          )
      },
      form(
        cls        := "flex flex-wrap gap-2",
        noValidate := true,
        onSubmit.preventDefault.mapToUnit --> addFormBus.writer,
        input(
          typ         := "text",
          cls         := "input input-sm flex-1 min-w-0",
          maxLength   := 255,
          placeholder := I18n.t(UiKeys.tagsEditorFormPlaceholder),
          aria.label  := I18n.t(UiKeys.tagsEditorFormPlaceholder),
          controlled(value <-- formTextVar.signal, onInput.mapToValue --> formTextVar.writer),
        ),
        select(
          cls         := "select select-sm",
          aria.label  := I18n.t(UiKeys.tagsEditorFormRelation),
          GrammarTag.pickable.map(tag => option(value := tag, Labels.grammarTag(tag))),
          controlled(value <-- relationVar.signal, onChange.mapToValue --> relationVar.writer),
        ),
        button(
          typ         := "submit",
          cls         := "btn btn-sm",
          disabled <-- busyVar.signal.combineWithFn(formTextVar.signal)((busy, text) => busy || text.trim.isEmpty),
          I18n.t(UiKeys.tagsEditorAddForm),
        ),
      ),
    )
  }
}
