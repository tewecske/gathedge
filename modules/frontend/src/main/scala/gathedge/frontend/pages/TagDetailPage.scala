package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GameApiClient, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, Labels, TagWordsList}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{Tag, WordLanguage}
import gathedge.shared.dto.{GameSetupWord, TagResponse}
import gathedge.shared.i18n.UiKeys

/** A standalone, read-only view of one tag's words and marked translations — the same `TagWordsList` table
  * `GameSetupPage`/`GameInstancePage` use, reached directly rather than through the game-creation flow. Backed by the
  * same session-only `GameApiClient.setupWords` those pages call, with `tagIds = Set(tagId)`; no new backend endpoint
  * was needed for this page, per the plan behind it.
  */
object TagDetailPage {

  def render(tagId: Long): HtmlElement = {
    AppShell.render(Page.TagDetail(tagId), new TagDetailPage(tagId).render())
  }
}

private class TagDetailPage(tagId: Long) {

  private val defaultSource = WordLanguage.De
  private val defaultTarget = WordLanguage.Hu

  private val sourceVar = Var(defaultSource)
  private val targetVar = Var(defaultTarget)

  private val formSignal = sourceVar.signal.combineWith(targetVar.signal).distinct

  private val tagVar: Var[Option[Tag]] = Var(None)

  private val wordsVar        = Var(List.empty[GameSetupWord])
  private val wordsLoadingVar = Var(false)

  private val errorVar: Var[Option[String]] = Var(None)

  private val reloadBus = new EventBus[Unit]()

  /** Whether the title is showing the name or an inline input in its place — no modal, unlike `WordCollect`'s rename,
    * since there is exactly one tag on this page for it to apply to.
    */
  private val renameOpenVar = Var(false)
  private val renameNameVar = Var("")
  private val renameBus     = new EventBus[Unit]()

  private val deleteOpenVar = Var(false)
  private val deleteBus     = new EventBus[Unit]()

  /** Fires once on mount and again on every language-pair change — the same shape `GameSetupPage.formRequests` uses, so
    * the words list is fetched exactly once for the initial pair rather than needing `formSignal`'s current value read
    * twice.
    */
  private val formRequests = EventStream.merge(formSignal.updates, reloadBus.events.sample(formSignal))

