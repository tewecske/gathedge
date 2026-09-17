package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GameApiClient, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, Formats, Labels, Pagination, SortHeader, TagPicker}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.AllGameQuery
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{Tag, WordLanguage}
import gathedge.shared.dto.{AllGamePage, AllGameSort, AllGameSummary, GameTagRef}
import gathedge.shared.i18n.UiKeys

/** Every account's games: name, tags, language pair, how many times each was played, how many accounts favorited it,
  * and when it was created — see `GameService.allGames`.
  *
  * Built to the same shape as `GameResultsPage`/`MyPlayHistoryPage`: a card table with sortable headings, a filter box
  * (here a substring of the game's name), a "my favorites" toggle, and server-side paging. It carries its whole listing
  * state in the URL, so it takes a `Signal[AllGameQuery]` and an `Observer[AllGameQuery]` the same way those pages do;
  * `App` supplies both. Each row's heart button toggles the caller's favorite mark — patched optimistically, reverted
  * if the call fails. There is no per-row detail modal — a game's own page is one click away on its name.
  *
  * Public: a signed-out visitor reads the catalog to find a game to play. Favoriting needs an account, so the heart
  * button and the "my favorites" toggle are drawn only when signed in — the same way `WordsPage` hides its tag
  * controls.
  *
  * The wordlist filter (`TagPicker`) and the two language filters work the same way `WordsPage`'s direction and collect
  * wordlist do: choosing a wordlist sets both language selects to its own pair and locks them there, since a game's
  * language pair is exactly its wordlist's. Unlike `WordsPage`, both are ordinary listing filters here rather than a
  * mandatory browsing direction, so each may be cleared back to "Any".
  */
object AllGamesPage {

  def render(query: Signal[AllGameQuery], onQuery: Observer[AllGameQuery]): HtmlElement = {
    AppShell.render(Page.AllGames(), new AllGamesPage(query, onQuery).render())
  }
}

private class AllGamesPage(pageQuery: Signal[AllGameQuery], onQuery: Observer[AllGameQuery]) {

  private val querySignal = pageQuery.distinct

  private val signedInSignal = AppState.isSignedInSignal

  private val gamesVar    = Var(List.empty[AllGameSummary])
  private val gamesSignal = gamesVar.signal

  private val totalVar    = Var(0L)
  private val totalSignal = totalVar.signal

  private val sortSignal     = querySignal.map(_.sort).distinct
  private val pageSignal     = querySignal.map(_.page).distinct
  private val pageSizeSignal = querySignal.map(_.pageSize).distinct

  private val changeBus = new EventBus[AllGameQuery => AllGameQuery]()

  private def change(edit: AllGameQuery => AllGameQuery): Unit = changeBus.emit(edit)

  // Same write-follows-the-query trick as `GameResultsPage`/`MyPlayHistoryPage` — the box cannot be a plain two-way
  // binding on the query itself.
  private val searchInputVar   = Var("")
  private val searchTypedBus   = new EventBus[String]()
  private val searchDebounceMs = 300

  /** Every wordlist there is — the same unpaged, public `GET /api/tags` every tag `<select>` on the site reads —
    * fetched once on mount. [[tagPicker]] searches the server on its own now; this is read only to resolve a `tagId`
    * the URL already names (a bookmarked filter, or the page arriving mid-session with one chosen) into the [[Tag]]
    * [[selectedTagSignal]] and [[tagPicker]]'s own hydration need.
    */
  private val tagsVar    = Var(List.empty[Tag])
  private val tagsSignal = tagsVar.signal
  private val tagsBus    = new EventBus[Unit]()

  /** The languages currently chosen, in the order they were chosen — [[TagPicker]]'s own `languages` narrows and ranks
    * by this, and the two language `<select>`s below read their values back out of it.
    */
  private val selectedLanguagesSignal: Signal[List[WordLanguage]] =
    querySignal.map(query => List(query.language1, query.language2).flatten).distinct

  private val selectedTagSignal: Signal[Option[Tag]] =
    querySignal.map(_.tagId).combineWithFn(tagsSignal)((id, tags) => id.flatMap(tid => tags.find(_.id == tid)))

  /** True while a wordlist is chosen: the two language selects are disabled, since a game's language pair is exactly
    * its wordlist's — the same lock `WordsPage.languagesLockedSignal` puts on its own pair for a collect wordlist.
    */
  private val languagesLockedSignal: Signal[Boolean] = selectedTagSignal.map(_.isDefined)

