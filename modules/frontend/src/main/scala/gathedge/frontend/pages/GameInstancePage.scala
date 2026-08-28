package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiClient, ApiError, GameApiClient}
import gathedge.frontend.components.{Alert, AppShell, GuestBanner, InlineRename, Labels, ShareRow, TagWordsList}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.{AppState, GameOwnership, PendingPlay, PlayHandoff}
import gathedge.shared.domain.{User, WordLanguage, WordPreference}
import gathedge.shared.dto.{GameDetail, GameSetupWord, GameVariantDto, PlayStarted}
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom

import scala.concurrent.Future

/** The variant picker for one quiz, from its shared link (`/g/{slug}`) — the play loop itself lives at `Page.GamePlay`,
  * reached only right after `startPlay` succeeds (see [[Page.GamePlay]]'s doc comment).
  *
  * The initial `GET /api/games/{slug}` mints nobody — see `Page.GameInstance`'s doc comment — a visitor can read the
  * quiz's name and tags with no session at all. Neither does the play-variant picker's preview fetch
  * (`GameApiClient.playSetup`, wired through [[previewTriggerStream]]/[[reversePreviewTriggerBus]] below): it is an
  * `optionalUser` read too, so merely opening a shared link and looking at the picker mints no guest. Starting a play
  * IS the first write the page makes, so it is where the guest detour sits ([[asReader]], copied in spirit from
  * `GameSetupPage`'s) — the only call this page ever makes to `startPlay`.
  */
object GameInstancePage {

  /** Generates the QR modal's data URI for a URL. Threaded through as an ordinary parameter — the real caller (`App`)
    * passes `(text) => QRCode.toDataURL(text).toFuture`; a spec passes a stub — the same reason `WordsPage.render`
    * takes `recognizeImage: ImageOcr.Recognize` instead of calling `ImageOcr` itself: it keeps the `qrcode` npm
    * package's `@JSImport` out of this page's reachable graph under the test linker. `Test / scalaJSLinkerConfig` is
    * `NoModule` (see `build.sbt`), and [[gathedge.frontend.facades.QRCode]]'s `@JSImport` is otherwise statically
    * reachable from `render()` through the [[components.ShareRow]] this page builds regardless of whether the QR button
    * is ever actually clicked — DCE removes only genuinely unreachable code, not a branch a spec merely never exercises
    * at runtime — so any spec that renders this page at all would otherwise fail to link, exactly as
    * [[gathedge.frontend.ocr.ImageOcr.Recognize]]'s own doc comment explains for `tesseract.js`.
    */
  def render(slug: String, generateQr: String => Future[String]): HtmlElement = {
    AppShell.render(Page.GameInstance(slug), new GameInstancePage(slug, generateQr).render())
  }
}

private class GameInstancePage(slug: String, generateQr: String => Future[String]) {

  private val gameVar    = Var(Option.empty[GameDetail])
  private val missingVar = Var(false)

  /** The name shown in the header, separate from [[gameVar]] so a rename updates it without rebuilding the card — see
    * [[render]]'s comment on why the card itself is built once.
    */
  private val nameVar = Var("")

  private val errorVar: Var[Option[String]]  = Var(None)
  private val noticeVar: Var[Option[String]] = Var(None)

  /** The direction-swap arrow's own state — `false` plays the game's stored direction, `true` reverses it for this play
    * only. See the design doc's "no dropdowns, just an arrow" direction control.
    */
  private val swapDirectionVar = Var(false)

  /** Mutually exclusive with [[wordLimitTextVar]], the same pattern `GameSetupPage` used before this control moved
    * here. Defaults to `true`: "use every eligible word".
    */
  private val selectAllVar     = Var(true)
  private val wordLimitTextVar = Var("")

  private val wordLimitSignal: Signal[Option[Int]] = {
    selectAllVar.signal.combineWith(wordLimitTextVar.signal).map {
      case (true, _)     => None
      case (false, text) => text.trim.toIntOption.filter(_ > 0)
    }
  }

  private val includeArticlesVar = Var(true)

  /** Whether *either* resolved direction of the current pair involves German — the swap arrow flips which language is
    * source, but German-either-way is symmetric, so this does not need to depend on [[swapDirectionVar]].
    */
  private val germanInvolvedSignal: Signal[Boolean] = {
    gameVar.signal.map(_.exists(g => g.sourceLanguage == WordLanguage.De || g.targetLanguage == WordLanguage.De))
  }

