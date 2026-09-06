package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiClient, ApiError, GameApiClient}
import gathedge.frontend.components.{Alert, AppShell, Labels, TagWordsList}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.{AppState, GameOwnership}
import gathedge.shared.domain.{Tag, User, WordLanguage}
import gathedge.shared.dto.{GameCreated, GameSetupWord}
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

  /** Case-insensitive substring match on tag name — the narrowing the filter box applies to an already-fetched tag
    * list. Purely client-side: `tagsVar` is populated once per language-pair change, and this never re-asks the server.
    */
  def matchingTags(tags: List[Tag], filter: String): List[Tag] = {
    val needle = filter.trim.toLowerCase
    if (needle.isEmpty) tags else tags.filter(_.name.toLowerCase.contains(needle))
  }

  /** The eligible-word preview flipped: every `text -> translations` row becomes the reverse edges, regrouped so each
    * former translation is now a source row with its own accepted answers. A swap of the language pair is the same pair
    * the other way round, so the preview is re-oriented in the browser rather than refetched.
    *
    * `wordId` is not carried back (a translation string has none) and `partOfSpeech` is dropped; the page renders the
    * pristine fetched list again on an even number of swaps (`wordsInvertedVar`), so nothing is lost by swapping twice.
    */
  def swapWords(words: List[GameSetupWord]): List[GameSetupWord] = {
    words
      .flatMap(word => word.translations.map(translation => translation -> word.text))
      .groupBy { case (translation, _) => translation }
      .map { case (translation, pairs) =>
        GameSetupWord(wordId = 0L, text = translation, translations = pairs.map(_._2).distinct.sorted)
      }
      .toList
      .sortBy(_.text)
  }
}

private class GameSetupPage {

  private val defaultSource = WordLanguage.De
  private val defaultTarget = WordLanguage.Hu

  private val sourceVar = Var(defaultSource)
  private val targetVar = Var(defaultTarget)

  private val formSignal = sourceVar.signal.combineWith(targetVar.signal).distinct

  /** The language pair with its order ignored. Swapping source and target is the same pair, so neither the tag list nor
    * the word-list preview is refetched for it: the eligible set is identical either way (`word_tag_pairs` rows are
    * stored in both directions), and a swap only re-orients what is already on screen.
    */
  private val languagePairSignal = formSignal.distinctBy { case (source, target) => Set(source, target) }

  private val tagsVar    = Var(List.empty[Tag])
  private val tagsSignal = tagsVar.signal

  private val tagFilterVar                          = Var("")
  private val filteredTagsSignal: Signal[List[Tag]] = {
    tagsSignal.combineWith(tagFilterVar.signal).map { case (tags, filter) =>
      GameSetupPage.matchingTags(tags, filter)
    }
  }

  private val selectedTagIdsVar = Var(Set.empty[Long])

  /** True while at least one tag is selected: the two language selects lock to the picked pair, the same guard
    * `WordsPage.languagesLockedSignal` puts on the listing once a collect tag is chosen. The swap button stays live.
    */
  private val languagesLockedSignal: Signal[Boolean] = selectedTagIdsVar.signal.map(_.nonEmpty)

  private val formAndTagsSignal = formSignal.combineWith(selectedTagIdsVar.signal)

  /** The setup screen's word-list preview, refetched whenever the language pair (order ignored) or the tag selection
    * changes — see `GameApiClient.setupWords`. Empty tag ids never reach the network: an unselected setup form's word
    * list is trivially empty, the same shortcut the backend itself takes. A pure swap does not refetch; `wordsView`
    * re-orients the loaded rows through `GameSetupPage.swapWords` instead.
    */
  private val wordsQuerySignal = {
    formSignal.combineWith(selectedTagIdsVar.signal).distinctBy { case (source, target, tagIds) =>
      (Set(source, target), tagIds)
    }
  }

  private val wordsVar        = Var(List.empty[GameSetupWord])
  private val wordsLoadingVar = Var(false)

  /** `true` after an odd number of swaps since the last fetch: `wordsVar` holds the rows as fetched, and the preview
    * shows them flipped. Reset on every real word-list fetch.
    */
  private val wordsInvertedVar = Var(false)

  /** What the preview column renders: the fetched rows, or `swapWords` of them after a swap. */
  private val wordsView: Signal[List[GameSetupWord]] = {
    wordsVar.signal.combineWith(wordsInvertedVar.signal).map { case (words, inverted) =>
      if (inverted) GameSetupPage.swapWords(words) else words
    }
  }

