package gathedge.backend.db

import gathedge.shared.domain.GroupRole
import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
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
    * tag — the same reason [[GroupRepository]]'s own [[listGroups]] batches its counts.
    */
  def findGroupsByIds(ids: List[Long]): Task[List[GroupRow]]

  def findGroupByInviteCode(code: String): Task[Option[GroupRow]]

  /** Rotates the group's invite code; the old one stops resolving the moment this returns. */
  def updateInviteCode(id: Long, code: String): Task[Unit]

  /** Renames the group. `nameNorm` is [[gathedge.shared.domain.Group.normalize]]'d for sorted/case-insensitive listing,
    * the same split `insertGroup` keeps between `name` and `nameNorm`.
    */
  def updateGroupName(id: Long, name: String, nameNorm: String): Task[Unit]

  /** Every group, with how many members and how many attached tags each has — what the browse page is built from. */
  def listGroups: Task[List[(GroupRow, Long, Long)]]

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

  def updateMemberRole(groupId: Long, userId: Long, role: String): Task[Long]

  def deleteMembership(groupId: Long, userId: Long): Task[Long]

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

  def updateGroupName(id: Long, name: String, nameNorm: String): RIO[GroupRepository, Unit] =
    ZIO.serviceWithZIO[GroupRepository](_.updateGroupName(id, name, nameNorm))

  def listGroups: RIO[GroupRepository, List[(GroupRow, Long, Long)]] =
    ZIO.serviceWithZIO[GroupRepository](_.listGroups)

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

  def updateMemberRole(groupId: Long, userId: Long, role: String): RIO[GroupRepository, Long] =
    ZIO.serviceWithZIO[GroupRepository](_.updateMemberRole(groupId, userId, role))

  def deleteMembership(groupId: Long, userId: Long): RIO[GroupRepository, Long] =
    ZIO.serviceWithZIO[GroupRepository](_.deleteMembership(groupId, userId))

  def tagsOfGroup(groupId: Long): RIO[GroupRepository, List[(TagRow, Long, Option[UserRow])]] =
    ZIO.serviceWithZIO[GroupRepository](_.tagsOfGroup(groupId))

  def delete(id: Long): RIO[GroupRepository, Long] =
    ZIO.serviceWithZIO[GroupRepository](_.delete(id))

  val live: ZLayer[DataSource, Nothing, GroupRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): GroupRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, GroupRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): GroupRepository
  )
}

final class GroupRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
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
    val q = quote(groups.filter(_.id == lift(id)).update(_.inviteCode -> lift(code)))
    logged(run(ctx.run(q)).unit)(_ => s"groups.updateInviteCode id=$id")
  }

  def updateGroupName(id: Long, name: String, nameNorm: String): Task[Unit] = {
    val q = quote(groups.filter(_.id == lift(id)).update(_.name -> lift(name), _.nameNorm -> lift(nameNorm)))
    logged(run(ctx.run(q)).unit)(_ => s"groups.updateName id=$id")
  }

  def listGroups: Task[List[(GroupRow, Long, Long)]] = {
    val allGroups    = quote(groups.sortBy(_.nameNorm)(using Ord.asc))
    val memberCounts = quote(members.groupBy(_.groupId).map { case (groupId, rows) => (groupId, rows.size) })
    // Only tags actually attached to a group are counted, so the `WHERE group_id IS NOT NULL` happens before the
    // group-by rather than after — an ungrouped tag has no row here at all.
    val tagCounts    = quote {
      tags.filter(_.groupId.isDefined).groupBy(_.groupId).map { case (groupId, rows) => (groupId, rows.size) }
    }
    val listed       = for {
      rows          <- run(ctx.run(allGroups))
      memberCounted <- run(ctx.run(memberCounts))
      tagCounted    <- run(ctx.run(tagCounts))
      byMember       = memberCounted.toMap
      byTag          = tagCounted.flatMap { case (groupId, count) => groupId.map(_ -> count) }.toMap
    } yield rows.map(group => (group, byMember.getOrElse(group.id, 0L), byTag.getOrElse(group.id, 0L)))
    logged(listed)(rows => s"groups.list rows=${rows.size}")
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

  def updateMemberRole(groupId: Long, userId: Long, role: String): Task[Long] = {
    val q = quote {
      members.filter(row => row.groupId == lift(groupId) && row.userId == lift(userId)).update(_.role -> lift(role))
    }
    logged(run(ctx.run(q)))(rows => s"groupMembers.updateRole group=$groupId user=$userId rows=$rows")
  }

  def deleteMembership(groupId: Long, userId: Long): Task[Long] = {
    val q = quote(members.filter(row => row.groupId == lift(groupId) && row.userId == lift(userId)).delete)
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
