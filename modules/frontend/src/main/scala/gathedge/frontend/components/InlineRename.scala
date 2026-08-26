package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.api.ApiError
import gathedge.frontend.i18n.I18n
import gathedge.shared.i18n.UiKeys

/** The "click a pencil, edit a name inline, Save/Cancel" state machine and its whole title row, extracted from
  * `GameInstancePage`'s quiz rename and reused by `TagDetailPage`'s tag rename — an editing flag, the text being
  * typed, in-flight/error state, and the async submit wired through `flatMapSwitch` so a second Enter cannot race the
  * first.
  *
  * What differs between callers — what gets renamed, what a success looks like, and any extra chrome beside the name
  * (a results link, a delete icon) — stays with the page, threaded through [[renderTitle]]'s parameters. Both callers
  * reach this page only once its subject is known to exist, so [[renderTitle]] never branches on whether there is
  * one: `nameSignal` is always meaningful, even if briefly empty before the first load lands.
  *
  * @param submit
  *   the rename call itself, e.g. `text => GameApiClient.rename(slug, text)`. By-name per call, not stored, so it can
  *   close over whatever id the page is renaming.
  */
final class InlineRename[A](submit: String => EventStream[Either[ApiError, A]]) {

  private val editingVar                    = Var(false)
  private val textVar                       = Var("")
  private val submittingVar                 = Var(false)
  private val errorVar: Var[Option[String]] = Var(None)

  private val saveBus = new EventBus[Unit]()

  val editingSignal: Signal[Boolean] = editingVar.signal

  /** Opens the editor, prefilled with the name as it stands right now. */
  def open(currentName: String): Unit = Var.set(textVar -> currentName, editingVar -> true, errorVar -> None)

  def cancel(): Unit = Var.set(editingVar -> false, errorVar -> None)

  /** Spliced into the page's root element. `onSaved` is what the page does with a successful rename — update its own
    * name state, since only it knows where that lives. `onError` is for anything beyond showing the message inline —
    * `GameInstancePage` uses it to drop a stale ownership hint on a 403; most callers can leave it as the default.
    */
  def bindings(onSaved: Observer[A], onError: ApiError => Unit = _ => ()): Modifier[HtmlElement] = {
    saveBus.events
      .filterWith(submittingVar.signal.not)
      .map(_ => textVar.now().trim)
      .collect { case text if text.nonEmpty => text }
      .flatMapSwitch { text =>
        submittingVar.set(true)
        submit(text)
      } -->
      Observer[Either[ApiError, A]] {
        case Right(value) =>
          Var.set(editingVar -> false, submittingVar -> false, errorVar -> None)
          onSaved.onNext(value)
        case Left(err)    =>
          onError(err)
          Var.set(submittingVar -> false, errorVar -> Some(err.fieldErrors.getOrElse("name", err.message)))
      }
  }

  /** The whole title: an `h1` swapped, purely on [[editingSignal]], between the name (with a pencil and whatever else
    * the page passes as `extra`) and the inline edit form. Each item in `extra` is responsible for its own
    * visibility — `GameInstancePage`'s results link and `TagDetailPage`'s delete icon each gate on a different
    * condition, so this does not attempt to share one.
    *
    * @param canEdit
    *   whether the pencil is offered at all — `TagDetailPage` gates it on `tag.ownedByMe`, `GameInstancePage` on its
    *   local ownership hint.
    * @param inputCls
    *   lets a caller match the edit input to what it replaces — `TagDetailPage` sizes it like the title it stands in
    *   for, `GameInstancePage` like the ordinary `input-sm` beside its own heading.
    */
  def renderTitle(
    nameSignal: Signal[String],
    canEdit: Signal[Boolean],
    editLabel: String,
    formLabel: String,
    inputCls: String = "input input-sm",
    extra: Modifier[HtmlElement]*,
  ): HtmlElement = {
    h1(
      cls := "card-title text-2xl",
      child <-- editingSignal.map {
        case true  => renderForm(formLabel, inputCls)
        case false => renderDisplay(nameSignal, canEdit, editLabel, extra)
      },
    )
  }

  private def renderDisplay(
    nameSignal: Signal[String],
    canEdit: Signal[Boolean],
    editLabel: String,
    extra: Seq[Modifier[HtmlElement]],
  ): HtmlElement = {
    div(
      cls := "flex items-center gap-2 flex-wrap",
      span(child.text <-- nameSignal),
      child.maybe <-- canEdit.map(
        Option.when(_)(
          InlineRename.iconButton(
            editLabel,
            InlineRename.pencilMark(),
            onClick.compose(_.sample(nameSignal)) --> Observer[String](open),
          )
        )
      ),
      extra,
    )
  }

  /** The editor itself: a labelled input (auto-focused, `Escape` cancels), Save/Cancel, and the field error beneath it
    * once an attempt has failed.
    */
  private def renderForm(formLabel: String, inputCls: String): HtmlElement = {
    div(
      form(
        cls        := "flex items-center gap-2 flex-wrap",
        noValidate := true,
        onSubmit.preventDefault.mapToUnit --> saveBus.writer,
        input(
          // `min-w-0` overrides the input's content-based minimum width, so it is what shrinks when the row is tight
          // rather than pushing Save/Cancel onto their own line — `flex-wrap` wraps whichever item first runs out of
          // room, which without this was Cancel, stranded under the input.
          cls         := s"$inputCls flex-1 min-w-0",
          placeholder := formLabel,
          aria.label  := formLabel,
          controlled(value <-- textVar.signal, onInput.mapToValue --> textVar.writer),
          onMountFocus,
          onKeyDown.filter(_.key == "Escape").mapToUnit --> Observer[Unit](_ => cancel()),
        ),
        button(
          cls      := "btn btn-sm btn-primary",
          typ      := "submit",
          disabled <-- submittingVar.signal,
          I18n.t(UiKeys.commonSave),
        ),
        button(
          cls      := "btn btn-sm btn-ghost",
          typ      := "button",
          disabled <-- submittingVar.signal,
          I18n.t(UiKeys.commonCancel),
          onClick.mapToUnit --> Observer[Unit](_ => cancel()),
        ),
      ),
      child.maybe <-- errorVar.signal.map(_.map(msg => p(cls := "text-error text-xs mt-1", msg))),
    )
  }
}

object InlineRename {

  /** The pencil [[renderTitle]] itself renders, and the shape `TagDetailPage`'s delete icon matches via
    * [[iconButton]] so the two sit next to each other looking like one family of controls.
    */
  def pencilMark(): SvgElement = {
    svg.svg(
      svg.cls            := "h-4 w-4",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "2",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(svg.d := "M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4Z"),
    )
  }

  /** A tooltip-labelled icon button — the pencil above, and `TagDetailPage`'s delete icon, share this so the two look
    * like one family of controls rather than two different button styles beside each other.
    */
  def iconButton(label: String, icon: SvgElement, click: Modifier[HtmlElement]): HtmlElement = {
    span(
      cls             := "tooltip",
      dataAttr("tip") := label,
      button(
        cls        := "btn btn-ghost btn-sm btn-square",
        typ        := "button",
        aria.label := label,
        icon,
        click,
      ),
    )
  }
}
