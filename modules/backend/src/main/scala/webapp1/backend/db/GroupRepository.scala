package webapp1.backend.db

import zio.*

trait GroupRepository {
  def insert(name: String, createdAt: Long): Task[GroupRow]
  def findById(id: Long): Task[Option[GroupRow]]

  /** Groups the user is a member of, paired with their role in each. */
  def listForUser(userId: Long): Task[List[(GroupRow, String)]]
  def delete(id: Long): Task[Unit]
}

trait GroupMemberRepository {
  def addMember(groupId: Long, userId: Long, role: String, joinedAt: Long): Task[Unit]
  def findRole(groupId: Long, userId: Long): Task[Option[String]]

  /** Members of a group, paired with their email. */
  def listForGroup(groupId: Long): Task[List[(GroupMemberRow, String)]]
  def removeMember(groupId: Long, userId: Long): Task[Unit]
  def updateRole(groupId: Long, userId: Long, role: String): Task[Unit]
  def countAdmins(groupId: Long): Task[Long]
}

trait GroupPairRepository {
  def insert(
    groupId: Long,
    source: String,
    target: String,
    createdBy: Long,
    createdByEmail: String,
    createdAt: Long,
  ): Task[GroupPairRow]
  def listForGroup(groupId: Long): Task[List[GroupPairRow]]
}

trait GroupInvitationRepository {
  def insert(row: GroupInvitationRow): Task[GroupInvitationRow]
  def findByToken(token: String): Task[Option[GroupInvitationRow]]
  def markAccepted(token: String, acceptedAt: Long): Task[Unit]
}