  private val wordPreferenceVar = Var[WordPreference](WordPreference.All)

  private val previewWordsVar   = Var(List.empty[GameSetupWord])
  private val previewLoadingVar = Var(false)

  /** Fires once, right after the game successfully loads — see `render`'s `loadBus` wiring. Merged into
    * [[previewTriggerStream]] below so the preview populates on first entering the Play screen, not only after the
    * reader touches a control: `Signal.updates` (relied on for the reactive refetch-on-change half) excludes a signal's
    * starting value, so relying on it alone left `renderPreviewList` showing the "no eligible words" message on entry
    * even when eligible words existed. Same `EventStream.merge(signal.updates, bus.events.sample(signal))` shape as
    * `GameSetupPage.formRequests`/`AdminUsersPage.listRequests`, just triggered by the load succeeding instead of an
    * explicit reload button.
    */
  private val gameLoadedBus = new EventBus[Unit]()

  /** Refetches the play-setup preview whenever direction or preference changes, once the game itself has loaded —
    * mirrors `GameSetupPage.wordsQuerySignal`'s reasoning, one screen over.
    */
  private val previewQuerySignal: Signal[(Boolean, WordPreference)] = {
    swapDirectionVar.signal.combineWith(wordPreferenceVar.signal).distinct
  }

  private val previewTriggerStream: EventStream[(Boolean, WordPreference)] = {
    EventStream.merge(previewQuerySignal.updates, gameLoadedBus.events.sample(previewQuerySignal))
  }

  /** Whether the *reverse* direction's pool is empty — the swap arrow (`renderDirectionSwap`) disables on this, per the
    * design doc: swapping into an empty pool would make `startPlay` fail its unreachable-from-the-UI `badRequest` case.
    * Fetched right after each current-direction preview settles ([[reversePreviewTriggerBus]]), rather than on its own
    * independent `gameLoadedBus`-merged trigger like [[previewTriggerStream]] — sequencing it after the primary fetch
    * is simply so both previews don't fire in the same tick; neither call mints a guest (both go through
    * `GameApiClient.playSetup` directly, an `optionalUser` read), so there is no session race to avoid here any more.
    */
  private val reversePoolEmptyVar = Var(false)

  private val reversePreviewQuerySignal: Signal[(Boolean, WordPreference)] = {
    swapDirectionVar.signal.map(!_).combineWith(wordPreferenceVar.signal).distinct
  }

  private val reversePreviewTriggerBus = new EventBus[Unit]()

  private val startingVar = Var(false)

  /** Whether this browser is the one that created the game — see `GameOwnership`'s doc comment on why that is a local
    * hint rather than something `GameDetail` states. A `Var` rather than a `val` so a 403 on rename (a stale hint, or
    * the same account signed in elsewhere) can turn the control off without a reload.
    */
  private val isOwnerVar = Var(GameOwnership.isOwned(slug))

  private val inlineRename = new InlineRename[GameDetail](text => GameApiClient.rename(slug, text))

  /** Copy-link, Web Share and QR code — all three act on this page's own URL, which is the shared link itself. See
    * [[components.ShareRow]] for why `generateQr` is threaded in rather than called directly.
    */
  private val shareRow = new ShareRow(() => pageUrl(), () => nameVar.now(), generateQr, msg => noticeVar.set(Some(msg)))

  /** Mirrors who the reader is at the moment a request is made — same trick as `GameSetupPage.readerVar`, needed
    * because [[asReader]] reads it outside a subscription.
    */
  private val readerVar = Var(Option.empty[User])

  private val loadBus  = new EventBus[Unit]()
  private val startBus = new EventBus[Unit]()

  /** A per-account write, with the guest detour in front of it — copied from `GameSetupPage.asReader`. With no session
    * `startPlay` cannot succeed, so a guest is minted first and the call retried against the session that creates.
    * Signed in, the mint is skipped entirely.
    */
  private def asReader[A](write: () => EventStream[Either[ApiError, A]]): EventStream[Either[ApiError, A]] = {
    readerVar.now() match {
      case Some(_) =>
        write()
      case None    =>
        ApiClient.createGuest.flatMapSwitch {
          case Right(response) =>
            AppState.setUser(response.user)
            noticeVar.set(Some(I18n.t(UiKeys.guestBannerHint)))
            write()
          case Left(err)       =>
            EventStream.fromValue(Left(err))
        }
    }
  }

