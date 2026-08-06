package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

trait GroupRepository {
  def insert(name: String, createdAt: Long): Task[GroupRow]

  /** Creates the group and its creator's membership row atomically. A group with no members can be neither seen nor
    * deleted, so the two writes must not be able to come apart.
    */
  def insertWithCreator(name: String, creatorId: Long, creatorRole: String, createdAt: Long): Task[GroupRow]
  def findById(id: Long): Task[Option[GroupRow]]

  /** Groups the user is a member of, paired with their role in each. */
  def listForUser(userId: Long): Task[List[(GroupRow, String)]]
  def delete(id: Long): Task[Unit]
}

object GroupRepository {
  def insert(name: String, createdAt: Long): RIO[GroupRepository, GroupRow] =
    ZIO.serviceWithZIO[GroupRepository](_.insert(name, createdAt))

  def insertWithCreator(
    name: String,
    creatorId: Long,
    creatorRole: String,
    createdAt: Long,
  ): RIO[GroupRepository, GroupRow] =
    ZIO.serviceWithZIO[GroupRepository](_.insertWithCreator(name, creatorId, creatorRole, createdAt))

  def findById(id: Long): RIO[GroupRepository, Option[GroupRow]] =
    ZIO.serviceWithZIO[GroupRepository](_.findById(id))

  def listForUser(userId: Long): RIO[GroupRepository, List[(GroupRow, String)]] =
    ZIO.serviceWithZIO[GroupRepository](_.listForUser(userId))

  def delete(id: Long): RIO[GroupRepository, Unit] =
    ZIO.serviceWithZIO[GroupRepository](_.delete(id))
}

final class GroupRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with GroupRepository {
  import ctx._

  private inline def groupsQ  = quote(querySchema[GroupRow]("groups"))
  private inline def membersQ = quote(querySchema[GroupMemberRow]("group_members"))

  def insert(name: String, createdAt: Long): Task[GroupRow] = {
    val row = GroupRow(0L, name, createdAt)
    logged(run(ctx.run(quote(groupsQ.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))) {
      group =>
        s"groups.insert id=${group.id}"
    }
  }

  def insertWithCreator(name: String, creatorId: Long, creatorRole: String, createdAt: Long): Task[GroupRow] = {
    val row     = GroupRow(0L, name, createdAt)
    // Both writes or neither: a group whose creator's membership row is missing is invisible in
    // "my groups" and, because every operation on it requires membership, impossible to delete.
    // `group_members` is written here rather than through GroupMemberRepository on purpose — see
    // the note on QuillRepository about transactions not composing across repositories.
    val queries = {
      for {
        id <- ctx.run(quote(groupsQ.insertValue(lift(row)).returningGenerated(_.id)))
        _  <- ctx.run(quote(membersQ.insertValue(lift(GroupMemberRow(id, creatorId, creatorRole, createdAt)))))
      } yield id
    }
    logged(transaction(queries).map(id => row.copy(id = id))) { group =>
      s"groups.insertWithCreator id=${group.id} creatorId=$creatorId role=$creatorRole"
    }
  }

  def findById(id: Long): Task[Option[GroupRow]] = {
    logged(run(ctx.run(quote(groupsQ.filter(_.id == lift(id))))).map(_.headOption)) { found =>
      s"groups.findById id=$id found=${found.isDefined}"
    }
  }

  def listForUser(userId: Long): Task[List[(GroupRow, String)]] = {
    val q = quote {
      for {
        m <- membersQ.filter(_.userId == lift(userId))
        g <- groupsQ.join(g => g.id == m.groupId)
      } yield (g, m.role)
    }
    logged(run(ctx.run(q)))(rows => s"groups.listForUser userId=$userId count=${rows.size}")
  }

  def delete(id: Long): Task[Unit] = {
    logged(run(ctx.run(quote(groupsQ.filter(_.id == lift(id)).delete))).unit)(_ => s"groups.delete id=$id")
  }
}

object PostgresGroupRepository {
  val live: ZLayer[DataSource, Nothing, GroupRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): GroupRepository
  )
}

/** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
object SqliteGroupRepository {
  val test: ZLayer[DataSource, Nothing, GroupRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): GroupRepository
  )
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

object GroupMemberRepository {
  def addMember(groupId: Long, userId: Long, role: String, joinedAt: Long): RIO[GroupMemberRepository, Unit] =
    ZIO.serviceWithZIO[GroupMemberRepository](_.addMember(groupId, userId, role, joinedAt))

  def findRole(groupId: Long, userId: Long): RIO[GroupMemberRepository, Option[String]] =
    ZIO.serviceWithZIO[GroupMemberRepository](_.findRole(groupId, userId))

  def listForGroup(groupId: Long): RIO[GroupMemberRepository, List[(GroupMemberRow, String)]] =
    ZIO.serviceWithZIO[GroupMemberRepository](_.listForGroup(groupId))

  def removeMember(groupId: Long, userId: Long): RIO[GroupMemberRepository, Unit] =
    ZIO.serviceWithZIO[GroupMemberRepository](_.removeMember(groupId, userId))

  def updateRole(groupId: Long, userId: Long, role: String): RIO[GroupMemberRepository, Unit] =
    ZIO.serviceWithZIO[GroupMemberRepository](_.updateRole(groupId, userId, role))

  def countAdmins(groupId: Long): RIO[GroupMemberRepository, Long] =
    ZIO.serviceWithZIO[GroupMemberRepository](_.countAdmins(groupId))
}

final class GroupMemberRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with GroupMemberRepository {
  import ctx._

  private inline def members = quote(querySchema[GroupMemberRow]("group_members"))
  private inline def usersQ  = quote(querySchema[UserRow]("users"))

  def addMember(groupId: Long, userId: Long, role: String, joinedAt: Long): Task[Unit] = {
    val q = quote(members.insertValue(lift(GroupMemberRow(groupId, userId, role, joinedAt))))
    logged(run(ctx.run(q)).unit)(_ => s"groupMembers.addMember groupId=$groupId userId=$userId role=$role")
  }

  def findRole(groupId: Long, userId: Long): Task[Option[String]] = {
    val q = quote(members.filter(m => m.groupId == lift(groupId) && m.userId == lift(userId)).map(_.role))
    logged(run(ctx.run(q)).map(_.headOption)) { role =>
      s"groupMembers.findRole groupId=$groupId userId=$userId role=${role.getOrElse("none")}"
    }
  }

  def listForGroup(groupId: Long): Task[List[(GroupMemberRow, String)]] = {
    val q = quote {
      for {
        m <- members.filter(_.groupId == lift(groupId))
        u <- usersQ.join(u => u.id == m.userId)
      } yield (m, u.email)
    }
    logged(run(ctx.run(q)))(rows => s"groupMembers.listForGroup groupId=$groupId count=${rows.size}")
  }

  def removeMember(groupId: Long, userId: Long): Task[Unit] = {
    val q = quote(members.filter(m => m.groupId == lift(groupId) && m.userId == lift(userId)).delete)
    logged(run(ctx.run(q)).unit)(_ => s"groupMembers.removeMember groupId=$groupId userId=$userId")
  }

  def updateRole(groupId: Long, userId: Long, role: String): Task[Unit] = {
    val q = quote {
      members.filter(m => m.groupId == lift(groupId) && m.userId == lift(userId)).update(_.role -> lift(role))
    }
    logged(run(ctx.run(q)).unit)(_ => s"groupMembers.updateRole groupId=$groupId userId=$userId role=$role")
  }

  def countAdmins(groupId: Long): Task[Long] = {
    val q = quote(members.filter(m => m.groupId == lift(groupId) && m.role == lift("admin")).size)
    logged(run(ctx.run(q)))(count => s"groupMembers.countAdmins groupId=$groupId count=$count")
  }
}

object PostgresGroupMemberRepository {
  val live: ZLayer[DataSource, Nothing, GroupMemberRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupMemberRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): GroupMemberRepository
  )
}

/** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
object SqliteGroupMemberRepository {
  val test: ZLayer[DataSource, Nothing, GroupMemberRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupMemberRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): GroupMemberRepository
  )
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

object GroupPairRepository {
  def insert(
    groupId: Long,
    source: String,
    target: String,
    createdBy: Long,
    createdByEmail: String,
    createdAt: Long,
  ): RIO[GroupPairRepository, GroupPairRow] =
    ZIO.serviceWithZIO[GroupPairRepository](_.insert(groupId, source, target, createdBy, createdByEmail, createdAt))

  def listForGroup(groupId: Long): RIO[GroupPairRepository, List[GroupPairRow]] =
    ZIO.serviceWithZIO[GroupPairRepository](_.listForGroup(groupId))
}

final class GroupPairRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with GroupPairRepository {
  import ctx._

  private inline def pairs = quote(querySchema[GroupPairRow]("group_pairs"))

  def insert(
    groupId: Long,
    source: String,
    target: String,
    createdBy: Long,
    createdByEmail: String,
    createdAt: Long,
  ): Task[GroupPairRow] = {
    val row = GroupPairRow(0L, groupId, source, target, createdBy, createdByEmail, createdAt)
    logged(run(ctx.run(quote(pairs.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))) {
      pair =>
        s"groupPairs.insert id=${pair.id} groupId=$groupId createdBy=$createdBy"
    }
  }

  def listForGroup(groupId: Long): Task[List[GroupPairRow]] = {
    logged(run(ctx.run(quote(pairs.filter(_.groupId == lift(groupId)).sortBy(_.createdAt))))) { rows =>
      s"groupPairs.listForGroup groupId=$groupId count=${rows.size}"
    }
  }
}

object PostgresGroupPairRepository {
  val live: ZLayer[DataSource, Nothing, GroupPairRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupPairRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): GroupPairRepository
  )
}

/** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
object SqliteGroupPairRepository {
  val test: ZLayer[DataSource, Nothing, GroupPairRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupPairRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): GroupPairRepository
  )
}

trait GroupInvitationRepository {
  def insert(row: GroupInvitationRow): Task[GroupInvitationRow]
  def findByToken(token: String): Task[Option[GroupInvitationRow]]
  def markAccepted(token: String, acceptedAt: Long): Task[Unit]
}

object GroupInvitationRepository {
  def insert(row: GroupInvitationRow): RIO[GroupInvitationRepository, GroupInvitationRow] =
    ZIO.serviceWithZIO[GroupInvitationRepository](_.insert(row))

  def findByToken(token: String): RIO[GroupInvitationRepository, Option[GroupInvitationRow]] =
    ZIO.serviceWithZIO[GroupInvitationRepository](_.findByToken(token))

  def markAccepted(token: String, acceptedAt: Long): RIO[GroupInvitationRepository, Unit] =
    ZIO.serviceWithZIO[GroupInvitationRepository](_.markAccepted(token, acceptedAt))
}

/** Invitation tokens are bearer credentials, so no log line here carries one — see [[QuillRepository.logged]]. */
final class GroupInvitationRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with GroupInvitationRepository {
  import ctx._

