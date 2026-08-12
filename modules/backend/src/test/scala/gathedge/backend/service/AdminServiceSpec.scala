package gathedge.backend.service

import gathedge.backend.{RecordingEmailSender, SentEmails, TestDataSource}
import gathedge.backend.config.AppConfig
import gathedge.backend.i18n.Messages
import gathedge.backend.db.{
  AuditLogRepository,
  EmailVerificationTokenRepository,
  GuestClaimCodeRepository,
  LoginAttemptRepository,
  OAuthIdentityRepository,
  SessionRepository,
  UserRepository,
}
import gathedge.backend.security.PasswordHasher
import gathedge.shared.domain.OAuthProvider
import gathedge.shared.dto.{AuditAction, LoginOutcome, Paging}
import zio._
import zio.json._
import zio.test._

object AdminServiceSpec extends ZIOSpecDefault {

  private val repoLayers = {
    TestDataSource.sqlite >>> (
      UserRepository.test ++ SessionRepository.test ++ OAuthIdentityRepository.test ++
        EmailVerificationTokenRepository.test ++ LoginAttemptRepository.test ++ GuestClaimCodeRepository.test ++ AuditLogRepository.test
    )
  }

  /** AuthService shares the same DataSource so a session issued by a login can be checked against the effect an admin
    * password reset has on it. It is also a *dependency* of AdminService now — the admin resend and unlink paths
    * delegate to it rather than reimplementing token issuing and the last-credential rule — so the two are stacked
    * rather than built side by side.
    *
    * `>+>` throughout, so the repositories and the recording mailer stay in the environment: reading back an audit row
    * or the token out of a sent link is how several of these assert, and no service exposes either.
    */
  private val layer = {
    (
      repoLayers ++ PasswordHasher.live ++ RateLimiter.live ++ AppConfig.live ++ RecordingEmailSender.live ++
        Messages.live >+>
        (AuthService.live ++ AuditTrail.live)
    ) >+> AdminService.live
  }

