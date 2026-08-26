package gathedge.backend.service

import gathedge.backend.db.{GroupMemberRow, GroupRepository, GroupRow, UserRow, WordRepository}
import gathedge.backend.security.Tokens
import gathedge.shared.domain.{Group, GroupRole}
import gathedge.shared.dto.{GroupDetail, GroupMemberSummary, GroupTagSummary}
import gathedge.shared.i18n.MessageRef
import gathedge.shared.validation.Validation
import zio.*

import java.util.concurrent.TimeUnit

/** Every way a group operation can fail. `NotFound` covers "no such group"; `GroupService.requireEditableTag`-style
  * hiding does not apply here the way it does to a tag, since a group's existence is not itself a secret — only its
  * roster is (see `GroupDetail`). `TagNotFound` mirrors `WordFailure.TagNotFound`'s own "not the owner and not visible"
  * answer, reused as-is by `ApiFailures` via `MessageKeys.wordTagNotFound`.
  */
enum GroupFailure {
  case ValidationError(fieldErrors: Map[String, MessageRef])
  case NotFound
  case InviteCodeInvalid
  case NotMember
  case NotAdmin
  case LastAdmin
  case TagNotFound
  case TagNotOwned
  case TagAlreadyInGroup
  case TagNotInGroup
  case RateLimited
}

/** Classroom-style tag groups. A group has two roles (`admin`/`member`); attaching one of the caller's own tags to a
  * group they belong to opens that tag's *content* to every member — see `WordService.requireEditableTag`, which reads
  * `GroupRepository` directly rather than depending on this service, avoiding a service-to-service cycle (this service
  * depends on `WordRepository`, the other direction).
  */
trait GroupService {

  /** Every group, with the caller's own role in each — `None` for one they haven't joined. */
  def list(viewerId: Long): UIO[List[Group]]

  def detail(groupId: Long, viewerId: Long): IO[GroupFailure, GroupDetail]

  /** Creates a group; the caller becomes its sole admin. */
  def create(name: String, userId: Long): IO[GroupFailure, GroupDetail]

  /** Redeems an invite code, joining as a plain member. Idempotent for a group the caller already belongs to. */
  def join(code: String, userId: Long): IO[GroupFailure, Unit]

  /** Removes the caller from the roster. Fails [[GroupFailure.LastAdmin]] if they are its only admin. */
  def leave(groupId: Long, userId: Long): IO[GroupFailure, Unit]

  /** Admin-only. Renames the group; no per-account uniqueness, unlike a tag's own rename. */
  def renameGroup(groupId: Long, name: String, userId: Long): IO[GroupFailure, GroupDetail]

  /** Admin-only. Mints a fresh code and immediately invalidates the old one. */
  def regenerateInviteCode(groupId: Long, userId: Long): IO[GroupFailure, String]

  /** Admin-only. Fails [[GroupFailure.LastAdmin]] when demoting the group's only admin. */
  def setMemberRole(
    groupId: Long,
    actingUserId: Long,
    targetUserId: Long,
    role: GroupRole,
  ): IO[GroupFailure, GroupMemberSummary]

  /** Admin-only. Fails [[GroupFailure.LastAdmin]] when removing the group's only admin. */
  def removeMember(groupId: Long, actingUserId: Long, targetUserId: Long): IO[GroupFailure, Unit]

  /** Attaches one of `userId`'s own tags to a group they belong to. [[GroupFailure.NotMember]] covers `userId` not
    * belonging to `groupId`; [[GroupFailure.TagNotOwned]] covers not owning `tagId`; [[GroupFailure.TagAlreadyInGroup]]
    * covers the tag already belonging to a group.
    */
  def attachTag(groupId: Long, tagId: Long, userId: Long): IO[GroupFailure, Unit]

  /** Detaches a tag, reverting it to owner-only edit rights. Callable by the tag's own owner or by any admin of the
    * group it currently belongs to.
    */
  def detachTag(groupId: Long, tagId: Long, userId: Long): IO[GroupFailure, Unit]
}

