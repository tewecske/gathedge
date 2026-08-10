package webapp1.shared.i18n

import scala.collection.mutable

/** Every catalog key the *browser* renders as page copy, as a constant.
  *
  * The sibling of [[MessageKeys]], and split from it along the line of who mints the message: a `MessageKeys` entry is
  * chosen by the server (an `ApiFailure`, a `Validation` result, an email template) and worded by whoever resolves it,
  * while a `UiKeys` entry is a literal that used to sit inline in a Laminar element. Hence the `ui.` prefix on all of
  * them — the namespace says which half a key belongs to.
  *
  * It lives in `shared` rather than in the frontend for one reason: `MessagesSpec` runs on the JVM, so this is what
  * lets the page copy carry the same guarantee the server's messages do — a mistyped key is a compile error and a
  * missing translation is a failing test, instead of the key itself appearing on screen.
  *
  * Keys are registered as they are declared rather than re-listed in [[all]] by hand, for the same reason as in
  * [[MessageKeys]]: a hand-maintained list is exactly the thing that goes stale and takes the guarantee with it.
  *
  * Two kinds of string are deliberately *not* here. Brand and language names — `OAuthProvider.displayName`,
  * `Locale.displayName` — are the same in every language by design (see the scaladoc on the latter). And a value that
  * travels on the wire is never translated: the `value` of a `<select>` `option` stays the enum's `toString` or the
  * stored audit code, and only its label comes through a key below.
  *
  * Where a field's label already exists as a `MessageKeys` constant — `field.email`, `field.password`, `field.source`,
  * `field.target` — the page reuses that one rather than minting a second. Those keys exist precisely so a form and the
  * endpoint behind it cannot disagree about what an input is called.
  */
object UiKeys {

  private val registered = mutable.LinkedHashSet.empty[String]

  private def key(value: String): String = {
    registered += value
    value
  }

  /** A count-sensitive message, read through `MessageCatalog.plural`. The catalog holds `<base>.one` and
    * `<base>.other`, never `<base>` itself, so those are what gets registered while the constant carries the base the
    * call site passes.
    */
  private def pluralKey(base: String): String = {
    registered += base + ".one"
    registered += base + ".other"
    base
  }

  /** Every registered key. Safe to read once this object has initialised, which — being a `lazy val` on an object whose
    * own initialiser never touches it — is any time a caller can reach it.
    */
  lazy val all: Set[String] = registered.toSet

  // -- Shared vocabulary -----------------------------------------------------------------------
  // Words appearing on several screens. Shared only where the *meaning* is shared: `commonAdd` is
  // the same verb on the to-do and the group-pair form, whereas a heading and a button that merely
  // read alike keep separate keys, since a translator may well want to split them.

  val commonSignIn: String        = key("ui.common.signIn")
  val commonSignUp: String        = key("ui.common.signUp")
  val commonAdd: String           = key("ui.common.add")
  val commonCreate: String        = key("ui.common.create")
  val commonSave: String          = key("ui.common.save")
  val commonRemove: String        = key("ui.common.remove")
  val commonApply: String         = key("ui.common.apply")
  val commonAdministrator: String = key("ui.common.administrator")
  val commonYes: String           = key("ui.common.yes")
  val commonNo: String            = key("ui.common.no")
  val commonOr: String            = key("ui.common.or")
  val commonWhen: String          = key("ui.common.when")
  val commonFrom: String          = key("ui.common.from")

  /** The em dash standing in for a value a row does not have. A key rather than a literal so a language that reads a
    * bare dash as something else has somewhere to say so.
    */
  val commonNone: String = key("ui.common.none")

  /** `{0}` is `Validation.minPasswordLength`. */
  val commonPasswordHint: String = key("ui.common.passwordHint")

  // -- Navigation ------------------------------------------------------------------------------

