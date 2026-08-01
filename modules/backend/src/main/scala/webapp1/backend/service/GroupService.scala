package webapp1.backend.service

import webapp1.backend.config.AppConfig
import webapp1.backend.db.{
  GroupInvitationRepository,
  GroupInvitationRow,
  GroupMemberRepository,
  GroupPairRepository,
  GroupPairRow,
  GroupRepository,
  GroupRow,
}
import webapp1.shared.domain.{Group, GroupMember, GroupPair, GroupRole, InvitationInfo}
import webapp1.shared.validation.Validation
import zio.*

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

enum GroupFailure {
  case ValidationError(fieldErrors: Map[String, String])
  case NotFound
  case NotMember
  case ReadOnlyMember
  case AdminOnly
  case LastAdmin
  case InvitationInvalid
}

/** Group creation makes the creator an admin member (summary.md); every other group operation requires membership,
  * writes to pairs additionally require a non-read-only role, and membership/invitation management requires the admin
  * role. Removing or demoting a group's last admin is always blocked — a superset of summary.md's "last admin can't
  * remove themselves" that also prevents ever leaving a group with zero admins via any other actor.
  */
trait GroupService {
  def createGroup(userId: Long, name: String): IO[GroupFailure, Group]
  def myGroups(userId: Long): UIO[List[Group]]
  def getGroup(userId: Long, groupId: Long): IO[GroupFailure, Group]
  def deleteGroup(userId: Long, groupId: Long): IO[GroupFailure, Unit]
  def listPairs(userId: Long, groupId: Long): IO[GroupFailure, List[GroupPair]]
  def addPair(
    userId: Long,
    userEmail: String,
    groupId: Long,
    source: String,
    target: String,
  ): IO[GroupFailure, GroupPair]
  def listMembers(userId: Long, groupId: Long): IO[GroupFailure, List[GroupMember]]
  def removeMember(userId: Long, groupId: Long, targetUserId: Long): IO[GroupFailure, Unit]
  def updateMemberRole(userId: Long, groupId: Long, targetUserId: Long, newRole: GroupRole): IO[GroupFailure, Unit]
  def inviteMember(userId: Long, groupId: Long, email: String, role: GroupRole): IO[GroupFailure, Unit]
  def getInvitationInfo(token: String): IO[GroupFailure, InvitationInfo]
  def acceptInvitation(userId: Long, userEmail: String, token: String): IO[GroupFailure, Group]
}

