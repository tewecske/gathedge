package gathedge.backend.service

import gathedge.backend.db.{UsageEventRepository, UserRepository}
import gathedge.shared.dto.{RouteUsage, SuspiciousUser}
import zio.*

import java.util.concurrent.TimeUnit

/** What `usage_events` answers on demand: which routes get used, and which accounts look unusual.
  *
  * Both are computed live over a caller-chosen window rather than memoized like `SystemService.dbStats` — a window
  * bounded by an index, not a `COUNT(*)` over the whole schema — and rather than by a background job writing flag rows:
  * an administrator picking a window is the point, and a job would have to pick one for them.
  *
  * "Suspicious" is two independent threshold checks, not a score: an account tripping either one is reported, with both
  * counts alongside so an administrator can see which. Neither threshold claims to know what's actually wrong — it's a
  * starting point for a look, not an accusation.
  */
trait UsageStatsService {
  def topRoutes(windowHours: Int): UIO[List[RouteUsage]]
  def suspiciousUsers(windowHours: Int, actionThreshold: Int, ipThreshold: Int): UIO[List[SuspiciousUser]]
}

object UsageStatsService {
  def topRoutes(windowHours: Int): URIO[UsageStatsService, List[RouteUsage]] =
    ZIO.serviceWithZIO[UsageStatsService](_.topRoutes(windowHours))

  def suspiciousUsers(
    windowHours: Int,
    actionThreshold: Int,
    ipThreshold: Int,
  ): URIO[UsageStatsService, List[SuspiciousUser]] =
    ZIO.serviceWithZIO[UsageStatsService](_.suspiciousUsers(windowHours, actionThreshold, ipThreshold))

  /** What an omitted query parameter falls back to. The two thresholds are a starting point, not a tuned policy — a
    * deployment that wants different ones adjusts the query, there being no evidence yet for a config setting nobody
    * has asked to change.
    */
  val defaultWindowHours: Int     = 24
  val defaultActionThreshold: Int = 500
  val defaultIpThreshold: Int     = 5

  val live: URLayer[UsageEventRepository & UserRepository, UsageStatsService] =
    ZLayer.fromFunction(UsageStatsServiceLive.apply)
}

final case class UsageStatsServiceLive(repo: UsageEventRepository, userRepo: UserRepository) extends UsageStatsService {

  private def since(windowHours: Int): UIO[Long] = {
    Clock.currentTime(TimeUnit.MILLISECONDS).map(_ - windowHours.max(1).toLong * 60L * 60L * 1000L)
  }

  def topRoutes(windowHours: Int): UIO[List[RouteUsage]] = {
    (
      for {
        cutoff <- since(windowHours)
        counts <- repo.countsByRoute(cutoff)
      } yield counts.map { case (method, route, count) => RouteUsage(method, route, count) }.sortBy(-_.count)
    ).orDie
  }

  def suspiciousUsers(windowHours: Int, actionThreshold: Int, ipThreshold: Int): UIO[List[SuspiciousUser]] = {
    (
      for {
        cutoff       <- since(windowHours)
        actionCounts <- repo.countsByUser(cutoff)
        ipCounts     <- repo.distinctIpCountsByUser(cutoff)
        actionByUser  = actionCounts.toMap
        ipByUser      = ipCounts.toMap
        flaggedIds    = {
          (actionCounts.collect { case (userId, count) if count >= actionThreshold => userId } ++
            ipCounts.collect { case (userId, count) if count >= ipThreshold => userId }).distinct
        }
        flagged      <- ZIO.foreach(flaggedIds) { userId =>
                          userRepo.findById(userId).map { user =>
                            SuspiciousUser(
                              userId = userId,
                              email = user.flatMap(_.email),
                              eventCount = actionByUser.getOrElse(userId, 0L),
                              distinctIpCount = ipByUser.getOrElse(userId, 0),
                            )
                          }
                        }
      } yield flagged.sortBy(user => -user.eventCount)
    ).orDie
  }
}
