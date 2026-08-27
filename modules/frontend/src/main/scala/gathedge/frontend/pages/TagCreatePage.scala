package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.AppRouter
import gathedge.frontend.Page
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, Labels}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{Gender, PartOfSpeech, Word, WordLanguage}
import gathedge.shared.dto.{CreateTagWithPairsRequest, TagPairInput, TagPairWord, TranslationEntry, WordPage}
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom

/** Builds a tag as an ordered list of bilingual pairs: type a source word, pick it from the dictionary, and the target
  * input offers its known translations; pick one and the pair lands in the columns below while the loop restarts. A
  * single part-of-speech selector above the inputs applies to any word not in the dictionary, so typing a long list
  * stays fast — no per-word popup. For a German noun a der/die/das picker sits in front of the German input and the
  * picked article becomes part of the typed text, exactly like the game's answer input. The typed source stays visible
  * in its input until the pair is committed.
  */
object TagCreatePage {
  def render(): HtmlElement = AppShell.render(Page.TagCreate, new TagCreatePage().render())
}

private final case class CommittedPair(
  id: Long,
  source: TagPairWord,
  target: TagPairWord,
  sourceText: String,
  targetText: String,
  pos: PartOfSpeech,
)

/** One row of an autocomplete dropdown: a word already in the dictionary, or the typed text as a word to create. */
private sealed trait Completion
private final case class DictionaryCompletion(word: Word) extends Completion
private final case class NewCompletion(text: String) extends Completion

class TagCreatePage {

  private val nameVar       = Var("")
  private val sourceLangVar = Var(WordLanguage.En)
  private val targetLangVar = Var(WordLanguage.De)
  private val pairsVar      = Var(List.empty[CommittedPair])
  private val pairIdCounter = Var(0L)

  private val srcQueryVar     = Var("")
  private val srcResultsVar   = Var(List.empty[Word])
  private val srcHighlightVar = Var(-1)
  private val srcTypedBus     = new EventBus[String]()

  private val tgtQueryVar     = Var("")
  private val tgtKnownVar     = Var(List.empty[Word])
  private val tgtLiveVar      = Var(List.empty[Word])
  private val tgtHighlightVar = Var(-1)
  private val tgtTypedBus     = new EventBus[String]()

  private val pendingSourceVar   = Var(Option.empty[(TagPairWord, String)])
  private val sourceCommittedBus = new EventBus[TagPairWord]()

  // Part of speech for words the reader types that are not in the dictionary. One shared choice above the inputs, not
  // a per-word popup, so entering a long list stays fast. A German noun's article is picked per word in front of the
  // German input (see `renderGenderPicker`), so its gender is read out of the input, not from a second selector.
  private val posVar = Var(PartOfSpeech.Noun)

  private val showSourceGenderSignal: Signal[Boolean] = sourceLangVar.signal
    .combineWith(posVar.signal)
    .map { case (language, pos) => language == WordLanguage.De && pos == PartOfSpeech.Noun }
    .distinct

  private val showTargetGenderSignal: Signal[Boolean] = targetLangVar.signal
    .combineWith(posVar.signal)
    .map { case (language, pos) => language == WordLanguage.De && pos == PartOfSpeech.Noun }
    .distinct

  private val tgtCandidatesSignal: Signal[List[Word]] = tgtKnownVar.signal
    .combineWith(tgtLiveVar.signal, tgtQueryVar.signal, targetLangVar.signal)
    .map { case (known, live, q, language) =>
      val low          = searchQuery(language, q).toLowerCase
      val knownMatches = known.filter(w => w.text.toLowerCase.startsWith(low))
      if (knownMatches.nonEmpty) knownMatches
      else live.filter(w => w.text.toLowerCase.startsWith(low))
    }

  private val saveBus     = new EventBus[Unit]()
  private val inFlightVar = Var(false)
  private val errorVar    = Var(Option.empty[String])

