package gathedge.backend.service

import gathedge.backend.{RecordingEmailSender, SentEmails, TestAuthLayers, TestDataSource}
import gathedge.backend.db.{
  AuditLogRepository,
  EmailVerificationTokenRepository,
  GuestClaimCodeRepository,
  LoginAttemptRepository,
  OAuthIdentityRepository,
  SessionRepository,
  UserRepository,
}
import gathedge.backend.i18n.Messages
import gathedge.backend.security.PasswordHasher
import gathedge.shared.domain.OAuthProvider
import gathedge.shared.i18n.{MessageKeys, MessageRef}
import gathedge.shared.validation.Validation
import zio._
import zio.test._

/** Proves the signup -> login -> session -> currentUser -> logout round trip against SQLite (the dual-dialect DB
  * strategy's test-side dialect), confirming the whole M1 wiring — Quill contexts, Flyway migrations, hashing, sessions
  * — actually works before M2/M3 build on top of it.
  */
object AuthServiceSpec extends ZIOSpecDefault {

  private val repoLayers = {
    TestDataSource.sqlite >>> (
      UserRepository.test ++ SessionRepository.test ++ OAuthIdentityRepository.test ++
        EmailVerificationTokenRepository.test ++ LoginAttemptRepository.test ++ GuestClaimCodeRepository.test ++ AuditLogRepository.test
    )
  }

  /** Every test but the verification suite runs on the shipped config, where the login gate is off. The recording
    * mailer is what makes the emailed link readable — there is no other way to get at a token, by design.
    */
  private def authServiceLayer(requireEmailVerification: Boolean): ZLayer[Any, Throwable, AuthService & SentEmails] = {
    val support = {
      PasswordHasher.live ++ RateLimiter.live ++ RecordingEmailSender.live ++ Messages.live ++
        TestAuthLayers.configWith(requireEmailVerification)
    }
    val built   = repoLayers ++ support
    built >>> (AuthService.live ++ ZLayer.service[SentEmails])
  }

  def spec = suite("AuthService (SQLite)")(coreSuite, verificationSuite)

