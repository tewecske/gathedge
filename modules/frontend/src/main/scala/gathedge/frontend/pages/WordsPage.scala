package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.AppRouter
import gathedge.frontend.Page
import gathedge.frontend.api.{ApiClient, ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, GuestBanner, Labels, Pagination, SortHeader}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.WordQuery
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{Gender, PartOfSpeech, Tag, User, Word, WordLanguage}
import gathedge.shared.dto.{
  CreateWordRequest,
  NewTranslation,
  TranslationOption,
  WordDetail,
  WordPage,
  WordSort,
  WordSummary,
}
import gathedge.shared.i18n.UiKeys

/** Browse the dictionary and tag what you want to learn.
  *
  * The screen is one interaction: choose a tag, type, click rows. A click is the whole of "add this word to my
  * vocabulary" — there is no dialog and no save button, because the thing being recorded is small enough that
  * confirming it would cost more than getting it wrong.
  *
  * '''It works signed out.''' The listing is public, so a visitor sees the same words; the first row they click mints
  * them a guest account ([[ApiClient.createGuest]]) and the tag write is retried against it. That is why the tag
  * controls only appear once there is somebody to own a tag.
  *
  * Its listing state lives in the URL, like the two admin listings — see [[WordQuery]] — so this page owns none of it
  * and takes the pair `App` supplies.
  */
object WordsPage {

  def render(query: Signal[WordQuery], onQuery: Observer[WordQuery]): HtmlElement = {
    AppShell.render(Page.Words(), new WordsPage(query, onQuery).render())
  }

  /** The tag a word goes under when the reader has chosen none. Data rather than copy: it becomes a row in `tags` that
    * they can rename or delete, so it is not translated — a tag created in Hungarian and then read in English would
    * otherwise appear to change its name.
    */
  val defaultTagName = "saved"

  /** Where the tag a tick files into is remembered.
    *
    * '''It is deliberately not in the URL, and that is the whole of the fix for the two controls being confusable.''' A
    * `?tag=` in the address narrows the listing — a view of the data, worth bookmarking and sending — while this is the
    * reader's working state, which nobody wants to send anybody. One select doing both meant that narrowing to
    * `lesson1` silently redirected every subsequent tick into it, and that choosing where to file emptied the listing
    * of everything not already in it.
    */
  private val collectStorageKey = "words.tag"

  /** Wrapped like `AppState`'s theme access, and for the same reason: the storage API throws rather than returns null
    * when the browser has it disabled, and a remembered tag is not worth failing a page load over.
    */
  def storedCollectTag: Option[Long] = {
    try Option(dom.window.localStorage.getItem(collectStorageKey)).flatMap(_.toLongOption)
    catch { case _: Throwable => None }
  }

  def storeCollectTag(tagId: Option[Long]): Unit = {
    try {
      tagId match {
        case Some(id) =>
          dom.window.localStorage.setItem(collectStorageKey, id.toString)
        case None     =>
          dom.window.localStorage.removeItem(collectStorageKey)
      }
    } catch { case _: Throwable => () }
  }

  /** Which of a row's translations count as marked, given the tag the page is collecting into.
    *
    * A chip has to answer the question the *click* acts on — "is this marked under the collect tag" — and never "under
    * any tag I have", which is the mistake that made a filtered listing look fully collected. `None` is only the moment
    * before the tag list arrives, or a reader with no tags at all, where the honest answer is whatever they have.
    *
    * On the companion rather than in the class so it can be stated as a table in a test: the page has no seam for
    * injecting rows, and this is the part of a chip that can be wrong.
    */
  def selectedTranslationIds(summary: WordSummary, collect: Option[Long]): Set[Long] = {
    collect match {
      case Some(tagId) =>
        summary.pairs.filter(_.tagId == tagId).map(_.translationWordId).toSet
      case None        =>
        summary.pairs.map(_.translationWordId).toSet
    }
  }
}

private class WordsPage(pageQuery: Signal[WordQuery], onQuery: Observer[WordQuery]) {

  /** `.distinct` because every reader here treats an emission as "ask the server again". */
  private val querySignal = pageQuery.distinct

  private val wordsVar    = Var(List.empty[WordSummary])
  private val wordsSignal = wordsVar.signal

  private val totalVar    = Var(0L)
  private val totalSignal = totalVar.signal

