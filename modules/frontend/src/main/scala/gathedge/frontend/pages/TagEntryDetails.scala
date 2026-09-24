package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Labels, WordPicker}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.Word
import gathedge.shared.dto.{TagEntryMainWordResponse, WordDetail, WordFormRef}
import gathedge.shared.i18n.UiKeys

/** The panel a wordlist row opens under itself, for one of its words: the reader's note beside the word, and the main
  * words it is a form of.
  *
  * An import writes both from the file — the note from a cell's parenthesised group, the forms from an extra column.
  * This is the same two writes by hand, so a word the reader typed in can carry them too.
  *
  * The note belongs to this wordlist's membership, so a save hands the new note to `onNoteSaved` and the page redraws
  * the cell. A form is a dictionary fact about the word, so the panel reads the word's main words itself and reads them
  * again after each change.
  *
  * The form types on offer come from the server, per main word: the relations the dictionary's own forms carry for that
  * main word's language and part of speech. So a noun is never offered `past`, and a language added later brings its
  * own form types with its import.
  */
private[pages] final class TagEntryDetails(
  tagId: Long,
  word: Word,
  comment: Option[String],
  onNoteSaved: Observer[Option[String]],
) {

  private val noteVar      = Var(comment.getOrElse(""))
  private val mainWordsVar = Var(Option.empty[List[WordFormRef]])
  private val mainVar      = Var(Option.empty[Word])
  private val relationsVar = Var(Option.empty[List[String]])
  private val relationVar  = Var("")
  private val errorVar     = Var(Option.empty[String])
  private val statusVar    = Var(Option.empty[String])
  private val busyVar      = Var(false)

  private val saveNoteBus  = new EventBus[Unit]()
  private val saveFormBus  = new EventBus[Unit]()
  private val removeBus    = new EventBus[WordFormRef]()
  private val loadFormsBus = new EventBus[Unit]()

  private val mainPicker = new WordPicker(
    language = Val(word.language),
    partOfSpeech = Val(None),
    onCommit = Observer.empty,
    placeholderSignal = Val(I18n.t(UiKeys.tagsEditorMainWordSearch)),
    onCommitWord =
      Observer[Option[Word]](picked => Var.set(mainVar -> picked, relationsVar -> None, statusVar -> None)),
    mainOnly = true,
    allowNew = false,
  )

  private def fail(err: ApiError): Unit = {
    Var.set(busyVar -> false, statusVar -> None, errorVar -> Some(err.message))
  }

  def render(): HtmlElement = {
    div(
      cls                := "flex flex-col gap-3 min-w-0",
      dataAttr("testid") := s"entry-details-${word.id}",
      div(cls := "font-semibold", Word.display(word)),
      renderNote(),
      renderFormOf(),
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
      // A picked main word decides which form types make sense, so each pick asks for its own list.
      mainVar.signal.updates
        .collect { case Some(main) => main }
        .flatMapSwitch(main => WordApiClient.formRelations(main.language, main.partOfSpeech)) -->
        Observer[Either[ApiError, List[String]]] {
          case Right(relations) =>
            Var.set(relationsVar -> Some(relations), relationVar -> relations.headOption.getOrElse(""))
          case Left(err)        =>
            fail(err)
        },
      saveFormBus.events
        .sample(mainVar.signal, relationVar.signal)
        .collect { case (Some(main), relation) if relation.nonEmpty => (main, relation) }
        .flatMapSwitch { case (main, relation) =>
          Var.set(busyVar -> true, errorVar -> None, statusVar -> None)
          WordApiClient.addMainWord(tagId, word.id, main.id, relation)
        } --> Observer[Either[ApiError, TagEntryMainWordResponse]] {
        case Right(response) =>
          Var.set(busyVar -> false, mainVar -> None, relationsVar -> None)
          mainPicker.clear()
          if (response.alreadyPresent)
            statusVar.set(Some(I18n.t(UiKeys.tagsEditorFormExists, Word.display(response.mainWord))))
          loadFormsBus.emit(())
        case Left(err)       =>
          fail(err)
      },
      removeBus.events.flatMapSwitch(ref => {
        Var.set(busyVar -> true, errorVar -> None, statusVar -> None)
        WordApiClient.removeMainWord(tagId, word.id, ref.word.id, ref.relation)
      }) --> Observer[Either[ApiError, Unit]] {
        case Right(_)  =>
          busyVar.set(false)
          loadFormsBus.emit(())
        case Left(err) =>
          fail(err)
      },
      loadFormsBus.events.flatMapSwitch(_ => WordApiClient.get(word.id)) --> Observer[Either[ApiError, WordDetail]] {
        case Right(detail) => mainWordsVar.set(Some(detail.mainWords))
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

  /** The main words this word is a form of, each removable, and the picker that adds one: a main word first, then a
    * form type from the ones that fit it.
    */
  private def renderFormOf(): HtmlElement = {
    div(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(UiKeys.tagsEditorFormOfLabel)),
      child <-- mainWordsVar.signal.map {
        case None                       => span(cls := "loading loading-spinner loading-xs", role := "status")
        case Some(refs) if refs.isEmpty => p(cls := "text-xs opacity-60", I18n.t(UiKeys.tagsEditorNotAForm))
        case Some(refs)                 =>
          ul(
            cls := "flex flex-col gap-0.5 text-sm",
            refs.map(ref => {
              li(
                cls := "flex flex-wrap items-center gap-2",
                span(Word.display(ref.word)),
                span(cls := "text-xs opacity-60", Labels.grammarRelation(ref.relation)),
                button(
                  typ    := "button",
                  cls    := "btn btn-ghost btn-xs",
                  disabled <-- busyVar.signal,
                  I18n.t(UiKeys.tagsEditorRemoveForm),
                  onClick.mapToUnit --> Observer[Unit](_ => removeBus.emit(ref)),
                ),
              )
            }),
          )
      },
      mainPicker.render(),
      child.maybe <-- mainVar.signal.combineWith(relationsVar.signal).map {
        case (None, _)                                       => None
        case (Some(_), None)                                 =>
          Some(span(cls := "loading loading-spinner loading-xs", role := "status"))
        case (Some(_), Some(relations)) if relations.isEmpty =>
          Some(p(cls := "text-xs opacity-60", I18n.t(UiKeys.tagsEditorNoRelations)))
        case (Some(_), Some(relations))                      =>
          Some(
            form(
              cls        := "flex flex-wrap gap-2",
              noValidate := true,
              onSubmit.preventDefault.mapToUnit --> saveFormBus.writer,
              select(
                cls        := "select select-sm flex-1 min-w-0",
                aria.label := I18n.t(UiKeys.tagsEditorFormRelation),
                relations.map(relation => option(value := relation, Labels.grammarRelation(relation))),
                controlled(value <-- relationVar.signal, onChange.mapToValue --> relationVar.writer),
              ),
              button(
                typ        := "submit",
                cls        := "btn btn-sm",
                disabled <-- busyVar.signal,
                I18n.t(UiKeys.tagsEditorSaveForm),
              ),
            )
          )
      },
    )
  }
}
