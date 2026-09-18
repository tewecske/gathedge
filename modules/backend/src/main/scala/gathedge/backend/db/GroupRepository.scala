package gathedge.backend.db

import gathedge.shared.domain.{Group, GroupRole}
import gathedge.shared.dto.GroupSort
import io.getquill.*
import zio.*

import javax.sql.DataSource

/** Classroom-style groups: a roster of accounts (`admin` or `member`) that a tag may be attached to, opening its
  * content to every member — see `WordService.requireEditableTag`.
  *
  * '''`inviteCode` is the credential.''' Like [[ProgressShareRepository]]'s share code and
  * [[GuestClaimCodeRepository]]'s transfer code, it must never reach a log line.
  *
  * Reads a `tags`/`word_tags` (owned by [[WordRepository]]) and `users` (owned by `UserRepository`) for display
  * purposes only — reading another repository's tables is fine, see `GameRepository`'s own note on this. Every write to
  * `tags.group_id` stays in [[WordRepository.setTagGroup]], which already owns every other write to that table.
  */
trait GroupRepository {

  def insertGroup(name: String, nameNorm: String, inviteCode: String, createdBy: Long, createdAt: Long): Task[GroupRow]

  def findGroupById(id: Long): Task[Option[GroupRow]]

  /** Batched form of [[findGroupById]], for resolving several tags' `Tag.group` refs in one query rather than one per
    * tag — the same reason [[GroupRepository]]'s own [[listPage]] batches its counts.
    */
  def findGroupsByIds(ids: List[Long]): Task[List[GroupRow]]

  def findGroupByInviteCode(code: String): Task[Option[GroupRow]]

  /** Rotates the group's invite code; the old one stops resolving the moment this returns. Bumps `version`. */
  def updateInviteCode(id: Long, code: String): Task[Unit]

  /** Renames the group, only if its `version` still matches `expectedVersion`. `nameNorm` is
    * [[gathedge.shared.domain.Group.normalize]]'d for sorted/case-insensitive listing, the same split `insertGroup`
    * keeps between `name` and `nameNorm`. Returns rows affected, so a caller can tell a lost race from a success.
    */
  def updateGroupName(id: Long, name: String, nameNorm: String, expectedVersion: Long): Task[Long]

  /** One page of groups, with how many members and how many attached tags each has — what the browse page is built
    * from. `nameContains` narrows by name, `tagContains` to groups holding an attached tag whose name contains it, both
    * case-insensitive substrings.
    */
  def listPage(
    offset: Int,
    limit: Int,
    nameContains: Option[String],
    tagContains: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): Task[List[(GroupRow, Long, Long)]]

  /** How many groups match the same narrowing [[listPage]] applies, across every page. */
  def countMatching(nameContains: Option[String], tagContains: Option[String]): Task[Long]

  /** Every group `userId` belongs to, with their role in each — how the listing resolves `Group.viewerRole` without one
    * query per row.
    */
  def listMembershipsFor(userId: Long): Task[List[GroupMemberRow]]

  /** Adds `userId` to the roster if not already on it. Idempotent — redeeming an invite code for a group one already
    * belongs to is a no-op, not a conflict — and reports which happened, purely for the log line.
    */
  def insertMembership(groupId: Long, userId: Long, role: String, createdAt: Long): Task[Boolean]

  def findMembership(groupId: Long, userId: Long): Task[Option[GroupMemberRow]]

  /** The roster, each row paired with the account it names — what [[gathedge.shared.dto.GroupDto.GroupMemberSummary]]
    * is built from.
    */
  def membersWithUsers(groupId: Long): Task[List[(GroupMemberRow, UserRow)]]

  def countAdmins(groupId: Long): Task[Long]

  /** Sets a member's role, only while their row's `version` still matches. Returns rows affected. Bumps `version`. */
  def updateMemberRole(groupId: Long, userId: Long, role: String, expectedVersion: Long): Task[Long]

