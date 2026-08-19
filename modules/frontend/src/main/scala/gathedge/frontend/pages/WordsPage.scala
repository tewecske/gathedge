package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.AppRouter
import gathedge.frontend.Page
import gathedge.frontend.api.{ApiClient, ApiError, WordApiClient}
import gathedge.frontend.components.{
  Alert,
  AppShell,
  BulkUploadDialog,
  GuestBanner,
  Labels,
  Pagination,
  SortHeader,
  WordCollect,
}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.WordQuery
import gathedge.frontend.ocr.ImageOcr
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{Gender, PartOfSpeech, Word, WordLanguage}
import gathedge.shared.dto.{CreateWordRequest, NewTranslation, TaggedPair, WordDetail, WordPage, WordSort, WordSummary}
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
  * The tick, the chips and the tag they file into are [[WordCollect]], shared with `WordDetailPage` — one collect tag
  * behind both screens. What is this page's own is the listing: the direction, the search, the filter and the page.
  *
  * Its listing state lives in the URL, like the two admin listings — see [[WordQuery]] — so this page owns none of it
  * and takes the pair `App` supplies.
  */
object WordsPage {

  /** `recognizeImage` is threaded through rather than called directly by [[BulkUploadDialog]] — see
    * [[ImageOcr.Recognize]]'s own scaladoc for why: it keeps `tesseract.js`'s import out of this page's reachable graph
    * under the test linker. `App` is the only caller that supplies the real one.
    */
  def render(
    query: Signal[WordQuery],
    onQuery: Observer[WordQuery],
    recognizeImage: ImageOcr.Recognize,
  ): HtmlElement = {
    AppShell.render(Page.Words(), new WordsPage(query, onQuery, recognizeImage).render())
  }
}

