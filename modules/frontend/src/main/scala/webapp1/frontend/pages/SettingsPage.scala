package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.Page
import webapp1.frontend.components.{AppShell, OAuthButtons, OAuthMessages}
import webapp1.frontend.state.AppState
import webapp1.shared.domain.OAuthProvider
import webapp1.shared.dto.{IdentitiesResponse, LinkedIdentity, SetPasswordRequest}
import webapp1.shared.validation.Validation

import OAuthProvider.display

object SettingsPage {
  def render(): HtmlElement = AppShell.render(Page.Settings, new SettingsPage().render())
}

/** Account settings: which social accounts are linked, and the password.
  *
  * This page is what makes the never-auto-link rule survivable. A social sign-in whose email already belongs to an
  * account is refused rather than let in (see `AuthService.loginWithOAuth`), and linking here is the only way to join
  * the two — so the OAuth callback redirects its failures to `/settings?error=…` as well as to `/login?error=…`.
  */
private class SettingsPage {
  private val identitiesVar: Var[List[LinkedIdentity]] = Var(Nil)
  private val identitiesSignal                         = identitiesVar.signal
  private val hasPasswordVar                           = Var(false)
  private val hasPasswordSignal                        = hasPasswordVar.signal
  private val availableVar: Var[List[OAuthProvider]]   = Var(Nil)

  /** Only providers with no link yet: a second Google account on the same login would make unlink-by-provider
    * ambiguous, and the server refuses it as `OAuthAlreadyLinked` anyway.
    */
  private val linkableSignal = {
    availableVar.signal
      .combineWith(identitiesSignal)
      .map { case (available, linked) =>
        available.filterNot(p => linked.exists(_.provider == p))
      }
  }

  private val currentPasswordVar             = Var("")
  private val newPasswordVar                 = Var("")
  private val errorVar: Var[Option[String]]  = Var(OAuthMessages.queryParam("error").map(OAuthMessages.errorMessage))
  private val noticeVar: Var[Option[String]] = Var(OAuthMessages.queryParam("linked").map(OAuthMessages.linkedMessage))
  private val inFlightVar                    = Var(false)
  private val inFlightSignal                 = inFlightVar.signal

  private val reloadBus         = new EventBus[Unit]()
  private val unlinkBus         = new EventBus[OAuthProvider]()
  private val passwordSubmitBus = new EventBus[Unit]()
  private val resendBus         = new EventBus[Unit]()

  /** The email card is driven off the session state rather than a fetch of its own: `/api/me` already carries
    * `emailVerified`, and this page is only reachable with a session.
    */
  private val emailStatusSignal = {
    AppState.currentUserSignal.map(_.map(user => (user.email, user.emailVerified)))
  }

  /** Also what the resend stream samples, so a click that outraces a session refresh cannot post an address that has
    * meanwhile been verified.
    */
  private val unverifiedEmailSignal = {
    AppState.currentUserSignal.map(_.filterNot(_.emailVerified).map(_.email))
  }

  private val passwordStream = passwordSubmitBus.events.filterWith(inFlightSignal.not)