  def render(): HtmlElement = {
    val srcInput = input(
      cls := "input input-sm w-full",
      typ := "text",
      onMountFocus,
      placeholder <-- sourceLangVar.signal.map(l => I18n.t(UiKeys.tagsSourcePlaceholder, Labels.language(l))),
      controlled(
        value <-- srcQueryVar.signal,
        onInput.mapToValue --> Observer[String] { text =>
          if (pendingSourceVar.now().isDefined) clearPending()
          Var.set(srcQueryVar -> text, srcHighlightVar -> -1)
          srcTypedBus.emit(text)
        },
      ),
      onKeyDown --> Observer[dom.KeyboardEvent](handleSourceKey),
    )

    val tgtInput = input(
      cls := "input input-sm w-full",
      typ := "text",
      placeholder <-- targetLangVar.signal.map(l => I18n.t(UiKeys.tagsTargetPlaceholder, Labels.language(l))),
      disabled <-- pendingSourceVar.signal.map(_.isEmpty),
      controlled(
        value <-- tgtQueryVar.signal,
        onInput.mapToValue --> Observer[String] { text =>
          Var.set(tgtQueryVar -> text, tgtHighlightVar -> -1)
          tgtTypedBus.emit(text)
        },
      ),
      onKeyDown --> Observer[dom.KeyboardEvent](handleTargetKey),
    )

    sourceInputRef = Some(srcInput.ref)
    targetInputRef = Some(tgtInput.ref)

    val srcDropdown = srcQueryVar.signal
      .combineWith(srcResultsVar.signal, sourceLangVar.signal, pendingSourceVar.signal)
      .map { case (q, results, language, pending) =>
        val search = searchQuery(language, q)
        val list   = completions(results, search)
        Option.when(pending.isEmpty && search.nonEmpty && list.nonEmpty)(
          renderCompletions(list, srcHighlightVar.signal, i => srcHighlightVar.set(i), pickSourceCompletion)
        )
      }

    val tgtDropdown = pendingSourceVar.signal
      .combineWith(tgtQueryVar.signal, tgtCandidatesSignal, targetLangVar.signal)
      .map { case (pending, q, candidates, language) =>
        val search = searchQuery(language, q)
        val list   = completions(candidates, search)
        Option.when(pending.isDefined && search.nonEmpty && list.nonEmpty)(
          renderCompletions(list, tgtHighlightVar.signal, i => tgtHighlightVar.set(i), pickTargetCompletion)
        )
      }

    val canSave = nameVar.signal
      .map(_.trim.nonEmpty)
      .combineWith(pairsVar.signal.map(_.nonEmpty))
      .map { case (a, b) => a && b }

    val disabledSignal = inFlightVar.signal
      .combineWith(canSave)
      .map { case (inFlight, save) => inFlight || !save }

    div(
      cls := "max-w-3xl mx-auto flex flex-col gap-6 p-4",
      h1(cls := "text-2xl font-bold", I18n.t(UiKeys.tagsCreate)),
      label(
        cls  := "flex flex-col gap-1",
        span(cls      := "label-text", I18n.t(UiKeys.tagsName)),
        input(
          cls         := "input input-bordered w-full max-w-sm",
          typ         := "text",
          placeholder := I18n.t(UiKeys.tagsNamePlaceholder),
          controlled(value <-- nameVar.signal, onInput.mapToValue --> nameVar.writer),
        ),
      ),
      div(
        cls  := "flex flex-wrap items-end gap-3",
        languageSelect(
          UiKeys.wordsLanguageLabel,
          sourceLangVar.signal,
          Observer[WordLanguage](l => sourceLangVar.set(l)),
        ),
        renderSwap(),
        languageSelect(UiKeys.wordsTargetLabel, targetLangVar.signal, Observer[WordLanguage](l => targetLangVar.set(l))),
      ),
      renderPosSelector(),
      div(
        cls  := "grid grid-cols-2 gap-4",
        div(
          cls := "flex flex-col gap-2",
          span(cls := "text-sm font-semibold", child.text <-- sourceLangVar.signal.map(Labels.language)),
          div(
            cls := "flex items-center gap-1",
            child.maybe <-- showSourceGenderSignal.map(
              Option.when(_)(renderGenderPicker("source-gender", srcQueryVar, () => focusSource()))
            ),
            div(cls := "relative flex-1", srcInput, child.maybe <-- srcDropdown),
          ),
        ),
        div(
          cls := "flex flex-col gap-2",
          span(cls := "text-sm font-semibold", child.text <-- targetLangVar.signal.map(Labels.language)),
          div(
            cls := "flex items-center gap-1",
            child.maybe <-- showTargetGenderSignal.map(
              Option.when(_)(renderGenderPicker("target-gender", tgtQueryVar, () => focusTarget()))
            ),
            div(cls := "relative flex-1", tgtInput, child.maybe <-- tgtDropdown),
          ),
        ),
      ),
      div(
        cls  := "flex flex-col gap-2",
        h2(cls := "text-lg font-semibold", I18n.t(UiKeys.tagsPairs)),
        child.maybe <-- pairsVar.signal.map(list => Option.when(list.nonEmpty)(renderPairsTable())),
      ),
      Alert.maybeError(errorVar.signal),
      div(
        cls  := "card-actions justify-end",
        button(
          cls := "btn btn-primary",
          typ := "button",
          disabled <-- disabledSignal,
          I18n.t(UiKeys.commonCreate),
          onClick.mapToUnit --> Observer[Unit](_ => saveBus.emit(())),
        ),
      ),
      // Source autocomplete: a live prefix search in the source language. For German the leading article is stripped
      // first; the dictionary stores the noun alone, and the search box's own pattern strips it too.
      srcTypedBus.events
        .debounce(300)
        .withCurrentValueOf(sourceLangVar, targetLangVar, posVar)
        .flatMapSwitch { case (typed, srcLang, tgtLang, pos) =>
          val search = searchQuery(srcLang, typed)
          if (search.isEmpty) EventStream.fromValue(List.empty[Word])
          else {
            WordApiClient
              .list(
                pageSize = Some(12),
                search = Some(search),
                language = Some(srcLang),
                target = Some(tgtLang),
                partOfSpeech = Some(pos),
              )
              .map(_.getOrElse(WordPage(Nil, 0L)).items.map(_.word))
          }
        } --> Observer[List[Word]] { items =>
        srcResultsVar.set(items)
        srcHighlightVar.set(if (items.nonEmpty) 0 else -1)
      },
      // Target autocomplete: a live prefix search in the target language, used when the source word has no known
      // translation (see `tgtCandidatesSignal`).
      tgtTypedBus.events
        .debounce(300)
        .withCurrentValueOf(targetLangVar, posVar)
        .flatMapSwitch { case (typed, tgtLang, pos) =>
          val search = searchQuery(tgtLang, typed)
          if (search.isEmpty) EventStream.fromValue(List.empty[Word])
          else {
            WordApiClient
              .list(pageSize = Some(12), search = Some(search), language = Some(tgtLang), partOfSpeech = Some(pos))
              .map(_.getOrElse(WordPage(Nil, 0L)).items.map(_.word))
          }
        } --> tgtLiveVar.writer,
      // When a source word is committed, offer its known translations (filtered to the target language) in the target
      // input; for a brand-new source word there are none, and the target input falls back to the live search above.
      sourceCommittedBus.events
        .flatMapSwitch {
          case TagPairWord.Existing(id) => WordApiClient.get(id).map(_.toOption.map(_.translations))
          case _                        => EventStream.fromValue(None)
        } --> Observer[Option[List[TranslationEntry]]] {
        case Some(translations) =>
          tgtKnownVar.set(translations.filter(_.word.language == targetLangVar.now()).map(_.word))
        case None               => tgtKnownVar.set(Nil)
      },
      saveBus.events
        .flatMapSwitch { _ =>
          val name         = nameVar.now().trim
          val pairRequests = pairsVar.now().map(p => TagPairInput(p.source, p.target))
          inFlightVar.set(true)
          errorVar.set(None)
          if (name.nonEmpty && pairRequests.nonEmpty)
            WordApiClient.createTagWithPairs(CreateTagWithPairsRequest(name, pairRequests))
          else EventStream.fromValue(Left(ApiError(-1, "", Map.empty[String, String])))
        } --> Observer[Either[ApiError, gathedge.shared.dto.TagResponse]] {
        case Right(_)  =>
          inFlightVar.set(false)
          AppRouter.router.pushState(Page.Words())
        case Left(err) =>
          inFlightVar.set(false)
          errorVar.set(Some(err.message))
      },
    )
  }

