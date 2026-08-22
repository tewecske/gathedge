package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiClient, ApiError, GameApiClient}
import gathedge.frontend.components.{Alert, AppShell, GuestBanner, Labels}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.{AppState, GameOwnership}
import gathedge.shared.domain.{AnswerOutcome, User, WordLanguage, WordPreference}
import gathedge.shared.dto.{GameAnswerResult, GameDetail, GamePrompt, GameResults, GameSetupWord, PlayStarted}
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.{Failure, Success}

/** One quiz, played from its shared link (`/g/{slug}`).
  *
  * The initial `GET /api/games/{slug}` mints nobody — see `Page.GameInstance`'s doc comment — a visitor can read the
  * quiz's name and tags with no session at all. Starting a play is the first write the page makes, so it is where the
  * guest detour sits ([[asReader]], copied in spirit from `GameSetupPage`'s), and it sits there once: every later call
  * in the loop (`nextPrompt`, `submitAnswer`) runs against the session `startPlay` already established.
  *
  * One prompt is shown at a time, with a "3 of 12" progress line and nothing else about how the game is going —
  * `GameEndpoints.submitAnswer`'s doc comment is why: a player is never shown correctness mid-game, and that includes
  * the running score. Once the play is finished, the score and the full answer history become the point of the screen —
  * that is what `getResults` and the phase's `Finished` state are for.
  */
object GameInstancePage {

  /** Generates the QR modal's data URI for a URL. Threaded through as an ordinary parameter — the real caller
    * (`App`) passes `(text) => QRCode.toDataURL(text).toFuture`; a spec passes a stub — the same reason
    * `WordsPage.render` takes `recognizeImage: ImageOcr.Recognize` instead of calling `ImageOcr` itself: it keeps
    * the `qrcode` npm package's `@JSImport` out of this page's reachable graph under the test linker.
    * `Test / scalaJSLinkerConfig` is `NoModule` (see `build.sbt`), and [[gathedge.frontend.facades.QRCode]]'s
    * `@JSImport` is otherwise statically reachable from `render()` through `renderShareRow`/`openQr` regardless of
    * whether the QR button is ever actually clicked — DCE removes only genuinely unreachable code, not a branch a
    * spec merely never exercises at runtime — so any spec that renders this page at all would otherwise fail to
    * link, exactly as [[gathedge.frontend.ocr.ImageOcr.Recognize]]'s own doc comment explains for `tesseract.js`.
    */
  def render(slug: String, generateQr: String => Future[String]): HtmlElement = {
    AppShell.render(Page.GameInstance(slug), new GameInstancePage(slug, generateQr).render())
  }
}

private class GameInstancePage(slug: String, generateQr: String => Future[String]) {

  private sealed trait Phase

  private object Phase {
    case object NotStarted extends Phase
    case object Loading    extends Phase

    final case class Playing(prompt: GamePrompt, wordCount: Int) extends Phase

    case object Finished extends Phase
  }

  private val gameVar    = Var(Option.empty[GameDetail])
  private val missingVar = Var(false)

  /** The name shown in the header, separate from [[gameVar]] so a rename updates it without rebuilding the card — see
    * [[render]]'s comment on why the card itself is built once.
    */
  private val nameVar = Var("")

  private val errorVar: Var[Option[String]]  = Var(None)
  private val noticeVar: Var[Option[String]] = Var(None)

  private val playIdVar    = Var(Option.empty[Long])
  private val wordCountVar = Var(0)

  private val promptVar   = Var(Option.empty[GamePrompt])
  private val finishedVar = Var(false)

  private val resultsVar = Var(Option.empty[GameResults])

  private val answerTextVar = Var("")

  /** The direction-swap arrow's own state — `false` plays the game's stored direction, `true` reverses it for this
    * play only. See the design doc's "no dropdowns, just an arrow" direction control.
    */
  private val swapDirectionVar = Var(false)

  /** Mutually exclusive with [[wordLimitTextVar]], the same pattern `GameSetupPage` used before this control moved
    * here. Defaults to `true`: "use every eligible word". Named `wordLimitTextVar`, not `wordCountVar` — the page
    * already has an unrelated `wordCountVar: Var[Int]` (the play's own fixed word count, shown in the "3 of 12"
    * progress line); see the naming note further down.
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

  /** Whether *either* resolved direction of the current pair involves German — the swap arrow flips which language
    * is source, but German-either-way is symmetric, so this does not need to depend on [[swapDirectionVar]].
    */
  private val germanInvolvedSignal: Signal[Boolean] = {
    gameVar.signal.map(_.exists(g => g.sourceLanguage == WordLanguage.De || g.targetLanguage == WordLanguage.De))
  }

