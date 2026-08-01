package webapp1.backend.service

import webapp1.backend.TestDataSource
import webapp1.backend.config.AppConfig
import webapp1.backend.db.{
  GroupInvitationRepository,
  GroupInvitationRow,
  SqliteGroupInvitationRepository,
  SqliteGroupMemberRepository,
  SqliteGroupPairRepository,
  SqliteGroupRepository,
  SqliteUserRepository,
  UserRepository,
}
import webapp1.shared.domain.GroupRole
import zio._
import zio.test._

import java.util.concurrent.TimeUnit

object GroupServiceSpec extends ZIOSpecDefault {

  private val repoLayer = {
    TestDataSource.sqlite >>> (
      SqliteGroupRepository.live ++ SqliteGroupMemberRepository.live ++ SqliteGroupPairRepository.live ++
        SqliteGroupInvitationRepository.live ++ SqliteUserRepository.live
    )
  }

  // repoLayer is referenced twice below; ZIO memoizes same-instance layers within
  // one composition, so both branches share a single underlying (SQLite) DB.
  private val layer: ZLayer[Any, Throwable, GroupService & GroupInvitationRepository & UserRepository] =
    repoLayer ++ ((repoLayer ++ EmailSender.live ++ AppConfig.live) >>> GroupServiceLive.live)

