package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.api.{AdminApiClient, ApiError}
import gathedge.frontend.components.{AdminSubmenu, Alert, AppShell}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.Page
import gathedge.shared.dto.WordFormAnomaly
import gathedge.shared.i18n.UiKeys

/** The `word_forms` fan-out diagnostic: a word wrongly linked as an inflected form of far more lemmas than a real
  * inflection table ever has (the shape of the bug where a mislabeled wiktextract tag turned `haben`/`sein` into a
  * "form" of hundreds of German verbs). A report plus a delete action, the same shape `AdminSystemPage`'s maintenance
  * card is, one row at a time instead of one button.
  */
object AdminWordFormsPage {
  def render(): HtmlElement = AppShell.render(Page.AdminWordForms, new AdminWordFormsPage().render())
}

private class AdminWordFormsPage {

  private val anomaliesVar: Var[Option[List[WordFormAnomaly]]] = Var(None)

  private val errorVar: Var[Option[String]] = Var(None)
  private val infoVar: Var[Option[String]]  = Var(None)
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal

  private val loadBus   = new EventBus[Unit]()
  private val deleteBus = new EventBus[WordFormAnomaly]()

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.adminWordFormsTitle)),
      AdminSubmenu.render(Page.AdminWordForms),
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(infoVar.signal),
      p(cls  := "text-sm opacity-60 mb-4", I18n.t(UiKeys.adminWordFormsHint)),
      child <-- anomaliesVar.signal.map(renderTable),
      loadBus.events.flatMapSwitch(_ => AdminApiClient.wordFormAnomalies) -->
        Observer[Either[ApiError, List[WordFormAnomaly]]] {
          case Right(anomalies) =>
            Var.set(anomaliesVar -> Some(anomalies), errorVar -> None)
          case Left(err)        =>
            errorVar.set(Some(err.message))
        },
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

  private def renderTable(anomalies: Option[List[WordFormAnomaly]]): HtmlElement = {
    anomalies match {
      case None                       =>
        span(cls := "loading loading-spinner loading-sm")
      case Some(rows) if rows.isEmpty =>
        p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminWordFormsEmpty))
      case Some(rows)                 =>
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
              if (dom.window.confirm(I18n.t(UiKeys.adminWordFormsDeleteConfirm)))
                deleteBus.emit(anomaly)
            },
        ),
      ),
    )
  }
}
