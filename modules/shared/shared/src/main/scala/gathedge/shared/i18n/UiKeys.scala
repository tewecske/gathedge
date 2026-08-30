package gathedge.shared.i18n

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
  * Where a field's label already exists as a `MessageKeys` constant — `field.email`, `field.password` — the page reuses
  * that one rather than minting a second. Those keys exist precisely so a form and the endpoint behind it cannot
  * disagree about what an input is called.
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
  // Words appearing on several screens. Shared only where the *meaning* is shared: a heading and a
  // button that merely read alike keep separate keys, since a translator may well want to split
  // them.

  val commonSignIn: String        = key("ui.common.signIn")
  val commonSignUp: String        = key("ui.common.signUp")
  val commonAdd: String           = key("ui.common.add")
  val commonCreate: String        = key("ui.common.create")
  val commonSave: String          = key("ui.common.save")
  val commonCancel: String        = key("ui.common.cancel")
  val commonRemove: String        = key("ui.common.remove")
  val commonApply: String         = key("ui.common.apply")
  val commonAdministrator: String = key("ui.common.administrator")
  val commonYes: String           = key("ui.common.yes")
  val commonNo: String            = key("ui.common.no")
  val commonOk: String            = key("ui.common.ok")
  val commonOr: String            = key("ui.common.or")
  val commonWhen: String          = key("ui.common.when")
  val commonFrom: String          = key("ui.common.from")

  /** The em dash standing in for a value a row does not have. A key rather than a literal so a language that reads a
    * bare dash as something else has somewhere to say so.
    */
  val commonNone: String = key("ui.common.none")

  // The paging control under a long table. The numbered buttons carry the number itself, so only the two arrows, the
  // page-size control and the "which of how many" indicator need words — and the arrows' words are read out rather
  // than shown, being `aria-label`s on a glyph. What the *rows* are called is each listing's own key, since "137
  // accounts" and "137 entries" are different sentences.

  val commonRowsPerPage: String  = key("ui.common.rowsPerPage")
  val commonPreviousPage: String = key("ui.common.previousPage")
  val commonNextPage: String     = key("ui.common.nextPage")

  /** `{0}` is the page being read, one-based; `{1}` how many there are. */
  val commonPageOf: String = key("ui.common.pageOf")

  /** `{0}` is `Validation.minPasswordLength`. */
  val commonPasswordHint: String = key("ui.common.passwordHint")

  // -- Share row -------------------------------------------------------------------------------
  // `components.ShareRow`: copy-link, Web Share and QR code for one URL. Shared by `GameInstancePage` (its own page
  // URL) and `GroupDetailPage` (an invite link built from the group's code) — see that component's doc comment.

  val shareCopyLink: String   = key("ui.share.copyLink")
  val shareCopied: String     = key("ui.share.copied")
  val shareButton: String     = key("ui.share.button")
  val shareQrGenerate: String = key("ui.share.qrGenerate")
  val shareQrTitle: String    = key("ui.share.qrTitle")
  val shareQrAlt: String      = key("ui.share.qrAlt")
  val shareQrClose: String    = key("ui.share.qrClose")
  val shareQrError: String    = key("ui.share.qrError")

  // -- Navigation ------------------------------------------------------------------------------

  val navMenu: String = key("ui.nav.menu")

  /** The language picker's accessible name. It was the one page string reaching `I18n.t` as a bare literal rather than
    * a constant, under a key outside the `ui.` namespace — which is exactly the gap this object exists to close, since
    * neither of `MessagesSpec`'s checks could see it. `e2e/tests/translation.spec.ts` is what caught it.
    */
  val navLanguage: String = key("ui.nav.language")

  val navWords: String           = key("ui.nav.words")
  val navAbout: String           = key("ui.nav.about")
  val navGames: String           = key("ui.nav.games")
  val navGroups: String          = key("ui.nav.groups")
  val navAdmin: String           = key("ui.nav.admin")
  val navAccountMenu: String     = key("ui.nav.accountMenu")
  val navLogOut: String          = key("ui.nav.logOut")
  val navThemeDark: String       = key("ui.nav.themeDark")
  val navThemeLight: String      = key("ui.nav.themeLight")
  val navAdminUsers: String      = key("ui.nav.adminUsers")
  val navAdminSystem: String     = key("ui.nav.adminSystem")
  val navAdminUsage: String      = key("ui.nav.adminUsage")
  val navAdminWordForms: String  = key("ui.nav.adminWordForms")
  val navAdminRateLimits: String = key("ui.nav.adminRateLimits")

  // -- Sign in / sign up -----------------------------------------------------------------------

  val signInVerified: String       = key("ui.signin.verified")
  val signInPasswordReset: String  = key("ui.signin.passwordReset")
  val signInNoAccount: String      = key("ui.signin.noAccount")
  val signInForgotPassword: String = key("ui.signin.forgotPassword")
  val signUpTitle: String          = key("ui.signup.title")
  val signUpHaveAccount: String    = key("ui.signup.haveAccount")

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

  // -- Forgot / reset password -------------------------------------------------------------------

  val forgotPasswordTitle: String  = key("ui.forgotPassword.title")
  val forgotPasswordBody: String   = key("ui.forgotPassword.body")
  val forgotPasswordSubmit: String = key("ui.forgotPassword.submit")

  /** Deliberately non-committal, like [[verificationResent]]: the endpoint answers the same for an unknown address and
    * a known one, so this copy must not say more than that.
    */
  val forgotPasswordSent: String = key("ui.forgotPassword.sent")

  val resetPasswordTitle: String  = key("ui.resetPassword.title")
  val resetPasswordSubmit: String = key("ui.resetPassword.submit")

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

  // -- Status pages ----------------------------------------------------------------------------

  val forbiddenTitle: String = key("ui.status.forbidden.title")
  val forbiddenBody: String  = key("ui.status.forbidden.body")
  val notFoundTitle: String  = key("ui.status.notFound.title")
  val notFoundBody: String   = key("ui.status.notFound.body")

  // -- About ------------------------------------------------------------------------------------
  // What the site is for, who runs it, and how it is licensed. The repository, licence and
  // author facts live in `Branding`, not in copy, so only the surrounding words are translated.

  val aboutTitle: String              = key("ui.about.title")
  val aboutIntro: String              = key("ui.about.intro")
  val aboutNameTitle: String          = key("ui.about.nameTitle")
  val aboutNameBody: String           = key("ui.about.nameBody")
  val aboutGoalTitle: String          = key("ui.about.goalTitle")
  val aboutGoalBody: String           = key("ui.about.goalBody")
  val aboutSourceTitle: String        = key("ui.about.sourceTitle")
  val aboutSourceBody: String         = key("ui.about.sourceBody")
  val aboutSourceLicenseLabel: String = key("ui.about.sourceLicenseLabel")
  val aboutCodeTitle: String          = key("ui.about.codeTitle")
  val aboutCodeBody: String           = key("ui.about.codeBody")
  val aboutCodeLicenseLabel: String   = key("ui.about.codeLicenseLabel")
  val aboutLinksTitle: String         = key("ui.about.linksTitle")
  val aboutGitHubLabel: String        = key("ui.about.githubLabel")
  val aboutAuthorTitle: String        = key("ui.about.authorTitle")
  val aboutAuthorBody: String         = key("ui.about.authorBody")
  val aboutContactLabel: String       = key("ui.about.contactLabel")

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

  val adminUsersFilterLabel: String       = key("ui.admin.users.filterLabel")
  val adminUsersFilterPlaceholder: String = key("ui.admin.users.filterPlaceholder")

  /** What the paging control says the list holds. `adminUsersEmpty` covers the count of zero, which under a search is
    * an answer rather than a number.
    */
  val adminUsersEmpty: String = key("ui.admin.users.empty")
  val adminUsersCount: String = pluralKey("ui.admin.users.count")

  // -- Administration: one account -------------------------------------------------------------

  val adminUserBack: String                = key("ui.admin.user.back")
  val adminUserGone: String                = key("ui.admin.user.gone")
  val adminUserPasswordPlaceholder: String = key("ui.admin.user.passwordPlaceholder")
  val adminUserDelete: String              = key("ui.admin.user.delete")
  val adminUserDeleteConfirm: String       = key("ui.admin.user.deleteConfirm")
  val adminUserSaved: String               = key("ui.admin.user.saved")

  // -- Administration: one account's play history --------------------------------------------

  val adminUserPlaysTitle: String     = key("ui.admin.user.plays.title")
  val adminUserPlaysColHeader: String = key("ui.admin.user.plays.colHeader")
  val adminUserPlaysBack: String      = key("ui.admin.user.plays.back")

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
  val adminAuditEmpty: String       = key("ui.admin.audit.empty")

  /** `.one`/`.other`, `{0}` being how many entries match — the server's count, not the size of the page on screen.
    *
    * One key rather than the pair this used to have. While the trail was read through a cursor the page could only say
    * how many rows it had *fetched*, and whether that was all of them was a second sentence; a counted listing knows.
    */
  val adminAuditCount: String = pluralKey("ui.admin.audit.count")

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
  val adminSystemPruneGuests: String   = pluralKey("ui.admin.system.prune.guests")
  val adminSystemPruneKeys: String     = pluralKey("ui.admin.system.prune.keys")

  val adminSystemLocksCleared: String = key("ui.admin.system.locksCleared")
  val adminSystemUnsafeConfig: String = key("ui.admin.system.unsafeConfig")

  val adminSystemConfigCard: String           = key("ui.admin.system.config.card")
  val adminSystemConfigEnv: String            = key("ui.admin.system.config.env")
  val adminSystemConfigBaseUrl: String        = key("ui.admin.system.config.baseUrl")
  val adminSystemConfigListening: String      = key("ui.admin.system.config.listening")
  val adminSystemConfigRequireVerify: String  = key("ui.admin.system.config.requireVerify")
  val adminSystemConfigSecureCookie: String   = key("ui.admin.system.config.secureCookie")
  val adminSystemConfigSocial: String         = key("ui.admin.system.config.social")
  val adminSystemConfigSocialNone: String     = key("ui.admin.system.config.socialNone")
  val adminSystemConfigMail: String           = key("ui.admin.system.config.mail")
  val adminSystemConfigMailLogged: String     = key("ui.admin.system.config.mailLogged")
  val adminSystemConfigMailFrom: String       = key("ui.admin.system.config.mailFrom")
  val adminSystemConfigStartTls: String       = key("ui.admin.system.config.startTls")
  val adminSystemConfigDatabase: String       = key("ui.admin.system.config.database")
  val adminSystemConfigDatabaseUser: String   = key("ui.admin.system.config.databaseUser")
  val adminSystemConfigDatabaseSchema: String = key("ui.admin.system.config.databaseSchema")
  val adminSystemConfigSessionLife: String    = key("ui.admin.system.config.sessionLife")
  val adminSystemConfigVerifyLife: String     = key("ui.admin.system.config.verifyLife")
  val adminSystemConfigResetLife: String      = key("ui.admin.system.config.resetLife")

  /** `.one`/`.other`; every lifetime above renders its value through it. */
  val adminSystemConfigHours: String = pluralKey("ui.admin.system.config.hours")

  val adminSystemConfigRateLimit: String      = key("ui.admin.system.config.rateLimit")
  val adminSystemConfigRateLimitValue: String = key("ui.admin.system.config.rateLimitValue")
  val adminSystemConfigNettyThreads: String   = key("ui.admin.system.config.nettyThreads")
  val adminSystemConfigNettyAuto: String      = key("ui.admin.system.config.nettyAuto")

  val adminSystemConfigProxyHops: String        = key("ui.admin.system.config.proxyHops")
  val adminSystemConfigAttemptRetention: String = key("ui.admin.system.config.attemptRetention")
  val adminSystemConfigGuestRetention: String   = key("ui.admin.system.config.guestRetention")
  val adminSystemConfigCaptcha: String          = key("ui.admin.system.config.captcha")
  val adminSystemConfigCaptchaOff: String       = key("ui.admin.system.config.captchaOff")
  val adminSystemConfigCaptchaThreshold: String = key("ui.admin.system.config.captchaThreshold")
  val adminSystemConfigQuotaTags: String        = key("ui.admin.system.config.quotaTags")
  val adminSystemConfigQuotaWordPairs: String   = key("ui.admin.system.config.quotaWordPairs")
  val adminSystemConfigQuotaValue: String       = key("ui.admin.system.config.quotaValue")

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
  val adminSystemStatsLoginAttempts: String = key("ui.admin.system.stats.loginAttempts")
  val adminSystemStatsFailedLogins: String  = key("ui.admin.system.stats.failedLogins")
  val adminSystemStatsLockedOut: String     = key("ui.admin.system.stats.lockedOut")
  val adminSystemStatsAuditEntries: String  = key("ui.admin.system.stats.auditEntries")
  val adminSystemStatsGuests: String        = key("ui.admin.system.stats.guests")
  val adminSystemStatsWords: String         = key("ui.admin.system.stats.words")
  val adminSystemStatsTranslations: String  = key("ui.admin.system.stats.translations")
  val adminSystemStatsTags: String          = key("ui.admin.system.stats.tags")

  val adminSystemMaintenanceCard: String         = key("ui.admin.system.maintenance.card")
  val adminSystemMaintenanceHint: String         = key("ui.admin.system.maintenance.hint")
  val adminSystemMaintenancePrune: String        = key("ui.admin.system.maintenance.prune")
  val adminSystemMaintenanceClear: String        = key("ui.admin.system.maintenance.clear")
  val adminSystemMaintenanceClearConfirm: String = key("ui.admin.system.maintenance.clearConfirm")

  // -- Administration: usage statistics ----------------------------------------------------------

  val adminUsageTitle: String       = key("ui.admin.usage.title")
  val adminUsageWindowLabel: String = key("ui.admin.usage.windowLabel")

  val adminUsageMostCard: String    = key("ui.admin.usage.most.card")
  val adminUsageLeastCard: String   = key("ui.admin.usage.least.card")
  val adminUsageColMethod: String   = key("ui.admin.usage.colMethod")
  val adminUsageColRoute: String    = key("ui.admin.usage.colRoute")
  val adminUsageColCount: String    = key("ui.admin.usage.colCount")
  val adminUsageRoutesEmpty: String = key("ui.admin.usage.routesEmpty")
  val adminUsageRoutesCount: String = pluralKey("ui.admin.usage.routes.count")

  val adminUsageSuspiciousCard: String  = key("ui.admin.usage.suspicious.card")
  val adminUsageSuspiciousHint: String  = key("ui.admin.usage.suspicious.hint")
  val adminUsageColUser: String         = key("ui.admin.usage.colUser")
  val adminUsageColEvents: String       = key("ui.admin.usage.colEvents")
  val adminUsageColIps: String          = key("ui.admin.usage.colIps")
  val adminUsageSuspiciousEmpty: String = key("ui.admin.usage.suspiciousEmpty")
  val adminUsageSuspiciousCount: String = pluralKey("ui.admin.usage.suspicious.count")

  // -- Administration: word-form fan-out anomalies -----------------------------------------------
  // A dictionary-import data check: a word wrongly linked as an inflected "form" of far more lemmas than any real
  // inflection table ever has. See `gathedge.shared.dto.WordFormAnomaly`.

  val adminWordFormsTitle: String         = key("ui.admin.wordForms.title")
  val adminWordFormsHint: String          = key("ui.admin.wordForms.hint")
  val adminWordFormsEmpty: String         = key("ui.admin.wordForms.empty")
  val adminWordFormsColWord: String       = key("ui.admin.wordForms.colWord")
  val adminWordFormsColLanguage: String   = key("ui.admin.wordForms.colLanguage")
  val adminWordFormsColRelation: String   = key("ui.admin.wordForms.colRelation")
  val adminWordFormsColLemmaCount: String = key("ui.admin.wordForms.colLemmaCount")
  val adminWordFormsDelete: String        = key("ui.admin.wordForms.delete")
  val adminWordFormsDeleteConfirm: String = key("ui.admin.wordForms.deleteConfirm")
  val adminWordFormsDeleted: String       = key("ui.admin.wordForms.deleted")

  // -- Administration: rate limits --------------------------------------------------------------
  // Every `RateLimiter` key currently holding a failure — who is blocked or approaching it, and for which action. See
  // `gathedge.backend.service.RateLimitKey` for the namespaces this reads.

  val adminRateLimitsTitle: String           = key("ui.admin.rateLimits.title")
  val adminRateLimitsHint: String            = key("ui.admin.rateLimits.hint")
  val adminRateLimitsEmpty: String           = key("ui.admin.rateLimits.empty")
  val adminRateLimitsColScope: String        = key("ui.admin.rateLimits.colScope")
  val adminRateLimitsColWho: String          = key("ui.admin.rateLimits.colWho")
  val adminRateLimitsColAttempts: String     = key("ui.admin.rateLimits.colAttempts")
  val adminRateLimitsColStatus: String       = key("ui.admin.rateLimits.colStatus")
  val adminRateLimitsColRetry: String        = key("ui.admin.rateLimits.colRetry")
  val adminRateLimitsColOldest: String       = key("ui.admin.rateLimits.colOldest")
  val adminRateLimitsStatusBlocked: String   = key("ui.admin.rateLimits.statusBlocked")
  val adminRateLimitsStatusWarn: String      = key("ui.admin.rateLimits.statusWarn")
  val adminRateLimitsClear: String           = key("ui.admin.rateLimits.clear")
  val adminRateLimitsClearConfirm: String    = key("ui.admin.rateLimits.clearConfirm")
  val adminRateLimitsCleared: String         = key("ui.admin.rateLimits.cleared")
  val adminRateLimitsClearAll: String        = key("ui.admin.rateLimits.clearAll")
  val adminRateLimitsClearAllConfirm: String = key("ui.admin.rateLimits.clearAllConfirm")

  val adminRateLimitsScopeEmail: String       = key("ui.admin.rateLimits.scope.email")
  val adminRateLimitsScopeIp: String          = key("ui.admin.rateLimits.scope.ip")
  val adminRateLimitsScopeVerify: String      = key("ui.admin.rateLimits.scope.verify")
  val adminRateLimitsScopeSignup: String      = key("ui.admin.rateLimits.scope.signup")
  val adminRateLimitsScopePwReset: String     = key("ui.admin.rateLimits.scope.pwReset")
  val adminRateLimitsScopeGuest: String       = key("ui.admin.rateLimits.scope.guest")
  val adminRateLimitsScopeClaim: String       = key("ui.admin.rateLimits.scope.claim")
  val adminRateLimitsScopeGroupJoin: String   = key("ui.admin.rateLimits.scope.groupJoin")
  val adminRateLimitsScopeShareRedeem: String = key("ui.admin.rateLimits.scope.shareRedeem")
  val adminRateLimitsScopeWordUpload: String  = key("ui.admin.rateLimits.scope.wordUpload")
  val adminRateLimitsScopeOther: String       = key("ui.admin.rateLimits.scope.misc")

  // -- Vocabulary ------------------------------------------------------------------------------
  // The browse-and-tag screen. Note what is *not* here: a German noun's article is never
  // translated, and neither is the word itself — only the labels around them.

  val wordsTitle: String             = key("ui.words.title")
  val wordsSearchLabel: String       = key("ui.words.searchLabel")
  val wordsSearchPlaceholder: String = key("ui.words.searchPlaceholder")
  val wordsLanguageLabel: String     = key("ui.words.languageLabel")
  val wordsTargetLabel: String       = key("ui.words.targetLabel")
  val wordsPosLabel: String          = key("ui.words.posLabel")
  val wordsSwapLanguages: String     = key("ui.words.swapLanguages")
  val wordsPosAny: String            = key("ui.words.posAny")
  val wordsOnlyMine: String          = key("ui.words.onlyMine")
  val wordsMainOnly: String          = key("ui.words.mainOnly")

  /** The three-state filter shared by the word list and the bulk-upload review: every word, only those translated into
    * the target language, or only those translated into any language at all. See `TranslationFilter`.
    */
  val wordsTranslationFilterLabel: String  = key("ui.words.translationFilterLabel")
  val wordsTranslationFilterAll: String    = key("ui.words.translationFilterAll")
  val wordsTranslationFilterTarget: String = key("ui.words.translationFilterTarget")
  val wordsTranslationFilterAny: String    = key("ui.words.translationFilterAny")
  val wordsEmpty: String                   = key("ui.words.empty")

  /** `{0}` is the matching row count. */
  val wordsCount: String = pluralKey("ui.words.count")

  val wordsColWord: String         = key("ui.words.colWord")
  val wordsColPos: String          = key("ui.words.colPos")
  val wordsColTranslations: String = key("ui.words.colTranslations")
  val wordsColTagged: String       = key("ui.words.colTagged")

  /** The three form/variant columns: `colMainWord`/`colVariantType` are populated only on a row that is itself an
    * inflected/declined form of another word; `colVariants` only on a row that is a lemma with forms of its own. See
    * `dto.WordSummary`'s doc comment.
    */
  val wordsColMainWord: String    = key("ui.words.colMainWord")
  val wordsColVariantType: String = key("ui.words.colVariantType")
  val wordsColVariants: String    = key("ui.words.colVariants")

  /** `{0}` is how many more of a lemma's forms exist beyond the ones shown inline in its Variants cell. */
  val wordsVariantsMore: String = pluralKey("ui.words.variantsMore")

  /** Read out beside each row's toggle, so the control says which word it acts on. `{0}` is the word. */
  val wordsTagAdd: String    = key("ui.words.tagAdd")
  val wordsTagRemove: String = key("ui.words.tagRemove")

  /** Read out on each translation chip, so the control says what it acts on. `{0}` is the translation.
    *
    * A chip is a toggle, not a label: clicking one says "this is the answer I want to be asked for", which is a
    * different statement from the tick's "I am learning this word".
    */
  val wordsPairAdd: String    = key("ui.words.pairAdd")
  val wordsPairRemove: String = key("ui.words.pairRemove")

  /** Marked beside a word that is being learned with no answer chosen in the language the listing translates into — a
    * tick alone leaves a practice screen with nothing to check against. Worded as the gap rather than as an
    * instruction, since it is a state of the row and not something that failed.
    */
  val wordsNoPair: String = key("ui.words.noPair")

  /** The tag a tick files a word into. Deliberately worded as an action rather than as "Tag": the control next to it
    * narrows the listing instead, and the two used to be one select that did both.
    */
  val wordsCollectLabel: String = key("ui.words.collectLabel")
  val wordsCollectHint: String  = key("ui.words.collectHint")

  /** The second half of that hint: clicking a translation is the other thing this screen's rows can do. */
  val wordsPairHint: String = key("ui.words.pairHint")

  /** The other half of that pair: which tag the listing is narrowed to, which changes nothing about where a tick files.
    */
  val wordsFilterTagLabel: String = key("ui.words.filterTagLabel")
  val wordsFilterTagAny: String   = key("ui.words.filterTagAny")

  /** Names what the listing's three-state order button orders by: the tick that filed each word under the narrowed tag
    * — see `dto.WordSort`. Which way round is the button's glyph, as it is on a column heading. Shown only while the
    * filter above holds a tag, since it is the only thing it can order by.
    */
  val wordsSortAddedToTag: String = key("ui.words.sortAddedToTag")

  /** Clears every filter above back to its default, remembered per browser — shown only once a filter differs from it.
    * See `listing.WordQuery.filterOnly`/`.storedFilter`.
    */
  val wordsResetFilters: String = key("ui.words.resetFilters")

  val wordsTagNew: String            = key("ui.words.tagNew")
  val wordsTagNewPlaceholder: String = key("ui.words.tagNewPlaceholder")

  /** The two icon buttons beside the collect select, acting on whichever tag it currently holds — see
    * `WordCollect.renderCollectSelect`. Both the button's accessible label and its tooltip, since neither carries
    * visible text of its own.
    */
  val wordsTagRenameButton: String = key("ui.words.tagRenameButton")
  val wordsTagDeleteButton: String = key("ui.words.tagDeleteButton")

  val wordsTagRenameTitle: String = key("ui.words.tagRenameTitle")
  val wordsTagRenameLabel: String = key("ui.words.tagRenameLabel")

  val wordsTagDeleteTitle: String = key("ui.words.tagDeleteTitle")

  /** `{0}` is the tag's name. Says what stays, since deleting a tag is not deleting the words in it. */
  val wordsTagDeleteConfirm: String = key("ui.words.tagDeleteConfirm")

  /** The two groups both tag dropdowns split into, own tags first — see `Tag.sorted`. */
  val wordsTagsMineGroup: String   = key("ui.words.tagsMineGroup")
  val wordsTagsOthersGroup: String = key("ui.words.tagsOthersGroup")

  /** Opens `TagsPage` — every tag the reader may edit, laid out as a table rather than a dropdown. */
  val wordsTagsListButton: String = key("ui.words.tagsListButton")

  /** Offered on the collect select when the chosen tag is not the reader's own: seeds an empty tag of theirs with the
    * same name, since only an owner may file words under it. `{0}` is the tag's name.
    */
  val wordsTagCopy: String   = key("ui.words.tagCopy")
  val wordsTagCopied: String = key("ui.words.tagCopied")

  /** Offered when the search matches nothing: `{0}` is what the reader typed. */
  val wordsAddMissing: String = key("ui.words.addMissing")

  val wordsAddGender: String          = key("ui.words.addGender")
  val wordsAddGenderNone: String      = key("ui.words.addGenderNone")
  val wordsAddTranslation: String     = key("ui.words.addTranslation")
  val wordsAddTranslations: String    = key("ui.words.addTranslations")
  val wordsAddTranslationHint: String = key("ui.words.addTranslationHint")

  /** The optional pair that links the new word into `word_forms` as an inflected/declined form of an existing one — see
    * `CreateWordRequest.mainWordId`/`.variantType`. Both are meaningful only together: a variant type with no main word
    * names nothing to link, and the server silently does nothing with it.
    */
  val wordsAddMainWordSection: String     = key("ui.words.addMainWordSection")
  val wordsAddMainWordLabel: String       = key("ui.words.addMainWordLabel")
  val wordsAddMainWordPlaceholder: String = key("ui.words.addMainWordPlaceholder")
  val wordsAddVariantTypeLabel: String    = key("ui.words.addVariantTypeLabel")
  val wordsAddVariantTypeNone: String     = key("ui.words.addVariantTypeNone")

  /** Required by the licence the dictionary data is under, and so not optional page furniture. */
  val wordsAttribution: String = key("ui.words.attribution")

  // -- Bulk upload -------------------------------------------------------------------------------
  // The modal that scans an uploaded text file for words in the page's current language pair and
  // tags every match into the collect tag. See `BulkUploadDialog`.

  val wordsBulkUploadButton: String    = key("ui.words.bulkUpload.button")
  val wordsBulkUploadTitle: String     = key("ui.words.bulkUpload.title")
  val wordsBulkUploadHint: String      = key("ui.words.bulkUpload.hint")
  val wordsBulkUploadSizeError: String = key("ui.words.bulkUpload.sizeError")

  val wordsBulkUploadPasteDivider: String     = key("ui.words.bulkUpload.pasteDivider")
  val wordsBulkUploadPastePlaceholder: String = key("ui.words.bulkUpload.pastePlaceholder")
  val wordsBulkUploadPasteButton: String      = key("ui.words.bulkUpload.pasteButton")

  // The image-input alternative: OCR runs in the browser (see `ImageOcr`), and only the text it finds is sent.

  val wordsBulkUploadImageDivider: String = key("ui.words.bulkUpload.imageDivider")
  val wordsBulkUploadImageButton: String  = key("ui.words.bulkUpload.imageButton")
  val wordsBulkUploadImageHint: String    = key("ui.words.bulkUpload.imageHint")
  val wordsBulkUploadImageError: String   = key("ui.words.bulkUpload.imageError")
  val wordsBulkUploadRecognizing: String  = key("ui.words.bulkUpload.recognizing")

  /** `{0}` is the word count, `{1}` the tag name — a tag is data, not copy, so it is never translated (the same rule
    * `WordCollect.defaultTagName` follows).
    */
  val wordsBulkUploadResult: String = pluralKey("ui.words.bulkUpload.result")

  // The review step: what a preview found, before anything is written. See `BulkUploadDialog.Phase.ReviewMatched`.

  val wordsBulkUploadMatchedHeading: String   = key("ui.words.bulkUpload.matchedHeading")
  val wordsBulkUploadAcceptAll: String        = key("ui.words.bulkUpload.acceptAll")
  val wordsBulkUploadAcceptExact: String      = key("ui.words.bulkUpload.acceptExact")
  val wordsBulkUploadDeclineAll: String       = key("ui.words.bulkUpload.declineAll")
  val wordsBulkUploadNoTranslation: String    = key("ui.words.bulkUpload.noTranslation")
  val wordsBulkUploadUnmatchedHeading: String = key("ui.words.bulkUpload.unmatchedHeading")
  val wordsBulkUploadLanguageSkip: String     = key("ui.words.bulkUpload.languageSkip")
  val wordsBulkUploadNext: String             = key("ui.words.bulkUpload.next")

  /** Accepts every match that already carries a translation — one click away from done, unlike a match with none. */
  val wordsBulkUploadAcceptAllWithTranslation: String = key("ui.words.bulkUpload.acceptAllWithTranslation")

  /** Badge on a matched row whose dictionary translation was also one of the imported tokens: the pair is confirmed by
    * the upload itself, and the translation is not shown again as its own row.
    */
  val wordsBulkUploadExactBadge: String = key("ui.words.bulkUpload.exactBadge")

  /** The suggestions list: a dictionary word close enough to an OCR-misread token to guess at, shown separately from an
    * exact match and never opt-out-accepted the way a match is — see `BulkUploadDialog.renderSuggestionsSection`. `{0}`
    * in `suggestionOcrLabel` is the original, likely-misread token.
    */
  val wordsBulkUploadSuggestionsHeading: String = key("ui.words.bulkUpload.suggestionsHeading")
  val wordsBulkUploadSuggestionsHint: String    = key("ui.words.bulkUpload.suggestionsHint")
  val wordsBulkUploadSuggestionOcrLabel: String = key("ui.words.bulkUpload.suggestionOcrLabel")
  val wordsBulkUploadSuggestionBadge: String    = key("ui.words.bulkUpload.suggestionBadge")

  // The manual-matching step: unmatched tokens the reader assigned a language to, linked by hand. See
  // `BulkUploadDialog.Phase.ReviewManual`.

  val wordsBulkUploadManualHeading: String = key("ui.words.bulkUpload.manualHeading")
  val wordsBulkUploadManualHint: String    = key("ui.words.bulkUpload.manualHint")
  val wordsBulkUploadPairedHeading: String = key("ui.words.bulkUpload.pairedHeading")
  val wordsBulkUploadBack: String          = key("ui.words.bulkUpload.back")
  val wordsBulkUploadConfirmButton: String = key("ui.words.bulkUpload.confirmButton")

  val wordDetailTranslations: String      = key("ui.word.translations")
  val wordDetailTags: String              = key("ui.word.tags")
  val wordDetailNoTranslations: String    = key("ui.word.noTranslations")
  val wordDetailNoTags: String            = key("ui.word.noTags")
  val wordDetailBack: String              = key("ui.word.back")
  val wordDetailNotFound: String          = key("ui.word.notFound")
  val wordDetailRemoveTranslation: String = key("ui.word.removeTranslation")

  /** The detail page's own form, which is the only place a word gains a translation in a *third* language — the listing
    * can only offer the two it is showing.
    */
  val wordDetailAddTitle: String    = key("ui.word.addTitle")
  val wordDetailAddLanguage: String = key("ui.word.addLanguage")

  /** The article control on the word itself, offered only on a noun of a gendered language that was imported without
    * one. `setGenderConflictLink` is the way out of the 409: it points at the listing for this word, where the row that
    * already holds the article is shown beside the blank one.
    */
  val wordDetailSetGender: String             = key("ui.word.setGender")
  val wordDetailSetGenderHint: String         = key("ui.word.setGenderHint")
  val wordDetailSetGenderConflictLink: String = key("ui.word.setGenderConflictLink")

  /** Shown when this word is itself an inflected/declined form of another — `dto.WordDetail.mainWords`. */
  val wordDetailMainWordLabel: String = key("ui.word.mainWord.label")

  /** The Forms section, shown when this word is a lemma with forms of its own — `dto.WordDetail.forms`, grouped by
    * `GrammarCategory` in the same priority order `GrammarTag.priorityOf` sorts by, so the two never disagree about
    * which group of forms comes first.
    */
  val wordDetailFormsHeading: String             = key("ui.word.forms.heading")
  val wordDetailFormsCategoryPluralCase: String  = key("ui.word.forms.category.pluralCase")
  val wordDetailFormsCategoryTense: String       = key("ui.word.forms.category.tense")
  val wordDetailFormsCategoryComparison: String  = key("ui.word.forms.category.comparison")
  val wordDetailFormsCategoryDiminutive: String  = key("ui.word.forms.category.diminutive")
  val wordDetailFormsCategoryAlternative: String = key("ui.word.forms.category.alternative")
  val wordDetailFormsCategoryOther: String       = key("ui.word.forms.category.otherKind")

  /** Every individual `word_forms.relation` tag this catalog names — `Labels.grammarTag` resolves through these, one
    * suffix per canonical wiktextract tag, falling back to a plain humanized rendering of the tag itself for anything
    * not listed here (see `Labels.grammarTag`'s doc comment — that fallback is required, not a gap to close, since
    * `relation` is deliberately not a closed enum). Coverage matches `GrammarTag.known`.
    */
  val grammarTagPrefix: String = "ui.grammarTag."

  val grammarTagPlural: String            = key(grammarTagPrefix + "plural")
  val grammarTagSingular: String          = key(grammarTagPrefix + "singular")
  val grammarTagDefinite: String          = key(grammarTagPrefix + "definite")
  val grammarTagIndefinite: String        = key(grammarTagPrefix + "indefinite")
  val grammarTagNominative: String        = key(grammarTagPrefix + "nominative")
  val grammarTagAccusative: String        = key(grammarTagPrefix + "accusative")
  val grammarTagDative: String            = key(grammarTagPrefix + "dative")
  val grammarTagGenitive: String          = key(grammarTagPrefix + "genitive")
  val grammarTagPossessedSingle: String   = key(grammarTagPrefix + "possessed-single")
  val grammarTagPossessedMany: String     = key(grammarTagPrefix + "possessed-many")
  val grammarTagSuperessive: String       = key(grammarTagPrefix + "superessive")
  val grammarTagSublative: String         = key(grammarTagPrefix + "sublative")
  val grammarTagAllative: String          = key(grammarTagPrefix + "allative")
  val grammarTagAblative: String          = key(grammarTagPrefix + "ablative")
  val grammarTagInstrumental: String      = key(grammarTagPrefix + "instrumental")
  val grammarTagInessive: String          = key(grammarTagPrefix + "inessive")
  val grammarTagIllative: String          = key(grammarTagPrefix + "illative")
  val grammarTagElative: String           = key(grammarTagPrefix + "elative")
  val grammarTagDelative: String          = key(grammarTagPrefix + "delative")
  val grammarTagAdessive: String          = key(grammarTagPrefix + "adessive")
  val grammarTagTranslative: String       = key(grammarTagPrefix + "translative")
  val grammarTagCausalFinal: String       = key(grammarTagPrefix + "causal-final")
  val grammarTagTerminative: String       = key(grammarTagPrefix + "terminative")
  val grammarTagEssiveFormal: String      = key(grammarTagPrefix + "essive-formal")
  val grammarTagPast: String              = key(grammarTagPrefix + "past")
  val grammarTagPresent: String           = key(grammarTagPrefix + "present")
  val grammarTagFuture: String            = key(grammarTagPrefix + "future")
  val grammarTagPreterite: String         = key(grammarTagPrefix + "preterite")
  val grammarTagParticiple: String        = key(grammarTagPrefix + "participle")
  val grammarTagInfinitive: String        = key(grammarTagPrefix + "infinitive")
  val grammarTagInfinitiveZu: String      = key(grammarTagPrefix + "infinitive-zu")
  val grammarTagSubjunctive: String       = key(grammarTagPrefix + "subjunctive")
  val grammarTagSubjunctiveI: String      = key(grammarTagPrefix + "subjunctive-i")
  val grammarTagSubjunctiveIi: String     = key(grammarTagPrefix + "subjunctive-ii")
  val grammarTagIndicative: String        = key(grammarTagPrefix + "indicative")
  val grammarTagImperative: String        = key(grammarTagPrefix + "imperative")
  val grammarTagSubordinateClause: String = key(grammarTagPrefix + "subordinate-clause")
  val grammarTagFirstPerson: String       = key(grammarTagPrefix + "first-person")
  val grammarTagSecondPerson: String      = key(grammarTagPrefix + "second-person")
  val grammarTagThirdPerson: String       = key(grammarTagPrefix + "third-person")
  val grammarTagAuxiliary: String         = key(grammarTagPrefix + "auxiliary")
  val grammarTagCausative: String         = key(grammarTagPrefix + "causative")
  val grammarTagNounFromVerb: String      = key(grammarTagPrefix + "noun-from-verb")
  val grammarTagComparative: String       = key(grammarTagPrefix + "comparative")
  val grammarTagSuperlative: String       = key(grammarTagPrefix + "superlative")
  val grammarTagDiminutive: String        = key(grammarTagPrefix + "diminutive")
  val grammarTagAlternative: String       = key(grammarTagPrefix + "alternative")
  val grammarTagDialectal: String         = key(grammarTagPrefix + "dialectal")
  val grammarTagNonstandard: String       = key(grammarTagPrefix + "nonstandard")
  val grammarTagColloquial: String        = key(grammarTagPrefix + "colloquial")
  val grammarTagArchaic: String           = key(grammarTagPrefix + "archaic")
  val grammarTagRare: String              = key(grammarTagPrefix + "rare")
  val grammarTagDated: String             = key(grammarTagPrefix + "dated")
  val grammarTagRegional: String          = key(grammarTagPrefix + "regional")
  val grammarTagPoetic: String            = key(grammarTagPrefix + "poetic")
  val grammarTagProscribed: String        = key(grammarTagPrefix + "proscribed")
  val grammarTagUncommon: String          = key(grammarTagPrefix + "uncommon")
  val grammarTagObsolete: String          = key(grammarTagPrefix + "obsolete")

  /** Every `WordLanguage`, `PartOfSpeech` and translation origin, resolved by suffix the way [[loginOutcomePrefix]] is.
    * The `<select>` values stay the wire codes.
    */
  val languagePrefix: String = "ui.language."

  val languageEn: String = key(languagePrefix + "en")
  val languageDe: String = key(languagePrefix + "de")
  val languageEs: String = key(languagePrefix + "es")
  val languageHu: String = key(languagePrefix + "hu")

  /** The parts of speech, spelled out rather than assembled from the wire code the way the two prefixes above are —
    * because one of those codes is `other`, and `ui.pos.other` would read to `MessagesSpec` as the plural half of a
    * `ui.pos` pair. `Labels.partOfSpeech` matches the enum exhaustively instead, which it can: unlike a stored code, an
    * enum cannot gain a case behind the frontend's back.
    */
  val posNoun: String      = key("ui.pos.noun")
  val posVerb: String      = key("ui.pos.verb")
  val posAdjective: String = key("ui.pos.adjective")
  val posAdverb: String    = key("ui.pos.adverb")
  val posOtherKind: String = key("ui.pos.otherKind")

  val originPrefix: String = "ui.origin."

  val originDictionary: String = key(originPrefix + "dictionary")
  val originPivot: String      = key(originPrefix + "pivot")
  val originForm: String       = key(originPrefix + "form")
  val originUser: String       = key(originPrefix + "user")

  // -- Tag creation ----------------------------------------------------------------------------
  // The dedicated page that builds a tag as an ordered list of bilingual pairs. See `TagCreatePage`.

  val tagsCreate: String          = key("ui.tags.create")
  val tagsName: String            = key("ui.tags.name")
  val tagsNamePlaceholder: String = key("ui.tags.namePlaceholder")

  /** Resting text in the source/target word inputs, `{0}` being the language's endonym (never translated). */
  val tagsSourcePlaceholder: String = key("ui.tags.sourcePlaceholder")
  val tagsTargetPlaceholder: String = key("ui.tags.targetPlaceholder")

  /** Heading above the two columns of pairs the reader has assembled so far. */
  val tagsPairs: String = key("ui.tags.pairs")

  /** Grammar of a word. Names the autocomplete row's badge, the inline selector beside a word the dictionary does not
    * have, and the pairs table's column.
    */
  val tagsPartOfSpeech: String = key("ui.tags.partOfSpeech")

  /** Accessible name of the row of chips offering the source word's known translations. */
  val tagsTranslations: String = key("ui.tags.translations")

  /** Marked on the autocomplete row that creates the typed text as a brand-new word. */
  val tagsNewWord: String = key("ui.tags.newWord")

  /** Accessible label for a pair's remove button. */
  val tagsRemovePair: String = key("ui.tags.removePair")

  /** Why a pair the reader just completed did not appear: the list already holds it. `{0}` and `{1}` are its two sides.
    */
  val tagsDuplicatePair: String = key("ui.tags.duplicatePair")

  /** Save is blocked until the reader has added at least one pair; `{0}` is the tag name. */
  val tagsEmptyPairs: String = key("ui.tags.emptyPairs")

  /** Confirmation shown after the tag is saved, `{0}` being the tag name. */
  val tagsSaved: String = key("ui.tags.saved")

  // -- Guest accounts --------------------------------------------------------------------------
  // The banner a visitor who has tagged something sees, and the two ways out of it: a transfer code
  // for another machine, or a real account.

  val guestBannerTitle: String = key("ui.guest.bannerTitle")
  val guestBannerHint: String  = key("ui.guest.bannerHint")
  val guestGetCode: String     = key("ui.guest.getCode")
  val guestUpgrade: String     = key("ui.guest.upgrade")

  val guestCodeOnce: String   = key("ui.guest.codeOnce")
  val guestCodeCopy: String   = key("ui.guest.codeCopy")
  val guestCodeCopied: String = key("ui.guest.codeCopied")
  val guestCodeClose: String  = key("ui.guest.codeClose")

  val guestUpgradeTitle: String = key("ui.guest.upgradeTitle")
  val guestUpgradeHint: String  = key("ui.guest.upgradeHint")

  val guestClaimHint: String        = key("ui.guest.claimHint")
  val guestClaimPlaceholder: String = key("ui.guest.claimPlaceholder")
  val guestClaimSubmit: String      = key("ui.guest.claimSubmit")
  val guestClaimLink: String        = key("ui.guest.claimLink")

  /** What the account menu says instead of an address, for an account that has none. */
  val guestAccountLabel: String = key("ui.guest.accountLabel")

  /** Body copy of the confirm dialog shown before a guest's "Sign in" menu item navigates away — signing into a
    * different, already-existing account abandons this guest's words rather than merging them.
    */
  val guestSignInWarning: String = key("ui.guest.signInWarning")

  // -- Games -------------------------------------------------------------------------------------

  val gamesTitle: String          = key("ui.games.title")
  val gamesVocabQuizTitle: String = key("ui.games.vocabQuiz.title")
  val gamesVocabQuizBody: String  = key("ui.games.vocabQuiz.body")
  val gamesVocabQuizPlay: String  = key("ui.games.vocabQuiz.play")

  /** The "games" card on the catalog page — a link to [[allGamesTitle]]'s table, shown only when signed in. */
  val gamesAllGamesTitle: String = key("ui.games.allGames.title")
  val gamesAllGamesBody: String  = key("ui.games.allGames.body")
  val gamesAllGamesOpen: String  = key("ui.games.allGames.open")

  /** The "my play history" card — a link to [[myPlaysTitle]]'s table, shown only when signed in. */
  val gamesMyPlaysTitle: String = key("ui.games.myPlays.title")
  val gamesMyPlaysBody: String  = key("ui.games.myPlays.body")
  val gamesMyPlaysOpen: String  = key("ui.games.myPlays.open")

  /** The "shared with me" card — a link to [[sharedProgressTitle]], shown only when signed in. */
  val gamesSharedProgressTitle: String = key("ui.games.sharedProgress.title")
  val gamesSharedProgressBody: String  = key("ui.games.sharedProgress.body")
  val gamesSharedProgressOpen: String  = key("ui.games.sharedProgress.open")

  // -- Games ------------------------------------------------------------------------------------

  /** Every account's games: name, tags, language pair, how many times each was played, how many accounts favorited it,
    * and when it was created. Paged/sorted/filtered the same way the play history is, plus a per-row favorite toggle
    * and a "my favorites" filter.
    */
  val allGamesTitle: String             = key("ui.allGames.title")
  val allGamesEmpty: String             = key("ui.allGames.empty")
  val allGamesNameCol: String           = key("ui.allGames.nameCol")
  val allGamesTagsCol: String           = key("ui.allGames.tagsCol")
  val allGamesSourceCol: String         = key("ui.allGames.sourceCol")
  val allGamesTargetCol: String         = key("ui.allGames.targetCol")
  val allGamesPlaysCol: String          = key("ui.allGames.playsCol")
  val allGamesLikesCol: String          = key("ui.allGames.likesCol")
  val allGamesCreatedCol: String        = key("ui.allGames.createdCol")
  val allGamesFilterLabel: String       = key("ui.allGames.filterLabel")
  val allGamesFilterPlaceholder: String = key("ui.allGames.filterPlaceholder")
  val allGamesFavoritesFilter: String   = key("ui.allGames.favoritesFilter")
  val allGamesFavoriteAdd: String       = key("ui.allGames.favoriteAdd")
  val allGamesFavoriteRemove: String    = key("ui.allGames.favoriteRemove")
  val allGamesCount: String             = pluralKey("ui.allGames.count")

  // -- Tag words list ---------------------------------------------------------------------------

  /** The source-word/translation preview shared by `components.TagWordsList` — used both when creating a quiz
    * (`GameSetupPage`) and when starting a new play of an existing one (`GameInstancePage`).
    */
  val tagWordsListHeading: String = key("ui.tagWordsList.heading")
  val tagWordsListCount: String   = pluralKey("ui.tagWordsList.count")
  val tagWordsListEmpty: String   = key("ui.tagWordsList.empty")
  val tagWordsListToggle: String  = key("ui.tagWordsList.toggle")

  // -- Tag detail ---------------------------------------------------------------------------------
  // A standalone read-only view of one tag's words and marked translations (`TagDetailPage`), reusing
  // `TagWordsList` and `gameSetupSourceLabel`/`gameSetupTargetLabel` for the language picker rather than
  // minting a second pair of labels with the same meaning.

  val tagDetailTitle: String      = key("ui.tagDetail.title")
  val tagDetailOwnerLabel: String = key("ui.tagDetail.ownerLabel")
  val tagDetailGroupLabel: String = key("ui.tagDetail.groupLabel")

  // -- Tags list ------------------------------------------------------------------------------------
  // Every tag the reader may edit (`TagsPage`), reached from `WordCollect.renderBar`'s "All tags" button — the same
  // set `WordCollect.mineOptions` offers, as a table instead of a dropdown.

  val tagsListTitle: String    = key("ui.tagsList.title")
  val tagsListYours: String    = key("ui.tagsList.yours")
  val tagsListColName: String  = key("ui.tagsList.colName")
  val tagsListColWords: String = key("ui.tagsList.colWords")
  val tagsListEmpty: String    = key("ui.tagsList.empty")

  // -- Game setup ----------------------------------------------------------------------------------

  val gameSetupTitle: String                = key("ui.gameSetup.title")
  val gameSetupSourceLabel: String          = key("ui.gameSetup.sourceLabel")
  val gameSetupTargetLabel: String          = key("ui.gameSetup.targetLabel")
  val gameSetupTagsLabel: String            = key("ui.gameSetup.tagsLabel")
  val gameSetupTagFilterLabel: String       = key("ui.gameSetup.tagFilterLabel")
  val gameSetupTagFilterPlaceholder: String = key("ui.gameSetup.tagFilterPlaceholder")
  val gameSetupNoEligibleTags: String       = key("ui.gameSetup.noEligibleTags")
  val gameSetupNoMatchingTags: String       = key("ui.gameSetup.noMatchingTags")
  val gameSetupPlay: String                 = key("ui.gameSetup.play")
  val gameSetupCreated: String              = key("ui.gameSetup.created")

  // -- Game instance -----------------------------------------------------------------------------

  val gameInstanceNotFound: String = key("ui.gameInstance.notFound")
  val gameInstanceStart: String    = key("ui.gameInstance.start")

  val gameInstanceDirectionSwap: String = key("ui.gameInstance.direction.swap")

  val gameInstanceWordLimitLabel: String  = key("ui.gameInstance.wordLimit.label")
  val gameInstanceWordLimitAll: String    = key("ui.gameInstance.wordLimit.all")
  val gameInstanceWordLimitCustom: String = key("ui.gameInstance.wordLimit.custom")
  val gameInstanceWordLimitCount: String  = key("ui.gameInstance.wordLimit.count")

  val gameInstanceIncludeArticlesLabel: String = key("ui.gameInstance.includeArticles.label")
  val gameInstanceIncludeArticlesHint: String  = key("ui.gameInstance.includeArticles.hint")

  val gameInstancePreferenceLabel: String        = key("ui.gameInstance.preference.label")
  val gameInstancePreferenceAll: String          = key("ui.gameInstance.preference.all")
  val gameInstancePreferenceUnplayed: String     = key("ui.gameInstance.preference.unplayed")
  val gameInstancePreferenceMostMistakes: String = key("ui.gameInstance.preference.mostMistakes")

  val gameInstanceModeLabel: String          = key("ui.gameInstance.mode.label")
  val gameInstanceModeTyping: String         = key("ui.gameInstance.mode.typing")
  val gameInstanceModeMultipleChoice: String = key("ui.gameInstance.mode.multipleChoice")

  val gameInstanceProgress: String          = key("ui.gameInstance.progress")
  val gameInstanceAnswerLabel: String       = key("ui.gameInstance.answerLabel")
  val gameInstanceAnswerPlaceholder: String = key("ui.gameInstance.answerPlaceholder")
  val gameInstanceSubmit: String            = key("ui.gameInstance.submit")
  val gameInstanceChooseLabel: String       = key("ui.gameInstance.chooseLabel")
  val gameInstanceFinishedTitle: String     = key("ui.gameInstance.finishedTitle")
  val gameInstanceBackToGames: String       = key("ui.gameInstance.backToGames")

  // The results screen: score, and the full per-word answer table.
  val gameInstanceScore: String              = key("ui.gameInstance.score")
  val gameInstanceResultsWordCol: String     = key("ui.gameInstance.results.wordCol")
  val gameInstanceResultsExpectedCol: String = key("ui.gameInstance.results.expectedCol")
  val gameInstanceResultsAnswerCol: String   = key("ui.gameInstance.results.answerCol")
  val gameInstanceResultsOutcomeCol: String  = key("ui.gameInstance.results.outcomeCol")
  val gameInstanceOutcomeCorrect: String     = key("ui.gameInstance.outcome.correct")
  val gameInstanceOutcomeTypo: String        = key("ui.gameInstance.outcome.typo")
  val gameInstanceOutcomeWrong: String       = key("ui.gameInstance.outcome.wrong")
  val gameInstancePlayAgain: String          = key("ui.gameInstance.playAgain")

  // Inline rename, offered only to a browser that created this game — see `GameOwnership`'s doc comment on why
  // there is no server-asserted "you own this" flag to key it off instead.
  val gameInstanceRenameEdit: String  = key("ui.gameInstance.rename.edit")
  val gameInstanceRenameLabel: String = key("ui.gameInstance.rename.label")

  // Owner-only — links to the results listing below.
  val gameInstanceViewResults: String = key("ui.gameInstance.viewResults")

  // -- Game results ------------------------------------------------------------------------------
  // The owner-facing "who played my game" listing (`GET /api/games/{slug}/plays`) and its per-player detail modal.

  val gameResultsTitle: String             = key("ui.gameResults.title")
  val gameResultsFilterLabel: String       = key("ui.gameResults.filterLabel")
  val gameResultsFilterPlaceholder: String = key("ui.gameResults.filterPlaceholder")
  val gameResultsEmpty: String             = key("ui.gameResults.empty")
  val gameResultsCount: String             = pluralKey("ui.gameResults.count")
  val gameResultsPlayerCol: String         = key("ui.gameResults.playerCol")
  val gameResultsScoreCol: String          = key("ui.gameResults.scoreCol")
  val gameResultsWordCountCol: String      = key("ui.gameResults.wordCountCol")
  val gameResultsVariantCol: String        = key("ui.gameResults.variantCol")
  val gameResultsStartedCol: String        = key("ui.gameResults.startedCol")
  val gameResultsViewButton: String        = key("ui.gameResults.viewButton")
  val gameResultsGuestBadge: String        = key("ui.gameResults.guestBadge")

  // The result modal: prev/next step through the currently loaded page only — see `GameResultsPage`'s doc comment.
  val gameResultsModalTitle: String   = key("ui.gameResults.modal.title")
  val gameResultsModalClose: String   = key("ui.gameResults.modal.close")
  val gameResultsModalPrev: String    = key("ui.gameResults.modal.prev")
  val gameResultsModalNext: String    = key("ui.gameResults.modal.next")
  val gameResultsModalLoading: String = key("ui.gameResults.modal.loading")

  // -- My play history ---------------------------------------------------------------------------
  // The signed-in caller's own play history across every game — `GET /api/games/plays/mine`. Always the caller's
  // own data, unlike the owner-facing listing above.

  val myPlaysTitle: String             = key("ui.myPlays.title")
  val myPlaysEmpty: String             = key("ui.myPlays.empty")
  val myPlaysGameCol: String           = key("ui.myPlays.gameCol")
  val myPlaysScoreCol: String          = key("ui.myPlays.scoreCol")
  val myPlaysWordsCol: String          = key("ui.myPlays.wordsCol")
  val myPlaysStartedCol: String        = key("ui.myPlays.startedCol")
  val myPlaysFilterLabel: String       = key("ui.myPlays.filterLabel")
  val myPlaysFilterPlaceholder: String = key("ui.myPlays.filterPlaceholder")
  val myPlaysCount: String             = pluralKey("ui.myPlays.count")

  // An unfinished play — `finishedAt` still unset — shows this badge in place of a score, and a "Continue" action
  // that re-enters the play loop where the player left off. Progress is already on the server; the loop is
  // `playId`-addressed and resumes from the next unanswered word.
  val myPlaysInProgress: String     = key("ui.myPlays.inProgress")
  val myPlaysContinueButton: String = key("ui.myPlays.continueButton")

  // -- Progress sharing --------------------------------------------------------------------------
  // Letting one account read another's game history, on either side's own say-so — a "sharer" whose plays become
  // visible and a "viewer" who may read them, joined by a share code, never a role like "parent" or "teacher".

  /** The share-my-progress card on the settings page: mint/display the caller's own code, and the list of accounts it
    * has been redeemed by. Mirrors `guestBannerTitle`/`guestCodeOnce` etc — the same shape as the guest transfer code,
    * minus the "once" framing, since this code is meant to be shared with more than one person.
    */
  val settingsShareTitle: String        = key("ui.settings.share.title")
  val settingsShareHint: String         = key("ui.settings.share.hint")
  val settingsShareGetCode: String      = key("ui.settings.share.getCode")
  val settingsShareCopy: String         = key("ui.settings.share.copy")
  val settingsShareCopied: String       = key("ui.settings.share.copied")
  val settingsShareViewersTitle: String = key("ui.settings.share.viewersTitle")
  val settingsShareViewersEmpty: String = key("ui.settings.share.viewersEmpty")
  val settingsShareRevoke: String       = key("ui.settings.share.revoke")

  /** The viewer-facing page: redeeming somebody else's code, and the list of accounts already sharing with the caller.
    */
  val sharedProgressTitle: String             = key("ui.sharedProgress.title")
  val sharedProgressRedeemLabel: String       = key("ui.sharedProgress.redeemLabel")
  val sharedProgressRedeemPlaceholder: String = key("ui.sharedProgress.redeemPlaceholder")
  val sharedProgressRedeemButton: String      = key("ui.sharedProgress.redeemButton")
  val sharedProgressRedeemSuccess: String     = key("ui.sharedProgress.redeemSuccess")
  val sharedProgressListTitle: String         = key("ui.sharedProgress.listTitle")
  val sharedProgressListEmpty: String         = key("ui.sharedProgress.listEmpty")
  val sharedProgressViewButton: String        = key("ui.sharedProgress.viewButton")
  val sharedProgressGuestBadge: String        = key("ui.sharedProgress.guestBadge")
  val sharedProgressHistoryTitle: String      = key("ui.sharedProgress.historyTitle")

  // -- Groups ------------------------------------------------------------------------------------
  // Classroom-style tag groups: browsing/creating/joining (`GroupsPage`) and one group's roster,
  // invite code, and attached tags (`GroupDetailPage`). Guest-account rows reuse
  // `sharedProgressGuestBadge` rather than minting a second "guest" label.

  val groupsTitle: String             = key("ui.groups.title")
  val groupsEmpty: String             = key("ui.groups.empty")
  val groupsColName: String           = key("ui.groups.colName")
  val groupsColMembers: String        = key("ui.groups.colMembers")
  val groupsColTags: String           = key("ui.groups.colTags")
  val groupsMemberCount: String       = pluralKey("ui.groups.memberCount")
  val groupsTagCount: String          = pluralKey("ui.groups.tagCount")
  val groupsRoleAdmin: String         = key("ui.groups.roleAdmin")
  val groupsRoleMember: String        = key("ui.groups.roleMember")
  val groupsCreateLabel: String       = key("ui.groups.createLabel")
  val groupsCreatePlaceholder: String = key("ui.groups.createPlaceholder")
  val groupsCreateButton: String      = key("ui.groups.createButton")
  val groupsJoinLabel: String         = key("ui.groups.joinLabel")
  val groupsJoinPlaceholder: String   = key("ui.groups.joinPlaceholder")
  val groupsJoinButton: String        = key("ui.groups.joinButton")
  val groupsJoinSuccess: String       = key("ui.groups.joinSuccess")

  val groupDetailRenameEdit: String                  = key("ui.groupDetail.renameEdit")
  val groupDetailRenameLabel: String                 = key("ui.groupDetail.renameLabel")
  val groupDetailRosterTitle: String                 = key("ui.groupDetail.rosterTitle")
  val groupDetailRosterHidden: String                = key("ui.groupDetail.rosterHidden")
  val groupDetailPromoteButton: String               = key("ui.groupDetail.promoteButton")
  val groupDetailDemoteButton: String                = key("ui.groupDetail.demoteButton")
  val groupDetailRemoveButton: String                = key("ui.groupDetail.removeButton")
  val groupDetailRemoveConfirm: String               = key("ui.groupDetail.removeConfirm")
  val groupDetailLeaveButton: String                 = key("ui.groupDetail.leaveButton")
  val groupDetailLeaveConfirm: String                = key("ui.groupDetail.leaveConfirm")
  val groupDetailInviteCodeTitle: String             = key("ui.groupDetail.inviteCodeTitle")
  val groupDetailInviteCodeRegenerate: String        = key("ui.groupDetail.inviteCodeRegenerate")
  val groupDetailInviteCodeRegenerateConfirm: String = key("ui.groupDetail.inviteCodeRegenerateConfirm")
  val groupDetailTagsTitle: String                   = key("ui.groupDetail.tagsTitle")
  val groupDetailTagsEmpty: String                   = key("ui.groupDetail.tagsEmpty")
  val groupDetailAttachLabel: String                 = key("ui.groupDetail.attachLabel")
  val groupDetailAttachButton: String                = key("ui.groupDetail.attachButton")
  val groupDetailAttachNoneAvailable: String         = key("ui.groupDetail.attachNoneAvailable")
  val groupDetailDetachButton: String                = key("ui.groupDetail.detachButton")
  val groupDetailDetachConfirm: String               = key("ui.groupDetail.detachConfirm")

  // Where an invite link lands (`/groups/join/{code}`, see `Page.GroupJoin`) — join in progress, success, and failure.
  val groupJoinJoining: String    = key("ui.groupJoin.joining")
  val groupJoinSuccess: String    = key("ui.groupJoin.success")
  val groupJoinViewGroups: String = key("ui.groupJoin.viewGroups")

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