  def spec = suite("GroupService (SQLite)")(
    test("creating a group makes the creator an admin member") {
      for {
        service <- ZIO.service[GroupService]
        group <- service.createGroup(1L, "Acme")
      } yield assertTrue(group.name == "Acme", group.myRole == GroupRole.Admin)
    },
    test("rejects a blank group name") {
      for {
        service <- ZIO.service[GroupService]
        result <- service.createGroup(1L, "  ").either
      } yield assertTrue(result.isLeft)
    },
    test("a non-member cannot list or add pairs (private to the owning group)") {
      for {
        service <- ZIO.service[GroupService]
        group <- service.createGroup(1L, "Private Co")
        listResult <- service.listPairs(2L, group.id).either
        addResult <- service.addPair(2L, "intruder@example.com", group.id, "a", "b").either
      } yield assertTrue(listResult == Left(GroupFailure.NotMember), addResult == Left(GroupFailure.NotMember))
    },
    test("member can add a pair; blank source/target is rejected") {
      for {
        service <- ZIO.service[GroupService]
        group <- service.createGroup(1L, "Writable Co")
        pair <- service.addPair(1L, "owner@example.com", group.id, "src", "tgt")
        pairs <- service.listPairs(1L, group.id)
        blankResult <- service.addPair(1L, "owner@example.com", group.id, "", "tgt").either
      } yield assertTrue(
        pair.source == "src",
        pair.target == "tgt",
        pair.createdByEmail == "owner@example.com",
        pairs.map(_.id) == List(pair.id),
        blankResult == Left(GroupFailure.ValidationError(Map("source" -> "Source is required"))),
      )
    },
    test("accessing a nonexistent group fails with NotFound") {
      for {
        service <- ZIO.service[GroupService]
        result <- service.listPairs(1L, 999999L).either
      } yield assertTrue(result == Left(GroupFailure.NotFound))
    },
    test("only a group admin can invite, remove members, change roles, or delete the group") {
      for {
        service <- ZIO.service[GroupService]
        group <- service.createGroup(1L, "Admin-only Co")
        invitationRepo <- ZIO.service[GroupInvitationRepository]
        now <- Clock.currentTime(TimeUnit.MILLISECONDS)
        invite <- invitationRepo.insert(
          GroupInvitationRow(
            0L,
            group.id,
            "member@example.com",
            "read_write",
            "tok-nonadmin",
            1L,
            now,
            now + 100000L,
            None,
          )
        )
        _ <- service.acceptInvitation(2L, "member@example.com", invite.token)
        inviteResult <- service.inviteMember(2L, group.id, "third@example.com", GroupRole.ReadOnly).either
        removeResult <- service.removeMember(2L, group.id, 1L).either
        roleResult <- service.updateMemberRole(2L, group.id, 1L, GroupRole.ReadOnly).either
        deleteResult <- service.deleteGroup(2L, group.id).either
      } yield assertTrue(
        inviteResult == Left(GroupFailure.AdminOnly),
        removeResult == Left(GroupFailure.AdminOnly),
        roleResult == Left(GroupFailure.AdminOnly),
        deleteResult == Left(GroupFailure.AdminOnly),
      )
    },
    test("the sole admin cannot be removed or demoted (last-admin protection)") {
      for {
        service <- ZIO.service[GroupService]
        group <- service.createGroup(1L, "Solo Admin Co")
        removeResult <- service.removeMember(1L, group.id, 1L).either
        demoteResult <- service.updateMemberRole(1L, group.id, 1L, GroupRole.ReadWrite).either
      } yield assertTrue(removeResult == Left(GroupFailure.LastAdmin), demoteResult == Left(GroupFailure.LastAdmin))
    },
    test("promoting a second admin allows the first admin to then leave") {
      // listMembers joins group_members with real user rows, so this test (unlike
      // the others) needs actual users rather than arbitrary literal ids.
      for {
        service <- ZIO.service[GroupService]
        userRepo <- ZIO.service[UserRepository]
        now0 <- Clock.currentTime(TimeUnit.MILLISECONDS)
        admin1 <- userRepo.insert("admin1@twoadmins.example.com", None, isAdmin = false, None, "light", now0)
        admin2 <- userRepo.insert("admin2@twoadmins.example.com", None, isAdmin = false, None, "light", now0)
        group <- service.createGroup(admin1.id, "Two Admins Co")
        invitationRepo <- ZIO.service[GroupInvitationRepository]
        now <- Clock.currentTime(TimeUnit.MILLISECONDS)
        invite <- invitationRepo.insert(
          GroupInvitationRow(
            0L,
            group.id,
            admin2.email,
            "read_write",
            "tok-promote",
            admin1.id,
            now,
            now + 100000L,
            None,
          )
        )
        acceptResult <- service.acceptInvitation(admin2.id, admin2.email, invite.token)
        _ <- service.updateMemberRole(admin1.id, group.id, admin2.id, GroupRole.Admin)
        removeResult <- service.removeMember(admin1.id, group.id, admin1.id)
        members <- service.listMembers(admin2.id, group.id)
      } yield assertTrue(
        acceptResult.myRole == GroupRole.ReadWrite,
        removeResult == (),
        members.map(_.userId) == List(admin2.id),
      )
    },
    test("accepting an invitation with a mismatched email is rejected") {
      for {
        service <- ZIO.service[GroupService]
        group <- service.createGroup(1L, "Mismatch Co")
        invitationRepo <- ZIO.service[GroupInvitationRepository]
        now <- Clock.currentTime(TimeUnit.MILLISECONDS)
        invite <- invitationRepo.insert(
          GroupInvitationRow(
            0L,
            group.id,
            "invited@example.com",
            "read_only",
            "tok-mismatch",
            1L,
            now,
            now + 100000L,
            None,
          )
        )
        result <- service.acceptInvitation(2L, "someone-else@example.com", invite.token).either
      } yield assertTrue(result == Left(GroupFailure.InvitationInvalid))
    },
    test("accepting an expired invitation is rejected") {
      for {
        service <- ZIO.service[GroupService]
        group <- service.createGroup(1L, "Expired Co")
        invitationRepo <- ZIO.service[GroupInvitationRepository]
        now <- Clock.currentTime(TimeUnit.MILLISECONDS)
        invite <- invitationRepo.insert(
          GroupInvitationRow(
            0L,
            group.id,
            "late@example.com",
            "read_only",
            "tok-expired",
            1L,
            now - 200000L,
            now - 100000L,
            None,
          )
        )
        result <- service.acceptInvitation(2L, "late@example.com", invite.token).either
      } yield assertTrue(result == Left(GroupFailure.InvitationInvalid))
    },
  ).provide(layer)
}
