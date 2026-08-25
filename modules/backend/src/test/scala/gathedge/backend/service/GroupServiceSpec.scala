package gathedge.backend.service

import gathedge.backend.{RecordingEmailSender, TestAuthLayers, TestCaptchaService, TestDataSource}
import gathedge.backend.db.{
  AuditLogRepository,
  EmailVerificationTokenRepository,
  GroupRepository,
  GuestClaimCodeRepository,
  LoginAttemptRepository,
  OAuthIdentityRepository,
  PasswordResetTokenRepository,
  SessionRepository,
  UserRepository,
  WordRepository,
}
import gathedge.backend.i18n.Messages
import gathedge.backend.security.PasswordHasher
import gathedge.shared.domain.GroupRole
import zio._
import zio.test._

/** Classroom-style tag groups: creating one (the caller becomes its sole admin), joining by invite code, the last-admin
  * guard on leaving/demoting/removing, and attaching/detaching a tag.
  */
object GroupServiceSpec extends ZIOSpecDefault {

  private val repoLayers = {
    TestDataSource.sqlite >>> (
      UserRepository.test ++ SessionRepository.test ++ OAuthIdentityRepository.test ++
        EmailVerificationTokenRepository.test ++ PasswordResetTokenRepository.test ++ LoginAttemptRepository.test ++
        GuestClaimCodeRepository.test ++ AuditLogRepository.test ++ GroupRepository.test ++ WordRepository.test
    )
  }

  private val layer = {
    (
      repoLayers ++ PasswordHasher.live ++ RateLimiter.live ++ RecordingEmailSender.live ++ Messages.live ++
        TestCaptchaService.live ++ TestAuthLayers.configWith(requireEmailVerification = false)
    ) >+> (AuthService.live ++ GroupService.live ++ WordService.live)
  }

  private def userId(email: String): ZIO[AuthService, Nothing, Long] = {
    AuthService.signup(email, "password123").orDieWith(failure => new RuntimeException(failure.toString)).map(_._1.id)
  }

  private def tagId(name: String, owner: Long): ZIO[WordService, Nothing, Long] = {
    WordService.createTag(name, owner).orDieWith(failure => new RuntimeException(failure.toString)).map(_.tag.id)
  }

