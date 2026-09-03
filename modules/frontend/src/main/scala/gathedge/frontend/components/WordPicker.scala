package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.api.WordApiClient
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{Gender, LanguageProfile, PartOfSpeech, Word, WordLanguage}
import gathedge.shared.dto.{TagPairWord, WordDetail, WordPage}
import org.scalajs.dom

/** One side of a bilingual pair, as an input: a live dictionary autocomplete, and — for a gendered language — an
  * [[ArticlePicker]] in front of the text field. There is '''no''' language control here; the language is a parameter
  * the parent owns, so the same widget drops into a page that already chose the pair's languages.
  *
  * The autocomplete is the one the tag pages always had: a debounced prefix search in the given language, every match
  * badged with its part of speech so `der See` reads apart from `die See`, and the typed text offered as a word to
  * create when the dictionary has no exact hit. Arrow keys move the highlight; '''only Enter or a click commits'''. Tab
  * and blur just leave the field — a reader who clicks away or tabs past has not chosen anything, so nothing is added
  * and they can still go back and fix the other side.
  *
  * '''`translateFrom`''' is the other half of the create page's behaviour: give it the id of the word on the opposite
  * side and, with nothing typed, the dropdown offers that word's known translations in this language — so the reader
  * can pick the answer straight away instead of typing it.
  *
  * '''On commit it emits exactly the word the reader chose''' — a [[TagPairWord.Existing]] for a dictionary row, a
  * [[TagPairWord.New]] carrying the language/text/part-of-speech/gender for one to create — the same contract the
  * create page's inline inputs produced. The editor turns that into an instant API call, whether it is adding a row or
  * replacing one side of an existing one.
  *
  * The dropdown is open only while the field has focus. Enter, Tab, Escape, a pick, or blur all close it.
  */