  private val coreSuite = suite("core")(
    test("signup, currentUser via session, login, logout invalidates the session") {
      for {
        signupResult  <- AuthService.signup("user@example.com", "password123")
        meAfterSignup <- AuthService.currentUser(signupResult._2.get)
        loginResult   <- AuthService.login("user@example.com", "password123")
        _             <- AuthService.logout(loginResult._2)
        meAfterLogout <- AuthService.currentUser(loginResult._2)
      } yield assertTrue(
        signupResult._1.email.contains("user@example.com"),
        !signupResult._1.isAdmin,
        meAfterSignup.contains(signupResult._1),
        loginResult._1.id == signupResult._1.id,
        meAfterLogout.isEmpty,
      )
    },
    test("signup rejects a duplicate email") {
      for {
        _      <- AuthService.signup("dup@example.com", "password123")
        result <- AuthService.signup("dup@example.com", "password123").either
      } yield assertTrue(result == Left(AuthFailure.EmailAlreadyRegistered))
    },
    test("login rejects a wrong password") {
      for {
        _      <- AuthService.signup("wrongpw@example.com", "password123")
        result <- AuthService.login("wrongpw@example.com", "nope12345").either
      } yield assertTrue(result == Left(AuthFailure.InvalidCredentials))
    },
    test("signup rejects a short password") {
      for {
        result <- AuthService.signup("short@example.com", "short1").either
      } yield assertTrue(
        result ==
          Left(
            AuthFailure.ValidationError(
              Map("password" -> MessageRef(MessageKeys.passwordTooShort, List(Validation.minPasswordLength.toString)))
            )
          )
      )
    },
    test("repeated wrong passwords trip the rate limiter") {
      for {
        _      <- AuthService.signup("locked@example.com", "password123")
        _      <-
          ZIO.foreachDiscard(1 to InMemoryRateLimiter.maxAttempts) { _ =>
            AuthService.login("locked@example.com", "nope12345").either
          }
        result <- AuthService.login("locked@example.com", "password123").either
      } yield assertTrue(result == Left(AuthFailure.RateLimited))
    },
    // The origin dimension is deliberately coarse — one attacker spraying many accounts from one address should be
    // throttled — which is exactly why resolving the origin correctly matters so much. Behind the shipped nginx every
    // request carried the proxy's address, so this rule locked out the entire deployment; `RouteSupportSpec` covers
    // the resolution, and these two pin what the limiter does once it has an answer.
    test("failures from one origin do not lock out an unrelated account signing in from another") {
      for {
        _      <- AuthService.signup("origin-a@example.com", "password123")
        _      <- AuthService.signup("origin-b@example.com", "password123")
        _      <-
          ZIO.foreachDiscard(1 to InMemoryRateLimiter.maxAttempts) { _ =>
            AuthService.login("origin-a@example.com", "nope12345", Some("198.51.100.4")).either
          }
        result <- AuthService.login("origin-b@example.com", "password123", Some("203.0.113.9")).either
      } yield assertTrue(result.isRight)
    },
    test("failures from one origin do lock out another account attempted from that same origin") {
      for {
        _      <- AuthService.signup("shared-a@example.com", "password123")
        _      <- AuthService.signup("shared-b@example.com", "password123")
        _      <-
          ZIO.foreachDiscard(1 to InMemoryRateLimiter.maxAttempts) { _ =>
            AuthService.login("shared-a@example.com", "nope12345", Some("192.0.2.77")).either
          }
        result <- AuthService.login("shared-b@example.com", "password123", Some("192.0.2.77")).either
      } yield assertTrue(result == Left(AuthFailure.RateLimited))
    },
    // Signup counts a duplicate address as a failure, and the address is the caller's to choose. While both shared
    // the `email:` namespace, five signup attempts against a known address locked its owner out of signing in —
    // an unauthenticated account-lockout that cost the attacker nothing but the address.
    test("repeated signups with a taken address do not lock its owner out of signing in") {
      for {
        _      <- AuthService.signup("targeted@example.com", "password123")
        _      <-
          ZIO.foreachDiscard(1 to InMemoryRateLimiter.maxAttempts + 1) { _ =>
            AuthService.signup("targeted@example.com", "password123").either
          }
        result <- AuthService.login("targeted@example.com", "password123").either
      } yield assertTrue(result.isRight)
    },
    // The other direction: signup keeps a budget, it is just its own.
    test("repeated signups with a taken address still trip signup's own limiter") {
      for {
        _      <- AuthService.signup("probed@example.com", "password123")
        _      <-
          ZIO.foreachDiscard(1 to InMemoryRateLimiter.maxAttempts) { _ =>
            AuthService.signup("probed@example.com", "password123").either
          }
        result <- AuthService.signup("probed@example.com", "password123").either
      } yield assertTrue(result == Left(AuthFailure.RateLimited))
    },
    test("failed logins do not spend the signup budget for the same address either") {
      for {
        _      <- AuthService.signup("crossways@example.com", "password123")
        _      <-
          ZIO.foreachDiscard(1 to InMemoryRateLimiter.maxAttempts) { _ =>
            AuthService.login("crossways@example.com", "nope12345").either
          }
        // Locked out of signing in, but a *different* address may still be registered from here.
        locked <- AuthService.login("crossways@example.com", "password123").either
        result <- AuthService.signup("unrelated@example.com", "password123").either
      } yield assertTrue(locked == Left(AuthFailure.RateLimited), result.isRight)
    },
    test("a successful login forgets earlier failures") {
      val almostLockedOut = InMemoryRateLimiter.maxAttempts - 1
      for {
        _      <- AuthService.signup("forgiven@example.com", "password123")
        _      <-
          ZIO.foreachDiscard(1 to almostLockedOut) { _ =>
            AuthService.login("forgiven@example.com", "nope12345").either
          }
        _      <- AuthService.login("forgiven@example.com", "password123")
        // Without the reset these would land on top of the earlier failures and trip the limiter.
        _      <-
          ZIO.foreachDiscard(1 to almostLockedOut) { _ =>
            AuthService.login("forgiven@example.com", "nope12345").either
          }
        result <- AuthService.login("forgiven@example.com", "nope12345").either
      } yield assertTrue(result == Left(AuthFailure.InvalidCredentials))
    },
    test("a social sign-in with an unknown subject and a free email creates an account") {
      for {
        result     <- AuthService.loginWithOAuth(identity(OAuthProvider.Google, "g-1", "fresh@example.com"))
        identities <- AuthService.listIdentities(result._1.id)
      } yield {
        assertTrue(
          result._1.email.contains("fresh@example.com"),
          identities.map(_.provider) == List(OAuthProvider.Google),
        )
      }
    },
    test("the same subject signing in again lands in the same account rather than a second one") {
      for {
        first  <- AuthService.loginWithOAuth(identity(OAuthProvider.Google, "g-2", "repeat@example.com"))
        // A provider is free to report a different address later (a work account renamed, a Microsoft
        // `preferred_username` change). The subject is the identity, so this must not fork the account.
        second <- AuthService.loginWithOAuth(identity(OAuthProvider.Google, "g-2", "renamed@example.com"))
      } yield assertTrue(first._1.id == second._1.id, second._1.email.contains("repeat@example.com"))
    },
    // The never-auto-link rule. Without this an attacker at any provider that does not verify email
    // addresses could sign in as the owner of an existing account by claiming their address.
    test("a social sign-in whose email belongs to an existing account is refused, not auto-linked") {
      for {
        _      <- AuthService.signup("taken@example.com", "password123")
        result <- AuthService.loginWithOAuth(identity(OAuthProvider.Google, "g-3", "taken@example.com")).either
      } yield assertTrue(result == Left(AuthFailure.OAuthAccountExists(OAuthProvider.Google)))
    },
    test("linking a provider from settings is what joins the two, and the linked subject then signs in") {
      for {
        signedUp   <- AuthService.signup("linker@example.com", "password123")
        user        = signedUp._1
        _          <- AuthService.linkOAuth(user.id, identity(OAuthProvider.Microsoft, "m-1", "linker@example.com"))
        loggedIn   <- AuthService.loginWithOAuth(identity(OAuthProvider.Microsoft, "m-1", "linker@example.com"))
        identities <- AuthService.listIdentities(user.id)
      } yield {
        assertTrue(loggedIn._1.id == user.id, identities.map(_.provider) == List(OAuthProvider.Microsoft))
      }
    },
    test("a provider already linked to this account cannot be linked twice") {
      for {
        signedUp <- AuthService.signup("twice@example.com", "password123")
        user      = signedUp._1
        _        <- AuthService.linkOAuth(user.id, identity(OAuthProvider.Google, "g-4", "twice@example.com"))
        result   <- AuthService.linkOAuth(user.id, identity(OAuthProvider.Google, "g-5", "twice@example.com")).either
      } yield assertTrue(result == Left(AuthFailure.OAuthAlreadyLinked))
    },
    test("unlinking leaves a password-holding account reachable, so it is allowed") {
      for {
        signedUp   <- AuthService.signup("unlink-ok@example.com", "password123")
        user        = signedUp._1
        _          <- AuthService.linkOAuth(user.id, identity(OAuthProvider.Google, "g-6", "unlink-ok@example.com"))
        _          <- AuthService.unlinkOAuth(user.id, OAuthProvider.Google)
        identities <- AuthService.listIdentities(user.id)
      } yield assertTrue(identities.isEmpty)
    },
    // The lockout guard. A social-only account has no password to fall back on, so removing its one
    // identity would leave nothing that can ever sign in to it again.
    test("unlinking the only credential of a social-only account is refused") {
      for {
        created    <- AuthService.loginWithOAuth(identity(OAuthProvider.Google, "g-7", "social-only@example.com"))
        result     <- AuthService.unlinkOAuth(created._1.id, OAuthProvider.Google).either
        identities <- AuthService.listIdentities(created._1.id)
      } yield assertTrue(result == Left(AuthFailure.LastCredential), identities.size == 1)
    },
    test("setting a password on a social-only account releases the lockout guard") {
      for {
        created    <- AuthService.loginWithOAuth(identity(OAuthProvider.Google, "g-8", "gets-password@example.com"))
        user        = created._1
        // No current password to supply: there is none to prove.
        _          <- AuthService.setPassword(user.id, None, "password123")
        _          <- AuthService.unlinkOAuth(user.id, OAuthProvider.Google)
        loggedIn   <- AuthService.login("gets-password@example.com", "password123")
        identities <- AuthService.listIdentities(user.id)
      } yield assertTrue(loggedIn._1.id == user.id, identities.isEmpty)
    },
    test("changing an existing password requires the current one") {
      for {
        signedUp <- AuthService.signup("changer@example.com", "password123")
        user      = signedUp._1
        missing  <- AuthService.setPassword(user.id, None, "newpassword123").either
        wrong    <- AuthService.setPassword(user.id, Some("wrongpass123"), "newpassword123").either
        _        <- AuthService.setPassword(user.id, Some("password123"), "newpassword123")
        loggedIn <- AuthService.login("changer@example.com", "newpassword123").either
      } yield {
        assertTrue(
          missing ==
            Left(
              AuthFailure.ValidationError(Map("currentPassword" -> MessageRef(MessageKeys.currentPasswordRequired)))
            ),
          wrong ==
            Left(
              AuthFailure.ValidationError(Map("currentPassword" -> MessageRef(MessageKeys.currentPasswordIncorrect)))
            ),
          loggedIn.isRight,
        )
      }
    },
  ).provide(authServiceLayer(requireEmailVerification = false))

