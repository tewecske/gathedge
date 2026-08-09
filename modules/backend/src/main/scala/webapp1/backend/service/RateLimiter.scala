package webapp1.backend.service

import webapp1.shared.dto.RateLimitEntry
import zio.*

import java.util.concurrent.TimeUnit

/** The limiter reports its state as the `shared` [[RateLimitEntry]] rather than a backend twin of it: the only consumer
  * is the administrator's account view, and `AuthService` already answers in `shared` types (`domain.User`). One case
  * class means there is nothing to keep in step.
  */
trait RateLimiter {
  def isBlocked(key: String): UIO[Boolean]
  def recordFailure(key: String): UIO[Unit]

  /** Forgets the failures recorded for a key. Called after a successful authentication so earlier typos don't keep
    * counting towards a lockout for the rest of the window.
    */
  def clear(key: String): UIO[Unit]

  /** What is held against one key right now, whether or not it is blocked. For the administrator's account view. */
  def status(key: String): UIO[RateLimitEntry]

  /** Every key with at least one failure still inside the window, most attempts first.
    *
    * This is the whole of the limiter's state, and it is per-process: a second backend instance has its own, and a
    * restart empties it. `login_attempts` is the durable record; this is only ever "who is locked out at this moment".
    */
  def snapshot: UIO[List[RateLimitEntry]]

  /** Drops every key. Returns how many were held. */
  def clearAll: UIO[Long]

  /** Drops only the keys whose failures have all aged out of the window, returning how many went. This is what
    * [[runPruner]] does on its schedule and what the administrator's maintenance button does on demand; unlike
    * [[clearAll]] it unblocks nobody, since a key it removes was no longer blocking anything.
    */
  def pruneStale: UIO[Long]

  /** Background cleanup loop; fork as a daemon fiber once at startup. Reports each pass to [[BackgroundJobs]], so the
    * system overview can show that the fiber is still alive — a `.forever` loop that has quietly died looks exactly
    * like one that has nothing to do.
    */
  def runPruner: URIO[BackgroundJobs, Nothing]
}

object RateLimiter {
  val live: ULayer[RateLimiter] = ZLayer.fromZIO(
    Ref.make(Map.empty[String, Vector[Long]]).map(InMemoryRateLimiter(_))
  )

  def isBlocked(key: String): URIO[RateLimiter, Boolean] =
    ZIO.serviceWithZIO[RateLimiter](_.isBlocked(key))

  def clear(key: String): URIO[RateLimiter, Unit] =
    ZIO.serviceWithZIO[RateLimiter](_.clear(key))

  def status(key: String): URIO[RateLimiter, RateLimitEntry] =
    ZIO.serviceWithZIO[RateLimiter](_.status(key))

  def snapshot: URIO[RateLimiter, List[RateLimitEntry]] =
    ZIO.serviceWithZIO[RateLimiter](_.snapshot)

  def clearAll: URIO[RateLimiter, Long] =
    ZIO.serviceWithZIO[RateLimiter](_.clearAll)

  /** The policy the limiter enforces, so a screen can say "3 of 5 in the last 15 minutes" instead of hard-coding the
    * numbers next to a copy of them.
    */
  val maxAttempts: Int = InMemoryRateLimiter.maxAttempts
  val window: Duration = InMemoryRateLimiter.window

  val prunerJobName: String = "rate-limit-pruner"
}

/** Key namespaces. Limiting per account only would let one attacker spray a single password across many accounts
  * untouched, so callers check both dimensions and block if either trips.
  */
object RateLimitKey {
  def email(value: String): String = s"email:${value.trim.toLowerCase}"

  /** Keyed on the /64 for IPv6 and on the exact address for IPv4, which is what each of them actually costs an attacker
    * to change.
    *
    * A residential IPv6 customer is routinely delegated a /64 or shorter, so limiting per address hands out 2^64 fresh
    * budgets to anyone who can be bothered to bind a new one — the limit is enforcing nothing. A /64 is the smallest
    * unit that is not free to move within. IPv4 has no equivalent: addresses there are scarce enough that the address
    * itself is the unit, and aggregating to a prefix would sweep unrelated customers behind one NAT into a single
    * budget.
    */
  def ip(value: String): String = s"ip:${normalizeAddress(value)}"

  /** Collapses an IPv6 literal onto its /64 prefix, leaving anything else untouched.
    *
    * `InetAddress` does the parsing because a /64 is the first eight *bytes*, and the textual form cannot be cut at
    * four groups: `2001:db8::1` and `2001:db8::2` share a prefix that neither string shows. It is only called for
    * values containing a colon — a string with one can never be a hostname, so this cannot turn into a DNS lookup on
    * the request path.
    */
  private def normalizeAddress(value: String): String = {
    val trimmed = value.trim
    if (!trimmed.contains(":")) {
      trimmed
    } else {
      val prefix = {
        scala.util
          .Try(java.net.InetAddress.getByName(trimmed))
          .toOption
          .collect { case v6: java.net.Inet6Address =>
            v6.getAddress.take(8).map(byte => f"${byte & 0xff}%02x").grouped(2).map(_.mkString).mkString(":")
          }
      }
      prefix.map(p => s"$p::/64").getOrElse(trimmed.toLowerCase)
    }
  }

