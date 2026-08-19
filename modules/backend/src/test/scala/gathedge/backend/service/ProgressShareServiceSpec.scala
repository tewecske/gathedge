package gathedge.backend.service

import gathedge.backend.{RecordingEmailSender, TestAuthLayers, TestCaptchaService, TestDataSource}
import gathedge.backend.db.{
  AuditLogRepository,
  EmailVerificationTokenRepository,
  GuestClaimCodeRepository,
  LoginAttemptRepository,
  OAuthIdentityRepository,
  PasswordResetTokenRepository,
  ProgressShareRepository,
  SessionRepository,
  UserRepository,
}
import gathedge.backend.i18n.Messages
import gathedge.backend.security.PasswordHasher
import zio._
import zio.test._

/** Progress sharing: minting/redeeming a share code, and the authorization check
  * (`ProgressShareService.requireShareAccess`) a viewer's read has to pass. Never a role like "parent" or "teacher" —
  * any two accounts, on the sharer's own say-so.
  */
object ProgressShareServiceSpec extends ZIOSpecDefault {

  private val repoLayers = {
    TestDataSource.sqlite >>> (
      UserRepository.test ++ SessionRepository.test ++ OAuthIdentityRepository.test ++
        EmailVerificationTokenRepository.test ++ PasswordResetTokenRepository.test ++ LoginAttemptRepository.test ++
        GuestClaimCodeRepository.test ++ AuditLogRepository.test ++ ProgressShareRepository.test
    )
  }

  private val layer = {
    (
      repoLayers ++ PasswordHasher.live ++ RateLimiter.live ++ RecordingEmailSender.live ++ Messages.live ++
        TestCaptchaService.live ++ TestAuthLayers.configWith(requireEmailVerification = false)
    ) >+> (AuthService.live ++ ProgressShareService.live)
  }

  private def userId(email: String): ZIO[AuthService, Nothing, Long] = {
    AuthService.signup(email, "password123").orDieWith(failure => new RuntimeException(failure.toString)).map(_._1.id)
  }

  def spec = {
    suite("Progress sharing (SQLite)")(
      test("asking for the share code again answers the same one, not a fresh one") {
        for {
          sharer <- userId("sharer1@example.com")
          first  <- ProgressShareService.issueCode(sharer)
          second <- ProgressShareService.issueCode(sharer)
        } yield assertTrue(first == second)
      },
      test("redeeming grants access, and the same code works for a second viewer") {
        for {
          sharer  <- userId("sharer2@example.com")
          viewerA <- userId("viewerA2@example.com")
          viewerB <- userId("viewerB2@example.com")
          code    <- ProgressShareService.issueCode(sharer)
          _       <- ProgressShareService.redeem(viewerA, code)
          _       <- ProgressShareService.redeem(viewerB, code)
          aAccess <- ProgressShareService.requireShareAccess(viewerA, sharer).either
          bAccess <- ProgressShareService.requireShareAccess(viewerB, sharer).either
          viewers <- ProgressShareService.viewersOf(sharer)
          sharers <- ProgressShareService.sharersOf(viewerA)
        } yield assertTrue(
          aAccess.isRight,
          bAccess.isRight,
          viewers.map(_.userId).toSet == Set(viewerA, viewerB),
          sharers.map(_.sharerUserId) == List(sharer),
        )
      },
      test("redeeming your own code is refused") {
        for {
          sharer <- userId("sharer3@example.com")
          code   <- ProgressShareService.issueCode(sharer)
          result <- ProgressShareService.redeem(sharer, code).either
        } yield assertTrue(result == Left(ProgressShareFailure.CannotShareWithSelf))
      },
      test("redeeming the same code twice for the same viewer is refused") {
        for {
          sharer <- userId("sharer4@example.com")
          viewer <- userId("viewer4@example.com")
          code   <- ProgressShareService.issueCode(sharer)
          _      <- ProgressShareService.redeem(viewer, code)
          again  <- ProgressShareService.redeem(viewer, code).either
        } yield assertTrue(again == Left(ProgressShareFailure.AlreadyShared))
      },
      test("an unknown code is invalid") {
        for {
          viewer <- userId("viewer5@example.com")
          result <- ProgressShareService.redeem(viewer, "ZZZZ-ZZZZ-ZZZZ-ZZZZ").either
        } yield assertTrue(result == Left(ProgressShareFailure.CodeInvalid))
      },
      test("a viewer with no share is denied, and revoking removes an existing one") {
        for {
          sharer   <- userId("sharer6@example.com")
          viewer   <- userId("viewer6@example.com")
          stranger <- userId("stranger6@example.com")
          code     <- ProgressShareService.issueCode(sharer)
          _        <- ProgressShareService.redeem(viewer, code)
          before   <- ProgressShareService.requireShareAccess(stranger, sharer).either
          granted  <- ProgressShareService.requireShareAccess(viewer, sharer).either
          _        <- ProgressShareService.revoke(sharer, viewer)
          after    <- ProgressShareService.requireShareAccess(viewer, sharer).either
        } yield assertTrue(
          before == Left(ProgressShareFailure.NotShared),
          granted.isRight,
          after == Left(ProgressShareFailure.NotShared),
        )
      },
    ).provide(layer) @@ TestAspect.timeout(120.seconds) @@ TestAspect.sequential
  }
}
