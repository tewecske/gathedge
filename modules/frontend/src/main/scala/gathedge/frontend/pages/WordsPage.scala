package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.AppRouter
import gathedge.frontend.Page
import gathedge.frontend.api.{ApiClient, ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, GuestBanner, Labels, Pagination, SortHeader}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.WordQuery
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{Gender, PartOfSpeech, Tag, User, Word, WordLanguage}
import gathedge.shared.dto.{CreateWordRequest, NewTranslation, WordDetail, WordPage, WordSort, WordSummary}
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

  private val sortSignal      = querySignal.map(_.sort).distinct
  private val pageSignal      = querySignal.map(_.page).distinct
  private val pageSizeSignal  = querySignal.map(_.pageSize).distinct
  private val targetSignal    = querySignal.map(_.target).distinct
  private val activeTagSignal = querySignal.map(_.tagId).distinct

  private val userSignal     = AppState.currentUserSignal
  private val signedInSignal = AppState.isSignedInSignal

  /** Mirrors of the two things a *click* has to read at the moment it happens: who the reader is, and which tag they
    * are filing under. Signals cannot be read outside a subscription, and a click handler is not one; these follow
    * their signals through an observer and are read with `.now()`.
    */
  private val readerVar    = Var(Option.empty[User])
  private val activeTagVar = Var(Option.empty[Long])

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
  private val newWordTransVar  = Var("")
  private val newWordBus       = new EventBus[String]()

  private val searchDebounceMs = 300

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.wordsTitle)),
      Alert.maybeError(errorSignal),
      Alert.maybeInfo(noticeSignal),
      renderDirection(),
      child.maybe <-- signedInSignal.map(Option.when(_)(renderTagBar())),
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
          case Left(_)     =>
            tagsVar.set(Nil)
        },
      newTagBus.events.map(_ => newTagVar.now().trim).filter(_.nonEmpty).flatMapSwitch(WordApiClient.createTag) -->
        Observer[Either[ApiError, Tag]] {
          case Right(tag) =>
            // Straight to filing under it: creating a tag is something a reader does *in order to* use it.
            Var.set(newTagVar -> "", errorVar -> None)
            change(_.reset(_.copy(tagId = Some(tag.id))))
            tagsBus.emit(())
          case Left(err)  =>
            errorVar.set(Some(err.message))
        },
      toggleStream --> Observer[Either[ApiError, Unit]] {
        case Right(_)  =>
          reloadBus.emit(())
          tagsBus.emit(())
        case Left(err) =>
          errorVar.set(Some(err.message))
      },
      newWordStream --> Observer[Either[ApiError, WordDetail]] {
        case Right(detail) =>
          // Straight to the word: it exists now, and whatever anybody else has already recorded about it is on that
          // screen — which is the answer to "somebody else added this word first".
          Var.set(newWordTransVar -> "", errorVar -> None)
          AppRouter.router.pushState(Page.WordDetail(detail.word.id))
        case Left(err)     =>
          errorVar.set(Some(err.message))
      },
      userSignal --> readerVar.writer,
      activeTagSignal --> activeTagVar.writer,
      onMountCallback { _ =>
        reloadBus.emit(())
        tagsBus.emit(())
      },
    )
  }

  /** A row toggle, with the guest detour in front of it.
    *
    * With no session the tag write cannot succeed, so it is preceded by minting a guest and retried against the session
    * that creates. Signed in, the mint is skipped entirely.
    */
  private def toggleStream: EventStream[Either[ApiError, Unit]] = {
    toggleBus.events.flatMapSwitch { case (wordId, tagged) =>
      readerVar.now() match {
        case Some(_) =>
          writeTag(wordId, tagged)
        case None    =>
          ApiClient.createGuest.flatMapSwitch {
            case Right(response) =>
              AppState.setUser(response.user)
              // The banner appears from here on: the reader now has an account, and nothing else has told them so.
              noticeVar.set(Some(I18n.t(UiKeys.guestBannerHint)))
              tagsBus.emit(())
              writeTag(wordId, tagged)
            case Left(err)       =>
              EventStream.fromValue(Left(err))
          }
      }
    }
  }

  /** Puts the word under the active tag, or under the reader's default one when they have not chosen. */
  private def writeTag(wordId: Long, tagged: Boolean): EventStream[Either[ApiError, Unit]] = {
    activeTagOrDefault.flatMapSwitch {
      case Left(err)    =>
        EventStream.fromValue(Left(err))
      case Right(tagId) =>
        if (tagged)
          WordApiClient.untagWord(wordId, tagId)
        else
          WordApiClient.tagWord(wordId, tagId)
    }
  }

  /** The tag a click files under: the one in the address bar, else whichever the reader already has, else a fresh one.
    *
    * Clicking without having chosen a tag has to mean something — it is the first thing a new reader does — so the page
    * creates one on their behalf rather than refusing the click.
    */
  private def activeTagOrDefault: EventStream[Either[ApiError, Long]] = {
    activeTagVar.now() match {
      case Some(id) =>
        EventStream.fromValue(Right(id))
      case None     =>
        tagsVar.now().headOption match {
          case Some(tag) =>
            EventStream.fromValue(Right(tag.id))
          case None      =>
            WordApiClient.createTag(WordsPage.defaultTagName).map(_.map(_.id))
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
      child.maybe <-- signedInSignal.map(Option.when(_)(renderMineToggle())),
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

  /** The tag being filed under, and the way to make another one. Only rendered with a session, since a tag belongs to
    * an account.
    */
  private def renderTagBar(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mb-4",
      div(
        cls := "card-body py-3 flex-row flex-wrap items-end gap-3",
        label(
          cls        := "form-control",
          span(cls := "label-text text-xs", I18n.t(UiKeys.wordsTagLabel)),
          select(
            cls    := "select select-sm",
            option(value := "", I18n.t(UiKeys.commonNone)),
            children <-- tagsSignal.map(
              _.map(tag => option(value := tag.id.toString, s"${tag.name} (${tag.wordCount})"))
            ),
            controlled(
              value <-- activeTagSignal.map(_.map(_.toString).getOrElse("")),
              onChange.mapToValue --> Observer[String] { raw =>
                change(_.reset(_.copy(tagId = raw.toLongOption)))
              },
            ),
          ),
        ),
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
    )
  }

  /** Adds the word the search did not find, in the language being browsed, with an optional first translation.
    *
    * The request is "ensure and attach": if somebody else has already added the word, the server answers the existing
    * one with everybody's translations on it rather than refusing.
    */
  private def newWordStream: EventStream[Either[ApiError, WordDetail]] = {
    newWordBus.events.withCurrentValueOf(querySignal).flatMapSwitch { case (text, query) =>
      val pos         = newWordPosVar.now()
      val gender      = {
        if (query.language == WordLanguage.De && pos == PartOfSpeech.Noun)
          newWordGenderVar.now()
        else
          None
      }
      val translation = newWordTransVar.now().trim
      WordApiClient.create(
        CreateWordRequest(
          language = query.language,
          text = text,
          partOfSpeech = pos,
          gender = gender,
          translations = Option
            .when(translation.nonEmpty)(NewTranslation(query.target, translation, None, None))
            .toList,
          tagIds = activeTagVar.now().toList,
        )
      )
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
                Option.when(language == WordLanguage.De && pos == PartOfSpeech.Noun)(renderGenderSelect())
              },
          label(
            cls      := "form-control grow",
            span(cls      := "label-text text-xs", I18n.t(UiKeys.wordsAddTranslation)),
            input(
              cls         := "input input-sm w-full",
              placeholder := I18n.t(UiKeys.wordsAddTranslationHint),
              controlled(value <-- newWordTransVar.signal, onInput.mapToValue --> newWordTransVar.writer),
            ),
          ),
          button(cls := "btn btn-sm btn-primary", typ := "submit", I18n.t(UiKeys.commonAdd)),
        ),
      ),
    )
  }

  private def renderGenderSelect(): HtmlElement = {
    label(
      cls := "form-control",
      span(cls := "label-text text-xs", I18n.t(UiKeys.wordsAddGender)),
      select(
        cls    := "select select-sm",
        option(value := "", I18n.t(UiKeys.wordsAddGenderNone)),
        // The article itself is the value *and* the label: `der` is part of the word being learned, not copy.
        Gender.all.map(gender => option(value := Gender.article(gender), Gender.article(gender))),
        controlled(
          value <-- newWordGenderVar.signal.map(Gender.toColumn),
          onChange.mapToValue --> Observer[String](article => newWordGenderVar.set(Gender.fromColumn(article))),
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
    // With a tag chosen, the tick means "carries *that* tag". With none, it means "is in my vocabulary at all", which
    // is the same question a reader who has not chosen one is asking.
    val taggedSignal = row
      .combineWithFn(activeTagSignal) { (summary, active) =>
        active match {
          case Some(tagId) =>
            summary.tagIds.contains(tagId)
          case None        =>
            summary.tagIds.nonEmpty
        }
      }
      .distinct

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
      td(child.text <-- row.map(_.translations.mkString(", "))),
    )
  }
}