object GroupService {

  def list(viewerId: Long): URIO[GroupService, List[Group]] =
    ZIO.serviceWithZIO[GroupService](_.list(viewerId))

  def detail(groupId: Long, viewerId: Long): ZIO[GroupService, GroupFailure, GroupDetail] =
    ZIO.serviceWithZIO[GroupService](_.detail(groupId, viewerId))

  def create(name: String, userId: Long): ZIO[GroupService, GroupFailure, GroupDetail] =
    ZIO.serviceWithZIO[GroupService](_.create(name, userId))

  def join(code: String, userId: Long): ZIO[GroupService, GroupFailure, Unit] =
    ZIO.serviceWithZIO[GroupService](_.join(code, userId))

  def leave(groupId: Long, userId: Long): ZIO[GroupService, GroupFailure, Unit] =
    ZIO.serviceWithZIO[GroupService](_.leave(groupId, userId))

  def renameGroup(groupId: Long, name: String, userId: Long): ZIO[GroupService, GroupFailure, GroupDetail] =
    ZIO.serviceWithZIO[GroupService](_.renameGroup(groupId, name, userId))

  def regenerateInviteCode(groupId: Long, userId: Long): ZIO[GroupService, GroupFailure, String] =
    ZIO.serviceWithZIO[GroupService](_.regenerateInviteCode(groupId, userId))

  def setMemberRole(
    groupId: Long,
    actingUserId: Long,
    targetUserId: Long,
    role: GroupRole,
  ): ZIO[GroupService, GroupFailure, GroupMemberSummary] =
    ZIO.serviceWithZIO[GroupService](_.setMemberRole(groupId, actingUserId, targetUserId, role))

  def removeMember(groupId: Long, actingUserId: Long, targetUserId: Long): ZIO[GroupService, GroupFailure, Unit] =
    ZIO.serviceWithZIO[GroupService](_.removeMember(groupId, actingUserId, targetUserId))

  def attachTag(groupId: Long, tagId: Long, userId: Long): ZIO[GroupService, GroupFailure, Unit] =
    ZIO.serviceWithZIO[GroupService](_.attachTag(groupId, tagId, userId))

  def detachTag(groupId: Long, tagId: Long, userId: Long): ZIO[GroupService, GroupFailure, Unit] =
    ZIO.serviceWithZIO[GroupService](_.detachTag(groupId, tagId, userId))

  val live: URLayer[GroupRepository & WordRepository & RateLimiter, GroupService] = {
    ZLayer.fromFunction((repo: GroupRepository, wordRepo: WordRepository, rateLimiter: RateLimiter) =>
      GroupServiceLive(repo, wordRepo, rateLimiter)
    )
  }
}

