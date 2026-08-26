package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GameApiClient, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, InlineRename, Labels, TagWordsList}
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

  private val inlineRename = new InlineRename[TagResponse](name => WordApiClient.renameTag(tagId, name))

  /** Gates both the pencil (`InlineRename.renderTitle`'s `canEdit`) and the delete icon below — the same condition, so
    * the two controls appear and disappear together rather than each re-deriving it. `Tag.editableByMe` is deliberately
    * not used here: rename/delete stay owner-only even for a tag a group has opened to this reader — see
    * `WordService.requireOwnTag`.
    */
  private val canEditSignal: Signal[Boolean] = tagVar.signal.map(_.exists(_.ownedByMe)).distinct

  /** The title text: the tag's name once it has loaded, else the generic placeholder — briefly, before the first
    * `WordApiClient.listTags` answers. Fed straight into `InlineRename.renderTitle`, which does not itself branch on
    * whether the tag exists: this page is only ever reached for one that does.
    */
  private val tagNameSignal: Signal[String] = {
    tagVar.signal.map(_.map(_.name).getOrElse(I18n.t(UiKeys.tagDetailTitle))).distinct
  }

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
          inlineRename.renderTitle(
            tagNameSignal,
            canEditSignal,
            I18n.t(UiKeys.wordsTagRenameButton),
            I18n.t(UiKeys.wordsTagRenameLabel),
            "input text-xl",
            deleteIcon(),
          ),
          child.maybe <-- tagVar.signal.map(_.map(renderMeta)),
          child.maybe <-- tagVar.signal.map(_.map(renderDeleteModal)),
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
      inlineRename.bindings(onSaved = Observer[TagResponse](response => tagVar.set(Some(response.tag)))),
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

  /** The delete icon beside the title — `extra` in the `InlineRename.renderTitle` call above — gated on
    * [[canEditSignal]], the same condition the pencil itself uses.
    */
  private def deleteIcon(): Modifier[HtmlElement] = {
    child.maybe <-- canEditSignal.map(
      Option.when(_)(
        InlineRename.iconButton(
          I18n.t(UiKeys.wordsTagDeleteButton),
          trashMark(),
          onClick.mapToUnit --> Observer[Unit](_ => deleteOpenVar.set(true)),
        )
      )
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
