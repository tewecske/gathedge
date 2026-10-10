package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{AdminApiClient, ApiError}
import gathedge.frontend.components.{AdminSubmenu, Alert, AppShell, ConfirmDialog, Formats}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.Page
import gathedge.shared.dto.{WordFormAnomaly, WordFormScanStatus}
import gathedge.shared.i18n.UiKeys

/** The `word_forms` fan-out diagnostic: a word wrongly linked as an inflected form of far more lemmas than a real
  * inflection table ever has (the shape of the bug where a mislabeled wiktextract tag turned `haben`/`sein` into a
  * "form" of hundreds of German verbs). A report plus a delete action, the same shape `AdminSystemPage`'s maintenance
  * card is, one row at a time instead of one button.
  *
  * Over the whole dictionary the scan outlasts a request, so it runs on the server in the background. The page starts
  * it with a button, polls its status while it runs, and draws the result once it is done. Opening the page only reads
  * the status: a scan started earlier, from this tab or another, shows its progress or its result.
  */
object AdminWordFormsPage {
  def render(): HtmlElement = AppShell.render(Page.AdminWordForms, new AdminWordFormsPage().render())

  /** How long the page waits between two status reads while a scan runs. */
  private[pages] val pollIntervalMs: Int = 1500
}

private class AdminWordFormsPage {

  private val confirmDialog = new ConfirmDialog()

  private val statusVar: Var[Option[WordFormScanStatus]] = Var(None)

  private val errorVar: Var[Option[String]] = Var(None)
  private val infoVar: Var[Option[String]]  = Var(None)
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal

  private val runningSignal = statusVar.signal.map(_.exists(_.running))

