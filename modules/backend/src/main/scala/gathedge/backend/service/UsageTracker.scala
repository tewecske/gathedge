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
  * The write is '''off the request path'''. `record` resolves the cheap, pure parts of the row (method, route, status,
  * client address, session id, timestamp) and hands them to a bounded queue; a single background fiber drains the
  * queue, does the one database-backed step — the session-cookie lookup — and the insert. The request returns as soon
  * as the event is enqueued. A full queue makes `record` wait for space rather than dropping the event, so a database
  * that cannot keep up slows callers a little instead of losing usage history.
  *
  * The session-cookie lookup on the drain fiber is the one place a caller is resolved a second time:
  * `authenticated`/`optionalUser` already do it for the handler, but the tracking aspect runs outside any one route
  * file's environment. `None` for a missing or invalid cookie — the same as an anonymous request — never fails the call
  * it is recording.
  */
trait UsageTracker {
  def record(request: Request, status: Status): UIO[Unit]
}

object UsageTracker {
  def record(request: Request, status: Status): URIO[UsageTracker, Unit] =
    ZIO.serviceWithZIO[UsageTracker](_.record(request, status))

  /** The request-path part of a `usage_events` row: everything that is a pure read of the request. The session-cookie
    * lookup that turns `sessionId` into a `userId` is left for the drain fiber, so the request path touches no
    * database.
    */
  private[service] final case class PendingEvent(
    method: String,
    route: String,
    status: Int,
    sessionId: Option[String],
    ip: Option[String],
    at: Long,
  )

  /** Scoped: it forks the drain fiber into the layer's scope, so the fiber is interrupted when the application shuts
    * down. The queue tail is lost on shutdown, which is acceptable — these rows are statistics, and a failed write is
    * already swallowed.
    */
  val live: URLayer[AuthService & AppConfig & UsageEventRepository, UsageTracker] = {
    ZLayer.scoped {
      for {
        authService <- ZIO.service[AuthService]
        config      <- ZIO.service[AppConfig]
        repo        <- ZIO.service[UsageEventRepository]
        queue       <- Queue.bounded[PendingEvent](config.app.usageEventQueueCapacity)
        tracker      = UsageTrackerLive(authService, config, repo, queue)
        _           <- tracker.drainLoop.forkScoped
      } yield tracker
    }
  }
}

final case class UsageTrackerLive(
  authService: AuthService,
  config: AppConfig,
  repo: UsageEventRepository,
  queue: Queue[UsageTracker.PendingEvent],
) extends UsageTracker {

  /** Enqueue and return. `Queue.bounded`'s `offer` suspends only while the queue is full, so under normal load this
    * does not wait; a full queue backpressures the caller rather than dropping the event.
    */
  def record(request: Request, status: Status): UIO[Unit] = {
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _   <- queue.offer(
               UsageTracker.PendingEvent(
                 method = request.method.toString,
                 route = RouteSupport.normalizeRoute(request),
                 status = status.code,
                 sessionId = SessionAuth.sessionIdFrom(request),
                 ip = RouteSupport.clientAddress(request, config.app.trustedProxyHops),
                 at = now,
               )
             )
    } yield ()
  }

  /** Takes one pending event at a time, resolves the account behind the session cookie, and writes the row. A failed
    * step is logged and swallowed — the same rule `AuditTrail.record`'s DB half follows — so the fiber survives a
    * database blip and keeps draining.
    */
  private[service] val drainLoop: UIO[Nothing] = {
    val one = {
      for {
        pending <- queue.take
        userId  <- pending.sessionId match {
                     case None      => ZIO.succeed(None)
                     case Some(sid) => authService.currentUser(sid).map(_.map(_.id))
                   }
        _       <- repo.insert(
                     UsageEventRow(
                       id = 0L,
                       createdAt = pending.at,
                       method = pending.method,
                       route = pending.route,
                       status = pending.status,
                       userId = userId,
                       ip = pending.ip,
                     )
                   )
      } yield ()
    }
    one.catchAllCause(cause => ZIO.logWarningCause("Could not record usage event", cause)).forever
  }
}