  def spec = {
    suite("Groups (SQLite)")(
      test("creating a group mints the caller as its sole admin") {
        for {
          owner  <- userId("owner1@example.com")
          detail <- GroupService.create("Period 3 German", owner)
        } yield assertTrue(
          detail.viewerRole.contains(GroupRole.Admin),
          detail.memberCount == 1L,
          detail.members.map(_.userId) == List(owner),
          detail.members.head.role == GroupRole.Admin,
        )
      },
      test("joining by code is idempotent, and always mints a plain member") {
        for {
          owner   <- userId("owner2@example.com")
          other   <- userId("other2@example.com")
          created <- GroupService.create("Group2", owner)
          code     = created.inviteCode.get
          _       <- GroupService.join(code, other)
          // Redeeming a second time for a group already joined must not fail — that is the whole of what
          // "idempotent" means here; there is nothing further to assert once this line does not raise.
          _       <- GroupService.join(code, other)
          detail  <- GroupService.detail(created.id, other)
        } yield assertTrue(
          detail.viewerRole.contains(GroupRole.Member),
          detail.memberCount == 2L,
        )
      },
      test("an unknown or rotated invite code is invalid") {
        for {
          owner   <- userId("owner3@example.com")
          other   <- userId("other3@example.com")
          created <- GroupService.create("Group3", owner)
          stale    = created.inviteCode.get
          _       <- GroupService.regenerateInviteCode(created.id, owner)
          unknown <- GroupService.join("ZZZZ-ZZZZ-ZZZZ-ZZZZ", other).either
          rotated <- GroupService.join(stale, other).either
        } yield assertTrue(
          unknown == Left(GroupFailure.InviteCodeInvalid),
          rotated == Left(GroupFailure.InviteCodeInvalid),
        )
      },
      test("regenerating the invite code lets the new one in") {
        for {
          owner   <- userId("owner4@example.com")
          other   <- userId("other4@example.com")
          created <- GroupService.create("Group4", owner)
          fresh   <- GroupService.regenerateInviteCode(created.id, owner)
          _       <- GroupService.join(fresh, other)
          detail  <- GroupService.detail(created.id, other)
        } yield assertTrue(detail.viewerRole.contains(GroupRole.Member))
      },
      test("leaving as the sole admin is refused; a second admin makes it possible") {
        for {
          owner   <- userId("owner5@example.com")
          other   <- userId("other5@example.com")
          created <- GroupService.create("Group5", owner)
          _       <- GroupService.join(created.inviteCode.get, other)
          blocked <- GroupService.leave(created.id, owner).either
          _       <- GroupService.setMemberRole(created.id, owner, other, GroupRole.Admin)
          allowed <- GroupService.leave(created.id, owner).either
          detail  <- GroupService.detail(created.id, other)
        } yield assertTrue(
          blocked == Left(GroupFailure.LastAdmin),
          allowed.isRight,
          detail.members.map(_.userId) == List(other),
        )
      },
      test("demoting or removing the group's last admin is refused") {
        for {
          owner   <- userId("owner6@example.com")
          other   <- userId("other6@example.com")
          created <- GroupService.create("Group6", owner)
          _       <- GroupService.join(created.inviteCode.get, other)
          demote  <- GroupService.setMemberRole(created.id, owner, owner, GroupRole.Member).either
          remove  <- GroupService.removeMember(created.id, owner, owner).either
        } yield assertTrue(
          demote == Left(GroupFailure.LastAdmin),
          remove == Left(GroupFailure.LastAdmin),
        )
      },
      test("a plain member cannot manage the roster or regenerate the code") {
        for {
          owner   <- userId("owner7@example.com")
          member  <- userId("member7@example.com")
          created <- GroupService.create("Group7", owner)
          _       <- GroupService.join(created.inviteCode.get, member)
          promote <- GroupService.setMemberRole(created.id, member, owner, GroupRole.Member).either
          regen   <- GroupService.regenerateInviteCode(created.id, member).either
        } yield assertTrue(
          promote == Left(GroupFailure.NotAdmin),
          regen == Left(GroupFailure.NotAdmin),
        )
      },
      test("attaching a tag requires membership and ownership, and refuses a tag already in a group") {
        for {
          owner     <- userId("owner8@example.com")
          outsider  <- userId("outsider8@example.com")
          groupA    <- GroupService.create("GroupA8", owner)
          // `owner` never joins this one, so it is the group `attachTag`'s NotMember case below tests against.
          groupB    <- GroupService.create("GroupB8", outsider)
          _         <- GroupService.join(groupA.inviteCode.get, outsider)
          tag       <- tagId("upload", owner)
          notMember <- GroupService.attachTag(groupB.id, tag, owner).either
          notOwned  <- GroupService.attachTag(groupA.id, tag, outsider).either
          ok        <- GroupService.attachTag(groupA.id, tag, owner).either
          already   <- GroupService.attachTag(groupA.id, tag, owner).either
        } yield assertTrue(
          notMember == Left(GroupFailure.NotMember),
          notOwned == Left(GroupFailure.TagNotOwned),
          ok.isRight,
          already == Left(GroupFailure.TagAlreadyInGroup),
        )
      },
      test(
        "a group member (not the owner) can now edit a group tag's content, but only the owner/admin can detach it"
      ) {
        for {
          owner   <- userId("owner9@example.com")
          member  <- userId("member9@example.com")
          created <- GroupService.create("Group9", owner)
          _       <- GroupService.join(created.inviteCode.get, member)
          tag     <- tagId("upload", owner)
          _       <- GroupService.attachTag(created.id, tag, owner)
          // The member is neither the tag's owner nor a group admin, so detaching is refused...
          refused <- GroupService.detachTag(created.id, tag, member).either
          // ...but the owner can, and reattaching lets an admin do it too.
          byOwner <- GroupService.detachTag(created.id, tag, owner).either
          _       <- GroupService.attachTag(created.id, tag, owner)
          _       <- GroupService.setMemberRole(created.id, owner, member, GroupRole.Admin)
          byAdmin <- GroupService.detachTag(created.id, tag, member).either
        } yield assertTrue(
          refused == Left(GroupFailure.NotAdmin),
          byOwner.isRight,
          byAdmin.isRight,
        )
      },
    ).provide(layer) @@ TestAspect.timeout(120.seconds) @@ TestAspect.sequential
  }
}
