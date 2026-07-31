package webapp1.backend.service

import webapp1.backend.TestDataSource
import webapp1.backend.db.{SessionRepository, SqliteSessionRepository, SqliteUserRepository, UserRepository}
import webapp1.backend.security.PasswordHasher
import zio._
import zio.test._

/** Proves the signup -> login -> session -> currentUser -> logout round trip
  * against SQLite (the dual-dialect DB strategy's test-side dialect), confirming
  * the whole M1 wiring — Quill contexts, Flyway migrations, hashing, sessions —
  * actually works before M2/M3 build on top of it.
  */
object AuthServiceSpec extends ZIOSpecDefault {

  private val repoLayers: ZLayer[Any, Throwable, UserRepository & SessionRepository] =
    TestDataSource.sqlite >>> (SqliteUserRepository.live ++ SqliteSessionRepository.live)

  private val authServiceLayer: ZLayer[Any, Throwable, AuthService] =
    (repoLayers ++ PasswordHasher.live ++ InMemoryRateLimiter.live) >>> AuthServiceLive.live

  def spec = suite("AuthService (SQLite)")(
    test("signup, currentUser via session, login, logout invalidates the session") {
      for {
        authService  <- ZIO.service[AuthService]
        signupResult <- authService.signup("user@example.com", "password123")
        meAfterSignup <- authService.currentUser(signupResult._2)
        loginResult  <- authService.login("user@example.com", "password123")
        _            <- authService.logout(loginResult._2)
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
        _      <- authService.signup("dup@example.com", "password123")
        result <- authService.signup("dup@example.com", "password123").either
      } yield assertTrue(result == Left(AuthFailure.EmailAlreadyRegistered))
    },
    test("login rejects a wrong password") {
      for {
        authService <- ZIO.service[AuthService]
        _      <- authService.signup("wrongpw@example.com", "password123")
        result <- authService.login("wrongpw@example.com", "nope12345").either
      } yield assertTrue(result == Left(AuthFailure.InvalidCredentials))
    },
    test("signup rejects a short password") {
      for {
        authService <- ZIO.service[AuthService]
        result <- authService.signup("short@example.com", "short1").either
      } yield assertTrue(result == Left(AuthFailure.ValidationError(Map("password" -> "Password must be at least 8 characters"))))
    },
    test("Google sign-in is rejected when the email isn't verified") {
      for {
        authService <- ZIO.service[AuthService]
        result <- authService
                    .loginWithGoogle(GoogleIdentity("g-subject-1", "unverified@example.com", emailVerified = false))
                    .either
      } yield assertTrue(result == Left(AuthFailure.GoogleAuthFailed("email not verified")))
    },
    test("Google sign-in with a verified email creates an account") {
      for {
        authService <- ZIO.service[AuthService]
        result <- authService.loginWithGoogle(GoogleIdentity("g-subject-2", "verified@example.com", emailVerified = true))
      } yield assertTrue(result._1.email == "verified@example.com")
    },
  ).provide(authServiceLayer)
}
