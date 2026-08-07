package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.api.{AdminApiClient, ApiError}
import webapp1.frontend.components.{AdminSubmenu, Alert, AppShell, Formats}
import webapp1.frontend.Page
import webapp1.shared.domain.OAuthProvider
import webapp1.shared.dto.{ConfigSummary, DbStats, JobStatus, RuntimeInfo, SystemOverview}

/** What this deployment is configured to do, what it is currently doing, and how much it is holding.
  *
  * None of it was reachable from the application before: the configuration only ever reached the log at startup, the
  * background daemons reported nothing at all, and the row counts needed a database session. Everything here is derived
  * — no configured secret is on the wire, so the page is safe to screenshot into a ticket.
  */
object AdminSystemPage {
  def render(): HtmlElement = AppShell.render(Page.AdminSystem, new AdminSystemPage().render())
}

private class AdminSystemPage {

  private val overviewVar: Var[Option[SystemOverview]] = Var(None)
  private val overviewSignal                           = overviewVar.signal

  private val errorVar: Var[Option[String]] = Var(None)
  private val infoVar: Var[Option[String]]  = Var(None)
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal

  private val loadBus       = new EventBus[Unit]()
  private val pruneBus      = new EventBus[Unit]()
  private val clearLocksBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", "System overview"),
      AdminSubmenu.render(Page.AdminSystem),
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(infoVar.signal),
      child.maybe <-- overviewSignal.map(_.map(_.config).map(renderIssues)),
      div(
        cls  := "grid gap-4 lg:grid-cols-3",
        child.maybe <-- overviewSignal.map(_.map(overview => renderConfig(overview.config))),
        child.maybe <-- overviewSignal.map(_.map(overview => renderRuntime(overview.runtime, overview.jobs))),
        child.maybe <-- overviewSignal.map(_.map(overview => renderStats(overview.stats))),
      ),
      renderMaintenance(),
      loadBus.events.flatMapSwitch(_ => AdminApiClient.systemOverview) -->
        Observer[Either[ApiError, SystemOverview]] {
          case Right(overview) =>
            Var.set(overviewVar -> Some(overview), errorVar -> None)
          case Left(err)       =>
            errorVar.set(Some(err.message))
        },
      pruneBus.events.filterWith(inFlightSignal.not) --> Observer[Unit](_ => started()),
      pruneBus.events.filterWith(inFlightSignal.not).flatMapSwitch(_ => AdminApiClient.systemPrune) -->
        Observer[Either[ApiError, webapp1.shared.dto.PruneResult]] {
          case Right(result) =>
            Var.set(
              inFlightVar -> false,
              errorVar    -> None,
              infoVar     -> Some(
                s"Removed ${result.sessions} session(s), ${result.verificationTokens} confirmation link(s) and " +
                  s"${result.rateLimitKeys} stale rate-limit key(s)."
              ),
            )
            loadBus.emit(())
          case Left(err)     =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message), infoVar -> None)
        },
      clearLocksBus.events.filterWith(inFlightSignal.not) --> Observer[Unit](_ => started()),
      clearLocksBus.events
        .filterWith(inFlightSignal.not)
        .flatMapSwitch(_ => AdminApiClient.clearRateLimits(None)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(inFlightVar -> false, errorVar -> None, infoVar -> Some("Every rate-limit lock was cleared."))
            loadBus.emit(())
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message), infoVar -> None)
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def started(): Unit = {
    Var.set(inFlightVar -> true, errorVar -> None, infoVar -> None)
  }

  private def card(title: String, body: Modifier[HtmlElement]*): HtmlElement = {
    div(cls := "card bg-base-100 shadow", div(cls := "card-body", h2(cls := "card-title text-base", title), body))
  }

  /** One label/value row. `warn` turns the value into a badge, which is how a number that means something is wrong gets
    * noticed on a page that is otherwise a wall of numbers.
    */
  private def row(label: String, value: String, warn: Boolean = false): HtmlElement = {
    div(
      cls := "flex justify-between gap-4 text-sm py-1 border-b border-base-200 last:border-0",
      span(cls := "opacity-60", label),
      if (warn)
        span(cls := "badge badge-warning badge-sm", value)
      else
        span(cls := "font-medium text-right break-all", value),
    )
  }

  private def yesNo(value: Boolean): String = {
    if (value)
      "yes"
    else
      "no"
  }

  /** `AppConfig.productionIssues` is empty unless the deployment is in production *and* misconfigured, in which case
    * the process refuses to boot — so this banner is unreachable on a running server today. It is here because the
    * check is the one piece of configuration whose whole point is to be read, and a future issue that only warns would
    * otherwise appear nowhere.
    */
  private def renderIssues(config: ConfigSummary): HtmlElement = {
    if (config.productionIssues.isEmpty)
      emptyNode.asInstanceOf[HtmlElement]
    else {
      div(
        role := "alert",
        cls  := "alert alert-error mb-4 flex-col items-start",
        span(cls := "font-semibold", "This configuration is unsafe for production:"),
        ul(cls   := "list-disc ml-6", config.productionIssues.map(issue => li(issue))),
      )
    }
  }

  private def renderConfig(config: ConfigSummary): HtmlElement = {
    card(
      "Configuration",
      row("Environment", config.env),
      row("Public base URL", config.publicBaseUrl),
      row("Listening on", s"${config.serverHost}:${config.serverPort}"),
      row("Email confirmation required", yesNo(config.requireEmailVerification)),
      row(
        "Secure session cookie",
        yesNo(config.sessionCookieSecure),
        warn = config.production && !config.sessionCookieSecure,
      ),
      row(
        "Social sign-in",
        if (config.configuredOAuthProviders.isEmpty)
          "none configured"
        else
          config.configuredOAuthProviders.map(OAuthProvider.displayName).mkString(", "),
      ),
      row(
        "Outgoing mail",
        // The distinction that matters in development: with no SMTP host the link is logged rather than sent, which
        // is why a confirmation email that "never arrives" is usually this line.
        if (config.mailConfigured)
          s"${config.mailSmtpHost.getOrElse("")}:${config.mailSmtpPort}"
        else
          "logged, not sent",
      ),
      row("Mail from", config.mailFrom),
      row("STARTTLS", yesNo(config.mailStartTls)),
      row("Database", config.databaseUrl),
      row("Database user", config.databaseUser),
      row("Session lifetime", s"${config.sessionValidityHours} hour(s)"),
      row("Invitation lifetime", s"${config.invitationValidityHours} hour(s)"),
      row("Confirmation link lifetime", s"${config.verificationValidityHours} hour(s)"),
      row(
        "Sign-in rate limit",
        s"${config.rateLimitMaxAttempts} attempts / ${config.rateLimitWindowMinutes} minutes",
      ),
      row(
        "Netty threads",
        if (config.nettyMaxThreads == 0)
          "automatic"
        else
          config.nettyMaxThreads.toString,
      ),
    )
  }

  private def renderRuntime(runtime: RuntimeInfo, jobs: List[JobStatus]): HtmlElement = {
    card(
      "Runtime",
      row("API version", runtime.apiVersion),
      row("Started", Formats.dateTime(runtime.startedAt)),
      row("Uptime", Formats.duration(runtime.uptimeMillis)),
      row("JVM", runtime.jvmVersion),
      row("Processors", runtime.availableProcessors.toString),
      row("Heap", s"${Formats.bytes(runtime.heapUsedBytes)} of ${Formats.bytes(runtime.heapMaxBytes)}"),
      row("Live threads", runtime.liveThreads.toString),
      row(
        "Schema",
        runtime.migrations.flatMap(_.version).lastOption.map(version => s"V$version").getOrElse("unknown"),
      ),
      row("Migrations applied", runtime.migrations.size.toString),
      h3(cls := "font-semibold text-sm mt-3", "Background jobs"),
      if (jobs.isEmpty)
        p(cls := "text-sm opacity-60", "None reporting.")
      else
        div(jobs.map(renderJob)),
    )
  }

  private def renderJob(job: JobStatus): HtmlElement = {
    val description = {
      job.lastError match {
        case Some(error) =>
          s"failed: $error"
        case None        =>
          job.lastRunAt match {
            case Some(at) =>
              s"${Formats.dateTime(at)} — ${job.lastOutcome.getOrElse("ran")}"
            // A job registers itself before its first pass, so "not yet" is normal right after a restart and a
            // symptom an interval later.
            case None     =>
              s"not run yet (every ${job.intervalMinutes} minutes)"
          }
      }
    }
    row(job.name, description, warn = job.lastError.isDefined)
  }

  private def renderStats(stats: DbStats): HtmlElement = {
    card(
      "Statistics",
      row("Accounts", stats.users.toString),
      row("Administrators", stats.admins.toString),
      row("Unconfirmed addresses", stats.unverifiedUsers.toString, warn = stats.unverifiedUsers > 0),
      row("Accounts without a password", stats.usersWithoutPassword.toString),
      row("Linked social accounts", stats.oauthIdentities.toString),
      row("Sessions", s"${stats.activeSessions} active of ${stats.sessions}"),
      row("Confirmation links", stats.verificationTokens.toString),
      // Both of these mean the reaper has not run, or has been failing: they are the numbers that turn "the job says
      // it ran" into "the job actually did something".
      row(
        "…expired, not yet pruned",
        stats.expiredVerificationTokens.toString,
        warn = stats.expiredVerificationTokens > 0,
      ),
      row("Groups", s"${stats.groups} with ${stats.groupMembers} member(s)"),
      row("Invitations", s"${stats.pendingInvitations} pending, ${stats.acceptedInvitations} accepted"),
      // Counts only: no administrator may read a task board or a group's entries.
      row("Task items", stats.todoItems.toString),
      row("Group entries", stats.groupPairs.toString),
      row("Recorded sign-in attempts", stats.loginAttempts.toString),
      row("Failed sign-ins (24h)", stats.failedLoginsLast24h.toString, warn = stats.failedLoginsLast24h > 0),
      row("Accounts locked out now", stats.blockedRateLimitKeys.toString, warn = stats.blockedRateLimitKeys > 0),
      row("Audit entries", stats.auditEntries.toString),
    )
  }

  private def renderMaintenance(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mt-4",
      div(
        cls := "card-body",
        h2(cls := "card-title text-base", "Maintenance"),
        p(
          cls  := "text-sm opacity-60",
          "Pruning is the hourly sweep, run now. Clearing locks lets every currently blocked address try again, " +
            "including one that is being brute-forced.",
        ),
        div(
          cls  := "card-actions gap-2",
          button(
            cls := "btn btn-sm btn-outline",
            typ := "button",
            disabled <-- inFlightSignal,
            "Prune expired sessions and links",
            onClick.mapToUnit --> Observer[Unit](_ => pruneBus.emit(())),
          ),
          button(
            cls := "btn btn-sm btn-error btn-outline",
            typ := "button",
            disabled <-- inFlightSignal,
            "Clear every rate-limit lock",
            onClick.mapToUnit -->
              Observer[Unit] { _ =>
                if (
                  dom.window.confirm(
                    "Let every currently locked-out address sign in again? " +
                      "Use the account's own page to unlock one person."
                  )
                )
                  clearLocksBus.emit(())
              },
          ),
        ),
      ),
    )
  }
}