  val navTodo: String          = key("ui.nav.todo")
  val navGroups: String        = key("ui.nav.groups")
  val navAdmin: String         = key("ui.nav.admin")
  val navAccountMenu: String   = key("ui.nav.accountMenu")
  val navLogOut: String        = key("ui.nav.logOut")
  val navThemeDark: String     = key("ui.nav.themeDark")
  val navThemeLight: String    = key("ui.nav.themeLight")
  val navAdminUsers: String    = key("ui.nav.adminUsers")
  val navAdminSystem: String   = key("ui.nav.adminSystem")
  val navGroupOverview: String = key("ui.nav.groupOverview")
  val navGroupMembers: String  = key("ui.nav.groupMembers")

  // -- Sign in / sign up -----------------------------------------------------------------------

  val signInVerified: String    = key("ui.signin.verified")
  val signInNoAccount: String   = key("ui.signin.noAccount")
  val signUpTitle: String       = key("ui.signup.title")
  val signUpHaveAccount: String = key("ui.signup.haveAccount")

  // -- Email confirmation ----------------------------------------------------------------------

  val verificationResendButton: String = key("ui.verification.resendButton")

  /** Deliberately non-committal, and shown by three pages: the endpoint answers the same for an unknown address, an
    * already-confirmed one and a fresh send, so this copy must not say more than that.
    */
  val verificationResent: String = key("ui.verification.resent")

  val checkInboxTitle: String     = key("ui.checkinbox.title")
  val checkInboxBody: String      = key("ui.checkinbox.body")
  val checkInboxPrompt: String    = key("ui.checkinbox.prompt")
  val checkInboxSubmit: String    = key("ui.checkinbox.submit")
  val checkInboxConfirmed: String = key("ui.checkinbox.confirmed")

  val verifyTitle: String        = key("ui.verify.title")
  val verifyResend: String       = key("ui.verify.resend")
  val verifyBackToSignIn: String = key("ui.verify.backToSignIn")

  // -- Social sign-in --------------------------------------------------------------------------
  // `{0}` is the provider's display name throughout, and every frame is phrased so the name lands
  // at the end: Hungarian would otherwise need the a/az article alternation in front of it, which
  // no placeholder can carry.

  val oauthContinueWith: String   = key("ui.oauth.continueWith")
  val oauthLink: String           = key("ui.oauth.link")
  val oauthLinked: String         = key("ui.oauth.linked")
  val oauthLinkedFallback: String = key("ui.oauth.linkedFallback")

  // The `?error=` codes `AuthRoutes.oauthErrorRedirect` puts in the address bar.
  val oauthErrorAccountExists: String       = key("ui.oauth.error.accountExists")
  val oauthErrorAlreadyLinked: String       = key("ui.oauth.error.alreadyLinked")
  val oauthErrorLinkRequiresSession: String = key("ui.oauth.error.linkRequiresSession")
  val oauthErrorStateMismatch: String       = key("ui.oauth.error.stateMismatch")
  val oauthErrorFailed: String              = key("ui.oauth.error.failed")

  // -- Invitations -----------------------------------------------------------------------------

  val inviteTitle: String = key("ui.invite.title")

  /** Split around the `<strong>` holding the group's name, which is why it is two keys and not one message with a
    * placeholder. `{0}` on the second half is the role.
    */
  val inviteIntroBefore: String = key("ui.invite.introBefore")
  val inviteIntroAfter: String  = key("ui.invite.introAfter")

  val inviteSentTo: String       = key("ui.invite.sentTo")
  val inviteAccepted: String     = key("ui.invite.accepted")
  val inviteExpired: String      = key("ui.invite.expired")
  val inviteAccept: String       = key("ui.invite.accept")
  val inviteWrongAccount: String = key("ui.invite.wrongAccount")

  // -- Status pages ----------------------------------------------------------------------------

  val forbiddenTitle: String = key("ui.status.forbidden.title")
  val forbiddenBody: String  = key("ui.status.forbidden.body")
  val notFoundTitle: String  = key("ui.status.notFound.title")
  val notFoundBody: String   = key("ui.status.notFound.body")
  val statusBackHome: String = key("ui.status.backHome")

