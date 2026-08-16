package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiClient, ApiError, GameApiClient}
import gathedge.frontend.components.{Alert, AppShell, GuestBanner, Labels}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.{AppState, GameOwnership}
import gathedge.shared.domain.{Tag, User, WordLanguage}
import gathedge.shared.dto.GameCreated
import gathedge.shared.i18n.UiKeys

/** Choosing a language pair and tags, and turning them into a fresh quiz.
  *
  * '''Unlike the vocabulary listing, reaching this screen mints a guest account.''' `GET /api/games/setup` requires a
  * session — there is no public read here the way `WordEndpoints.list` offers one — so the tag fetch this page runs on
  * mount goes through the same guest detour a tick on the listing does, just one step earlier. Landing here signed out
  * already means committing to play, which is the reasoning that makes this the one screen minting on a page view.
  */
object GameSetupPage {

  def render(): HtmlElement = {
    AppShell.render(Page.GameSetup, new GameSetupPage().render())
  }
}

private class GameSetupPage {

  private val defaultSource = WordLanguage.De
  private val defaultTarget = WordLanguage.Hu

  private val sourceVar = Var(defaultSource)
  private val targetVar = Var(defaultTarget)

  private val formSignal = sourceVar.signal.combineWith(targetVar.signal).distinct

  private val tagsVar    = Var(List.empty[Tag])
  private val tagsSignal = tagsVar.signal

  private val selectedTagIdsVar = Var(Set.empty[Long])

  private val formAndTagsSignal = formSignal.combineWith(selectedTagIdsVar.signal)

  private val loadingVar    = Var(false)
  private val loadingSignal = loadingVar.signal

  private val creatingVar    = Var(false)
  private val creatingSignal = creatingVar.signal

  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal                   = errorVar.signal

  private val noticeVar: Var[Option[String]] = Var(None)
  private val noticeSignal                   = noticeVar.signal

  private val createdVar = Var(Option.empty[GameCreated])

  private val userSignal = AppState.currentUserSignal

  /** Mirrors who the reader is at the moment a request is made — signals cannot be read outside a subscription, and the
    * guest detour needs `.now()`. Same trick as `WordCollect.readerVar`.
    */
  private val readerVar = Var(Option.empty[User])

  private val reloadBus = new EventBus[Unit]()
  private val playBus   = new EventBus[Unit]()

  private val formRequests = EventStream.merge(formSignal.updates, reloadBus.events.sample(formSignal))

  /** A per-account read or write, with the guest detour in front of it — copied in spirit from `WordCollect.asReader`.
    * With no session neither the tag fetch nor the create call can succeed, so a guest is minted first and the call is
    * retried against the session that creates. Signed in, the mint is skipped entirely.
    */
  private def asReader[A](write: () => EventStream[Either[ApiError, A]]): EventStream[Either[ApiError, A]] = {
    readerVar.now() match {
      case Some(_) =>
        write()
      case None    =>
        ApiClient.createGuest.flatMapSwitch {
          case Right(response) =>
            AppState.setUser(response.user)
            noticeVar.set(Some(I18n.t(UiKeys.guestBannerHint)))
            write()
          case Left(err)       =>
            EventStream.fromValue(Left(err))
        }
    }
  }