  /** Removes a membership, only while its `version` still matches. Returns rows affected. */
  def deleteMembership(groupId: Long, userId: Long, expectedVersion: Long): Task[Long]

  /** The group's attached tags, each with its word count and owner — what
    * [[gathedge.shared.dto.GroupDto.GroupTagSummary]] is built from.
    */
  def tagsOfGroup(groupId: Long): Task[List[(TagRow, Long, Option[UserRow])]]

  /** No route reaches this in v1 — it exists so `PostgresIntegrationSpec` can exercise `tags.group_id ON DELETE SET
    * NULL` directly.
    */
  def delete(id: Long): Task[Long]
}

object GroupRepository {

  def insertGroup(
    name: String,
    nameNorm: String,
    inviteCode: String,
    createdBy: Long,
    createdAt: Long,
  ): RIO[GroupRepository, GroupRow] =
    ZIO.serviceWithZIO[GroupRepository](_.insertGroup(name, nameNorm, inviteCode, createdBy, createdAt))

  def findGroupById(id: Long): RIO[GroupRepository, Option[GroupRow]] =
    ZIO.serviceWithZIO[GroupRepository](_.findGroupById(id))

  def findGroupsByIds(ids: List[Long]): RIO[GroupRepository, List[GroupRow]] =
    ZIO.serviceWithZIO[GroupRepository](_.findGroupsByIds(ids))

  def findGroupByInviteCode(code: String): RIO[GroupRepository, Option[GroupRow]] =
    ZIO.serviceWithZIO[GroupRepository](_.findGroupByInviteCode(code))

  def updateInviteCode(id: Long, code: String): RIO[GroupRepository, Unit] =
    ZIO.serviceWithZIO[GroupRepository](_.updateInviteCode(id, code))

  def updateGroupName(id: Long, name: String, nameNorm: String, expectedVersion: Long): RIO[GroupRepository, Long] =
    ZIO.serviceWithZIO[GroupRepository](_.updateGroupName(id, name, nameNorm, expectedVersion))

  def listPage(
    offset: Int,
    limit: Int,
    nameContains: Option[String],
    tagContains: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): RIO[GroupRepository, List[(GroupRow, Long, Long)]] =
    ZIO.serviceWithZIO[GroupRepository](_.listPage(offset, limit, nameContains, tagContains, sort, descending))

  def countMatching(nameContains: Option[String], tagContains: Option[String]): RIO[GroupRepository, Long] =
    ZIO.serviceWithZIO[GroupRepository](_.countMatching(nameContains, tagContains))

  def listMembershipsFor(userId: Long): RIO[GroupRepository, List[GroupMemberRow]] =
    ZIO.serviceWithZIO[GroupRepository](_.listMembershipsFor(userId))

  def insertMembership(groupId: Long, userId: Long, role: String, createdAt: Long): RIO[GroupRepository, Boolean] =
    ZIO.serviceWithZIO[GroupRepository](_.insertMembership(groupId, userId, role, createdAt))

  def findMembership(groupId: Long, userId: Long): RIO[GroupRepository, Option[GroupMemberRow]] =
    ZIO.serviceWithZIO[GroupRepository](_.findMembership(groupId, userId))

  def membersWithUsers(groupId: Long): RIO[GroupRepository, List[(GroupMemberRow, UserRow)]] =
    ZIO.serviceWithZIO[GroupRepository](_.membersWithUsers(groupId))

  def countAdmins(groupId: Long): RIO[GroupRepository, Long] =
    ZIO.serviceWithZIO[GroupRepository](_.countAdmins(groupId))

  def updateMemberRole(
    groupId: Long,
    userId: Long,
    role: String,
    expectedVersion: Long,
  ): RIO[GroupRepository, Long] =
    ZIO.serviceWithZIO[GroupRepository](_.updateMemberRole(groupId, userId, role, expectedVersion))

