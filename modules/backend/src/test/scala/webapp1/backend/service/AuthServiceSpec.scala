package webapp1.backend.service

import webapp1.backend.TestDataSource
import webapp1.backend.db.{
  OAuthIdentityRepository,
  SessionRepository,
  SqliteOAuthIdentityRepository,
  SqliteSessionRepository,
  SqliteUserRepository,
  UserRepository,
}
import webapp1.backend.security.PasswordHasher
import webapp1.shared.domain.OAuthProvider
import zio._
import zio.test._

/** Proves the signup -> login -> session -> currentUser -> logout round trip against SQLite (the dual-dialect DB
  * strategy's test-side dialect), confirming the whole M1 wiring — Quill contexts, Flyway migrations, hashing, sessions
  * — actually works before M2/M3 build on top of it.
  */
object AuthServiceSpec extends ZIOSpecDefault {

  private val repoLayers: ZLayer[Any, Throwable, UserRepository & SessionRepository & OAuthIdentityRepository] = {
    TestDataSource.sqlite >>>
      (SqliteUserRepository.live ++ SqliteSessionRepository.live ++ SqliteOAuthIdentityRepository.live)
  }

  private val authServiceLayer: ZLayer[Any, Throwable, AuthService] =
    (repoLayers ++ PasswordHasher.live ++ InMemoryRateLimiter.live) >>> AuthServiceLive.live

