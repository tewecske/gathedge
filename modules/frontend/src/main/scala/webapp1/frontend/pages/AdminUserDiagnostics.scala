package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.api.{AdminApiClient, ApiError}
import webapp1.frontend.components.{Alert, Formats}
import webapp1.shared.domain.OAuthProvider
import webapp1.shared.dto.{
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
        completed("Email address confirmed.", accountChanged = true),
      resendBus.events.filterWith(inFlightSignal.not) --> started,
      resendBus.events
        .filterWith(inFlightSignal.not)
        .flatMapSwitch(_ => AdminApiClient.resendUserVerification(userId)) -->
        completed("Confirmation link sent.", accountChanged = false),
      revokeBus.events.filterWith(inFlightSignal.not) --> started,
      revokeBus.events.filterWith(inFlightSignal.not).flatMapSwitch(_ => AdminApiClient.revokeUserSessions(userId)) -->
        completed("Signed the user out everywhere.", accountChanged = false),
      lockoutBus.events.filterWith(inFlightSignal.not) --> started,
      lockoutBus.events.filterWith(inFlightSignal.not).flatMapSwitch(_ => AdminApiClient.clearUserLockout(userId)) -->
        completed("Sign-in lockout cleared.", accountChanged = false),
      unlinkBus.events.filterWith(inFlightSignal.not) --> started,
      unlinkBus.events
        .filterWith(inFlightSignal.not)
        .flatMapSwitch(provider => AdminApiClient.unlinkUserIdentity(userId, provider)) -->
        completed("Social account detached.", accountChanged = true),
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
      "Email confirmation",
      p(
        cls := "text-sm",
        detail.emailVerifiedAt match {
          case Some(at) =>
            s"Confirmed on ${Formats.dateTime(at)}."
          case None     =>
            "This address has never been confirmed."
        },
      ),
      renderTokens(detail.verificationTokens),
      div(
        cls := "card-actions mt-2 gap-2",
        // Only offered while it would do something; confirming an already-confirmed address is a no-op server-side
        // too, but a button that does nothing is a button that gets clicked to find out.
        if (detail.emailVerifiedAt.isEmpty)
          actionButton("Mark confirmed", "btn-primary", None, verifyBus)
        else
          emptyNode,
        actionButton("Send a confirmation link", "btn-outline", None, resendBus),
      ),
    )
  }

  private def renderTokens(tokens: List[AdminVerificationTokenInfo]): HtmlElement = {
    tokens.headOption match {
      case None        =>
        p(cls := "text-sm opacity-60", "No confirmation link has been issued.")
      case Some(token) =>
        val state = {
          if (token.consumed)
            "already used"
          else if (token.expired)
            "expired"
          else
            s"valid until ${Formats.dateTime(token.expiresAt)}"
        }
        p(cls := "text-sm opacity-60", s"Last link issued ${Formats.dateTime(token.createdAt)} — $state.")
    }
  }

  private def renderSecurityCard(detail: AdminUserDetail): HtmlElement = {
    card(
      "Sign-in security",
      renderLockout(detail.lockout),
      renderAttempts(detail.recentLoginAttempts),
      div(
        cls := "card-actions mt-2",
        if (detail.lockout.blocked)
          actionButton("Clear lockout", "btn-primary", None, lockoutBus)
        else
          emptyNode,
      ),
    )
  }

  private def renderLockout(lockout: LockoutStatus): HtmlElement = {
    if (lockout.blocked) {
      Alert.error(
        s"Locked out: ${lockout.attempts} of ${lockout.maxAttempts} failed attempts in the last " +
          s"${lockout.windowMinutes} minutes. Unblocks itself in ${Formats.minutes(lockout.retryAfterMillis)}."
      )
    } else {
      p(
        cls := "text-sm",
        s"Not locked out — ${lockout.attempts} of ${lockout.maxAttempts} failed attempts in the last " +
          s"${lockout.windowMinutes} minutes.",
      )
    }
  }

  private def renderAttempts(attempts: List[LoginAttemptEntry]): HtmlElement = {
    if (attempts.isEmpty) {
      p(cls := "text-sm opacity-60", "No recorded sign-in attempts.")
    } else {
      div(
        cls := "overflow-x-auto mt-2",
        table(
          cls := "table table-sm",
          thead(tr(th("When"), th("Outcome"), th("From"))),
          tbody(
            attempts.map { attempt =>
              tr(
                td(Formats.dateTime(attempt.occurredAt)),
                td(renderOutcome(attempt.outcome)),
                td(cls := "font-mono text-xs", attempt.ip.getOrElse("—")),
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
    span(cls := s"badge $style", LoginOutcome.display(outcome))
  }

  private def renderSessionsCard(detail: AdminUserDetail): HtmlElement = {
    card(
      "Sessions",
      p(
        cls := "text-sm",
        s"${detail.activeSessions} active session(s), ${detail.sessions.size} recorded in total.",
      ),
      renderSessions(detail.sessions.filter(_.active)),
      div(
        cls := "card-actions mt-2",
        if (detail.activeSessions > 0) {
          actionButton(
            "Sign out everywhere",
            "btn-error btn-outline",
            Some("End every session this user holds? They will have to sign in again."),
            revokeBus,
          )
        } else
          emptyNode,
      ),
    )
  }

  private def renderSessions(sessions: List[AdminSessionInfo]): HtmlElement = {
    if (sessions.isEmpty) {
      p(cls := "text-sm opacity-60", "No active sessions.")
    } else {
      div(
        cls := "overflow-x-auto mt-2",
        table(
          cls := "table table-sm",
          // No session identifier of any kind: the sessions table's primary key *is* the bearer token, so there is
          // nothing safe to show. This is also why there is no per-session revoke button.
          thead(tr(th("Signed in"), th("Expires"))),
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
      "Linked social accounts",
      if (detail.identities.isEmpty)
        p(cls := "text-sm opacity-60", "None linked.")
      else
        div(
          cls := "overflow-x-auto",
          table(
            cls := "table table-sm",
            thead(tr(th("Provider"), th("Reported address"), th("Linked"), th())),
            tbody(detail.identities.map(renderIdentity)),
          ),
        ),
      p(
        cls := "text-sm opacity-60 mt-2",
        if (detail.hasPassword)
          "This account also has a password."
        else
          "This account has no password, so its last linked provider cannot be detached.",
      ),
    )
  }

  private def renderIdentity(identity: AdminIdentityInfo): HtmlElement = {
    tr(
      td(OAuthProvider.displayName(identity.provider)),
      td(identity.email.getOrElse("—")),
      td(Formats.dateTime(identity.createdAt)),
      td(
        button(
          cls := "btn btn-xs btn-error btn-outline",
          typ := "button",
          disabled <-- inFlightSignal,
          "Detach",
          onClick.mapToUnit -->
            Observer[Unit] { _ =>
              // The server refuses to remove an account's last credential (409); this only asks first.
              if (
                dom.window.confirm(
                  s"Detach the ${OAuthProvider.displayName(identity.provider)} account? " +
                    "The user will no longer be able to sign in with it."
                )
              )
                unlinkBus.emit(identity.provider)
            },
        )
      ),
    )
  }
}