final class WordPicker(
  language: Signal[WordLanguage],
  partOfSpeech: Signal[Option[PartOfSpeech]],
  onCommit: Observer[TagPairWord],
  placeholderSignal: Signal[String],
  translateFrom: Signal[Option[Long]] = Val(None),
  onCommitWord: Observer[Option[Word]] = Observer.empty[Option[Word]],
) {

  private val queryVar                         = Var("")
  private val resultsVar                       = Var(List.empty[Word])
  private val highlightVar                     = Var(-1)
  private val openVar                          = Var(false)
  private val typedBus                         = new EventBus[String]()
  private val posMirror                        = Var(Option.empty[PartOfSpeech])
  private val langMirror                       = Var(WordLanguage.En)
  private val suggestionsVar                   = Var(List.empty[Word])
  private var inputRef: Option[dom.html.Input] = None

  def setText(text: String): Unit = queryVar.set(text)
  def clear(): Unit               = { queryVar.set(""); resultsVar.set(Nil); highlightVar.set(-1); openVar.set(false) }
  def focus(): Unit               = inputRef.foreach(_.focus())

  private sealed trait Completion
  private final case class DictionaryCompletion(word: Word) extends Completion
  private final case class NewCompletion(text: String)      extends Completion

  private val maxRows = 5

  private def bare(lang: WordLanguage, raw: String): String             = LanguageProfile.of(lang).strip(raw)._1.trim
  private def genderOf(lang: WordLanguage, raw: String): Option[Gender] = LanguageProfile.of(lang).strip(raw)._2

  private def completions(words: List[Word], search: String): List[Completion] = {
    val low    = search.toLowerCase
    val ranked = words.zipWithIndex
      .sortBy { case (word, i) =>
        val text = word.text.toLowerCase
        val rank = if (text == low) 0 else if (text.startsWith(low)) 1 else 2
        (rank, i)
      }
      .map(_._1)
      .take(maxRows)
    val exact  = ranked.exists(_.text.equalsIgnoreCase(search))
    val newRow = if (search.nonEmpty && !exact) List[Completion](NewCompletion(search)) else Nil
    ranked.map(DictionaryCompletion.apply) ++ newRow
  }

  private val optionsSignal: Signal[List[Completion]] = {
    queryVar.signal
      .combineWith(resultsVar.signal, suggestionsVar.signal, language)
      .map { case (raw, results, suggestions, lang) =>
        val search = bare(lang, raw)
        if (search.isEmpty) suggestions.map(DictionaryCompletion.apply)
        else completions(results, search)
      }
  }

  private def currentOptions(): List[Completion] = {
    val raw    = queryVar.now()
    val search = bare(langMirror.now(), raw)
    if (search.isEmpty) suggestionsVar.now().map(DictionaryCompletion.apply)
    else completions(resultsVar.now(), search)
  }

  private def commit(entry: Completion): Unit = {
    val lang = langMirror.now()
    openVar.set(false)
    resultsVar.set(Nil)
    entry match {
      case DictionaryCompletion(word) =>
        onCommit.onNext(TagPairWord.Existing(word.id))
        // The whole word, so the parent can read the part of speech a dictionary pick settles — the `ref` alone carries
        // only an id.
        onCommitWord.onNext(Some(word))
      case NewCompletion(text)        =>
        val raw    = queryVar.now()
        val gender = genderOf(lang, raw)
        val pos    = posMirror.now().getOrElse(if (gender.isDefined) PartOfSpeech.Noun else PartOfSpeech.Other)
        onCommit.onNext(TagPairWord.New(lang, bare(lang, text), pos, gender))
        onCommitWord.onNext(None)
    }
  }

  private def acceptFromKeyboard(): Unit = {
    val list = currentOptions()
    val h    = highlightVar.now()
    if (list.nonEmpty) commit(list(if (h >= 0 && h < list.size) h else 0))
    else if (bare(langMirror.now(), queryVar.now()).nonEmpty)
      commit(NewCompletion(bare(langMirror.now(), queryVar.now())))
  }

  private def handleKey(ev: dom.KeyboardEvent): Unit = {
    ev.key match {
      case "Enter"     =>
        ev.preventDefault()
        acceptFromKeyboard()
        openVar.set(false)
      case "Tab"       =>
        // Leaving the field is not choosing anything — let focus move on, just close the dropdown.
        openVar.set(false)
      case "ArrowDown" =>
        ev.preventDefault()
        openVar.set(true)
        highlightVar.update(h => Math.min(h + 1, currentOptions().size - 1))
      case "ArrowUp"   =>
        ev.preventDefault()
        highlightVar.update(h => if (h <= 0) 0 else h - 1)
      case "Escape"    =>
        resultsVar.set(Nil)
        openVar.set(false)
      case _           =>
        ()
    }
  }

  def render(): HtmlElement = {
    val field = input(
      cls := "input input-sm w-full",
      typ := "text",
      placeholder <-- placeholderSignal,
      controlled(
        value <-- queryVar.signal,
        onInput.mapToValue --> Observer[String] { text =>
          Var.set(queryVar -> text, highlightVar -> -1)
          openVar.set(true)
          typedBus.emit(text)
        },
      ),
      onFocus.mapToUnit --> Observer[Unit](_ => openVar.set(true)),
      // A click on a dropdown row blurs the input before its own `onClick` fires, so closing is deferred a beat.
      onBlur.mapToUnit --> Observer[Unit](_ => dom.window.setTimeout(() => openVar.set(false), 120)),
      onKeyDown --> Observer[dom.KeyboardEvent](handleKey),
      onMountCallback(ctx => inputRef = Some(ctx.thisNode.ref.asInstanceOf[dom.html.Input])),
    )

    div(
      cls := "flex flex-col gap-1",
      language --> langMirror.writer,
      partOfSpeech --> posMirror.writer,
      // The opposite word's known translations in this language, offered as the no-typing dropdown.
      translateFrom.updates.flatMapSwitch {
        case Some(id) => WordApiClient.get(id).map(_.toOption)
        case None     => EventStream.fromValue(Option.empty[WordDetail])
      } --> Observer[Option[WordDetail]] { detail =>
        val lang        = langMirror.now()
        val suggestions =
          detail.map(_.translations.filter(_.word.language == lang).map(_.word).take(maxRows)).getOrElse(Nil)
        suggestionsVar.set(suggestions)
        // Preselect the first translation, so a reader who just wants it presses Enter once — the old create page did
        // this the moment the source word was committed.
        if (suggestions.nonEmpty && bare(lang, queryVar.now()).isEmpty) highlightVar.set(0)
      },
      div(
        cls := "flex items-center gap-1",
        child.maybe <-- language.map { lang =>
          Option.when(LanguageProfile.of(lang).hasGenders)(
            ArticlePicker.render(s"wp-${lang}-article", LanguageProfile.of(lang), queryVar, () => focus())
          )
        },
        div(cls := "relative flex-1", field, child.maybe <-- dropdown()),
      ),
      // Debounced live prefix search in the given language, narrowed to the part of speech when one is known.
      typedBus.events
        .debounce(250)
        .withCurrentValueOf(language, partOfSpeech)
        .flatMapSwitch { case (typed, lang, pos) =>
          val search = bare(lang, typed)
          if (search.isEmpty) EventStream.fromValue(List.empty[Word])
          else {
            WordApiClient
              .list(pageSize = Some(12), search = Some(search), language = Some(lang), partOfSpeech = pos)
              .map(_.getOrElse(WordPage(Nil, 0L)).items.map(_.word))
          }
        } --> Observer[List[Word]] { items =>
        resultsVar.set(items)
        highlightVar.set(if (items.nonEmpty) 0 else -1)
      },
    )
  }

  private def dropdown(): Signal[Option[HtmlElement]] = {
    optionsSignal.combineWith(openVar.signal).map { case (list, open) =>
      Option.when(open && list.nonEmpty)(
        ul(
          cls := "menu menu-sm bg-base-100 rounded-box shadow absolute z-10 w-full mt-1 max-h-60 overflow-y-auto",
          list.zipWithIndex.map { case (entry, i) =>
            li(
              a(
                cls("menu-active") <-- highlightVar.signal.map(_ == i),
                div(
                  cls := "flex w-full items-center justify-between gap-2",
                  entry match {
                    case DictionaryCompletion(word) => span(Word.display(word))
                    case NewCompletion(text)        => span("+ ", text)
                  },
                  entry match {
                    case DictionaryCompletion(word) =>
                      span(cls := "badge badge-ghost badge-xs", Labels.partOfSpeech(word.partOfSpeech))
                    case NewCompletion(_)           =>
                      span(cls := "badge badge-primary badge-xs", I18n.t(gathedge.shared.i18n.UiKeys.tagsNewWord))
                  },
                ),
                onMouseEnter.mapToUnit --> Observer[Unit](_ => highlightVar.set(i)),
                onClick.mapToUnit --> Observer[Unit](_ => commit(entry)),
              )
            )
          },
        )
      )
    }
  }
}