  def spec = suite("AuthService (SQLite)")(
    test("signup, currentUser via session, login, logout invalidates the session") {
      for {
        authService <- ZIO.service[AuthService]
        signupResult <- authService.signup("user@example.com", "password123")
        meAfterSignup <- authService.currentUser(signupResult._2)
        loginResult <- authService.login("user@example.com", "password123")
        _ <- authService.logout(loginResult._2)
        meAfterLogout <- authService.currentUser(loginResult._2)
      } yield assertTrue(
        signupResult._1.email == "user@example.com",
        !signupResult._1.isAdmin,
        meAfterSignup.contains(signupResult._1),
        loginResult._1.id == signupResult._1.id,
        meAfterLogout.isEmpty,
      )
    },
    test("signup rejects a duplicate email") {
      for {
        authService <- ZIO.service[AuthService]
        _ <- authService.signup("dup@example.com", "password123")
        result <- authService.signup("dup@example.com", "password123").either
      } yield assertTrue(result == Left(AuthFailure.EmailAlreadyRegistered))
    },
    test("login rejects a wrong password") {
      for {
        authService <- ZIO.service[AuthService]
        _ <- authService.signup("wrongpw@example.com", "password123")
        result <- authService.login("wrongpw@example.com", "nope12345").either
      } yield assertTrue(result == Left(AuthFailure.InvalidCredentials))
    },
    test("signup rejects a short password") {
      for {
        authService <- ZIO.service[AuthService]
        result <- authService.signup("short@example.com", "short1").either
      } yield assertTrue(
        result == Left(AuthFailure.ValidationError(Map("password" -> "Password must be at least 8 characters")))
      )
    },
    test("repeated wrong passwords trip the rate limiter") {
      for {
        authService <- ZIO.service[AuthService]
        _ <- authService.signup("locked@example.com", "password123")
        _ <-
          ZIO.foreachDiscard(1 to InMemoryRateLimiter.maxAttempts) { _ =>
            authService.login("locked@example.com", "nope12345").either
          }
        result <- authService.login("locked@example.com", "password123").either
      } yield assertTrue(result == Left(AuthFailure.RateLimited))
    },
    test("a successful login forgets earlier failures") {
      val almostLockedOut = InMemoryRateLimiter.maxAttempts - 1
      for {
        authService <- ZIO.service[AuthService]
        _ <- authService.signup("forgiven@example.com", "password123")
        _ <-
          ZIO.foreachDiscard(1 to almostLockedOut) { _ =>
            authService.login("forgiven@example.com", "nope12345").either
          }
        _ <- authService.login("forgiven@example.com", "password123")
        // Without the reset these would land on top of the earlier failures and trip the limiter.
        _ <-
          ZIO.foreachDiscard(1 to almostLockedOut) { _ =>
            authService.login("forgiven@example.com", "nope12345").either
          }
        result <- authService.login("forgiven@example.com", "nope12345").either
      } yield assertTrue(result == Left(AuthFailure.InvalidCredentials))
    },
    test("a social sign-in with an unknown subject and a free email creates an account") {
      for {
        authService <- ZIO.service[AuthService]
        result <- authService.loginWithOAuth(identity(OAuthProvider.Google, "g-1", "fresh@example.com"))
        identities <- authService.listIdentities(result._1.id)
      } yield {
        assertTrue(result._1.email == "fresh@example.com", identities.map(_.provider) == List(OAuthProvider.Google))
      }
    },
    test("the same subject signing in again lands in the same account rather than a second one") {
      for {
        authService <- ZIO.service[AuthService]
        first <- authService.loginWithOAuth(identity(OAuthProvider.Google, "g-2", "repeat@example.com"))
        // A provider is free to report a different address later (a work account renamed, a Microsoft
        // `preferred_username` change). The subject is the identity, so this must not fork the account.
        second <- authService.loginWithOAuth(identity(OAuthProvider.Google, "g-2", "renamed@example.com"))
      } yield assertTrue(first._1.id == second._1.id, second._1.email == "repeat@example.com")
    },
    // The never-auto-link rule. Without this an attacker at any provider that does not verify email
    // addresses could sign in as the owner of an existing account by claiming their address.
    test("a social sign-in whose email belongs to an existing account is refused, not auto-linked") {
      for {
        authService <- ZIO.service[AuthService]
        _ <- authService.signup("taken@example.com", "password123")
        result <- authService.loginWithOAuth(identity(OAuthProvider.Google, "g-3", "taken@example.com")).either
      } yield assertTrue(result == Left(AuthFailure.OAuthAccountExists(OAuthProvider.Google)))
    },
    test("linking a provider from settings is what joins the two, and the linked subject then signs in") {
      for {
        authService <- ZIO.service[AuthService]
        signedUp <- authService.signup("linker@example.com", "password123")
        user = signedUp._1
        _ <- authService.linkOAuth(user.id, identity(OAuthProvider.Microsoft, "m-1", "linker@example.com"))
        loggedIn <- authService.loginWithOAuth(identity(OAuthProvider.Microsoft, "m-1", "linker@example.com"))
        identities <- authService.listIdentities(user.id)
      } yield {
        assertTrue(loggedIn._1.id == user.id, identities.map(_.provider) == List(OAuthProvider.Microsoft))
      }
    },
    test("a provider already linked to this account cannot be linked twice") {
      for {
        authService <- ZIO.service[AuthService]
        signedUp <- authService.signup("twice@example.com", "password123")
        user = signedUp._1
        _ <- authService.linkOAuth(user.id, identity(OAuthProvider.Google, "g-4", "twice@example.com"))
        result <- authService.linkOAuth(user.id, identity(OAuthProvider.Google, "g-5", "twice@example.com")).either
      } yield assertTrue(result == Left(AuthFailure.OAuthAlreadyLinked))
    },
    test("unlinking leaves a password-holding account reachable, so it is allowed") {
      for {
        authService <- ZIO.service[AuthService]
        signedUp <- authService.signup("unlink-ok@example.com", "password123")
        user = signedUp._1
        _ <- authService.linkOAuth(user.id, identity(OAuthProvider.Google, "g-6", "unlink-ok@example.com"))
        _ <- authService.unlinkOAuth(user.id, OAuthProvider.Google)
        identities <- authService.listIdentities(user.id)
      } yield assertTrue(identities.isEmpty)
    },
    // The lockout guard. A social-only account has no password to fall back on, so removing its one
    // identity would leave nothing that can ever sign in to it again.
    test("unlinking the only credential of a social-only account is refused") {
      for {
        authService <- ZIO.service[AuthService]
        created <- authService.loginWithOAuth(identity(OAuthProvider.Google, "g-7", "social-only@example.com"))
        result <- authService.unlinkOAuth(created._1.id, OAuthProvider.Google).either
        identities <- authService.listIdentities(created._1.id)
      } yield assertTrue(result == Left(AuthFailure.LastCredential), identities.size == 1)
    },
    test("setting a password on a social-only account releases the lockout guard") {
      for {
        authService <- ZIO.service[AuthService]
        created <- authService.loginWithOAuth(identity(OAuthProvider.Google, "g-8", "gets-password@example.com"))
        user = created._1
        // No current password to supply: there is none to prove.
        _ <- authService.setPassword(user.id, None, "password123")
        _ <- authService.unlinkOAuth(user.id, OAuthProvider.Google)
        loggedIn <- authService.login("gets-password@example.com", "password123")
        identities <- authService.listIdentities(user.id)
      } yield assertTrue(loggedIn._1.id == user.id, identities.isEmpty)
    },
    test("changing an existing password requires the current one") {
      for {
        authService <- ZIO.service[AuthService]
        signedUp <- authService.signup("changer@example.com", "password123")
        user = signedUp._1
        missing <- authService.setPassword(user.id, None, "newpassword123").either
        wrong <- authService.setPassword(user.id, Some("wrongpass123"), "newpassword123").either
        _ <- authService.setPassword(user.id, Some("password123"), "newpassword123")
        loggedIn <- authService.login("changer@example.com", "newpassword123").either
      } yield {
        assertTrue(
          missing == Left(AuthFailure.ValidationError(Map("currentPassword" -> "Enter your current password"))),
          wrong == Left(AuthFailure.ValidationError(Map("currentPassword" -> "Incorrect password"))),
          loggedIn.isRight,
        )
      }
    },
  ).provide(authServiceLayer)

  private def identity(provider: OAuthProvider, subject: String, email: String): OAuthIdentity = {
    OAuthIdentity(provider, subject, email, emailVerified = true)
  }
}
