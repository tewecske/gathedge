package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.AppRouter
import gathedge.frontend.Page
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, ArticlePicker, Labels, WordCollect}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.WordQuery
import gathedge.shared.domain.{Gender, LanguageProfile, PartOfSpeech, Word, WordLanguage}
import gathedge.shared.dto.{CreateTagWithPairsRequest, TagPairInput, TagPairWord, TranslationEntry, WordPage}
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom

/** Builds a tag as an ordered list of bilingual pairs, starting from the source word and nothing else.
  *
  * The source input is the whole of the entry: type, and every dictionary word that matches comes back, each row badged
  * with its part of speech — which is what tells `der See` from `die See`, and `laufen` the verb from `Laufen` the
  * noun. '''Picking a word sets the pair's part of speech''' rather than being filtered by one, so the reader never has
  * to answer a grammar question before they have typed anything.
  *
  * The chosen word's known translations then appear under the target input as chips, the reader's caret already in that
  * input. Arrow keys move over them, Enter or Tab takes one. Typing a letter instead narrows to a live dictionary
  * search in the target language — there is no mode to leave, because focus never went anywhere.
  *
  * A word the dictionary does not have is offered as the last completion. Committing one as the source raises an inline
  * part-of-speech select beside the input, since nothing else can say what it is; a new target word inherits the
  * pair's, and the pairs table below corrects either afterwards.
  *
  * For a gendered-language input an article picker sits in front of it (der/die/das for German, el/la for Spanish) and
  * the picked article becomes part of the typed text, exactly like the game's answer input. An article means a gender,
  * and a gender means a noun.
  */
object TagCreatePage {
  def render(): HtmlElement = AppShell.render(Page.TagCreate, new TagCreatePage().render())

  // `child.maybe <--` can briefly hold the outgoing and incoming chip row in the DOM at once, and `aria-controls` on
  // the input has to resolve to exactly one of them. Same reason `Popover.nextIds` exists.
  private var instanceCounter = 0

  private def nextListboxId(): String = {
    instanceCounter += 1
    s"tag-create-options-$instanceCounter"
  }

  /** The listing a saved tag lands on: narrowed to the tag itself and pointed the way it was written, so the reader
    * sees the words they just entered rather than the whole dictionary.
    *
    * All three narrowings matter and none of the rest do — a page number or a search term carried over from anywhere
    * would only hide part of what was just built. It is also deliberately not [[WordQuery.default]]: `WordsPage`
    * restores a remembered filter over a *bare* arrival only, and a tag id is what keeps this one from being bare.
    */
  def landingQuery(source: WordLanguage, target: WordLanguage, tagId: Long): WordQuery = {
    WordQuery(language = source, target = target, tagId = Some(tagId))
  }
}

private final case class CommittedPair(
  id: Long,
  source: TagPairWord,
  target: TagPairWord,
  sourceText: String,
  targetText: String,
  pos: PartOfSpeech,
)

/** The source word of the pair being typed: what will be written, what the input shows, and the part of speech it
  * settled — from the dictionary for an [[TagPairWord.Existing]] word, from the inline select for a new one. The part
  * of speech is held here rather than read back off `ref` because an `Existing` ref carries only an id.
  */
private final case class PendingSource(ref: TagPairWord, display: String, pos: PartOfSpeech)

