package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.AppShell
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.User
import webapp1.shared.dto.UpdateUserRequest

object AdminUserDetailPage {
  def render(currentUser: User, userId: Long): HtmlElement = AppShell.render(
    currentUser,
    Page.AdminUserDetail(userId),
    new AdminUserDetailPage(userId).render(),
  )
}

private class AdminUserDetailPage(userId: Long) {
  private val userVar: Var[Option[User]] = Var(None)
  private val notFoundVar = Var(false)

  private val emailVar = Var("")
  private val isAdminVar = Var(false)
  private val passwordVar = Var("")

  private val errorVar: Var[Option[String]] = Var(None)
  private val infoVar: Var[Option[String]] = Var(None)

  private val loadBus = new EventBus[Unit]()
  private val saveBus = new EventBus[Unit]()
  private val deleteBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      div(cls := "mb-4", a(cls := "link", AppRouter.router.navigateTo(Page.Admin), "← Back to users")),
      child.maybe <-- errorVar.signal.map(_.map(msg => renderAlert("alert-error", msg))),
      child.maybe <-- infoVar.signal.map(_.map(msg => renderAlert("alert-info", msg))),
      child.maybe <-- notFoundVar.signal.map(Option.when(_)(renderNotFound())),
      child.maybe <-- userVar.signal.map(_.map(_ => renderForm())),
      loadBus.events.flatMapSwitch(_ => ApiClient.get[User](s"/api/admin/users/$userId")) -->
        Observer[Either[ApiError, User]] {
          case Right(u) =>
            userVar.set(Some(u))
            emailVar.set(u.email)
            isAdminVar.set(u.isAdmin)
            notFoundVar.set(false)
          case Left(err) if err.status == 404 =>
            notFoundVar.set(true)
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
      saveBus.events.flatMapSwitch(_ => save()) --> Observer[Unit](_ => ()),
      deleteBus.events.flatMapSwitch(_ => delete()) --> Observer[Unit](_ => ()),
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderNotFound(): HtmlElement = {
    div(role := "alert", cls := "alert alert-warning", span("This user no longer exists."))
  }

  private def renderForm(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body",
        fieldSet(
          cls := "fieldset",
          legend(cls := "fieldset-legend", "Email"),
          input(
            cls := "input w-full",
            typ := "email",
            value <-- emailVar.signal,
            onInput.mapToValue --> emailVar.writer,
          ),
          legend(cls := "fieldset-legend", "New password"),
          input(
            cls := "input w-full",
            typ := "password",
            placeholder := "Leave blank to keep the current password",
            value <-- passwordVar.signal,
            onInput.mapToValue --> passwordVar.writer,
          ),
          label(
            cls := "label gap-2 mt-2",
            input(
              typ := "checkbox",
              cls := "checkbox",
              checked <-- isAdminVar.signal,
              onClick.mapToChecked --> isAdminVar.writer,
            ),
            "Administrator",
          ),
        ),
        div(
          cls := "card-actions justify-between mt-4",
          button(
            cls := "btn btn-error btn-outline",
            typ := "button",
            "Delete user",
            onClick.mapToUnit -->
              Observer[Unit] { _ =>
                if (dom.window.confirm("Delete this user? This cannot be undone."))
                  deleteBus.emit(())
              },
          ),
          button(cls := "btn btn-primary", typ := "button", "Save", onClick.mapToUnit --> saveBus.writer),
        ),
      ),
    )
  }

  private def save() = {
    val password = Option(passwordVar.now()).filter(_.nonEmpty)
    ApiClient
      .put[UpdateUserRequest, User](
        s"/api/admin/users/$userId",
        UpdateUserRequest(emailVar.now(), isAdminVar.now(), password),
      )
      .map {
        case Right(u) =>
          userVar.set(Some(u))
          passwordVar.set("")
          errorVar.set(None)
          infoVar.set(Some("Saved"))
        case Left(err) =>
          errorVar.set(Some(err.message))
          infoVar.set(None)
      }
  }

  private def delete() = {
    ApiClient
      .delete(s"/api/admin/users/$userId")
      .map {
        case Right(_) =>
          AppRouter.router.pushState(Page.Admin)
        case Left(err) =>
          errorVar.set(Some(err.message))
      }
  }

  private def renderAlert(kind: String, message: String): HtmlElement = {
    div(role := "alert", cls := s"alert $kind mb-4", span(message))
  }
}