private class WordsPage(
  pageQuery: Signal[WordQuery],
  onQuery: Observer[WordQuery],
  recognizeImage: ImageOcr.Recognize,
) {

  /** `.distinct` because every reader here treats an emission as "ask the server again". */
  private val querySignal = pageQuery.distinct

  private val wordsVar    = Var(List.empty[WordSummary])
  private val wordsSignal = wordsVar.signal

  private val totalVar    = Var(0L)
  private val totalSignal = totalVar.signal

  private val sortSignal     = querySignal.map(_.sort).distinct
  private val pageSignal     = querySignal.map(_.page).distinct
  private val pageSizeSignal = querySignal.map(_.pageSize).distinct
  private val targetSignal   = querySignal.map(_.target).distinct

  /** The tag the *listing* is narrowed to. Nothing else reads it — where a tick files is the collect tag, which lives
    * in [[WordCollect]] and never in the URL.
    */
  private val filterTagSignal = querySignal.map(_.tagId).distinct

  private val userSignal     = AppState.currentUserSignal
  private val signedInSignal = AppState.isSignedInSignal

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

  private val reloadBus = new EventBus[Unit]()

  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal                   = errorVar.signal

  private val noticeVar: Var[Option[String]] = Var(None)
  private val noticeSignal                   = noticeVar.signal

  private val warningVar: Var[Option[String]] = Var(None)
  private val warningSignal                   = warningVar.signal

  private val loadingVar    = Var(false)
  private val loadingSignal = loadingVar.signal

  /** The tick, the chips, the collect tag they file into and the guest detour in front of them — shared verbatim with
    * the word page, which offers the same two writes on one word. A landed write applies what changed to this page's
    * local state without refetching.
    */
  private val collect = new WordCollect(
    onError = errorVar.writer,
    onNotice = noticeVar.writer.contramap[String](Some(_)),
    onWarning = warningVar.writer.contramap[String](Some(_)),
    onWritten = Observer[WordCollect.Change](change => wordsVar.update(_.map(applyChange(_, change)))),
  )

  private val listRequests = EventStream.merge(querySignal.updates, reloadBus.events.sample(querySignal))

  /** Uploaded words land in the dictionary and the collect tag without changing what this listing is filtered to, so a
    * re-fetch is the only way a newly tagged word already on screen shows its tick.
    */
  private val bulkUpload = new BulkUploadDialog(
    collect,
    querySignal.map(_.language).distinct,
    targetSignal,
    onUploaded = Observer[Unit](_ => reloadBus.emit(())),
    recognizeImage = recognizeImage,
  )

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
      h1(cls  := "text-2xl font-bold mb-4", I18n.t(UiKeys.wordsTitle)),
      Alert.maybeError(errorSignal),
      Alert.maybeInfo(noticeSignal),
      Alert.maybeWarning(warningSignal),
      renderDirection(),
      collect.renderBar(),
      div(cls := "mb-4", bulkUpload.renderButton()),
      bulkUpload.renderModal(),
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
      p(cls   := "text-xs opacity-60 mt-6", I18n.t(UiKeys.wordsAttribution)),
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
      onMountCallback(_ => reloadBus.emit(())),
      collect.bindings,
    )
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

  private def applyChange(row: WordSummary, change: WordCollect.Change): WordSummary = {
    change match {
      case WordCollect.TagChange(wordId, tagId, tagged) if row.word.id == wordId                     =>
        if (tagged)
          row.copy(tagIds = (row.tagIds :+ tagId).distinct)
        else {
          // Untagging removes the tag id and all pairs filed under that tag.
          row.copy(
            tagIds = row.tagIds.filterNot(_ == tagId),
            pairs = row.pairs.filterNot(_.tagId == tagId),
          )
        }
      case WordCollect.PairChange(wordId, tagId, translationWordId, marked) if row.word.id == wordId =>
        if (marked) {
          // Marking a pair also files the word under the tag.
          row.copy(
            tagIds = (row.tagIds :+ tagId).distinct,
            pairs = (row.pairs :+ TaggedPair(tagId, translationWordId)).distinct,
          )
        } else {
          // Unmarking removes just this one pair.
          row.copy(pairs = row.pairs.filterNot(p => p.tagId == tagId && p.translationWordId == translationWordId))
        }
      case _                                                                                         =>
        row
    }
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
      renderSwap(),
      languageSelect(
        UiKeys.wordsTargetLabel,
        targetSignal,
        Observer[WordLanguage](language => change(_.reset(_.copy(target = language)))),
      ),
      label(
        cls := "flex flex-col gap-1",
        span(cls := "label-text text-xs", I18n.t(UiKeys.wordsPosLabel)),
        select(
          cls    := "select select-sm w-28",
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

  /** Reads the pair the other way round, in one click.
    *
    * A single edit rather than two, because either half alone can pass through a state the reader did not ask for —
    * setting the target to what the source already is asks the server for a word translated into its own language, and
    * costs a listing request to show it. Through `reset` like every other change to the direction: the rows are
    * different rows, so the page number the reader was on means nothing against them.
    *
    * Sits between the two selects, since that is the only place it can say *which* pair it swaps. It is a button in a
    * row of labelled controls, so it hangs off the bottom edge with them (`items-end` on the container) and lines its
    * height up with `select-sm` rather than with the labels above them.
    */
  private def renderSwap(): HtmlElement = {
    span(
      // The tooltip has to be a wrapper: daisyUI's `.tooltip` is `display:inline-block`, which would undo the
      // `inline-flex` that centres a `btn`'s icon.
      cls             := "tooltip",
      dataAttr("tip") := I18n.t(UiKeys.wordsSwapLanguages),
      button(
        typ        := "button",
        cls        := "btn btn-ghost btn-sm btn-square",
        // The tooltip is a `data-` attribute drawn by CSS, so it says nothing to a screen reader; this is what does.
        aria.label := I18n.t(UiKeys.wordsSwapLanguages),
        swapMark(),
        onClick.mapToUnit --> Observer[Unit] { _ =>
          change(_.reset(query => query.copy(language = query.target, target = query.language)))
        },
      ),
    )
  }

  /** Narrows the listing to one tag, and does nothing else — in particular it does not decide where a tick files. It
    * sits among the filters rather than in the collect card for exactly that reason, offers "all tags", and is the half
    * of the old single control that stayed in the URL.
    */
  private def renderTagFilter(): HtmlElement = {
    label(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(UiKeys.wordsFilterTagLabel)),
      select(
        cls    := "select select-sm w-52",
        option(value := "", I18n.t(UiKeys.wordsFilterTagAny)),
        children <-- collect.tagsSignal.map(WordCollect.tagOptionGroups),
        controlled(
          // Against the tag list, not the id alone: a `<select>` cannot hold a value whose `<option>` is
          // not there yet, and the options arrive a request later than the URL does. Combining the two
          // is what re-applies the value when they land — without it, opening a bookmarked `?tag=…`
          // showed a listing that *was* narrowed under a control that said "all tags".
          value <-- collect.selectedTagValue(filterTagSignal),
          onChange.mapToValue --> Observer[String] { raw =>
            change(_.reset(_.copy(tagId = raw.toLongOption)))
          },
        ),
      ),
    )
  }

  private def renderMineToggle(): HtmlElement = {
    label(
      // As tall as a `select-sm`, not taller: the row is `items-end`, so a box of a different height puts its contents
      // on a different centre line from every control beside it while still lining its bottom edge up with them.
      cls := "label gap-2 h-8 cursor-pointer",
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

  /** Every `<select>` on this page carries a literal width, and that is deliberate — leaving them to size themselves
    * moves the controls beside them as the page is used. daisyUI opts a `.select` into the browser's
    * customizable-select rendering, which sizes the box to the *selected* option rather than to the widest one, so
    * picking "Hungarian" after "German" widens it; and the two tag selects would move anyway, since their option labels
    * carry a word count that grows a digit. The widths fit the longest option in both catalogs; a longer tag name
    * ellipsizes, which `.select` already does on its own.
    */
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
            WordLanguage.fromString(code).getOrElse(WordQuery.default.language)
          ),
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
      collect.collectTagOrDefault.flatMapSwitch {
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
            cls      := "flex flex-col gap-1",
            span(cls := "label-text text-xs", I18n.t(UiKeys.wordsPosLabel)),
            select(
              cls    := "select select-sm w-40",
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
      cls := "flex flex-col gap-1 grow",
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
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(UiKeys.wordsAddGender)),
      select(
        cls    := "select select-sm w-24",
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
        cls := "flex flex-col gap-1 grow",
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
    val tagIdsSignal   = row.map(_.tagIds).distinct
    val pairsSignal    = row.map(_.pairs).distinct
    val taggedSignal   = collect.taggedSignal(tagIdsSignal)
    val selectedSignal = collect.selectedSignal(pairsSignal)

    // A word being learned with no answer marked in the language this listing translates into. Only ever asked of a
    // tagged row: an untagged word has nothing marked either, and that is not a gap but a word nobody is learning.
    // The row's `pairs` only ever carry marks on translations the row is showing, so an empty set here is exactly
    // "nothing chosen in the target language" rather than "nothing chosen anywhere".
    val unpairedSignal =
      taggedSignal.combineWithFn(selectedSignal)((tagged, marked) => tagged && marked.isEmpty).distinct

    tr(
      cls := "hover",
      td(collect.renderTick(id, row.map(summary => Word.display(summary.word)), tagIdsSignal)),
      td(
        div(
          cls := "flex items-center gap-1",
          a(
            cls := "link link-hover font-medium",
            AppRouter.router.navigateTo(Page.WordDetail(id)),
            child.text <-- row.map(summary => Word.display(summary.word)),
          ),
          child.maybe <-- unpairedSignal.map(Option.when(_)(collect.renderPairWarning())),
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
            .splitSeq(_.wordId)(option => collect.renderChip(id, option.key, option.map(_.text), pairsSignal)),
        )
      ),
    )
  }

  /** The two arrows on the swap button: one running right over one running left, which is the shape a reader reads as
    * "these two change places" without a word on it.
    */
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
}