  private inline def invitations = quote(querySchema[GroupInvitationRow]("group_invitations"))

  def insert(row: GroupInvitationRow): Task[GroupInvitationRow] = {
    val inserted = run(ctx.run(quote(invitations.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id))) { invitation =>
      s"groupInvitations.insert id=${invitation.id} groupId=${row.groupId} role=${row.role}"
    }
  }

  def findByToken(token: String): Task[Option[GroupInvitationRow]] = {
    logged(run(ctx.run(quote(invitations.filter(_.token == lift(token))))).map(_.headOption)) { found =>
      s"groupInvitations.findByToken found=${found.isDefined}"
    }
  }

  def markAccepted(token: String, acceptedAt: Long): Task[Unit] = {
    val q = quote(invitations.filter(_.token == lift(token)).update(_.acceptedAt -> lift(Option(acceptedAt))))
    logged(run(ctx.run(q)).unit)(_ => "groupInvitations.markAccepted")
  }
}

object PostgresGroupInvitationRepository {
  val live: ZLayer[DataSource, Nothing, GroupInvitationRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupInvitationRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): GroupInvitationRepository
  )
}

/** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
object SqliteGroupInvitationRepository {
  val test: ZLayer[DataSource, Nothing, GroupInvitationRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupInvitationRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): GroupInvitationRepository
  )
}
