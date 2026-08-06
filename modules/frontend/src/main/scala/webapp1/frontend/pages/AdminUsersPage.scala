package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{AdminApiClient, ApiError}
import webapp1.frontend.components.{AppShell, FormField}
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.User
import webapp1.shared.dto.CreateUserRequest
import webapp1.shared.validation.Validation

object AdminUsersPage {
  def render(): HtmlElement = AppShell.render(Page.Admin, new AdminUsersPage().render())
}

/** All of the create-user form's state, including its validity. Field errors are derived rather than stored, so they
  * cannot go stale; `showErrors` keeps them hidden until the first submit attempt.
  */
private case class CreateUserForm(
  email: String = "",
  password: String = "",
  isAdmin: Boolean = false,
  showErrors: Boolean = false,
) {
  def emailError: Option[String] = Validation.validateEmail(email).left.toOption

  def passwordError: Option[String] = Validation.validatePassword(password).left.toOption

  def displayError(error: CreateUserForm => Option[String]): Option[String] = {
    if (showErrors)
      error(this)
    else
      None
  }

  /** `Some` exactly when the form is valid, so it doubles as the validity check. */
  def toRequest: Option[CreateUserRequest] = {
    for {
      validEmail    <- Validation.validateEmail(email).toOption
      validPassword <- Validation.validatePassword(password).toOption
    } yield CreateUserRequest(validEmail, validPassword, isAdmin)
  }
}

private class AdminUsersPage {
  private val usersVar    = Var(List.empty[User])
  private val usersSignal = usersVar.signal

  private val formVar    = Var(CreateUserForm())
  private val formSignal = formVar.signal

  // Writable lenses into the one form Var, so inputs stay two-way bound without a Var per field.
  private val emailVar    = formVar.zoom(_.email)((form, email) => form.copy(email = email))
  private val passwordVar = formVar.zoom(_.password)((form, password) => form.copy(password = password))
  private val isAdminVar  = formVar.zoom(_.isAdmin)((form, isAdmin) => form.copy(isAdmin = isAdmin))

  private val emailErrorSignal    = formSignal.map(_.displayError(_.emailError))
  private val passwordErrorSignal = formSignal.map(_.displayError(_.passwordError))

  // Server-side failures only; field-level problems render next to their input.
  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal                   = errorVar.signal
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal

  private val loadBus   = new EventBus[Unit]()
  private val createBus = new EventBus[Unit]()

  // Validation is pure; the effects hang off the resulting stream as observers.
  private val createStream = createBus.events.filterWith(inFlightSignal.not).map(_ => formVar.now().toRequest)

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", "User management"),
      child.maybe <-- errorSignal.map(_.map(renderError)),
      renderCreateForm(),
      renderTable(),
      loadBus.events.flatMapSwitch(_ => AdminApiClient.listUsers) -->
        Observer[Either[ApiError, List[User]]] {
          case Right(users) =>
            Var.set(usersVar -> users, errorVar -> None)
          case Left(err)    =>
            errorVar.set(Some(err.message))
        },
      createStream -->
        Observer[Option[CreateUserRequest]] {
          case None    =>
            formVar.update(_.copy(showErrors = true))
          case Some(_) =>
            Var.set(inFlightVar -> true, errorVar -> None)
        },
      createStream
        .collect { case Some(request) =>
          request
        }
        .flatMapSwitch(request => AdminApiClient.createUser(request)) -->
        Observer[Either[ApiError, User]] {
          case Right(user) =>
            usersVar.update(_ :+ user)
            Var.set(inFlightVar -> false, formVar -> CreateUserForm(), errorVar -> None)
          case Left(err)   =>
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
        h2(cls       := "card-title", "Create user"),
        form(
          cls        := "flex flex-wrap gap-2 items-start",
          // Browser validation would pre-empt our own messages, and they differ from the server's rules.
          noValidate := true,
          onSubmit.preventDefault.mapToUnit --> createBus.writer,
          FormField.render(emailErrorSignal)(
            input(
              cls         := "input",
              cls("input-error") <-- emailErrorSignal.map(_.nonEmpty),
              typ         := "email",
              placeholder := "Email",
              controlled(value <-- emailVar.signal, onInput.mapToValue --> emailVar.writer),
            )
          ),
          FormField.render(passwordErrorSignal)(
            input(
              cls         := "input",
              cls("input-error") <-- passwordErrorSignal.map(_.nonEmpty),
              typ         := "password",
              placeholder := s"Password (min ${Validation.minPasswordLength} characters)",
              controlled(value <-- passwordVar.signal, onInput.mapToValue --> passwordVar.writer),
            )
          ),
          label(
            cls      := "label gap-2 h-12",
            input(
              typ := "checkbox",
              cls := "checkbox",
              controlled(checked <-- isAdminVar.signal, onClick.mapToChecked --> isAdminVar.writer),
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

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