  private val tagPicker = new TagPicker(
    languages = selectedLanguagesSignal,
    selectedTag = selectedTagSignal,
    onSelect = Observer[Tag] { tag =>
      change(
        _.reset(
          _.copy(tagId = Some(tag.id), language1 = Some(tag.sourceLanguage), language2 = Some(tag.targetLanguage))
        )
      )
    },
    placeholderText = I18n.t(UiKeys.allGamesTagFilterPlaceholder),
  )

  private val errorVar: Var[Option[String]] = Var(None)

  private val loadingVar    = Var(false)
  private val loadingSignal = loadingVar.signal

  private val reloadBus    = new EventBus[Unit]()
  private val listRequests = EventStream.merge(querySignal.updates, reloadBus.events.sample(querySignal))

  // The row as it looked when its heart was clicked — carries the pre-toggle `favoritedByMe`/`likeCount`, so a failed
  // call can put both back exactly.
  private val favoriteToggleBus = new EventBus[AllGameSummary]()

  /** Rewrites one row in place: its heart state, and its like count nudged by `delta` (never below zero). */
  private def patchFavorite(slug: String, favorited: Boolean, delta: Long): Unit = {
    gamesVar.update(_.map { game =>
      if (game.slug == slug)
        game.copy(favoritedByMe = favorited, likeCount = math.max(0L, game.likeCount + delta))
      else
        game
    })
  }

  def render(): HtmlElement = {
    div(
      div(
        cls := "mb-4",
        h1(cls := "text-2xl font-bold", I18n.t(UiKeys.allGamesTitle)),
      ),
      Alert.maybeError(errorVar.signal),
      renderSearch(),
      renderTable(),
      Pagination.render(
        page = pageSignal,
        total = totalSignal,
        pageSize = pageSizeSignal,
        onPage = Observer[Int](page => change(_.copy(page = page))),
        onPageSize = Observer[Int](size => change(_.reset(_.copy(pageSize = size)))),
        summary = totalSignal.map(summaryOf).distinct,
        busy = loadingSignal,
      ),
      changeBus.events.withCurrentValueOf(querySignal).map { case (edit, current) => edit(current) } --> onQuery,
      querySignal.map(_.search).distinct --> searchInputVar.writer,
      searchTypedBus.events.debounce(searchDebounceMs).withCurrentValueOf(querySignal) -->
        Observer[(String, AllGameQuery)] { case (typed, current) =>
          val wanted = typed.trim
          if (wanted != current.search) {
            change(_.reset(_.copy(search = wanted)))
          }
        },
      listRequests -->
        Observer[AllGameQuery](_ => Var.set(loadingVar -> true, errorVar -> None)),
      listRequests.flatMapSwitch(load) -->
        Observer[Either[ApiError, AllGamePage]] {
          case Right(result) =>
            Var.set(
              gamesVar   -> result.items,
              totalVar   -> result.total,
              loadingVar -> false,
              errorVar   -> None,
            )
          case Left(err)     =>
            Var.set(loadingVar -> false, errorVar -> Some(err.message))
        },
      // Patch the row the instant the heart is clicked, then fire the call; a failure puts the row back and surfaces
      // the error. Concurrent toggles on different rows are independent, hence `flatMapMerge`.
      favoriteToggleBus.events -->
        Observer[AllGameSummary](game =>
          patchFavorite(game.slug, !game.favoritedByMe, if (game.favoritedByMe) -1L else 1L)
        ),
      favoriteToggleBus.events.flatMapMerge { game =>
        val adding = !game.favoritedByMe
        val call   = if (adding) GameApiClient.favorite(game.slug) else GameApiClient.unfavorite(game.slug)
        call.map(result => (game, adding, result))
      } -->
        Observer[(AllGameSummary, Boolean, Either[ApiError, Unit])] {
          case (game, adding, Left(err)) =>
            patchFavorite(game.slug, game.favoritedByMe, if (adding) -1L else 1L)
            errorVar.set(Some(err.message))
          case (_, _, Right(_))          =>
            ()
        },
      tagsBus.events.flatMapSwitch(_ => WordApiClient.listTags) --> Observer[Either[ApiError, List[Tag]]] {
        case Right(tags) => tagsVar.set(Tag.sorted(tags))
        case Left(_)     => ()
      },
      onMountCallback(_ => { reloadBus.emit(()); tagsBus.emit(()) }),
    )
  }

