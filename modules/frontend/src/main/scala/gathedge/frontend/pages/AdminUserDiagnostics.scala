package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.api.{AdminApiClient, ApiError}
import gathedge.frontend.components.{Alert, Formats, Labels}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.OAuthProvider
import gathedge.shared.i18n.UiKeys
import gathedge.shared.dto.{
  AdminIdentityInfo,
  AdminSessionInfo,
  AdminUserDetail,
  AdminVerificationTokenInfo,
  LockoutStatus,
  LoginAttemptEntry,
  LoginOutcome,
}

/** The four cards under the edit form on the administrator's account screen: email confirmation, sign-in security,
  * sessions and linked accounts.
  *
  * Split out of [[AdminUserDetailPage]] because it answers a different question — that form is "change this account",
  * these are "why can this person not get in" — and because it is driven by one endpoint (`userDetail`) that the form
  * does not use.
  *
  * Every action reloads the whole card set on success rather than patching it: the actions interact (confirming an
  * address spends the outstanding link; clearing a lockout changes what the attempts table means), and a re-read is
  * both simpler and the same rule the rest of the app follows.
  *
  * @param userChanged
  *   fires when an action changed the account itself, so the page above can re-read the user it is editing.
  */
private class AdminUserDiagnostics(userId: Long, userChanged: Observer[Unit]) {

  private val detailVar: Var[Option[AdminUserDetail]] = Var(None)
  private val detailSignal                            = detailVar.signal

  private val errorVar: Var[Option[String]] = Var(None)
  private val infoVar: Var[Option[String]]  = Var(None)
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal

  private val loadBus    = new EventBus[Unit]()
  private val verifyBus  = new EventBus[Unit]()
  private val resendBus  = new EventBus[Unit]()
  private val revokeBus  = new EventBus[Unit]()
  private val lockoutBus = new EventBus[Unit]()
  private val unlinkBus  = new EventBus[OAuthProvider]()

  /** One place to say what a completed action does, so the five of them cannot drift: report it, re-read the cards, and
    * — for the ones that change the account rather than its sessions — tell the page above to re-read too.
    */
  private def completed(message: String, accountChanged: Boolean): Observer[Either[ApiError, Unit]] = {
    Observer[Either[ApiError, Unit]] {
      case Right(_)  =>
        Var.set(inFlightVar -> false, infoVar -> Some(message), errorVar -> None)
        loadBus.emit(())
        if (accountChanged)
          userChanged.onNext(())
      case Left(err) =>
        Var.set(inFlightVar -> false, errorVar -> Some(err.message), infoVar -> None)
    }
  }

  private def started[A]: Observer[A] = {
    Observer[A](_ => Var.set(inFlightVar -> true, errorVar -> None, infoVar -> None))
  }