  // -- To-do -----------------------------------------------------------------------------------

  val todoTitle: String       = key("ui.todo.title")
  val todoPlaceholder: String = key("ui.todo.placeholder")

  val todoStatusToDo: String       = key("ui.todostatus.toDo")
  val todoStatusInProgress: String = key("ui.todostatus.inProgress")
  val todoStatusDone: String       = key("ui.todostatus.done")

  // -- Groups ----------------------------------------------------------------------------------

  val groupsTitle: String       = key("ui.groups.title")
  val groupsPlaceholder: String = key("ui.groups.placeholder")
  val groupsCreate: String      = key("ui.groups.create")

  val groupBackToGroups: String  = key("ui.group.backToGroups")
  val groupFallbackName: String  = key("ui.group.fallbackName")
  val groupPairAddedBy: String   = key("ui.group.pairAddedBy")
  val groupDelete: String        = key("ui.group.delete")
  val groupDeleteConfirm: String = key("ui.group.deleteConfirm")

  val membersBackToGroup: String = key("ui.members.backToGroup")
  val membersTitle: String       = key("ui.members.title")

  /** `{0}` is the group's name; the page falls back to [[membersTitle]] until the group has loaded. */
  val membersTitleFor: String = key("ui.members.titleFor")

  val membersForbidden: String         = key("ui.members.forbidden")
  val membersColRole: String           = key("ui.members.colRole")
  val membersInvitePlaceholder: String = key("ui.members.invitePlaceholder")
  val membersInvite: String            = key("ui.members.invite")
  val membersInvited: String           = key("ui.members.invited")

  val roleAdmin: String     = key("ui.role.admin")
  val roleReadWrite: String = key("ui.role.readWrite")
  val roleReadOnly: String  = key("ui.role.readOnly")

  // -- Account settings ------------------------------------------------------------------------

  val settingsTitle: String            = key("ui.settings.title")
  val settingsEmailCard: String        = key("ui.settings.emailCard")
  val settingsVerified: String         = key("ui.settings.verified")
  val settingsNotVerified: String      = key("ui.settings.notVerified")
  val settingsResendHint: String       = key("ui.settings.resendHint")
  val settingsLinkedCard: String       = key("ui.settings.linkedCard")
  val settingsLinkedHint: String       = key("ui.settings.linkedHint")
  val settingsNothingLinked: String    = key("ui.settings.nothingLinked")
  val settingsUnlink: String           = key("ui.settings.unlink")
  val settingsSetPasswordFirst: String = key("ui.settings.setPasswordFirst")
  val settingsCurrentPassword: String  = key("ui.settings.currentPassword")
  val settingsNewPassword: String      = key("ui.settings.newPassword")
  val settingsChangePassword: String   = key("ui.settings.changePassword")
  val settingsSetPassword: String      = key("ui.settings.setPassword")
  val settingsUnlinked: String         = key("ui.settings.unlinked")
  val settingsPasswordSaved: String    = key("ui.settings.passwordSaved")
  val settingsVerificationSent: String = key("ui.settings.verificationSent")

  // -- Administration: user list ---------------------------------------------------------------

  val adminUsersTitle: String      = key("ui.admin.users.title")
  val adminUsersCreateCard: String = key("ui.admin.users.createCard")

  /** `{0}` is `Validation.minPasswordLength`. */
  val adminUsersPasswordPlaceholder: String = key("ui.admin.users.passwordPlaceholder")

  val adminUsersColAdmin: String         = key("ui.admin.users.colAdmin")
  val adminUsersColConfirmed: String     = key("ui.admin.users.colConfirmed")
  val adminUsersColSignIn: String        = key("ui.admin.users.colSignIn")
  val adminUsersColCreated: String       = key("ui.admin.users.colCreated")
  val adminUsersBadgeAdmin: String       = key("ui.admin.users.badgeAdmin")
  val adminUsersBadgeUser: String        = key("ui.admin.users.badgeUser")
  val adminUsersBadgeConfirmed: String   = key("ui.admin.users.badgeConfirmed")
  val adminUsersBadgeUnconfirmed: String = key("ui.admin.users.badgeUnconfirmed")
  val adminUsersBadgeLocked: String      = key("ui.admin.users.badgeLocked")
  val adminUsersBadgeOk: String          = key("ui.admin.users.badgeOk")

