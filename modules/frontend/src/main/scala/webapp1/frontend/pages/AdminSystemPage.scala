package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.api.{AdminApiClient, ApiError}
import webapp1.frontend.components.{AdminSubmenu, Alert, AppShell, Formats}
import webapp1.frontend.i18n.I18n
import webapp1.frontend.Page
import webapp1.shared.domain.OAuthProvider
import webapp1.shared.dto.{ConfigSummary, DbStats, JobStatus, PruneResult, RuntimeInfo, SystemOverview}
import webapp1.shared.i18n.UiKeys

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
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.adminSystemTitle)),
      AdminSubmenu.render(Page.AdminSystem),
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(infoVar.signal),
      child.maybe <-- overviewSignal.map(_.map(_.config).flatMap(renderIssues)),
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
        Observer[Either[ApiError, PruneResult]] {
          case Right(result) =>
            Var.set(
              inFlightVar -> false,
              errorVar    -> None,
              infoVar     -> Some(pruneMessage(result)),
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
            Var.set(inFlightVar -> false, errorVar -> None, infoVar -> Some(I18n.t(UiKeys.adminSystemLocksCleared)))
            loadBus.emit(())
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message), infoVar -> None)
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  /** The four counts are pluralised one at a time and spliced into a frame, rather than written as a single sentence
    * with four numbers in it: only English needs the plural forms, and building it this way also keeps the Hungarian
    * frame clear of the definite article that no placeholder can carry.
    */
  private def pruneMessage(result: PruneResult): String = {
    I18n.t(
      UiKeys.adminSystemPruneDone,
      I18n.plural(UiKeys.adminSystemPruneSessions, result.sessions.toLong),
      I18n.plural(UiKeys.adminSystemPruneTokens, result.verificationTokens.toLong),
      I18n.plural(UiKeys.adminSystemPruneAttempts, result.loginAttempts.toLong),
      I18n.plural(UiKeys.adminSystemPruneKeys, result.rateLimitKeys.toLong),
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
      I18n.t(UiKeys.commonYes)
    else
      I18n.t(UiKeys.commonNo)
  }

  /** `AppConfig.productionIssues` is empty unless the deployment is in production *and* misconfigured, in which case
    * the process refuses to boot — so this banner is unreachable on a running server today. It is here because the
    * check is the one piece of configuration whose whole point is to be read, and a future issue that only warns would
    * otherwise appear nowhere.
    *
    * Answers `Option`, not an `emptyNode` cast: `emptyNode` is a `CommentNode`, so `asInstanceOf[HtmlElement]` threw a
    * `ClassCastException` on every load — and because the three cards below read the same signal, that one exception
    * aborted the Airstream transaction and left them unrendered too.
    */
  private def renderIssues(config: ConfigSummary): Option[HtmlElement] = {
    if (config.productionIssues.isEmpty)
      None
    else {
      Some(
        div(
          role := "alert",
          cls  := "alert alert-error mb-4 flex-col items-start",
          span(cls := "font-semibold", I18n.t(UiKeys.adminSystemUnsafeConfig)),
          ul(cls   := "list-disc ml-6", config.productionIssues.map(issue => li(issue))),
        )
      )
    }
  }

  private def renderConfig(config: ConfigSummary): HtmlElement = {
    card(
      I18n.t(UiKeys.adminSystemConfigCard),
      row(I18n.t(UiKeys.adminSystemConfigEnv), config.env),
      row(I18n.t(UiKeys.adminSystemConfigBaseUrl), config.publicBaseUrl),
      row(I18n.t(UiKeys.adminSystemConfigListening), s"${config.serverHost}:${config.serverPort}"),
      row(I18n.t(UiKeys.adminSystemConfigRequireVerify), yesNo(config.requireEmailVerification)),
      row(
        I18n.t(UiKeys.adminSystemConfigSecureCookie),
        yesNo(config.sessionCookieSecure),
        warn = config.production && !config.sessionCookieSecure,
      ),
      row(
        I18n.t(UiKeys.adminSystemConfigSocial),
        if (config.configuredOAuthProviders.isEmpty)
          I18n.t(UiKeys.adminSystemConfigSocialNone)
        else
          config.configuredOAuthProviders.map(OAuthProvider.displayName).mkString(", "),
      ),
      row(
        I18n.t(UiKeys.adminSystemConfigMail),
        // The distinction that matters in development: with no SMTP host the link is logged rather than sent, which
        // is why a confirmation email that "never arrives" is usually this line.
        if (config.mailConfigured)
          s"${config.mailSmtpHost.getOrElse("")}:${config.mailSmtpPort}"
        else
          I18n.t(UiKeys.adminSystemConfigMailLogged),
      ),
      row(I18n.t(UiKeys.adminSystemConfigMailFrom), config.mailFrom),
      row(I18n.t(UiKeys.adminSystemConfigStartTls), yesNo(config.mailStartTls)),
      row(I18n.t(UiKeys.adminSystemConfigDatabase), config.databaseUrl),
      row(I18n.t(UiKeys.adminSystemConfigDatabaseUser), config.databaseUser),
      row(I18n.t(UiKeys.adminSystemConfigSessionLife), hours(config.sessionValidityHours)),
      row(I18n.t(UiKeys.adminSystemConfigVerifyLife), hours(config.verificationValidityHours)),
      row(
        I18n.t(UiKeys.adminSystemConfigRateLimit),
        I18n.t(UiKeys.adminSystemConfigRateLimitValue, config.rateLimitMaxAttempts, config.rateLimitWindowMinutes),
      ),
      row(
        I18n.t(UiKeys.adminSystemConfigNettyThreads),
        if (config.nettyMaxThreads == 0)
          I18n.t(UiKeys.adminSystemConfigNettyAuto)
        else
          config.nettyMaxThreads.toString,
      ),
    )
  }

  private def hours(value: Long): String = I18n.plural(UiKeys.adminSystemConfigHours, value)

  private def renderRuntime(runtime: RuntimeInfo, jobs: List[JobStatus]): HtmlElement = {
    card(
      I18n.t(UiKeys.adminSystemRuntimeCard),
      row(I18n.t(UiKeys.adminSystemRuntimeApiVersion), runtime.apiVersion),
      row(I18n.t(UiKeys.adminSystemRuntimeStarted), Formats.dateTime(runtime.startedAt)),
      row(I18n.t(UiKeys.adminSystemRuntimeUptime), Formats.duration(runtime.uptimeMillis)),
      row(I18n.t(UiKeys.adminSystemRuntimeJvm), runtime.jvmVersion),
      row(I18n.t(UiKeys.adminSystemRuntimeProcessors), runtime.availableProcessors.toString),
      row(
        I18n.t(UiKeys.adminSystemRuntimeHeap),
        I18n.t(
          UiKeys.adminSystemRuntimeHeapValue,
          Formats.bytes(runtime.heapUsedBytes),
          Formats.bytes(runtime.heapMaxBytes),
        ),
      ),
      row(I18n.t(UiKeys.adminSystemRuntimeThreads), runtime.liveThreads.toString),
      row(
        I18n.t(UiKeys.adminSystemRuntimeSchema),
        runtime.migrations
          .flatMap(_.version)
          .lastOption
          .map(version => s"V$version")
          .getOrElse(I18n.t(UiKeys.adminSystemRuntimeSchemaUnknown)),
      ),
      row(I18n.t(UiKeys.adminSystemRuntimeMigrations), runtime.migrations.size.toString),
      h3(cls := "font-semibold text-sm mt-3", I18n.t(UiKeys.adminSystemJobsHeading)),
      if (jobs.isEmpty)
        p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminSystemJobsNone))
      else
        div(jobs.map(renderJob)),
    )
  }

  private def renderJob(job: JobStatus): HtmlElement = {
    val description = {
      job.lastError match {
        case Some(error) =>
          I18n.t(UiKeys.adminSystemJobFailed, error)
        case None        =>
          job.lastRunAt match {
            case Some(at) =>
              I18n.t(
                UiKeys.adminSystemJobRan,
                Formats.dateTime(at),
                job.lastOutcome.getOrElse(I18n.t(UiKeys.adminSystemJobOutcome)),
              )
            // A job registers itself before its first pass, so "not yet" is normal right after a restart and a
            // symptom an interval later.
            case None     =>
              I18n.t(UiKeys.adminSystemJobNotRun, job.intervalMinutes)
          }
      }
    }
    row(job.name, description, warn = job.lastError.isDefined)
  }

  private def renderStats(stats: DbStats): HtmlElement = {
    card(
      I18n.t(UiKeys.adminSystemStatsCard),
      row(I18n.t(UiKeys.adminSystemStatsUsers), stats.users.toString),
      row(I18n.t(UiKeys.adminSystemStatsAdmins), stats.admins.toString),
      row(
        I18n.t(UiKeys.adminSystemStatsUnconfirmed),
        stats.unverifiedUsers.toString,
        warn = stats.unverifiedUsers > 0,
      ),
      row(I18n.t(UiKeys.adminSystemStatsNoPassword), stats.usersWithoutPassword.toString),
      row(I18n.t(UiKeys.adminSystemStatsIdentities), stats.oauthIdentities.toString),
      row(
        I18n.t(UiKeys.adminSystemStatsSessions),
        I18n.t(UiKeys.adminSystemStatsSessionsValue, stats.activeSessions, stats.sessions),
      ),
      row(I18n.t(UiKeys.adminSystemStatsTokens), stats.verificationTokens.toString),
      // Both of these mean the reaper has not run, or has been failing: they are the numbers that turn "the job says
      // it ran" into "the job actually did something".
      row(
        I18n.t(UiKeys.adminSystemStatsTokensExpired),
        stats.expiredVerificationTokens.toString,
        warn = stats.expiredVerificationTokens > 0,
      ),
      row(I18n.t(UiKeys.adminSystemStatsLoginAttempts), stats.loginAttempts.toString),
      row(
        I18n.t(UiKeys.adminSystemStatsFailedLogins),
        stats.failedLoginsLast24h.toString,
        warn = stats.failedLoginsLast24h > 0,
      ),
      row(
        I18n.t(UiKeys.adminSystemStatsLockedOut),
        stats.blockedRateLimitKeys.toString,
        warn = stats.blockedRateLimitKeys > 0,
      ),
      row(I18n.t(UiKeys.adminSystemStatsAuditEntries), stats.auditEntries.toString),
    )
  }

  private def renderMaintenance(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mt-4",
      div(
        cls := "card-body",
        h2(cls := "card-title text-base", I18n.t(UiKeys.adminSystemMaintenanceCard)),
        p(cls  := "text-sm opacity-60", I18n.t(UiKeys.adminSystemMaintenanceHint)),
        div(
          cls  := "card-actions gap-2",
          button(
            cls := "btn btn-sm btn-outline",
            typ := "button",
            disabled <-- inFlightSignal,
            I18n.t(UiKeys.adminSystemMaintenancePrune),
            onClick.mapToUnit --> Observer[Unit](_ => pruneBus.emit(())),
          ),
          button(
            cls := "btn btn-sm btn-error btn-outline",
            typ := "button",
            disabled <-- inFlightSignal,
            I18n.t(UiKeys.adminSystemMaintenanceClear),
            onClick.mapToUnit -->
              Observer[Unit] { _ =>
                if (dom.window.confirm(I18n.t(UiKeys.adminSystemMaintenanceClearConfirm)))
                  clearLocksBus.emit(())
              },
          ),
        ),
      ),
    )
  }
}