  def spec = suite("AdminService (SQLite)")(
    test("creates a user and lists it") {
      for {
        created <- AdminService.createUser(AdminActor.system, "new@example.com", "password123", isAdmin = false)
        listed  <- AdminService.listUsers(page = Paging.firstPage, pageSize = 100, None, None, descending = false)
      } yield assertTrue(
        created.email.contains("new@example.com"),
        !created.isAdmin,
        listed.items.exists(_.id == created.id),
        listed.total == listed.items.size.toLong,
      )
    },
    test("rejects a duplicate email on create") {
      for {
        _      <- AdminService.createUser(AdminActor.system, "dup@example.com", "password123", isAdmin = false)
        result <- AdminService.createUser(AdminActor.system, "dup@example.com", "password456", isAdmin = false).either
      } yield assertTrue(result == Left(AdminFailure.DuplicateEmail))
    },
    test("rejects a weak password on create") {
      for {
        result <- AdminService.createUser(AdminActor.system, "weak@example.com", "short", isAdmin = false).either
      } yield assertTrue(result.isLeft)
    },
    test("viewing a nonexistent user fails with NotFound") {
      for {
        result <- AdminService.getUser(999999L).either
      } yield assertTrue(result == Left(AdminFailure.NotFound))
    },
    test("an admin cannot remove their own admin privileges") {
      for {
        admin  <- AdminService.createUser(AdminActor.system, "self-admin@example.com", "password123", isAdmin = true)
        result <-
          AdminService
            .updateUser(AdminActor(admin.id), admin.id, admin.email.getOrElse(""), isAdmin = false, password = None)
            .either
      } yield assertTrue(result == Left(AdminFailure.SelfDemote))
    },
    test("an admin cannot delete their own account") {
      for {
        admin  <- AdminService.createUser(AdminActor.system, "self-delete@example.com", "password123", isAdmin = true)
        result <- AdminService.deleteUser(AdminActor(admin.id), admin.id).either
      } yield assertTrue(result == Left(AdminFailure.SelfDelete))
    },
    test("editing a user to another user's email is rejected as a duplicate") {
      for {
        admin  <- AdminService.createUser(AdminActor.system, "admin2@example.com", "password123", isAdmin = true)
        other  <- AdminService.createUser(AdminActor.system, "other@example.com", "password123", isAdmin = false)
        result <-
          AdminService
            .updateUser(AdminActor(admin.id), other.id, "admin2@example.com", isAdmin = false, password = None)
            .either
      } yield assertTrue(result == Left(AdminFailure.DuplicateEmail))
    },
    test("blank password on update keeps the existing password (no-op)") {
      for {
        admin   <- AdminService.createUser(AdminActor.system, "admin3@example.com", "password123", isAdmin = true)
        user    <- AdminService.createUser(AdminActor.system, "keep-pw@example.com", "originalpw", isAdmin = false)
        updated <-
          AdminService
            .updateUser(AdminActor(admin.id), user.id, "keep-pw@example.com", isAdmin = false, password = Some(""))
      } yield assertTrue(updated.email.contains("keep-pw@example.com"))
    },
    test("resetting a user's password revokes their existing sessions") {
      for {
        admin       <- AdminService.createUser(AdminActor.system, "admin5@example.com", "password123", isAdmin = true)
        user        <- AdminService.createUser(AdminActor.system, "reset-pw@example.com", "originalpw", isAdmin = false)
        loginResult <- AuthService.login("reset-pw@example.com", "originalpw")
        sessionId    = loginResult._2
        before      <- AuthService.currentUser(sessionId)
        _           <- AdminService.updateUser(
                         AdminActor(admin.id),
                         user.id,
                         "reset-pw@example.com",
                         isAdmin = false,
                         password = Some("replacedpw"),
                       )
        after       <- AuthService.currentUser(sessionId)
      } yield assertTrue(before.isDefined, after.isEmpty)
    },
    suite("account diagnostics")(
      test("confirming an address on the user's behalf marks it verified and audits the action") {
        for {
          admin    <- AdminService.createUser(AdminActor.system, "verify-admin@example.com", "password123", isAdmin = true)
          // Through signup rather than createUser: an administrator-created account already starts confirmed, so it
          // could never exercise this.
          signedUp <- AuthService.signup("unconfirmed@example.com", "password123")
          before   <- AdminService.userDetail(signedUp._1.id)
          _        <- AdminService.verifyEmailFor(AdminActor(admin.id), signedUp._1.id)
          after    <- AdminService.userDetail(signedUp._1.id)
          audited  <-
            AdminService.auditLog(Paging.firstPage, 50, None, false, Some(AuditAction.userVerifyEmail), None, None)
        } yield assertTrue(
          before.emailVerifiedAt.isEmpty,
          !before.user.emailVerified,
          after.emailVerifiedAt.isDefined,
          after.user.emailVerified,
          audited.items.exists(entry =>
            entry.actorUserId.contains(admin.id) && entry.targetId.contains(signedUp._1.id.toString)
          ),
        )
      },
      test("confirming an already-confirmed address is a no-op rather than a failure") {
        for {
          user   <- AdminService.createUser(AdminActor.system, "already@example.com", "password123", isAdmin = false)
          result <- AdminService.verifyEmailFor(AdminActor.system, user.id).either
          detail <- AdminService.userDetail(user.id)
        } yield assertTrue(result.isRight, detail.emailVerifiedAt.isDefined)
      },
      test("an administrator-sent confirmation link is redeemable") {
        for {
          signedUp <- AuthService.signup("admin-resend@example.com", "password123")
          _        <- AdminService.resendVerificationFor(AdminActor.system, signedUp._1.id)
          token    <- SentEmails.lastVerificationToken
          _        <- ZIO.foreachDiscard(token)(AuthService.verifyEmail)
          detail   <- AdminService.userDetail(signedUp._1.id)
        } yield assertTrue(token.isDefined, detail.emailVerifiedAt.isDefined)
      },
      test("revoking sessions signs the user out everywhere") {
        for {
          user    <- AdminService.createUser(AdminActor.system, "revoke-me@example.com", "password123", isAdmin = false)
          session <- AuthService.login("revoke-me@example.com", "password123").map(_._2)
          before  <- AuthService.currentUser(session)
          detail  <- AdminService.userDetail(user.id)
          _       <- AdminService.revokeSessions(AdminActor.system, user.id)
          after   <- AuthService.currentUser(session)
          cleared <- AdminService.userDetail(user.id)
        } yield assertTrue(before.isDefined, detail.activeSessions == 1, after.isEmpty, cleared.activeSessions == 0)
      },
      test("detaching the only credential of a password-less account is refused") {
        val identity =
          OAuthIdentity(OAuthProvider.Google, "google-subject-1", "social@example.com", emailVerified = true)
        for {
          created <- AuthService.loginWithOAuth(identity)
          detail  <- AdminService.userDetail(created._1.id)
          result  <- AdminService.unlinkIdentity(AdminActor.system, created._1.id, OAuthProvider.Google).either
        } yield assertTrue(
          !detail.hasPassword,
          detail.identities.map(_.provider) == List(OAuthProvider.Google),
          result == Left(AdminFailure.LastCredential),
        )
      },
      test("detaching a provider that is not attached is a not-found") {
        for {
          user   <- AdminService.createUser(AdminActor.system, "no-social@example.com", "password123", isAdmin = false)
          result <- AdminService.unlinkIdentity(AdminActor.system, user.id, OAuthProvider.Microsoft).either
        } yield assertTrue(result == Left(AdminFailure.NotFound))
      },
      test("failed sign-ins are recorded, lock the account out, and the lockout can be cleared") {
        for {
          user     <- AdminService.createUser(AdminActor.system, "lockme@example.com", "password123", isAdmin = false)
          _        <- ZIO.foreachDiscard(1 to 5)(_ => AuthService.login("lockme@example.com", "wrong").either)
          blocked  <- AdminService.userDetail(user.id)
          // Right password, but the limiter answers before the password is even checked.
          refused  <- AuthService.login("lockme@example.com", "password123").either
          _        <- AdminService.clearLockout(AdminActor(user.id), user.id)
          unlocked <- AdminService.userDetail(user.id)
          allowed  <- AuthService.login("lockme@example.com", "password123").either
        } yield assertTrue(
          blocked.lockout.blocked,
          blocked.lockout.attempts == 5,
          blocked.lockout.maxAttempts == 5,
          blocked.recentLoginAttempts.count(_.outcome == LoginOutcome.badPassword) == 5,
          refused == Left(AuthFailure.RateLimited),
          !unlocked.lockout.blocked,
          allowed.isRight,
        )
      },
      test("a successful sign-in is recorded too") {
        for {
          user   <- AdminService.createUser(AdminActor.system, "recorded@example.com", "password123", isAdmin = false)
          _      <- AuthService.login("recorded@example.com", "password123")
          detail <- AdminService.userDetail(user.id)
          global <- AdminService.loginAttempts(50, Some(LoginOutcome.success))
        } yield assertTrue(
          detail.recentLoginAttempts.exists(_.outcome == LoginOutcome.success),
          global.exists(_.email == "recorded@example.com"),
        )
      },
      test("an attempt against an address with no account is recorded without a user") {
        for {
          _        <- AuthService.login("ghost@example.com", "whatever").either
          attempts <- AdminService.loginAttempts(50, Some(LoginOutcome.unknownEmail))
        } yield assertTrue(attempts.exists(attempt => attempt.email == "ghost@example.com" && attempt.userId.isEmpty))
      },
      test("the detail view carries no credential of any kind") {
        for {
          user   <- AdminService.createUser(AdminActor.system, "secrets@example.com", "password123", isAdmin = true)
          _      <- AuthService.login("secrets@example.com", "password123")
          detail <- AdminService.userDetail(user.id)
        } yield {
          val rendered = detail.toJson
          // The session id is the bearer token and the hash is the credential; neither has any representation in the
          // DTO at all, which is what this pins — `AdminSessionInfo` has no id field to accidentally fill in.
          assertTrue(
            detail.activeSessions == 1,
            detail.hasPassword,
            !rendered.contains("$2a$"),
            !rendered.contains("passwordHash"),
            !rendered.contains("sessionId"),
          )
        }
      },
      test("every mutating action leaves exactly one audit entry naming the administrator") {
        for {
          admin  <- AdminService.createUser(AdminActor.system, "auditor@example.com", "password123", isAdmin = true)
          target <- AdminService.createUser(AdminActor(admin.id), "audited@example.com", "password123", isAdmin = false)
          _      <- AdminService.revokeSessions(AdminActor(admin.id, Some("10.0.0.9")), target.id)
          _      <- AdminService.clearLockout(AdminActor(admin.id), target.id)
          _      <- AdminService.deleteUser(AdminActor(admin.id), target.id)
          byUser <-
            AdminService.auditLog(Paging.firstPage, 50, None, false, None, Some(admin.id), Some(target.id.toString))
        } yield assertTrue(
          byUser.items.map(_.action).toSet ==
            Set(
              AuditAction.userCreate,
              AuditAction.userSessionsRevoked,
              AuditAction.userLockoutCleared,
              AuditAction.userDelete,
            ),
          // The total counts what the same filter matches, not what one page holds — here they coincide.
          byUser.total == byUser.items.size.toLong,
          byUser.items.forall(_.actorEmail.contains("auditor@example.com")),
          byUser.items.exists(_.ip.contains("10.0.0.9")),
        )
      },
      test("an audit entry outlives the account it names") {
        for {
          admin  <- AdminService.createUser(AdminActor.system, "vanishing@example.com", "password123", isAdmin = true)
          victim <- AdminService.createUser(AdminActor(admin.id), "gone@example.com", "password123", isAdmin = false)
          _      <- AdminService.deleteUser(AdminActor(admin.id), victim.id)
          after  <- AdminService.auditLog(
                      Paging.firstPage,
                      50,
                      None,
                      false,
                      Some(AuditAction.userDelete),
                      None,
                      Some(victim.id.toString),
                    )
        } yield assertTrue(after.items.exists(_.actorEmail.contains("vanishing@example.com")))
      },
    ),
    test("deleting a user removes them from the list") {
      for {
        admin  <- AdminService.createUser(AdminActor.system, "admin4@example.com", "password123", isAdmin = true)
        victim <- AdminService.createUser(AdminActor.system, "victim@example.com", "password123", isAdmin = false)
        _      <- AdminService.deleteUser(AdminActor(admin.id), victim.id)
        listed <- AdminService.listUsers(page = Paging.firstPage, pageSize = 100, None, None, descending = false)
      } yield assertTrue(!listed.items.exists(_.id == victim.id))
    },
  ).provide(layer)
}