final class GroupServiceLive(
  groupRepo: GroupRepository,
  memberRepo: GroupMemberRepository,
  pairRepo: GroupPairRepository,
  invitationRepo: GroupInvitationRepository,
  emailSender: EmailSender,
  config: AppConfig,
) extends GroupService {

  private val secureRandom = new SecureRandom()
  private val invitationValidity: Duration = 7.days

  private def toDomain(row: GroupRow, role: String): Group = {
    Group(row.id, row.name, GroupRole.fromString(role).getOrElse(GroupRole.ReadOnly), row.createdAt.toString)
  }

  private def toDomain(row: GroupPairRow): GroupPair = {
    GroupPair(row.id, row.source, row.target, row.createdByEmail, row.createdAt.toString)
  }

  private def findGroupOrDie(groupId: Long): UIO[GroupRow] = {
    groupRepo.findById(groupId).orDie.map(_.getOrElse(throw new IllegalStateException(s"group $groupId vanished")))
  }

  private def newToken(): String = {
    val bytes = new Array[Byte](32)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  def createGroup(userId: Long, name: String): IO[GroupFailure, Group] = {
    Validation.validateNonBlank(name, "Name") match {
      case Left(err) =>
        ZIO.fail(GroupFailure.ValidationError(Map("name" -> err)))
      case Right(validName) =>
        for {
          now <- Clock.currentTime(TimeUnit.MILLISECONDS)
          group <- groupRepo.insert(validName, now).orDie
          _ <- memberRepo.addMember(group.id, userId, GroupRole.toDbString(GroupRole.Admin), now).orDie
        } yield toDomain(group, GroupRole.toDbString(GroupRole.Admin))
    }
  }

  def myGroups(userId: Long): UIO[List[Group]] = {
    groupRepo
      .listForUser(userId)
      .orDie
      .map(
        _.map { case (row, role) =>
          toDomain(row, role)
        }
      )
  }

  private def requireMembership(userId: Long, groupId: Long): IO[GroupFailure, GroupRole] = {
    for {
      maybeGroup <- groupRepo.findById(groupId).orDie
      _ <- ZIO.fromOption(maybeGroup).orElseFail(GroupFailure.NotFound)
      maybeRole <- memberRepo.findRole(groupId, userId).orDie
      role <- ZIO.fromOption(maybeRole.flatMap(GroupRole.fromString)).orElseFail(GroupFailure.NotMember)
    } yield role
  }

  private def requireAdmin(userId: Long, groupId: Long): IO[GroupFailure, Unit] = {
    requireMembership(userId, groupId)
      .flatMap(role => ZIO.unless(role == GroupRole.Admin)(ZIO.fail(GroupFailure.AdminOnly)))
      .unit
  }

  private def guardLastAdmin(groupId: Long, targetCurrentRole: Option[GroupRole]): IO[GroupFailure, Unit] = {
    ZIO
      .when(targetCurrentRole.contains(GroupRole.Admin)) {
        memberRepo.countAdmins(groupId).orDie.flatMap(count => ZIO.when(count <= 1)(ZIO.fail(GroupFailure.LastAdmin)))
      }
      .unit
  }

  def getGroup(userId: Long, groupId: Long): IO[GroupFailure, Group] = {
    for {
      role <- requireMembership(userId, groupId)
      group <- findGroupOrDie(groupId)
    } yield toDomain(group, GroupRole.toDbString(role))
  }

  def deleteGroup(userId: Long, groupId: Long): IO[GroupFailure, Unit] = {
    for {
      _ <- requireAdmin(userId, groupId)
      _ <- groupRepo.delete(groupId).orDie
    } yield ()
  }

  def listPairs(userId: Long, groupId: Long): IO[GroupFailure, List[GroupPair]] = {
    for {
      _ <- requireMembership(userId, groupId)
      rows <- pairRepo.listForGroup(groupId).orDie
    } yield rows.map(toDomain)
  }

  def addPair(
    userId: Long,
    userEmail: String,
    groupId: Long,
    source: String,
    target: String,
  ): IO[GroupFailure, GroupPair] = {
    val fieldErrors = {
      List(
        Validation.validateNonBlank(source, "Source").left.toOption.map("source" -> _),
        Validation.validateNonBlank(target, "Target").left.toOption.map("target" -> _),
      ).flatten.toMap
    }
    for {
      role <- requireMembership(userId, groupId)
      _ <- ZIO.unless(role.canWrite)(ZIO.fail(GroupFailure.ReadOnlyMember))
      _ <- ZIO.when(fieldErrors.nonEmpty)(ZIO.fail(GroupFailure.ValidationError(fieldErrors)))
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      row <- pairRepo.insert(groupId, source.trim, target.trim, userId, userEmail, now).orDie
    } yield toDomain(row)
  }

  def listMembers(userId: Long, groupId: Long): IO[GroupFailure, List[GroupMember]] = {
    for {
      _ <- requireMembership(userId, groupId)
      rows <- memberRepo.listForGroup(groupId).orDie
    } yield rows.map { case (row, email) =>
      GroupMember(
        row.userId,
        email,
        GroupRole.fromString(row.role).getOrElse(GroupRole.ReadOnly),
        row.joinedAt.toString,
      )
    }
  }

  def removeMember(userId: Long, groupId: Long, targetUserId: Long): IO[GroupFailure, Unit] = {
    for {
      _ <- requireAdmin(userId, groupId)
      targetRole <- memberRepo.findRole(groupId, targetUserId).orDie.map(_.flatMap(GroupRole.fromString))
      _ <- guardLastAdmin(groupId, targetRole)
      _ <- memberRepo.removeMember(groupId, targetUserId).orDie
    } yield ()
  }

  def updateMemberRole(userId: Long, groupId: Long, targetUserId: Long, newRole: GroupRole): IO[GroupFailure, Unit] = {
    for {
      _ <- requireAdmin(userId, groupId)
      targetRole <- memberRepo.findRole(groupId, targetUserId).orDie.map(_.flatMap(GroupRole.fromString))
      _ <- ZIO.when(newRole != GroupRole.Admin)(guardLastAdmin(groupId, targetRole))
      _ <- memberRepo.updateRole(groupId, targetUserId, GroupRole.toDbString(newRole)).orDie
    } yield ()
  }

  def inviteMember(userId: Long, groupId: Long, email: String, role: GroupRole): IO[GroupFailure, Unit] = {
    val normalizedEmail = email.trim.toLowerCase
    for {
      _ <- requireAdmin(userId, groupId)
      _ <- ZIO
        .fromEither(Validation.validateEmail(normalizedEmail))
        .mapError(err => GroupFailure.ValidationError(Map("email" -> err)))
      group <- findGroupOrDie(groupId)
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      token = newToken()
      _ <-
        invitationRepo
          .insert(
            GroupInvitationRow(
              0L,
              groupId,
              normalizedEmail,
              GroupRole.toDbString(role),
              token,
              userId,
              now,
              now + invitationValidity.toMillis,
              None,
            )
          )
          .orDie
      link = s"${config.app.publicBaseUrl}/invitations/$token"
      _ <-
        emailSender.send(normalizedEmail, s"You're invited to join ${group.name}", s"Join the group here: $link").orDie
    } yield ()
  }

  def getInvitationInfo(token: String): IO[GroupFailure, InvitationInfo] = {
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      maybeInvite <- invitationRepo.findByToken(token).orDie
      invite <- ZIO.fromOption(maybeInvite).orElseFail(GroupFailure.InvitationInvalid)
      group <- findGroupOrDie(invite.groupId)
    } yield InvitationInfo(
      group.name,
      invite.email,
      GroupRole.fromString(invite.role).getOrElse(GroupRole.ReadOnly),
      expired = invite.expiresAt < now,
      accepted = invite.acceptedAt.isDefined,
    )
  }

  def acceptInvitation(userId: Long, userEmail: String, token: String): IO[GroupFailure, Group] = {
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      maybeInvite <- invitationRepo.findByToken(token).orDie
      invite <- ZIO.fromOption(maybeInvite).orElseFail(GroupFailure.InvitationInvalid)
      _ <- ZIO.when(invite.acceptedAt.isDefined || invite.expiresAt < now)(ZIO.fail(GroupFailure.InvitationInvalid))
      _ <- ZIO.unless(invite.email.equalsIgnoreCase(userEmail))(ZIO.fail(GroupFailure.InvitationInvalid))
      existingRole <- memberRepo.findRole(invite.groupId, userId).orDie
      _ <-
        existingRole match {
          case Some(_) =>
            memberRepo.updateRole(invite.groupId, userId, invite.role).orDie
          case None =>
            memberRepo.addMember(invite.groupId, userId, invite.role, now).orDie
        }
      _ <- invitationRepo.markAccepted(token, now).orDie
      group <- findGroupOrDie(invite.groupId)
    } yield toDomain(group, invite.role)
  }
}

object GroupServiceLive {
  val live: URLayer[
    GroupRepository & GroupMemberRepository & GroupPairRepository & GroupInvitationRepository & EmailSender & AppConfig,
    GroupService,
  ] = ZLayer.fromFunction(
    (
      g: GroupRepository,
      m: GroupMemberRepository,
      p: GroupPairRepository,
      i: GroupInvitationRepository,
      e: EmailSender,
      cfg: AppConfig,
    ) => new GroupServiceLive(g, m, p, i, e, cfg): GroupService
  )
}