  def render(): HtmlElement = {
    div(
      cls := "mt-6 flex flex-col gap-4",
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(infoVar.signal),
      child.maybe <-- detailSignal.map(_.map(renderVerificationCard)),
      child.maybe <-- detailSignal.map(_.map(renderSecurityCard)),
      child.maybe <-- detailSignal.map(_.map(renderSessionsCard)),
      child.maybe <-- detailSignal.map(_.map(renderIdentitiesCard)),
      loadBus.events.flatMapSwitch(_ => AdminApiClient.userDetail(userId)) -->
        Observer[Either[ApiError, AdminUserDetail]] {
          case Right(detail) =>
            detailVar.set(Some(detail))
          // A failure here leaves the cards absent rather than showing an error banner over the edit form: the page
          // above already reports whether the account itself could be loaded, and two banners for one dead API say
          // nothing the first does not.
          case Left(_)       =>
            detailVar.set(None)
        },
      verifyBus.events.filterWith(inFlightSignal.not) --> started,
      verifyBus.events.filterWith(inFlightSignal.not).flatMapSwitch(_ => AdminApiClient.verifyUserEmail(userId)) -->
        completed(I18n.t(UiKeys.adminDiagEmailConfirmed), accountChanged = true),
      resendBus.events.filterWith(inFlightSignal.not) --> started,
      resendBus.events
        .filterWith(inFlightSignal.not)
        .flatMapSwitch(_ => AdminApiClient.resendUserVerification(userId)) -->
        completed(I18n.t(UiKeys.adminDiagLinkSent), accountChanged = false),
      revokeBus.events.filterWith(inFlightSignal.not) --> started,
      revokeBus.events.filterWith(inFlightSignal.not).flatMapSwitch(_ => AdminApiClient.revokeUserSessions(userId)) -->
        completed(I18n.t(UiKeys.adminDiagSignedOut), accountChanged = false),
      lockoutBus.events.filterWith(inFlightSignal.not) --> started,
      lockoutBus.events.filterWith(inFlightSignal.not).flatMapSwitch(_ => AdminApiClient.clearUserLockout(userId)) -->
        completed(I18n.t(UiKeys.adminDiagLockoutCleared), accountChanged = false),
      unlinkBus.events.filterWith(inFlightSignal.not) --> started,
      unlinkBus.events
        .filterWith(inFlightSignal.not)
        .flatMapSwitch(provider => AdminApiClient.unlinkUserIdentity(userId, provider)) -->
        completed(I18n.t(UiKeys.adminDiagDetached), accountChanged = true),
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def card(title: String, body: Modifier[HtmlElement]*): HtmlElement = {
    div(cls := "card bg-base-100 shadow", div(cls := "card-body", h2(cls := "card-title text-base", title), body))
  }

  private def actionButton(label: String, style: String, confirmation: Option[String], bus: EventBus[Unit]) = {
    button(
      cls := s"btn btn-sm $style",
      typ := "button",
      disabled <-- inFlightSignal,
      label,
      onClick.mapToUnit -->
        Observer[Unit] { _ =>
          if (confirmation.forall(dom.window.confirm))
            bus.emit(())
        },
    )
  }

  private def renderVerificationCard(detail: AdminUserDetail): HtmlElement = {
    card(
      I18n.t(UiKeys.adminDiagVerificationCard),
      p(
        cls := "text-sm",
        detail.emailVerifiedAt match {
          case Some(at) =>
            I18n.t(UiKeys.adminDiagConfirmedOn, Formats.dateTime(at))
          case None     =>
            I18n.t(UiKeys.adminDiagNeverConfirmed)
        },
      ),
      renderTokens(detail.verificationTokens),
      div(
        cls := "card-actions mt-2 gap-2",
        // Only offered while it would do something; confirming an already-confirmed address is a no-op server-side
        // too, but a button that does nothing is a button that gets clicked to find out.
        if (detail.emailVerifiedAt.isEmpty)
          actionButton(I18n.t(UiKeys.adminDiagMarkConfirmed), "btn-primary", None, verifyBus)
        else
          emptyNode,
        actionButton(I18n.t(UiKeys.adminDiagSendLink), "btn-outline", None, resendBus),
      ),
    )
  }

  private def renderTokens(tokens: List[AdminVerificationTokenInfo]): HtmlElement = {
    tokens.headOption match {
      case None        =>
        p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminDiagNoToken))
      case Some(token) =>
        val state = {
          if (token.consumed)
            I18n.t(UiKeys.adminDiagTokenUsed)
          else if (token.expired)
            I18n.t(UiKeys.adminDiagTokenExpired)
          else
            I18n.t(UiKeys.adminDiagTokenValid, Formats.dateTime(token.expiresAt))
        }
        p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminDiagLastToken, Formats.dateTime(token.createdAt), state))
    }
  }

  private def renderSecurityCard(detail: AdminUserDetail): HtmlElement = {
    card(
      I18n.t(UiKeys.adminDiagSecurityCard),
      renderLockout(detail.lockout),
      renderAttempts(detail.recentLoginAttempts),
      div(
        cls := "card-actions mt-2",
        if (detail.lockout.blocked)
          actionButton(I18n.t(UiKeys.adminDiagClearLockout), "btn-primary", None, lockoutBus)
        else
          emptyNode,
      ),
    )
  }

  private def renderLockout(lockout: LockoutStatus): HtmlElement = {
    if (lockout.blocked) {
      Alert.error(
        I18n.t(
          UiKeys.adminDiagLockedOut,
          lockout.attempts,
          lockout.maxAttempts,
          lockout.windowMinutes,
          Formats.minutes(lockout.retryAfterMillis),
        )
      )
    } else {
      p(
        cls := "text-sm",
        I18n.t(UiKeys.adminDiagNotLockedOut, lockout.attempts, lockout.maxAttempts, lockout.windowMinutes),
      )
    }
  }

  private def renderAttempts(attempts: List[LoginAttemptEntry]): HtmlElement = {
    if (attempts.isEmpty) {
      p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminDiagNoAttempts))
    } else {
      div(
        cls := "overflow-x-auto mt-2",
        table(
          cls := "table table-sm",
          thead(
            tr(th(I18n.t(UiKeys.commonWhen)), th(I18n.t(UiKeys.adminDiagColOutcome)), th(I18n.t(UiKeys.commonFrom)))
          ),
          tbody(
            attempts.map { attempt =>
              tr(
                td(Formats.dateTime(attempt.occurredAt)),
                td(renderOutcome(attempt.outcome)),
                td(cls := "font-mono text-xs", attempt.ip.getOrElse(I18n.t(UiKeys.commonNone))),
              )
            }
          ),
        ),
      )
    }
  }

  private def renderOutcome(outcome: String): HtmlElement = {
    val style = {
      if (outcome == LoginOutcome.success)
        "badge-success badge-soft"
      else if (outcome == LoginOutcome.rateLimited)
        "badge-error"
      else
        "badge-warning badge-soft"
    }
    span(cls := s"badge $style", Labels.loginOutcome(outcome))
  }

  private def renderSessionsCard(detail: AdminUserDetail): HtmlElement = {
    card(
      I18n.t(UiKeys.adminDiagSessionsCard),
      p(
        cls := "text-sm",
        I18n.plural(UiKeys.adminDiagSessionsCount, detail.activeSessions.toLong, detail.sessions.size),
      ),
      renderSessions(detail.sessions.filter(_.active)),
      div(
        cls := "card-actions mt-2",
        if (detail.activeSessions > 0) {
          actionButton(
            I18n.t(UiKeys.adminDiagSignOutEverywhere),
            "btn-error btn-outline",
            Some(I18n.t(UiKeys.adminDiagSignOutConfirm)),
            revokeBus,
          )
        } else
          emptyNode,
      ),
    )
  }

  private def renderSessions(sessions: List[AdminSessionInfo]): HtmlElement = {
    if (sessions.isEmpty) {
      p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminDiagNoSessions))
    } else {
      div(
        cls := "overflow-x-auto mt-2",
        table(
          cls := "table table-sm",
          // No session identifier of any kind: the sessions table's primary key *is* the bearer token, so there is
          // nothing safe to show. This is also why there is no per-session revoke button.
          thead(tr(th(I18n.t(UiKeys.adminDiagColSignedIn)), th(I18n.t(UiKeys.adminDiagColExpires)))),
          tbody(
            sessions.map { session =>
              tr(td(Formats.dateTime(session.createdAt)), td(Formats.dateTime(session.expiresAt)))
            }
          ),
        ),
      )
    }
  }

  private def renderIdentitiesCard(detail: AdminUserDetail): HtmlElement = {
    card(
      I18n.t(UiKeys.adminDiagIdentitiesCard),
      if (detail.identities.isEmpty)
        p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminDiagNoneLinked))
      else
        div(
          cls := "overflow-x-auto",
          table(
            cls := "table table-sm",
            thead(
              tr(
                th(I18n.t(UiKeys.adminDiagColProvider)),
                th(I18n.t(UiKeys.adminDiagColReportedAddress)),
                th(I18n.t(UiKeys.adminDiagColLinked)),
                th(),
              )
            ),
            tbody(detail.identities.map(renderIdentity)),
          ),
        ),
      p(
        cls := "text-sm opacity-60 mt-2",
        if (detail.hasPassword)
          I18n.t(UiKeys.adminDiagHasPassword)
        else
          I18n.t(UiKeys.adminDiagNoPassword),
      ),
    )
  }

  private def renderIdentity(identity: AdminIdentityInfo): HtmlElement = {
    tr(
      td(OAuthProvider.displayName(identity.provider)),
      td(identity.email.getOrElse(I18n.t(UiKeys.commonNone))),
      td(Formats.dateTime(identity.createdAt)),
      td(
        button(
          cls := "btn btn-xs btn-error btn-outline",
          typ := "button",
          disabled <-- inFlightSignal,
          I18n.t(UiKeys.adminDiagDetach),
          onClick.mapToUnit -->
            Observer[Unit] { _ =>
              // The server refuses to remove an account's last credential (409); this only asks first.
              if (
                dom.window.confirm(
                  I18n.t(UiKeys.adminDiagDetachConfirm, OAuthProvider.displayName(identity.provider))
                )
              )
                unlinkBus.emit(identity.provider)
            },
        )
      ),
    )
  }
}