  def render(): HtmlElement = {
    div(
      cls := "max-w-2xl mx-auto flex flex-col gap-6",
      h1(cls := "text-2xl font-bold", "Account settings"),
      child.maybe <-- noticeVar.signal.map(_.map(renderNotice)),
      child.maybe <-- errorVar.signal.map(_.map(renderError)),
      renderEmail(),
      renderIdentities(),
      renderPasswordForm(),
      // One load on mount, and one after every successful mutation, so the lockout guard's view of
      // "how many credentials are left" is never stale enough to enable a button the server refuses.
      EventStream.unit().mergeWith(reloadBus.events).flatMapSwitch(_ => ApiClient.identities) -->
        Observer[Either[ApiError, IdentitiesResponse]] {
          case Right(res) =>
            identitiesVar.set(res.identities)
            hasPasswordVar.set(res.hasPassword)
            availableVar.set(res.available)
          case Left(err)  =>
            errorVar.set(Some(err.message))
        },
      unlinkBus.events.flatMapSwitch(ApiClient.unlinkIdentity) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(errorVar -> None, noticeVar -> Some("Account unlinked."))
            reloadBus.emit(())
          case Left(err) =>
            Var.set(errorVar -> Some(err.message), noticeVar -> None)
        },
      passwordStream --> Observer[Unit](_ => Var.set(inFlightVar -> true, errorVar -> None, noticeVar -> None)),
      passwordStream.flatMapSwitch(_ => submitPassword()) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(
              inFlightVar        -> false,
              currentPasswordVar -> "",
              newPasswordVar     -> "",
              noticeVar          -> Some("Password saved."),
            )
            reloadBus.emit(())
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      resendBus.events
        .sample(unverifiedEmailSignal)
        .collect { case Some(email) =>
          email
        }
        .flatMapSwitch(ApiClient.resendVerification) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(errorVar -> None, noticeVar -> Some("Verification link sent — check your inbox."))
          case Left(err) =>
            Var.set(errorVar -> Some(err.message), noticeVar -> None)
        },
    )
  }

  /** The unverified half is shown whether or not the deployment enforces verification: an unproven address is worth
    * fixing either way, and this page cannot see the server's `app.require-email-verification`.
    */
  private def renderEmail(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body",
        h2(cls := "card-title text-lg", "Email address"),
        child.maybe <--
          emailStatusSignal.map(
            _.map { case (email, verified) =>
              renderEmailStatus(email, verified)
            }
          ),
        child.maybe <--
          emailStatusSignal.map(
            _.collect { case (_, false) =>
              renderResend()
            }
          ),
      ),
    )
  }

  private def renderEmailStatus(email: String, verified: Boolean): HtmlElement = {
    div(
      cls := "flex items-center justify-between gap-4",
      span(email),
      if (verified)
        span(cls := "badge badge-success", "Verified")
      else
        span(cls := "badge badge-warning", "Not verified"),
    )
  }

  private def renderResend(): HtmlElement = {
    div(
      cls := "flex items-center justify-between gap-4 mt-2",
      p(cls      := "text-sm opacity-70", "Click the link we emailed you, or send yourself a new one."),
      button(cls := "btn btn-sm", typ := "button", onClick.mapToUnit --> resendBus.writer, "Resend verification email"),
    )
  }

  private def renderIdentities(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body",
        h2(cls := "card-title text-lg", "Linked accounts"),
        p(cls  := "text-sm opacity-70", "Sign in with any of these instead of your password."),
        child.maybe <--
          identitiesSignal.map(identities =>
            Option.when(identities.isEmpty)(p(cls := "text-sm", "Nothing linked yet."))
          ),
        ul(
          cls  := "flex flex-col divide-y divide-base-300",
          children <--
            identitiesSignal.splitSeq(_.provider) { identitySignal =>
              renderIdentityRow(identitySignal.key, identitySignal)
            },
        ),
        child.maybe <--
          linkableSignal.map(linkable =>
            Option.when(linkable.nonEmpty)(div(cls := "mt-4", OAuthButtons.render(linkableSignal, link = true)))
          ),
      ),
    )
  }

  private def renderIdentityRow(provider: OAuthProvider, identitySignal: Signal[LinkedIdentity]): HtmlElement = {
    // The account's last remaining way in cannot be removed. The server refuses it too — this only
    // keeps the button from looking available when pressing it can never work.
    val isLastCredentialSignal = {
      hasPasswordSignal
        .combineWith(identitiesSignal)
        .map { case (hasPassword, identities) =>
          !hasPassword && identities.sizeIs <= 1
        }
    }
    li(
      cls := "flex items-center justify-between gap-4 py-3",
      div(
        span(cls := "font-medium", provider.display),
        child.maybe <--
          identitySignal.map(_.email).distinct.map(_.map(address => div(cls := "text-sm opacity-70", address))),
      ),
      div(
        cls := "flex flex-col items-end gap-1",
        button(
          cls := "btn btn-ghost btn-sm",
          typ := "button",
          disabled <-- isLastCredentialSignal,
          onClick.mapTo(provider) --> unlinkBus.writer,
          "Unlink",
        ),
        child.maybe <--
          isLastCredentialSignal.map(Option.when(_)(span(cls := "text-xs opacity-70", "Set a password first"))),
      ),
    )
  }

  private def renderPasswordForm(): HtmlElement = {
    form(
      cls := "card bg-base-100 shadow",
      onSubmit.preventDefault.mapToUnit --> passwordSubmitBus.writer,
      div(
        cls := "card-body",
        h2(cls := "card-title text-lg", child.text <-- hasPasswordSignal.map(passwordFormTitle)),
        fieldSet(
          cls  := "fieldset",
          // `label`, not `legend`: a fieldset promotes its *first* legend child to the caption over the
          // top border no matter where it sits, which pulled "New password" above the current-password
          // field. Same class, so it renders identically.
          // An account created through a social sign-in has no password to prove, so the field
          // that would ask for it is not rendered at all.
          child.maybe <--
            hasPasswordSignal.map(
              Option.when(_)(
                div(
                  label(cls := "fieldset-legend", "Current password"),
                  input(
                    cls     := "input w-full",
                    typ     := "password",
                    controlled(value <-- currentPasswordVar.signal, onInput.mapToValue --> currentPasswordVar.writer),
                  ),
                )
              )
            ),
          label(cls := "fieldset-legend", "New password"),
          input(
            cls     := "input w-full",
            typ     := "password",
            controlled(value <-- newPasswordVar.signal, onInput.mapToValue --> newPasswordVar.writer),
          ),
          p(cls     := "label", s"At least ${Validation.minPasswordLength} characters"),
        ),
        div(
          cls  := "card-actions justify-end mt-4",
          button(
            cls := "btn btn-primary",
            typ := "submit",
            disabled <-- inFlightSignal,
            child.text <-- hasPasswordSignal.map(passwordFormTitle),
          ),
        ),
      ),
    )
  }

  private def passwordFormTitle(hasPassword: Boolean): String = {
    if (hasPassword)
      "Change password"
    else
      "Set a password"
  }

  private def submitPassword(): EventStream[Either[ApiError, Unit]] = {
    val current = Option(currentPasswordVar.now()).filter(_.nonEmpty)
    ApiClient.setPassword(SetPasswordRequest(current, newPasswordVar.now()))
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }

  private def renderNotice(message: String): HtmlElement = {
    div(role := "status", cls := "alert alert-success", span(message))
  }
}
