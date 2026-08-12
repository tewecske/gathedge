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

  private val textVar     = Var("")
  private val languageVar = Var(WordLanguage.Hu)
  private val genderVar   = Var(Option.empty[Gender])

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
          case Left(err)     =>
            // A word that is not there is a different thing from a request that failed, and reads differently.
            if (err.status == 404)
              Var.set(missingVar -> true, errorVar -> None)
            else
              errorVar.set(Some(err.message))
        },
      addStream --> Observer[Either[ApiError, WordDetail]] {
        case Right(detail) =>
          Var.set(detailVar -> Some(detail), textVar -> "", inFlightVar -> false, errorVar -> None)
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

  private def addStream: EventStream[Either[ApiError, WordDetail]] = {
    addBus.events
      .filterWith(inFlightSignal.not)
      .map(_ => textVar.now().trim)
      .filter(_.nonEmpty)
      .flatMapSwitch { text =>
        inFlightVar.set(true)
        WordApiClient.addTranslation(
          id,
          // The part of speech is left to the server, which takes the source word's: a noun translates to a noun.
          NewTranslation(languageVar.now(), text, None, genderVar.now()),
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
        renderTranslations(detail.translations),
        h2(cls := "font-semibold mt-4", I18n.t(UiKeys.wordDetailTags)),
        renderTags(detail.tags),
        child.maybe <-- signedInSignal.map(Option.when(_)(renderAddForm())),
      ),
    )
  }

  private def renderTranslations(entries: List[TranslationEntry]): HtmlElement = {
    if (entries.isEmpty)
      p(cls := "text-sm opacity-60", I18n.t(UiKeys.wordDetailNoTranslations))
    else {
      div(
        cls := "flex flex-col gap-1",
        entries.map { entry =>
          div(
            cls := "flex items-center gap-2",
            span(cls := "badge badge-ghost badge-sm", Labels.language(entry.word.language)),
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
        },
      )
    }
  }

  private def renderTags(tags: List[Tag]): HtmlElement = {
    if (tags.isEmpty)
      p(cls   := "text-sm opacity-60", I18n.t(UiKeys.wordDetailNoTags))
    else
      div(cls := "flex flex-wrap gap-2", tags.map(tag => span(cls := "badge badge-primary badge-soft", tag.name)))
  }

  private def renderAddForm(): HtmlElement = {
    form(
      cls        := "flex flex-wrap items-end gap-2 mt-4",
      noValidate := true,
      onSubmit.preventDefault.mapToUnit --> addBus.writer,
      label(
        cls         := "form-control",
        span(cls := "label-text text-xs", I18n.t(UiKeys.wordsLanguageLabel)),
        select(
          cls    := "select select-sm",
          WordLanguage.all.map(language => option(value := WordLanguage.code(language), Labels.language(language))),
          controlled(
            value <-- languageVar.signal.map(WordLanguage.code),
            onChange.mapToValue --> Observer[String] { code =>
              languageVar.set(WordLanguage.fromString(code).getOrElse(WordLanguage.Hu))
            },
          ),
        ),
      ),
      input(
        cls         := "input input-sm",
        placeholder := I18n.t(UiKeys.wordsAddTranslationHint),
        controlled(value <-- textVar.signal, onInput.mapToValue --> textVar.writer),
      ),
      // Only a German noun takes an article, so the control appears only for one.
      child.maybe <-- languageVar.signal.map(language => Option.when(language == WordLanguage.De)(renderGender())),
      button(
        cls         := "btn btn-sm btn-primary",
        typ         := "submit",
        disabled <-- inFlightSignal,
        I18n.t(UiKeys.wordsAddTranslation),
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