  // -- Source / target input behaviour --------------------------------------------------------------

  private def handleSourceKey(ev: dom.KeyboardEvent): Unit = {
    ev.key match {
      case "Enter" =>
        ev.preventDefault()
        if (pendingSourceVar.now().isEmpty) acceptSourceFromKeyboard()
      case "Tab" =>
        // With a suggestion to pick, Tab accepts it; otherwise let the focus move on.
        if (pendingSourceVar.now().isEmpty && sourceCompletions().nonEmpty) {
          ev.preventDefault()
          acceptSourceFromKeyboard()
        }
      case "ArrowDown" =>
        ev.preventDefault()
        srcHighlightVar.update(h => Math.min(h + 1, sourceCompletions().size - 1))
      case "ArrowUp" =>
        ev.preventDefault()
        srcHighlightVar.update(h => if (h <= 0) 0 else h - 1)
      case "Escape" =>
        srcResultsVar.set(Nil)
      case _ =>
        ()
    }
  }

  private def handleTargetKey(ev: dom.KeyboardEvent): Unit = {
    ev.key match {
      case "Enter" =>
        ev.preventDefault()
        acceptTargetFromKeyboard()
      case "Tab" =>
        if (targetCompletions().nonEmpty) {
          ev.preventDefault()
          acceptTargetFromKeyboard()
        }
      case "ArrowDown" =>
        ev.preventDefault()
        tgtHighlightVar.update(h => Math.min(h + 1, targetCompletions().size - 1))
      case "ArrowUp" =>
        ev.preventDefault()
        tgtHighlightVar.update(h => if (h <= 0) 0 else h - 1)
      case "Escape" =>
        tgtLiveVar.set(Nil)
      case _ =>
        ()
    }
  }