  // -- Administration: one account -------------------------------------------------------------

  val adminUserBack: String                = key("ui.admin.user.back")
  val adminUserGone: String                = key("ui.admin.user.gone")
  val adminUserPasswordPlaceholder: String = key("ui.admin.user.passwordPlaceholder")
  val adminUserDelete: String              = key("ui.admin.user.delete")
  val adminUserDeleteConfirm: String       = key("ui.admin.user.deleteConfirm")
  val adminUserSaved: String               = key("ui.admin.user.saved")

  // -- Administration: account diagnostics -----------------------------------------------------

  val adminDiagEmailConfirmed: String = key("ui.admin.diag.emailConfirmed")
  val adminDiagLinkSent: String       = key("ui.admin.diag.linkSent")
  val adminDiagSignedOut: String      = key("ui.admin.diag.signedOut")
  val adminDiagLockoutCleared: String = key("ui.admin.diag.lockoutCleared")
  val adminDiagDetached: String       = key("ui.admin.diag.detached")

  val adminDiagVerificationCard: String = key("ui.admin.diag.verificationCard")
  val adminDiagConfirmedOn: String      = key("ui.admin.diag.confirmedOn")
  val adminDiagNeverConfirmed: String   = key("ui.admin.diag.neverConfirmed")
  val adminDiagNoToken: String          = key("ui.admin.diag.noToken")
  val adminDiagTokenUsed: String        = key("ui.admin.diag.tokenUsed")
  val adminDiagTokenExpired: String     = key("ui.admin.diag.tokenExpired")
  val adminDiagTokenValid: String       = key("ui.admin.diag.tokenValid")
  val adminDiagLastToken: String        = key("ui.admin.diag.lastToken")
  val adminDiagMarkConfirmed: String    = key("ui.admin.diag.markConfirmed")
  val adminDiagSendLink: String         = key("ui.admin.diag.sendLink")

  val adminDiagSecurityCard: String = key("ui.admin.diag.securityCard")
  val adminDiagLockedOut: String    = key("ui.admin.diag.lockedOut")
  val adminDiagNotLockedOut: String = key("ui.admin.diag.notLockedOut")
  val adminDiagClearLockout: String = key("ui.admin.diag.clearLockout")
  val adminDiagNoAttempts: String   = key("ui.admin.diag.noAttempts")
  val adminDiagColOutcome: String   = key("ui.admin.diag.colOutcome")

  val adminDiagSessionsCard: String = key("ui.admin.diag.sessionsCard")

  /** `.one`/`.other`; `{0}` is the number of active sessions and `{1}` the number recorded in total. */
  val adminDiagSessionsCount: String = pluralKey("ui.admin.diag.sessionsCount")

  val adminDiagSignOutEverywhere: String = key("ui.admin.diag.signOutEverywhere")
  val adminDiagSignOutConfirm: String    = key("ui.admin.diag.signOutConfirm")
  val adminDiagNoSessions: String        = key("ui.admin.diag.noSessions")
  val adminDiagColSignedIn: String       = key("ui.admin.diag.colSignedIn")
  val adminDiagColExpires: String        = key("ui.admin.diag.colExpires")

  val adminDiagIdentitiesCard: String     = key("ui.admin.diag.identitiesCard")
  val adminDiagNoneLinked: String         = key("ui.admin.diag.noneLinked")
  val adminDiagColProvider: String        = key("ui.admin.diag.colProvider")
  val adminDiagColReportedAddress: String = key("ui.admin.diag.colReportedAddress")
  val adminDiagColLinked: String          = key("ui.admin.diag.colLinked")
  val adminDiagHasPassword: String        = key("ui.admin.diag.hasPassword")
  val adminDiagNoPassword: String         = key("ui.admin.diag.noPassword")
  val adminDiagDetach: String             = key("ui.admin.diag.detach")
  val adminDiagDetachConfirm: String      = key("ui.admin.diag.detachConfirm")

