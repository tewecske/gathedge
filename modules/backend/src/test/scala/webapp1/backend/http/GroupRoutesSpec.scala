package webapp1.backend.http

import webapp1.backend.TestDataSource
import webapp1.backend.config.AppConfig
import webapp1.backend.db.{
  GroupInvitationRepository,
  GroupInvitationRow,
  SqliteGroupInvitationRepository,
  SqliteGroupMemberRepository,
  SqliteGroupPairRepository,
  SqliteGroupRepository,
  SqliteSessionRepository,
  SqliteUserRepository,
}
import webapp1.backend.security.PasswordHasher
import webapp1.backend.service.{
  AuthService,
  AuthServiceLive,
  EmailSender,
  GroupService,
  GroupServiceLive,
  InMemoryRateLimiter,
}
import webapp1.shared.domain.{Group, GroupRole}
import webapp1.shared.dto.{ErrorResponse, InviteMemberRequest, UpdateRoleRequest}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.util.concurrent.TimeUnit

import RouteRunner.{orDieWithFailure, runRoutes, withCsrf, withSession}

/** `GroupRoutes` had no route-level coverage at all: every `GroupFailure -> Response` mapping was only ever asserted
  * through the browser. These pin the status codes the frontend branches on, so a change to the shared mapping in
  * [[ApiFailures]] — or to the statuses `GroupEndpoints` binds them to — can't silently flatten a 403 into a 400.
  */
object GroupRoutesSpec extends ZIOSpecDefault {

  private val repoLayer = {
    TestDataSource.sqlite >>> (
      SqliteGroupRepository.live ++ SqliteGroupMemberRepository.live ++ SqliteGroupPairRepository.live ++
        SqliteGroupInvitationRepository.live ++ SqliteUserRepository.live ++ SqliteSessionRepository.live
    )
  }

  private val layer: ZLayer[Any, Throwable, AuthService & GroupService & GroupInvitationRepository] = {
    repoLayer ++
      ((repoLayer ++ PasswordHasher.live ++ InMemoryRateLimiter.live) >>> AuthServiceLive.live) ++
      ((repoLayer ++ EmailSender.live ++ AppConfig.live) >>> GroupServiceLive.live)
  }

  /** Signs a user up and returns both halves the routes need: the user id the services key on, and the session id that
    * goes into the cookie.
    */
  private def signUp(email: String): ZIO[AuthService, Nothing, (Long, String)] = {
    ZIO.serviceWithZIO[AuthService] { service =>
      orDieWithFailure(service.signup(email, "password123")).map { case (user, sessionId) =>
        (user.id, sessionId)
      }
    }
  }

  private def createGroup(userId: Long, name: String): ZIO[GroupService, Nothing, Group] = {
    ZIO.serviceWithZIO[GroupService](service => orDieWithFailure(service.createGroup(userId, name)))
  }

  /** Joins a user to a group in a given role. Invitation tokens are only ever emailed, never returned by the API, so
    * the row goes in directly and is then accepted through the service.
    */
  private def joinGroup(
    group: Group,
    invitedBy: Long,
    userId: Long,
    email: String,
    role: GroupRole,
  ): ZIO[GroupService & GroupInvitationRepository, Nothing, Unit] = {
    for {
      invitationRepo <- ZIO.service[GroupInvitationRepository]
      groupService <- ZIO.service[GroupService]
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      token = s"token-for-$email"
      _ <- orDieWithFailure(
        invitationRepo.insert(
          GroupInvitationRow(
            0L,
            group.id,
            email,
            GroupRole.toDbString(role),
            token,
            invitedBy,
            now,
            now + 1.day.toMillis,
            None,
          )
        )
      )
      _ <- orDieWithFailure(groupService.acceptInvitation(userId, email, token))
    } yield ()
  }

  private def errorMessage(response: Response): ZIO[Any, Nothing, String] = {
    response
      .body
      .asString
      .orDie
      .map(body => body.fromJson[ErrorResponse].fold(err => s"undecodable error body: $err", _.message))
  }

  def spec = {
    suite("GroupRoutes")(
      test("a signed-in non-member gets 403, not 404 — the group exists, they just aren't in it") {
        for {
          owner <- signUp("owner@example.com")
          outsider <- signUp("outsider@example.com")
          group <- createGroup(owner._1, "Acme")
          response <- runRoutes(GroupRoutes.routes, withSession(Request.get(s"/api/groups/${group.id}"), outsider._2))
          message <- errorMessage(response)
        } yield assertTrue(response.status == Status.Forbidden, message == "You are not a member of this group")
      },
      test("a group that doesn't exist is 404") {
        for {
          session <- signUp("nobody@example.com")
          response <- runRoutes(GroupRoutes.routes, withSession(Request.get("/api/groups/9999"), session._2))
        } yield assertTrue(response.status == Status.NotFound)
      },
      test("a read-only member cannot add a pair") {
        for {
          owner <- signUp("pair-owner@example.com")
          reader <- signUp("reader@example.com")
          group <- createGroup(owner._1, "Readers")
          _ <- joinGroup(group, owner._1, reader._1, "reader@example.com", GroupRole.ReadOnly)
          body = Body.fromString("""{"source":"a","target":"b"}""")
          request = withCsrf(withSession(Request.post(s"/api/groups/${group.id}/pairs", body), reader._2))
          response <- runRoutes(GroupRoutes.routes, request)
          message <- errorMessage(response)
        } yield assertTrue(response.status == Status.Forbidden, message == "Your role in this group is read-only")
      },
      test("a non-admin member cannot invite") {
        for {
          owner <- signUp("invite-owner@example.com")
          member <- signUp("member@example.com")
          group <- createGroup(owner._1, "Invites")
          _ <- joinGroup(group, owner._1, member._1, "member@example.com", GroupRole.ReadWrite)
          body = Body.fromString(InviteMemberRequest("someone@example.com", GroupRole.ReadOnly).toJson)
          request = withCsrf(withSession(Request.post(s"/api/groups/${group.id}/invitations", body), member._2))
          response <- runRoutes(GroupRoutes.routes, request)
          message <- errorMessage(response)
        } yield assertTrue(response.status == Status.Forbidden, message == "Only a group administrator can do this")
      },
      test("demoting the only administrator is a 409, not a 400") {
        for {
          owner <- signUp("last-admin@example.com")
          group <- createGroup(owner._1, "Solo")
          body = Body.fromString(UpdateRoleRequest(GroupRole.ReadOnly).toJson)
          request = withCsrf(withSession(Request.put(s"/api/groups/${group.id}/members/${owner._1}", body), owner._2))
          response <- runRoutes(GroupRoutes.routes, request)
        } yield assertTrue(response.status == Status.Conflict)
      },
      test("a validation failure keeps its per-field errors") {
        for {
          session <- signUp("blank-name@example.com")
          request = withCsrf(withSession(Request.post("/api/groups", Body.fromString("""{"name":"  "}""")), session._2))
          response <- runRoutes(GroupRoutes.routes, request)
          raw <- response.body.asString.orDie
          decoded = raw.fromJson[ErrorResponse]
        } yield assertTrue(
          response.status == Status.BadRequest,
          decoded.map(_.fieldErrors.contains("name")) == Right(true),
        )
      },
    ).provide(layer, Scope.default) @@ TestAspect.timeout(60.seconds)
  }
}
