package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.AppShell
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.User
import webapp1.shared.dto.CreateUserRequest
import webapp1.shared.validation.Validation

object AdminUsersPage {
  def render(): HtmlElement = AppShell.render(Page.Admin, new AdminUsersPage().render())
}

private class AdminUsersPage {
  private val usersVar = Var(List.empty[User])
  private val usersSignal = usersVar.signal

  private val emailVar = Var("")
  private val emailSignal = emailVar.signal
  private val passwordVar = Var("")
  private val passwordSignal = passwordVar.signal
  private val isAdminVar = Var(false)
  private val isAdminSignal = isAdminVar.signal

  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal = errorVar.signal
  private val inFlightVar = Var(false)
  private val inFlightSignal = inFlightVar.signal

  private val loadBus = new EventBus[Unit]()
  private val createBus = new EventBus[Unit]()

  // Validation is pure; the effects hang off the resulting stream as observers.
  private val createStream = createBus.events.filterWith(inFlightSignal.not).map(_ => validateNewUser())

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", "User management"),
      child.maybe <-- errorSignal.map(_.map(renderError)),
      renderCreateForm(),
      renderTable(),
      loadBus.events.flatMapSwitch(_ => ApiClient.get[List[User]]("/api/admin/users")) -->
        Observer[Either[ApiError, List[User]]] {
          case Right(users) =>
            Var.set(usersVar -> users, errorVar -> None)
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
      createStream -->
        Observer[Either[String, CreateUserRequest]] {
          case Left(err) =>
            errorVar.set(Some(err))
          case Right(_) =>
            Var.set(inFlightVar -> true, errorVar -> None)
        },
      createStream
        .collect { case Right(request) =>
          request
        }
        .flatMapSwitch(request => ApiClient.post[CreateUserRequest, User]("/api/admin/users", request)) -->
        Observer[Either[ApiError, User]] {
          case Right(user) =>
            usersVar.update(_ :+ user)
            Var.set(inFlightVar -> false, emailVar -> "", passwordVar -> "", isAdminVar -> false, errorVar -> None)
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderCreateForm(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mb-4",
      div(
        cls := "card-body",
        h2(cls := "card-title", "Create user"),
        form(
          cls := "flex flex-wrap gap-2 items-center",
          onSubmit.preventDefault.mapToUnit --> createBus.writer,
          input(
            cls := "input",
            typ := "email",
            placeholder := "Email",
            controlled(value <-- emailSignal, onInput.mapToValue --> emailVar.writer),
          ),
          input(
            cls := "input",
            typ := "password",
            placeholder := s"Password (min ${Validation.minPasswordLength} characters)",
            controlled(value <-- passwordSignal, onInput.mapToValue --> passwordVar.writer),
          ),
          label(
            cls := "label gap-2",
            input(
              typ := "checkbox",
              cls := "checkbox",
              controlled(checked <-- isAdminSignal, onClick.mapToChecked --> isAdminVar.writer),
            ),
            "Administrator",
          ),
          button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, "Create"),
        ),
      ),
    )
  }

  private def renderTable(): HtmlElement = {
    div(
      cls := "overflow-x-auto card bg-base-100 shadow",
      table(
        cls := "table",
        thead(tr(th("Email"), th("Admin"), th("Created"))),
        tbody(
          children <--
            usersSignal.splitSeq(_.id) { userSignal =>
              renderRow(userSignal.key, userSignal)
            }
        ),
      ),
    )
  }

  private def renderRow(id: Long, userSignal: Signal[User]): HtmlElement = {
    tr(
      cls := "hover",
      // A link rather than a click handler on the row, so the detail page is reachable by keyboard.
      td(
        a(
          cls := "link link-hover",
          AppRouter.router.navigateTo(Page.AdminUserDetail(id)),
          text <-- userSignal.map(_.email).distinct,
        )
      ),
      td(
        child <--
          userSignal
            .map(_.isAdmin)
            .distinct
            .map { isAdmin =>
              if (isAdmin)
                span(cls := "badge badge-primary", "Admin")
              else
                span(cls := "badge badge-ghost", "User")
            }
      ),
      td(text <-- userSignal.map(_.createdAt).distinct),
    )
  }

  private def validateNewUser(): Either[String, CreateUserRequest] = {
    for {
      email <- Validation.validateEmail(emailVar.now())
      password <- Validation.validatePassword(passwordVar.now())
    } yield CreateUserRequest(email, password, isAdminVar.now())
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