  /** Every value `dto.LoginOutcome` can hold, resolved by suffixing the stored code onto this prefix. A row written by
    * a newer build falls back to showing the code itself rather than an empty cell.
    */
  val loginOutcomePrefix: String = "ui.loginoutcome."

  val loginOutcomeSuccess: String          = key(loginOutcomePrefix + "success")
  val loginOutcomeUnknownEmail: String     = key(loginOutcomePrefix + "unknown_email")
  val loginOutcomeBadPassword: String      = key(loginOutcomePrefix + "bad_password")
  val loginOutcomeNoPassword: String       = key(loginOutcomePrefix + "no_password")
  val loginOutcomeEmailNotVerified: String = key(loginOutcomePrefix + "email_not_verified")
  val loginOutcomeRateLimited: String      = key(loginOutcomePrefix + "rate_limited")

  // -- Administration: audit log ---------------------------------------------------------------

  val adminAuditTitle: String       = key("ui.admin.audit.title")
  val adminAuditColAction: String   = key("ui.admin.audit.colAction")
  val adminAuditEveryAction: String = key("ui.admin.audit.everyAction")
  val adminAuditActorId: String     = key("ui.admin.audit.actorId")
  val adminAuditActorAny: String    = key("ui.admin.audit.actorAny")
  val adminAuditColActor: String    = key("ui.admin.audit.colActor")
  val adminAuditColTarget: String   = key("ui.admin.audit.colTarget")
  val adminAuditColDetail: String   = key("ui.admin.audit.colDetail")
  val adminAuditSystemActor: String = key("ui.admin.audit.systemActor")
  val adminAuditTargetUser: String  = key("ui.admin.audit.targetUser")
  val adminAuditLoadOlder: String   = key("ui.admin.audit.loadOlder")
  val adminAuditEmpty: String       = key("ui.admin.audit.empty")

  /** `.one`/`.other`, `{0}` being the number of rows currently held. */
  val adminAuditCountAll: String   = pluralKey("ui.admin.audit.countAll")
  val adminAuditCountShown: String = pluralKey("ui.admin.audit.countShown")

  /** Every value `dto.AuditAction` can hold, resolved by suffix like [[loginOutcomePrefix]]. The filter `<select>`
    * still submits the stored code — only the label is translated.
    */
  val auditActionPrefix: String = "ui.auditaction."

  val auditActionUserCreate: String              = key(auditActionPrefix + "user.create")
  val auditActionUserUpdate: String              = key(auditActionPrefix + "user.update")
  val auditActionUserDelete: String              = key(auditActionPrefix + "user.delete")
  val auditActionUserPasswordReset: String       = key(auditActionPrefix + "user.password_reset")
  val auditActionUserVerifyEmail: String         = key(auditActionPrefix + "user.verify_email")
  val auditActionUserVerificationResend: String  = key(auditActionPrefix + "user.verification_resend")
  val auditActionUserSessionsRevoked: String     = key(auditActionPrefix + "user.sessions_revoked")
  val auditActionUserOAuthUnlink: String         = key(auditActionPrefix + "user.oauth_unlink")
  val auditActionUserLockoutCleared: String      = key(auditActionPrefix + "user.lockout_cleared")
  val auditActionSystemPrune: String             = key(auditActionPrefix + "system.prune")
  val auditActionSystemRateLimitsCleared: String = key(auditActionPrefix + "system.rate_limits_cleared")

  // -- Administration: system overview ----------------------------------------------------------

  val adminSystemTitle: String = key("ui.admin.system.title")

