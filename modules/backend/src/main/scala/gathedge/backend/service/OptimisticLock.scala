package gathedge.backend.service

import zio.*

/** Shared reading of a version-guarded write's result.
  *
  * A guarded repository method filters its `UPDATE`/`DELETE` on `WHERE ... AND version = ?` and returns the rows it
  * touched. Zero rows has two meanings: the row is gone (or was never the caller's), or it is still there but its
  * `version` moved because somebody else wrote it between this request's read and this write. `resolve` re-checks
  * existence to tell the two apart, so a caller reports a stale write as a conflict and a missing row as not-found.
  */
object OptimisticLock {

  def resolve[E](rows: Long, stillExists: => UIO[Boolean], stale: E, missing: E): IO[E, Unit] = {
    if (rows > 0L)
      ZIO.unit
    else
      stillExists.flatMap(exists => ZIO.fail(if (exists) stale else missing))
  }
}
