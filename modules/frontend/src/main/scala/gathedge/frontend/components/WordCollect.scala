package gathedge.frontend.components

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.api.{ApiClient, ApiError, WordApiClient}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{Tag, User, WordLanguage}
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
  * the pieces it wants. What a write *means* stays with the page: the listing applies what changed to its local state,
  * the word page does the same, and each keeps its own alerts — hence the three observers rather than an error `Var`
  * here.
  */
object WordCollect {

  sealed trait Change
  final case class TagChange(wordId: Long, tagId: Long, tagged: Boolean)                           extends Change
  final case class PairChange(wordId: Long, tagId: Long, translationWordId: Long, marked: Boolean) extends Change

  /** The tag a word goes under when the reader has chosen none. Data rather than copy: it becomes a row in `tags` that
    * they can rename or delete on the Tags screen, so it is not translated — a tag created in Hungarian and then read
    * in English would otherwise appear to change its name.
    */
  val defaultTagName = "saved"

  /** Where the tag a tick files into is remembered — per browser, never in the URL: it is the reader's working state,
    * not a view of the data worth bookmarking or sending.
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

  /** Which tag a reader should be collecting into, given the one this browser remembers and the tag list just fetched
    * for the account now signed in.
    *
    * A remembered id is a *browser* fact, not an account fact: `localStorage` outlives a sign-out, a guest mint and an
    * account swept away, so an id left there may name a tag this reader cannot write to — a write against it fails with
    * `TagNotFound`, which is exactly the "No such tag" a guest with no tags of their own hits. It is therefore kept
    * only where the fetched list confirms it, and otherwise replaced by one of the reader's own, then by any tag a
    * group has opened to them, then by nothing — `None` meaning "make one on the first click".
    */
  def keptCollectTag(remembered: Option[Long], tags: List[Tag]): Option[Long] = {
    remembered
      .filter(id => tags.exists(tag => tag.id == id && tag.editableByMe))
      .orElse(tags.find(_.ownedByMe).map(_.id))
      .orElse(tags.find(_.editableByMe).map(_.id))
  }

  /** Folds a tag the reader just gained into a list they already had, replacing any existing entry for the same id
    * rather than appending beside it.
    *
    * Not `tags :+ tag` followed by `.distinct`: [[Tag]] carries `wordCount`, which the next tag-list refresh can
    * change, so a stale and a fresh copy of the same tag compare unequal and a plain `distinct` leaves both in — the
    * same tag name rendered twice for every write that lands between two refreshes.
    */
  def withTag(tags: List[Tag], tag: Tag): List[Tag] = tags.filterNot(_.id == tag.id) :+ tag

  /** Tag `<select>`s that deliberately show every tag, not just the reader's own — the listing filter narrows a *view*
    * of the data, so a stranger's tag is as legitimate a thing to filter by as one's own. Grouped the way
    * [[Tag.sorted]] orders them: the reader's own tags under one heading, everyone else's under another. An `<option>`
    * cannot carry a badge or a colour, so the group heading — an `<optgroup label>`, which every screen reader
    * announces — is the marking the dropdown shows, not an icon on each row. A group with nothing in it is left out
    * rather than rendered empty, most often "My tags" for a reader who owns none yet.
    */
  def tagOptionGroups(tags: List[Tag]): List[HtmlElement] = {
    val (mine, others) = Tag.sorted(tags).partition(_.ownedByMe)
    List(
      Option.when(mine.nonEmpty)(optGroup(labelAttr := I18n.t(UiKeys.wordsTagsMineGroup), mine.map(tagOption))),
      Option.when(others.nonEmpty)(optGroup(labelAttr := I18n.t(UiKeys.wordsTagsOthersGroup), others.map(tagOption))),
    ).flatten
  }

  /** What the collect select offers: every tag the reader may edit — their own, plus any tag a group they belong to has
    * open — and nothing else, since a tick has to write somewhere the reader can actually write. Their own tags come
    * first, ungrouped; a fellow member's tag then falls under an `<optgroup>` named for the group that opened it, one
    * section per group, so two classrooms sharing a reader read as two separate lists rather than one merged pile. A
    * stranger's tag outside any shared group is not merely mis-grouped here — it is absent, same as before groups
    * existed.
    */
  def mineOptions(tags: List[Tag]): List[HtmlElement] = {
    val editable         = tags.filter(_.editableByMe)
    val (mine, byOthers) = editable.partition(_.ownedByMe)
    val mineOpts         = mine.sortBy(_.name.toLowerCase).map(tagOption)
    val groupOpts        = byOthers
      .groupBy(_.group.map(_.name).getOrElse(""))
      .toList
      .sortBy { case (name, _) => name.toLowerCase }
      .map { case (name, groupTags) =>
        optGroup(labelAttr := name, groupTags.sortBy(_.name.toLowerCase).map(tagOption))
      }
    mineOpts ++ groupOpts
  }

