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

  // -- Navigation ------------------------------------------------------------------------------

  val navMenu: String = key("ui.nav.menu")
  val navHome: String = key("ui.nav.home")

  /** The language picker's accessible name. It was the one page string reaching `I18n.t` as a bare literal rather than
    * a constant, under a key outside the `ui.` namespace — which is exactly the gap this object exists to close, since
    * neither of `MessagesSpec`'s checks could see it. `e2e/tests/translation.spec.ts` is what caught it.
    */
  val navLanguage: String = key("ui.nav.language")

  val navWords: String       = key("ui.nav.words")
  val navAdmin: String       = key("ui.nav.admin")
  val navAccountMenu: String = key("ui.nav.accountMenu")
  val navLogOut: String      = key("ui.nav.logOut")
  val navThemeDark: String   = key("ui.nav.themeDark")
  val navThemeLight: String  = key("ui.nav.themeLight")
  val navAdminUsers: String  = key("ui.nav.adminUsers")
  val navAdminSystem: String = key("ui.nav.adminSystem")

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

  // -- Status pages ----------------------------------------------------------------------------

  val forbiddenTitle: String = key("ui.status.forbidden.title")
  val forbiddenBody: String  = key("ui.status.forbidden.body")
  val notFoundTitle: String  = key("ui.status.notFound.title")
  val notFoundBody: String   = key("ui.status.notFound.body")
  val statusBackHome: String = key("ui.status.backHome")

  // -- Home ------------------------------------------------------------------------------------
  // The placeholder landing page. A new project replaces this screen first, and these two keys with
  // it — they are here so the skeleton renders in both languages out of the box.

  val homeTitle: String = key("ui.home.title")
  val homeBody: String  = key("ui.home.body")

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
  val adminSystemConfigVerifyLife: String    = key("ui.admin.system.config.verifyLife")

  /** `.one`/`.other`; both lifetimes above render their value through it. */
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

  // -- Vocabulary ------------------------------------------------------------------------------
  // The browse-and-tag screen. Note what is *not* here: a German noun's article is never
  // translated, and neither is the word itself — only the labels around them.

  val wordsTitle: String             = key("ui.words.title")
  val wordsSearchLabel: String       = key("ui.words.searchLabel")
  val wordsSearchPlaceholder: String = key("ui.words.searchPlaceholder")
  val wordsLanguageLabel: String     = key("ui.words.languageLabel")
  val wordsTargetLabel: String       = key("ui.words.targetLabel")
  val wordsPosLabel: String          = key("ui.words.posLabel")
  val wordsPosAny: String            = key("ui.words.posAny")
  val wordsOnlyMine: String          = key("ui.words.onlyMine")
  val wordsEmpty: String             = key("ui.words.empty")

  /** `{0}` is the matching row count. */
  val wordsCount: String = pluralKey("ui.words.count")

  val wordsColWord: String         = key("ui.words.colWord")
  val wordsColPos: String          = key("ui.words.colPos")
  val wordsColTranslations: String = key("ui.words.colTranslations")
  val wordsColTagged: String       = key("ui.words.colTagged")

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

  val wordsTagNew: String            = key("ui.words.tagNew")
  val wordsTagNewPlaceholder: String = key("ui.words.tagNewPlaceholder")

  /** Offered when the search matches nothing: `{0}` is what the reader typed. */
  val wordsAddMissing: String = key("ui.words.addMissing")

  val wordsAddGender: String          = key("ui.words.addGender")
  val wordsAddGenderNone: String      = key("ui.words.addGenderNone")
  val wordsAddTranslation: String     = key("ui.words.addTranslation")
  val wordsAddTranslations: String    = key("ui.words.addTranslations")
  val wordsAddTranslationHint: String = key("ui.words.addTranslationHint")

  /** Required by the licence the dictionary data is under, and so not optional page furniture. */
  val wordsAttribution: String = key("ui.words.attribution")

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

  /** Every `WordLanguage`, `PartOfSpeech` and translation origin, resolved by suffix the way [[loginOutcomePrefix]] is.
    * The `<select>` values stay the wire codes.
    */
  val languagePrefix: String = "ui.language."

  val languageEn: String = key(languagePrefix + "en")
  val languageDe: String = key(languagePrefix + "de")
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
  val originUser: String       = key(originPrefix + "user")

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
  val guestUpgradeDone: String  = key("ui.guest.upgradeDone")

  val guestClaimHint: String        = key("ui.guest.claimHint")
  val guestClaimPlaceholder: String = key("ui.guest.claimPlaceholder")
  val guestClaimSubmit: String      = key("ui.guest.claimSubmit")
  val guestClaimLink: String        = key("ui.guest.claimLink")

  /** What the account menu says instead of an address, for an account that has none. */
  val guestAccountLabel: String = key("ui.guest.accountLabel")

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
