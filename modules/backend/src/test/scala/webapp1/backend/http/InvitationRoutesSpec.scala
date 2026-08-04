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
  SqliteOAuthIdentityRepository,
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
import webapp1.shared.domain.{Group, GroupRole, InvitationInfo}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.util.concurrent.TimeUnit

import RouteRunner.{orDieWithFailure, runRoutes, withCsrf, withSession}

/** `InvitationRoutes` is the one route file that mixes a deliberately public endpoint with an authenticated one, and it
  * used to carry its own `GroupFailure -> Response` mapping with a `case _ =>` catch-all. These lock in both halves:
  * viewing an invitation stays reachable without a session, and accepting one does not.
  */
object InvitationRoutesSpec extends ZIOSpecDefault {

  private val repoLayer = {
    TestDataSource.sqlite >>> (
      SqliteGroupRepository.live ++ SqliteGroupMemberRepository.live ++ SqliteGroupPairRepository.live ++
        SqliteGroupInvitationRepository.live ++ SqliteUserRepository.live ++ SqliteSessionRepository.live ++
        SqliteOAuthIdentityRepository.live
    )
  }

  private val layer: ZLayer[Any, Throwable, AuthService & GroupService & GroupInvitationRepository] = {
    repoLayer ++
      ((repoLayer ++ PasswordHasher.live ++ InMemoryRateLimiter.live) >>> AuthServiceLive.live) ++
      ((repoLayer ++ EmailSender.live ++ AppConfig.live) >>> GroupServiceLive.live)
  }

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

  /** Tokens are emailed, never returned by the API, so a test that needs one puts the row in itself. */
  private def invite(
    group: Group,
    invitedBy: Long,
    email: String,
    token: String,
  ): ZIO[GroupInvitationRepository, Nothing, Unit] = {
    for {
      invitationRepo <- ZIO.service[GroupInvitationRepository]
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _ <- orDieWithFailure(
        invitationRepo.insert(
          GroupInvitationRow(
            0L,
            group.id,
            email,
            GroupRole.toDbString(GroupRole.ReadWrite),
            token,
            invitedBy,
            now,
            now + 1.day.toMillis,
            None,
          )
        )
      )
    } yield ()
  }

  def spec = {
    suite("InvitationRoutes")(
      // The visitor hasn't signed up yet when they follow the emailed link, so this endpoint must not require a
      // session — the token is the secret.
      test("viewing an invitation works with no session at all") {
        for {
          owner <- signUp("inviter@example.com")
          group <- createGroup(owner._1, "Book Club")
          _ <- invite(group, owner._1, "guest@example.com", "public-token")
          response <- runRoutes(InvitationRoutes.routes, Request.get("/api/invitations/public-token"))
          raw <- response.body.asString.orDie
        } yield assertTrue(
          response.status == Status.Ok,
          raw.fromJson[InvitationInfo].map(_.groupName) == Right("Book Club"),
          raw.fromJson[InvitationInfo].map(_.email) == Right("guest@example.com"),
        )
      },
      test("an unknown token is a 400, not a 500") {
        for {
          response <- runRoutes(InvitationRoutes.routes, Request.get("/api/invitations/no-such-token"))
        } yield assertTrue(response.status == Status.BadRequest)
      },
      test("accepting without the CSRF header is refused before the session is even looked at") {
        for {
          response <- runRoutes(InvitationRoutes.routes, Request.post("/api/invitations/t/accept", Body.empty))
        } yield assertTrue(response.status == Status.Forbidden)
      },
      test("accepting without a session is unauthorized") {
        for {
          response <- runRoutes(
            InvitationRoutes.routes,
            withCsrf(Request.post("/api/invitations/t/accept", Body.empty)),
          )
        } yield assertTrue(response.status == Status.Unauthorized)
      },
      test("accepting an invitation addressed to somebody else is refused") {
        for {
          owner <- signUp("owner2@example.com")
          intruder <- signUp("intruder@example.com")
          group <- createGroup(owner._1, "Private")
          _ <- invite(group, owner._1, "wanted@example.com", "someone-elses-token")
          request = withCsrf(
            withSession(Request.post("/api/invitations/someone-elses-token/accept", Body.empty), intruder._2)
          )
          response <- runRoutes(InvitationRoutes.routes, request)
        } yield assertTrue(response.status == Status.BadRequest)
      },
      test("the invitee accepts and lands in the group") {
        for {
          owner <- signUp("owner3@example.com")
          invitee <- signUp("invitee@example.com")
          group <- createGroup(owner._1, "Welcoming")
          _ <- invite(group, owner._1, "invitee@example.com", "good-token")
          request = withCsrf(withSession(Request.post("/api/invitations/good-token/accept", Body.empty), invitee._2))
          response <- runRoutes(InvitationRoutes.routes, request)
          raw <- response.body.asString.orDie
        } yield assertTrue(
          response.status == Status.Ok,
          raw.fromJson[Group].map(_.name) == Right("Welcoming"),
          raw.fromJson[Group].map(_.myRole) == Right(GroupRole.ReadWrite),
        )
      },
      test("a token can only be accepted once") {
        for {
          owner <- signUp("owner4@example.com")
          invitee <- signUp("twice@example.com")
          group <- createGroup(owner._1, "Once")
          _ <- invite(group, owner._1, "twice@example.com", "single-use")
          request = withCsrf(withSession(Request.post("/api/invitations/single-use/accept", Body.empty), invitee._2))
          first <- runRoutes(InvitationRoutes.routes, request)
          second <- runRoutes(InvitationRoutes.routes, request)
        } yield assertTrue(first.status == Status.Ok, second.status == Status.BadRequest)
      },
    ).provide(layer, Scope.default) @@ TestAspect.timeout(60.seconds)
  }
}
