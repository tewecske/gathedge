package gathedge.frontend.components

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.api.{ApiClient, ApiError, WordApiClient}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{Tag, User}
import gathedge.shared.dto.TaggedPair
import gathedge.shared.i18n.UiKeys

/** Everything "this word is in my vocabulary" is made of, shared by the two screens that offer it.
  *
  * The listing and the word page do the same two writes — file a word under a tag, and mark one of its translations as
  * the answer to practise — against the same collect tag, which lives in this browser rather than in the URL or on the
  * server. That is why this is one object with the storage key, the default-tag rule, the guest detour and the two
  * controls in it: two copies would be two tags, and a reader who ticked a word on one screen would find it missing on
  * the other.
  *
  * A page constructs one, splices [[bindings]] into its root element (the streams only run while mounted) and renders
  * the pieces it wants. What a write *means* stays with the page: the listing re-asks the server for its rows, the word
  * page re-fetches the word, and each keeps its own alerts — hence the three observers rather than an error `Var` here.
  */
object WordCollect {

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

  /** Which of a word's translations count as marked, given the tag the page is collecting into.
    *
    * A chip has to answer the question the *click* acts on — "is this marked under the collect tag" — and never "under
    * any tag I have", which is the mistake that made a filtered listing look fully collected. `None` is only the moment
    * before the tag list arrives, or a reader with no tags at all, where the honest answer is whatever they have.
    *
    * Takes the marks rather than a row so both screens can ask it: the listing has a `WordSummary`, the word page a
    * `WordDetail`. Here rather than in either of them so it can be stated as a table in a test — neither page has a
    * seam for injecting rows, and this is the part of a chip that can be wrong.
    */
  def selectedTranslationIds(pairs: List[TaggedPair], collect: Option[Long]): Set[Long] = {
    collect match {
      case Some(tagId) =>
        pairs.filter(_.tagId == tagId).map(_.translationWordId).toSet
      case None        =>
        pairs.map(_.translationWordId).toSet
    }
  }

  /** Whether the tick is showing, given the reader's tags on the word — the same question [[selectedTranslationIds]]
    * answers for a chip, and wrong in the same way if it is asked of the listing's filter instead of the collect tag.
    */
  def isTagged(tagIds: List[Long], collect: Option[Long]): Boolean = {
    collect match {
      case Some(tagId) =>
        tagIds.contains(tagId)
      case None        =>
        tagIds.nonEmpty
    }
  }
}

/** @param onError
  *   the page's own alert: a failed write's message, and `None` where a success clears it.
  * @param onNotice
  *   where "you now have a guest account" goes, once the detour has minted one.
  * @param onWritten
  *   what the page does when a tick or a chip lands: ask the server for whatever it is showing, again.
  */
