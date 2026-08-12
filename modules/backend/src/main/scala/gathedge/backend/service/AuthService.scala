package gathedge.backend.service

import gathedge.backend.config.AppConfig
import gathedge.backend.db.{
  EmailVerificationTokenRepository,
  EmailVerificationTokenRow,
  GuestClaimCodeRepository,
  LoginAttemptRepository,
  LoginAttemptRow,
  OAuthIdentityRepository,
  OAuthIdentityRow,
  SessionRepository,
  SessionRow,
  UserRepository,
  UserRow,
}
import gathedge.backend.i18n.Messages
import gathedge.backend.security.{PasswordHasher, SecurityLog, SessionAuth, Tokens}
import gathedge.shared.domain.{Locale, OAuthProvider, Theme, User}
import gathedge.shared.domain.Locale.{code, urlPrefix}
import gathedge.shared.dto.{LinkedIdentity, LoginOutcome}
import gathedge.shared.i18n.MessageKeys
import gathedge.shared.i18n.MessageRef
import gathedge.shared.validation.Validation
import zio.*

import java.util.concurrent.TimeUnit

import OAuthProvider.wire

enum AuthFailure {
  case InvalidCredentials
  case EmailAlreadyRegistered
  case ValidationError(fieldErrors: Map[String, MessageRef])
  case RateLimited
  case OAuthFailed(reason: String)

  /** A social sign-in succeeded at the provider, but its email already belongs to an account that has never linked that
    * provider. Deliberately not a login: see [[AuthService.loginWithOAuth]].
    */
  case OAuthAccountExists(provider: OAuthProvider)

  /** That provider identity is already attached to some account — this one, or somebody else's. */
  case OAuthAlreadyLinked

  /** Unlinking would leave the account with no way to sign in at all. */
  case LastCredential

  /** The password was right, but the address has never been proven and `app.require-email-verification` is on. Only
    * [[AuthService.login]] raises this.
    */
  case EmailNotVerified

  /** No such verification token, or one already redeemed or past its expiry — deliberately one case for all three, so
    * the token space cannot be probed for near-misses.
    */
  case InvalidVerificationToken
}

/** The guest paths' failures, in three enums rather than one.
  *
  * Not tidiness: an endpoint declares exactly the statuses it can answer, and `ApiFailures` mappings return the *union*
  * of what they can produce, so one enum spanning all four paths would force every one of its statuses onto every one
  * of them — a 403 on minting, a 409 on redeeming a code. That is the same reasoning that gave `login` its own
  * `ApiFailures.authLogin` rather than widening `auth`'s union, applied one level earlier: narrow the service's error
  * type, not the description.
  */
enum GuestMintFailure {
  case RateLimited
}

enum GuestClaimFailure {
  case RateLimited

  /** No such transfer code, or one that has been revoked — one case for both, so the code space cannot be probed. */
  case InvalidCode
}

/** Issuing a transfer code can only be refused for one reason, and its endpoint declares only that status. */
enum GuestCodeFailure {
  case NotGuest
}

/** What can go wrong acting *on* a guest account: minting it a transfer code, or turning it into a real one. */
enum GuestAccountFailure {

  /** The caller has an address and a password already, so there is nothing to upgrade and no use for a bearer code. */
  case NotGuest

  case ValidationError(fieldErrors: Map[String, MessageRef])
  case EmailAlreadyRegistered
}

trait AuthService {

  /** `clientIp`, when known, is rate-limited alongside the email address.
    *
    * The session id is `None` when `app.require-email-verification` is on: the account exists but may not act until the
    * emailed link is followed, so there is nothing to hand the browser.
    *
    * `locale` is the language the signup form was in, and is stored on the account. It decides the language of the
    * verification email this issues — the only chance to get that right, since nothing else about a brand-new account
    * says what language its owner reads.
    */
  def signup(
    email: String,
    password: String,
    clientIp: Option[String] = None,
    locale: Locale = Locale.default,
  ): IO[AuthFailure, (User, Option[String])]
  def login(email: String, password: String, clientIp: Option[String] = None): IO[AuthFailure, (User, String)]

  /** Redeems a verification link. Single-use: the token is marked consumed whether or not the account was already
    * verified.
    */
  def verifyEmail(token: String): IO[AuthFailure, Unit]

  /** Issues a fresh link, replacing any outstanding one.
    *
    * Succeeds for an unknown address and for an already-verified one alike — reporting either would turn this into an
    * account-enumeration oracle. The one failure is [[AuthFailure.RateLimited]], so the form can say why nothing
    * happened.
    */
  def resendVerification(email: String, clientIp: Option[String] = None): IO[AuthFailure, Unit]

  /** Issues a fresh link for a named account, unconditionally.
    *
    * The administrator-facing counterpart to [[resendVerification]], and deliberately not the same method: that one
    * hides whether the address exists and spends a rate-limit budget, both of which exist to protect an *anonymous*
    * caller's target. Neither applies to a caller the `adminOnly` aspect has already identified, and both would make
    * the administrator's button lie about what it did.
    */
  def issueVerificationFor(userId: Long, email: String, locale: Locale): UIO[Unit]