final case class GroupServiceLive(repo: GroupRepository, wordRepo: WordRepository, rateLimiter: RateLimiter)
    extends GroupService {

  private def adminCode  = GroupRole.code(GroupRole.Admin)
  private def memberCode = GroupRole.code(GroupRole.Member)

  private def toMemberSummary(member: GroupMemberRow, user: UserRow): GroupMemberSummary = {
    GroupMemberSummary(
      user.id,
      user.email,
      user.isGuest,
      GroupRole.fromString(member.role).getOrElse(GroupRole.Member),
      member.createdAt,
    )
  }

  /** Refuses to demote/remove/leave the group's only admin — the same shape `AuthService.unlinkOAuth` follows for an
    * account's last credential. A no-op for a plain member: only an admin's own departure can ever leave the roster
    * without one.
    */
  private def guardNotLastAdmin(groupId: Long, member: GroupMemberRow): IO[GroupFailure, Unit] = {
    ZIO
      .when(member.role == adminCode) {
        repo.countAdmins(groupId).orDie.flatMap(count => ZIO.when(count <= 1)(ZIO.fail(GroupFailure.LastAdmin)))
      }
      .unit
  }

  /** The group, once `userId` is confirmed to be one of its admins. [[GroupFailure.NotAdmin]] covers both not being a
    * member at all and being a plain member — from the caller's side there is no difference worth surfacing.
    */
  private def requireAdmin(groupId: Long, userId: Long): IO[GroupFailure, GroupRow] = {
    for {
      group      <- repo.findGroupById(groupId).orDie.someOrFail(GroupFailure.NotFound)
      membership <- repo.findMembership(groupId, userId).orDie
      _          <- ZIO.unless(membership.exists(_.role == adminCode))(ZIO.fail(GroupFailure.NotAdmin))
    } yield group
  }

  def list(viewerId: Long): UIO[List[Group]] = {
    for {
      rows        <- repo.listGroups.orDie
      memberships <- repo.listMembershipsFor(viewerId).orDie
      roleByGroup  = memberships.flatMap(m => GroupRole.fromString(m.role).map(m.groupId -> _)).toMap
    } yield rows.map { case (row, memberCount, tagCount) =>
      Group(row.id, row.name, memberCount, tagCount, roleByGroup.get(row.id))
    }
  }

  def detail(groupId: Long, viewerId: Long): IO[GroupFailure, GroupDetail] = {
    for {
      group      <- repo.findGroupById(groupId).orDie.someOrFail(GroupFailure.NotFound)
      membership <- repo.findMembership(groupId, viewerId).orDie
      memberRows <- repo.membersWithUsers(groupId).orDie
      tagRows    <- repo.tagsOfGroup(groupId).orDie
      isMember    = membership.isDefined
      isAdmin     = membership.exists(_.role == adminCode)
    } yield GroupDetail(
      id = group.id,
      name = group.name,
      memberCount = memberRows.size.toLong,
      viewerRole = membership.flatMap(m => GroupRole.fromString(m.role)),
      inviteCode = Option.when(isAdmin)(group.inviteCode),
      members = if (isMember) memberRows.map { case (member, user) => toMemberSummary(member, user) } else Nil,
      tags = tagRows.map { case (tag, wordCount, owner) =>
        GroupTagSummary(tag.id, tag.name, wordCount, owner.flatMap(_.email), owner.exists(_.isGuest))
      },
    )
  }

  def create(name: String, userId: Long): IO[GroupFailure, GroupDetail] = {
    for {
      valid  <- ZIO
                  .fromEither(Validation.validateGroupName(name))
                  .mapError(error => GroupFailure.ValidationError(Map("name" -> error)))
      normal  = Group.normalize(valid)
      code   <- Tokens.claimCode()
      now    <- Clock.currentTime(TimeUnit.MILLISECONDS)
      group  <- repo.insertGroup(valid, normal, code, userId, now).orDie
      _      <- repo.insertMembership(group.id, userId, adminCode, now).orDie
      result <- detail(group.id, userId)
    } yield result
  }

  def join(code: String, userId: Long): IO[GroupFailure, Unit] = {
    val key = RateLimitKey.groupJoin(userId)
    for {
      blocked <- rateLimiter.isBlocked(key)
      _       <- ZIO.when(blocked)(ZIO.fail(GroupFailure.RateLimited))
      _       <- rateLimiter.recordFailure(key)
      group   <-
        repo.findGroupByInviteCode(Tokens.normalizeClaimCode(code)).orDie.someOrFail(GroupFailure.InviteCodeInvalid)
      now     <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _       <- repo.insertMembership(group.id, userId, memberCode, now).orDie
      _       <- rateLimiter.clear(key)
    } yield ()
  }

  def leave(groupId: Long, userId: Long): IO[GroupFailure, Unit] = {
    for {
      membership <- repo.findMembership(groupId, userId).orDie.someOrFail(GroupFailure.NotFound)
      _          <- guardNotLastAdmin(groupId, membership)
      _          <- repo.deleteMembership(groupId, userId).orDie
    } yield ()
  }

  def renameGroup(groupId: Long, name: String, userId: Long): IO[GroupFailure, GroupDetail] = {
    for {
      _              <- requireAdmin(groupId, userId)
      valid          <- ZIO
                          .fromEither(Validation.validateGroupName(name))
                          .mapError(error => GroupFailure.ValidationError(Map("name" -> error)))
      normal          = Group.normalize(valid)
      _              <- repo.updateGroupName(groupId, valid, normal).orDie
      result         <- detail(groupId, userId)
    } yield result
  }

  def regenerateInviteCode(groupId: Long, userId: Long): IO[GroupFailure, String] = {
    for {
      _    <- requireAdmin(groupId, userId)
      code <- Tokens.claimCode()
      _    <- repo.updateInviteCode(groupId, code).orDie
    } yield code
  }

  def setMemberRole(
    groupId: Long,
    actingUserId: Long,
    targetUserId: Long,
    role: GroupRole,
  ): IO[GroupFailure, GroupMemberSummary] = {
    for {
      _       <- requireAdmin(groupId, actingUserId)
      target  <- repo.findMembership(groupId, targetUserId).orDie.someOrFail(GroupFailure.NotFound)
      _       <- ZIO.when(role == GroupRole.Member)(guardNotLastAdmin(groupId, target))
      rows    <- repo.updateMemberRole(groupId, targetUserId, GroupRole.code(role)).orDie
      _       <- ZIO.when(rows == 0L)(ZIO.fail(GroupFailure.NotFound))
      members <- repo.membersWithUsers(groupId).orDie
      updated <- ZIO
                   .fromOption(members.find { case (member, _) => member.userId == targetUserId })
                   .orElseFail(GroupFailure.NotFound)
    } yield toMemberSummary(updated._1, updated._2)
  }

  def removeMember(groupId: Long, actingUserId: Long, targetUserId: Long): IO[GroupFailure, Unit] = {
    for {
      _      <- requireAdmin(groupId, actingUserId)
      target <- repo.findMembership(groupId, targetUserId).orDie.someOrFail(GroupFailure.NotFound)
      _      <- guardNotLastAdmin(groupId, target)
      _      <- repo.deleteMembership(groupId, targetUserId).orDie
    } yield ()
  }

  def attachTag(groupId: Long, tagId: Long, userId: Long): IO[GroupFailure, Unit] = {
    for {
      _          <- repo.findGroupById(groupId).orDie.someOrFail(GroupFailure.NotFound)
      membership <- repo.findMembership(groupId, userId).orDie
      _          <- ZIO.when(membership.isEmpty)(ZIO.fail(GroupFailure.NotMember))
      tag        <- wordRepo.findTagById(tagId).orDie.someOrFail(GroupFailure.TagNotFound)
      _          <- ZIO.unless(tag.userId == userId)(ZIO.fail(GroupFailure.TagNotOwned))
      _          <- ZIO.when(tag.groupId.isDefined)(ZIO.fail(GroupFailure.TagAlreadyInGroup))
      _          <- wordRepo.setTagGroup(tagId, Some(groupId)).orDie
    } yield ()
  }

  def detachTag(groupId: Long, tagId: Long, userId: Long): IO[GroupFailure, Unit] = {
    for {
      tag        <- wordRepo.findTagById(tagId).orDie.someOrFail(GroupFailure.TagNotFound)
      _          <- ZIO.unless(tag.groupId.contains(groupId))(ZIO.fail(GroupFailure.TagNotInGroup))
      membership <- repo.findMembership(groupId, userId).orDie
      isAdmin     = membership.exists(_.role == adminCode)
      _          <- ZIO.unless(tag.userId == userId || isAdmin)(ZIO.fail(GroupFailure.NotAdmin))
      _          <- wordRepo.setTagGroup(tagId, None).orDie
    } yield ()
  }
}
