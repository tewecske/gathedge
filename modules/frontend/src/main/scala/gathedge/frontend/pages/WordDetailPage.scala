package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.AppRouter
import gathedge.frontend.Page
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, GuestBanner, Labels, WordCollect}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{Gender, PartOfSpeech, Tag, Word, WordLanguage}
import gathedge.shared.dto.{NewTranslation, TaggedPair, TranslationEntry, WordDetail}
import gathedge.shared.i18n.UiKeys

/** One word: what it is, what it means in the other two languages, and which of the reader's tags it carries.
  *
  * Public, like the listing — a visitor sees the word and everybody's translations, and simply has no tags of their
  * own. Adding a translation needs a session, so the form appears only with one.
  *
  * It collects the same way the listing does, through the same [[WordCollect]] and the same collect tag: the tick
  * beside the word files it, and each translation is a chip that marks it as the answer to practise. This is the only
  * screen that shows every language at once, so it is where a translation outside the listing's target language can be
  * marked at all — and a click here files into exactly the tag a click there would.
  */
object WordDetailPage {

  def render(id: Long): HtmlElement = {
    AppShell.render(Page.WordDetail(id), new WordDetailPage(id).render())
  }
}

private class WordDetailPage(id: Long) {

  private val detailVar    = Var(Option.empty[WordDetail])
  private val detailSignal = detailVar.signal

  private val missingVar = Var(false)

  private val errorVar: Var[Option[String]] = Var(None)

  private val noticeVar: Var[Option[String]] = Var(None)

  private val inFlightVar    = Var(false)
  private val inFlightSignal = inFlightVar.signal

  private val signedInSignal = AppState.isSignedInSignal

  private val loadBus   = new EventBus[Unit]()
  private val addBus    = new EventBus[Unit]()
  private val removeBus = new EventBus[Long]()

  /** The tick, the chips and the tag they file into — the listing's, verbatim. A landed write applies what changed to
    * this page's local state without refetching — except for removing a translation, which changes the row set.
    */
  private val collect = new WordCollect(
    onError = errorVar.writer,
    onNotice = noticeVar.writer.contramap[String](Some(_)),
    onWritten = Observer[WordCollect.Change](change => applyChange(change)),
  )

  /** Derived from the page's own signal rather than from the loaded value, so that changing the collect tag in the bar
    * recolours the tick and the chips at once instead of at the next load.
    */
  private val tagIdsSignal = detailSignal.map(_.map(_.tags.map(_.id)).getOrElse(Nil)).distinct
  private val pairsSignal  = detailSignal.map(_.map(_.pairs).getOrElse(Nil)).distinct

  private val taggedSignal   = collect.taggedSignal(tagIdsSignal)
  private val selectedSignal = collect.selectedSignal(pairsSignal)

  /** A word being learned with nothing marked as its answer — the same gap the listing marks, asked across all of the
    * word's translations rather than only the ones one listing was showing.
    */
  private val unpairedSignal =
    taggedSignal.combineWithFn(selectedSignal)((tagged, marked) => tagged && marked.isEmpty).distinct

  private val textVar   = Var("")
  private val genderVar = Var(Option.empty[Gender])

  /** Which language the next translation is in. `None` until the word is loaded, because what it may be depends on the
    * word: a word is never a translation of itself, so its own language is not on offer.
    */
  private val languageVar = Var(Option.empty[WordLanguage])

  /** Mirrors the tag list so applyChange can read it when adding a tag. */
  private val tagsVar = Var(List.empty[Tag])