  private val wordPreferenceVar = Var[WordPreference](WordPreference.All)

  private val previewWordsVar    = Var(List.empty[GameSetupWord])
  private val previewLoadingVar  = Var(false)

  /** Fires once, right after the game successfully loads — see `render`'s `loadBus` wiring. Merged into
    * [[previewTriggerStream]] below so the preview populates on first entering the Play screen, not only after the
    * reader touches a control: `Signal.updates` (relied on for the reactive refetch-on-change half) excludes a
    * signal's starting value, so relying on it alone left `renderPreviewList` showing the "no eligible words"
    * message on entry even when eligible words existed. Same `EventStream.merge(signal.updates, bus.events.sample(signal))`
    * shape as `GameSetupPage.formRequests`/`AdminUsersPage.listRequests`, just triggered by the load succeeding
    * instead of an explicit reload button.
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

  /** Whether the *reverse* direction's pool is empty — the swap arrow (`renderDirectionSwap`) disables on this, per
    * the design doc: swapping into an empty pool would make `startPlay` fail its unreachable-from-the-UI
    * `badRequest` case. Fetched right after each current-direction preview settles ([[reversePreviewTriggerBus]]),
    * rather than on its own independent `gameLoadedBus`-merged trigger like [[previewTriggerStream]] — sequencing
    * it after the primary fetch means only the primary fetch's `asReader` ever has to mint a guest session on a
    * signed-out first visit, not both fetches racing to mint one each.
    */
  private val reversePoolEmptyVar = Var(false)

  private val reversePreviewQuerySignal: Signal[(Boolean, WordPreference)] = {
    swapDirectionVar.signal.map(!_).combineWith(wordPreferenceVar.signal).distinct
  }

  private val reversePreviewTriggerBus = new EventBus[Unit]()

  private val startingVar   = Var(false)
  private val submittingVar = Var(false)

  /** Whether this browser is the one that created the game — see `GameOwnership`'s doc comment on why that is a local
    * hint rather than something `GameDetail` states. A `Var` rather than a `val` so a 403 on rename (a stale hint, or
    * the same account signed in elsewhere) can turn the control off without a reload.
    */
  private val isOwnerVar = Var(GameOwnership.isOwned(slug))

  private val renameEditingVar                    = Var(false)
  private val renameTextVar                       = Var("")
  private val renameSubmittingVar                 = Var(false)
  private val renameErrorVar: Var[Option[String]] = Var(None)

  /** The QR modal — see `AppShell.confirmSignInOpenVar`'s doc comment for why a `Var[Boolean]` toggling a `modal-open`
    * class, not `HTMLDialogElement.showModal`: that API is unimplemented in jsdom, which the frontend specs run under.
    */
  private val qrOpenVar                         = Var(false)
  private val qrDataUriVar: Var[Option[String]] = Var(None)
  private val qrErrorVar: Var[Option[String]]   = Var(None)

  /** Mirrors who the reader is at the moment a request is made — same trick as `GameSetupPage.readerVar`, needed
    * because [[asReader]] reads it outside a subscription.
    */
  private val readerVar = Var(Option.empty[User])

  private val loadBus    = new EventBus[Unit]()
  private val startBus   = new EventBus[Unit]()
  private val nextBus    = new EventBus[Unit]()
  private val submitBus  = new EventBus[Unit]()
  private val resultsBus = new EventBus[Unit]()

  private val renameEditBus   = new EventBus[Unit]()
  private val renameCancelBus = new EventBus[Unit]()
  private val renameSaveBus   = new EventBus[Unit]()

