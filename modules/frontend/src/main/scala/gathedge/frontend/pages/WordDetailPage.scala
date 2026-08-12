package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.AppRouter
import gathedge.frontend.Page
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, Labels}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{Gender, PartOfSpeech, Tag, Word, WordLanguage}
import gathedge.shared.dto.{NewTranslation, TranslationEntry, WordDetail}
import gathedge.shared.i18n.UiKeys

/** One word: what it is, what it means in the other two languages, and which of the reader's tags it carries.
  *
  * Public, like the listing — a visitor sees the word and everybody's translations, and simply has no tags of their
  * own. Adding a translation needs a session, so the form appears only with one.
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

  private val inFlightVar    = Var(false)
  private val inFlightSignal = inFlightVar.signal

  private val signedInSignal = AppState.isSignedInSignal

  private val textVar   = Var("")
  private val genderVar = Var(Option.empty[Gender])

  /** Which language the next translation is in. `None` until the word is loaded, because what it may be depends on the
    * word: a word is never a translation of itself, so its own language is not on offer.
    */
  private val languageVar = Var(Option.empty[WordLanguage])

  private val loadBus   = new EventBus[Unit]()
  private val addBus    = new EventBus[Unit]()
  private val removeBus = new EventBus[Long]()

  def render(): HtmlElement = {
    div(
      cls := "max-w-2xl mx-auto",
      a(cls := "link link-hover text-sm", AppRouter.router.navigateTo(Page.Words()), I18n.t(UiKeys.wordDetailBack)),
      Alert.maybeError(errorVar.signal),
      child.maybe <-- missingVar.signal.map(Option.when(_)(Alert.info(I18n.t(UiKeys.wordDetailNotFound)))),
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
        h1(cls := "card-title text-2xl", Word.display(detail.word)),
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
            div(cls := "flex flex-col gap-1 mt-1", group.map(renderEntry)),
        )
      }),
    )
  }

  private def renderEntry(entry: TranslationEntry): HtmlElement = {
    div(
      cls := "flex items-center gap-2",
      span(Word.display(entry.word)),
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
