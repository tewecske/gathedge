package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.{AppShell, GroupSubmenu}
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.{Group, GroupPair}
import webapp1.shared.dto.CreatePairRequest
import webapp1.shared.validation.Validation

object GroupDetailPage {
  def render(groupId: Long): HtmlElement = AppShell.render(
    Page.GroupDetail(groupId),
    new GroupDetailPage(groupId).render(),
  )
}

private class GroupDetailPage(groupId: Long) {
  private val groupVar = Var(Option.empty[Group])
  private val groupSignal = groupVar.signal
  private val pairsVar = Var(List.empty[GroupPair])
  private val pairsSignal = pairsVar.signal

  private val sourceVar = Var("")
  private val sourceSignal = sourceVar.signal
  private val targetVar = Var("")
  private val targetSignal = targetVar.signal

  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal = errorVar.signal
  private val inFlightVar = Var(false)
  private val inFlightSignal = inFlightVar.signal

  private val loadBus = new EventBus[Unit]()
  private val addPairBus = new EventBus[Unit]()
  private val deleteGroupBus = new EventBus[Unit]()

  private val loadStream = loadBus.events
  // Validation is pure; the effects hang off the resulting stream as observers.
  private val addPairStream = addPairBus.events.filterWith(inFlightSignal.not).map(_ => validatePair())
  private val deleteStream = deleteGroupBus.events.filterWith(inFlightSignal.not)

  def render(): HtmlElement = {
    div(
      div(cls := "mb-4", a(cls := "link", AppRouter.router.navigateTo(Page.Groups), "← Back to groups")),
      h1(cls := "text-2xl font-bold mb-4", text <-- groupSignal.map(_.map(_.name).getOrElse("Group")).distinct),
      child.maybe <-- groupSignal.map(_.map(g => GroupSubmenu.render(groupId, Page.GroupDetail(groupId), g.myRole))),
      child.maybe <-- errorSignal.map(_.map(msg => renderAlert("alert-error", msg))),
      child.maybe <-- groupSignal.map(_.filter(_.myRole.canWrite).map(_ => renderAddPairForm())),
      renderPairsTable(),
      child.maybe <-- groupSignal.map(_.filter(_.myRole.isAdmin).map(_ => renderDeleteGroupButton())),
      // The group and its pairs load in parallel and share one error slot, so clear it when a
      // fresh load starts — otherwise a stale failure outlives the request that produced it.
      loadStream --> Observer[Unit](_ => errorVar.set(None)),
      loadStream.flatMapSwitch(_ => ApiClient.get[Group](s"/api/groups/$groupId")) -->
        Observer[Either[ApiError, Group]] {
          case Right(g) =>
            groupVar.set(Some(g))
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
      loadStream.flatMapSwitch(_ => ApiClient.get[List[GroupPair]](s"/api/groups/$groupId/pairs")) -->
        Observer[Either[ApiError, List[GroupPair]]] {
          case Right(items) =>
            pairsVar.set(items)
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
      addPairStream -->
        Observer[Either[String, CreatePairRequest]] {
          case Left(err) =>
            errorVar.set(Some(err))
          case Right(_) =>
            Var.set(inFlightVar -> true, errorVar -> None)
        },
      addPairStream
        .collect { case Right(request) =>
          request
        }
        .flatMapSwitch(request =>
          ApiClient.post[CreatePairRequest, GroupPair](s"/api/groups/$groupId/pairs", request)
        ) -->
        Observer[Either[ApiError, GroupPair]] {
          case Right(pair) =>
            pairsVar.update(_ :+ pair)
            Var.set(inFlightVar -> false, sourceVar -> "", targetVar -> "", errorVar -> None)
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      deleteStream --> Observer[Unit](_ => inFlightVar.set(true)),
      deleteStream.flatMapSwitch(_ => ApiClient.delete(s"/api/groups/$groupId")) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_) =>
            inFlightVar.set(false)
            AppRouter.router.pushState(Page.Groups)
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderAddPairForm(): HtmlElement = {
    form(
      cls := "flex gap-2 mb-4",
      onSubmit.preventDefault.mapToUnit --> addPairBus.writer,
      input(
        cls := "input flex-1",
        placeholder := "Source",
        controlled(value <-- sourceSignal, onInput.mapToValue --> sourceVar.writer),
      ),
      input(
        cls := "input flex-1",
        placeholder := "Target",
        controlled(value <-- targetSignal, onInput.mapToValue --> targetVar.writer),
      ),
      button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, "Add"),
    )
  }

  private def renderPairsTable(): HtmlElement = {
    div(
      cls := "overflow-x-auto card bg-base-100 shadow",
      table(
        cls := "table",
        thead(tr(th("Source"), th("Target"), th("Added by"))),
        tbody(
          children <--
            pairsSignal.splitSeq(_.id) { pairSignal =>
              renderPairRow(pairSignal)
            }
        ),
      ),
    )
  }

  private def renderPairRow(pairSignal: Signal[GroupPair]): HtmlElement = {
    tr(
      td(text <-- pairSignal.map(_.source).distinct),
      td(text <-- pairSignal.map(_.target).distinct),
      td(text <-- pairSignal.map(_.createdByEmail).distinct),
    )
  }

  private def renderDeleteGroupButton(): HtmlElement = {
    div(
      cls := "mt-4",
      button(
        cls := "btn btn-error btn-outline btn-sm",
        typ := "button",
        disabled <-- inFlightSignal,
        "Delete group",
        onClick.mapToUnit -->
          Observer[Unit] { _ =>
            if (dom.window.confirm("Delete this group? This cannot be undone."))
              deleteGroupBus.emit(())
          },
      ),
    )
  }

  private def validatePair(): Either[String, CreatePairRequest] = {
    for {
      source <- Validation.validateNonBlank(sourceVar.now(), "Source")
      target <- Validation.validateNonBlank(targetVar.now(), "Target")
    } yield CreatePairRequest(source, target)
  }

  private def renderAlert(kind: String, message: String): HtmlElement = {
    div(role := "alert", cls := s"alert $kind mb-4", span(message))
  }
}
