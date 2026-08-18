package gathedge.frontend.components

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.api.ApiError
import gathedge.frontend.i18n.{CurrentLocale, I18n}
import gathedge.shared.domain.{Tag, WordLanguage}
import gathedge.shared.dto.{BulkUploadWordsRequest, BulkUploadWordsResponse}
import gathedge.shared.i18n.{MessageKeys, MessageRef, UiKeys}
import zio.json._

/** The "Bulk upload" modal on the Words page: pick a text file, and every word it finds already in the dictionary — or
  * not yet there — in each of the page's current source/target languages gets tagged into the collect tag.
  *
  * This is the one call in the frontend that steps outside `EndpointClient`/`WordApiClient`. Every other endpoint goes
  * through zio-http's `fetch`-based generated client, which exposes no upload-progress event; this dialog needs one, so
  * it speaks to `WordEndpoints.bulkUpload`'s own path directly with a plain `XMLHttpRequest`, sending and decoding the
  * same DTOs the generated client would.
  *
  * The progress bar's two halves mean different things. 0-50% is `xhr.upload.onprogress`, a real signal for how much of
  * the request body has gone out. 50-100% has no real signal to show — the whole match/create/tag pass happens
  * server-side inside this one request/response — so it creeps toward 90% on a timer and snaps to 100% once the
  * response lands.
  *
  * The target tag is never chosen here: it is the page's collect tag, resolved (or minted, for a reader who has none
  * yet) through [[WordCollect.collectTagOrDefault]], the same path a tick or a chip takes.
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
    case Succeeded(count: Int, tagName: String)
    case Failed(message: String)
  }

  private val openVar     = Var(false)
  private val phaseVar    = Var[Phase](Phase.Idle)
  private val phaseSignal = phaseVar.signal

  private val progressVar    = Var(0)
  private val progressSignal = progressVar.signal

  // Mirrored from the page's signals and the collect tag list, so a plain DOM callback (FileReader, XMLHttpRequest)
  // can read the value that was current when the upload started, without threading a Signal through every callback —
  // the same reason `WordCollect.readerVar` mirrors `AppState.currentUserSignal`.
  private val sourceLanguageVar = Var(WordLanguage.En)
  private val targetLanguageVar = Var(WordLanguage.En)
  private val tagsVar           = Var(List.empty[Tag])

  private val xhrVar = Var(Option.empty[dom.XMLHttpRequest])

  /** A file, once read as text — the only asynchronous step before the upload itself is `collectTagOrDefault`, which
    * this drives through `flatMapSwitch` the same way `WordCollect.writeTag`/`writePair` do.
    */
  private val contentBus = new EventBus[String]()

  private def isBusySignal: Signal[Boolean] = {
    phaseSignal.map {
      case Phase.Uploading | Phase.Processing => true
      case _                                  => false
    }.distinct
  }

  private def okDisabledSignal: Signal[Boolean] = {
    phaseSignal.map {
      case Phase.Succeeded(_, _) => false
      case _                     => true
    }.distinct
  }

  def open(): Unit = {
    Var.set(openVar -> true, phaseVar -> Phase.Idle, progressVar -> 0, xhrVar -> None)
  }

  /** Aborts an in-flight upload, if any, and closes without applying anything — the reader is free to reopen and try
    * again.
    */
  private def cancel(): Unit = {
    xhrVar.now().foreach(_.abort())
    Var.set(openVar -> false, xhrVar -> None, phaseVar -> Phase.Idle, progressVar -> 0)
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

  private def sendRequest(tagId: Long, content: String): Unit = {
    val xhr = new dom.XMLHttpRequest()
    xhr.open("POST", s"/api/words/tags/$tagId/bulk-upload")
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
        handleSuccess(xhr.responseText, tagId)
      else
        handleFailure(xhr.responseText)
    }
    xhr.onerror = { (_: dom.Event) =>
      Var.set(xhrVar -> None, progressVar -> 0, phaseVar -> Phase.Failed(I18n.t(MessageKeys.requestFailed)))
    }
    xhrVar.set(Some(xhr))
    xhr.send(BulkUploadWordsRequest(content, sourceLanguageVar.now(), targetLanguageVar.now()).toJson)
  }

  private def handleSuccess(body: String, tagId: Long): Unit = {
    body.fromJson[BulkUploadWordsResponse] match {
      case Right(response) =>
        val tagName = tagsVar.now().find(_.id == tagId).map(_.name).getOrElse("")
        Var.set(progressVar -> 100, phaseVar -> Phase.Succeeded(response.addedCount, tagName))
        collect.reloadTags()
        onUploaded.onNext(())
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
        cls   := "modal-box",
        h3(cls   := "font-bold text-lg", I18n.t(UiKeys.wordsBulkUploadTitle)),
        p(cls    := "text-sm opacity-70 py-2", I18n.t(UiKeys.wordsBulkUploadHint)),
        input(
          typ    := "file",
          cls    := "file-input file-input-bordered w-full",
          accept := ".txt,text/plain",
          disabled <-- isBusySignal,
          onChange --> Observer[dom.Event] { event =>
            val target = event.target.asInstanceOf[dom.html.Input]
            Option(target.files).filter(_.length > 0).map(_.item(0)).foreach(handleFile)
          },
        ),
        child.maybe <-- phaseSignal.map(renderStatus),
        div(
          cls    := "modal-action",
          button(
            cls := "btn",
            typ := "button",
            I18n.t(UiKeys.commonCancel),
            onClick.mapToUnit --> Observer[Unit](_ => cancel()),
          ),
          button(
            cls := "btn btn-primary",
            typ := "button",
            disabled <-- okDisabledSignal,
            I18n.t(UiKeys.commonOk),
            onClick.mapToUnit --> Observer[Unit](_ => close()),
          ),
        ),
      ),
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => cancel())),
      sourceLanguage --> sourceLanguageVar.writer,
      targetLanguage --> targetLanguageVar.writer,
      collect.tagsSignal --> tagsVar.writer,
      uploadStream --> Observer[Either[ApiError, (Long, String)]] {
        case Right((tagId, content)) =>
          sendRequest(tagId, content)
        case Left(err)               =>
          Var.set(progressVar -> 0, phaseVar -> Phase.Failed(err.message))
      },
      // The processing creep: ticks every 200ms, but only does anything while `Processing` is showing — the same
      // `filterWith` gate `WordCollect.bindings` uses to keep the tag list fetch signed-in-only.
      EventStream.periodic(intervalMs = 200).filterWith(phaseSignal.map(_ == Phase.Processing).distinct) -->
        Observer[Int](_ => progressVar.update(count => math.min(count + 2, 90))),
    )
  }

  private def renderStatus(phase: Phase): Option[HtmlElement] = {
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
}

object BulkUploadDialog {
  val maxBytes: Int = 2 * 1024 * 1024

  private final case class ErrorBody(error: MessageRef, message: String) derives JsonCodec
}
