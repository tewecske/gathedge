package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GameApiClient, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, Labels, TagWordsList}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{Tag, WordLanguage}
import gathedge.shared.dto.GameSetupWord
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
          h1(
            cls   := "card-title text-2xl",
            child.text <-- tagVar.signal.map(_.map(_.name).getOrElse(I18n.t(UiKeys.tagDetailTitle))),
          ),
          child.maybe <-- tagVar.signal.map(_.map(renderMeta)),
          div(
            cls   := "flex flex-wrap items-end gap-3 mt-2",
            languageSelect(UiKeys.gameSetupSourceLabel, sourceVar.signal, sourceVar.writer),
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
