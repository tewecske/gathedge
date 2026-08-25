package gathedge.shared.domain

import zio.json.*

/** A member's standing in a [[Group]]. An admin manages the roster (promote/demote/remove, regenerate the invite code)
  * and decides which tags leave the group; a plain member already has everything needed to collaborate — editing the
  * content of any tag the group holds — so joining never mints an admin outright. See `WordService.requireEditableTag`
  * for the write-access rule this role feeds into.
  */
enum GroupRole derives JsonCodec, CanEqual {
  case Admin,
    Member
}

object GroupRole {

  /** Lower-case wire form: what `group_members.role` stores and what the DTOs carry. Written out rather than derived
    * from `toString`, so renaming a case cannot silently orphan a stored row — the rule [[WordLanguage.code]] follows.
    */
  def code(role: GroupRole): String = {
    role match {
      case Admin  =>
        "admin"
      case Member =>
        "member"
    }
  }

  def fromString(value: String): Option[GroupRole] = {
    value match {
      case "admin"  =>
        Some(Admin)
      case "member" =>
        Some(Member)
      case _        =>
        None
    }
  }
}

/** The id+name a tag carries to point at the group it belongs to, if any. Kept separate from a full [[Group]] so
  * listing a tag never has to resolve the group's member/tag counts just to show its name.
  */
final case class GroupRef(id: Long, name: String) derives JsonCodec

/** A group, as the browse listing and a tag's `group` reference show it. Groups are public in name and tag count —
  * anyone may browse them, same as a tag itself — but the roster is not: `memberCount` is a number everyone sees, while
  * the actual member list on [[gathedge.shared.dto.GroupDto.GroupDetail]] is populated only for a viewer who is
  * themself a member.
  *
  * @param viewerRole
  *   the caller's own standing in the group, or `None` for a signed-out visitor or a non-member. Plays the role
  *   [[Tag.ownedByMe]] plays for a tag, except there are two "in" states instead of one.
  */
final case class Group(id: Long, name: String, memberCount: Long, tagCount: Long, viewerRole: Option[GroupRole])
    derives JsonCodec

object Group {

  val maxNameLength = 64

  /** The form a name is stored and compared in. Group names are not unique — several classes working from the same book
    * may legitimately share a name — so this is for sorted/case-insensitive listing only, not a collision check.
    */
  def normalize(name: String): String = name.trim.toLowerCase
}