  private def currentTargetCandidates(): List[Word] = {
    val known        = tgtKnownVar.now()
    val live         = tgtLiveVar.now()
    val low          = searchQuery(targetLangVar.now(), tgtQueryVar.now()).toLowerCase
    val knownMatches = known.filter(w => w.text.toLowerCase.startsWith(low))
    if (knownMatches.nonEmpty) knownMatches
    else live.filter(w => w.text.toLowerCase.startsWith(low))
  }

  private def sourceCompletions(): List[Completion] = {
    completions(srcResultsVar.now(), searchQuery(sourceLangVar.now(), srcQueryVar.now()))
  }

  private def targetCompletions(): List[Completion] = {
    completions(currentTargetCandidates(), searchQuery(targetLangVar.now(), tgtQueryVar.now()))
  }

  /** The dropdown's rows: the dictionary words the search matched, with the exact match and direct prefix matches first
    * (the listing can surface a lemma whose inflected form matched — "hauen" for "haust" — and that is not the word the
    * reader is typing); then the typed text as a word to create when no dictionary word is exactly it. Most words are
    * already in the dictionary, which is fine — this page adds existing words to a tag.
    */
  private def completions(words: List[Word], search: String): List[Completion] = {
    val low    = search.toLowerCase
    val ranked = words.zipWithIndex.sortBy { case (word, i) =>
      val text = word.text.toLowerCase
      val rank = if (text == low) 0 else if (text.startsWith(low)) 1 else 2
      (rank, i)
    }.map(_._1)
    // The dropdown shows at most five dictionary words; the exact match is always rank 0, so it is never pushed out.
    val top    = ranked.take(5)
    val exact  = top.exists(w => w.text.equalsIgnoreCase(search))
    val newRow = if (search.nonEmpty && !exact) List[Completion](NewCompletion(search)) else Nil
    top.map(word => DictionaryCompletion(word)) ++ newRow
  }

  private def acceptSourceFromKeyboard(): Unit = {
    val search = searchQuery(sourceLangVar.now(), srcQueryVar.now())
    val list   = completions(srcResultsVar.now(), search)
    val h      = srcHighlightVar.now()
    if (list.nonEmpty) {
      val idx = if (h >= 0 && h < list.size) h else 0
      pickSourceCompletion(list(idx))
    } else if (search.nonEmpty) {
      commitNewSource()
    }
  }

  private def acceptTargetFromKeyboard(): Unit = {
    val search = searchQuery(targetLangVar.now(), tgtQueryVar.now())
    val list   = completions(currentTargetCandidates(), search)
    val h      = tgtHighlightVar.now()
    if (list.nonEmpty) {
      val idx = if (h >= 0 && h < list.size) h else 0
      pickTargetCompletion(list(idx))
    } else if (search.nonEmpty) {
      commitNewTarget()
    } else {
      // An empty target must not add a one-sided pair: go back to the source input so the reader can edit the word.
      focusSource()
    }
  }

  private def pickSourceCompletion(completion: Completion): Unit = {
    completion match {
      case DictionaryCompletion(word) => commitSource(word)
      case NewCompletion(_)           => commitNewSource()
    }
  }

