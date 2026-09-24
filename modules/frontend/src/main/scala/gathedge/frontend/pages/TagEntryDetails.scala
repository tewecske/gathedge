package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Labels, WordPicker}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{PartOfSpeech, Word}
import gathedge.shared.dto.{TagEntryMainWordResponse, TagPairWord, WordDetail, WordFormRef}
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom

/** The panel a wordlist row opens under itself, for one of its words: the reader's note beside the word, its part of
  * speech, and the main words it is a form of.
  *
  * An import writes all three from the file. This is the same writes by hand, so a word the reader typed in can carry
  * them too.
  *
  * The note belongs to this wordlist's membership, so a save hands the new note to `onNoteSaved` and the page redraws
  * the cell. The part of speech and the form-of links are dictionary facts about the word. A changed part of speech
  * goes to `onWordChanged`, so the row shows it. The panel reads the word's links itself, and reads them again after
  * each change.
  *
  * '''Shared data is guarded.''' The part of speech of a word the reader did not mint, and a link they did not make,
  * are an administrator's to change. The controls say so, and an administrator confirms a warning before changing
  * dictionary data. The server applies the same rule, so the page is not what enforces it.
  *
  * A main word has the form's part of speech. The picker searches only those, and a main word the reader types that the
  * dictionary lacks is created with it. The form types on offer are the ones the dictionary's own forms carry for that
  * language and part of speech, so a noun is never offered `past`.
  */