  private val loadingVar    = Var(false)
  private val loadingSignal = loadingVar.signal

  private val creatingVar    = Var(false)
  private val creatingSignal = creatingVar.signal

  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal                   = errorVar.signal

  private val createdVar = Var(Option.empty[GameCreated])

  private val userSignal = AppState.currentUserSignal

  /** Mirrors who the reader is at the moment a request is made — signals cannot be read outside a subscription, and the
    * guest detour needs `.now()`. Same trick as `WordCollect.readerVar`.
    */
  private val readerVar = Var(Option.empty[User])

  private val reloadBus = new EventBus[Unit]()
  private val playBus   = new EventBus[Unit]()

  private val formRequests =
    EventStream.merge(languagePairSignal.updates, reloadBus.events.sample(formSignal))

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
            write()
          case Left(err)       =>
            EventStream.fromValue(Left(err))
        }
    }
  }

  def render(): HtmlElement = {
    div(
      cls := "max-w-4xl mx-auto",
      Alert.maybeError(errorSignal),
      child.maybe <-- createdVar.signal.map(
        _.map(created => Alert.success(I18n.t(UiKeys.gameSetupCreated, created.name)))
      ),
      div(
        cls := "card bg-base-100 shadow mt-4",
        div(
          cls := "card-body",
          h1(cls := "card-title text-2xl", I18n.t(UiKeys.gameSetupTitle)),
          div(
            cls  := "flex flex-wrap items-end gap-3 mb-4",
            languageSelect(UiKeys.gameSetupSourceLabel, sourceVar.signal, sourceVar.writer, languagesLockedSignal),
            renderSwap(),
            languageSelect(UiKeys.gameSetupTargetLabel, targetVar.signal, targetVar.writer, languagesLockedSignal),
          ),
          div(
            cls  := "flex flex-col md:flex-row gap-6",
            renderTagsColumn(),
            renderWordsColumn(),
          ),
          renderPlayButton(),
        ),
      ),
      AppState.currentUserSignal --> readerVar.writer,
      // A real language-pair change (order ignored — a swap is not one) fetches a different tag list, so a filter typed
      // against the old one is cleared with it — the same reasoning that already drops now-ineligible selections below.
      formRequests -->
        Observer[(WordLanguage, WordLanguage)](_ => Var.set(loadingVar -> true, errorVar -> None, tagFilterVar -> "")),
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
      wordsQuerySignal.updates --> Observer[(WordLanguage, WordLanguage, Set[Long])](_ =>
        Var.set(wordsLoadingVar -> true, wordsInvertedVar -> false)
      ),
      wordsQuerySignal.updates.flatMapSwitch { case (source, target, tagIds) =>
        if (tagIds.isEmpty)
          EventStream.fromValue(Right(Nil))
        else
          asReader(() => GameApiClient.setupWords(source, target, tagIds))
      } -->
        Observer[Either[ApiError, List[GameSetupWord]]] {
          case Right(words) =>
            Var.set(wordsVar -> words, wordsLoadingVar -> false)
          case Left(err)    =>
            Var.set(wordsLoadingVar -> false, errorVar -> Some(err.message))
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

  private def renderTagsColumn(): HtmlElement = {
    div(
      cls := "flex-1",
      span(cls := "label-text text-xs", I18n.t(UiKeys.gameSetupTagsLabel)),
      label(
        cls    := "flex flex-col gap-1 mt-1 max-w-xs",
        span(cls      := "label-text text-xs", I18n.t(UiKeys.gameSetupTagFilterLabel)),
        input(
          cls         := "input input-sm",
          typ         := "search",
          placeholder := I18n.t(UiKeys.gameSetupTagFilterPlaceholder),
          controlled(value <-- tagFilterVar.signal, onInput.mapToValue --> tagFilterVar.writer),
        ),
      ),
      div(cls  := "flex flex-col gap-3 mt-2", children <-- filteredTagsSignal.map(tagCheckboxGroups)),
      child.maybe <-- tagsSignal.combineWith(loadingSignal).map { case (tags, loading) =>
        Option.when(tags.isEmpty && !loading)(
          p(cls := "text-sm opacity-60 mt-1", I18n.t(UiKeys.gameSetupNoEligibleTags))
        )
      },
      // Distinct from the message above: tags exist for this language pair, the filter just matched none of them.
      child.maybe <-- tagsSignal.combineWith(filteredTagsSignal, loadingSignal).map { case (tags, filtered, loading) =>
        Option.when(tags.nonEmpty && filtered.isEmpty && !loading)(
          p(cls := "text-sm opacity-60 mt-1", I18n.t(UiKeys.gameSetupNoMatchingTags))
        )
      },
    )
  }

  private def renderWordsColumn(): HtmlElement = {
    div(
      cls := "flex-1",
      TagWordsList.render(wordsView, wordsLoadingVar.signal),
    )
  }

  /** Copied from `WordsPage.renderSwap`/`swapMark`: reuse the pattern, not the (page-private) function. Swaps both vars
    * in one `Var.set` call so `formSignal` fires once, not twice. A swap is the same pair the other way round, so it
    * refetches nothing: it flips `wordsInvertedVar`, and `wordsView` re-orients the loaded preview rows. Stays enabled
    * while the language selects are locked — the one language control a picked tag does not freeze.
    */
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
          Var.set(
            sourceVar        -> targetVar.now(),
            targetVar        -> sourceVar.now(),
            wordsInvertedVar -> !wordsInvertedVar.now(),
          )
        },
      ),
    )
  }

  /** The two arrows on the swap button — copied from `WordsPage.swapMark`. */
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

  /** Copied from `WordsPage.languageSelect`: reuse the pattern, not the (page-private) function. `locked` disables the
    * select while a tag is selected, so the pair cannot drift off the picked tags; the swap button is left live.
    */
  private def languageSelect(
    labelKey: String,
    selected: Signal[WordLanguage],
    onPick: Observer[WordLanguage],
    locked: Signal[Boolean],
  ): HtmlElement = {
    label(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(labelKey)),
      span(
        // The tooltip has to be a wrapper — daisyUI's `.tooltip` is `display:inline-block` — and it carries a class
        // only while locked, so nothing draws around a live select.
        cls("tooltip") <-- locked,
        dataAttr("tip") <-- locked.map(on => if (on) I18n.t(UiKeys.gameSetupLanguagesLockedHint) else ""),
        select(
          cls := "select select-sm w-28",
          disabled <-- locked,
          WordLanguage.all.map(language => option(value := WordLanguage.code(language), Labels.language(language))),
          controlled(
            value <-- selected.map(WordLanguage.code),
            onChange.mapToValue --> onPick.contramap[String](code =>
              WordLanguage.fromString(code).getOrElse(defaultSource)
            ),
          ),
        ),
      ),
      // The tooltip is CSS-drawn from a `data-` attribute, so it says nothing to a screen reader; this does.
      span(
        cls    := "sr-only",
        child.text <-- locked.map(on => if (on) I18n.t(UiKeys.gameSetupLanguagesLockedHint) else ""),
      ),
    )
  }

  /** Checkboxes, not `<select multiple>` — a clearer control for an unbounded tag count. Grouped like
    * `WordCollect.mineOptions`: own tags first (ungrouped), then one section per shared `GroupRef` — named with the
    * group's own name, not an i18n key, same reasoning as there — so a classroom's tags read together instead of
    * scattered through "Other tags". Anything left over (no group, not mine) still falls into "Other tags".
    */
  private def tagCheckboxGroups(tags: List[Tag]): List[HtmlElement] = {
    val (mine, others)       = Tag.sorted(tags).partition(_.ownedByMe)
    val (grouped, ungrouped) = others.partition(_.group.isDefined)
    val groupSections        = grouped
      .groupBy(_.group.get.name)
      .toList
      .sortBy { case (name, _) => name.toLowerCase }
      .map { case (name, groupTags) => tagGroup(name, groupTags.sortBy(_.name.toLowerCase)) }
    List(Option.when(mine.nonEmpty)(tagGroup(I18n.t(UiKeys.wordsTagsMineGroup), mine))).flatten ++
      groupSections ++
      List(Option.when(ungrouped.nonEmpty)(tagGroup(I18n.t(UiKeys.wordsTagsOthersGroup), ungrouped))).flatten
  }

  private def tagGroup(label: String, tags: List[Tag]): HtmlElement = {
    div(
      span(cls := "label-text text-xs font-semibold", label),
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