  private def pickTargetCompletion(completion: Completion): Unit = {
    completion match {
      case DictionaryCompletion(word) => commitTarget(word)
      case NewCompletion(_)           => commitNewTarget()
    }
  }

  private def commitSource(word: Word): Unit = {
    val ref = TagPairWord.Existing(word.id)
    pendingSourceVar.set(Some((ref, Word.display(word))))
    // Keep the chosen word in the source input so the reader sees what will be added; only committing the pair clears
    // it. The display form keeps a German noun's article visible.
    Var.set(srcQueryVar -> Word.display(word), srcResultsVar -> Nil, srcHighlightVar -> -1)
    sourceCommittedBus.emit(ref)
    dom.window.setTimeout(() => focusTarget(), 0)
  }

  private def commitNewSource(): Unit = {
    val language = sourceLangVar.now()
    val raw      = srcQueryVar.now()
    val text     = searchQuery(language, raw)
    val gender   = genderOf(raw, language)
    val display  = displayNew(text, language, gender)
    val ref      = TagPairWord.New(language, text, posVar.now(), gender)
    pendingSourceVar.set(Some((ref, display)))
    Var.set(srcQueryVar -> display, srcResultsVar -> Nil, srcHighlightVar -> -1)
    sourceCommittedBus.emit(ref)
    dom.window.setTimeout(() => focusTarget(), 0)
  }

  private def commitTarget(word: Word): Unit = {
    commitTargetWord(TagPairWord.Existing(word.id), Word.display(word))
  }

  private def commitNewTarget(): Unit = {
    val language = targetLangVar.now()
    val raw      = tgtQueryVar.now()
    val text     = searchQuery(language, raw)
    val gender   = genderOf(raw, language)
    commitTargetWord(TagPairWord.New(language, text, posVar.now(), gender), displayNew(text, language, gender))
  }

  private def commitTargetWord(ref: TagPairWord, display: String): Unit = {
    pendingSourceVar.now() match {
      case Some((sourceRef, sourceDisplay)) =>
        val id = pairIdCounter.now()
        pairIdCounter.set(id + 1)
        pairsVar.update(list => list :+ CommittedPair(id, sourceRef, ref, sourceDisplay, display, posVar.now()))
        resetPairInputs()
        focusSource()
      case None                             =>
        ()
    }
  }

  /** A pair was committed: clear both inputs and start the next source word. */
  private def resetPairInputs(): Unit = {
    pendingSourceVar.set(None)
    Var.set(
      srcQueryVar     -> "",
      srcResultsVar   -> Nil,
      srcHighlightVar -> -1,
      tgtQueryVar     -> "",
      tgtKnownVar     -> Nil,
      tgtLiveVar      -> Nil,
      tgtHighlightVar -> -1,
    )
  }

  /** The reader edited the committed source word, so the pending target no longer matches it: drop it and start over. */
  private def clearPending(): Unit = {
    pendingSourceVar.set(None)
    Var.set(tgtQueryVar -> "", tgtKnownVar -> Nil, tgtLiveVar -> Nil, tgtHighlightVar -> -1)
  }

  /** A leading `der `/`die `/`das ` from a German input, dropped for searching and for the stored word text — the
    * dictionary stores the noun alone and keeps the article as the gender column, exactly like `GamePlayPage`'s.
    */
  private def stripArticle(text: String): String = {
    val lower = text.toLowerCase
    Gender.all.map(Gender.article).find(a => lower.startsWith(a + " ")).fold(text)(a => text.drop(a.length + 1))
  }

  /** The text to search and store for a German input: the article is not part of the word. */
  private def searchQuery(language: WordLanguage, raw: String): String = {
    val stripped = if (language == WordLanguage.De) stripArticle(raw) else raw
    stripped.trim
  }

  /** A German noun's gender, read out of the article the reader picked into the input. */
  private def genderOf(raw: String, language: WordLanguage): Option[Gender] = {
    if (language != WordLanguage.De || posVar.now() != PartOfSpeech.Noun) None
    else {
      val first = raw.trim.toLowerCase.split("\\s+").headOption.getOrElse("")
      Gender.fromString(first)
    }
  }

  private def displayNew(text: String, language: WordLanguage, gender: Option[Gender]): String = {
    if (language == WordLanguage.De && posVar.now() == PartOfSpeech.Noun)
      gender.map(g => Gender.article(g) + " " + text.capitalize).getOrElse(text)
    else text
  }