private[pages] final class TagEntryDetails(
  tagId: Long,
  word: Word,
  comment: Option[String],
  onNoteSaved: Observer[Option[String]],
  onWordChanged: Observer[Word] = Observer.empty,
) {

  private val noteVar      = Var(comment.getOrElse(""))
  private val posVar       = Var(word.partOfSpeech)
  private val detailVar    = Var(Option.empty[WordDetail])
  private val mainRefVar   = Var(Option.empty[TagPairWord])
  private val relationsVar = Var(Option.empty[List[String]])
  private val relationVar  = Var("")
  private val errorVar     = Var(Option.empty[String])
  private val statusVar    = Var(Option.empty[String])
  private val busyVar      = Var(false)

  private val saveNoteBus  = new EventBus[Unit]()
  private val posBus       = new EventBus[PartOfSpeech]()
  private val saveFormBus  = new EventBus[Unit]()
  private val removeBus    = new EventBus[WordFormRef]()
  private val loadFormsBus = new EventBus[Unit]()

  /** The reader may change what they minted; an administrator may change anything. */
  private val canEditPosSignal: Signal[Boolean] = {
    detailVar.signal.combineWith(AppState.isGlobalAdminSignal).map {
      case (Some(detail), admin) => detail.createdByMe || admin
      case (None, _)             => false
    }
  }

  private val mainPicker = new WordPicker(
    language = Val(word.language),
    partOfSpeech = posVar.signal.map(Some(_)),
    onCommit = Observer[TagPairWord](ref => Var.set(mainRefVar -> Some(ref), statusVar -> None)),
    placeholderSignal = Val(I18n.t(UiKeys.tagsEditorMainWordSearch)),
    mainOnly = true,
  )

  private def fail(err: ApiError): Unit = {
    Var.set(busyVar -> false, statusVar -> None, errorVar -> Some(err.message))
  }

  /** An administrator about to change dictionary data is asked first. Anyone else never reaches here for it. */
  private def confirmed(fromDictionary: Boolean): Boolean =
    !fromDictionary || dom.window.confirm(I18n.t(UiKeys.tagsEditorDictionaryWarn))

  def render(): HtmlElement = {
    div(
      cls                := "flex flex-col gap-3 min-w-0",
      dataAttr("testid") := s"entry-details-${word.id}",
      div(cls := "font-semibold", Word.display(word)),
      renderNote(),
      renderPartOfSpeech(),
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
      // A declined warning leaves the select where it was: `posVar` never moved, and the select follows it.
      posBus.events
        .withCurrentValueOf(detailVar.signal)
        .collect {
          case (pos, Some(detail)) if pos != posVar.now() && confirmed(detail.fromDictionary) => (pos, detail)
        }
        .flatMapSwitch { case (pos, detail) =>
          Var.set(busyVar -> true, errorVar -> None)
          WordApiClient.setPartOfSpeech(word.id, pos, confirm = detail.fromDictionary)
        } --> Observer[Either[ApiError, WordDetail]] {
        case Right(detail) =>
          Var.set(busyVar -> false, detailVar -> Some(detail), posVar -> detail.word.partOfSpeech, mainRefVar -> None)
          mainPicker.clear()
          onWordChanged.onNext(detail.word)
        case Left(err)     =>
          fail(err)
      },
      // The form types follow the word's part of speech, which a main word shares.
      posVar.signal.distinct.flatMapSwitch(pos => WordApiClient.formRelations(word.language, pos)) -->
        Observer[Either[ApiError, List[String]]] {
          case Right(relations) =>
            Var.set(relationsVar -> Some(relations), relationVar -> relations.headOption.getOrElse(""))
          case Left(err)        =>
            fail(err)
        },
      saveFormBus.events
        .sample(mainRefVar.signal, relationVar.signal)
        .collect { case (Some(main), relation) if relation.nonEmpty => (main, relation) }
        .flatMapSwitch { case (main, relation) =>
          Var.set(busyVar -> true, errorVar -> None, statusVar -> None)
          WordApiClient.addMainWord(tagId, word.id, main, relation)
        } --> Observer[Either[ApiError, TagEntryMainWordResponse]] {
        case Right(response) =>
          Var.set(busyVar -> false, mainRefVar -> None)
          mainPicker.clear()
          if (response.alreadyPresent)
            statusVar.set(Some(I18n.t(UiKeys.tagsEditorFormExists, Word.display(response.mainWord))))
          loadFormsBus.emit(())
        case Left(err)       =>
          fail(err)
      },
      removeBus.events
        .filter(ref => confirmed(ref.fromDictionary))
        .flatMapSwitch(ref => {
          Var.set(busyVar -> true, errorVar -> None, statusVar -> None)
          WordApiClient.removeMainWord(tagId, word.id, ref.word.id, ref.relation, confirm = ref.fromDictionary)
        }) --> Observer[Either[ApiError, Unit]] {
        case Right(_)  =>
          busyVar.set(false)
          loadFormsBus.emit(())
        case Left(err) =>
          fail(err)
      },
      loadFormsBus.events.flatMapSwitch(_ => WordApiClient.get(word.id)) --> Observer[Either[ApiError, WordDetail]] {
        case Right(detail) => detailVar.set(Some(detail))
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

  /** The word's part of speech. Locked, with the reason, for a word the reader may not change. */
  private def renderPartOfSpeech(): HtmlElement = {
    div(
      cls := "flex flex-col gap-1",
      span(cls     := "label-text text-xs", I18n.t(UiKeys.tagsEditorPartOfSpeech)),
      select(
        cls        := "select select-sm w-full sm:w-48",
        aria.label := I18n.t(UiKeys.tagsEditorPartOfSpeech),
        disabled <-- canEditPosSignal.combineWithFn(busyVar.signal)((can, busy) => !can || busy),
        PartOfSpeech.all.map(pos => option(value := PartOfSpeech.code(pos), Labels.partOfSpeech(pos))),
        controlled(
          value <-- posVar.signal.map(PartOfSpeech.code),
          onChange.mapToValue.map(PartOfSpeech.fromString).collect { case Some(pos) => pos } --> posBus.writer,
        ),
      ),
      child.maybe <-- detailVar.signal.combineWith(canEditPosSignal).map {
        case (Some(_), false) => Some(p(cls := "text-xs opacity-60", I18n.t(UiKeys.tagsEditorPosLocked)))
        case _                => None
      },
    )
  }

  /** The main words this word is a form of, and the picker that adds one: a main word first, then a form type. A link
    * offers its remove button only to whoever may remove it.
    */
  private def renderFormOf(): HtmlElement = {
    div(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(UiKeys.tagsEditorFormOfLabel)),
      child <-- detailVar.signal.combineWith(AppState.isGlobalAdminSignal).map {
        case (None, _)                                     =>
          span(cls := "loading loading-spinner loading-xs", role := "status")
        case (Some(detail), _) if detail.mainWords.isEmpty =>
          p(cls := "text-xs opacity-60", I18n.t(UiKeys.tagsEditorNotAForm))
        case (Some(detail), admin)                         =>
          ul(
            cls := "flex flex-col gap-0.5 text-sm",
            detail.mainWords.map(ref => {
              li(
                cls := "flex flex-wrap items-center gap-2",
                span(Word.display(ref.word)),
                span(cls := "text-xs opacity-60", Labels.grammarRelation(ref.relation)),
                Option.when((ref.createdByMe && !ref.fromDictionary) || admin)(
                  button(
                    typ := "button",
                    cls := "btn btn-ghost btn-xs",
                    disabled <-- busyVar.signal,
                    I18n.t(UiKeys.tagsEditorRemoveForm),
                    onClick.mapToUnit --> Observer[Unit](_ => removeBus.emit(ref)),
                  )
                ),
              )
            }),
          )
      },
      mainPicker.render(),
      child.maybe <-- mainRefVar.signal.combineWith(relationsVar.signal).map {
        case (None, _)                                       =>
          None
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
