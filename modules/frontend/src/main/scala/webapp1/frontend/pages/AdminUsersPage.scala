package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.AppShell
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.User
import webapp1.shared.dto.CreateUserRequest

object AdminUsersPage {
  def render(user: User): HtmlElement = AppShell.render(user, Page.Admin, new AdminUsersPage().render())
}

private class AdminUsersPage {
  private val usersVar = Var(List.empty[User])

  private val emailVar = Var("")
  private val passwordVar = Var("")
  private val isAdminVar = Var(false)

  private val errorVar: Var[Option[String]] = Var(None)

  private val loadBus = new EventBus[Unit]()
  private val createBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", "User management"),
      child.maybe <-- errorVar.signal.map(_.map(renderError)),
      renderCreateForm(),
      renderTable(),
      loadBus.events.flatMapSwitch(_ => ApiClient.get[List[User]]("/api/admin/users")) -->
        Observer[Either[ApiError, List[User]]] {
          case Right(users) =>
            usersVar.set(users)
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
      createBus.events.flatMapSwitch(_ => createUser()) --> Observer[Unit](_ => ()),
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderCreateForm(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mb-4",
      div(
        cls := "card-body",
        h2(cls := "card-title", "Create user"),
        div(
          cls := "flex flex-wrap gap-2 items-center",
          input(
            cls := "input",
            typ := "email",
            placeholder := "Email",
            value <-- emailVar.signal,
            onInput.mapToValue --> emailVar.writer,
          ),
          input(
            cls := "input",
            typ := "password",
            placeholder := "Password (min 8 characters)",
            value <-- passwordVar.signal,
            onInput.mapToValue --> passwordVar.writer,
          ),
          label(
            cls := "label gap-2",
            input(
              typ := "checkbox",
              cls := "checkbox",
              checked <-- isAdminVar.signal,
              onClick.mapToChecked --> isAdminVar.writer,
            ),
            "Administrator",
          ),
          button(cls := "btn btn-primary", typ := "button", "Create", onClick.mapToUnit --> createBus.writer),
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
            usersVar
              .signal
              .splitSeq(_.id) { userSignal =>
                renderRow(userSignal)
              }
        ),
      ),
    )
  }

  private def renderRow(userSignal: StrictSignal[User]): HtmlElement = {
    val id = userSignal.now().id
    tr(
      cls := "hover cursor-pointer",
      onClick.mapToUnit --> Observer[Unit](_ => AppRouter.router.pushState(Page.AdminUserDetail(id))),
      td(text <-- userSignal.map(_.email)),
      td(
        child <--
          userSignal
            .map(_.isAdmin)
            .map { isAdmin =>
              if (isAdmin)
                span(cls := "badge badge-primary", "Admin")
              else
                span(cls := "badge badge-ghost", "User")
            }
      ),
      td(text <-- userSignal.map(_.createdAt)),
    )
  }

  private def createUser() = {
    val email = emailVar.now()
    val password = passwordVar.now()
    val isAdmin = isAdminVar.now()
    ApiClient
      .post[CreateUserRequest, User]("/api/admin/users", CreateUserRequest(email, password, isAdmin))
      .map {
        case Right(user) =>
          usersVar.update(_ :+ user)
          Var.set(emailVar -> "", passwordVar -> "", isAdminVar -> false)
          errorVar.set(None)
        case Left(err) =>
          errorVar.set(Some(err.message))
      }
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