  def render(): HtmlElement = {
    div(
      cls := "max-w-xl mx-auto",
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(noticeVar.signal),
      child.maybe <-- missingVar.signal.map(Option.when(_)(Alert.info(I18n.t(UiKeys.gameInstanceNotFound)))),
      // Built once, the moment the game finishes loading, and never again — see `renderGameCard`'s doc comment. A
      // rename only ever writes through `nameVar`, so it does not need to rebuild anything else under it.
      child.maybe <-- gameVar.signal
        .map(_.isDefined)
        .distinct
        .map(loaded => Option.when(loaded)(renderGameCard())),
      child.maybe <-- AppState.currentUserSignal.map(user => Option.when(user.exists(_.isGuest))(GuestBanner.render())),
      AppState.currentUserSignal --> readerVar.writer,
      loadBus.events.flatMapSwitch(_ => GameApiClient.get(slug)) -->
        Observer[Either[ApiError, GameDetail]] {
          case Right(detail) =>
            Var.set(gameVar -> Some(detail), nameVar -> detail.name, missingVar -> false, errorVar -> None)
            gameLoadedBus.emit(())
          case Left(err)     =>
            // A quiz that is not there is a different thing from a request that failed, and reads differently.
            if (err.status == 404)
              Var.set(missingVar -> true, errorVar -> None)
            else
              errorVar.set(Some(err.message))
        },
      startBus.events --> Observer[Unit](_ => Var.set(startingVar -> true, errorVar -> None)),
      startBus.events
        .withCurrentValueOf(
          swapDirectionVar.signal,
          wordLimitSignal,
          includeArticlesVar.signal,
          wordPreferenceVar.signal,
        )
        .flatMapSwitch { case (swap, limit, articles, preference) =>
          asReader(() => GameApiClient.startPlay(slug, swap, limit, articles, preference))
            .map(_.map(started => (started, swap, limit, articles, preference)))
        } -->
        Observer[Either[ApiError, (PlayStarted, Boolean, Option[Int], Boolean, WordPreference)]] {
          case Right((started, swap, limit, articles, preference)) =>
            // Only reachable once `renderStart`'s button exists, which itself only renders inside `renderGameCard` —
            // `gameVar` is always loaded by the time `startBus` can fire, same assumption `renderGameCard` makes.
            val game       = gameVar
              .now()
              .getOrElse(throw new IllegalStateException("startBus fired before the game finished loading"))
            val (src, tgt) =
              if (swap) (game.targetLanguage, game.sourceLanguage) else (game.sourceLanguage, game.targetLanguage)
            val variant    = GameVariantDto(src, tgt, limit, articles, preference)
            PendingPlay.set(started.playId, PlayHandoff(game.name, started.wordCount, variant))
            AppRouter.router.pushState(Page.GamePlay(slug, started.playId))
          case Left(err)                                           =>
            Var.set(startingVar -> false, errorVar -> Some(err.message))
        },
      previewTriggerStream --> Observer[(Boolean, WordPreference)](_ => previewLoadingVar.set(true)),
      previewTriggerStream
        .filterWith(gameVar.signal.map(_.isDefined))
        .flatMapSwitch { case (swap, preference) => GameApiClient.playSetup(slug, swap, preference) } -->
        Observer[Either[ApiError, List[GameSetupWord]]] {
          case Right(words) =>
            Var.set(previewWordsVar -> words, previewLoadingVar -> false)
            reversePreviewTriggerBus.emit(())
          case Left(err)    =>
            Var.set(previewLoadingVar -> false, errorVar -> Some(err.message))
            reversePreviewTriggerBus.emit(())
        },
      // The swap arrow's own `disabled` source — see [[reversePoolEmptyVar]]'s doc comment for why this is
      // sequenced off the primary preview settling rather than given its own `gameLoadedBus`-merged trigger.
      reversePreviewTriggerBus.events
        .sample(reversePreviewQuerySignal)
        .filterWith(gameVar.signal.map(_.isDefined))
        .flatMapSwitch { case (swap, preference) => GameApiClient.playSetup(slug, swap, preference) } -->
        Observer[Either[ApiError, List[GameSetupWord]]] {
          case Right(words) =>
            reversePoolEmptyVar.set(words.isEmpty)
          case Left(_)      =>
            // A failed probe should not lock the reader out of swapping — fail open, same as the button's default.
            reversePoolEmptyVar.set(false)
        },
      inlineRename.bindings(
        onSaved = Observer[GameDetail](detail => Var.set(nameVar -> detail.name, gameVar -> Some(detail))),
        onError = { err =>
          if (err.status == 403) {
            // A stale local hint, or the same account signed in elsewhere and the game changed owner-relevant state
            // since: either way, this browser does not get to keep offering a control that will not work.
            GameOwnership.forget(slug)
            inlineRename.cancel()
            Var.set(isOwnerVar -> false, errorVar -> Some(err.message))
          }
        },
      ),
      // Last, like every other page's initial load — see `WordsPage`'s or `AdminSystemPage`'s own placement: the
      // stream this triggers (`loadBus`, above) has to already have a subscriber when this fires, or the mount's own
      // reload is emitted to nobody and silently lost, leaving the quiz stuck loading forever.
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  /** The card's chrome — name, language pair, tags, share row, and the picker — built exactly once, the first time
    * [[render]] sees the game loaded. It reads [[gameVar]] with `.now()` rather than reactively: those fields never
    * change after load (a rename only ever touches [[nameVar]]).
    */
  private def renderGameCard(): HtmlElement = {
    val detail = gameVar
      .now()
      .getOrElse(
        throw new IllegalStateException("renderGameCard called before the game finished loading")
      )
    div(
      cls := "card bg-base-100 shadow mt-4",
      div(
        cls := "card-body",
        inlineRename.renderTitle(
          nameVar.signal,
          isOwnerVar.signal,
          I18n.t(UiKeys.gameInstanceRenameEdit),
          I18n.t(UiKeys.gameInstanceRenameLabel),
          "input text-xl",
          resultsLink(),
        ),
        p(
          cls   := "text-sm opacity-70",
          s"${Labels.language(detail.sourceLanguage)} → ${Labels.language(detail.targetLanguage)}",
        ),
        if (detail.tags.nonEmpty) {
          div(
            cls := "flex flex-wrap gap-2 mt-1",
            detail.tags.map(tag => {
              a(
                cls := "link",
                AppRouter.router.navigateTo(Page.TagDetail(tag.id)),
                tag.name,
              )
            }),
          )
        } else
          emptyNode,
        shareRow.render(),
        div(cls := "mt-4", renderStart()),
      ),
    )
  }

  private def pageUrl(): String = dom.window.location.href

  /** Owner-only, and only once the owner opted into `trackResults` at creation — see `GameRow.trackResults`'s doc
    * comment. Links to the results listing rather than opening it here, the same split `AllGamesPage`/`GameInstance`
    * already draw between "this game" and "a listing about it". Passed to `InlineRename.renderTitle` as `extra`, so it
    * is absent from the title only in edit mode, same as the pencil beside it.
    */
  private def resultsLink(): Modifier[HtmlElement] = {
    // Owner-only. Links to the results listing rather than opening it here, the same split
    // `AllGamesPage`/`GameInstance` already draw between "this game" and "a listing about it".
    child.maybe <-- isOwnerVar.signal.map { owner =>
      Option.when(owner)(
        a(
          cls := "btn btn-ghost btn-xs",
          AppRouter.router.navigateTo(Page.GameResults(slug)),
          I18n.t(UiKeys.gameInstanceViewResults),
        )
      )
    }
  }

  private def renderStart(): HtmlElement = {
    div(
      cls := "flex flex-col gap-4",
      renderDirectionSwap(),
      renderWordLimitControls(),
      renderIncludeArticlesControl(),
      renderPreferenceControl(),
      renderPreviewList(),
      button(
        cls := "btn btn-primary",
        typ := "button",
        disabled <-- startingVar.signal,
        I18n.t(UiKeys.gameInstanceStart),
        onClick.mapToUnit --> startBus.writer,
      ),
    )
  }

  /** `[source] <-> [target]` with no dropdowns — clicking the arrow flips [[swapDirectionVar]], which decides the
    * play's actual direction independent of the game's own stored one. Labels read from [[gameVar]] directly
    * (unaffected by the swap toggle itself — this is a display order, not a fetch), swapped in place when the toggle is
    * on.
    */
  private def renderDirectionSwap(): HtmlElement = {
    div(
      cls := "flex items-center gap-2",
      child <-- gameVar.signal.combineWith(swapDirectionVar.signal).map {
        case (Some(game), swapped) =>
          val (first, second) =
            if (swapped) (game.targetLanguage, game.sourceLanguage) else (game.sourceLanguage, game.targetLanguage)
          div(
            cls := "flex items-center gap-2",
            span(cls := "font-medium", Labels.language(first)),
            button(
              cls    := "btn btn-ghost btn-xs",
              typ    := "button",
              title  := I18n.t(UiKeys.gameInstanceDirectionSwap),
              // Disabled/no-op if the reverse direction's pool is empty — mirrors `swapDirection`'s `badRequest`
              // case being unreachable from the UI. See [[reversePoolEmptyVar]].
              disabled <-- reversePoolEmptyVar.signal,
              "⇄",
              onClick.mapToUnit --> Observer[Unit](_ => swapDirectionVar.update(!_)),
            ),
            span(cls := "font-medium", Labels.language(second)),
          )
        case (None, _)             =>
          emptyNode
      },
    )
  }

  /** Moved verbatim from the old `GameSetupPage`, just retargeted at [[selectAllVar]]/[[wordLimitTextVar]] here. */
  private def renderWordLimitControls(): HtmlElement = {
    div(
      cls := "flex flex-col gap-2",
      span(cls := "label-text text-xs", I18n.t(UiKeys.gameInstanceWordLimitLabel)),
      label(
        cls    := "flex items-center gap-2 cursor-pointer",
        input(
          typ    := "checkbox",
          cls    := "checkbox checkbox-sm",
          controlled(
            checked <-- selectAllVar.signal,
            onClick.mapToChecked --> Observer[Boolean] { on =>
              if (on) Var.set(selectAllVar -> true, wordLimitTextVar -> "") else selectAllVar.set(false)
            },
          ),
        ),
        span(cls := "label-text text-sm", I18n.t(UiKeys.gameInstanceWordLimitSelectAll)),
      ),
      label(
        cls    := "flex items-center gap-2",
        span(cls  := "label-text text-sm", I18n.t(UiKeys.gameInstanceWordLimitCount)),
        input(
          typ     := "number",
          minAttr := "1",
          cls     := "input input-sm w-24",
          disabled <-- selectAllVar.signal,
          controlled(
            value <-- wordLimitTextVar.signal,
            onInput.mapToValue --> Observer[String] { text =>
              Var.set(wordLimitTextVar -> text, selectAllVar -> false)
            },
          ),
        ),
      ),
    )
  }

  private def renderIncludeArticlesControl(): HtmlElement = {
    div(
      child.maybe <-- germanInvolvedSignal.map { involved =>
        Option.when(involved)(
          label(
            cls := "flex items-center gap-2 cursor-pointer",
            input(
              typ := "checkbox",
              cls := "checkbox checkbox-sm",
              controlled(checked <-- includeArticlesVar.signal, onClick.mapToChecked --> includeArticlesVar.writer),
            ),
            div(
              span(cls := "label-text text-sm", I18n.t(UiKeys.gameInstanceIncludeArticlesLabel)),
              p(cls    := "text-xs opacity-60", I18n.t(UiKeys.gameInstanceIncludeArticlesHint)),
            ),
          )
        )
      }
    )
  }

  private def renderPreferenceControl(): HtmlElement = {
    div(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(UiKeys.gameInstancePreferenceLabel)),
      select(
        cls    := "select select-sm w-full max-w-xs",
        option(value := "all", I18n.t(UiKeys.gameInstancePreferenceAll)),
        option(value := "unplayed", I18n.t(UiKeys.gameInstancePreferenceUnplayed)),
        option(value := "mostMistakes", I18n.t(UiKeys.gameInstancePreferenceMostMistakes)),
        controlled(
          value <-- wordPreferenceVar.signal.map(WordPreference.code),
          onChange.mapToValue --> wordPreferenceVar.writer.contramap[String](code =>
            WordPreference.fromString(code).getOrElse(WordPreference.All)
          ),
        ),
      ),
    )
  }

  /** The chosen direction/preference's eligible pool preview — same `TagWordsList` the setup screen uses, one screen
    * over. Collapsed by default here (unlike the setup screen): the player is about to be quizzed on these words, so
    * showing the list open by default would hand them the answers.
    */
  private def renderPreviewList(): HtmlElement = {
    TagWordsList.render(previewWordsVar.signal, previewLoadingVar.signal, collapsed = true)
  }
}
