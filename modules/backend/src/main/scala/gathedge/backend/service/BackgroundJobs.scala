package gathedge.backend.service

import gathedge.shared.dto.JobStatus
import zio.*

import java.util.concurrent.TimeUnit

/** What the daemon fibers forked at startup are doing.
  *
  * `SessionReaper` and the rate-limiter pruner are `.forever` loops that log and are never heard from again: if one
  * dies, or quietly fails every pass, nothing anywhere says so. Each pass now reports itself here, and the system
  * overview shows the result — a `lastRunAt` an hour older than the interval is the signal that something is wrong, and
  * there was previously no way to see it short of reading the log.
  *
  * State is a `Ref`, per process, like the rate limiter's. It answers "is this instance healthy", which is a question
  * about this instance.
  */
trait BackgroundJobs {

  /** Declares a job before it has run, so the overview lists it as pending rather than omitting it. */
  def register(name: String, interval: Duration): UIO[Unit]

  def recordSuccess(name: String, outcome: String): UIO[Unit]
  def recordFailure(name: String, error: String): UIO[Unit]
  def statuses: UIO[List[JobStatus]]
}

object BackgroundJobs {
  def register(name: String, interval: Duration): URIO[BackgroundJobs, Unit] =
    ZIO.serviceWithZIO[BackgroundJobs](_.register(name, interval))

  def recordSuccess(name: String, outcome: String): URIO[BackgroundJobs, Unit] =
    ZIO.serviceWithZIO[BackgroundJobs](_.recordSuccess(name, outcome))

  def recordFailure(name: String, error: String): URIO[BackgroundJobs, Unit] =
    ZIO.serviceWithZIO[BackgroundJobs](_.recordFailure(name, error))

  def statuses: URIO[BackgroundJobs, List[JobStatus]] =
    ZIO.serviceWithZIO[BackgroundJobs](_.statuses)

  val live: ULayer[BackgroundJobs] = ZLayer.fromZIO(
    Ref.make(Map.empty[String, JobStatus]).map(InMemoryBackgroundJobs(_))
  )
}

final case class InMemoryBackgroundJobs(state: Ref[Map[String, JobStatus]]) extends BackgroundJobs {

  def register(name: String, interval: Duration): UIO[Unit] = {
    state.update { jobs =>
      jobs.updatedWith(name) {
        // A second registration of the same name leaves whatever it has already recorded alone: `register` is a
        // declaration, not a reset.
        case Some(existing) =>
          Some(existing.copy(intervalMinutes = interval.toMinutes))
        case None           =>
          Some(JobStatus(name, interval.toMinutes, None, None, None))
      }
    }
  }

  private def update(name: String)(f: (JobStatus, Long) => JobStatus): UIO[Unit] = {
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _   <- state.update { jobs =>
               val current = jobs.getOrElse(name, JobStatus(name, 0L, None, None, None))
               jobs.updated(name, f(current, now))
             }
    } yield ()
  }

  def recordSuccess(name: String, outcome: String): UIO[Unit] = {
    update(name)((job, now) => job.copy(lastRunAt = Some(now), lastOutcome = Some(outcome), lastError = None))
  }

  /** `lastRunAt` moves for a failed pass too: the loop did run, and the interesting fact is that it ran and failed.
    * `lastError` is what distinguishes the two, and it is cleared by the next success.
    */
  def recordFailure(name: String, error: String): UIO[Unit] = {
    update(name)((job, now) => job.copy(lastRunAt = Some(now), lastOutcome = Some("failed"), lastError = Some(error)))
  }

  def statuses: UIO[List[JobStatus]] = state.get.map(_.values.toList.sortBy(_.name))
}
