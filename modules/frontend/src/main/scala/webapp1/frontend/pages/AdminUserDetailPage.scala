package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.api.{AdminApiClient, ApiError}
import webapp1.frontend.components.{AdminSubmenu, Alert, AppShell, FormField}
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

/** State of the edit-user form. Field errors are derived rather than stored, and `showErrors` keeps them hidden until
  * the first submit attempt.
  */
private case class EditUserForm(
  email: String = "",
  password: String = "",
  isAdmin: Boolean = false,
  showErrors: Boolean = false,
) {
  def emailError: Option[String] = Validation.validateEmail(email).left.toOption

  /** A blank password field means "keep the current password", so it is only validated when filled in. */
  def passwordError: Option[String] = {
    validatedPassword.left.toOption
  }

  def displayError(error: EditUserForm => Option[String]): Option[String] = {
    if (showErrors)
      error(this)
    else
      None
  }

  /** `Some` exactly when the form is valid, so it doubles as the validity check. */
  def toRequest: Option[UpdateUserRequest] = {
    for {
      validEmail    <- Validation.validateEmail(email).toOption
      validPassword <- validatedPassword.toOption
    } yield UpdateUserRequest(validEmail, isAdmin, validPassword)
  }

  private def validatedPassword: Either[String, Option[String]] = {
    Some(password).filter(_.nonEmpty) match {
      case None          =>
        Right(None)
      case Some(entered) =>
        Validation.validatePassword(entered).map(Some(_))
    }
  }
}

private class AdminUserDetailPage(userId: Long) {
  private val userVar: Var[Option[User]] = Var(None)
  private val userSignal                 = userVar.signal
  private val notFoundVar                = Var(false)
  private val notFoundSignal             = notFoundVar.signal

  private val formVar             = Var(EditUserForm())
  private val emailVar            = formVar.zoom(_.email)((form, email) => form.copy(email = email))
  private val passwordVar         = formVar.zoom(_.password)((form, password) => form.copy(password = password))
  private val isAdminVar          = formVar.zoom(_.isAdmin)((form, isAdmin) => form.copy(isAdmin = isAdmin))
  private val emailErrorSignal    = formVar.signal.map(_.displayError(_.emailError))
  private val passwordErrorSignal = formVar.signal.map(_.displayError(_.passwordError))

  // Server-side failures only; field-level problems render next to their input.
  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal                   = errorVar.signal
  private val infoVar: Var[Option[String]]  = Var(None)
  private val infoSignal                    = infoVar.signal
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal

  private val loadBus   = new EventBus[Unit]()
  private val saveBus   = new EventBus[Unit]()
  private val deleteBus = new EventBus[Unit]()

  // Confirming an address or detaching a provider changes the account this form is editing, so the diagnostics ask
  // for a re-read rather than leaving the two views disagreeing.
  private val diagnostics = new AdminUserDiagnostics(userId, loadBus.writer)

  // Validation is pure; the effects hang off the resulting stream as observers.
  private val saveStream   = saveBus.events.filterWith(inFlightSignal.not).map(_ => formVar.now().toRequest)
  private val deleteStream = deleteBus.events.filterWith(inFlightSignal.not)

  def render(): HtmlElement = {
    div(
      AdminSubmenu.render(Page.AdminUserDetail(userId)),
      div(cls := "mb-4", a(cls := "link", AppRouter.router.navigateTo(Page.Admin), "← Back to users")),
      Alert.maybeError(errorSignal),
      Alert.maybeInfo(infoSignal),
      child.maybe <-- notFoundSignal.map(Option.when(_)(renderNotFound())),
      // Only the presence of a user decides whether the form exists; its contents live in `formVar`.
      // Without `distinct` every save would rebuild the form element and drop the user's focus.
      child.maybe <-- userSignal.map(_.isDefined).distinct.map(Option.when(_)(renderForm())),
      // Same `distinct` for the same reason: the diagnostics own their own state and must survive a save.
      child.maybe <--
        userSignal.map(_.isDefined).distinct.map(Option.when(_)(diagnostics.render())),
      loadBus.events.flatMapSwitch(_ => AdminApiClient.getUser(userId)) -->
        Observer[Either[ApiError, User]] {
          case Right(u)                       =>
            Var.set(
              userVar     -> Some(u),
              formVar     -> EditUserForm(email = u.email, isAdmin = u.isAdmin),
              notFoundVar -> false,
              errorVar    -> None,
            )
          case Left(err) if err.status == 404 =>
            notFoundVar.set(true)
          case Left(err)                      =>
            errorVar.set(Some(err.message))
        },
      saveStream -->
        Observer[Option[UpdateUserRequest]] {
          case None    =>
            formVar.update(_.copy(showErrors = true))
            infoVar.set(None)
          case Some(_) =>
            Var.set(inFlightVar -> true, errorVar -> None, infoVar -> None)
        },
      saveStream
        .collect { case Some(request) =>
          request
        }
        .flatMapSwitch(request => AdminApiClient.updateUser(userId, request)) -->
        Observer[Either[ApiError, User]] {
          case Right(u)  =>
            // Keep whatever is in the email/admin controls: the response confirms it, and the
            // user may already be editing again. Only the write-only password field is cleared.
            Var.set(
              inFlightVar -> false,
              userVar     -> Some(u),
              passwordVar -> "",
              errorVar    -> None,
              infoVar     -> Some("Saved"),
            )
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message), infoVar -> None)
        },
      // Auto-dismiss the confirmation; flatMapSwitch cancels the pending timer if a newer
      // message arrives, so the last one always gets its full four seconds.
      infoSignal.updates.filter(_.isDefined).flatMapSwitch(_ => EventStream.delay(4000)) -->
        Observer[Unit](_ => infoVar.set(None)),
      deleteStream --> Observer[Unit](_ => inFlightVar.set(true)),
      deleteStream.flatMapSwitch(_ => AdminApiClient.deleteUser(userId)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            inFlightVar.set(false)
            AppRouter.router.pushState(Page.Admin)
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderNotFound(): HtmlElement = {
    Alert.warning("This user no longer exists.")
  }

  private def renderForm(): HtmlElement = {
    form(
      cls        := "card bg-base-100 shadow",
      // Browser validation would pre-empt our own messages, and they differ from the server's rules.
      noValidate := true,
      onSubmit.preventDefault.mapToUnit --> saveBus.writer,
      div(
        cls := "card-body",
        fieldSet(
          cls := "fieldset",
          legend(cls := "fieldset-legend", "Email"),
          FormField.render(emailErrorSignal)(
            input(
              cls := "input w-full",
              cls("input-error") <-- emailErrorSignal.map(_.nonEmpty),
              typ := "email",
              controlled(value <-- emailVar.signal, onInput.mapToValue --> emailVar.writer),
            )
          ),
          legend(cls := "fieldset-legend", "New password"),
          FormField.render(passwordErrorSignal)(
            input(
              cls         := "input w-full",
              cls("input-error") <-- passwordErrorSignal.map(_.nonEmpty),
              typ         := "password",
              placeholder := "Leave blank to keep the current password",
              controlled(value <-- passwordVar.signal, onInput.mapToValue --> passwordVar.writer),
            )
          ),
          label(
            cls      := "label gap-2 mt-2",
            input(
              typ := "checkbox",
              cls := "checkbox",
              controlled(checked <-- isAdminVar.signal, onClick.mapToChecked --> isAdminVar.writer),
            ),
            "Administrator",
          ),
        ),
        div(
          cls := "card-actions justify-between mt-4",
          button(
            cls      := "btn btn-error btn-outline",
            typ      := "button",
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

}