  private val tagsVar    = Var(List.empty[Tag])
  private val tagsSignal = tagsVar.signal

  private val sortSignal     = querySignal.map(_.sort).distinct
  private val pageSignal     = querySignal.map(_.page).distinct
  private val pageSizeSignal = querySignal.map(_.pageSize).distinct
  private val targetSignal   = querySignal.map(_.target).distinct

  /** The tag the *listing* is narrowed to. Nothing else reads it — where a tick files is [[collectTagVar]]. */
  private val filterTagSignal = querySignal.map(_.tagId).distinct

  private val userSignal     = AppState.currentUserSignal
  private val signedInSignal = AppState.isSignedInSignal

  /** Mirrors who the reader is at the moment a row is *clicked*. Signals cannot be read outside a subscription, and a
    * click handler is not one; this follows its signal through an observer and is read with `.now()`.
    */
  private val readerVar = Var(Option.empty[User])

  /** The tag a tick files into: remembered per browser (see [[WordsPage.storedCollectTag]]), never in the URL. `None`
    * only until the reader's tag list arrives, or while they have no tags at all — a tick then makes one.
    */
  private val collectTagVar    = Var(WordsPage.storedCollectTag)
  private val collectTagSignal = collectTagVar.signal

  /** Every way this page asks for a different listing, as edits applied to whatever the address bar says — the
    * arrangement `AdminUsersPage` uses, and for the same reason: the state is in the URL, not in a local `Var`.
    */
  private val changeBus = new EventBus[WordQuery => WordQuery]()

  private def change(edit: WordQuery => WordQuery): Unit = changeBus.emit(edit)

  /** What the search box shows: seeded from the query, never read back. The reader's keystrokes travel on
    * [[searchTypedBus]] instead — see the long note in `AdminUsersPage`, which this follows exactly.
    */
  private val searchInputVar = Var("")
  private val searchTypedBus = new EventBus[String]()

  private val newTagVar = Var("")
  private val newTagBus = new EventBus[Unit]()
  private val reloadBus = new EventBus[Unit]()
  private val tagsBus   = new EventBus[Unit]()

  /** A row the reader clicked, with what should happen to it. The stream is what the guest-minting detour hangs off. */
  private val toggleBus = new EventBus[(Long, Boolean)]()

  /** A translation chip the reader clicked: which row, which translation, and whether it was already marked. Its own
    * bus for the reason [[toggleBus]] is one — the guest detour hangs off the stream, not off the click.
    */
  private val pairBus = new EventBus[(Long, Long, Boolean)]()

  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal                   = errorVar.signal

  private val noticeVar: Var[Option[String]] = Var(None)
  private val noticeSignal                   = noticeVar.signal

  private val loadingVar    = Var(false)
  private val loadingSignal = loadingVar.signal

  private val listRequests = EventStream.merge(querySignal.updates, reloadBus.events.sample(querySignal))

  /** The term a reader searched for and the dictionary does not have — the only case where adding a word is offered.
    * `None` while a request is in flight, so the form does not flash up between keystrokes.
    */
  private val missingSignal = {
    wordsSignal
      .combineWith(querySignal, loadingSignal, signedInSignal)
      .map { case (words, query, loading, signedIn) =>
        Option.when(signedIn && !loading && words.isEmpty && query.search.trim.nonEmpty)(query.search.trim)
      }
      .distinct
  }

  private val newWordPosVar    = Var(PartOfSpeech.Noun)
  private val newWordGenderVar = Var(Option.empty[Gender])
  private val newWordBus       = new EventBus[String]()

  /** One box per language, so a word can be given both its translations at the moment it is added rather than only the
    * one the listing happens to be showing. A box for the word's own language is never rendered.
    */
  private val newWordTransVars: Map[WordLanguage, Var[String]] = {
    WordLanguage.all.map(language => language -> Var("")).toMap
  }

  /** The article of a German *translation*, which is part of that word the same way the source word's is. */
  private val newWordTransGenderVar = Var(Option.empty[Gender])

