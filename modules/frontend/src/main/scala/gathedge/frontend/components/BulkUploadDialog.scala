package gathedge.frontend.components

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.i18n.{CurrentLocale, I18n}
import gathedge.shared.domain.{Tag, Word, WordLanguage}
import gathedge.shared.dto.{
  BulkUploadConfirmResponse,
  BulkUploadManualPair,
  BulkUploadMatch,
  BulkUploadPreviewRequest,
  BulkUploadPreviewResponse,
}
import gathedge.shared.i18n.{MessageKeys, MessageRef, UiKeys}
import zio.json._

/** The "Bulk upload" modal on the Words page: pick a text file, review what the dictionary already knows about it, and
  * only then write anything.
  *
  * A file upload used to tag (and silently create) every token it found — including every unrelated substring a real
  * document is full of — which turned dictionary pollution into the ordinary case. This is a wizard instead: an upload
  * only ''previews'' matches (`WordEndpoints.bulkUploadPreview`, no write), the reader accepts or declines each match
  * and optionally links unmatched tokens by hand, and only the final `bulkUploadConfirm` call writes anything.
  *
  * This is the one call in the frontend that steps outside `EndpointClient`/`WordApiClient` — but only for the preview:
  * it is the call carrying the file's text, and `EndpointClient`'s `fetch`-based client exposes no upload-progress
  * event, so preview speaks to its endpoint directly with a plain `XMLHttpRequest`, sending and decoding the same DTOs
  * the generated client would. Confirm's body is small (ids and a handful of short strings), so it is an ordinary
  * `WordApiClient` call.
  *
  * The progress bar's two halves mean different things, both only during the preview: 0-50% is `xhr.upload.onprogress`,
  * a real signal for how much of the request body has gone out. 50-100% has no real signal to show — the whole match
  * pass happens server-side inside this one request/response — so it creeps toward 90% on a timer and snaps to 100%
  * once the response lands.
  *
  * The target tag is never chosen here: it is the page's collect tag, resolved (or minted, for a reader who has none
  * yet) through [[WordCollect.collectTagOrDefault]], the same path a tick or a chip takes — resolved once, at upload
  * time, and held for the confirm call at the end of the wizard.
  */