  private val phaseSignal: Signal[Phase] = {
    playIdVar.signal
      .combineWith(finishedVar.signal, promptVar.signal, wordCountVar.signal)
      .map {
        case (None, _, _, _)              =>
          Phase.NotStarted
        case (Some(_), true, _, _)        =>
          Phase.Finished
        case (Some(_), false, Some(p), n) =>
          Phase.Playing(p, n)
        case (Some(_), false, None, _)    =>
          Phase.Loading
      }
      .distinct
  }

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
      // rename only ever writes through `nameVar`, so it cannot remount the phase in progress underneath it.
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
      startBus.events -->
        Observer[Unit] { _ =>
          // Also clears the previous play's finish state AND `playIdVar`, so this doubles as "Play again" that
          // returns to the variant picker rather than silently replaying the previous variant — see `renderResults`.
          Var.set(
            startingVar -> true,
            errorVar    -> None,
            finishedVar -> false,
            resultsVar  -> None,
            promptVar   -> None,
            playIdVar   -> None,
          )
        },
      startBus.events
        .withCurrentValueOf(swapDirectionVar.signal, wordLimitSignal, includeArticlesVar.signal, wordPreferenceVar.signal)
        .flatMapSwitch { case (swap, limit, articles, preference) =>
          asReader(() => GameApiClient.startPlay(slug, swap, limit, articles, preference))
        } -->
        Observer[Either[ApiError, PlayStarted]] {
          case Right(started) =>
            // `wordCountVar` here is the pre-existing play-progress var (see the naming note above), set from the
            // server's actual sampled count — unrelated to this task's own `wordLimitTextVar`.
            Var.set(playIdVar -> Some(started.playId), wordCountVar -> started.wordCount, startingVar -> false)
            nextBus.emit(())
          case Left(err)      =>
            Var.set(startingVar -> false, errorVar -> Some(err.message))
        },
      previewTriggerStream --> Observer[(Boolean, WordPreference)](_ => previewLoadingVar.set(true)),
      previewTriggerStream
        .filterWith(gameVar.signal.map(_.isDefined))
        .flatMapSwitch { case (swap, preference) => asReader(() => GameApiClient.playSetup(slug, swap, preference)) } -->
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
        .flatMapSwitch { case (swap, preference) => asReader(() => GameApiClient.playSetup(slug, swap, preference)) } -->
        Observer[Either[ApiError, List[GameSetupWord]]] {
          case Right(words) =>
            reversePoolEmptyVar.set(words.isEmpty)
          case Left(_)      =>
            // A failed probe should not lock the reader out of swapping — fail open, same as the button's default.
            reversePoolEmptyVar.set(false)
        },
      nextPromptStream --> Observer[Either[ApiError, GamePrompt]] {
        case Right(prompt) =>
          if (prompt.finished) {
            Var.set(finishedVar -> true, promptVar -> None, submittingVar -> false)
            resultsBus.emit(())
          } else {
            Var.set(promptVar -> Some(prompt), answerTextVar -> "", submittingVar -> false)
          }
        case Left(err)     =>
          Var.set(submittingVar -> false, errorVar -> Some(err.message))
      },
      submitStream --> Observer[Either[ApiError, Unit]] {
        case Right(_)  =>
          nextBus.emit(())
        case Left(err) =>
          Var.set(submittingVar -> false, errorVar -> Some(err.message))
      },
      resultsStream --> Observer[Either[ApiError, GameResults]] {
        case Right(results) =>
          resultsVar.set(Some(results))
        case Left(err)      =>
          errorVar.set(Some(err.message))
      },
      renameEditBus.events -->
        Observer[Unit](_ => Var.set(renameTextVar -> nameVar.now(), renameEditingVar -> true, renameErrorVar -> None)),
      renameCancelBus.events -->
        Observer[Unit](_ => Var.set(renameEditingVar -> false, renameErrorVar -> None)),
      renameStream --> Observer[Either[ApiError, GameDetail]] {
        case Right(detail) =>
          Var.set(
            nameVar             -> detail.name,
            gameVar             -> Some(detail),
            renameEditingVar    -> false,
            renameSubmittingVar -> false,
            renameErrorVar      -> None,
          )
        case Left(err)     =>
          if (err.status == 403) {
            // A stale local hint, or the same account signed in elsewhere and the game changed owner-relevant state
            // since: either way, this browser does not get to keep offering a control that will not work.
            GameOwnership.forget(slug)
            Var.set(
              isOwnerVar          -> false,
              renameEditingVar    -> false,
              renameSubmittingVar -> false,
              errorVar            -> Some(err.message),
            )
          } else {
            Var.set(
              renameSubmittingVar -> false,
              renameErrorVar      -> Some(err.fieldErrors.getOrElse("name", err.message)),
            )
          }
      },
      // Last, like every other page's initial load — see `WordsPage`'s or `AdminSystemPage`'s own placement: the
      // stream this triggers (`loadBus`, above) has to already have a subscriber when this fires, or the mount's own
      // reload is emitted to nobody and silently lost, leaving the quiz stuck loading forever.
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def nextPromptStream: EventStream[Either[ApiError, GamePrompt]] = {
    nextBus.events
      .map(_ => playIdVar.now())
      .collect { case Some(playId) => playId }
      .flatMapSwitch(playId => GameApiClient.nextPrompt(playId))
  }