  private val loadBus   = new EventBus[Unit]()
  private val pollBus   = new EventBus[Unit]()
  private val startBus  = new EventBus[Unit]()
  private val cancelBus = new EventBus[Unit]()
  private val deleteBus = new EventBus[WordFormAnomaly]()

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.adminWordFormsTitle)),
      AdminSubmenu.render(Page.AdminWordForms),
      confirmDialog.render(),
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(infoVar.signal),
      p(cls  := "text-sm opacity-60 mb-4", I18n.t(UiKeys.adminWordFormsHint)),
      child <-- statusVar.signal.map(renderStatus),
      // Every call that answers a status goes through one observer, so a poll, a start and a cancel cannot disagree
      // about what the page draws. A running scan schedules the next poll; unmounting the page drops it.
      EventStream
        .merge(
          loadBus.events.flatMapSwitch(_ => AdminApiClient.wordFormScanStatus),
          startBus.events.filterWith(inFlightSignal.not).flatMapSwitch(_ => AdminApiClient.startWordFormScan),
          cancelBus.events.filterWith(inFlightSignal.not).flatMapSwitch(_ => AdminApiClient.cancelWordFormScan),
        ) -->
        Observer[Either[ApiError, WordFormScanStatus]] {
          case Right(status) =>
            Var.set(statusVar -> Some(status), inFlightVar -> false, errorVar -> None)
            if (status.running) {
              pollBus.emit(())
            }
          case Left(err)     =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      pollBus.events.delay(AdminWordFormsPage.pollIntervalMs) --> loadBus.writer,
      startBus.events.filterWith(inFlightSignal.not) --> Observer[Unit](_ => started()),
      cancelBus.events.filterWith(inFlightSignal.not) --> Observer[Unit](_ => started()),
      deleteBus.events.filterWith(inFlightSignal.not) --> Observer[WordFormAnomaly](_ => started()),
      deleteBus.events
        .filterWith(inFlightSignal.not)
        .flatMapSwitch(anomaly => AdminApiClient.deleteWordFormAnomaly(anomaly.formWordId, anomaly.relation)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(inFlightVar -> false, errorVar -> None, infoVar -> Some(I18n.t(UiKeys.adminWordFormsDeleted)))
            loadBus.emit(())
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message), infoVar -> None)
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def started(): Unit = {
    Var.set(inFlightVar -> true, errorVar -> None, infoVar -> None)
  }

  private def renderStatus(status: Option[WordFormScanStatus]): HtmlElement = {
    status match {
      case None                             =>
        span(cls := "loading loading-spinner loading-sm")
      case Some(current) if current.running =>
        renderRunning(current)
      case Some(current)                    =>
        div(
          div(
            cls := "flex flex-wrap items-center gap-4 mb-4",
            startButton(),
            current.state match {
              case WordFormScanStatus.Done      =>
                span(
                  cls := "text-sm opacity-60",
                  I18n.t(UiKeys.adminWordFormsFinishedAt, current.finishedAt.map(Formats.dateTime).getOrElse("")),
                )
              case WordFormScanStatus.Cancelled =>
                span(cls := "text-sm opacity-60", I18n.t(UiKeys.adminWordFormsCancelled))
              case WordFormScanStatus.Failed    =>
                span(cls := "text-sm text-error", I18n.t(UiKeys.adminWordFormsFailed))
              case _                            =>
                span(cls := "text-sm opacity-60", I18n.t(UiKeys.adminWordFormsNotScanned))
            },
          ),
          if (current.state == WordFormScanStatus.Done) renderTable(current.anomalies) else emptyNode,
        )
    }
  }

  private def startButton(): HtmlElement = {
    button(
      cls := "btn btn-primary btn-sm",
      typ := "button",
      disabled <-- inFlightSignal.combineWith(runningSignal).map { case (busy, running) => busy || running },
      I18n.t(UiKeys.adminWordFormsScan),
      onClick.mapToUnit --> startBus.writer,
    )
  }

  // The bar is the share of the id range read, not of the rows: rough, but it moves, and it says the scan is alive.
  private def renderRunning(status: WordFormScanStatus): HtmlElement = {
    div(
      cls := "flex flex-wrap items-center gap-4 mb-4",
      progressTag(
        cls     := "progress progress-primary w-56",
        value   := status.progressPercent.toString,
        maxAttr := "100",
      ),
      span(cls  := "text-sm", I18n.t(UiKeys.adminWordFormsRunning, status.progressPercent)),
      button(
        cls     := "btn btn-sm btn-outline",
        typ     := "button",
        disabled <-- inFlightSignal,
        I18n.t(UiKeys.adminWordFormsCancel),
        onClick.mapToUnit --> cancelBus.writer,
      ),
    )
  }

  private def renderTable(rows: List[WordFormAnomaly]): HtmlElement = {
    if (rows.isEmpty) {
      p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminWordFormsEmpty))
    } else {
      div(
        cls := "overflow-x-auto",
        table(
          cls := "table table-sm",
          thead(
            tr(
              th(I18n.t(UiKeys.adminWordFormsColWord)),
              th(I18n.t(UiKeys.adminWordFormsColLanguage)),
              th(I18n.t(UiKeys.adminWordFormsColRelation)),
              th(cls := "text-right", I18n.t(UiKeys.adminWordFormsColLemmaCount)),
              th(),
            )
          ),
          tbody(rows.sortBy(row => -row.lemmaCount).map(renderRow)),
        ),
      )
    }
  }

  private def renderRow(anomaly: WordFormAnomaly): HtmlElement = {
    tr(
      cls := "hover",
      td(anomaly.formText),
      td(anomaly.language),
      td(cls := "font-mono text-xs", anomaly.relation),
      td(cls := "text-right", anomaly.lemmaCount.toString),
      td(
        cls  := "text-right",
        button(
          cls := "btn btn-xs btn-error btn-outline",
          typ := "button",
          disabled <-- inFlightSignal,
          I18n.t(UiKeys.adminWordFormsDelete),
          onClick.mapToUnit -->
            Observer[Unit] { _ =>
              confirmDialog.ask(
                I18n.t(UiKeys.adminWordFormsDelete),
                I18n.t(UiKeys.adminWordFormsDeleteConfirm),
                danger = true,
              )(deleteBus.emit(anomaly))
            },
        ),
      ),
    )
  }
}