  private def load(query: AllGameQuery): EventStream[Either[ApiError, AllGamePage]] = {
    GameApiClient.allGames(
      page = Some(query.page),
      pageSize = Some(query.pageSize),
      sort = query.sort.column,
      dir = query.sort.wire,
      search = Option(query.search).filter(_.nonEmpty),
      favoritesOnly = Option.when(query.favoritesOnly)(true),
      tagId = query.tagId,
      language1 = query.language1,
      language2 = query.language2,
    )
  }

  private def summaryOf(total: Long): String = {
    if (total <= 0L)
      I18n.t(UiKeys.allGamesEmpty)
    else
      I18n.plural(UiKeys.allGamesCount, total)
  }

  private def renderSearch(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-end gap-4 mb-4",
      label(
        cls := "form-control",
        span(cls      := "label-text text-xs", I18n.t(UiKeys.allGamesFilterLabel)),
        input(
          cls         := "input input-sm",
          typ         := "search",
          placeholder := I18n.t(UiKeys.allGamesFilterPlaceholder),
          controlled(value <-- searchInputVar.signal, onInput.mapToValue --> searchInputVar.writer),
          onInput.mapToValue --> searchTypedBus.writer,
        ),
      ),
      renderTagFilter(),
      languageSelect(
        UiKeys.allGamesSourceCol,
        querySignal.map(_.language1).distinct,
        Observer[Option[WordLanguage]](language => change(_.reset(_.copy(language1 = language)))),
        languagesLockedSignal,
      ),
      languageSelect(
        UiKeys.allGamesTargetCol,
        querySignal.map(_.language2).distinct,
        Observer[Option[WordLanguage]](language => change(_.reset(_.copy(language2 = language)))),
        languagesLockedSignal,
      ),
      // Favoriting needs an account, so the filter is offered only to a signed-in visitor.
      child.maybe <-- signedInSignal.map(Option.when(_)(renderFavoritesToggle())),
    )
  }

  private def renderFavoritesToggle(): HtmlElement = {
    label(
      cls := "label cursor-pointer gap-2",
      input(
        typ    := "checkbox",
        cls    := "toggle toggle-sm",
        checked <-- querySignal.map(_.favoritesOnly),
        onClick.mapToChecked --> Observer[Boolean](on => change(_.reset(_.copy(favoritesOnly = on)))),
      ),
      span(cls := "label-text text-xs", I18n.t(UiKeys.allGamesFavoritesFilter)),
    )
  }

  /** The wordlist filter: [[tagPicker]], with a clear button beside it once a wordlist is chosen — typing over it is
    * not enough on its own, since a search box for one is the same widget as a search box for none. See [[TagPicker]]'s
    * doc comment for why clearing is this page's job rather than the widget's own.
    */
  private def renderTagFilter(): HtmlElement = {
    label(
      cls := "form-control",
      span(cls := "label-text text-xs", I18n.t(UiKeys.allGamesTagFilterLabel)),
      div(
        cls    := "flex items-center gap-1",
        div(cls := "w-48", tagPicker.render()),
        child.maybe <-- querySignal.map(_.tagId.isDefined).distinct.map(Option.when(_)(renderClearTag())),
      ),
    )
  }

  private def renderClearTag(): HtmlElement = {
    button(
      typ        := "button",
      cls        := "btn btn-ghost btn-sm btn-square",
      aria.label := I18n.t(UiKeys.allGamesTagFilterClear),
      "×",
      onClick.mapToUnit --> Observer[Unit] { _ =>
        tagPicker.clear()
        change(_.reset(_.copy(tagId = None)))
      },
    )
  }

  /** Every `<select>` here carries a literal width, the same reason `WordsPage.languageSelect` gives for its own.
    * Unlike that one, `None` is a real option ("Any"), since this is an ordinary listing filter rather than a mandatory
    * browsing direction.
    */
  private def languageSelect(
    labelKey: String,
    selected: Signal[Option[WordLanguage]],
    onPick: Observer[Option[WordLanguage]],
    locked: Signal[Boolean],
  ): HtmlElement = {
    label(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(labelKey)),
      span(
        cls("tooltip") <-- locked,
        dataAttr("tip") <-- locked.map(on => if (on) I18n.t(UiKeys.allGamesLanguageLockedHint) else ""),
        select(
          cls := "select select-sm w-28",
          disabled <-- locked,
          option(value := "", I18n.t(UiKeys.allGamesLanguageAny)),
          WordLanguage.all.map(language => option(value := WordLanguage.code(language), Labels.language(language))),
          controlled(
            value <-- selected.map(_.map(WordLanguage.code).getOrElse("")),
            onChange.mapToValue --> onPick.contramap[String](code => WordLanguage.fromString(code)),
          ),
        ),
      ),
      span(
        cls    := "sr-only",
        child.text <-- locked.map(on => if (on) I18n.t(UiKeys.allGamesLanguageLockedHint) else ""),
      ),
    )
  }

  private def renderTable(): HtmlElement = {
    val onSort = Observer[SortHeader.Sort](sort => change(_.reset(_.copy(sort = sort))))

    div(
      cls := "overflow-x-auto card bg-base-100 shadow",
      table(
        cls := "table",
        thead(
          tr(
            // The first column holds the heart button and the like count together, so it carries the likes sort.
            SortHeader.render(I18n.t(UiKeys.allGamesLikesCol), AllGameSort.likeCount, sortSignal, onSort),
            SortHeader.render(I18n.t(UiKeys.allGamesNameCol), AllGameSort.name, sortSignal, onSort),
            // Tags, the language pair and the play count are filterable/readable but not sortable — see `AllGameSort`.
            th(I18n.t(UiKeys.allGamesTagsCol)),
            th(I18n.t(UiKeys.allGamesSourceCol)),
            th(I18n.t(UiKeys.allGamesTargetCol)),
            th(I18n.t(UiKeys.allGamesPlaysCol)),
            SortHeader.render(I18n.t(UiKeys.allGamesCreatedCol), AllGameSort.createdAt, sortSignal, onSort),
          )
        ),
        tbody(
          children <--
            gamesSignal.map(_.map(renderRow))
        ),
      ),
    )
  }

  private def renderRow(game: AllGameSummary): HtmlElement = {
    tr(
      cls := "hover",
      td(
        div(
          cls := "flex items-center gap-1",
          // The heart toggles the caller's own mark, so it needs an account; the like count stays for everyone.
          child.maybe <-- signedInSignal.map(Option.when(_)(renderFavorite(game))),
          span(cls := "tabular-nums", game.likeCount.toString),
        )
      ),
      td(a(cls := "link link-hover", AppRouter.router.navigateTo(Page.GameInstance(game.slug)), game.name)),
      td(renderTags(game.tags)),
      td(Labels.language(game.sourceLanguage)),
      td(Labels.language(game.targetLanguage)),
      td(game.playCount.toString),
      td(Formats.dateTime(game.createdAt)),
    )
  }

  private def renderFavorite(game: AllGameSummary): HtmlElement = {
    button(
      typ               := "button",
      cls               := "btn btn-ghost btn-circle btn-sm",
      cls("text-error") := game.favoritedByMe,
      aria.label        := I18n.t(if (game.favoritedByMe) UiKeys.allGamesFavoriteRemove else UiKeys.allGamesFavoriteAdd),
      if (game.favoritedByMe) heartSolid() else heartOutline(),
      onClick.mapTo(game) --> favoriteToggleBus.writer,
    )
  }

  private def heartOutline(): SvgElement = {
    svg.svg(
      svg.cls            := "h-4 w-4",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "1.5",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(
        svg.d :=
          "M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12Z"
      ),
    )
  }

  private def heartSolid(): SvgElement = {
    svg.svg(
      svg.cls     := "h-4 w-4",
      svg.viewBox := "0 0 24 24",
      svg.fill    := "currentColor",
      svg.path(
        svg.d :=
          "m11.645 20.91-.007-.003-.022-.012a15.247 15.247 0 0 1-.383-.218 25.18 25.18 0 0 1-4.244-3.17C4.688 15.36 2.25 12.174 2.25 8.25 2.25 5.322 4.714 3 7.688 3A5.5 5.5 0 0 1 12 5.052 5.5 5.5 0 0 1 16.313 3c2.973 0 5.437 2.322 5.437 5.25 0 3.925-2.438 7.111-4.739 9.256a25.175 25.175 0 0 1-4.244 3.17 15.247 15.247 0 0 1-.383.219l-.022.012-.007.004-.003.001a.752.752 0 0 1-.704 0l-.003-.001Z"
      ),
    )
  }

  private def renderTags(tags: List[GameTagRef]): HtmlElement = {
    div(
      cls := "flex flex-wrap gap-2",
      tags.map(tag => {
        a(
          cls := "link",
          AppRouter.router.navigateTo(Page.TagDetail(tag.id)),
          tag.name,
        )
      }),
    )
  }
}