  def render(): HtmlElement = {
    div(
      cls := "max-w-2xl mx-auto",
      a(cls := "link link-hover text-sm", AppRouter.router.navigateTo(Page.Words()), I18n.t(UiKeys.wordDetailBack)),
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(noticeVar.signal),
      child.maybe <-- missingVar.signal.map(Option.when(_)(Alert.info(I18n.t(UiKeys.wordDetailNotFound)))),
      // Above the word for the reason it sits above the table on the listing: it says where a tick goes, and reading
      // that after clicking is reading it too late.
      collect.renderBar(),
      child.maybe <-- detailSignal.map(_.map(renderWord)),
      EventStream.unit().mergeWith(loadBus.events).flatMapSwitch(_ => WordApiClient.get(id)) -->
        Observer[Either[ApiError, WordDetail]] {
          case Right(detail) =>
            Var.set(detailVar -> Some(detail), missingVar -> false, errorVar -> None)
            keepLanguageValid(detail)
          case Left(err)     =>
            // A word that is not there is a different thing from a request that failed, and reads differently.
            if (err.status == 404)
              Var.set(missingVar -> true, errorVar -> None)
            else
              errorVar.set(Some(err.message))
        },
      collect.tagsSignal --> tagsVar.writer,
      addStream --> Observer[Either[ApiError, WordDetail]] {
        case Right(detail) =>
          // The form stays where it is and keeps its language: adding one translation is usually the first of two.
          Var.set(detailVar -> Some(detail), textVar -> "", genderVar -> None, inFlightVar -> false, errorVar -> None)
        case Left(err)     =>
          Var.set(inFlightVar -> false, errorVar -> Some(err.message))
      },
      removeBus.events.flatMapSwitch(translationId => WordApiClient.removeTranslation(id, translationId)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            loadBus.emit(())
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
      // The reader may have arrived here with no account at all: a tick or a chip mints them a guest, and this is what
      // says so afterwards, exactly as on the listing.
      child.maybe <-- AppState.currentUserSignal.map(user => Option.when(user.exists(_.isGuest))(GuestBanner.render())),
      collect.bindings,
    )
  }

  /** The languages this word can be translated into: the two that are not its own. */
  private def otherLanguages(word: Word): List[WordLanguage] = WordLanguage.all.filterNot(_ == word.language)

  /** Keeps the form's language on one the word can actually take. It starts empty, and a word whose own language the
    * reader had selected on the previous word would otherwise submit a pair the server refuses.
    */
  private def keepLanguageValid(detail: WordDetail): Unit = {
    val allowed = otherLanguages(detail.word)
    if (!languageVar.now().exists(allowed.contains)) {
      languageVar.set(allowed.headOption)
    }
  }

  private def applyChange(change: WordCollect.Change): Unit = {
    change match {
      case WordCollect.TagChange(wordId, tagId, tagged)                     =>
        detailVar.update {
          case Some(detail) if detail.word.id == wordId =>
            if (tagged) {
              // Add the tag id. Look up the tag name from the tag list; if it's not there yet (tag just created),
              // it will be added by the tagsBus refresh moments later.
              val newTag = tagsVar.now().find(_.id == tagId)
              newTag match {
                case Some(tag) =>
                  Some(detail.copy(tags = (detail.tags :+ tag).distinct))
                case None      =>
                  Some(detail) // Tag not in the list yet; tagsBus will refresh it.
              }
            } else {
              // Remove the tag and its pairs.
              Some(
                detail.copy(
                  tags = detail.tags.filterNot(_.id == tagId),
                  pairs = detail.pairs.filterNot(_.tagId == tagId),
                )
              )
            }
          case other                                    =>
            other
        }
      case WordCollect.PairChange(wordId, tagId, translationWordId, marked) =>
        detailVar.update {
          case Some(detail) if detail.word.id == wordId =>
            if (marked) {
              // Add the pair and the tag.
              val newTag = tagsVar.now().find(_.id == tagId)
              Some(
                detail.copy(
                  tags = newTag match {
                    case Some(tag) => (detail.tags :+ tag).distinct
                    case None      => detail.tags // Will be added by tagsBus.
                  },
                  pairs = (detail.pairs :+ TaggedPair(tagId, translationWordId)).distinct,
                )
              )
            } else {
              // Remove just this pair.
              Some(
                detail.copy(
                  pairs = detail.pairs.filterNot(p => p.tagId == tagId && p.translationWordId == translationWordId)
                )
              )
            }
          case other                                    =>
            other
        }
    }
  }

  private def addStream: EventStream[Either[ApiError, WordDetail]] = {
    addBus.events
      .filterWith(inFlightSignal.not)
      .map(_ => (textVar.now().trim, languageVar.now()))
      .collect { case (text, Some(language)) if text.nonEmpty => (text, language) }
      .flatMapSwitch { case (text, language) =>
        inFlightVar.set(true)
        WordApiClient.addTranslation(
          id,
          // The part of speech is left to the server, which takes the source word's: a noun translates to a noun.
          NewTranslation(language, text, None, genderVar.now().filter(_ => language == WordLanguage.De)),
        )
      }
  }

  private def renderWord(detail: WordDetail): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mt-4",
      div(
        cls := "card-body",
        div(
          cls  := "flex items-center gap-2",
          collect.renderTick(detail.word.id, Val(Word.display(detail.word)), tagIdsSignal),
          h1(cls := "card-title text-2xl", Word.display(detail.word)),
          child.maybe <-- unpairedSignal.map(Option.when(_)(collect.renderPairWarning())),
        ),
        p(
          cls  := "text-sm opacity-70",
          s"${Labels.language(detail.word.language)} · ${Labels.partOfSpeech(detail.word.partOfSpeech)}",
        ),
        h2(cls := "font-semibold mt-4", I18n.t(UiKeys.wordDetailTranslations)),
        renderTranslations(detail.word, detail.translations),
        child.maybe <-- signedInSignal.map(Option.when(_)(renderAddForm(detail.word))),
        h2(cls := "font-semibold mt-4", I18n.t(UiKeys.wordDetailTags)),
        renderTags(detail.tags),
      ),
    )
  }

  /** Grouped by language, with both other languages shown even when one of them is empty.
    *
    * The empty one is the point: a word that has a Hungarian translation and no English one used to render as a list
    * with nothing to say that English was missing, and the form below reads as "add another Hungarian one".
    */
  private def renderTranslations(word: Word, entries: List[TranslationEntry]): HtmlElement = {
    div(
      cls := "flex flex-col gap-3",
      otherLanguages(word).map(language => {
        val group = entries.filter(_.word.language == language)
        div(
          div(cls := "badge badge-ghost badge-sm", Labels.language(language)),
          if (group.isEmpty)
            p(cls   := "text-sm opacity-60 mt-1", I18n.t(UiKeys.wordDetailNoTranslations))
          else
            div(cls := "flex flex-col gap-1 mt-1", group.map(entry => renderEntry(word, entry))),
        )
      }),
    )
  }

  /** The word itself is the chip — a control, not text beside one — for the reason the listing's cell is: a click on it
    * says "this is the answer I want to be asked for", and files both words under the collect tag.
    *
    * Two edges may point at the same word (one from the dictionary, one somebody typed), which renders two chips that
    * mark and unmark together. They say the same thing about the same pair, so that is what they should do.
    */
  private def renderEntry(word: Word, entry: TranslationEntry): HtmlElement = {
    div(
      cls := "flex items-center gap-2",
      collect.renderChip(word.id, entry.word.id, Val(Word.display(entry.word)), pairsSignal),
      // Marked rather than hidden: a pair inferred through English is worth having and worth knowing about.
      span(cls := "text-xs opacity-60", Labels.translationOrigin(entry.origin)),
      if (entry.ownedByMe) {
        button(
          cls := "btn btn-ghost btn-xs",
          typ := "button",
          I18n.t(UiKeys.wordDetailRemoveTranslation),
          onClick.mapTo(entry.id) --> removeBus.writer,
        )
      } else
        emptyNode,
    )
  }

  private def renderTags(tags: List[Tag]): HtmlElement = {
    if (tags.isEmpty)
      p(cls   := "text-sm opacity-60", I18n.t(UiKeys.wordDetailNoTags))
    else
      div(cls := "flex flex-wrap gap-2", tags.map(tag => span(cls := "badge badge-primary badge-soft", tag.name)))
  }

  /** The only place a word gains a translation in a language the listing was not showing, so it names itself and its
    * language select offers both of the word's other languages rather than all three.
    */
  private def renderAddForm(word: Word): HtmlElement = {
    val languages = otherLanguages(word)

    div(
      cls := "mt-4 pt-3 border-t border-base-300",
      h2(cls       := "font-semibold", I18n.t(UiKeys.wordDetailAddTitle)),
      form(
        cls        := "flex flex-wrap items-end gap-2 mt-2",
        noValidate := true,
        onSubmit.preventDefault.mapToUnit --> addBus.writer,
        label(
          cls := "form-control",
          span(cls := "label-text text-xs", I18n.t(UiKeys.wordDetailAddLanguage)),
          select(
            cls    := "select select-sm",
            languages.map(language => option(value := WordLanguage.code(language), Labels.language(language))),
            controlled(
              value <-- languageVar.signal.map(_.map(WordLanguage.code).getOrElse("")),
              onChange.mapToValue --> Observer[String] { code =>
                languageVar.set(WordLanguage.fromString(code).filter(languages.contains).orElse(languages.headOption))
              },
            ),
          ),
        ),
        // Only a German noun takes an article, so the control appears only for one.
        child.maybe <--
          languageVar.signal.map(language => Option.when(language.contains(WordLanguage.De))(renderGender())),
        label(
          cls := "form-control grow",
          span(cls      := "label-text text-xs", I18n.t(UiKeys.wordsAddTranslation)),
          input(
            cls         := "input input-sm w-full",
            placeholder := I18n.t(UiKeys.wordsAddTranslationHint),
            controlled(value <-- textVar.signal, onInput.mapToValue --> textVar.writer),
          ),
        ),
        button(
          cls := "btn btn-sm btn-primary",
          typ := "submit",
          disabled <-- inFlightSignal,
          I18n.t(UiKeys.commonAdd),
        ),
      ),
    )
  }

  private def renderGender(): HtmlElement = {
    label(
      cls := "form-control",
      span(cls := "label-text text-xs", I18n.t(UiKeys.wordsAddGender)),
      select(
        cls    := "select select-sm",
        option(value := "", I18n.t(UiKeys.wordsAddGenderNone)),
        Gender.all.map(gender => option(value := Gender.article(gender), Gender.article(gender))),
        controlled(
          value <-- genderVar.signal.map(Gender.toColumn),
          onChange.mapToValue --> Observer[String](article => genderVar.set(Gender.fromColumn(article))),
        ),
      ),
    )
  }
}