  /** Signs in — or registers — the person behind a verified provider identity.
    *
    * Matching is on `(provider, subject)` only. When that misses but the email is already taken, this **fails** with
    * [[AuthFailure.OAuthAccountExists]] rather than logging into the existing account: any provider that lets a user
    * present an address they do not control would otherwise be an account-takeover path, and no amount of
    * `email_verified` checking generalises safely across providers. The recovery route is to sign in normally and link
    * the provider from the settings page, which is what [[linkOAuth]] is for.
    */
  def loginWithOAuth(identity: OAuthIdentity, locale: Locale = Locale.default): IO[AuthFailure, (User, String)]

  /** Attaches a provider identity to an already-authenticated user. */
  def linkOAuth(userId: Long, identity: OAuthIdentity): IO[AuthFailure, Unit]

  /** Detaches one, unless it is the account's last remaining credential. */
  def unlinkOAuth(userId: Long, provider: OAuthProvider): IO[AuthFailure, Unit]
  def listIdentities(userId: Long): UIO[List[LinkedIdentity]]
  def hasPassword(userId: Long): UIO[Boolean]

  /** `currentPassword` is verified only when the account already has one; a social-only account is setting its first.
    */
  def setPassword(userId: Long, currentPassword: Option[String], newPassword: String): IO[AuthFailure, Unit]
  def logout(sessionId: String): UIO[Unit]

  /** None both when there's no session and when it's expired/revoked. */
  def currentUser(sessionId: String): UIO[Option[User]]
  def updateTheme(userId: Long, theme: Theme): Task[User]

  /** Records the language the account chose. Which language a *page* renders in is decided by its URL prefix, not by
    * this — see `V8__user_locale.sql` for what the stored value is actually for.
    */
  def updateLocale(userId: Long, locale: Locale): Task[User]

  /** Mints an account with no address and no password, and a session to go with it.
    *
    * Called on a visitor's first *write*, never on a page view: a session per visit would be a row per crawler. The
    * session lasts a year (`SessionAuth.guestSessionDuration`) because a guest has no other way back in.
    */
  def createGuest(clientIp: Option[String], locale: Locale = Locale.default): IO[GuestMintFailure, (User, String)]

  /** A fresh transfer code for a guest account, answered once and never again. Existing codes stay usable — a reader
    * may want the same vocabulary on a phone and a laptop.
    *
    * Typed on the single case rather than on [[GuestAccountFailure]], because refusing a real account is the only way
    * this can fail and the endpoint declares exactly that one status.
    */
  def issueClaimCode(userId: Long): IO[GuestCodeFailure, String]

  /** Signs the caller in as the guest account a transfer code belongs to. */
  def claimGuest(code: String, clientIp: Option[String] = None): IO[GuestClaimFailure, (User, String)]

  /** Gives a guest account an address and a password, in place. Every tag and word it holds stays where it is, under
    * the same id; the session the caller already holds keeps working.
    */
  def upgradeGuest(userId: Long, email: String, password: String, locale: Locale = Locale.default): IO[
    GuestAccountFailure,
    User,
  ]
}

object AuthService {
  def signup(
    email: String,
    password: String,
    clientIp: Option[String] = None,
    locale: Locale = Locale.default,
  ): ZIO[AuthService, AuthFailure, (User, Option[String])] =
    ZIO.serviceWithZIO[AuthService](_.signup(email, password, clientIp, locale))

  def login(
    email: String,
    password: String,
    clientIp: Option[String] = None,
  ): ZIO[AuthService, AuthFailure, (User, String)] =
    ZIO.serviceWithZIO[AuthService](_.login(email, password, clientIp))

  def verifyEmail(token: String): ZIO[AuthService, AuthFailure, Unit] =
    ZIO.serviceWithZIO[AuthService](_.verifyEmail(token))

  def resendVerification(email: String, clientIp: Option[String] = None): ZIO[AuthService, AuthFailure, Unit] =
    ZIO.serviceWithZIO[AuthService](_.resendVerification(email, clientIp))

  def issueVerificationFor(userId: Long, email: String, locale: Locale): URIO[AuthService, Unit] =
    ZIO.serviceWithZIO[AuthService](_.issueVerificationFor(userId, email, locale))

  def loginWithOAuth(
    identity: OAuthIdentity,
    locale: Locale = Locale.default,
  ): ZIO[AuthService, AuthFailure, (User, String)] =
    ZIO.serviceWithZIO[AuthService](_.loginWithOAuth(identity, locale))

  def linkOAuth(userId: Long, identity: OAuthIdentity): ZIO[AuthService, AuthFailure, Unit] =
    ZIO.serviceWithZIO[AuthService](_.linkOAuth(userId, identity))

  def unlinkOAuth(userId: Long, provider: OAuthProvider): ZIO[AuthService, AuthFailure, Unit] =
    ZIO.serviceWithZIO[AuthService](_.unlinkOAuth(userId, provider))

  def listIdentities(userId: Long): URIO[AuthService, List[LinkedIdentity]] =
    ZIO.serviceWithZIO[AuthService](_.listIdentities(userId))