  // `focusTarget` / `focusSource` move the caret between the two inputs. The timeout lets Laminar flush the
  // `disabled` -> enabled transition on the target input before the focus call lands.
  private var sourceInputRef: Option[dom.html.Input] = None
  private var targetInputRef: Option[dom.html.Input] = None

  private def focusTarget(): Unit = targetInputRef.foreach(_.focus())
  private def focusSource(): Unit = sourceInputRef.foreach(_.focus())

  // -- Rendering helpers ----------------------------------------------------------------------------

  private def languageSelect(
    labelKey: String,
    selected: Signal[WordLanguage],
    onPick: Observer[WordLanguage],
  ): HtmlElement = {
    label(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(labelKey)),
      select(
        cls      := "select select-sm w-28",
        disabled <-- pairsVar.signal.map(_.nonEmpty),
        WordLanguage.all.map(l => option(value := WordLanguage.code(l), Labels.language(l))),
        controlled(
          value <-- selected.map(WordLanguage.code),
          onChange.mapToValue --> Observer[String](code =>
            onPick.onNext(WordLanguage.fromString(code).getOrElse(WordLanguage.En))
          ),
        ),
      ),
    )
  }

  private def renderSwap(): HtmlElement = {
    span(
      cls             := "tooltip",
      dataAttr("tip") := I18n.t(UiKeys.wordsSwapLanguages),
      button(
        typ        := "button",
        cls        := "btn btn-ghost btn-sm btn-square",
        aria.label := I18n.t(UiKeys.wordsSwapLanguages),
        disabled   <-- pairsVar.signal.map(_.nonEmpty),
        swapMark(),
        onClick.mapToUnit --> Observer[Unit] { _ =>
          // Once a pair is in the table the language pair is locked (see `languageSelect`); the disabled state also
          // blocks this handler. While a pair is half-typed, swapping only moves back to the filled input so the
          // reader can edit the word — clearing it would lose it.
          if (pendingSourceVar.now().isDefined || srcQueryVar.now().nonEmpty || tgtQueryVar.now().nonEmpty) {
            focusSource()
          } else {
            val s = sourceLangVar.now()
            val t = targetLangVar.now()
            Var.set(sourceLangVar -> t, targetLangVar -> s)
          }
        },
      ),
    )
  }

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

  /** The × shown on a committed pair's remove button. */
  private def xMark(): SvgElement = {
    svg.svg(
      svg.cls            := "h-3.5 w-3.5",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "2",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(svg.d := "M18 6 6 18"),
      svg.path(svg.d := "m6 6 12 12"),
    )
  }

  private def renderPosSelector(): HtmlElement = {
    div(
      cls := "flex items-center gap-2 flex-wrap",
      span(cls := "label-text text-xs", I18n.t(UiKeys.tagsPartOfSpeech)),
      div(
        cls := "join",
        PartOfSpeech.all.map { pos =>
          input(
            typ         := "radio",
            nameAttr    := "pos-selector",
            cls         := "btn btn-sm join-item",
            aria.label  := Labels.partOfSpeech(pos),
            checked     <-- posVar.signal.map(_ == pos),
            onClick.mapToUnit --> Observer[Unit](_ => posVar.set(pos)),
          )
        },
      ),
    )
  }

  /** A daisyUI `join` of btn-styled radio inputs for the German article, mirroring `GamePlayPage`'s `answer-gender`
    * picker: one click sets the article prefix instead of typing it. Picking one replaces any article already at the
    * front of the input and refocuses it so the reader can keep typing the word straight after it.
    */
  private def renderGenderPicker(groupName: String, textVar: Var[String], refocus: () => Unit): HtmlElement = {
    div(
      cls := "join",
      Gender.all.map { gender =>
        val article = Gender.article(gender)
        input(
          typ        := "radio",
          cls        := "join-item btn btn-xs",
          nameAttr   := groupName,
          aria.label := article,
          controlled(
            checked <-- textVar.signal.map(_.toLowerCase.startsWith(article + " ")),
            onClick.mapToUnit --> Observer[Unit] { _ =>
              textVar.set(s"$article ${stripArticle(textVar.now())}")
              refocus()
            },
          ),
        )
      },
    )
  }

  private def renderCompletions(
    list: List[Completion],
    highlight: Signal[Int],
    onHover: Int => Unit,
    onPick: Completion => Unit,
  ): HtmlElement = {
    ul(
      cls := "menu menu-sm bg-base-100 rounded-box shadow absolute z-10 w-full mt-1 max-h-60 overflow-y-auto",
      list.zipWithIndex.map { case (entry, i) =>
        li(
          a(
            // The highlight drives only this one class, not a re-render of the whole dropdown — re-creating the list
            // on every hover/arrow would replace the element under the cursor and swallow the click. `menu-active` is
            // the daisyUI menu's highlighted state.
            cls("menu-active") <-- highlight.map(_ == i),
            div(
              cls := "flex w-full items-center justify-between gap-2",
              entry match {
                case DictionaryCompletion(word) =>
                  span(Word.display(word))
                case NewCompletion(text)        =>
                  span("+ ", text)
              },
              entry match {
                case DictionaryCompletion(_) =>
                  span(cls := "badge badge-ghost badge-xs", I18n.t(UiKeys.tagsInDictionary))
                case NewCompletion(_)        =>
                  span(cls := "badge badge-primary badge-xs", I18n.t(UiKeys.tagsNewWord))
              },
            ),
            onMouseEnter.mapToUnit --> Observer[Unit](_ => onHover(i)),
            onClick.mapToUnit --> Observer[Unit](_ => onPick(entry)),
          )
        )
      },
    )
  }

  private def renderPairsTable(): HtmlElement = {
    table(
      cls := "table table-sm",
      thead(
        tr(
          th(child.text <-- sourceLangVar.signal.map(Labels.language)),
          th(child.text <-- targetLangVar.signal.map(Labels.language)),
          th(I18n.t(UiKeys.tagsPartOfSpeech)),
          th(""),
        )
      ),
      tbody(
        children <-- pairsVar.signal.splitSeq(_.id) { row =>
          tr(
            td(child.text <-- row.map(_.sourceText)),
            td(child.text <-- row.map(_.targetText)),
            td(
              select(
                cls := "select select-xs w-24",
                PartOfSpeech.all.map(pos => option(value := PartOfSpeech.code(pos), Labels.partOfSpeech(pos))),
                controlled(
                  value <-- row.map(_.pos).map(PartOfSpeech.code),
                  onChange.mapToValue --> Observer[String](code => changePairPos(row.key, code)),
                ),
              )
            ),
            td(
              button(
                typ        := "button",
                cls        := "btn btn-ghost btn-xs btn-square",
                aria.label := I18n.t(UiKeys.tagsRemovePair),
                xMark(),
                onClick.mapToUnit --> Observer[Unit](_ => pairsVar.update(list => list.filterNot(_.id == row.key))),
              )
            ),
          )
        }
      ),
    )
  }

  /** Changes a committed pair's part of speech. For a word that is still new the dropdown decides what gets created on
    * save (and drops a German noun's article when it is no longer a noun); a word already in the dictionary keeps its
    * own part of speech, so only the pair's record changes.
    */
  private def changePairPos(id: Long, code: String): Unit = {
    val pos = PartOfSpeech.fromString(code).getOrElse(PartOfSpeech.Other)
    pairsVar.update(list => list.map { pair =>
      if (pair.id != id) pair
      else {
        val source = withNewPos(pair.source, pos)
        val target = withNewPos(pair.target, pos)
        pair.copy(
          pos = pos,
          source = source.map(_._1).getOrElse(pair.source),
          target = target.map(_._1).getOrElse(pair.target),
          sourceText = source.map(_._2).getOrElse(pair.sourceText),
          targetText = target.map(_._2).getOrElse(pair.targetText),
        )
      }
    })
  }

  /** Rebuilds a not-yet-created word with a new part of speech, along with its display text (the article on a German
    * noun belongs to the noun). `None` for a word already in the dictionary — its part of speech is fixed.
    */
  private def withNewPos(ref: TagPairWord, pos: PartOfSpeech): Option[(TagPairWord, String)] = {
    ref match {
      case TagPairWord.New(language, text, _, gender) =>
        val updated = TagPairWord.New(language, text, pos, gender)
        val display =
          if (language == WordLanguage.De && pos == PartOfSpeech.Noun)
            gender.map(g => Gender.article(g) + " " + text.capitalize).getOrElse(text)
          else text
        Some((updated, display))
      case _: TagPairWord.Existing =>
        None
    }
  }
}