  /** The four counts are pluralised separately and spliced in as `{0}`–`{3}`, which is also what keeps the Hungarian
    * frame clear of the a/az alternation.
    */
  val adminSystemPruneDone: String     = key("ui.admin.system.prune.done")
  val adminSystemPruneSessions: String = pluralKey("ui.admin.system.prune.sessions")
  val adminSystemPruneTokens: String   = pluralKey("ui.admin.system.prune.tokens")
  val adminSystemPruneAttempts: String = pluralKey("ui.admin.system.prune.attempts")
  val adminSystemPruneKeys: String     = pluralKey("ui.admin.system.prune.keys")

  val adminSystemLocksCleared: String = key("ui.admin.system.locksCleared")
  val adminSystemUnsafeConfig: String = key("ui.admin.system.unsafeConfig")

  val adminSystemConfigCard: String          = key("ui.admin.system.config.card")
  val adminSystemConfigEnv: String           = key("ui.admin.system.config.env")
  val adminSystemConfigBaseUrl: String       = key("ui.admin.system.config.baseUrl")
  val adminSystemConfigListening: String     = key("ui.admin.system.config.listening")
  val adminSystemConfigRequireVerify: String = key("ui.admin.system.config.requireVerify")
  val adminSystemConfigSecureCookie: String  = key("ui.admin.system.config.secureCookie")
  val adminSystemConfigSocial: String        = key("ui.admin.system.config.social")
  val adminSystemConfigSocialNone: String    = key("ui.admin.system.config.socialNone")
  val adminSystemConfigMail: String          = key("ui.admin.system.config.mail")
  val adminSystemConfigMailLogged: String    = key("ui.admin.system.config.mailLogged")
  val adminSystemConfigMailFrom: String      = key("ui.admin.system.config.mailFrom")
  val adminSystemConfigStartTls: String      = key("ui.admin.system.config.startTls")
  val adminSystemConfigDatabase: String      = key("ui.admin.system.config.database")
  val adminSystemConfigDatabaseUser: String  = key("ui.admin.system.config.databaseUser")
  val adminSystemConfigSessionLife: String   = key("ui.admin.system.config.sessionLife")
  val adminSystemConfigInviteLife: String    = key("ui.admin.system.config.inviteLife")
  val adminSystemConfigVerifyLife: String    = key("ui.admin.system.config.verifyLife")

  /** `.one`/`.other`; the three lifetimes above all render their value through it. */
  val adminSystemConfigHours: String = pluralKey("ui.admin.system.config.hours")

  val adminSystemConfigRateLimit: String      = key("ui.admin.system.config.rateLimit")
  val adminSystemConfigRateLimitValue: String = key("ui.admin.system.config.rateLimitValue")
  val adminSystemConfigNettyThreads: String   = key("ui.admin.system.config.nettyThreads")
  val adminSystemConfigNettyAuto: String      = key("ui.admin.system.config.nettyAuto")

  val adminSystemRuntimeCard: String          = key("ui.admin.system.runtime.card")
  val adminSystemRuntimeApiVersion: String    = key("ui.admin.system.runtime.apiVersion")
  val adminSystemRuntimeStarted: String       = key("ui.admin.system.runtime.started")
  val adminSystemRuntimeUptime: String        = key("ui.admin.system.runtime.uptime")
  val adminSystemRuntimeJvm: String           = key("ui.admin.system.runtime.jvm")
  val adminSystemRuntimeProcessors: String    = key("ui.admin.system.runtime.processors")
  val adminSystemRuntimeHeap: String          = key("ui.admin.system.runtime.heap")
  val adminSystemRuntimeHeapValue: String     = key("ui.admin.system.runtime.heapValue")
  val adminSystemRuntimeThreads: String       = key("ui.admin.system.runtime.threads")
  val adminSystemRuntimeSchema: String        = key("ui.admin.system.runtime.schema")
  val adminSystemRuntimeSchemaUnknown: String = key("ui.admin.system.runtime.schemaUnknown")
  val adminSystemRuntimeMigrations: String    = key("ui.admin.system.runtime.migrations")