  /** Separate from [[email]] so asking for another verification link cannot burn an account's login budget, nor the
    * other way round.
    */
  def verification(value: String): String = s"verify:${value.trim.toLowerCase}"

  /** Separate from [[email]] for a sharper reason than [[verification]]'s: signup counts a *duplicate address* as a
    * failure, and the address it counts is one the caller chose. Sharing the namespace with [[email]] therefore let
    * anyone lock a known account out of signing in by attempting to register it five times — the victim's login budget
    * spent from an unauthenticated endpoint, by an attacker who only had to know the address.
    *
    * Signup keeps a budget of its own: answering "that address is taken" is an enumeration oracle this application
    * accepts by design (summary.md), and it still should not be free to probe at speed.
    */
  def signup(value: String): String = s"signup:${value.trim.toLowerCase}"
}

/** Per-key sliding-window limiter (5 failures / 15 min, per summary.md). In-process only — acceptable for a single
  * backend instance; would need a shared store (e.g. the DB) to hold across multiple instances.
  */
final case class InMemoryRateLimiter(state: Ref[Map[String, Vector[Long]]]) extends RateLimiter {
  import InMemoryRateLimiter._

  private def prune(attempts: Vector[Long], now: Long): Vector[Long] = {
    attempts.filter(t => now - t <= windowMillis)
  }

  private def entry(key: String, attempts: Vector[Long], now: Long): RateLimitEntry = {
    val live    = prune(attempts, now)
    val blocked = live.size >= maxAttempts
    RateLimitEntry(
      key = key,
      attempts = live.size,
      blocked = blocked,
      oldestAttemptAt = live.minOption,
      // Once the oldest failure ages out the key is one under the limit, so that is when it stops being blocked.
      retryAfterMillis = if (blocked) live.minOption.map(oldest => windowMillis - (now - oldest)).getOrElse(0L) else 0L,
    )
  }

  def isBlocked(key: String): UIO[Boolean] = {
    for {
      now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      attempts <- state.get.map(_.getOrElse(normalize(key), Vector.empty))
    } yield prune(attempts, now).size >= maxAttempts
  }

  def recordFailure(key: String): UIO[Unit] = {
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _   <- state.update { m =>
               val k      = normalize(key)
               val pruned = prune(m.getOrElse(k, Vector.empty), now)
               m.updated(k, pruned :+ now)
             }
    } yield ()
  }

  def clear(key: String): UIO[Unit] = {
    state.update(_ - normalize(key))
  }

  def status(key: String): UIO[RateLimitEntry] = {
    val normalized = normalize(key)
    for {
      now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      attempts <- state.get.map(_.getOrElse(normalized, Vector.empty))
    } yield entry(normalized, attempts, now)
  }

  def snapshot: UIO[List[RateLimitEntry]] = {
    for {
      now  <- Clock.currentTime(TimeUnit.MILLISECONDS)
      held <- state.get
      // The map is only pruned every 15 minutes, so it can hold keys whose failures have all aged out; a caller
      // reading "who is locked out" must not see those.
      alive = held.map { case (key, attempts) => entry(key, attempts, now) }.filter(_.attempts > 0)
    } yield alive.toList.sortBy(candidate => (-candidate.attempts, candidate.key))
  }

  def clearAll: UIO[Long] = {
    state.getAndSet(Map.empty).map(_.size.toLong)
  }

  def pruneStale: UIO[Long] = {
    for {
      now     <- Clock.currentTime(TimeUnit.MILLISECONDS)
      dropped <- state.modify { held =>
                   val kept = held.view.mapValues(prune(_, now)).filter(_._2.nonEmpty).toMap
                   ((held.size - kept.size).toLong, kept)
                 }
    } yield dropped
  }

  /** Drops keys with no attempts left in the window, bounding map growth from one-off email addresses. Runs forever —
    * fork as a daemon fiber at startup.
    */
  def runPruner: URIO[BackgroundJobs, Nothing] = {
    val once = {
      for {
        dropped <- pruneStale
        _       <- BackgroundJobs.recordSuccess(RateLimiter.prunerJobName, s"dropped $dropped stale key(s)")
        _       <- ZIO.sleep(pruneInterval)
      } yield ()
    }
    BackgroundJobs.register(RateLimiter.prunerJobName, pruneInterval) *> once.forever
  }
}

object InMemoryRateLimiter {
  val maxAttempts             = 5
  val window: Duration        = 15.minutes
  val windowMillis: Long      = window.toMillis
  val pruneInterval: Duration = 15.minutes

  private def normalize(key: String): String = key.trim.toLowerCase
}