  def deleteMembership(groupId: Long, userId: Long, expectedVersion: Long): RIO[GroupRepository, Long] =
    ZIO.serviceWithZIO[GroupRepository](_.deleteMembership(groupId, userId, expectedVersion))

  def tagsOfGroup(groupId: Long): RIO[GroupRepository, List[(TagRow, Long, Option[UserRow])]] =
    ZIO.serviceWithZIO[GroupRepository](_.tagsOfGroup(groupId))

  def delete(id: Long): RIO[GroupRepository, Long] =
    ZIO.serviceWithZIO[GroupRepository](_.delete(id))

  val live: ZLayer[DataSource, Nothing, GroupRepository] =
    ZLayer.fromFunction((ds: DataSource) => new GroupRepositoryLive(ds): GroupRepository)
}

final class GroupRepositoryLive(dataSource: DataSource)
    extends QuillRepository(dataSource, new PostgresZioJdbcContext(SnakeCase))
    with GroupRepository {
  import ctx._

  private inline def groups        = quote(querySchema[GroupRow]("groups"))
  private inline def members       = quote(querySchema[GroupMemberRow]("group_members"))
  // Read-only views of tables owned by WordRepository/UserRepository — see the trait doc. The lambda parameter is
  // `row`, never `user` — Postgres reserved word.
  private inline def tags          = quote(querySchema[TagRow]("tags"))
  private inline def wordTagsTable = quote(querySchema[WordTagRow]("word_tags"))
  private inline def users         = quote(querySchema[UserRow]("users"))

  def insertGroup(
    name: String,
    nameNorm: String,
    inviteCode: String,
    createdBy: Long,
    createdAt: Long,
  ): Task[GroupRow] = {
    val row      = GroupRow(0L, name, nameNorm, inviteCode, Some(createdBy), createdAt)
    val inserted = run(ctx.run(quote(groups.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id)))(saved => s"groups.insert id=${saved.id} createdBy=$createdBy")
  }

  def findGroupById(id: Long): Task[Option[GroupRow]] = {
    logged(run(ctx.run(quote(groups.filter(_.id == lift(id))))).map(_.headOption)) { found =>
      s"groups.findById id=$id found=${found.isDefined}"
    }
  }

  def findGroupsByIds(ids: List[Long]): Task[List[GroupRow]] = {
    if (ids.isEmpty)
      ZIO.succeed(Nil)
    else {
      val q = quote(groups.filter(row => liftQuery(ids).contains(row.id)))
      logged(run(ctx.run(q)))(rows => s"groups.findByIds count=${ids.size} found=${rows.size}")
    }
  }

  def findGroupByInviteCode(code: String): Task[Option[GroupRow]] = {
    val q = quote(groups.filter(_.inviteCode == lift(code)))
    logged(run(ctx.run(q)).map(_.headOption))(found => s"groups.findByInviteCode found=${found.isDefined}")
  }

  def updateInviteCode(id: Long, code: String): Task[Unit] = {
    val q = quote(
      groups.filter(_.id == lift(id)).update(_.inviteCode -> lift(code), row => row.version -> (row.version + 1))
    )
    logged(run(ctx.run(q)).unit)(_ => s"groups.updateInviteCode id=$id")
  }

  def updateGroupName(id: Long, name: String, nameNorm: String, expectedVersion: Long): Task[Long] = {
    val q = quote(
      groups
        .filter(row => row.id == lift(id) && row.version == lift(expectedVersion))
        .update(_.name -> lift(name), _.nameNorm -> lift(nameNorm), row => row.version -> (row.version + 1))
    )
    logged(run(ctx.run(q)))(rows => s"groups.updateName id=$id rows=$rows")
  }

  /** The `LIKE` pattern behind the name filter, or `None` when it is empty — the same shape
    * `UserRepository.emailPattern` follows, normalised through [[Group.normalize]] since that is what `nameNorm`
    * already stores.
    */
  private def namePattern(nameContains: Option[String]): Option[String] = {
    nameContains.map(Group.normalize).filter(_.nonEmpty).map(needle => s"%$needle%")
  }

  private def tagPattern(tagContains: Option[String]): Option[String] = {
    tagContains.map(_.trim.toLowerCase).filter(_.nonEmpty).map(needle => s"%$needle%")
  }

  /** The rows a page is cut from, before ordering: the narrowing [[listPage]] and [[countMatching]] have to share, or
    * the total would count a different set than the page shows.
    *
    * The tag filter is a correlated subquery rather than a join, the same reason `WordRepository.matching`'s own
    * `tagId` filter is: Quill's Dynamic Query cannot synthesize a `.join` inside a `filterOpt` closure.
    */
  private def matching(nameContains: Option[String], tagContains: Option[String]): DynamicQuery[GroupRow] = {
    dynamicQuerySchema[GroupRow]("groups")
      .filterOpt(namePattern(nameContains))((row, pattern) => quote(row.nameNorm.like(unquote(pattern))))
      .filterOpt(tagPattern(tagContains))((row, pattern) =>
        quote(tags.filter(tag => tag.groupId.contains(row.id) && tag.nameNorm.like(unquote(pattern))).nonEmpty)
      )
  }

  /** The `dto.GroupSort` vocabulary translated to an `ORDER BY`, defaulting to name — the listing's own order, and the
    * one every group has something to show for.
    */
  private def ordered(
    query: DynamicQuery[GroupRow],
    sort: Option[String],
    descending: Boolean,
  ): DynamicQuery[GroupRow] = {
    sort match {
      case Some(GroupSort.name) =>
        query.sortBy(_.nameNorm)(using ordering(descending))
      case _                    =>
        query.sortBy(_.nameNorm)(using Ord.asc)
    }
  }

  def listPage(
    offset: Int,
    limit: Int,
    nameContains: Option[String],
    tagContains: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): Task[List[(GroupRow, Long, Long)]] = {
    val page    = ordered(matching(nameContains, tagContains), sort, descending).drop(offset).take(limit)
    val counted = for {
      rows          <- run(ctx.run(page))
      ids            = rows.map(_.id)
      memberCounted <-
        run(ctx.run(quote {
          members.filter(row => liftQuery(ids).contains(row.groupId)).groupBy(_.groupId).map {
            case (groupId, grouped) =>
              (groupId, grouped.size)
          }
        }))
      // Only tags actually attached to one of these groups are counted, so the filter happens before the group-by
      // rather than after — an ungrouped tag, or one attached elsewhere, has no row here at all.
      tagCounted    <-
        run(ctx.run(quote {
          tags
            .filter(row => row.groupId.exists(groupId => liftQuery(ids).contains(groupId)))
            .groupBy(_.groupId)
            .map { case (groupId, grouped) => (groupId, grouped.size) }
        }))
      byMember       = memberCounted.toMap
      byTag          = tagCounted.flatMap { case (groupId, count) => groupId.map(_ -> count) }.toMap
    } yield rows.map(group => (group, byMember.getOrElse(group.id, 0L), byTag.getOrElse(group.id, 0L)))
    logged(counted) { rows =>
      s"groups.listPage offset=$offset limit=$limit sort=${sort.getOrElse("-")} desc=$descending rows=${rows.size}"
    }
  }

  def countMatching(nameContains: Option[String], tagContains: Option[String]): Task[Long] = {
    logged(run(ctx.run(matching(nameContains, tagContains).size)))(count => s"groups.countMatching count=$count")
  }

  def listMembershipsFor(userId: Long): Task[List[GroupMemberRow]] = {
    val q = quote(members.filter(_.userId == lift(userId)))
    logged(run(ctx.run(q)))(rows => s"groupMembers.listFor user=$userId rows=${rows.size}")
  }

  def insertMembership(groupId: Long, userId: Long, role: String, createdAt: Long): Task[Boolean] = {
    val existing = quote(members.filter(row => row.groupId == lift(groupId) && row.userId == lift(userId)))
    val row      = GroupMemberRow(0L, groupId, userId, role, createdAt)
    val added    = run {
      ctx.run(existing).flatMap { found =>
        if (found.nonEmpty)
          ZIO.succeed(false)
        else
          ctx.run(quote(members.insertValue(lift(row)).returningGenerated(_.id))).as(true)
      }
    }
    logged(added)(wasAdded => s"groupMembers.insert group=$groupId user=$userId added=$wasAdded")
  }

  def findMembership(groupId: Long, userId: Long): Task[Option[GroupMemberRow]] = {
    val q = quote(members.filter(row => row.groupId == lift(groupId) && row.userId == lift(userId)))
    logged(run(ctx.run(q)).map(_.headOption))(found => s"groupMembers.find group=$groupId found=${found.isDefined}")
  }

  def membersWithUsers(groupId: Long): Task[List[(GroupMemberRow, UserRow)]] = {
    val rosterQ = quote(members.filter(_.groupId == lift(groupId)))
    val joined  = for {
      roster <- run(ctx.run(rosterQ))
      userIds = roster.map(_.userId)
      people <- run(ctx.run(quote(users.filter(row => liftQuery(userIds).contains(row.id)))))
      byId    = people.map(row => row.id -> row).toMap
    } yield roster.flatMap(member => byId.get(member.userId).map(member -> _))
    logged(joined)(rows => s"groupMembers.withUsers group=$groupId rows=${rows.size}")
  }

  def countAdmins(groupId: Long): Task[Long] = {
    val adminRole = GroupRole.code(GroupRole.Admin)
    val q         = quote(members.filter(row => row.groupId == lift(groupId) && row.role == lift(adminRole)).size)
    logged(run(ctx.run(q)))(count => s"groupMembers.countAdmins group=$groupId count=$count")
  }

  def updateMemberRole(groupId: Long, userId: Long, role: String, expectedVersion: Long): Task[Long] = {
    val q = quote {
      members
        .filter(row =>
          row.groupId == lift(groupId) && row.userId == lift(userId) && row.version == lift(expectedVersion)
        )
        .update(_.role -> lift(role), row => row.version -> (row.version + 1))
    }
    logged(run(ctx.run(q)))(rows => s"groupMembers.updateRole group=$groupId user=$userId rows=$rows")
  }

  def deleteMembership(groupId: Long, userId: Long, expectedVersion: Long): Task[Long] = {
    val q = quote(
      members
        .filter(row =>
          row.groupId == lift(groupId) && row.userId == lift(userId) && row.version == lift(expectedVersion)
        )
        .delete
    )
    logged(run(ctx.run(q)))(rows => s"groupMembers.delete group=$groupId user=$userId rows=$rows")
  }

  def tagsOfGroup(groupId: Long): Task[List[(TagRow, Long, Option[UserRow])]] = {
    val groupTagsQ = quote(tags.filter(_.groupId.contains(lift(groupId))))
    val counts     = quote(wordTagsTable.groupBy(_.tagId).map { case (tagId, rows) => (tagId, rows.size) })
    val joined     = for {
      rows      <- run(ctx.run(groupTagsQ))
      counted   <- run(ctx.run(counts))
      byTag      = counted.toMap
      ownerIds   = rows.map(_.userId).distinct
      owners    <- run(ctx.run(quote(users.filter(row => liftQuery(ownerIds).contains(row.id)))))
      ownersById = owners.map(row => row.id -> row).toMap
    } yield rows.map(tag => (tag, byTag.getOrElse(tag.id, 0L), ownersById.get(tag.userId)))
    logged(joined)(rows => s"groups.tagsOf group=$groupId rows=${rows.size}")
  }

  def delete(id: Long): Task[Long] = {
    val q = quote(groups.filter(_.id == lift(id)).delete)
    logged(run(ctx.run(q)))(rows => s"groups.delete id=$id rows=$rows")
  }
}