  def hasPassword(userId: Long): URIO[AuthService, Boolean] =
    ZIO.serviceWithZIO[AuthService](_.hasPassword(userId))

  def setPassword(
    userId: Long,
    currentPassword: Option[String],
    newPassword: String,
  ): ZIO[AuthService, AuthFailure, Unit] =
    ZIO.serviceWithZIO[AuthService](_.setPassword(userId, currentPassword, newPassword))

  def logout(sessionId: String): URIO[AuthService, Unit] =
    ZIO.serviceWithZIO[AuthService](_.logout(sessionId))

  def currentUser(sessionId: String): URIO[AuthService, Option[User]] =
    ZIO.serviceWithZIO[AuthService](_.currentUser(sessionId))

  def updateTheme(userId: Long, theme: Theme): RIO[AuthService, User] =
    ZIO.serviceWithZIO[AuthService](_.updateTheme(userId, theme))

  def updateLocale(userId: Long, locale: Locale): RIO[AuthService, User] =
    ZIO.serviceWithZIO[AuthService](_.updateLocale(userId, locale))

  def createGuest(
    clientIp: Option[String],
    locale: Locale = Locale.default,
  ): ZIO[AuthService, GuestMintFailure, (User, String)] =
    ZIO.serviceWithZIO[AuthService](_.createGuest(clientIp, locale))

  def issueClaimCode(userId: Long): ZIO[AuthService, GuestCodeFailure, String] =
    ZIO.serviceWithZIO[AuthService](_.issueClaimCode(userId))

  def claimGuest(code: String, clientIp: Option[String] = None): ZIO[AuthService, GuestClaimFailure, (User, String)] =
    ZIO.serviceWithZIO[AuthService](_.claimGuest(code, clientIp))

  def upgradeGuest(
    userId: Long,
    email: String,
    password: String,
    locale: Locale = Locale.default,
  ): ZIO[AuthService, GuestAccountFailure, User] =
    ZIO.serviceWithZIO[AuthService](_.upgradeGuest(userId, email, password, locale))

  /** Hashed once at startup rather than kept as a literal, so the work factor always matches whatever
    * [[gathedge.backend.security.PasswordHasher]] is configured with.
    */
  private val timingEqualizerSource = "account-enumeration-guard"

  /** How long a verification link stays redeemable. A day is generous for a link that goes straight back to the address
    * that just asked for it, and the account can request another at any time.
    */
  val verificationValidity: Duration = 24.hours

  val live: URLayer[
    UserRepository & SessionRepository & OAuthIdentityRepository & EmailVerificationTokenRepository &
      LoginAttemptRepository & GuestClaimCodeRepository & PasswordHasher & RateLimiter & EmailSender & Messages &
      AppConfig,
    AuthService,
  ] = ZLayer {
    for {
      userRepo            <- ZIO.service[UserRepository]
      sessionRepo         <- ZIO.service[SessionRepository]
      identityRepo        <- ZIO.service[OAuthIdentityRepository]
      tokenRepo           <- ZIO.service[EmailVerificationTokenRepository]
      attemptRepo         <- ZIO.service[LoginAttemptRepository]
      claimCodeRepo       <- ZIO.service[GuestClaimCodeRepository]
      hasher              <- ZIO.service[PasswordHasher]
      rateLimiter         <- ZIO.service[RateLimiter]
      emailSender         <- ZIO.service[EmailSender]
      messages            <- ZIO.service[Messages]
      config              <- ZIO.service[AppConfig]
      timingEqualizerHash <- hasher.hash(timingEqualizerSource).orDie
    } yield AuthServiceLive(
      userRepo,
      sessionRepo,
      identityRepo,
      tokenRepo,
      attemptRepo,
      claimCodeRepo,
      hasher,
      rateLimiter,
      emailSender,
      messages,
      config,
      timingEqualizerHash,
    ): AuthService
  }
}

