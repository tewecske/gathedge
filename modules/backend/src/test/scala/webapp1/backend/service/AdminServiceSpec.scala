package webapp1.backend.service

import webapp1.backend.{TestAuthLayers, TestDataSource}
import webapp1.backend.db.{
  EmailVerificationTokenRepository,
  OAuthIdentityRepository,
  SessionRepository,
  SqliteEmailVerificationTokenRepository,
  SqliteOAuthIdentityRepository,
  SqliteSessionRepository,
  SqliteUserRepository,
  UserRepository,
}
import webapp1.backend.security.PasswordHasher
import zio._
import zio.test._

object AdminServiceSpec extends ZIOSpecDefault {

  private val repoLayers: ZLayer[
    Any,
    Throwable,
    UserRepository & SessionRepository & OAuthIdentityRepository & EmailVerificationTokenRepository,
  ] = {
    TestDataSource.sqlite >>> (
      SqliteUserRepository.test ++ SqliteSessionRepository.test ++ SqliteOAuthIdentityRepository.test ++
        SqliteEmailVerificationTokenRepository.test
    )
  }

  // AuthService shares the same DataSource so a session issued by a login can be checked against
  // the effect an admin password reset has on it.
  private val layer: ZLayer[Any, Throwable, AdminService & AuthService] = {
    (repoLayers ++ PasswordHasher.live ++ RateLimiter.live ++ TestAuthLayers.emailAndConfig) >>>
      (AdminService.live ++ AuthService.live)
  }

  def spec = suite("AdminService (SQLite)")(
    test("creates a user and lists it") {
      for {
        service <- ZIO.service[AdminService]
        created <- service.createUser(0L, "new@example.com", "password123", isAdmin = false)
        listed  <- service.listUsers
      } yield assertTrue(created.email == "new@example.com", !created.isAdmin, listed.exists(_.id == created.id))
    },
    test("rejects a duplicate email on create") {
      for {
        service <- ZIO.service[AdminService]
        _       <- service.createUser(0L, "dup@example.com", "password123", isAdmin = false)
        result  <- service.createUser(0L, "dup@example.com", "password456", isAdmin = false).either
      } yield assertTrue(result == Left(AdminFailure.DuplicateEmail))
    },
    test("rejects a weak password on create") {
      for {
        service <- ZIO.service[AdminService]
        result  <- service.createUser(0L, "weak@example.com", "short", isAdmin = false).either
      } yield assertTrue(result.isLeft)
    },
    test("viewing a nonexistent user fails with NotFound") {
      for {
        service <- ZIO.service[AdminService]
        result  <- service.getUser(999999L).either
      } yield assertTrue(result == Left(AdminFailure.NotFound))
    },
    test("an admin cannot remove their own admin privileges") {
      for {
        service <- ZIO.service[AdminService]
        admin   <- service.createUser(0L, "self-admin@example.com", "password123", isAdmin = true)
        result  <- service.updateUser(admin.id, admin.id, admin.email, isAdmin = false, password = None).either
      } yield assertTrue(result == Left(AdminFailure.SelfDemote))
    },
    test("an admin cannot delete their own account") {
      for {
        service <- ZIO.service[AdminService]
        admin   <- service.createUser(0L, "self-delete@example.com", "password123", isAdmin = true)
        result  <- service.deleteUser(admin.id, admin.id).either
      } yield assertTrue(result == Left(AdminFailure.SelfDelete))
    },
    test("editing a user to another user's email is rejected as a duplicate") {
      for {
        service <- ZIO.service[AdminService]
        admin   <- service.createUser(0L, "admin2@example.com", "password123", isAdmin = true)
        other   <- service.createUser(0L, "other@example.com", "password123", isAdmin = false)
        result  <- service.updateUser(admin.id, other.id, "admin2@example.com", isAdmin = false, password = None).either
      } yield assertTrue(result == Left(AdminFailure.DuplicateEmail))
    },
    test("blank password on update keeps the existing password (no-op)") {
      for {
        service <- ZIO.service[AdminService]
        admin   <- service.createUser(0L, "admin3@example.com", "password123", isAdmin = true)
        user    <- service.createUser(0L, "keep-pw@example.com", "originalpw", isAdmin = false)
        updated <- service.updateUser(admin.id, user.id, "keep-pw@example.com", isAdmin = false, password = Some(""))
      } yield assertTrue(updated.email == "keep-pw@example.com")
    },
    test("resetting a user's password revokes their existing sessions") {
      for {
        adminService <- ZIO.service[AdminService]
        authService  <- ZIO.service[AuthService]
        admin        <- adminService.createUser(0L, "admin5@example.com", "password123", isAdmin = true)
        user         <- adminService.createUser(0L, "reset-pw@example.com", "originalpw", isAdmin = false)
        loginResult  <- authService.login("reset-pw@example.com", "originalpw")
        sessionId     = loginResult._2
        before       <- authService.currentUser(sessionId)
        _            <- adminService.updateUser(
                          admin.id,
                          user.id,
                          "reset-pw@example.com",
                          isAdmin = false,
                          password = Some("replacedpw"),
                        )
        after        <- authService.currentUser(sessionId)
      } yield assertTrue(before.isDefined, after.isEmpty)
    },
    test("deleting a user removes them from the list") {
      for {
        service <- ZIO.service[AdminService]
        admin   <- service.createUser(0L, "admin4@example.com", "password123", isAdmin = true)
        victim  <- service.createUser(0L, "victim@example.com", "password123", isAdmin = false)
        _       <- service.deleteUser(admin.id, victim.id)
        listed  <- service.listUsers
      } yield assertTrue(!listed.exists(_.id == victim.id))
    },
  ).provide(layer)
}
