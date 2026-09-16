package gathedge.backend.service

import gathedge.backend.config.AppConfig
import gathedge.backend.db.{UsageEventRepository, UsageEventRow}
import gathedge.backend.http.RouteSupport
import gathedge.backend.security.SessionAuth
import io.opentelemetry.api.trace.{SpanContext, SpanKind}
import zio.*
import zio.http.{Request, Status}
import zio.telemetry.opentelemetry.tracing.Tracing

import java.util.concurrent.TimeUnit

/** Where every request lands a `usage_events` row, from `RouteSupport.usageTracking`.
  *
  * The write is '''off the request path'''. `record` resolves the cheap, pure parts of the row (method, route, status,
  * client address, session id, timestamp) and hands them to a bounded queue; a single background fiber drains the queue
  * '''a batch at a time''', does the one database-backed step — the session-cookie lookup, once per distinct cookie in
  * the batch — and one batched insert. The request returns as soon as the event is enqueued. A full queue makes
  * `record` wait for space rather than dropping the event, so a database that cannot keep up slows callers a little
  * instead of losing usage history.
  *
  * The session-cookie lookup on the drain fiber is the one place a caller is resolved a second time:
  * `authenticated`/`optionalUser` already do it for the handler, but the tracking aspect runs outside any one route
  * file's environment. `None` for a missing or invalid cookie — the same as an anonymous request — never fails the call
  * it is recording.
  *
  * '''Tracing:''' `record` captures the request's `SpanContext` (the `SpanKind.SERVER` span from
  * `RouteSupport.serverSpan`) and carries it on the queued event. The drain fiber opens a `SpanKind.CONSUMER` span
  * around the write, in its own trace, with a span link back to that request span — the OpenTelemetry way to show
  * async, triggered-by work. With no Java agent the tracer is a no-op and this is all free.
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
    traceLink: Option[SpanContext],
  )

  /** Scoped: it forks the drain fiber into the layer's scope, so the fiber is interrupted when the application shuts
    * down. The queue tail is lost on shutdown, which is acceptable — these rows are statistics, and a failed write is
    * already swallowed.
    */
  val live: URLayer[AuthService & AppConfig & UsageEventRepository & Tracing, UsageTracker] = {
    ZLayer.scoped {
      for {
        authService <- ZIO.service[AuthService]
        config      <- ZIO.service[AppConfig]
        repo        <- ZIO.service[UsageEventRepository]
        tracing     <- ZIO.service[Tracing]
        queue       <- Queue.bounded[PendingEvent](config.app.usageEventQueueCapacity)
        tracker      = UsageTrackerLive(authService, config, repo, tracing, queue)
        _           <- tracker.drainLoop.forkScoped
      } yield tracker
    }
  }
}

final case class UsageTrackerLive(
  authService: AuthService,
  config: AppConfig,
  repo: UsageEventRepository,
  tracing: Tracing,
  queue: Queue[UsageTracker.PendingEvent],
) extends UsageTracker {

  /** Enqueue and return. `Queue.bounded`'s `offer` suspends only while the queue is full, so under normal load this
    * does not wait; a full queue backpressures the caller rather than dropping the event.
    */
  def record(request: Request, status: Status): UIO[Unit] = {
    for {
      spanContext <- tracing.getCurrentSpanContextUnsafe
      now         <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _           <- queue.offer(
                       UsageTracker.PendingEvent(
                         method = request.method.toString,
                         route = RouteSupport.normalizeRoute(request),
                         status = status.code,
                         sessionId = SessionAuth.sessionIdFrom(request),
                         ip = RouteSupport.clientAddress(request, config.app.trustedProxyHops),
                         at = now,
                         traceLink = Option.when(spanContext.isValid)(spanContext),
                       )
                     )
    } yield ()
  }

  /** Takes everything the queue is holding, resolves the accounts behind the session cookies, and writes the rows. A
    * failed step is logged and swallowed — the same rule `AuditTrail.record`'s DB half follows — so the fiber survives
    * a database blip and keeps draining, and a row the database refuses loses only itself.
    *
    * '''One batch, not one row.''' `take` blocks until there is something to do, then `takeUpTo` sweeps up whatever
    * else arrived while the last batch was being written, and the lot goes in as one JDBC batch. The session lookups
    * are deduplicated across the batch too: a burst from one reader carries one cookie, so it costs one lookup however
    * many requests it was. Under load this collapses two statements per request towards two per batch; on an idle
    * server a batch is one event and nothing changes.
    *
    * The lookups and insert run inside a `SpanKind.CONSUMER` span, linked to the request spans the batch came from
    * (`traceLink`). The span is its trace's root — the write outlives the request, so a link, not a parent edge — and
    * the Java agent's SQL span nests under it. No agent: the span call is a no-op.
    */
  private[service] val drainLoop: UIO[Nothing] = {
    val batch = {
      for {
        first <- queue.take
        rest  <- queue.takeUpTo(config.app.usageEventQueueCapacity)
        events = first :: rest.toList
        _     <- tracing.span("usage_events insert", SpanKind.CONSUMER, links = events.flatMap(_.traceLink)) {
                   for {
                     // One lookup per distinct cookie, not per event: the map is what every row in the batch reads.
                     userIds <- ZIO
                                  .foreach(events.flatMap(_.sessionId).distinct) { sid =>
                                    authService.currentUser(sid).map(user => sid -> user.map(_.id))
                                  }
                                  .map(_.toMap)
                     rows     = events.map { pending =>
                                  UsageEventRow(
                                    id = 0L,
                                    createdAt = pending.at,
                                    method = pending.method,
                                    route = pending.route,
                                    status = pending.status,
                                    userId = pending.sessionId.flatMap(userIds.getOrElse(_, None)),
                                    ip = pending.ip,
                                  )
                                }
                     _       <- repo.insertAll(rows).catchAllCause { cause =>
                                  // A batch fails as a unit, so one unwritable row would take the rest of the batch's
                                  // history with it. The retry costs a statement per row, but only on the rare batch
                                  // that failed, and keeps the rule that a bad row loses itself and nothing else.
                                  ZIO.logWarningCause("Usage-event batch failed; retrying row by row", cause) *>
                                    ZIO.foreachDiscard(rows) { row =>
                                      repo.insert(row).catchAllCause { rowCause =>
                                        ZIO.logWarningCause("Could not record usage event", rowCause)
                                      }
                                    }
                                }
                   } yield ()
                 }
      } yield ()
    }
    batch.catchAllCause(cause => ZIO.logWarningCause("Could not record usage events", cause)).forever
  }
}