  /** The gate itself. Everything here runs with `require-email-verification` on except the two tests that pin what
    * changes when it is off — which is the whole point of the flag: the tokens are issued either way.
    */
  private val verificationSuite = {
    suite("email verification")(
      suite("with the login gate on")(
        test("signup issues no session, and the emailed link is what opens one") {
          for {
            signedUp <- AuthService.signup("verify-me@example.com", "password123")
            blocked  <- AuthService.login("verify-me@example.com", "password123").either
            token    <- SentEmails.lastVerificationToken
            sent     <- SentEmails.all
            _        <- AuthService.verifyEmail(token.get)
            loggedIn <- AuthService.login("verify-me@example.com", "password123")
          } yield assertTrue(
            signedUp._2.isEmpty,
            !signedUp._1.emailVerified,
            blocked == Left(AuthFailure.EmailNotVerified),
            sent.map(_.to) == Vector("verify-me@example.com"),
            loggedIn._1.emailVerified,
          )
        },
        test("a token is single-use") {
          for {
            _     <- AuthService.signup("once@example.com", "password123")
            token <- SentEmails.lastVerificationToken
            _     <- AuthService.verifyEmail(token.get)
            again <- AuthService.verifyEmail(token.get).either
          } yield assertTrue(again == Left(AuthFailure.InvalidVerificationToken))
        },
        test("an unknown token is refused the same way a spent one is") {
          for {
            result <- AuthService.verifyEmail("not-a-real-token").either
          } yield assertTrue(result == Left(AuthFailure.InvalidVerificationToken))
        },
        test("an expired token is refused") {
          for {
            _      <- AuthService.signup("stale@example.com", "password123")
            token  <- SentEmails.lastVerificationToken
            _      <- TestClock.adjust(AuthService.verificationValidity.plus(1.minute))
            result <- AuthService.verifyEmail(token.get).either
          } yield assertTrue(result == Left(AuthFailure.InvalidVerificationToken))
        },
        test("resending replaces the outstanding token, and the wrong password is still refused as such") {
          for {
            _             <- AuthService.signup("resend@example.com", "password123")
            first         <- SentEmails.lastVerificationToken
            _             <- AuthService.resendVerification("resend@example.com")
            second        <- SentEmails.lastVerificationToken
            stale         <- AuthService.verifyEmail(first.get).either
            _             <- AuthService.verifyEmail(second.get)
            // The gate is behind the password check, so an unverified account and a wrong password
            // are indistinguishable to anyone who does not know the password.
            wrongPassword <- AuthService.login("resend@example.com", "nope12345").either
          } yield assertTrue(
            first != second,
            stale == Left(AuthFailure.InvalidVerificationToken),
            wrongPassword == Left(AuthFailure.InvalidCredentials),
          )
        },
        test("resending for an unknown address succeeds silently and sends nothing") {
          for {
            result <- AuthService.resendVerification("nobody@example.com").either
            sent   <- SentEmails.all
          } yield assertTrue(result == Right(()), sent.isEmpty)
        },
        test("a provider that asserts a verified email creates an already-verified account") {
          for {
            created <- AuthService.loginWithOAuth(identity(OAuthProvider.Google, "g-v1", "oauth@example.com"))
          } yield assertTrue(created._1.emailVerified)
        },
        test("a provider that asserts nothing creates an unverified one") {
          for {
            created <- AuthService.loginWithOAuth(
                         OAuthIdentity(OAuthProvider.Microsoft, "m-v1", "unclaimed@example.com", emailVerified = false)
                       )
          } yield assertTrue(!created._1.emailVerified)
        },
        test("linking a provider that vouches for the same address verifies the account") {
          for {
            signedUp <- AuthService.signup("linked-verify@example.com", "password123")
            _        <- AuthService.linkOAuth(
                          signedUp._1.id,
                          identity(OAuthProvider.Google, "g-v2", "linked-verify@example.com"),
                        )
            loggedIn <- AuthService.login("linked-verify@example.com", "password123")
            _        <- SentEmails.all
          } yield assertTrue(loggedIn._1.emailVerified)
        },
      ).provide(authServiceLayer(requireEmailVerification = true)),
      suite("with the login gate off")(
        test("signup still opens a session and still sends a link") {
          for {
            signedUp <- AuthService.signup("lenient@example.com", "password123")
            loggedIn <- AuthService.login("lenient@example.com", "password123")
            token    <- SentEmails.lastVerificationToken
          } yield assertTrue(
            signedUp._2.isDefined,
            !signedUp._1.emailVerified,
            !loggedIn._1.emailVerified,
            token.isDefined,
          )
        },
        test("the link still verifies the account") {
          for {
            _        <- AuthService.signup("lenient-verify@example.com", "password123")
            token    <- SentEmails.lastVerificationToken
            _        <- AuthService.verifyEmail(token.get)
            loggedIn <- AuthService.login("lenient-verify@example.com", "password123")
          } yield assertTrue(loggedIn._1.emailVerified)
        },
      ).provide(authServiceLayer(requireEmailVerification = false)),
    )
  }

  private def identity(provider: OAuthProvider, subject: String, email: String): OAuthIdentity = {
    OAuthIdentity(provider, subject, email, emailVerified = true)
  }
}
