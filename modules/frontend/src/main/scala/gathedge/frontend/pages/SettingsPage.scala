package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiClient, ApiError, ProgressShareApiClient}
import gathedge.frontend.Page
import gathedge.frontend.components.{AppShell, CaptchaField, OAuthButtons, OAuthMessages}
import gathedge.frontend.state.AppState
import gathedge.shared.domain.OAuthProvider
import gathedge.shared.dto.{
  AuthResponse,
  CaptchaStatusResponse,
  IdentitiesResponse,
  LinkedIdentity,
  SetPasswordRequest,
  SharedViewer,
  UpdateProfileRequest,
  ShareCodeResponse,
}
import gathedge.frontend.i18n.I18n
import gathedge.shared.i18n.{MessageKeys, UiKeys}
import gathedge.shared.validation.Validation
import org.scalajs.dom

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

  /** The profile form, seeded from the session the page was opened with. It follows nothing after that: a form that
    * re-seeded itself from every `setUser` would overwrite what the reader is in the middle of typing.
    */
  private val usernameVar      = Var(AppState.currentUser.flatMap(_.username).getOrElse(""))
  private val nameVar          = Var(AppState.currentUser.flatMap(_.name).getOrElse(""))
  private val profileSubmitBus = new EventBus[Unit]()

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

  // Progress sharing: the caller's own share code and who has redeemed it. Kept separate from the
  // identities/password sections' own vars, but sharing the page's `errorVar`/`noticeVar` for feedback.
  private val shareCodeVar: Var[Option[String]]   = Var(None)
  private val viewersVar: Var[List[SharedViewer]] = Var(Nil)
  private val shareCodeBus                        = new EventBus[Unit]()
  private val viewersReloadBus                    = new EventBus[Unit]()
  private val revokeViewerBus                     = new EventBus[Long]()

  /** The resend endpoint is the anonymous `/api/auth/verification/resend`, which is captcha-gated — so this page has to
    * present the widget and carry its token too, the same as the sign-in page's resend block.
    */
  private val captchaStatusVar: Var[Option[CaptchaStatusResponse]] = Var(None)
  private val captchaTokenVar: Var[Option[String]]                 = Var(None)
  private val captchaResetBus                                      = new EventBus[Unit]()
  private val captchaSiteKeySignal                                 = captchaStatusVar.signal.map(_.flatMap(_.siteKey)).distinct

  /** The email card is driven off the session state rather than a fetch of its own: `/api/me` already carries
    * `emailVerified`, and this page is only reachable with a session.
    */
  /** `flatMap`, not `map`: an account with no address at all — a guest — has no email status to show, exactly like
    * nobody being signed in.
    */
  private val emailStatusSignal = {
    AppState.currentUserSignal.map(_.flatMap(user => user.email.map(address => (address, user.emailVerified))))
  }

  /** Also what the resend stream samples, so a click that outraces a session refresh cannot post an address that has
    * meanwhile been verified.
    */
  private val unverifiedEmailSignal = {
    AppState.currentUserSignal.map(_.filterNot(_.emailVerified).flatMap(_.email))
  }

  private val passwordStream = passwordSubmitBus.events.filterWith(inFlightSignal.not)
  private val profileStream  = profileSubmitBus.events.filterWith(inFlightSignal.not)

  def render(): HtmlElement = {
    div(
      cls := "max-w-2xl mx-auto flex flex-col gap-6",
      h1(cls := "text-2xl font-bold", I18n.t(UiKeys.settingsTitle)),
      child.maybe <-- noticeVar.signal.map(_.map(renderNotice)),
      child.maybe <-- errorVar.signal.map(_.map(renderError)),
      renderEmail(),
      renderProfileForm(),
      renderIdentities(),
      renderShareCard(),
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
            Var.set(errorVar -> None, noticeVar -> Some(I18n.t(UiKeys.settingsUnlinked)))
            reloadBus.emit(())
          case Left(err) =>
            Var.set(errorVar -> Some(err.message), noticeVar -> None)
        },
      profileStream --> Observer[Unit](_ => Var.set(inFlightVar -> true, errorVar -> None, noticeVar -> None)),
      profileStream.flatMapSwitch(_ => submitProfile()) -->
        Observer[Either[ApiError, AuthResponse]] {
          case Right(res) =>
            Var.set(inFlightVar -> false, noticeVar -> Some(I18n.t(UiKeys.settingsProfileSaved)))
            // The server normalises the username, so the boxes are rewritten from its answer rather than from
            // what was typed — otherwise `Levente` would stay on screen while `levente` was what got stored.
            Var.set(
              usernameVar       -> res.user.username.getOrElse(""),
              nameVar           -> res.user.name.getOrElse(""),
            )
            AppState.setUser(res.user)
          case Left(err)  =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      passwordStream --> Observer[Unit](_ => Var.set(inFlightVar -> true, errorVar -> None, noticeVar -> None)),
      passwordStream.flatMapSwitch(_ => submitPassword()) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(
              inFlightVar        -> false,
              currentPasswordVar -> "",
              newPasswordVar     -> "",
              noticeVar          -> Some(I18n.t(UiKeys.settingsPasswordSaved)),
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
        .flatMapSwitch(email => ApiClient.resendVerification(email, captchaTokenVar.now())) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(errorVar -> None, noticeVar -> Some(I18n.t(UiKeys.settingsVerificationSent)))
            captchaResetBus.writer.onNext(())
          case Left(err) =>
            Var.set(errorVar -> Some(err.message), noticeVar -> None)
            captchaResetBus.writer.onNext(())
        },
      ApiClient.captchaStatus -->
        Observer[Either[ApiError, CaptchaStatusResponse]] {
          case Right(status) =>
            captchaStatusVar.set(Some(status))
          case Left(_)       =>
            captchaStatusVar.set(None)
        },
      shareCodeBus.events.flatMapSwitch(_ => ProgressShareApiClient.code()) -->
        Observer[Either[ApiError, ShareCodeResponse]] {
          case Right(response) =>
            Var.set(shareCodeVar -> Some(response.code), errorVar -> None)
          case Left(err)       =>
            errorVar.set(Some(err.message))
        },
      EventStream.unit().mergeWith(viewersReloadBus.events).flatMapSwitch(_ => ProgressShareApiClient.viewers()) -->
        Observer[Either[ApiError, List[SharedViewer]]] {
          case Right(list) =>
            viewersVar.set(list)
          case Left(err)   =>
            errorVar.set(Some(err.message))
        },
      revokeViewerBus.events.flatMapSwitch(ProgressShareApiClient.revokeViewer) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            viewersReloadBus.emit(())
          case Left(err) =>
            errorVar.set(Some(err.message))
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
        h2(cls := "card-title text-lg", I18n.t(UiKeys.settingsEmailCard)),
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
        span(cls := "badge badge-success", I18n.t(UiKeys.settingsVerified))
      else
        span(cls := "badge badge-warning", I18n.t(UiKeys.settingsNotVerified)),
    )
  }

  private def renderResend(): HtmlElement = {
    div(
      cls := "flex items-center justify-between gap-4 mt-2",
      p(cls := "text-sm opacity-70", I18n.t(UiKeys.settingsResendHint)),
      button(
        cls := "btn btn-sm",
        typ := "button",
        onClick.mapToUnit --> resendBus.writer,
        I18n.t(UiKeys.verificationResendButton),
      ),
      child.maybe <-- captchaSiteKeySignal.map(_.map(renderCaptcha)),
    )
  }

  private def renderCaptcha(siteKey: String): HtmlElement = {
    new CaptchaField(siteKey, captchaTokenVar.writer, captchaResetBus.events).render()
  }

  private def renderIdentities(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body",
        h2(cls := "card-title text-lg", I18n.t(UiKeys.settingsLinkedCard)),
        p(cls  := "text-sm opacity-70", I18n.t(UiKeys.settingsLinkedHint)),
        child.maybe <--
          identitiesSignal.map(identities =>
            Option.when(identities.isEmpty)(p(cls := "text-sm", I18n.t(UiKeys.settingsNothingLinked)))
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
          I18n.t(UiKeys.settingsUnlink),
        ),
        child.maybe <--
          isLastCredentialSignal.map(
            Option.when(_)(span(cls := "text-xs opacity-70", I18n.t(UiKeys.settingsSetPasswordFirst)))
          ),
      ),
    )
  }

  private def renderShareCard(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body gap-3",
        h2(cls := "card-title text-lg", I18n.t(UiKeys.settingsShareTitle)),
        p(cls  := "text-sm opacity-70", I18n.t(UiKeys.settingsShareHint)),
        div(
          cls  := "card-actions",
          button(
            cls := "btn btn-sm",
            typ := "button",
            I18n.t(UiKeys.settingsShareGetCode),
            onClick.mapToUnit --> shareCodeBus.writer,
          ),
        ),
        child.maybe <-- shareCodeVar.signal.map(_.map(renderShareCode)),
        renderViewers(),
        onMountCallback(_ => viewersReloadBus.emit(())),
      ),
    )
  }

  private def renderShareCode(shareCode: String): HtmlElement = {
    div(
      cls  := "alert alert-info flex flex-wrap items-center gap-2",
      role := "status",
      code(cls := "font-mono text-lg tracking-wider whitespace-nowrap", shareCode),
      button(
        cls    := "btn btn-xs",
        typ    := "button",
        I18n.t(UiKeys.settingsShareCopy),
        onClick.mapToUnit --> Observer[Unit](_ => copyShareCode(shareCode)),
      ),
    )
  }

  private def renderViewers(): HtmlElement = {
    div(
      h3(cls := "font-semibold text-sm mt-2", I18n.t(UiKeys.settingsShareViewersTitle)),
      child.maybe <--
        viewersVar.signal.map(list =>
          Option.when(list.isEmpty)(p(cls := "text-sm opacity-70", I18n.t(UiKeys.settingsShareViewersEmpty)))
        ),
      ul(
        cls  := "flex flex-col divide-y divide-base-300",
        children <-- viewersVar.signal.map(_.map(renderViewerRow)),
      ),
    )
  }

  private def renderViewerRow(viewer: SharedViewer): HtmlElement = {
    li(
      cls := "flex items-center justify-between gap-4 py-2",
      span(cls := "text-sm", viewer.email.getOrElse("—")),
      button(
        cls    := "btn btn-ghost btn-xs",
        typ    := "button",
        I18n.t(UiKeys.settingsShareRevoke),
        onClick.mapTo(viewer.userId) --> revokeViewerBus.writer,
      ),
    )
  }

  /** Feature-checked, like `AppShell.copyToClipboard`: the Clipboard API is absent in jsdom and on older browsers, and
    * a copy button that throws would take the page with it.
    */
  private def copyShareCode(value: String): Unit = {
    try {
      val clipboard = dom.window.navigator.asInstanceOf[scala.scalajs.js.Dynamic].clipboard
      if (!scala.scalajs.js.isUndefined(clipboard)) {
        clipboard.writeText(value)
        noticeVar.set(Some(I18n.t(UiKeys.settingsShareCopied)))
      }
    } catch { case _: Throwable => () }
  }

  /** Username and name, saved together. Both may be emptied: an empty box is sent as `None`, which clears the column
    * rather than failing validation — see `AuthService.updateProfile`.
    */
  private def renderProfileForm(): HtmlElement = {
    form(
      cls := "card bg-base-100 shadow",
      onSubmit.preventDefault.mapToUnit --> profileSubmitBus.writer,
      div(
        cls := "card-body",
        h2(cls := "card-title text-lg", I18n.t(UiKeys.settingsProfileCard)),
        p(cls  := "text-sm opacity-70", I18n.t(UiKeys.settingsProfileHint)),
        fieldSet(
          cls  := "fieldset",
          label(cls := "fieldset-legend", I18n.t(MessageKeys.fieldUsername)),
          input(
            cls     := "input w-full",
            typ     := "text",
            controlled(value <-- usernameVar.signal, onInput.mapToValue --> usernameVar.writer),
          ),
          p(
            cls     := "label",
            I18n.t(UiKeys.settingsUsernameHint, Validation.minUsernameLength, Validation.maxUsernameLength),
          ),
          label(cls := "fieldset-legend", I18n.t(MessageKeys.fieldName)),
          input(
            cls     := "input w-full",
            typ     := "text",
            controlled(value <-- nameVar.signal, onInput.mapToValue --> nameVar.writer),
          ),
          p(cls     := "label", I18n.t(UiKeys.settingsNameHint)),
        ),
        div(
          cls  := "card-actions justify-end mt-4",
          button(
            cls := "btn btn-primary",
            typ := "submit",
            disabled <-- inFlightSignal,
            I18n.t(UiKeys.settingsProfileSave),
          ),
        ),
      ),
    )
  }

  private def submitProfile(): EventStream[Either[ApiError, AuthResponse]] = {
    def entered(value: String): Option[String] = Option(value.trim).filter(_.nonEmpty)
    ApiClient.updateProfile(UpdateProfileRequest(entered(usernameVar.now()), entered(nameVar.now())))
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
                  label(cls := "fieldset-legend", I18n.t(UiKeys.settingsCurrentPassword)),
                  input(
                    cls     := "input w-full",
                    typ     := "password",
                    controlled(value <-- currentPasswordVar.signal, onInput.mapToValue --> currentPasswordVar.writer),
                  ),
                )
              )
            ),
          label(cls := "fieldset-legend", I18n.t(UiKeys.settingsNewPassword)),
          input(
            cls     := "input w-full",
            typ     := "password",
            controlled(value <-- newPasswordVar.signal, onInput.mapToValue --> newPasswordVar.writer),
          ),
          p(cls     := "label", I18n.t(UiKeys.commonPasswordHint, Validation.minPasswordLength)),
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
      I18n.t(UiKeys.settingsChangePassword)
    else
      I18n.t(UiKeys.settingsSetPassword)
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
