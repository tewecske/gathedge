package webapp1.shared.domain

import zio.json.*

enum GroupRole derives JsonCodec {
  case Admin, ReadWrite, ReadOnly

  def canWrite: Boolean = this != GroupRole.ReadOnly
  def isAdmin: Boolean = this == GroupRole.Admin
}

object GroupRole {
  val all: List[GroupRole] = List(Admin, ReadWrite, ReadOnly)

  def fromString(s: String): Option[GroupRole] = s match {
    case "admin"      => Some(Admin)
    case "read_write" => Some(ReadWrite)
    case "read_only"  => Some(ReadOnly)
    case _            => None
  }

  def toDbString(role: GroupRole): String = role match {
    case Admin     => "admin"
    case ReadWrite => "read_write"
    case ReadOnly  => "read_only"
  }
}

final case class Group(id: Long, name: String, myRole: GroupRole, createdAt: String) derives JsonCodec

final case class GroupPair(
  id: Long,
  source: String,
  target: String,
  createdByEmail: String,
  createdAt: String,
) derives JsonCodec