  val adminSystemJobsHeading: String = key("ui.admin.system.jobs.heading")
  val adminSystemJobsNone: String    = key("ui.admin.system.jobs.none")
  val adminSystemJobFailed: String   = key("ui.admin.system.jobs.failed")
  val adminSystemJobRan: String      = key("ui.admin.system.jobs.ran")
  val adminSystemJobOutcome: String  = key("ui.admin.system.jobs.outcome")
  val adminSystemJobNotRun: String   = key("ui.admin.system.jobs.notRun")

  val adminSystemStatsCard: String          = key("ui.admin.system.stats.card")
  val adminSystemStatsUsers: String         = key("ui.admin.system.stats.users")
  val adminSystemStatsAdmins: String        = key("ui.admin.system.stats.admins")
  val adminSystemStatsUnconfirmed: String   = key("ui.admin.system.stats.unconfirmed")
  val adminSystemStatsNoPassword: String    = key("ui.admin.system.stats.noPassword")
  val adminSystemStatsIdentities: String    = key("ui.admin.system.stats.identities")
  val adminSystemStatsSessions: String      = key("ui.admin.system.stats.sessions")
  val adminSystemStatsSessionsValue: String = key("ui.admin.system.stats.sessionsValue")
  val adminSystemStatsTokens: String        = key("ui.admin.system.stats.tokens")
  val adminSystemStatsTokensExpired: String = key("ui.admin.system.stats.tokensExpired")
  val adminSystemStatsGroups: String        = key("ui.admin.system.stats.groups")

  /** `.one`/`.other`; `{0}` is the member count and `{1}` the number of groups holding them. */
  val adminSystemStatsGroupMembers: String = pluralKey("ui.admin.system.stats.groupMembers")

  val adminSystemStatsInvitations: String      = key("ui.admin.system.stats.invitations")
  val adminSystemStatsInvitationsValue: String = key("ui.admin.system.stats.invitationsValue")
  val adminSystemStatsTodoItems: String        = key("ui.admin.system.stats.todoItems")
  val adminSystemStatsGroupPairs: String       = key("ui.admin.system.stats.groupPairs")
  val adminSystemStatsLoginAttempts: String    = key("ui.admin.system.stats.loginAttempts")
  val adminSystemStatsFailedLogins: String     = key("ui.admin.system.stats.failedLogins")
  val adminSystemStatsLockedOut: String        = key("ui.admin.system.stats.lockedOut")
  val adminSystemStatsAuditEntries: String     = key("ui.admin.system.stats.auditEntries")

  val adminSystemMaintenanceCard: String         = key("ui.admin.system.maintenance.card")
  val adminSystemMaintenanceHint: String         = key("ui.admin.system.maintenance.hint")
  val adminSystemMaintenancePrune: String        = key("ui.admin.system.maintenance.prune")
  val adminSystemMaintenanceClear: String        = key("ui.admin.system.maintenance.clear")
  val adminSystemMaintenanceClearConfirm: String = key("ui.admin.system.maintenance.clearConfirm")

  // -- Formatting ------------------------------------------------------------------------------
  // All `.one`/`.other`. The old `"$n day(s)"` idiom has no Hungarian equivalent — a numeral there
  // is followed by the singular, always — which is what `MessageCatalog.plural` exists to express.

  val durationDays: String    = pluralKey("ui.duration.days")
  val durationHours: String   = pluralKey("ui.duration.hours")
  val durationMinutes: String = pluralKey("ui.duration.minutes")
  val durationSeconds: String = pluralKey("ui.duration.seconds")

  /** Joins the two coarsest units, e.g. "3 days, 4 hours". A key rather than a hard-coded comma so the separator is the
    * translator's to choose.
    */
  val durationPair: String = key("ui.duration.pair")

  /** Rounded up; what "unblocks itself in …" is rendered with. */
  val formatMinutes: String = pluralKey("ui.format.minutes")
}