  def render(): HtmlElement = {
    div(
      cls := "p-4 max-w-2xl",
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.gameSetupTitle)),
      Alert.maybeError(errorSignal),
      Alert.maybeInfo(noticeSignal),
      child.maybe <-- createdVar.signal.map(
        _.map(created => Alert.success(I18n.t(UiKeys.gameSetupCreated, created.name)))
      ),
      div(
        cls  := "flex flex-wrap items-end gap-3 mb-4",
        languageSelect(UiKeys.gameSetupSourceLabel, sourceVar.signal, sourceVar.writer),
        languageSelect(UiKeys.gameSetupTargetLabel, targetVar.signal, targetVar.writer),
      ),
      div(
        cls  := "mb-4",
        span(cls := "label-text text-xs", I18n.t(UiKeys.gameSetupTagsLabel)),
        div(cls  := "flex flex-col gap-3 mt-1", children <-- tagsSignal.map(tagCheckboxGroups)),
        child.maybe <-- tagsSignal.combineWith(loadingSignal).map { case (tags, loading) =>
          Option.when(tags.isEmpty && !loading)(
            p(cls := "text-sm opacity-60 mt-1", I18n.t(UiKeys.gameSetupNoEligibleTags))
          )
        },
      ),
      renderPlayButton(),
      child.maybe <-- userSignal.map(user => Option.when(user.exists(_.isGuest))(GuestBanner.render())),
      AppState.currentUserSignal --> readerVar.writer,
      formRequests --> Observer[(WordLanguage, WordLanguage)](_ => Var.set(loadingVar -> true, errorVar -> None)),
      formRequests.flatMapSwitch { case (source, target) => asReader(() => GameApiClient.setup(source, target)) } -->
        Observer[Either[ApiError, List[Tag]]] {
          case Right(tags) =>
            val sorted           = Tag.sorted(tags)
            Var.set(
              tagsVar           -> sorted,
              loadingVar        -> false,
              errorVar          -> None,
              // A language change can drop tags that were eligible under the old pair.
              selectedTagIdsVar -> selectedTagIdsVar.now().filter(id => sorted.exists(_.id == id)),
            )
          case Left(err)   =>
            Var.set(loadingVar -> false, errorVar -> Some(err.message), tagsVar -> Nil)
        },
      playBus.events --> Observer[Unit](_ => Var.set(creatingVar -> true, errorVar -> None, createdVar -> None)),
      playBus.events.withCurrentValueOf(formAndTagsSignal).flatMapSwitch { case (source, target, tagIds) =>
        asReader(() => GameApiClient.create(source, target, tagIds.toList))
      } -->
        Observer[Either[ApiError, GameCreated]] {
          case Right(created) =>
            Var.set(creatingVar -> false, createdVar -> Some(created))
            // This browser is the one that created it, so it is offered the rename control — see `GameOwnership`'s
            // doc comment on why the game's own detail response cannot carry that flag itself.
            GameOwnership.markOwned(created.slug)
            AppRouter.router.pushState(Page.GameInstance(created.slug))
          case Left(err)      =>
            Var.set(creatingVar -> false, errorVar -> Some(err.message))
        },
      // Last, like every other page's initial load — see `WordsPage`'s or `AdminSystemPage`'s own placement: the
      // stream this triggers (`formRequests`, above) has to already have a subscriber when this fires, or the mount's
      // own reload is emitted to nobody and silently lost, leaving the tag list empty until something else (a
      // language change) asks again.
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  /** Copied from `WordsPage.languageSelect`: reuse the pattern, not the (page-private) function. */
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

  /** Checkboxes, not `<select multiple>` — a clearer control for an unbounded tag count. Grouped like
    * `WordCollect.tagOptionGroups`, reusing its group-label keys rather than minting new ones.
    */
  private def tagCheckboxGroups(tags: List[Tag]): List[HtmlElement] = {
    val (mine, others) = Tag.sorted(tags).partition(_.ownedByMe)
    List(
      Option.when(mine.nonEmpty)(tagGroup(UiKeys.wordsTagsMineGroup, mine)),
      Option.when(others.nonEmpty)(tagGroup(UiKeys.wordsTagsOthersGroup, others)),
    ).flatten
  }

  private def tagGroup(labelKey: String, tags: List[Tag]): HtmlElement = {
    div(
      span(cls := "label-text text-xs font-semibold", I18n.t(labelKey)),
      div(cls  := "flex flex-col gap-1", tags.map(tagCheckbox)),
    )
  }

  private def tagCheckbox(tag: Tag): HtmlElement = {
    label(
      cls := "label gap-2 justify-start cursor-pointer",
      input(
        typ    := "checkbox",
        cls    := "checkbox checkbox-sm",
        controlled(
          checked <-- selectedTagIdsVar.signal.map(_.contains(tag.id)),
          onClick.mapToChecked --> Observer[Boolean] { on =>
            selectedTagIdsVar.update(ids => if (on) ids + tag.id else ids - tag.id)
          },
        ),
      ),
      span(cls := "label-text text-sm", s"${tag.name} (${tag.wordCount})"),
    )
  }

  private def renderPlayButton(): HtmlElement = {
    button(
      typ := "button",
      cls := "btn btn-primary",
      disabled <-- selectedTagIdsVar.signal.combineWith(creatingSignal).map { case (ids, busy) =>
        ids.isEmpty || busy
      },
      I18n.t(UiKeys.gameSetupPlay),
      onClick.mapToUnit --> playBus.writer,
    )
  }
}
