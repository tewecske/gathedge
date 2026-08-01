package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.AppShell
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.User
import webapp1.shared.dto.UpdateUserRequest
import webapp1.shared.validation.Validation

object AdminUserDetailPage {
  def render(userId: Long): HtmlElement = AppShell.render(
    Page.AdminUserDetail(userId),
    new AdminUserDetailPage(userId).render(),
  )
}

private class AdminUserDetailPage(userId: Long) {
  private val userVar: Var[Option[User]] = Var(None)
  private val userSignal = userVar.signal
  private val notFoundVar = Var(false)
  private val notFoundSignal = notFoundVar.signal

  private val emailVar = Var("")
  private val emailSignal = emailVar.signal
  private val isAdminVar = Var(false)
  private val isAdminSignal = isAdminVar.signal
  private val passwordVar = Var("")
  private val passwordSignal = passwordVar.signal

  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal = errorVar.signal
  private val infoVar: Var[Option[String]] = Var(None)
  private val infoSignal = infoVar.signal
  private val inFlightVar = Var(false)
  private val inFlightSignal = inFlightVar.signal

  private val loadBus = new EventBus[Unit]()
  private val saveBus = new EventBus[Unit]()
  private val deleteBus = new EventBus[Unit]()

  // Validation is pure; the effects hang off the resulting stream as observers.
  private val saveStream = saveBus.events.filterWith(inFlightSignal.not).map(_ => validateEdit())
  private val deleteStream = deleteBus.events.filterWith(inFlightSignal.not)

  def render(): HtmlElement = {
    div(
      div(cls := "mb-4", a(cls := "link", AppRouter.router.navigateTo(Page.Admin), "← Back to users")),
      child.maybe <-- errorSignal.map(_.map(msg => renderAlert("alert-error", msg))),
      child.maybe <-- infoSignal.map(_.map(msg => renderAlert("alert-info", msg))),
      child.maybe <-- notFoundSignal.map(Option.when(_)(renderNotFound())),
      child.maybe <-- userSignal.map(_.map(_ => renderForm())),
      loadBus.events.flatMapSwitch(_ => ApiClient.get[User](s"/api/admin/users/$userId")) -->
        Observer[Either[ApiError, User]] {
          case Right(u) =>
            Var.set(
              userVar -> Some(u),
              emailVar -> u.email,
              isAdminVar -> u.isAdmin,
              notFoundVar -> false,
              errorVar -> None,
            )
          case Left(err) if err.status == 404 =>
            notFoundVar.set(true)
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
      saveStream -->
        Observer[Either[String, UpdateUserRequest]] {
          case Left(err) =>
            Var.set(errorVar -> Some(err), infoVar -> None)
          case Right(_) =>
            Var.set(inFlightVar -> true, errorVar -> None, infoVar -> None)
        },
      saveStream
        .collect { case Right(request) =>
          request
        }
        .flatMapSwitch(request => ApiClient.put[UpdateUserRequest, User](s"/api/admin/users/$userId", request)) -->
        Observer[Either[ApiError, User]] {
          case Right(u) =>
            Var.set(
              inFlightVar -> false,
              userVar -> Some(u),
              passwordVar -> "",
              errorVar -> None,
              infoVar -> Some("Saved"),
            )
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message), infoVar -> None)
        },
      // Auto-dismiss the confirmation; flatMapSwitch cancels the pending timer if a newer
      // message arrives, so the last one always gets its full four seconds.
      infoSignal.updates.filter(_.isDefined).flatMapSwitch(_ => EventStream.delay(4000)) -->
        Observer[Unit](_ => infoVar.set(None)),
      deleteStream --> Observer[Unit](_ => inFlightVar.set(true)),
      deleteStream.flatMapSwitch(_ => ApiClient.delete(s"/api/admin/users/$userId")) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_) =>
            inFlightVar.set(false)
            AppRouter.router.pushState(Page.Admin)
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderNotFound(): HtmlElement = {
    div(role := "alert", cls := "alert alert-warning", span("This user no longer exists."))
  }

  private def renderForm(): HtmlElement = {
    form(
      cls := "card bg-base-100 shadow",
      onSubmit.preventDefault.mapToUnit --> saveBus.writer,
      div(
        cls := "card-body",
        fieldSet(
          cls := "fieldset",
          legend(cls := "fieldset-legend", "Email"),
          input(
            cls := "input w-full",
            typ := "email",
            controlled(value <-- emailSignal, onInput.mapToValue --> emailVar.writer),
          ),
          legend(cls := "fieldset-legend", "New password"),
          input(
            cls := "input w-full",
            typ := "password",
            placeholder := "Leave blank to keep the current password",
            controlled(value <-- passwordSignal, onInput.mapToValue --> passwordVar.writer),
          ),
          label(
            cls := "label gap-2 mt-2",
            input(
              typ := "checkbox",
              cls := "checkbox",
              controlled(checked <-- isAdminSignal, onClick.mapToChecked --> isAdminVar.writer),
            ),
            "Administrator",
          ),
        ),
        div(
          cls := "card-actions justify-between mt-4",
          button(
            cls := "btn btn-error btn-outline",
            typ := "button",
            disabled <-- inFlightSignal,
            "Delete user",
            onClick.mapToUnit -->
              Observer[Unit] { _ =>
                if (dom.window.confirm("Delete this user? This cannot be undone."))
                  deleteBus.emit(())
              },
          ),
          button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, "Save"),
        ),
      ),
    )
  }

  /** A blank password field means "keep the current password", so it is only validated when filled in. */
  private def validateEdit(): Either[String, UpdateUserRequest] = {
    val password = Some(passwordVar.now()).filter(_.nonEmpty)
    for {
      email <- Validation.validateEmail(emailVar.now())
      validPassword <-
        password.fold[Either[String, Option[String]]](Right(None))(Validation.validatePassword(_).map(Some(_)))
    } yield UpdateUserRequest(email, isAdminVar.now(), validPassword)
  }

  private def renderAlert(kind: String, message: String): HtmlElement = {
    div(role := "alert", cls := s"alert $kind mb-4", span(message))
  }
}