  private def tagOption(tag: Tag): HtmlElement = option(value := tag.id.toString, s"${tag.name} (${tag.wordCount})")
}

/** @param onError
  *   the page's own alert: a failed write's message, and `None` where a success clears it.
  * @param onNotice
  *   where "you now have a guest account" goes, once the detour has minted one.
  * @param onWarning
  *   where a soft-quota warning goes — a tag or pair write that still succeeded, but pushed the account's usage to or
  *   past `AppConfig.quotas`' soft threshold. Separate from [[onNotice]] because the two read differently: a notice is
  *   informational, a warning is worth the page's attention (`Alert.warning` rather than `Alert.info`).
  * @param onWritten
  *   what the page does when a tick or a chip lands: apply what changed to its local state.
  * @param collectLanguages
  *   the source/target languages an auto-minted "saved" tag is created with — the direction the page is browsing.
  *   Mandatory on every tag now, so the first tick has to name one.
  */
final class WordCollect(
  onError: Observer[Option[String]],
  onNotice: Observer[String],
  onWarning: Observer[String],
  onWritten: Observer[WordCollect.Change],
  collectLanguages: Signal[(WordLanguage, WordLanguage)] = Val((WordLanguage.De, WordLanguage.Hu)),
) {

  private val tagsVar = Var(List.empty[Tag])

  /** The reader's tags, with their word counts: what both tag selects are drawn from. */
  val tagsSignal: Signal[List[Tag]] = tagsVar.signal

  /** The tag a tick files into: remembered per browser (see [[WordCollect.storedCollectTag]]), never in the URL. `None`
    * only until the reader's tag list arrives, or while they have no tags at all — a tick then makes one.
    */
  private val collectTagVar = Var(WordCollect.storedCollectTag)

  /** Whether [[tagsVar]] holds a list fetched for whoever is signed in *now* — i.e. whether [[collectTagVar]] has been
    * through [[reconcileCollectTag]] against that list.
    *
    * The remembered id is read from `localStorage` before any request is made, so between the first render and the tag
    * list landing it is only a guess: it may name a previous account's tag, and a click in that window would write
    * against it and fail with `TagNotFound`. [[collectTagOrDefault]] therefore fetches the list first whenever this is
    * `false`, rather than assuming the mount-time fetch has already answered.
    */
  private val tagsLoadedVar = Var(false)

  val collectTagSignal: Signal[Option[Long]] = collectTagVar.signal

  /** Mirrors who the reader is at the moment a word is *clicked*. Signals cannot be read outside a subscription, and a
    * click handler is not one; this follows its signal through an observer and is read with `.now()`.
    */
  private val readerVar = Var(Option.empty[User])

  private val signedInSignal = AppState.isSignedInSignal

  private val tagsBus = new EventBus[Unit]()

  /** Mirrors [[collectLanguages]] so a click handler — not a subscription — can read the current browsing direction
    * with `.now()` when it mints the reader's first tag.
    */
  private val collectLanguagesVar = Var((WordLanguage.De, WordLanguage.Hu))

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
            setTags(tags)
            tagsLoadedVar.set(true)
            reconcileCollectTag(tags)
          case Left(_)     =>
            tagsVar.set(Nil)
            // Left unloaded deliberately: a list that never arrived has confirmed nothing, so the next write fetches
            // it again rather than writing against a remembered id nothing has checked.
            tagsLoadedVar.set(false)
        },
      EventStream.merge(toggleStream, pairStream) --> writeResult,
      AppState.currentUserSignal --> readerVar.writer,
      collectLanguages --> collectLanguagesVar.writer,
      onMountCallback(_ => tagsBus.emit(())),
    )
  }

  /** Asks for the reader's tags again — what a page calls when something outside a tick may have changed them. */
  def reloadTags(): Unit = tagsBus.emit(())

  private def setCollectTag(tagId: Option[Long]): Unit = {
    collectTagVar.set(tagId)
    WordCollect.storeCollectTag(tagId)
  }

  /** [[Tag.sorted]] applied wherever the tag list changes, so a caller reading [[tagsSignal]] never has to re-derive
    * the order the two dropdowns show it in.
    */
  private def setTags(tags: List[Tag]): Unit = tagsVar.set(Tag.sorted(tags))

  /** Keeps the collect tag on one the reader may still edit, and chooses one for a reader who has never picked —
    * including a guest, whose first tag is minted by their first tick. A tag deleted on another device, one that turns
    * out not to be editable (a foreign id left over in `localStorage` from before the select stopped offering it), or
    * one whose group the reader has since left, would otherwise leave every tick failing against an id they cannot
    * write to. Prefers one of the reader's own over a group tag, so an owner is never silently defaulted into filing
    * under somebody else's classroom.
    */
  private def reconcileCollectTag(tags: List[Tag]): Unit = {
    val kept = WordCollect.keptCollectTag(collectTagVar.now(), tags)
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
  private def asReader[A](write: () => EventStream[Either[ApiError, A]]): EventStream[Either[ApiError, A]] = {
    readerVar.now() match {
      case Some(_) =>
        write()
      case None    =>
        ApiClient.createGuest.flatMapSwitch {
          case Right(response) =>
            AppState.setUser(response.user)
            // A freshly minted guest owns no tags yet, so any collect tag remembered in this browser's localStorage —
            // left over from a previous account on the same device — cannot be theirs. Forgetting it here is what
            // makes `collectTagOrDefault` mint a fresh one instead of writing against a foreign or deleted id and
            // failing with `TagNotFound`. `reconcileCollectTag` would fix this too, but only once the tag list it
            // reacts to has actually come back, which is later than the `write()` below.
            setCollectTag(None)
            // The list in hand was fetched for nobody (or for whoever was here before), so it says nothing about this
            // account's tags either — the write below fetches a fresh one rather than reading it.
            tagsLoadedVar.set(false)
            // The banner appears from here on: the reader now has an account, and nothing else has told them so.
            onNotice.onNext(I18n.t(UiKeys.guestBannerHint))
            tagsBus.emit(())
            write()
          case Left(err)       =>
            EventStream.fromValue(Left(err))
        }
    }
  }

  private def toggleStream: EventStream[Either[ApiError, WordCollect.Change]] = {
    toggleBus.events.flatMapSwitch { case (wordId, tagged) =>
      asReader(() => writeTag(wordId, tagged))
    }
  }

  private def pairStream: EventStream[Either[ApiError, WordCollect.Change]] = {
    pairBus.events.flatMapSwitch { case (wordId, translationWordId, marked) =>
      asReader(() => writePair(wordId, translationWordId, marked))
    }
  }

  /** What both writes do when they land. A chip moves tag counts as much as a tick does — marking a translation files
    * that word under the tag too — so both refresh the page and the tag list.
    */
  private val writeResult: Observer[Either[ApiError, WordCollect.Change]] = {
    Observer[Either[ApiError, WordCollect.Change]] {
      case Right(change) =>
        onWritten.onNext(change)
        tagsBus.emit(())
      case Left(err)     =>
        onError.onNext(Some(err.message))
    }
  }

  /** Puts the word under the collect tag, or under the reader's default one when they have not chosen. */
  private def writeTag(wordId: Long, tagged: Boolean): EventStream[Either[ApiError, WordCollect.Change]] = {
    collectTagOrDefault.flatMapSwitch {
      case Left(err)    =>
        EventStream.fromValue(Left(err))
      case Right(tagId) =>
        val result: EventStream[Either[ApiError, Unit]] = {
          if (tagged)
            WordApiClient.untagWord(wordId, tagId)
          else
            WordApiClient.tagWord(wordId, tagId)
        }
        result.map(_.map(_ => WordCollect.TagChange(wordId, tagId, !tagged)))
    }
  }

  /** Marks or unmarks the translation under the collect tag — the same `collectTagOrDefault` path a tick takes, so a
    * first-ever click on a chip mints a tag the same way a first-ever tick does.
    */
  private def writePair(
    wordId: Long,
    translationWordId: Long,
    marked: Boolean,
  ): EventStream[Either[ApiError, WordCollect.Change]] = {
    collectTagOrDefault.flatMapSwitch {
      case Left(err)    =>
        EventStream.fromValue(Left(err))
      case Right(tagId) =>
        if (marked) {
          WordApiClient
            .deselectPair(wordId, tagId, translationWordId)
            .map(_.map(_ => WordCollect.PairChange(wordId, tagId, translationWordId, !marked)))
        } else {
          WordApiClient
            .selectPair(wordId, tagId, translationWordId)
            .map(_.map { response =>
              // Fired here rather than surfaced through `writeResult`, which only ever sees the `Change` a write
              // produced — `deselectPair` never warns, so widening that shared shape to carry an optional one for the
              // sake of this one branch would be a wider change for a narrower reason.
              response.warning.foreach(warning => onWarning.onNext(I18n.resolve(warning)))
              WordCollect.PairChange(wordId, tagId, translationWordId, !marked)
            })
        }
    }
  }

  /** The tag a click files under: the collect tag, else the reader's own first tag, else the first group tag they may
    * edit, else a fresh one.
    *
    * Clicking without having chosen a tag has to mean something — it is the first thing a new reader does — so the page
    * creates one on their behalf rather than refusing the click. The fallback prefers `ownedByMe` over a shared group
    * tag before falling back to either, not merely the list's first: the list is global, and picking a tag the caller
    * may not edit here would write against an id that fails with `TagNotFound`. Public because adding a word is filed
    * the same way.
    */
  def collectTagOrDefault: EventStream[Either[ApiError, Long]] = {
    if (tagsLoadedVar.now()) {
      resolvedCollectTag
    } else {
      // The remembered id has not been checked against this account yet — see [[tagsLoadedVar]]. A click landing in
      // that window (the moment after a page load, or right after a guest was minted) must not write against it.
      WordApiClient.listTags.flatMapSwitch {
        case Right(tags) =>
          setTags(tags)
          tagsLoadedVar.set(true)
          reconcileCollectTag(tags)
          resolvedCollectTag
        case Left(err)   =>
          EventStream.fromValue(Left(err))
      }
    }
  }

  /** [[collectTagOrDefault]] once the tag list is known to be this account's. */
  private def resolvedCollectTag: EventStream[Either[ApiError, Long]] = {
    collectTagVar.now() match {
      case Some(id) =>
        EventStream.fromValue(Right(id))
      case None     =>
        tagsVar.now().find(_.ownedByMe).orElse(tagsVar.now().find(_.editableByMe)) match {
          case Some(tag) =>
            EventStream.fromValue(Right(tag.id))
          case None      =>
            val (source, target) = collectLanguagesVar.now()
            WordApiClient
              .createTag(WordCollect.defaultTagName, source, target)
              .map(_.map(response => {
                response.warning.foreach(warning => onWarning.onNext(I18n.resolve(warning)))
                // Held locally as well: the select has the tag at once, and a tag list fetched a moment later cannot
                // reconcile the reader off a tag that exists.
                tagsVar.update(existing => Tag.sorted(WordCollect.withTag(existing, response.tag)))
                setCollectTag(Some(response.tag.id))
                response.tag.id
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

  /** Where ticks are filed. Rendered for every visitor, signed in or not — a tag belongs to an account, but a reader
    * with no account yet still has to be able to see and use the control before their first tick mints one for them,
    * through the same `asReader` detour a tick or a chip takes.
    *
    * A card of its own, away from the filters, because it says where words go, not which words are shown. Making,
    * renaming and deleting tags all live on the Tags screen now; this bar only picks among the ones that exist.
    */
  def renderBar(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mb-4",
      div(
        cls := "card-body py-3 gap-2",
        div(
          cls := "flex flex-wrap items-end gap-3",
          // Absent until the reader has a tag to file under — their own, or one a group has opened to them — so with
          // none of those it would otherwise render with nothing to choose between, even while the global list is
          // non-empty.
          child.maybe <-- tagsSignal.map(tags => Option.when(tags.exists(_.editableByMe))(renderCollectSelect())),
        ),
        p(cls := "text-xs opacity-60", I18n.t(UiKeys.wordsCollectHint)),
        p(cls := "text-xs opacity-60", I18n.t(UiKeys.wordsPairHint)),
      ),
    )
  }

  /** The collect tag itself. No "none" option: a tick has to go somewhere, so the page picks a tag rather than leaving
    * the reader to discover that the empty entry silently meant "the first one". Offers every tag the reader may edit —
    * see [[WordCollect.mineOptions]] — since a tick against any other would fail with `TagNotFound`.
    */
  private def renderCollectSelect(): HtmlElement = {
    label(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs font-semibold", I18n.t(UiKeys.wordsCollectLabel)),
      select(
        cls    := "select select-sm select-primary w-52",
        children <-- tagsSignal.map(WordCollect.mineOptions),
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