  def render(): HtmlElement = {
    div(
      cls := "max-w-3xl mx-auto",
      Alert.maybeError(errorVar.signal),
      div(
        cls := "card bg-base-100 shadow mt-4",
        div(
          cls := "card-body",
          renderTitleRow(),
          child.maybe <-- tagVar.signal.map(_.map(renderMeta)),
          div(
            cls   := "flex flex-wrap items-end gap-3 mt-2",
            languageSelect(UiKeys.gameSetupSourceLabel, sourceVar.signal, sourceVar.writer),
            renderSwap(),
            languageSelect(UiKeys.gameSetupTargetLabel, targetVar.signal, targetVar.writer),
          ),
          div(cls := "mt-4", TagWordsList.render(wordsVar.signal, wordsLoadingVar.signal, collapsed = false)),
        ),
      ),
      reloadBus.events.flatMapSwitch(_ => WordApiClient.listTags) -->
        Observer[Either[ApiError, List[Tag]]] {
          case Right(tags) =>
            tagVar.set(tags.find(_.id == tagId))
          case Left(err)   =>
            errorVar.set(Some(err.message))
        },
      renameBus.events
        .sample(renameNameVar.signal)
        .map(_.trim)
        .filter(_.nonEmpty)
        .flatMapSwitch(name => WordApiClient.renameTag(tagId, name)) -->
        Observer[Either[ApiError, TagResponse]] {
          case Right(response) =>
            Var.set(tagVar -> Some(response.tag), renameOpenVar -> false, errorVar -> None)
          case Left(err)       =>
            errorVar.set(Some(err.message))
        },
      deleteBus.events.flatMapSwitch(_ => WordApiClient.deleteTag(tagId)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            AppRouter.router.pushState(Page.Tags)
          case Left(err) =>
            Var.set(deleteOpenVar -> false, errorVar -> Some(err.message))
        },
      formRequests --> Observer[(WordLanguage, WordLanguage)](_ => wordsLoadingVar.set(true)),
      formRequests.flatMapSwitch { case (source, target) => GameApiClient.setupWords(source, target, Set(tagId)) } -->
        Observer[Either[ApiError, List[GameSetupWord]]] {
          case Right(words) =>
            Var.set(wordsVar -> words, wordsLoadingVar -> false)
          case Left(err)    =>
            Var.set(wordsLoadingVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  /** The tag's name, or an inline edit form in its place, with rename/delete icons beside it — the same two icons
    * `WordCollect.renderCollectSelect` offers, next to the tag they act on rather than next to a `<select>`. Owner-only,
    * same as there: `WordService.requireOwnTag` refuses either write for anyone else, so the icons are hidden rather
    * than left to fail.
    */
  private def renderTitleRow(): HtmlElement = {
    h1(
      cls   := "card-title text-2xl",
      child <-- tagVar.signal.combineWithFn(renameOpenVar.signal) {
        case (Some(tag), true)  => renderRenameForm(tag)
        case (Some(tag), false) => renderTitleDisplay(tag)
        case (None, _)          => span(I18n.t(UiKeys.tagDetailTitle))
      },
    )
  }

  private def renderTitleDisplay(tag: Tag): HtmlElement = {
    div(
      cls := "flex items-center gap-1 flex-wrap",
      span(tag.name),
      Option.when(tag.ownedByMe)(
        renderTagIconButton(UiKeys.wordsTagRenameButton, pencilMark(), () => openRename(tag))
      ),
      Option.when(tag.ownedByMe)(
        renderTagIconButton(UiKeys.wordsTagDeleteButton, trashMark(), () => deleteOpenVar.set(true))
      ),
      renderDeleteModal(tag),
    )
  }

  private def renderRenameForm(tag: Tag): HtmlElement = {
    form(
      cls        := "flex items-center gap-1",
      noValidate := true,
      onSubmit.preventDefault.mapToUnit --> renameBus.writer,
      input(
        cls    := "input input-bordered text-2xl font-bold h-auto py-1 w-full max-w-sm",
        controlled(value <-- renameNameVar.signal, onInput.mapToValue --> renameNameVar.writer),
        onMountFocus,
        onKeyDown.filter(_.key == "Escape").mapToUnit --> Observer[Unit](_ => renameOpenVar.set(false)),
      ),
      renderTagIconButton(UiKeys.commonSave, checkMark(), () => renameBus.emit(())),
      renderTagIconButton(UiKeys.commonCancel, closeMark(), () => renameOpenVar.set(false)),
    )
  }

  private def openRename(tag: Tag): Unit = {
    Var.set(renameNameVar -> tag.name, renameOpenVar -> true)
  }

  private def renderTagIconButton(labelKey: String, icon: SvgElement, onClick0: () => Unit): HtmlElement = {
    span(
      cls             := "tooltip",
      dataAttr("tip") := I18n.t(labelKey),
      button(
        cls        := "btn btn-ghost btn-sm btn-square",
        typ        := "button",
        aria.label := I18n.t(labelKey),
        icon,
        onClick.mapToUnit --> Observer[Unit](_ => onClick0()),
      ),
    )
  }

  /** Copied from `WordCollect.renderDeleteModal`'s pattern, addressed at this page's one tag rather than the collect
    * select's current one.
    */
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

  /** Copied from `WordCollect.pencilMark`: reuse the pattern, not the (page-private) function. */
  private def pencilMark(): SvgElement = {
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

  /** Copied from `WordCollect.trashMark`: reuse the pattern, not the (page-private) function. */
  private def trashMark(): SvgElement = {
    svg.svg(
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
  }

  /** The inline rename form's own two icons — a plain checkmark and cross, drawn rather than typed for the reason
    * `WordCollect.chipMark` gives: an SVG's box is its ink, centred on a button of any size.
    */
  private def checkMark(): SvgElement = {
    svg.svg(
      svg.cls            := "h-4 w-4",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "2",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(svg.d := "M5 12.5l4.5 4.5L19 7"),
    )
  }

  private def closeMark(): SvgElement = {
    svg.svg(
      svg.cls            := "h-4 w-4",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "2",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(svg.d := "M6 6l12 12M18 6L6 18"),
    )
  }

  private def renderMeta(tag: Tag): HtmlElement = {
    div(
      cls := "flex flex-wrap gap-4 text-sm opacity-70",
      Option.when(tag.ownedByMe)(span(I18n.t(UiKeys.tagDetailOwnerLabel))),
      tag.group.map { group =>
        span(
          s"${I18n.t(UiKeys.tagDetailGroupLabel)}: ",
          a(cls := "link", AppRouter.router.navigateTo(Page.GroupDetail(group.id)), group.name),
        )
      },
    )
  }

  /** Copied from `GameSetupPage.renderSwap`/`swapMark`: reuse the pattern, not the (page-private) function. */
  private def renderSwap(): HtmlElement = {
    span(
      cls             := "tooltip",
      dataAttr("tip") := I18n.t(UiKeys.wordsSwapLanguages),
      button(
        typ        := "button",
        cls        := "btn btn-ghost btn-sm btn-square",
        aria.label := I18n.t(UiKeys.wordsSwapLanguages),
        swapMark(),
        onClick.mapToUnit --> Observer[Unit] { _ =>
          Var.set(sourceVar -> targetVar.now(), targetVar -> sourceVar.now())
        },
      ),
    )
  }

  /** The two arrows on the swap button — copied from `GameSetupPage.swapMark`. */
  private def swapMark(): SvgElement = {
    svg.svg(
      svg.cls            := "h-4 w-4",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "2",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(svg.d := "M4 9h15m0 0l-4-4m4 4l-4 4"),
      svg.path(svg.d := "M20 15H5m0 0l4-4m-4 4l4 4"),
    )
  }

  /** Copied from `GameSetupPage.languageSelect`: reuse the pattern, not the (page-private) function. */
  private def languageSelect(
    labelKey: String,
    selected: Signal[WordLanguage],
    onPick: Observer[WordLanguage],
  ): HtmlElement = {
    label(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(labelKey)),
      select(
        cls    := "select select-sm w-28",
        WordLanguage.all.map(language => option(value := WordLanguage.code(language), Labels.language(language))),
        controlled(
          value <-- selected.map(WordLanguage.code),
          onChange.mapToValue --> onPick.contramap[String](code =>
            WordLanguage.fromString(code).getOrElse(defaultSource)
          ),
        ),
      ),
    )
  }
}