final class WordCollect(
  onError: Observer[Option[String]],
  onNotice: Observer[String],
  onWritten: Observer[Unit],
) {

  private val tagsVar = Var(List.empty[Tag])

  /** The reader's tags, with their word counts: what both tag selects are drawn from. */
  val tagsSignal: Signal[List[Tag]] = tagsVar.signal

  /** The tag a tick files into: remembered per browser (see [[WordCollect.storedCollectTag]]), never in the URL. `None`
    * only until the reader's tag list arrives, or while they have no tags at all — a tick then makes one.
    */
  private val collectTagVar = Var(WordCollect.storedCollectTag)

  val collectTagSignal: Signal[Option[Long]] = collectTagVar.signal

  /** Mirrors who the reader is at the moment a word is *clicked*. Signals cannot be read outside a subscription, and a
    * click handler is not one; this follows its signal through an observer and is read with `.now()`.
    */
  private val readerVar = Var(Option.empty[User])

  private val signedInSignal = AppState.isSignedInSignal

  private val newTagVar = Var("")
  private val newTagBus = new EventBus[Unit]()
  private val tagsBus   = new EventBus[Unit]()

  /** A word the reader clicked, with what should happen to it. The stream is what the guest-minting detour hangs off.
    */
  private val toggleBus = new EventBus[(Long, Boolean)]()

  /** A translation chip the reader clicked: which word, which translation, and whether it was already marked. Its own
    * bus for the reason [[toggleBus]] is one — the guest detour hangs off the stream, not off the click.
    */
  private val pairBus = new EventBus[(Long, Long, Boolean)]()

  /** Everything that has to be running for a click to do anything. Spliced into the page's root element as
    * `collect.bindings*`, because a Laminar stream is only subscribed while the element holding it is mounted.
    */
  def bindings: Seq[Modifier[HtmlElement]] = {
    List[Modifier[HtmlElement]](
      // The tag list is only fetchable with a session, and it is what the tag selects are drawn from.
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
            newTagVar.set("")
            onError.onNext(None)
            tagsVar.update(_ :+ tag)
            setCollectTag(Some(tag.id))
            tagsBus.emit(())
          case Left(err)  =>
            onError.onNext(Some(err.message))
        },
      EventStream.merge(toggleStream, pairStream) --> writeResult,
      AppState.currentUserSignal --> readerVar.writer,
      onMountCallback(_ => tagsBus.emit(())),
    )
  }

  /** Asks for the reader's tags again — what a page calls when something outside a tick may have changed them. */
  def reloadTags(): Unit = tagsBus.emit(())

  private def setCollectTag(tagId: Option[Long]): Unit = {
    collectTagVar.set(tagId)
    WordCollect.storeCollectTag(tagId)
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
            onNotice.onNext(I18n.t(UiKeys.guestBannerHint))
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
    * that word under the tag too — so both refresh the page and the tag list.
    */
  private val writeResult: Observer[Either[ApiError, Unit]] = Observer[Either[ApiError, Unit]] {
    case Right(_)  =>
      onWritten.onNext(())
      tagsBus.emit(())
    case Left(err) =>
      onError.onNext(Some(err.message))
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
    * creates one on their behalf rather than refusing the click. Public because adding a word is filed the same way.
    */
  def collectTagOrDefault: EventStream[Either[ApiError, Long]] = {
    collectTagVar.now() match {
      case Some(id) =>
        EventStream.fromValue(Right(id))
      case None     =>
        tagsVar.now().headOption match {
          case Some(tag) =>
            EventStream.fromValue(Right(tag.id))
          case None      =>
            WordApiClient
              .createTag(WordCollect.defaultTagName)
              .map(_.map(created => {
                setCollectTag(Some(created.id))
                created.id
              }))
        }
    }
  }

  /** What a tag `<select>` should be showing: the id, but only once the tag list holding its `<option>` has arrived.
    *
    * Emitting on the tag list as well as on the id is the whole point — a value set while its option is missing is
    * dropped by the browser, and a signal that never emits again has no way to put it back.
    */
  def selectedTagValue(selected: Signal[Option[Long]]): Signal[String] = {
    selected.combineWithFn(tagsSignal)((id, tags) => {
      id.filter(chosen => tags.exists(_.id == chosen)).map(_.toString).getOrElse("")
    })
  }

  /** Where ticks are filed, and the way to make another tag. Only worth rendering with a session, since a tag belongs
    * to an account — each page gates it on that itself.
    *
    * A card of its own, away from the filters, because it answers a different question from the tag *filter*: this one
    * says where words go, that one says which words are shown. The hint under it is what makes that readable without
    * having to try it.
    */
  def renderBar(): HtmlElement = {
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
              cls      := "flex flex-col gap-1",
              span(cls      := "label-text text-xs", I18n.t(UiKeys.wordsTagNew)),
              input(
                cls         := "input input-sm w-52",
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
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs font-semibold", I18n.t(UiKeys.wordsCollectLabel)),
      select(
        cls    := "select select-sm select-primary w-52",
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

  /** Whether a word carrying these tags is in the vocabulary, as the tick asks it. */
  def taggedSignal(tagIds: Signal[List[Long]]): Signal[Boolean] = {
    tagIds.combineWithFn(collectTagSignal)(WordCollect.isTagged).distinct
  }

  /** Which of a word's translations are marked, as the chips ask it. */
  def selectedSignal(pairs: Signal[List[TaggedPair]]): Signal[Set[Long]] = {
    pairs.combineWithFn(collectTagSignal)(WordCollect.selectedTranslationIds).distinct
  }

  /** The word's own control: in or out of the vocabulary, in one click.
    *
    * `label` is a signal because it names the word, which on the listing is a row that can be replaced under the
    * button. It takes the reader's tags on the word rather than a ready-made boolean, so that no caller can answer "is
    * it tagged" off the listing's *filter* — the mistake that made a narrowed listing look fully collected.
    */
  def renderTick(wordId: Long, label: Signal[String], tagIds: Signal[List[Long]]): HtmlElement = {
    val tagged = taggedSignal(tagIds)

    button(
      // A fixed box, because the two glyphs below are not the same width and the column would twitch on a tick.
      cls := "btn btn-ghost btn-xs w-8 px-0",
      cls("text-success") <-- tagged,
      typ := "button",
      aria.label <-- label.combineWithFn(tagged) { (word, isTagged) =>
        val key = {
          if (isTagged)
            UiKeys.wordsTagRemove
          else
            UiKeys.wordsTagAdd
        }
        I18n.t(key, word)
      },
      child.text <-- tagged.map(isTagged => {
        if (isTagged)
          "✓"
        else
          "+"
      }),
      onClick.compose(_.sample(tagged)) --> Observer[Boolean](isTagged => toggleBus.emit((wordId, isTagged))),
    )
  }

  /** One translation, as a control rather than as text.
    *
    * Clicking it says "this is the answer I want to be asked for", which also files both words under the collect tag —
    * the tick files one word, this files a pair. Marked state is shown with a tick as well as with colour, since colour
    * alone is not a difference every reader can see, and stated for a screen reader as `aria-pressed`.
    */
  def renderChip(
    wordId: Long,
    translationWordId: Long,
    text: Signal[String],
    pairs: Signal[List[TaggedPair]],
  ): HtmlElement = {
    // The marks, not a ready-made "is this one selected", for the reason [[renderTick]] takes the tags.
    val markedSignal = selectedSignal(pairs).map(_.contains(translationWordId)).distinct

    button(
      typ := "button",
      cls := "badge badge-sm cursor-pointer gap-0.5 px-1",
      cls("badge-primary") <-- markedSignal,
      cls("badge-ghost") <-- markedSignal.map(!_),
      aria.pressed <-- markedSignal.map(_.toString),
      aria.label <-- text.combineWithFn(markedSignal) { (translation, marked) =>
        val key = {
          if (marked)
            UiKeys.wordsPairRemove
          else
            UiKeys.wordsPairAdd
        }
        I18n.t(key, translation)
      },
      // The tick keeps its box whether or not it is showing — `visibility:hidden` keeps the mark's width — so marking
      // a chip recolours it without resizing it or nudging the chips after it along the row. It is smaller than the
      // word and sits under a tighter gap, and the mirror after the word is what keeps the word itself centred: a mark
      // only on the left reads as a chip padded wrong rather than as a chip with a mark.
      span(cls := "flex", cls("invisible") <-- markedSignal.map(!_), chipMark()),
      span(child.text <-- text),
      span(cls := "flex invisible", aria.hidden := true, chipMark()),
      onClick.compose(_.sample(markedSignal)) -->
        Observer[Boolean](marked => pairBus.emit((wordId, translationWordId, marked))),
    )
  }

  /** Beside a tagged word whose translations are all unmarked: the tick says it is being learned, and nothing says what
    * the answer to it is, so a practice screen would have the word and nothing to check against.
    *
    * It states the gap on hover and never blocks anything — the word is still perfectly usable, and marking a chip is
    * the whole of the fix, which is a click away on the same screen.
    */
  def renderPairWarning(): HtmlElement = {
    span(
      cls             := "tooltip tooltip-error text-error leading-none",
      dataAttr("tip") := I18n.t(UiKeys.wordsNoPair),
      // Drawn by CSS off a `data-` attribute, which no screen reader announces — so the same sentence goes into the
      // accessibility tree as text, the way the mark itself carries none.
      span(cls := "sr-only", I18n.t(UiKeys.wordsNoPair)),
      warningMark(),
    )
  }

  /** The exclamation mark, drawn for the reason [[chipMark]] is: an SVG's box is its ink, so it sits on the word's
    * centre line beside a link of any size rather than wherever the font puts a `!` inside its own line box.
    */
  private def warningMark(): SvgElement = {
    svg.svg(
      svg.cls            := "h-4 w-4 shrink-0",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "2",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.circle(svg.cx := "12", svg.cy := "12", svg.r := "9"),
      svg.path(svg.d    := "M12 7.5v5"),
      svg.path(svg.d    := "M12 16.25h.01"),
    )
  }

  /** The tick on a chip, drawn rather than typed.
    *
    * A `✓` character sits wherever its font puts it inside a line box, and that box is proportional to the font size —
    * so a mark this much smaller than the word beside it lands visibly above the middle however the line height is set,
    * and moves again whenever the size is changed. An SVG's box *is* its ink, which the badge's `align-items:center`
    * then centres exactly, at any size.
    */
  private def chipMark(): SvgElement = {
    svg.svg(
      svg.cls            := "h-[0.4rem] w-[0.47rem] shrink-0",
      svg.viewBox        := "0 0 14 12",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "2.5",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(svg.d := "M1 6.5L5 10.5L13 1.5"),
    )
  }
}