final case class AuthServiceLive(
  userRepo: UserRepository,
  sessionRepo: SessionRepository,
  identityRepo: OAuthIdentityRepository,
  tokenRepo: EmailVerificationTokenRepository,
  attemptRepo: LoginAttemptRepository,
  claimCodeRepo: GuestClaimCodeRepository,
  hasher: PasswordHasher,
  rateLimiter: RateLimiter,
  emailSender: EmailSender,
  messages: Messages,
  config: AppConfig,
  /** Hash of a fixed throwaway string, verified against on the login paths that have no real hash to check, so that "no
    * such account" costs the same as "wrong password". Skipping the bcrypt work there made response time a reliable
    * oracle for which email addresses have accounts.
    */
  timingEqualizerHash: String,
) extends AuthService {

  private def logRateLimited(email: String): UIO[Unit] = {
    SecurityLog.warn(s"Rate limit exceeded for '$email'")
  }

  private def logFailedAttempt(email: String, reason: String): UIO[Unit] = {
    SecurityLog.warn(s"Failed auth attempt for '$email': $reason")
  }

  /** Records one sign-in attempt, successful or not, for the administrator's account view.
    *
    * The `security` log already carries these, but a log file is not queryable from a screen and the in-memory rate
    * limiter only knows the current 15-minute window. This is the durable half.
    *
    * '''A failure here is swallowed.''' Recording an attempt is observability, not authentication: a full disk or a
    * dropped connection must not turn a correct password into a failed sign-in, nor a wrong one into a server error.
    * `logError` rather than `ignore`, so the loss is visible in the log it is meant to supplement.
    */
  private def recordAttempt(
    normalizedEmail: String,
    userId: Option[Long],
    clientIp: Option[String],
    outcome: String,
  ): UIO[Unit] = {
    (
      for {
        now <- Clock.currentTime(TimeUnit.MILLISECONDS)
        _   <- attemptRepo.insert(LoginAttemptRow(0L, normalizedEmail, userId, clientIp, outcome, now))
      } yield ()
    ).catchAllCause(cause => ZIO.logErrorCause(s"Could not record a '$outcome' sign-in attempt", cause))
  }

  private def toDomain(row: UserRow): User = {
    User(
      row.id,
      row.email,
      row.isAdmin,
      Theme.fromString(row.theme).getOrElse(Theme.Light),
      Locale.fromString(row.locale).getOrElse(Locale.default),
      row.createdAt.toString,
      row.emailVerifiedAt.isDefined,
      row.isGuest,
    )
  }

  private def toLinkedIdentity(row: OAuthIdentityRow): Option[LinkedIdentity] = {
    // A row whose provider no longer parses is one this build has dropped support for. Skipping it keeps
    // the settings page renderable; the row stays in the table for whoever removed the case to deal with.
    OAuthProvider.fromString(row.provider).map(p => LinkedIdentity(p, row.email, row.createdAt.toString))
  }

  private def createSession(userId: Long, duration: Duration = SessionAuth.sessionDuration): UIO[String] = {
    for {
      id  <- Tokens.urlSafe()
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _   <- sessionRepo.insert(SessionRow(id, userId, now, now + duration.toMillis, None)).orDie
    } yield id
  }

  private def validateCredentials(email: String, password: String): IO[AuthFailure, Unit] = {
    val errors = {
      List(
        Validation.validateEmail(email).left.toOption.map("email" -> _),
        Validation.validatePassword(password).left.toOption.map("password" -> _),
      ).flatten.toMap
    }
    if (errors.nonEmpty)
      ZIO.fail(AuthFailure.ValidationError(errors))
    else
      ZIO.unit
  }

  /** The two dimensions [[login]] is limited on. Both are the un-namespaced keys, which is what makes
    * `AdminService.lockoutKeysFor` able to reconstruct them from a stored address and a stored origin.
    */
  private def loginRateLimitKeys(normalizedEmail: String, clientIp: Option[String]): List[String] = {
    RateLimitKey.email(normalizedEmail) :: clientIp.map(RateLimitKey.ip).toList
  }

  /** Signup's own budget, namespaced on both dimensions so neither can spend [[login]]'s — see [[RateLimitKey.signup]].
    */
  private def signupRateLimitKeys(normalizedEmail: String, clientIp: Option[String]): List[String] = {
    RateLimitKey.signup(normalizedEmail) :: clientIp.map(RateLimitKey.signup).toList
  }

  private def anyKeyBlocked(keys: List[String]): UIO[Boolean] = {
    ZIO.exists(keys)(rateLimiter.isBlocked)
  }

  private def recordFailure(keys: List[String]): UIO[Unit] = {
    ZIO.foreachDiscard(keys)(rateLimiter.recordFailure)
  }

  private def clearFailures(keys: List[String]): UIO[Unit] = {
    ZIO.foreachDiscard(keys)(rateLimiter.clear)
  }

  /** Burns the same bcrypt work a real verification would, so failing early can't be timed. */
  private def equalizeTiming: UIO[Unit] = {
    hasher.verify("", timingEqualizerHash).orDie.unit
  }

  /** Issues a fresh verification token for an account and emails the link, replacing any token still outstanding for
    * it.
    *
    * A send that fails is logged rather than propagated: it must not undo an otherwise-complete signup, and the account
    * is recoverable through [[resendVerification]] either way.
    */
  def issueVerificationFor(userId: Long, email: String, locale: Locale): UIO[Unit] = {
    issueVerification(userId, email, locale)
  }

  /** The account's stored language, for the two places that have a row in hand and need to write to its owner. */
  private def localeOf(row: UserRow): Locale = {
    Locale.fromString(row.locale).getOrElse(Locale.default)
  }

  private def issueVerification(userId: Long, email: String, locale: Locale): UIO[Unit] = {
    val catalog = messages.catalog(locale)
    for {
      token <- Tokens.urlSafe()
      now   <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _     <- tokenRepo.deleteForUser(userId).orDie
      _     <-
        tokenRepo
          .insert(
            EmailVerificationTokenRow(0L, userId, token, now, now + AuthService.verificationValidity.toMillis, None)
          )
          .orDie
      // The link carries the locale prefix so the page it lands on is in the language the mail was
      // written in — it is a full page load, so nothing else would tell the SPA which to render.
      link   = s"${config.app.publicBaseUrl}${locale.urlPrefix}/verify-email/$token"
      _     <- emailSender
                 .send(
                   email,
                   catalog(MessageKeys.emailVerifySubject),
                   catalog(
                     MessageKeys.emailVerifyBody,
                     link,
                     AuthService.verificationValidity.toHours.toString,
                   ),
                 )
                 .catchAllCause(cause => ZIO.logErrorCause(s"Could not send verification email to '$email'", cause))
    } yield ()
  }

  def signup(
    email: String,
    password: String,
    clientIp: Option[String],
    locale: Locale,
  ): IO[AuthFailure, (User, Option[String])] = {
    val normalizedEmail = email.trim.toLowerCase
    val keys            = signupRateLimitKeys(normalizedEmail, clientIp)
    for {
      _         <- validateCredentials(normalizedEmail, password)
      blocked   <- anyKeyBlocked(keys)
      _         <- ZIO.when(blocked)(logRateLimited(normalizedEmail) *> ZIO.fail(AuthFailure.RateLimited))
      existing  <- userRepo.findByEmail(normalizedEmail).orDie
      _         <-
        ZIO.when(existing.isDefined) {
          recordFailure(keys) *> ZIO.fail(AuthFailure.EmailAlreadyRegistered)
        }
      hash      <- hasher.hash(password).orDie
      now       <- Clock.currentTime(TimeUnit.MILLISECONDS)
      row       <- userRepo
                     .insert(normalizedEmail, Some(hash), isAdmin = false, "light", locale.code, now, emailVerifiedAt = None)
                     .orDie
      _         <- issueVerification(row.id, normalizedEmail, locale)
      // With verification mandatory the account cannot act until the link is followed, so it gets
      // no session at all rather than one that every guarded route would refuse.
      sessionId <- ZIO.unless(config.app.requireEmailVerification)(createSession(row.id))
      _         <- clearFailures(keys)
    } yield (toDomain(row), sessionId)
  }

  def login(email: String, password: String, clientIp: Option[String]): IO[AuthFailure, (User, String)] = {
    val normalizedEmail                                           = email.trim.toLowerCase
    val keys                                                      = loginRateLimitKeys(normalizedEmail, clientIp)
    // Every exit from this method records exactly one `login_attempts` row, including the successful one — an
    // administrator looking at "why can this person not sign in" needs the successes to tell a forgotten password
    // apart from an account nobody has touched in a month.
    def attempt(userId: Option[Long], outcome: String): UIO[Unit] = {
      recordAttempt(normalizedEmail, userId, clientIp, outcome)
    }

    for {
      blocked   <- anyKeyBlocked(keys)
      _         <-
        ZIO.when(blocked) {
          attempt(None, LoginOutcome.rateLimited) *> logRateLimited(normalizedEmail) *>
            ZIO.fail(AuthFailure.RateLimited)
        }
      maybeRow  <- userRepo.findByEmail(normalizedEmail).orDie
      row       <-
        maybeRow match {
          case None    =>
            equalizeTiming *> recordFailure(keys) *> attempt(None, LoginOutcome.unknownEmail) *>
              logFailedAttempt(normalizedEmail, "no such account") *> ZIO.fail(AuthFailure.InvalidCredentials)
          case Some(r) =>
            ZIO.succeed(r)
        }
      _         <-
        row.passwordHash match {
          case None       =>
            equalizeTiming *> recordFailure(keys) *> attempt(Some(row.id), LoginOutcome.noPassword) *>
              logFailedAttempt(normalizedEmail, "no password set (Google-only account)") *>
              ZIO.fail(AuthFailure.InvalidCredentials)
          case Some(hash) =>
            hasher
              .verify(password, hash)
              .orDie
              .flatMap { ok =>
                if (ok)
                  ZIO.unit
                else {
                  recordFailure(keys) *> attempt(Some(row.id), LoginOutcome.badPassword) *>
                    logFailedAttempt(normalizedEmail, "wrong password") *> ZIO.fail(AuthFailure.InvalidCredentials)
                }
              }
        }
      // Deliberately after the password check: refusing an unverified account before knowing the
      // password would tell an attacker which addresses have accounts.
      _         <-
        ZIO.when(config.app.requireEmailVerification && row.emailVerifiedAt.isEmpty) {
          // Recorded, but still not counted against the rate limit: the credentials were right, and the account is
          // being told to go and read its email, not being defended against.
          attempt(Some(row.id), LoginOutcome.emailNotVerified) *>
            logFailedAttempt(normalizedEmail, "email not verified") *> ZIO.fail(AuthFailure.EmailNotVerified)
        }
      sessionId <- createSession(row.id)
      _         <- clearFailures(keys)
      _         <- attempt(Some(row.id), LoginOutcome.success)
    } yield (toDomain(row), sessionId)
  }

  def verifyEmail(token: String): IO[AuthFailure, Unit] = {
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      row <- tokenRepo.findByToken(token).orDie.someOrFail(AuthFailure.InvalidVerificationToken)
      _   <- ZIO.when(row.consumedAt.isDefined || row.expiresAt <= now)(ZIO.fail(AuthFailure.InvalidVerificationToken))
      _   <- tokenRepo.markConsumed(token, now).orDie
      _   <- userRepo.markEmailVerified(row.userId, now).orDie
      _   <- SecurityLog.info(s"Email verified for user ${row.userId}")
    } yield ()
  }

  def resendVerification(email: String, clientIp: Option[String]): IO[AuthFailure, Unit] = {
    val normalizedEmail = email.trim.toLowerCase
    // Both keys go through the verification namespace, so asking for links neither consumes nor is
    // consumed by the login budget for the same address or address family.
    val keys            = RateLimitKey.verification(normalizedEmail) :: clientIp.map(RateLimitKey.verification).toList
    for {
      blocked  <- anyKeyBlocked(keys)
      _        <- ZIO.when(blocked)(logRateLimited(normalizedEmail) *> ZIO.fail(AuthFailure.RateLimited))
      // Every request counts, not just the ones that find an account — the limiter's only counter
      // is `recordFailure`, and counting successes is exactly what caps resends here.
      _        <- recordFailure(keys)
      maybeRow <- userRepo.findByEmail(normalizedEmail).orDie
      // Silence for an unknown or already-verified address: the caller gets the same 204 either
      // way, so this endpoint says nothing about which addresses have accounts.
      _        <- ZIO.foreachDiscard(maybeRow.filter(_.emailVerifiedAt.isEmpty)) { row =>
                    // The account's own language, not the language of the browser asking. A resend can be
                    // requested from the sign-in page in either one, and the mail belongs to the account.
                    // `normalizedEmail` rather than `row.email`: the row was found *by* that address, and the
                    // column is an `Option` only because a guest account has none.
                    issueVerification(row.id, normalizedEmail, localeOf(row))
                  }
    } yield ()
  }

  private def insertIdentity(userId: Long, identity: OAuthIdentity): UIO[Unit] = {
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _   <-
        identityRepo
          .insert(OAuthIdentityRow(0L, userId, identity.provider.wire, identity.subject, Some(identity.email), now))
          .orDie
    } yield ()
  }

  def loginWithOAuth(identity: OAuthIdentity, locale: Locale): IO[AuthFailure, (User, String)] = {
    val normalizedEmail = identity.email.trim.toLowerCase
    for {
      existingLink <- identityRepo.findByProviderAndSubject(identity.provider.wire, identity.subject).orDie
      row          <-
        existingLink match {
          case Some(link) =>
            // The link is a FK onto users with ON DELETE CASCADE, so a link with no user is a broken
            // invariant rather than a request the caller got wrong.
            userRepo
              .findById(link.userId)
              .orDie
              .someOrElseZIO(
                ZIO.die(new IllegalStateException(s"oauth_identities row ${link.id} points at a missing user"))
              )
          case None       =>
            userRepo
              .findByEmail(normalizedEmail)
              .orDie
              .flatMap {
                case Some(_) =>
                  SecurityLog.warn(
                    s"Refused ${identity.provider.wire} sign-in for '$normalizedEmail': " +
                      "the email belongs to an account with no such linked identity"
                  ) *> ZIO.fail(AuthFailure.OAuthAccountExists(identity.provider))
                case None    =>
                  for {
                    now     <- Clock.currentTime(TimeUnit.MILLISECONDS)
                    // Only providers that actually assert the claim count as proof. Microsoft never
                    // does, so accounts created through it start unverified — harmless, since they
                    // have no password and the gate is on password login.
                    created <-
                      userRepo
                        .insert(
                          normalizedEmail,
                          None,
                          isAdmin = false,
                          "light",
                          locale.code,
                          now,
                          emailVerifiedAt = Option.when(identity.emailVerified)(now),
                        )
                        .orDie
                    _       <- insertIdentity(created.id, identity)
                    _       <- SecurityLog.info(
                                 s"Account created via ${identity.provider.wire} sign-in for '$normalizedEmail'"
                               )
                  } yield created
              }
        }
      sessionId    <- createSession(row.id)
    } yield (toDomain(row), sessionId)
  }

  def linkOAuth(userId: Long, identity: OAuthIdentity): IO[AuthFailure, Unit] = {
    for {
      existingLink <- identityRepo.findByProviderAndSubject(identity.provider.wire, identity.subject).orDie
      _            <- ZIO.when(existingLink.isDefined)(ZIO.fail(AuthFailure.OAuthAlreadyLinked))
      mine         <- identityRepo.listForUser(userId).orDie
      // One identity per provider per account: two Google accounts on one login would make the
      // settings page's unlink-by-provider ambiguous.
      _            <- ZIO.when(mine.exists(_.provider == identity.provider.wire))(ZIO.fail(AuthFailure.OAuthAlreadyLinked))
      _            <- insertIdentity(userId, identity)
      // Linking already proved account ownership (it needs a session) and the provider proved the
      // address — but only for the address it reported, so this verifies nothing when the two differ.
      _            <-
        ZIO.when(identity.emailVerified) {
          userRepo
            .findById(userId)
            .orDie
            .flatMap {
              case Some(row) if row.emailVerifiedAt.isEmpty && row.email.contains(identity.email.trim.toLowerCase) =>
                Clock.currentTime(TimeUnit.MILLISECONDS).flatMap(now => userRepo.markEmailVerified(userId, now).orDie)
              case _                                                                                               =>
                ZIO.unit
            }
        }
      _            <- SecurityLog.info(s"Linked ${identity.provider.wire} identity to user $userId")
    } yield ()
  }

  def unlinkOAuth(userId: Long, provider: OAuthProvider): IO[AuthFailure, Unit] = {
    for {
      mine  <- identityRepo.listForUser(userId).orDie
      _     <- ZIO.unless(mine.exists(_.provider == provider.wire))(ZIO.fail(AuthFailure.OAuthFailed("not linked")))
      hasPw <- hasPassword(userId)
      // Without this an account whose only credential is the identity being removed becomes
      // permanently unreachable — there is no password to fall back to and no other provider.
      _     <- ZIO.when(!hasPw && mine.sizeIs <= 1)(ZIO.fail(AuthFailure.LastCredential))
      _     <- identityRepo.deleteByUserAndProvider(userId, provider.wire).orDie
      _     <- SecurityLog.info(s"Unlinked ${provider.wire} identity from user $userId")
    } yield ()
  }

  def listIdentities(userId: Long): UIO[List[LinkedIdentity]] = {
    identityRepo.listForUser(userId).orDie.map(_.flatMap(toLinkedIdentity))
  }

  def hasPassword(userId: Long): UIO[Boolean] = {
    userRepo.findById(userId).orDie.map(_.exists(_.passwordHash.isDefined))
  }

  def setPassword(userId: Long, currentPassword: Option[String], newPassword: String): IO[AuthFailure, Unit] = {
    for {
      row  <- userRepo
                .findById(userId)
                .orDie
                .someOrElseZIO(ZIO.die(new IllegalStateException(s"user $userId not found")))
      _    <- ZIO
                .fromEither(Validation.validatePassword(newPassword))
                .mapError(message => AuthFailure.ValidationError(Map("newPassword" -> message)))
      _    <-
        row.passwordHash match {
          case None           =>
            // No password yet, so there is nothing to prove: the session cookie is the authorisation.
            ZIO.unit
          case Some(existing) =>
            currentPassword match {
              case None           =>
                ZIO.fail(
                  AuthFailure.ValidationError(Map("currentPassword" -> MessageRef(MessageKeys.currentPasswordRequired)))
                )
              case Some(supplied) =>
                hasher
                  .verify(supplied, existing)
                  .orDie
                  .flatMap { ok =>
                    if (ok)
                      ZIO.unit
                    else {
                      // An account with no address is named by its id. Unreachable for a guest in practice — one has
                      // no password to get wrong — but the log line has to say who either way.
                      logFailedAttempt(
                        row.email.getOrElse(s"user id=${row.id}"),
                        "wrong current password on password change",
                      ) *>
                        ZIO.fail(
                          AuthFailure.ValidationError(
                            Map("currentPassword" -> MessageRef(MessageKeys.currentPasswordIncorrect))
                          )
                        )
                    }
                  }
            }
        }
      hash <- hasher.hash(newPassword).orDie
      _    <- userRepo.updatePasswordHash(userId, hash).orDie
      _    <- SecurityLog.info(
                s"Password ${
                    if (row.passwordHash.isDefined)
                      "changed"
                    else
                      "set"
                  } for user $userId"
              )
    } yield ()
  }

  def logout(sessionId: String): UIO[Unit] = {
    Clock.currentTime(TimeUnit.MILLISECONDS).flatMap(now => sessionRepo.revoke(sessionId, now).orDie)
  }

  /** One query, not two: this resolves the cookie on every authenticated request, so the join is worth having — see
    * [[gathedge.backend.db.SessionRepository.findActiveWithUser]].
    */
  def currentUser(sessionId: String): UIO[Option[User]] = {
    (
      for {
        now   <- Clock.currentTime(TimeUnit.MILLISECONDS)
        found <- sessionRepo.findActiveWithUser(sessionId, now)
      } yield found.map { case (_, userRow) =>
        toDomain(userRow)
      }
    ).orDie
  }

  def updateTheme(userId: Long, theme: Theme): Task[User] = {
    for {
      _   <- userRepo.updateTheme(userId, theme.toString.toLowerCase)
      row <- userRepo.findById(userId).someOrFail(new RuntimeException(s"user $userId not found"))
    } yield toDomain(row)
  }

  def updateLocale(userId: Long, locale: Locale): Task[User] = {
    for {
      _   <- userRepo.updateLocale(userId, locale.code)
      row <- userRepo.findById(userId).someOrFail(new RuntimeException(s"user $userId not found"))
    } yield toDomain(row)
  }

  // -- Guest accounts ---------------------------------------------------------------------------

  /** The row, if it is a guest. Everything below refuses to act on a real account rather than quietly doing something
    * odd to it: a transfer code on an account with a password would be a second, weaker credential for it.
    */
  private def requireGuest(userId: Long): IO[Unit, UserRow] = {
    // Fails with `Unit` and lets each caller name its own failure: the two callers answer the same condition under
    // different enums, because their endpoints declare different statuses.
    userRepo.findById(userId).orDie.someOrFail(()).filterOrFail(_.isGuest)(())
  }

  def createGuest(clientIp: Option[String], locale: Locale): IO[GuestMintFailure, (User, String)] = {
    // Keyed on the address alone, because a guest has nothing else to key on — and every call counts, since a success
    // is exactly what this budget exists to cap.
    val keys = clientIp.map(RateLimitKey.guest).toList
    for {
      blocked   <- anyKeyBlocked(keys)
      _         <- ZIO.when(blocked) {
                     SecurityLog.warn(s"Rate limit exceeded minting a guest account from ${clientIp.getOrElse("?")}") *>
                       ZIO.fail(GuestMintFailure.RateLimited)
                   }
      _         <- recordFailure(keys)
      now       <- Clock.currentTime(TimeUnit.MILLISECONDS)
      row       <- userRepo.insertGuest("light", locale.code, now).orDie
      sessionId <- createSession(row.id, SessionAuth.guestSessionDuration)
      _         <- SecurityLog.info(s"Minted guest account ${row.id}")
    } yield (toDomain(row), sessionId)
  }

  def issueClaimCode(userId: Long): IO[GuestCodeFailure, String] = {
    for {
      _    <- requireGuest(userId).mapError(_ => GuestCodeFailure.NotGuest)
      code <- Tokens.claimCode()
      now  <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _    <- claimCodeRepo.insert(userId, code, now).orDie
      // The code itself never appears in a log line: it is the credential, like a session id.
      _    <- SecurityLog.info(s"Issued a transfer code for guest account $userId")
    } yield code
  }

  def claimGuest(code: String, clientIp: Option[String]): IO[GuestClaimFailure, (User, String)] = {
    val normalized = Tokens.normalizeClaimCode(code)
    val keys       = clientIp.map(RateLimitKey.claim).toList
    for {
      blocked   <- anyKeyBlocked(keys)
      _         <- ZIO.when(blocked) {
                     SecurityLog.warn(s"Rate limit exceeded redeeming a transfer code from ${clientIp.getOrElse("?")}") *>
                       ZIO.fail(GuestClaimFailure.RateLimited)
                   }
      _         <- recordFailure(keys)
      found     <- claimCodeRepo.findActive(normalized).orDie
      claimed   <- found match {
                     case None       =>
                       SecurityLog.warn(s"Unknown transfer code offered from ${clientIp.getOrElse("?")}") *>
                         ZIO.fail(GuestClaimFailure.InvalidCode)
                     case Some(code) =>
                       ZIO.succeed(code)
                   }
      // A code whose account has since been deleted answers the same "no such code" as one that never existed.
      row       <- userRepo.findById(claimed.userId).orDie.someOrFail(GuestClaimFailure.InvalidCode)
      now       <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _         <- claimCodeRepo.markUsed(claimed.id, now).orDie
      sessionId <- createSession(row.id, SessionAuth.guestSessionDuration)
      _         <- clearFailures(keys)
      _         <- SecurityLog.info(s"Transfer code redeemed for account ${row.id}")
    } yield (toDomain(row), sessionId)
  }

  def upgradeGuest(userId: Long, email: String, password: String, locale: Locale): IO[GuestAccountFailure, User] = {
    val normalizedEmail = email.trim.toLowerCase
    for {
      _        <- requireGuest(userId).mapError(_ => GuestAccountFailure.NotGuest)
      // `validateCredentials` is shared with signup and speaks `AuthFailure`; only its one validation case is
      // reachable, and the catch-all keeps the match total without inventing a second failure.
      _        <- validateCredentials(normalizedEmail, password).mapError {
                    case AuthFailure.ValidationError(errors) =>
                      GuestAccountFailure.ValidationError(errors)
                    case _                                   =>
                      GuestAccountFailure.ValidationError(Map.empty)
                  }
      existing <- userRepo.findByEmail(normalizedEmail).orDie
      _        <- ZIO.when(existing.isDefined)(ZIO.fail(GuestAccountFailure.EmailAlreadyRegistered))
      hash     <- hasher.hash(password).orDie
      now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      // Unverified like any fresh signup, and the link goes out the same way. The session the caller already holds
      // keeps working — `app.require-email-verification` gates *signing in*, and throwing somebody out of the account
      // they have just claimed would be a strange way to congratulate them.
      _        <- userRepo.upgradeGuest(userId, normalizedEmail, hash, None).orDie
      // Every transfer code goes: they were a way into an account with nothing to lose, and this one now has a
      // password. Leaving them live would keep a weaker credential for a stronger account.
      _        <- claimCodeRepo.revokeAllFor(userId, now).orDie
      _        <- issueVerification(userId, normalizedEmail, locale)
      row      <- userRepo.findById(userId).orDie.someOrFail(GuestAccountFailure.NotGuest)
      _        <- SecurityLog.info(s"Guest account $userId became a registered account")
    } yield toDomain(row)
  }
}
