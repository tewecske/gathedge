package gathedge.backend.service

import gathedge.backend.config.AppConfig
import gathedge.backend.db.{UsageEventRepository, UsageEventRow}
import gathedge.backend.http.RouteSupport
import gathedge.backend.security.SessionAuth
import zio.*
import zio.http.{Request, Status}

import java.util.concurrent.TimeUnit

/** Where every request lands a `usage_events` row, from `RouteSupport.usageTracking`.
  *
  * The one place a caller is resolved a second time: `authenticated`/`optionalUser` already do it for the handler, but
  * the tracking aspect runs outside any one route file's environment, so it repeats the session-cookie lookup rather
  * than sharing theirs. `None` for a missing or invalid cookie — the same as an anonymous request — never fails the
  * call it is recording.
  */
trait UsageTracker {
  def record(request: Request, status: Status): UIO[Unit]
}

object UsageTracker {
  def record(request: Request, status: Status): URIO[UsageTracker, Unit] =
    ZIO.serviceWithZIO[UsageTracker](_.record(request, status))

  val live: URLayer[AuthService & AppConfig & UsageEventRepository, UsageTracker] =
    ZLayer.fromFunction(UsageTrackerLive.apply)
}

final case class UsageTrackerLive(authService: AuthService, config: AppConfig, repo: UsageEventRepository)
    extends UsageTracker {

  /** A failed write is logged and swallowed, the same rule `AuditTrail.record`'s DB half follows: this runs after every
    * response has already been decided, so there is nothing left to fail it into.
    */
  def record(request: Request, status: Status): UIO[Unit] = {
    (
      for {
        now    <- Clock.currentTime(TimeUnit.MILLISECONDS)
        userId <-
          SessionAuth.sessionIdFrom(request) match {
            case None      => ZIO.succeed(None)
            case Some(sid) => authService.currentUser(sid).map(_.map(_.id))
          }
        _      <- repo.insert(
                    UsageEventRow(
                      id = 0L,
                      createdAt = now,
                      method = request.method.toString,
                      route = RouteSupport.normalizeRoute(request),
                      status = status.code,
                      userId = userId,
                      ip = RouteSupport.clientAddress(request, config.app.trustedProxyHops),
                    )
                  )
      } yield ()
    ).catchAllCause(cause => ZIO.logWarningCause("Could not record usage event", cause))
  }
}