final class BulkUploadDialog(
  collect: WordCollect,
  sourceLanguage: Signal[WordLanguage],
  targetLanguage: Signal[WordLanguage],
  onUploaded: Observer[Unit],
) {

  private enum Phase {
    case Idle
    case Uploading
    case Processing
    case ReviewMatched
    case ReviewManual
    case Confirming
    case Succeeded(count: Int, tagName: String)
    case Failed(message: String)
  }

  private val openVar     = Var(false)
  private val phaseVar    = Var[Phase](Phase.Idle)
  private val phaseSignal = phaseVar.signal

  private val progressVar    = Var(0)
  private val progressSignal = progressVar.signal

  // Mirrored from the page's signals and the collect tag list, so a plain DOM callback (FileReader, XMLHttpRequest,
  // a column click) can read the value that was current without threading a Signal through every callback — the same
  // reason `WordCollect.readerVar` mirrors `AppState.currentUserSignal`.
  private val sourceLanguageVar = Var(WordLanguage.En)
  private val targetLanguageVar = Var(WordLanguage.En)
  private val tagsVar           = Var(List.empty[Tag])

  /** The tag the upload resolved into, at the moment the file was read — held for the confirm call at the end of the
    * wizard, since nothing else in the review steps asks for it again.
    */
  private val tagIdVar = Var(Option.empty[Long])

  private val xhrVar = Var(Option.empty[dom.XMLHttpRequest])

  private val previewVar = Var(Option.empty[BulkUploadPreviewResponse])

  /** Matched word ids the reader has accepted — seeded to every match the moment a preview lands, since accepting
    * everything found is the ordinary case; "decline" clears it rather than the reader unchecking each one.
    */
  private val acceptedVar = Var(Set.empty[Long])

  /** An unmatched token's assigned language, keyed by the token text. Absent means "skip" — the token was never
    * assigned one and so never reaches the manual-matching columns.
    */
  private val assignedLanguageVar = Var(Map.empty[String, WordLanguage])

  /** Confirmed manual pairs, `(sourceText, targetText)` — always one text in [[sourceLanguageVar]], one in
    * [[targetLanguageVar]], regardless of which column the reader clicked first.
    */
  private val manualPairsVar = Var(List.empty[(String, String)])

  /** The token last clicked in either manual-matching column, waiting for a click in the other column to complete a
    * pair. A second click in the *same* column replaces it rather than erroring — friendlier than making the reader
    * notice and undo a mistaken first click.
    */
  private val pendingPickVar = Var(Option.empty[String])

  /** A file, once read as text — the only asynchronous step before the preview request itself is `collectTagOrDefault`,
    * which this drives through `flatMapSwitch` the same way `WordCollect.writeTag`/`writePair` do.
    */
  private val contentBus = new EventBus[String]()

  private val confirmBus = new EventBus[Unit]()

  private def isBusySignal: Signal[Boolean] = {
    phaseSignal.map {
      case Phase.Uploading | Phase.Processing | Phase.Confirming => true
      case _                                                     => false
    }.distinct
  }

  private def showFileInputSignal: Signal[Boolean] = {
    phaseSignal.map {
      case Phase.Idle | Phase.Uploading | Phase.Processing | Phase.Failed(_) => true
      case _                                                                 => false
    }.distinct
  }

  private def okDisabledSignal: Signal[Boolean] = {
    phaseSignal.map {
      case Phase.Succeeded(_, _) => false
      case _                     => true
    }.distinct
  }

  def open(): Unit = {
    Var.set(
      openVar             -> true,
      phaseVar            -> Phase.Idle,
      progressVar         -> 0,
      xhrVar              -> None,
      previewVar          -> None,
      acceptedVar         -> Set.empty,
      assignedLanguageVar -> Map.empty,
      manualPairsVar      -> Nil,
      pendingPickVar      -> None,
      tagIdVar            -> None,
    )
  }

  /** Aborts an in-flight preview, if any, and closes without writing anything — the reader is free to reopen and try
    * again. Reachable from every phase before [[Phase.Succeeded]]; nothing this wizard does is committed before the
    * final confirm call.
    */
  private def cancel(): Unit = {
    xhrVar.now().foreach(_.abort())
    Var.set(
      openVar             -> false,
      xhrVar              -> None,
      phaseVar            -> Phase.Idle,
      progressVar         -> 0,
      previewVar          -> None,
      acceptedVar         -> Set.empty,
      assignedLanguageVar -> Map.empty,
      manualPairsVar      -> Nil,
      pendingPickVar      -> None,
      tagIdVar            -> None,
    )
  }

  /** Only reachable once a result has landed — see [[okDisabledSignal]] — so there is nothing left to undo here. */
  private def close(): Unit = openVar.set(false)

  private def handleFile(file: dom.File): Unit = {
    if (file.size > BulkUploadDialog.maxBytes) {
      phaseVar.set(Phase.Failed(I18n.t(UiKeys.wordsBulkUploadSizeError)))
    } else {
      Var.set(phaseVar -> Phase.Uploading, progressVar -> 0)
      val reader = new dom.FileReader()
      reader.onload = { (_: dom.Event) =>
        contentBus.emit(reader.result.asInstanceOf[String])
      }
      reader.onerror = { (_: dom.Event) =>
        phaseVar.set(Phase.Failed(I18n.t(MessageKeys.requestFailed)))
      }
      reader.readAsText(file)
    }
  }

  private def uploadStream: EventStream[Either[ApiError, (Long, String)]] = {
    contentBus.events.flatMapSwitch { content =>
      collect.collectTagOrDefault.map(_.map(tagId => (tagId, content)))
    }
  }

  private def sendPreviewRequest(tagId: Long, content: String): Unit = {
    val xhr = new dom.XMLHttpRequest()
    xhr.open("POST", s"/api/words/tags/$tagId/bulk-upload/preview")
    xhr.setRequestHeader("Content-Type", "application/json; charset=UTF-8")
    // The two headers `EndpointClient` sets globally on every call — CSRF, and the language transactional mail is
    // written in. Neither is automatic on a hand-built `XMLHttpRequest`; the session cookie is, since this is
    // same-origin in both dev (Vite proxy) and prod (nginx).
    xhr.setRequestHeader("X-Requested-With", "XMLHttpRequest")
    xhr.setRequestHeader("X-Locale", CurrentLocale.value.code)
    xhr.upload.onprogress = { (event: dom.ProgressEvent) =>
      if (event.lengthComputable) {
        progressVar.set(((event.loaded / event.total) * 50).toInt)
      }
    }
    xhr.upload.onload = { (_: dom.Event) =>
      Var.set(progressVar -> 50, phaseVar -> Phase.Processing)
    }
    xhr.onload = { (_: dom.Event) =>
      xhrVar.set(None)
      if (xhr.status >= 200 && xhr.status < 300)
        handlePreviewSuccess(xhr.responseText)
      else
        handleFailure(xhr.responseText)
    }
    xhr.onerror = { (_: dom.Event) =>
      Var.set(xhrVar -> None, progressVar -> 0, phaseVar -> Phase.Failed(I18n.t(MessageKeys.requestFailed)))
    }
    xhrVar.set(Some(xhr))
    xhr.send(BulkUploadPreviewRequest(content, sourceLanguageVar.now(), targetLanguageVar.now()).toJson)
  }

  private def handlePreviewSuccess(body: String): Unit = {
    body.fromJson[BulkUploadPreviewResponse] match {
      case Right(response) =>
        Var.set(
          previewVar  -> Some(response),
          // Accepting everything a preview found is the ordinary case; "decline all" is the one click for the
          // exception.
          acceptedVar -> response.matched.map(_.word.id).toSet,
          phaseVar    -> Phase.ReviewMatched,
          progressVar -> 100,
        )
      case Left(_)         =>
        Var.set(progressVar -> 0, phaseVar -> Phase.Failed(I18n.t(MessageKeys.requestFailed)))
    }
  }

  /** Decodes the same flat `{error, message, fieldErrors?}` shape every `ApiFailure` case encodes to (see its own
    * scaladoc) — a small local mirror rather than a shared one, since `ApiFailure` itself carries no `JsonCodec`: it is
    * the `Endpoint`/zio-schema stack's type, and this is the one call that bypasses that stack.
    */
  private def handleFailure(body: String): Unit = {
    val message = body.fromJson[BulkUploadDialog.ErrorBody] match {
      case Right(err) =>
        I18n.resolve(err.error)
      case Left(_)    =>
        I18n.t(MessageKeys.requestFailed)
    }
    Var.set(progressVar -> 0, phaseVar -> Phase.Failed(message))
  }

  private def startConfirm(): Unit = {
    phaseVar.set(Phase.Confirming)
    confirmBus.emit(())
  }

  /** Every id the reader's review actually touches: the matched words they kept checked, plus both sides of every pair
    * they linked by hand. What they unchecked or never assigned a language to is simply never sent.
    */
  private def confirmStream: EventStream[Either[ApiError, BulkUploadConfirmResponse]] = {
    confirmBus.events.flatMapSwitch { _ =>
      WordApiClient.bulkUploadConfirm(
        tagIdVar.now().getOrElse(0L),
        sourceLanguageVar.now(),
        targetLanguageVar.now(),
        acceptedVar.now().toList,
        manualPairsVar.now().map(BulkUploadManualPair.apply),
      )
    }
  }

  /** A token clicked in either manual-matching column. The second click of a pair — one assigned `sourceLanguageVar`,
    * the other `targetLanguageVar` — completes it regardless of which side came first; a second click in the *same*
    * column just replaces the pending pick.
    */
  private def handleColumnClick(token: String): Unit = {
    val language = assignedLanguageVar.now().get(token)
    val pending  = pendingPickVar.now()
    pending match {
      case Some(picked) if assignedLanguageVar.now().get(picked) != language =>
        val pair = {
          if (assignedLanguageVar.now().get(picked).contains(sourceLanguageVar.now()))
            (picked, token)
          else
            (token, picked)
        }
        manualPairsVar.update(_ :+ pair)
        pendingPickVar.set(None)
      case _                                                                 =>
        pendingPickVar.set(Some(token))
    }
  }

  def renderButton(): HtmlElement = {
    button(
      cls := "btn btn-sm",
      typ := "button",
      I18n.t(UiKeys.wordsBulkUploadButton),
      onClick.mapToUnit --> Observer[Unit](_ => open()),
    )
  }

  def renderModal(): HtmlElement = {
    div(
      cls := "modal",
      cls("modal-open") <-- openVar.signal,
      div(
        cls   := "modal-box w-full max-w-2xl",
        h3(cls := "font-bold text-lg", I18n.t(UiKeys.wordsBulkUploadTitle)),
        p(cls  := "text-sm opacity-70 py-2", I18n.t(UiKeys.wordsBulkUploadHint)),
        child.maybe <-- showFileInputSignal.map(Option.when(_)(renderFileInput())),
        child.maybe <-- phaseSignal.map(renderPhaseBody),
        div(
          cls  := "modal-action",
          children <-- phaseSignal.map(renderActions),
        ),
      ),
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => cancel())),
      sourceLanguage --> sourceLanguageVar.writer,
      targetLanguage --> targetLanguageVar.writer,
      collect.tagsSignal --> tagsVar.writer,
      uploadStream --> Observer[Either[ApiError, (Long, String)]] {
        case Right((tagId, content)) =>
          tagIdVar.set(Some(tagId))
          sendPreviewRequest(tagId, content)
        case Left(err)               =>
          Var.set(progressVar -> 0, phaseVar -> Phase.Failed(err.message))
      },
      confirmStream --> Observer[Either[ApiError, BulkUploadConfirmResponse]] {
        case Right(response) =>
          val tagName = tagsVar.now().find(tag => tagIdVar.now().contains(tag.id)).map(_.name).getOrElse("")
          Var.set(phaseVar -> Phase.Succeeded(response.addedCount, tagName))
          collect.reloadTags()
          onUploaded.onNext(())
        case Left(err)       =>
          phaseVar.set(Phase.Failed(err.message))
      },
      // The processing creep: ticks every 200ms, but only does anything while `Processing` is showing — the same
      // `filterWith` gate `WordCollect.bindings` uses to keep the tag list fetch signed-in-only.
      EventStream.periodic(intervalMs = 200).filterWith(phaseSignal.map(_ == Phase.Processing).distinct) -->
        Observer[Int](_ => progressVar.update(count => math.min(count + 2, 90))),
    )
  }

  private def renderFileInput(): HtmlElement = {
    input(
      typ    := "file",
      cls    := "file-input file-input-bordered w-full",
      accept := ".txt,text/plain",
      disabled <-- isBusySignal,
      onChange --> Observer[dom.Event] { event =>
        val target = event.target.asInstanceOf[dom.html.Input]
        Option(target.files).filter(_.length > 0).map(_.item(0)).foreach(handleFile)
      },
    )
  }

  private def renderPhaseBody(phase: Phase): Option[HtmlElement] = {
    phase match {
      case Phase.Idle                         =>
        None
      case Phase.Uploading | Phase.Processing =>
        Some(
          div(
            cls := "mt-3",
            progressTag(
              cls     := "progress progress-primary w-full",
              maxAttr := "100",
              value <-- progressSignal.map(_.toString),
            ),
          )
        )
      case Phase.ReviewMatched                =>
        previewVar.now().map(renderReviewMatched)
      case Phase.ReviewManual                 =>
        Some(renderReviewManual())
      case Phase.Confirming                   =>
        Some(div(cls := "mt-3 flex justify-center", span(cls := "loading loading-spinner loading-md")))
      case Phase.Succeeded(count, tagName)    =>
        Some(
          div(
            cls := "alert alert-success mt-3 text-sm",
            I18n.plural(UiKeys.wordsBulkUploadResult, count.toLong, tagName),
          )
        )
      case Phase.Failed(message)              =>
        Some(div(cls := "alert alert-error mt-3 text-sm", message))
    }
  }

  private def renderActions(phase: Phase): List[HtmlElement] = {
    val cancelButton = button(
      cls := "btn",
      typ := "button",
      I18n.t(UiKeys.commonCancel),
      onClick.mapToUnit --> Observer[Unit](_ => cancel()),
    )
    phase match {
      case Phase.Succeeded(_, _) =>
        List(
          button(
            cls := "btn btn-primary",
            typ := "button",
            I18n.t(UiKeys.commonOk),
            onClick.mapToUnit --> Observer[Unit](_ => close()),
          )
        )
      case Phase.ReviewMatched   =>
        List(cancelButton, renderNextOrConfirmButton())
      case Phase.ReviewManual    =>
        List(
          cancelButton,
          button(
            cls := "btn",
            typ := "button",
            I18n.t(UiKeys.wordsBulkUploadBack),
            onClick.mapToUnit --> Observer[Unit](_ => phaseVar.set(Phase.ReviewMatched)),
          ),
          button(
            cls := "btn btn-primary",
            typ := "button",
            I18n.t(UiKeys.wordsBulkUploadConfirmButton),
            onClick.mapToUnit --> Observer[Unit](_ => startConfirm()),
          ),
        )
      case _                     =>
        List(cancelButton)
    }
  }

  /** "Next" when the reader has assigned at least one unmatched token a language — there is a manual-matching step to
    * show — or straight to "Confirm" when there is nothing left to link by hand.
    */
  private def renderNextOrConfirmButton(): HtmlElement = {
    button(
      cls := "btn btn-primary",
      typ := "button",
      child.text <-- assignedLanguageVar.signal.map(_.nonEmpty).map { hasAssignments =>
        if (hasAssignments) I18n.t(UiKeys.wordsBulkUploadNext) else I18n.t(UiKeys.wordsBulkUploadConfirmButton)
      },
      onClick.mapToUnit --> Observer[Unit] { _ =>
        if (assignedLanguageVar.now().nonEmpty)
          phaseVar.set(Phase.ReviewManual)
        else
          startConfirm()
      },
    )
  }

  private def renderReviewMatched(preview: BulkUploadPreviewResponse): HtmlElement = {
    val matchedIds                            = preview.matched.map(_.word.id).toSet
    val (withTranslation, withoutTranslation) = preview.matched.partition(_.translations.nonEmpty)
    div(
      Option.when(preview.matched.nonEmpty)(renderMatchedControls(matchedIds)),
      Option.when(withTranslation.nonEmpty)(
        renderMatchedSubgroup(
          UiKeys.wordsBulkUploadMatchedWithTranslationHeading,
          withTranslation,
          UiKeys.wordsBulkUploadAcceptAllWithTranslation,
          UiKeys.wordsBulkUploadDeclineAllWithTranslation,
        )
      ),
      Option.when(withoutTranslation.nonEmpty)(
        renderMatchedSubgroup(
          UiKeys.wordsBulkUploadMatchedWithoutTranslationHeading,
          withoutTranslation,
          UiKeys.wordsBulkUploadAcceptAllWithoutTranslation,
          UiKeys.wordsBulkUploadDeclineAllWithoutTranslation,
        )
      ),
      Option.when(preview.unmatched.nonEmpty)(renderUnmatchedSection(preview.unmatched)),
    )
  }

  /** The whole matched list's Accept all / Decline all — every subgroup and both language columns at once. */
  private def renderMatchedControls(matchedIds: Set[Long]): HtmlElement = {
    div(
      cls := "flex items-center justify-between mt-3",
      h4(cls := "font-semibold text-sm", I18n.t(UiKeys.wordsBulkUploadMatchedHeading)),
      div(
        cls  := "flex gap-2",
        button(
          cls := "btn btn-xs",
          typ := "button",
          I18n.t(UiKeys.wordsBulkUploadAcceptAll),
          onClick.mapToUnit --> Observer[Unit](_ => acceptedVar.set(matchedIds)),
        ),
        button(
          cls := "btn btn-xs",
          typ := "button",
          I18n.t(UiKeys.wordsBulkUploadDeclineAll),
          onClick.mapToUnit --> Observer[Unit](_ => acceptedVar.set(Set.empty)),
        ),
      ),
    )
  }

  /** One matched subgroup — "with translation" or "without" — its own Accept all / Decline all scoped to just its ids,
    * and the source/target language split as two columns.
    */
  private def renderMatchedSubgroup(
    headingKey: String,
    matches: List[BulkUploadMatch],
    acceptKey: String,
    declineKey: String,
  ): HtmlElement = {
    val ids = matches.map(_.word.id).toSet
    div(
      cls := "mt-3",
      div(
        cls := "flex items-center justify-between",
        h5(cls := "font-semibold text-xs opacity-70", I18n.t(headingKey)),
        div(
          cls  := "flex gap-2",
          button(
            cls := "btn btn-xs",
            typ := "button",
            I18n.t(acceptKey),
            onClick.mapToUnit --> Observer[Unit](_ => acceptedVar.update(_ ++ ids)),
          ),
          button(
            cls := "btn btn-xs",
            typ := "button",
            I18n.t(declineKey),
            onClick.mapToUnit --> Observer[Unit](_ => acceptedVar.update(_ -- ids)),
          ),
        ),
      ),
      div(
        cls := "grid grid-cols-2 gap-2 mt-1",
        renderMatchedColumn(sourceLanguageVar.now(), matches.filter(_.word.language == sourceLanguageVar.now())),
        renderMatchedColumn(targetLanguageVar.now(), matches.filter(_.word.language == targetLanguageVar.now())),
      ),
    )
  }

  private def renderMatchedColumn(language: WordLanguage, matches: List[BulkUploadMatch]): HtmlElement = {
    div(
      h5(cls := "text-xs font-semibold", Labels.language(language)),
      div(
        cls  := "max-h-48 overflow-y-auto border border-base-300 rounded p-2 mt-1 flex flex-col gap-1",
        matches.map(renderMatchedRow),
      ),
    )
  }

  /** The word itself always came from the uploaded file — that is what a match is. Its translations came from the
    * dictionary and may never have appeared in the upload at all, so each carries its own badge saying so.
    */
  private def renderMatchedRow(m: BulkUploadMatch): HtmlElement = {
    label(
      cls := "flex items-start gap-2 py-1",
      input(
        typ := "checkbox",
        cls := "checkbox checkbox-sm mt-1",
        controlled(
          checked <-- acceptedVar.signal.map(_.contains(m.word.id)),
          onClick.mapToChecked --> Observer[Boolean] { on =>
            acceptedVar.update(ids => if (on) ids + m.word.id else ids - m.word.id)
          },
        ),
      ),
      div(
        div(
          cls := "flex items-center gap-1 flex-wrap",
          span(cls := "font-medium text-sm", Word.display(m.word)),
          span(cls := "badge badge-ghost badge-xs", I18n.t(UiKeys.wordsBulkUploadFromUpload)),
        ),
        if (m.translations.isEmpty)
          span(cls := "text-xs opacity-70", I18n.t(UiKeys.wordsBulkUploadNoTranslation))
        else {
          div(
            cls    := "flex items-center gap-1 flex-wrap mt-0.5",
            span(cls := "text-xs opacity-70", "→ " + m.translations.map(_.text).mkString(", ")),
            span(cls := "badge badge-ghost badge-xs", I18n.t(UiKeys.wordsBulkUploadFromDictionary)),
          )
        }),
    )
  }

  private def renderUnmatchedSection(unmatched: List[String]): HtmlElement = {
    div(
      cls := "mt-4",
      h4(cls := "font-semibold text-sm", I18n.t(UiKeys.wordsBulkUploadUnmatchedHeading)),
      div(
        cls  := "max-h-48 overflow-y-auto border border-warning bg-warning/10 rounded p-2 mt-1 flex flex-col gap-1",
        unmatched.map(renderUnmatchedRow),
      ),
    )
  }

  private def renderUnmatchedRow(token: String): HtmlElement = {
    div(
      cls := "flex items-center justify-between gap-2",
      span(cls := "text-sm", token),
      select(
        cls    := "select select-xs w-32",
        option(value := "", I18n.t(UiKeys.wordsBulkUploadLanguageSkip)),
        option(value := WordLanguage.code(sourceLanguageVar.now()), Labels.language(sourceLanguageVar.now())),
        option(value := WordLanguage.code(targetLanguageVar.now()), Labels.language(targetLanguageVar.now())),
        controlled(
          value <-- assignedLanguageVar.signal.map(_.get(token).map(WordLanguage.code).getOrElse("")),
          onChange.mapToValue --> Observer[String] { code =>
            assignedLanguageVar.update { assigned =>
              WordLanguage.fromString(code) match {
                case Some(language) => assigned.updated(token, language)
                case None           => assigned - token
              }
            }
          },
        ),
      ),
    )
  }

  private def renderReviewManual(): HtmlElement = {
    div(
      cls := "mt-3",
      h4(cls := "font-semibold text-sm", I18n.t(UiKeys.wordsBulkUploadManualHeading)),
      p(cls  := "text-xs opacity-70", I18n.t(UiKeys.wordsBulkUploadManualHint)),
      div(
        cls  := "grid grid-cols-2 gap-2 mt-2",
        renderManualColumn(sourceLanguageVar.now()),
        renderManualColumn(targetLanguageVar.now()),
      ),
      child.maybe <-- manualPairsVar.signal.map(pairs => Option.when(pairs.nonEmpty)(renderPairedList(pairs))),
    )
  }

  private def unpairedTokensSignal(language: WordLanguage): Signal[List[String]] = {
    assignedLanguageVar.signal
      .combineWith(manualPairsVar.signal)
      .map { case (assigned, pairs) =>
        val paired = pairs.flatMap { case (source, target) => List(source, target) }.toSet
        assigned.collect {
          case (token, tokenLanguage) if tokenLanguage == language && !paired.contains(token) =>
            token
        }.toList
      }
      .distinct
  }

  private def renderManualColumn(language: WordLanguage): HtmlElement = {
    div(
      h5(cls := "text-xs font-semibold", Labels.language(language)),
      div(
        cls  := "max-h-40 overflow-y-auto border border-base-300 rounded p-2 flex flex-col gap-1 mt-1",
        children <-- unpairedTokensSignal(language).map(_.map(renderPickableToken)),
      ),
    )
  }

  private def renderPickableToken(token: String): HtmlElement = {
    div(
      cls := "px-2 py-1 rounded cursor-pointer text-sm",
      cls("bg-primary/20") <-- pendingPickVar.signal.map(_.contains(token)),
      token,
      onClick.mapToUnit --> Observer[Unit](_ => handleColumnClick(token)),
    )
  }

  private def renderPairedList(pairs: List[(String, String)]): HtmlElement = {
    div(
      cls := "mt-3",
      h5(cls := "text-xs font-semibold", I18n.t(UiKeys.wordsBulkUploadPairedHeading)),
      div(
        cls  := "flex flex-col gap-1 mt-1",
        pairs.map { pair =>
          div(
            cls := "flex items-center justify-between text-sm",
            span(s"${pair._1} → ${pair._2}"),
            button(
              cls := "btn btn-xs btn-ghost",
              typ := "button",
              I18n.t(UiKeys.commonRemove),
              onClick.mapToUnit --> Observer[Unit](_ => manualPairsVar.update(_.filterNot(_ == pair))),
            ),
          )
        },
      ),
    )
  }
}

object BulkUploadDialog {
  val maxBytes: Int = 2 * 1024 * 1024

  private final case class ErrorBody(error: MessageRef, message: String) derives JsonCodec
}