/** One row of an autocomplete dropdown: a word already in the dictionary, or the typed text as a word to create. */
private sealed trait Completion
private final case class DictionaryCompletion(word: Word) extends Completion
private final case class NewCompletion(text: String)      extends Completion

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

  private val pendingSourceVar   = Var(Option.empty[PendingSource])
  private val sourceCommittedBus = new EventBus[TagPairWord]()

  private val targetListboxId = TagCreatePage.nextListboxId()

  /** The gendered-language article picker in front of the source input, offered only while no source word is committed
    * and the source language actually has genders. Once a word is committed, the input holds that word's own display
    * text and an article clicked here would change what is shown without changing what will be written.
    * `Some(language)` rather than a bare `Boolean` so the render site knows which language's articles to offer.
    */
  private val showSourceGenderSignal: Signal[Option[WordLanguage]] = sourceLangVar.signal
    .combineWith(pendingSourceVar.signal)
    .map { case (language, pending) =>
      Option.when(LanguageProfile.of(language).hasGenders && pending.isEmpty)(language)
    }
    .distinct

  private val showTargetGenderSignal: Signal[Option[WordLanguage]] = targetLangVar.signal
    .map(language => Option.when(LanguageProfile.of(language).hasGenders)(language))
    .distinct

  /** Present exactly when the committed source is a word the dictionary does not have — the one case where nothing but
    * the reader can say what part of speech it is.
    */
  private val pendingPosSignal: Signal[Option[PartOfSpeech]] = pendingSourceVar.signal.map {
    case Some(PendingSource(_: TagPairWord.New, _, pos)) => Some(pos)
    case _                                               => None
  }.distinct

  private val tgtCandidatesSignal: Signal[List[Word]] = tgtKnownVar.signal
    .combineWith(tgtLiveVar.signal, tgtQueryVar.signal, targetLangVar.signal)
    .map { case (known, live, q, language) =>
      val low          = searchQuery(language, q).toLowerCase
      val knownMatches = known.filter(w => w.text.toLowerCase.startsWith(low))
      if (knownMatches.nonEmpty) knownMatches
      else live.filter(w => w.text.toLowerCase.startsWith(low))
    }

  /** What the chip row is showing. Unlike the source dropdown this does '''not''' wait for something to be typed: an
    * empty target query with a source committed is the moment the known translations are the whole point.
    */
  private val tgtOptionsSignal: Signal[List[Completion]] = pendingSourceVar.signal
    .combineWith(tgtQueryVar.signal, tgtCandidatesSignal, targetLangVar.signal)
    .map { case (pending, q, candidates, language) =>
      if (pending.isEmpty) Nil
      else completions(candidates, searchQuery(language, q))
    }

  private val saveBus     = new EventBus[Unit]()
  private val inFlightVar = Var(false)
  private val errorVar    = Var(Option.empty[String])

  /** Why the pair the reader just completed did not appear. A warning rather than an error: nothing failed, and the
    * pair they wanted is already where they wanted it.
    */
  private val noticeVar = Var(Option.empty[String])

  def render(): HtmlElement = {
    val srcInput = input(
      cls := "input input-sm w-full",
      typ := "text",
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
      cls           := "input input-sm w-full",
      typ           := "text",
      role          := "combobox",
      aria.controls := targetListboxId,
      aria.expanded <-- tgtOptionsSignal.map(_.nonEmpty),
      // Names the highlighted chip for a screen reader without moving focus out of the input, which is what lets the
      // arrow keys walk the chips while every keystroke still lands in the text field.
      aria.activeDescendant <-- tgtHighlightVar.signal.map(h => if (h >= 0) chipId(h) else ""),
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

    // The tag has to be called something, and naming it is the one thing the reader does once rather than per pair —
    // so the caret starts here, and Enter hands it to the source/target loop it never leaves again. Tab is left alone:
    // what follows the name is the language pair, which is the other thing decided once and before any typing.
    val nameInput = input(
      cls         := "input input-bordered w-full max-w-sm",
      typ         := "text",
      onMountFocus,
      placeholder := I18n.t(UiKeys.tagsNamePlaceholder),
      controlled(value <-- nameVar.signal, onInput.mapToValue --> nameVar.writer),
      onKeyDown --> Observer[dom.KeyboardEvent] { ev =>
        if (ev.key == "Enter") {
          ev.preventDefault()
          focusSource()
        }
      },
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

    val tgtChips = tgtOptionsSignal.map(list => Option.when(list.nonEmpty)(renderChipRow(list)))

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
        span(cls := "label-text", I18n.t(UiKeys.tagsName)),
        nameInput,
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
      div(
        cls  := "grid grid-cols-2 gap-4 items-start",
        div(
          cls := "flex flex-col gap-2",
          span(cls := "text-sm font-semibold", child.text <-- sourceLangVar.signal.map(Labels.language)),
          div(
            cls    := "flex items-center gap-1",
            child.maybe <-- showSourceGenderSignal.map(
              _.map(language =>
                ArticlePicker.render("source-gender", LanguageProfile.of(language), srcQueryVar, () => focusSource())
              )
            ),
            div(cls := "relative flex-1", srcInput, child.maybe <-- srcDropdown),
          ),
          child.maybe <-- pendingPosSignal.map(_.isDefined).distinct.map(Option.when(_)(renderPendingPosSelect())),
        ),
        div(
          cls := "flex flex-col gap-2",
          span(cls := "text-sm font-semibold", child.text <-- targetLangVar.signal.map(Labels.language)),
          div(
            cls    := "flex items-center gap-1",
            child.maybe <-- showTargetGenderSignal.map(
              _.map(language =>
                ArticlePicker.render("target-gender", LanguageProfile.of(language), tgtQueryVar, () => focusTarget())
              )
            ),
            div(cls := "flex-1", tgtInput),
          ),
          child.maybe <-- tgtChips,
        ),
      ),
      div(
        cls  := "flex flex-col gap-2",
        h2(cls := "text-lg font-semibold", I18n.t(UiKeys.tagsPairs)),
        child.maybe <-- pairsVar.signal.map(list => Option.when(list.nonEmpty)(renderPairsTable())),
      ),
      Alert.maybeWarning(noticeVar.signal),
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
      // Source autocomplete: a live prefix search in the source language, deliberately *not* narrowed by a part of
      // speech — picking a word is what settles that, so filtering by it first would hide the very rows the badge is
      // there to tell apart. For German the leading article is stripped: the dictionary stores the noun alone.
      srcTypedBus.events
        .debounce(300)
        .withCurrentValueOf(sourceLangVar, targetLangVar)
        .flatMapSwitch { case (typed, srcLang, tgtLang) =>
          val search = searchQuery(srcLang, typed)
          if (search.isEmpty) EventStream.fromValue(List.empty[Word])
          else {
            WordApiClient
              .list(pageSize = Some(12), search = Some(search), language = Some(srcLang), target = Some(tgtLang))
              .map(_.getOrElse(WordPage(Nil, 0L)).items.map(_.word))
          }
        } --> Observer[List[Word]] { items =>
        srcResultsVar.set(items)
        srcHighlightVar.set(if (items.nonEmpty) 0 else -1)
      },
      // Target autocomplete: a live prefix search in the target language, narrowed to the part of speech the source
      // word settled. Strict, with no unfiltered retry — a translation the dictionary filed under another part of
      // speech is added through the `+ new word` chip instead.
      tgtTypedBus.events
        .debounce(300)
        .withCurrentValueOf(targetLangVar, pendingSourceVar)
        .flatMapSwitch { case (typed, tgtLang, pending) =>
          val search = searchQuery(tgtLang, typed)
          if (search.isEmpty) EventStream.fromValue(List.empty[Word])
          else {
            WordApiClient
              .list(
                pageSize = Some(12),
                search = Some(search),
                language = Some(tgtLang),
                partOfSpeech = pending.map(_.pos),
              )
              .map(_.getOrElse(WordPage(Nil, 0L)).items.map(_.word))
          }
        } --> tgtLiveVar.writer,
      // When a source word is committed, offer its known translations as the chip row. The listing's own translations
      // are capped at three by `WordService.translationsPerRow`, so the detail request is what can answer "the top
      // five". A brand-new source word has none, and the target input falls back to the live search above.
      sourceCommittedBus.events
        .flatMapSwitch {
          case TagPairWord.Existing(id) => WordApiClient.get(id).map(_.toOption.map(_.translations))
          case _                        => EventStream.fromValue(None)
        } --> Observer[Option[List[TranslationEntry]]] { entries =>
        val known = entries
          .getOrElse(Nil)
          .filter(_.word.language == targetLangVar.now())
          .map(_.word)
          .take(translationChips)
        Var.set(tgtKnownVar -> known, tgtHighlightVar -> (if (known.nonEmpty) 0 else -1))
      },
      saveBus.events
        .flatMapSwitch { _ =>
          val name         = nameVar.now().trim
          val pairRequests = pairsVar.now().map(p => TagPairInput(p.source, p.target))
          inFlightVar.set(true)
          Var.set(errorVar -> None, noticeVar -> None)
          if (name.nonEmpty && pairRequests.nonEmpty)
            WordApiClient.createTagWithPairs(CreateTagWithPairsRequest(name, pairRequests))
          else EventStream.fromValue(Left(ApiError(-1, "", Map.empty[String, String])))
        } --> Observer[Either[ApiError, gathedge.shared.dto.TagResponse]] {
        case Right(response) =>
          inFlightVar.set(false)
          // Where a tick files next is the tag that was just built, and it has to be stored before the navigation:
          // `WordsPage`'s `WordCollect` reads `storedCollectTag` once, as it is constructed.
          WordCollect.storeCollectTag(Some(response.tag.id))
          AppRouter.router.pushState(
            Page.Words(TagCreatePage.landingQuery(sourceLangVar.now(), targetLangVar.now(), response.tag.id))
          )
        case Left(err)       =>
          inFlightVar.set(false)
          errorVar.set(Some(err.message))
      },
    )
  }

  // -- Source / target input behaviour --------------------------------------------------------------

  /** How many of a source word's translations the chip row offers. */
  private val translationChips = 5

  private def chipId(index: Int): String = s"$targetListboxId-$index"

  private def handleSourceKey(ev: dom.KeyboardEvent): Unit = {
    ev.key match {
      case "Enter"     =>
        ev.preventDefault()
        if (pendingSourceVar.now().isEmpty) acceptSourceFromKeyboard()
      case "Tab"       =>
        // With a suggestion to pick, Tab accepts it; otherwise let the focus move on.
        if (pendingSourceVar.now().isEmpty && sourceCompletions().nonEmpty) {
          ev.preventDefault()
          acceptSourceFromKeyboard()
        }
      case "ArrowDown" =>
        ev.preventDefault()
        srcHighlightVar.update(h => Math.min(h + 1, sourceCompletions().size - 1))
      case "ArrowUp"   =>
        ev.preventDefault()
        srcHighlightVar.update(h => if (h <= 0) 0 else h - 1)
      case "Escape"    =>
        srcResultsVar.set(Nil)
      case _           =>
        ()
    }
  }

  /** The chip row is horizontal but the source dropdown above it is vertical, so both axes move the highlight rather
    * than making the reader remember which control they are in.
    *
    * Escape empties the candidates themselves rather than setting a "dismissed" flag beside them: that is what leaves
    * `targetCompletions()` genuinely empty, so the next Tab falls through to the browser and moves focus out of the
    * field. Typing again re-runs the live search.
    */
  private def handleTargetKey(ev: dom.KeyboardEvent): Unit = {
    ev.key match {
      case "Enter"                    =>
        ev.preventDefault()
        acceptTargetFromKeyboard()
      case "Tab"                      =>
        if (targetCompletions().nonEmpty) {
          ev.preventDefault()
          acceptTargetFromKeyboard()
        }
      case "ArrowDown" | "ArrowRight" =>
        ev.preventDefault()
        tgtHighlightVar.update(h => Math.min(h + 1, targetCompletions().size - 1))
      case "ArrowUp" | "ArrowLeft"    =>
        ev.preventDefault()
        tgtHighlightVar.update(h => if (h <= 0) 0 else h - 1)
      case "Escape"                   =>
        Var.set(tgtKnownVar -> Nil, tgtLiveVar -> Nil, tgtHighlightVar -> -1)
      case _                          =>
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
    if (pendingSourceVar.now().isEmpty) Nil
    else completions(currentTargetCandidates(), searchQuery(targetLangVar.now(), tgtQueryVar.now()))
  }

  /** The rows on offer: the dictionary words the search matched, with the exact match and direct prefix matches first
    * (the listing can surface a lemma whose inflected form matched — "hauen" for "haust" — and that is not the word the
    * reader is typing); then the typed text as a word to create when no dictionary word is exactly it. Most words are
    * already in the dictionary, which is fine — this page adds existing words to a tag.
    *
    * With nothing typed every word ranks alike and the order it arrived in survives, which is what keeps the chip row
    * showing a source word's translations in the order the dictionary gives them.
    */
  private def completions(words: List[Word], search: String): List[Completion] = {
    val low    = search.toLowerCase
    val ranked = words.zipWithIndex
      .sortBy { case (word, i) =>
        val text = word.text.toLowerCase
        val rank = if (text == low) 0 else if (text.startsWith(low)) 1 else 2
        (rank, i)
      }
      .map(_._1)
    // At most five dictionary words; the exact match is always rank 0, so it is never pushed out.
    val top    = ranked.take(translationChips)
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
    val list   = targetCompletions()
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

  /** Picking a dictionary word is what settles the pair's part of speech — the reader is never asked for one. */
  private def commitSource(word: Word): Unit = {
    val ref = TagPairWord.Existing(word.id)
    pendingSourceVar.set(Some(PendingSource(ref, Word.display(word), word.partOfSpeech)))
    // Keep the chosen word in the source input so the reader sees what will be added; only committing the pair clears
    // it. The display form keeps a German noun's article visible.
    Var.set(srcQueryVar -> Word.display(word), srcResultsVar -> Nil, srcHighlightVar -> -1)
    sourceCommittedBus.emit(ref)
    dom.window.setTimeout(() => focusTarget(), 0)
  }

  /** A word the dictionary does not have has no part of speech to read, so it starts as a noun — much the commonest
    * thing a reader types — and the inline select beside the input is what changes it.
    */
  private def commitNewSource(): Unit = {
    val language = sourceLangVar.now()
    val raw      = srcQueryVar.now()
    val text     = searchQuery(language, raw)
    val gender   = genderOf(raw, language)
    val display  = displayNew(text, language, gender)
    val ref      = TagPairWord.New(language, text, PartOfSpeech.Noun, gender)
    pendingSourceVar.set(Some(PendingSource(ref, display, PartOfSpeech.Noun)))
    Var.set(srcQueryVar -> display, srcResultsVar -> Nil, srcHighlightVar -> -1)
    sourceCommittedBus.emit(ref)
    dom.window.setTimeout(() => focusTarget(), 0)
  }

  private def commitTarget(word: Word): Unit = {
    commitTargetWord(TagPairWord.Existing(word.id), Word.display(word))
  }

  /** A new target word inherits the pair's part of speech: the source settled it, and the pairs table below is where
    * either side is corrected afterwards.
    */
  private def commitNewTarget(): Unit = {
    val language = targetLangVar.now()
    val raw      = tgtQueryVar.now()
    val text     = searchQuery(language, raw)
    val gender   = genderOf(raw, language)
    val pos      = pendingSourceVar.now().map(_.pos).getOrElse(PartOfSpeech.Noun)
    commitTargetWord(TagPairWord.New(language, text, pos, gender), displayNew(text, language, gender))
  }

  /** A pair the list already holds is refused rather than added a second time.
    *
    * The write behind it is idempotent — `WordRepository.linkPair` inserts each of its four rows only if it is not
    * already there — so a duplicate would not reach the database twice. It would still stand in the table twice, be
    * removable only one row at a time, and count twice against `AppConfig.quotas`' projected pair usage, which is
    * enough to block a tag the reader could in fact afford.
    */
  private def commitTargetWord(ref: TagPairWord, display: String): Unit = {
    pendingSourceVar.now() match {
      case Some(pending) =>
        val key = pairKey(pending.display, display, pending.pos)
        if (pairsVar.now().exists(p => pairKey(p.sourceText, p.targetText, p.pos) == key)) {
          noticeVar.set(Some(I18n.t(UiKeys.tagsDuplicatePair, pending.display, display)))
        } else {
          val id = pairIdCounter.now()
          pairIdCounter.set(id + 1)
          pairsVar.update(list => list :+ CommittedPair(id, pending.ref, ref, pending.display, display, pending.pos))
          noticeVar.set(None)
        }
        // Cleared either way: the pair the reader was typing is accounted for, and leaving it in the inputs would make
        // a refusal look like a control that had stopped responding.
        resetPairInputs()
        focusSource()
      case None          =>
        ()
    }
  }

  /** What makes two pairs the same one.
    *
    * The displayed text rather than the [[TagPairWord]] refs, because the two are not interchangeable to a reader: a
    * word picked from the dictionary and the same word typed past an autocomplete that had not answered yet are an
    * `Existing` and a `New` ref, unequal as data and identical on the screen. The part of speech is part of the key for
    * the opposite reason — `laufen` the verb and `Laufen` the noun read alike once case is folded, and are two
    * different words.
    */
  private def pairKey(source: String, target: String, pos: PartOfSpeech): (String, String, PartOfSpeech) = {
    (source.trim.toLowerCase, target.trim.toLowerCase, pos)
  }

  /** The inline select's one write: rebuilds the pending source's word with the chosen part of speech, and the input's
    * text with it — a German word that stops being a noun stops carrying an article.
    */
  private def changePendingPos(code: String): Unit = {
    val pos = PartOfSpeech.fromString(code).getOrElse(PartOfSpeech.Other)
    pendingSourceVar.now().foreach { pending =>
      withNewPos(pending.ref, pos).foreach { case (ref, display) =>
        pendingSourceVar.set(Some(PendingSource(ref, display, pos)))
        srcQueryVar.set(display)
      }
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

  /** The reader edited the committed source word, so the pending target no longer matches it: drop it and start over.
    */
  private def clearPending(): Unit = {
    pendingSourceVar.set(None)
    Var.set(tgtQueryVar -> "", tgtKnownVar -> Nil, tgtLiveVar -> Nil, tgtHighlightVar -> -1)
  }

  /** The text to search and store for a gendered-language input: the article is not part of the word. */
  private def searchQuery(language: WordLanguage, raw: String): String = {
    LanguageProfile.of(language).strip(raw)._1.trim
  }

  /** A gendered noun's gender, read out of the article the reader picked into the input. No part-of-speech test in
    * front of it any more: an article in the box is a gender, and a gender is what makes the word a noun.
    */
  private def genderOf(raw: String, language: WordLanguage): Option[Gender] = {
    LanguageProfile.of(language).strip(raw)._2
  }

  private def displayNew(text: String, language: WordLanguage, gender: Option[Gender]): String = {
    val profile = LanguageProfile.of(language)
    profile.display(profile.capitalize(text, gender), gender)
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
        cls    := "select select-sm w-28",
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
        disabled <-- pairsVar.signal.map(_.nonEmpty),
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

  /** The part of speech of a word the dictionary does not have, beside the input holding it. Offered only on that path
    * — a word picked from the dictionary brought its own, and asking again could only contradict it.
    */
  private def renderPendingPosSelect(): HtmlElement = {
    label(
      cls := "flex items-center gap-2",
      span(cls   := "label-text text-xs", I18n.t(UiKeys.tagsPartOfSpeech)),
      select(
        cls      := "select select-xs w-28",
        nameAttr := "pending-pos",
        PartOfSpeech.all.map(pos => option(value := PartOfSpeech.code(pos), Labels.partOfSpeech(pos))),
        controlled(
          value <-- pendingPosSignal.map(_.getOrElse(PartOfSpeech.Noun)).map(PartOfSpeech.code),
          onChange.mapToValue --> Observer[String](changePendingPos),
        ),
      ),
    )
  }

  /** The source dropdown: a vertical menu, because each row carries two facts — the word and the part of speech that
    * tells it from its homographs. That badge is the whole reason `der See` and `die See`, or `laufen` and `Laufen`,
    * are legible as separate rows rather than as a listing that repeated itself.
    */
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
                case DictionaryCompletion(word) =>
                  span(cls := "badge badge-ghost badge-xs", Labels.partOfSpeech(word.partOfSpeech))
                case NewCompletion(_)           =>
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

  /** The target's candidates, as chips rather than as a dropdown — the same control the words listing offers a
    * translation as (`WordCollect.renderChip`), minus its marked/unmarked state: here a chip is a pick, not a toggle.
    *
    * They are in the page's flow rather than absolutely positioned, because unlike a dropdown they are showing before
    * anything is typed and are the thing the reader is meant to be reading. `tabIndex := -1` keeps Tab out of them,
    * which is what leaves Tab free to mean "accept the highlighted one" while the caret stays in the input.
    */
  private def renderChipRow(list: List[Completion]): HtmlElement = {
    div(
      idAttr     := targetListboxId,
      role       := "listbox",
      aria.label := I18n.t(UiKeys.tagsTranslations),
      cls        := "flex flex-wrap gap-1",
      list.zipWithIndex.map { case (entry, i) =>
        val highlighted   = tgtHighlightVar.signal.map(_ == i).distinct
        val text          = entry match {
          case DictionaryCompletion(word) => Word.display(word)
          case NewCompletion(value)       => "+ " + value
        }
        button(
          idAttr   := chipId(i),
          role     := "option",
          typ      := "button",
          tabIndex := -1,
          cls      := "badge badge-sm cursor-pointer",
          cls("badge-primary") <-- highlighted,
          cls("badge-ghost") <-- highlighted.map(!_).map(_ && entry.isInstanceOf[DictionaryCompletion]),
          cls("badge-outline") <-- highlighted.map(!_).map(_ && entry.isInstanceOf[NewCompletion]),
          aria.selected <-- highlighted,
          text,
          onMouseEnter.mapToUnit --> Observer[Unit](_ => tgtHighlightVar.set(i)),
          onClick.mapToUnit --> Observer[Unit](_ => pickTargetCompletion(entry)),
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
    pairsVar.update(list => {
      list.map { pair =>
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
      }
    })
  }

  /** Rebuilds a not-yet-created word with a new part of speech, along with its display text. A gender belongs to a
    * noun, so a word that stops being one loses it — and with it the article on its display text. `None` for a word
    * already in the dictionary: its part of speech is fixed.
    */
  private def withNewPos(ref: TagPairWord, pos: PartOfSpeech): Option[(TagPairWord, String)] = {
    ref match {
      case TagPairWord.New(language, text, _, gender) =>
        val kept = if (pos == PartOfSpeech.Noun) gender else None
        Some((TagPairWord.New(language, text, pos, kept), displayNew(text, language, kept)))
      case _: TagPairWord.Existing                    =>
        None
    }
  }
}