  private val searchDebounceMs = 300

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.wordsTitle)),
      Alert.maybeError(errorSignal),
      Alert.maybeInfo(noticeSignal),
      renderDirection(),
      child.maybe <-- signedInSignal.map(Option.when(_)(renderCollectBar())),
      renderSearch(),
      // Offered only when the search found nothing: the dictionary is meant to already have the word, and a permanent
      // "add a word" form next to a hundred matches would invite duplicates of words that are already there.
      child.maybe <-- missingSignal.map(term => term.map(renderAddMissing)),
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
      child.maybe <-- userSignal.map(user => Option.when(user.exists(_.isGuest))(GuestBanner.render())),
      p(cls  := "text-xs opacity-60 mt-6", I18n.t(UiKeys.wordsAttribution)),
      changeBus.events.withCurrentValueOf(querySignal).map { case (edit, current) => edit(current) } --> onQuery,
      querySignal.map(_.search).distinct --> searchInputVar.writer,
      searchTypedBus.events.debounce(searchDebounceMs).withCurrentValueOf(querySignal) -->
        Observer[(String, WordQuery)] { case (typed, current) =>
          val wanted = typed.trim
          if (wanted != current.search) {
            change(_.reset(_.copy(search = wanted)))
          }
        },
      listRequests --> Observer[WordQuery](_ => Var.set(loadingVar -> true, errorVar -> None)),
      listRequests.flatMapSwitch(load) -->
        Observer[Either[ApiError, WordPage]] {
          case Right(result) =>
            Var.set(wordsVar -> result.items, totalVar -> result.total, loadingVar -> false, errorVar -> None)
          case Left(err)     =>
            Var.set(loadingVar -> false, errorVar -> Some(err.message))
        },
      // The tag list is only fetchable with a session, and it is what the tag bar is drawn from.
      tagsBus.events.filterWith(signedInSignal).flatMapSwitch(_ => WordApiClient.listTags) -->
        Observer[Either[ApiError, List[Tag]]] {
          case Right(tags) =>
            tagsVar.set(tags)
            reconcileCollectTag(tags)
          case Left(_)     =>
            tagsVar.set(Nil)
        },
      newTagBus.events.map(_ => newTagVar.now().trim).filter(_.nonEmpty).flatMapSwitch(WordApiClient.createTag) -->
        Observer[Either[ApiError, Tag]] {
          case Right(tag) =>
            // Straight to filing under it: creating a tag is something a reader does *in order to* use it. The listing
            // is deliberately *not* narrowed to it — that is the filter's job, and one control doing both is what made
            // the two indistinguishable. Held locally as well as re-fetched, so the select has the new name at once.
            Var.set(newTagVar -> "", errorVar -> None)
            tagsVar.update(_ :+ tag)
            setCollectTag(Some(tag.id))
            tagsBus.emit(())
          case Left(err)  =>
            errorVar.set(Some(err.message))
        },
      EventStream.merge(toggleStream, pairStream) --> writeResult,
      newWordStream --> Observer[Either[ApiError, WordDetail]] {
        case Right(detail) =>
          // Straight to the word: it exists now, and whatever anybody else has already recorded about it is on that
          // screen — which is the answer to "somebody else added this word first", and where a translation in a third
          // language is added.
          newWordTransVars.values.foreach(_.set(""))
          Var.set(newWordTransGenderVar -> None, errorVar -> None)
          AppRouter.router.pushState(Page.WordDetail(detail.word.id))
        case Left(err)     =>
          errorVar.set(Some(err.message))
      },
      userSignal --> readerVar.writer,
      onMountCallback { _ =>
        reloadBus.emit(())
        tagsBus.emit(())
      },
    )
  }

  /** A per-account write, with the guest detour in front of it.
    *
    * With no session no such write can succeed, so it is preceded by minting a guest and retried against the session
    * that creates. Signed in, the mint is skipped entirely. The write is by-name because it must not be started before
    * the session exists.
    */
  private def asReader(write: () => EventStream[Either[ApiError, Unit]]): EventStream[Either[ApiError, Unit]] = {
    readerVar.now() match {
      case Some(_) =>
        write()
      case None    =>
        ApiClient.createGuest.flatMapSwitch {
          case Right(response) =>
            AppState.setUser(response.user)
            // The banner appears from here on: the reader now has an account, and nothing else has told them so.
            noticeVar.set(Some(I18n.t(UiKeys.guestBannerHint)))
            tagsBus.emit(())
            write()
          case Left(err)       =>
            EventStream.fromValue(Left(err))
        }
    }
  }

  private def toggleStream: EventStream[Either[ApiError, Unit]] = {
    toggleBus.events.flatMapSwitch { case (wordId, tagged) =>
      asReader(() => writeTag(wordId, tagged))
    }
  }

  private def pairStream: EventStream[Either[ApiError, Unit]] = {
    pairBus.events.flatMapSwitch { case (wordId, translationWordId, marked) =>
      asReader(() => writePair(wordId, translationWordId, marked))
    }
  }

  /** What both writes do when they land. A chip moves tag counts as much as a tick does — marking a translation files
    * that word under the tag too — so both refresh the listing and the tag list.
    */
  private val writeResult: Observer[Either[ApiError, Unit]] = Observer[Either[ApiError, Unit]] {
    case Right(_)  =>
      reloadBus.emit(())
      tagsBus.emit(())
    case Left(err) =>
      errorVar.set(Some(err.message))
  }

  private def setCollectTag(tagId: Option[Long]): Unit = {
    collectTagVar.set(tagId)
    WordsPage.storeCollectTag(tagId)
  }

  /** Keeps the collect tag on a tag that still exists, and chooses one for a reader who has never picked — including a
    * guest, whose first tag is minted by their first tick. A tag deleted on another device would otherwise leave every
    * tick failing against an id nobody owns.
    */
  private def reconcileCollectTag(tags: List[Tag]): Unit = {
    val kept = collectTagVar.now().filter(id => tags.exists(_.id == id)).orElse(tags.headOption.map(_.id))
    if (kept != collectTagVar.now()) {
      setCollectTag(kept)
    }
  }

  /** Puts the word under the collect tag, or under the reader's default one when they have not chosen. */
  private def writeTag(wordId: Long, tagged: Boolean): EventStream[Either[ApiError, Unit]] = {
    collectTagOrDefault.flatMapSwitch {
      case Left(err)    =>
        EventStream.fromValue(Left(err))
      case Right(tagId) =>
        if (tagged)
          WordApiClient.untagWord(wordId, tagId)
        else
          WordApiClient.tagWord(wordId, tagId)
    }
  }

  /** Marks or unmarks the translation under the collect tag — the same `collectTagOrDefault` path a tick takes, so a
    * first-ever click on a chip mints a tag the same way a first-ever tick does.
    */
  private def writePair(
    wordId: Long,
    translationWordId: Long,
    marked: Boolean,
  ): EventStream[Either[ApiError, Unit]] = {
    collectTagOrDefault.flatMapSwitch {
      case Left(err)    =>
        EventStream.fromValue(Left(err))
      case Right(tagId) =>
        if (marked)
          WordApiClient.deselectPair(wordId, tagId, translationWordId)
        else
          WordApiClient.selectPair(wordId, tagId, translationWordId)
    }
  }

  /** The tag a click files under: the collect tag, else whichever the reader already has, else a fresh one.
    *
    * Clicking without having chosen a tag has to mean something — it is the first thing a new reader does — so the page
    * creates one on their behalf rather than refusing the click.
    */
  private def collectTagOrDefault: EventStream[Either[ApiError, Long]] = {
    collectTagVar.now() match {
      case Some(id) =>
        EventStream.fromValue(Right(id))
      case None     =>
        tagsVar.now().headOption match {
          case Some(tag) =>
            EventStream.fromValue(Right(tag.id))
          case None      =>
            WordApiClient
              .createTag(WordsPage.defaultTagName)
              .map(_.map(created => {
                setCollectTag(Some(created.id))
                created.id
              }))
        }
    }
  }

  private def load(query: WordQuery): EventStream[Either[ApiError, WordPage]] = {
    WordApiClient.list(
      page = Some(query.page),
      pageSize = Some(query.pageSize),
      sort = query.sort.column,
      dir = query.sort.wire,
      search = Option(query.search).filter(_.nonEmpty),
      language = Some(query.language),
      target = Some(query.target),
      partOfSpeech = query.partOfSpeech,
      tagId = query.tagId,
      mine = Option.when(query.mine)(true),
    )
  }

  private def summaryOf(total: Long): String = {
    if (total <= 0L)
      I18n.t(UiKeys.wordsEmpty)
    else
      I18n.plural(UiKeys.wordsCount, total)
  }

  /** Which language is being read, and which one the translations are in. Two selects rather than one "de → hu"
    * control, so a reader can flip either half without the other becoming impossible.
    */
  private def renderDirection(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-end gap-3 mb-4",
      languageSelect(
        UiKeys.wordsLanguageLabel,
        querySignal.map(_.language),
        Observer[WordLanguage](language => change(_.reset(_.copy(language = language)))),
      ),
      languageSelect(
        UiKeys.wordsTargetLabel,
        targetSignal,
        Observer[WordLanguage](language => change(_.reset(_.copy(target = language)))),
      ),
      label(
        cls := "form-control",
        span(cls := "label-text text-xs", I18n.t(UiKeys.wordsPosLabel)),
        select(
          cls    := "select select-sm",
          // The option's `value` stays the wire code; only its label is translated.
          option(value := "", I18n.t(UiKeys.wordsPosAny)),
          PartOfSpeech.all.map(pos => option(value := PartOfSpeech.code(pos), Labels.partOfSpeech(pos))),
          controlled(
            value <-- querySignal.map(_.partOfSpeech.map(PartOfSpeech.code).getOrElse("")),
            onChange.mapToValue --> Observer[String] { code =>
              change(_.reset(_.copy(partOfSpeech = PartOfSpeech.fromString(code))))
            },
          ),
        ),
      ),
      child.maybe <-- signedInSignal.map(Option.when(_)(renderTagFilter())),
      child.maybe <-- signedInSignal.map(Option.when(_)(renderMineToggle())),
    )
  }

  /** What a tag `<select>` should be showing: the id, but only once the tag list holding its `<option>` has arrived.
    *
    * Emitting on the tag list as well as on the id is the whole point — a value set while its option is missing is
    * dropped by the browser, and a signal that never emits again has no way to put it back.
    */
  private def selectedTagValue(selected: Signal[Option[Long]]): Signal[String] = {
    selected.combineWithFn(tagsSignal)((id, tags) => {
      id.filter(chosen => tags.exists(_.id == chosen)).map(_.toString).getOrElse("")
    })
  }

  /** Narrows the listing to one tag, and does nothing else — in particular it does not decide where a tick files. It
    * sits among the filters rather than in the collect card for exactly that reason, offers "all tags", and is the half
    * of the old single control that stayed in the URL.
    */
  private def renderTagFilter(): HtmlElement = {
    label(
      cls := "form-control",
      span(cls := "label-text text-xs", I18n.t(UiKeys.wordsFilterTagLabel)),
      select(
        cls    := "select select-sm",
        option(value := "", I18n.t(UiKeys.wordsFilterTagAny)),
        children <-- tagsSignal.map(
          _.map(tag => option(value := tag.id.toString, s"${tag.name} (${tag.wordCount})"))
        ),
        controlled(
          // Against the tag list, not the id alone: a `<select>` cannot hold a value whose `<option>` is
          // not there yet, and the options arrive a request later than the URL does. Combining the two
          // is what re-applies the value when they land — without it, opening a bookmarked `?tag=…`
          // showed a listing that *was* narrowed under a control that said "all tags".
          value <-- selectedTagValue(filterTagSignal),
          onChange.mapToValue --> Observer[String] { raw =>
            change(_.reset(_.copy(tagId = raw.toLongOption)))
          },
        ),
      ),
    )
  }

  private def renderMineToggle(): HtmlElement = {
    label(
      cls := "label gap-2 h-12 cursor-pointer",
      input(
        typ    := "checkbox",
        cls    := "checkbox checkbox-sm",
        controlled(
          checked <-- querySignal.map(_.mine),
          onClick.mapToChecked --> Observer[Boolean](mine => change(_.reset(_.copy(mine = mine)))),
        ),
      ),
      span(cls := "label-text text-sm", I18n.t(UiKeys.wordsOnlyMine)),
    )
  }

  private def languageSelect(
    labelKey: String,
    selected: Signal[WordLanguage],
    onPick: Observer[WordLanguage],
  ): HtmlElement = {
    label(
      cls := "form-control",
      span(cls := "label-text text-xs", I18n.t(labelKey)),
      select(
        cls    := "select select-sm",
        WordLanguage.all.map(language => option(value := WordLanguage.code(language), Labels.language(language))),
        controlled(
          value <-- selected.map(WordLanguage.code),
          onChange.mapToValue --> onPick.contramap[String](code =>
            WordLanguage.fromString(code).getOrElse(WordQuery.default.language)
          ),
        ),
      ),
    )
  }

  /** Where ticks are filed, and the way to make another tag. Only rendered with a session, since a tag belongs to an
    * account.
    *
    * A card of its own, above the table and away from the filters, because it answers a different question from the tag
    * *filter*: this one says where words go, that one says which words are shown. The hint under it is what makes that
    * readable without having to try it.
    */
  private def renderCollectBar(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mb-4",
      div(
        cls := "card-body py-3 gap-2",
        div(
          cls := "flex flex-wrap items-end gap-3",
          // Absent until there is a tag to name: a reader with none has the box below and nothing to choose between.
          child.maybe <-- tagsSignal.map(tags => Option.when(tags.nonEmpty)(renderCollectSelect())),
          form(
            cls        := "flex items-end gap-2",
            noValidate := true,
            onSubmit.preventDefault.mapToUnit --> newTagBus.writer,
            label(
              cls      := "form-control",
              span(cls      := "label-text text-xs", I18n.t(UiKeys.wordsTagNew)),
              input(
                cls         := "input input-sm",
                placeholder := I18n.t(UiKeys.wordsTagNewPlaceholder),
                controlled(value <-- newTagVar.signal, onInput.mapToValue --> newTagVar.writer),
              ),
            ),
            button(cls := "btn btn-sm", typ := "submit", I18n.t(UiKeys.commonAdd)),
          ),
        ),
        p(cls := "text-xs opacity-60", I18n.t(UiKeys.wordsCollectHint)),
        p(cls := "text-xs opacity-60", I18n.t(UiKeys.wordsPairHint)),
      ),
    )
  }

  /** The collect tag itself. No "none" option: a tick has to go somewhere, so the page picks a tag rather than leaving
    * the reader to discover that the empty entry silently meant "the first one".
    */
  private def renderCollectSelect(): HtmlElement = {
    label(
      cls := "form-control",
      span(cls := "label-text text-xs font-semibold", I18n.t(UiKeys.wordsCollectLabel)),
      select(
        cls    := "select select-sm select-primary",
        children <-- tagsSignal.map(
          _.map(tag => option(value := tag.id.toString, s"${tag.name} (${tag.wordCount})"))
        ),
        controlled(
          value <-- selectedTagValue(collectTagSignal),
          onChange.mapToValue --> Observer[String](raw => setCollectTag(raw.toLongOption)),
        ),
      ),
    )
  }

  /** The languages a word in `source` can be translated into, the one being read first. Two of the three, always: a
    * word is never a translation of itself, and the server refuses that pair anyway.
    */
  private def translationLanguages(source: WordLanguage, target: WordLanguage): List[WordLanguage] = {
    (target :: WordLanguage.all).distinct.filterNot(_ == source)
  }

  /** Adds the word the search did not find, in the language being browsed, with a translation in either of the other
    * two languages — both, if the reader types both.
    *
    * The request is "ensure and attach": if somebody else has already added the word, the server answers the existing
    * one with everybody's translations on it rather than refusing. It is filed under the collect tag, since a word
    * somebody bothered to type is one they are collecting.
    */
  private def newWordStream: EventStream[Either[ApiError, WordDetail]] = {
    newWordBus.events.withCurrentValueOf(querySignal).flatMapSwitch { case (text, query) =>
      val pos          = newWordPosVar.now()
      val gender       = {
        if (query.language == WordLanguage.De && pos == PartOfSpeech.Noun)
          newWordGenderVar.now()
        else
          None
      }
      val translations = translationLanguages(query.language, query.target).flatMap(language => {
        val typed = newWordTransVars(language).now().trim
        Option.when(typed.nonEmpty)(
          NewTranslation(
            language,
            typed,
            None,
            // Only a German noun takes an article; the server drops one given for anything else.
            newWordTransGenderVar.now().filter(_ => language == WordLanguage.De),
          )
        )
      })
      collectTagOrDefault.flatMapSwitch {
        case Left(err)    =>
          EventStream.fromValue(Left(err))
        case Right(tagId) =>
          WordApiClient.create(
            CreateWordRequest(
              language = query.language,
              text = text,
              partOfSpeech = pos,
              gender = gender,
              translations = translations,
              tagIds = List(tagId),
            )
          )
      }
    }
  }

  private def renderAddMissing(term: String): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mb-4",
      div(
        cls := "card-body py-3",
        h2(cls       := "card-title text-base", I18n.t(UiKeys.wordsAddMissing, term)),
        form(
          cls        := "flex flex-wrap items-end gap-2",
          noValidate := true,
          onSubmit.preventDefault.mapTo(term) --> newWordBus.writer,
          label(
            cls      := "form-control",
            span(cls := "label-text text-xs", I18n.t(UiKeys.wordsPosLabel)),
            select(
              cls    := "select select-sm",
              PartOfSpeech.all.map(pos => option(value := PartOfSpeech.code(pos), Labels.partOfSpeech(pos))),
              controlled(
                value <-- newWordPosVar.signal.map(PartOfSpeech.code),
                onChange.mapToValue --> Observer[String] { code =>
                  newWordPosVar.set(PartOfSpeech.fromString(code).getOrElse(PartOfSpeech.Other))
                },
              ),
            ),
          ),
          // Only a German noun takes an article.
          child.maybe <--
            querySignal
              .map(_.language)
              .combineWith(newWordPosVar.signal)
              .map { case (language, pos) =>
                Option.when(language == WordLanguage.De && pos == PartOfSpeech.Noun)(
                  renderGenderSelect(newWordGenderVar)
                )
              },
          // One box per other language rather than one for whichever the listing is showing: a word typed here is
          // usually being learned in both directions, and the alternative was to add it, open it, and add the second
          // translation from the detail page.
          div(
            cls      := "grow basis-full",
            span(cls := "label-text text-xs", I18n.t(UiKeys.wordsAddTranslations)),
            div(
              cls    := "flex flex-wrap items-end gap-2",
              children <--
                querySignal
                  .map(query => translationLanguages(query.language, query.target))
                  .distinct
                  .map(_.map(renderTranslationInput)),
            ),
          ),
          button(cls := "btn btn-sm btn-primary", typ := "submit", I18n.t(UiKeys.commonAdd)),
        ),
      ),
    )
  }

  private def renderTranslationInput(language: WordLanguage): HtmlElement = {
    label(
      cls := "form-control grow",
      span(cls := "label-text text-xs", Labels.language(language)),
      div(
        cls    := "flex items-end gap-2",
        // A German translation carries its article for the same reason a German headword does: it is part of the word.
        child.maybe <--
          newWordPosVar.signal.map(pos => {
            Option.when(language == WordLanguage.De && pos == PartOfSpeech.Noun)(
              renderGenderSelect(newWordTransGenderVar)
            )
          }),
        input(
          cls         := "input input-sm w-full",
          placeholder := I18n.t(UiKeys.wordsAddTranslationHint),
          controlled(
            value <-- newWordTransVars(language).signal,
            onInput.mapToValue --> newWordTransVars(language).writer,
          ),
        ),
      ),
    )
  }

  private def renderGenderSelect(target: Var[Option[Gender]]): HtmlElement = {
    label(
      cls := "form-control",
      span(cls := "label-text text-xs", I18n.t(UiKeys.wordsAddGender)),
      select(
        cls    := "select select-sm",
        option(value := "", I18n.t(UiKeys.wordsAddGenderNone)),
        // The article itself is the value *and* the label: `der` is part of the word being learned, not copy.
        Gender.all.map(gender => option(value := Gender.article(gender), Gender.article(gender))),
        controlled(
          value <-- target.signal.map(Gender.toColumn),
          onChange.mapToValue --> Observer[String](article => target.set(Gender.fromColumn(article))),
        ),
      ),
    )
  }

  private def renderSearch(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-end gap-2 mb-4",
      label(
        cls := "form-control grow",
        span(cls      := "label-text text-xs", I18n.t(UiKeys.wordsSearchLabel)),
        input(
          cls         := "input w-full",
          typ         := "search",
          placeholder := I18n.t(UiKeys.wordsSearchPlaceholder),
          controlled(value <-- searchInputVar.signal, onInput.mapToValue --> searchInputVar.writer),
          onInput.mapToValue --> searchTypedBus.writer,
        ),
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
            // The toggle's column. Its heading is read out rather than shown: the control is a tick, and a word above
            // it would be wider than the column it labels.
            th(cls := "w-12", span(cls := "sr-only", I18n.t(UiKeys.wordsColTagged))),
            SortHeader.render(I18n.t(UiKeys.wordsColWord), WordSort.text, sortSignal, onSort),
            SortHeader.render(I18n.t(UiKeys.wordsColPos), WordSort.pos, sortSignal, onSort),
            // Translations are a list rendered into one cell, so there is no `ORDER BY` that produces them — the same
            // reason the audit trail's target column carries no sort.
            th(I18n.t(UiKeys.wordsColTranslations)),
          )
        ),
        tbody(children <-- wordsSignal.splitSeq(_.word.id)(row => renderRow(row.key, row))),
      ),
    )
  }

  /** One row. `id` comes from the split key rather than the signal, so the link is a fixed page rather than one rebuilt
    * on every emission — and the toggle knows which word it acts on without sampling anything.
    */
  private def renderRow(id: Long, row: Signal[WordSummary]): HtmlElement = {
    // The tick answers the question the button acts on — "does this carry the *collect* tag" — not what the listing is
    // narrowed to. Reading it off the filter is what made a filtered listing look like a fully-collected one.
    val taggedSignal = row
      .combineWithFn(collectTagSignal) { (summary, collect) =>
        collect match {
          case Some(tagId) =>
            summary.tagIds.contains(tagId)
          case None        =>
            summary.tagIds.nonEmpty
        }
      }
      .distinct

    val selectedSignal = row.combineWithFn(collectTagSignal)(WordsPage.selectedTranslationIds).distinct

    tr(
      cls := "hover",
      td(
        button(
          cls := "btn btn-ghost btn-xs",
          cls("text-success") <-- taggedSignal,
          typ := "button",
          aria.label <-- row.combineWithFn(taggedSignal) { (summary, tagged) =>
            val key = {
              if (tagged)
                UiKeys.wordsTagRemove
              else
                UiKeys.wordsTagAdd
            }
            I18n.t(key, Word.display(summary.word))
          },
          child.text <-- taggedSignal.map(tagged => {
            if (tagged)
              "✓"
            else
              "+"
          }),
          onClick.compose(_.sample(taggedSignal)) --> Observer[Boolean](tagged => toggleBus.emit((id, tagged))),
        )
      ),
      td(
        a(
          cls := "link link-hover font-medium",
          AppRouter.router.navigateTo(Page.WordDetail(id)),
          child.text <-- row.map(summary => Word.display(summary.word)),
        )
      ),
      td(
        cls := "text-sm opacity-70",
        child.text <-- row.map(summary => Labels.partOfSpeech(summary.word.partOfSpeech)),
      ),
      td(
        // The chips sit in a div rather than on the cell: `display:flex` on a `<td>` takes it out of the table's own
        // layout and the column stops lining up with its heading.
        div(
          cls := "flex flex-wrap gap-1",
          children <-- row
            .map(_.translations)
            .distinct
            .splitSeq(_.wordId)(option => renderTranslationChip(id, option.key, option, selectedSignal)),
        )
      ),
    )
  }

  /** One translation, as a control rather than as text.
    *
    * Clicking it says "this is the answer I want to be asked for", which also files both words under the collect tag —
    * the tick files one word, this files a pair. Marked state is shown with a tick as well as with colour, since colour
    * alone is not a difference every reader can see, and stated for a screen reader as `aria-pressed`.
    */
  private def renderTranslationChip(
    wordId: Long,
    translationWordId: Long,
    option: Signal[TranslationOption],
    selected: Signal[Set[Long]],
  ): HtmlElement = {
    val markedSignal = selected.map(_.contains(translationWordId)).distinct

    button(
      typ := "button",
      cls := "badge badge-sm cursor-pointer",
      cls("badge-primary") <-- markedSignal,
      cls("badge-ghost") <-- markedSignal.map(!_),
      aria.pressed <-- markedSignal.map(_.toString),
      aria.label <-- option.combineWithFn(markedSignal) { (translation, marked) =>
        val key = {
          if (marked)
            UiKeys.wordsPairRemove
          else
            UiKeys.wordsPairAdd
        }
        I18n.t(key, translation.text)
      },
      child.text <-- option.combineWithFn(markedSignal) { (translation, marked) =>
        if (marked)
          s"✓ ${translation.text}"
        else
          translation.text
      },
      onClick.compose(_.sample(markedSignal)) -->
        Observer[Boolean](marked => pairBus.emit((wordId, translationWordId, marked))),
    )
  }
}