  private def submitStream: EventStream[Either[ApiError, Unit]] = {
    submitBus.events
      .filterWith(submittingVar.signal.not)
      .map(_ => (playIdVar.now(), promptVar.now().flatMap(_.wordId), answerTextVar.now().trim))
      .collect { case (Some(playId), Some(wordId), text) if text.nonEmpty => (playId, wordId, text) }
      .flatMapSwitch { case (playId, wordId, text) =>
        submittingVar.set(true)
        GameApiClient.submitAnswer(playId, wordId, text)
      }
  }

  private def resultsStream: EventStream[Either[ApiError, GameResults]] = {
    resultsBus.events
      .map(_ => playIdVar.now())
      .collect { case Some(playId) => playId }
      .flatMapSwitch(playId => GameApiClient.getResults(playId))
  }

  private def renameStream: EventStream[Either[ApiError, GameDetail]] = {
    renameSaveBus.events
      .filterWith(renameSubmittingVar.signal.not)
      .map(_ => renameTextVar.now().trim)
      .collect { case text if text.nonEmpty => text }
      .flatMapSwitch { text =>
        renameSubmittingVar.set(true)
        GameApiClient.rename(slug, text)
      }
  }

  /** The card's chrome — name, language pair, tags — built exactly once, the first time [[render]] sees the game
    * loaded. It reads [[gameVar]] with `.now()` rather than reactively: those fields never change after load (a rename
    * only ever touches [[nameVar]]), and rebuilding this on every signal tick would remount whatever `phaseSignal` is
    * showing underneath it — losing an in-progress answer to a rename made mid-play.
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
        renderNameHeader(),
        p(
          cls   := "text-sm opacity-70",
          s"${Labels.language(detail.sourceLanguage)} → ${Labels.language(detail.targetLanguage)}",
        ),
        if (detail.tagNames.nonEmpty)
          div(cls := "flex flex-wrap gap-2 mt-1", detail.tagNames.map(name => span(cls := "badge badge-ghost", name)))
        else
          emptyNode,
        renderShareRow(),
        div(cls := "mt-4", child <-- phaseSignal.map(renderPhase)),
      ),
    )
  }

  /** Copy-link, Web Share and QR code — all three act on this page's own URL, which is the shared link itself: nothing
    * here needs the game's slug or id, only `dom.window.location.href`. Kept out of [[renderNameHeader]] and its rename
    * control on purpose — sharing is not an owner-only action, unlike the pencil next to the name.
    */
  private def renderShareRow(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-center gap-2 mt-3",
      button(
        cls := "btn btn-ghost btn-xs",
        typ := "button",
        I18n.t(UiKeys.gameInstanceShareCopyLink),
        onClick.mapToUnit --> Observer[Unit](_ => copyLink()),
      ),
      button(
        cls := "btn btn-ghost btn-xs",
        typ := "button",
        I18n.t(UiKeys.gameInstanceShareButton),
        onClick.mapToUnit --> Observer[Unit](_ => share()),
      ),
      button(
        cls := "btn btn-ghost btn-xs",
        typ := "button",
        I18n.t(UiKeys.gameInstanceShareQrGenerate),
        onClick.mapToUnit --> Observer[Unit](_ => openQr()),
      ),
      renderQrModal(),
    )
  }

  private def pageUrl(): String = dom.window.location.href

  /** Feature-checked, like `AppShell.copyToClipboard`/`GuestBanner.copyToClipboard`: the Clipboard API is absent in
    * jsdom (which the frontend specs run under) and on older browsers, and a copy button that throws would take the
    * page with it.
    */
  private def copyToClipboard(value: String): Boolean = {
    try {
      val clipboard = dom.window.navigator.asInstanceOf[js.Dynamic].clipboard
      if (!js.isUndefined(clipboard)) {
        clipboard.writeText(value)
        true
      } else {
        false
      }
    } catch { case _: Throwable => false }
  }

  private def copyLink(): Unit = {
    if (copyToClipboard(pageUrl())) {
      noticeVar.set(Some(I18n.t(UiKeys.gameInstanceShareCopied)))
    }
  }

  /** `navigator.share` first, falling back to [[copyLink]] when the API is absent — mobile browsers overwhelmingly have
    * it, desktop ones mostly still don't. Feature-detected the same way as the clipboard call above rather than
    * declared against a `dom` facade, since Scala.js's own DOM bindings do not have it either.
    *
    * The share sheet's own promise is not awaited: it rejects on a plain user cancel (`AbortError`) exactly as often as
    * on a real failure, and there is nothing more useful to do with either outcome than nothing.
    */
  private def share(): Unit = {
    val nav = dom.window.navigator.asInstanceOf[js.Dynamic]
    if (js.typeOf(nav.share) != "undefined") {
      try {
        nav.share(js.Dynamic.literal(title = nameVar.now(), url = pageUrl()))
        ()
      } catch { case _: Throwable => copyLink() }
    } else {
      copyLink()
    }
  }

  /** Opens the modal immediately and fills it in once the QR code is ready, rather than generating it up front on page
    * load — a visitor who never asks for the code never pays for it. Generated once per page load and cached in
    * [[qrDataUriVar]]: the URL it encodes cannot change under this page, so a second click has nothing new to render.
    */
  private def openQr(): Unit = {
    Var.set(qrOpenVar -> true, qrErrorVar -> None)
    if (qrDataUriVar.now().isEmpty) {
      generateQr(pageUrl()).onComplete {
        case Success(uri) =>
          qrDataUriVar.set(Some(uri))
        case Failure(_)   =>
          qrErrorVar.set(Some(I18n.t(UiKeys.gameInstanceShareQrError)))
      }
    }
  }

  /** Copied from `AppShell.renderSignInConfirmModal`'s pattern exactly: a `div.modal` with `modal-open` toggled off a
    * `Var[Boolean]`, not `HTMLDialogElement.showModal` — that call is unimplemented in jsdom, which the frontend specs
    * run under.
    */
  private def renderQrModal(): HtmlElement = {
    div(
      cls := "modal",
      cls("modal-open") <-- qrOpenVar.signal,
      div(
        cls   := "modal-box",
        h3(cls := "font-semibold text-lg", I18n.t(UiKeys.gameInstanceShareQrTitle)),
        div(
          cls  := "flex justify-center py-6",
          child <-- qrDataUriVar.signal.map {
            case Some(uri) =>
              img(cls := "w-48 h-48", src := uri, alt := I18n.t(UiKeys.gameInstanceShareQrAlt))
            case None      =>
              span(cls := "loading loading-spinner")
          },
        ),
        child.maybe <-- qrErrorVar.signal.map(_.map(msg => p(cls := "text-error text-sm text-center", msg))),
        div(
          cls  := "modal-action",
          button(
            cls := "btn",
            typ := "button",
            I18n.t(UiKeys.gameInstanceShareQrClose),
            onClick.mapToUnit --> Observer[Unit](_ => qrOpenVar.set(false)),
          ),
        ),
      ),
      // Closes on an outside click, same as `AppShell.renderSignInConfirmModal`'s `modal-backdrop`.
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => qrOpenVar.set(false))),
    )
  }

  private def renderNameHeader(): HtmlElement = {
    div(
      child <-- renameEditingVar.signal.map {
        case true  =>
          renderRenameForm()
        case false =>
          renderNameDisplay()
      }
    )
  }

  private def renderNameDisplay(): HtmlElement = {
    div(
      cls := "flex items-center gap-2 flex-wrap",
      h1(cls := "card-title text-2xl", child.text <-- nameVar.signal),
      child.maybe <-- isOwnerVar.signal.map { owner =>
        Option.when(owner)(
          button(
            cls   := "btn btn-ghost btn-xs",
            typ   := "button",
            title := I18n.t(UiKeys.gameInstanceRenameEdit),
            "✎",
            onClick.mapToUnit --> renameEditBus.writer,
          )
        )
      },
      // Owner-only, and only once the owner opted into `trackResults` at creation — see `GameRow.trackResults`'s doc
      // comment. Links to the results listing rather than opening it here, the same split `MyGamesPage`/`GameInstance`
      // already draw between "this game" and "a listing about it".
      child.maybe <-- isOwnerVar.signal.combineWith(gameVar.signal).map { case (owner, game) =>
        Option.when(owner && game.exists(_.trackResults))(
          a(
            cls := "btn btn-ghost btn-xs",
            AppRouter.router.navigateTo(Page.GameResults(slug)),
            I18n.t(UiKeys.gameInstanceViewResults),
          )
        )
      },
    )
  }

  private def renderRenameForm(): HtmlElement = {
    div(
      form(
        cls        := "flex items-center gap-2 flex-wrap",
        noValidate := true,
        onSubmit.preventDefault.mapToUnit --> renameSaveBus.writer,
        label(
          cls         := "sr-only",
          forId       := "gameInstanceRenameInput",
          I18n.t(UiKeys.gameInstanceRenameLabel),
        ),
        input(
          idAttr      := "gameInstanceRenameInput",
          cls         := "input input-sm",
          placeholder := I18n.t(UiKeys.gameInstanceRenameLabel),
          controlled(value <-- renameTextVar.signal, onInput.mapToValue --> renameTextVar.writer),
        ),
        button(
          cls         := "btn btn-sm btn-primary",
          typ         := "submit",
          disabled <-- renameSubmittingVar.signal,
          I18n.t(UiKeys.commonSave),
        ),
        button(
          cls         := "btn btn-sm btn-ghost",
          typ         := "button",
          disabled <-- renameSubmittingVar.signal,
          I18n.t(UiKeys.commonCancel),
          onClick.mapToUnit --> renameCancelBus.writer,
        ),
      ),
      child.maybe <-- renameErrorVar.signal.map(_.map(msg => p(cls := "text-error text-xs mt-1", msg))),
    )
  }

  private def renderPhase(phase: Phase): HtmlElement = {
    phase match {
      case Phase.NotStarted          =>
        renderStart()
      case Phase.Loading             =>
        span(cls := "loading loading-spinner")
      case Phase.Playing(prompt, wc) =>
        renderPrompt(prompt, wc)
      case Phase.Finished            =>
        renderFinished()
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
    * (unaffected by the swap toggle itself — this is a display order, not a fetch), swapped in place when the
    * toggle is on.
    */
  private def renderDirectionSwap(): HtmlElement = {
    div(
      cls := "flex items-center gap-2",
      child <-- gameVar.signal.combineWith(swapDirectionVar.signal).map {
        case (Some(game), swapped) =>
          val (first, second) = if (swapped) (game.targetLanguage, game.sourceLanguage) else (game.sourceLanguage, game.targetLanguage)
          div(
            cls := "flex items-center gap-2",
            span(cls := "font-medium", Labels.language(first)),
            button(
              cls   := "btn btn-ghost btn-xs",
              typ   := "button",
              title := I18n.t(UiKeys.gameInstanceDirectionSwap),
              // Disabled/no-op if the reverse direction's pool is empty — mirrors `swapDirection`'s `badRequest`
              // case being unreachable from the UI. See [[reversePoolEmptyVar]].
              disabled <-- reversePoolEmptyVar.signal,
              "⇄",
              onClick.mapToUnit --> Observer[Unit](_ => swapDirectionVar.update(!_)),
            ),
            span(cls := "font-medium", Labels.language(second)),
          )
        case (None, _)              =>
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
      },
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

  /** The chosen direction/preference's eligible pool preview — same shape as `GameSetupPage.renderWordsList`, one
    * screen over.
    */
  private def renderPreviewList(): HtmlElement = {
    div(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(UiKeys.gameInstanceWordsHeading)),
      span(
        cls    := "label-text text-sm opacity-70",
        child.text <-- previewWordsVar.signal.map(words => I18n.plural(UiKeys.gameInstanceWordsCount, words.size.toLong)),
      ),
      child.maybe <-- previewWordsVar.signal.combineWith(previewLoadingVar.signal).map { case (words, loading) =>
        Option.when(words.isEmpty && !loading)(p(cls := "text-sm opacity-60", I18n.t(UiKeys.gameInstanceWordsEmpty)))
      },
    )
  }

  private def renderPrompt(prompt: GamePrompt, wordCount: Int): HtmlElement = {
    div(
      p(
        cls        := "text-sm opacity-70",
        I18n.t(UiKeys.gameInstanceProgress, prompt.position.getOrElse(0), wordCount),
      ),
      h2(cls       := "text-xl font-semibold my-2", prompt.wordText.getOrElse("")),
      form(
        cls        := "flex flex-wrap items-end gap-2",
        noValidate := true,
        onSubmit.preventDefault.mapToUnit --> submitBus.writer,
        label(
          cls := "form-control grow",
          span(cls      := "label-text text-xs", I18n.t(UiKeys.gameInstanceAnswerLabel)),
          input(
            cls         := "input input-sm w-full",
            placeholder := I18n.t(UiKeys.gameInstanceAnswerPlaceholder),
            controlled(value <-- answerTextVar.signal, onInput.mapToValue --> answerTextVar.writer),
            // `renderPrompt` is freshly mounted for every new `GamePrompt` (see `phaseSignal`'s `.distinct`), so
            // focusing on mount both auto-focuses on first load and re-focuses on every new word.
            onMountFocus,
          ),
        ),
        button(
          cls := "btn btn-sm btn-primary",
          typ := "submit",
          disabled <-- submittingVar.signal,
          I18n.t(UiKeys.gameInstanceSubmit),
        ),
      ),
    )
  }

  private def renderFinished(): HtmlElement = {
    div(child <-- resultsVar.signal.map {
      case None          =>
        span(cls := "loading loading-spinner")
      case Some(results) =>
        renderResults(results)
    })
  }

  private def renderResults(results: GameResults): HtmlElement = {
    div(
      cls := "flex flex-col gap-3",
      p(cls := "font-semibold text-lg", I18n.t(UiKeys.gameInstanceFinishedTitle)),
      p(cls := "text-xl font-bold", I18n.t(UiKeys.gameInstanceScore, results.score, results.maxScore)),
      p(
        cls := "text-sm opacity-70",
        s"${Labels.language(results.variant.sourceLanguage)} → ${Labels.language(results.variant.targetLanguage)} · ${Labels.wordPreference(results.variant.wordPreference)}",
      ),
      renderResultsTable(results.answers),
      div(
        cls := "flex flex-wrap items-center gap-3 mt-1",
        button(
          cls := "btn btn-primary btn-sm",
          typ := "button",
          disabled <-- startingVar.signal,
          I18n.t(UiKeys.gameInstancePlayAgain),
          onClick.mapToUnit --> startBus.writer,
        ),
        a(
          cls := "link link-hover text-sm",
          AppRouter.router.navigateTo(Page.Games),
          I18n.t(UiKeys.gameInstanceBackToGames),
        ),
      ),
    )
  }

  private def renderResultsTable(answers: List[GameAnswerResult]): HtmlElement = {
    div(
      cls := "overflow-x-auto",
      table(
        cls := "table table-sm",
        thead(
          tr(
            th(I18n.t(UiKeys.gameInstanceResultsWordCol)),
            th(I18n.t(UiKeys.gameInstanceResultsExpectedCol)),
            th(I18n.t(UiKeys.gameInstanceResultsAnswerCol)),
            th(I18n.t(UiKeys.gameInstanceResultsOutcomeCol)),
          )
        ),
        tbody(answers.map(renderResultRow)),
      ),
    )
  }

  private def renderResultRow(answer: GameAnswerResult): HtmlElement = {
    tr(
      cls := "hover",
      td(answer.wordText),
      td(answer.expectedText),
      td(answer.givenText),
      td(renderOutcomeBadge(answer.outcome)),
    )
  }

  /** Mistakes (typo/wrong) get a warning/error badge, matching `AdminUserDiagnostics.renderOutcome`'s style for
    * `login_attempts.outcome` — the same "outcome of one attempt, in a table" shape.
    */
  private def renderOutcomeBadge(outcome: AnswerOutcome): HtmlElement = {
    val style = outcome match {
      case AnswerOutcome.Correct =>
        "badge-success badge-soft"
      case AnswerOutcome.Typo    =>
        "badge-warning"
      case AnswerOutcome.Wrong   =>
        "badge-error"
    }
    span(cls := s"badge $style", Labels.gameOutcome(outcome))
  }
}
