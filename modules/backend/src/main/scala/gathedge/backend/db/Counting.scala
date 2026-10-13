package gathedge.backend.db

import io.getquill.*

/** Row counts that render as a bare `COUNT(*)`.
  *
  * '''Never count with Quill's `.size`.''' It renders `COUNT(alias.*)`, a count of whole-row values, and Postgres must
  * build each row to evaluate it. That rules out an index-only scan: on the dictionary's `words` table it read the
  * whole 830 MB heap where `COUNT(*)` reads 44 MB of index, 416 ms against 48 ms with a warm cache. `COUNT(*)` names no
  * column, so a covering index answers it. The SQL fragment below is the only way to make Quill emit it.
  */
object Counting {

  /** `COUNT(*)` as a value: the count column of a `groupBy`. */
  inline def countStar: Long = sql"COUNT(*)".pure.as[Long]

  extension [T](inline q: Query[T]) {

    /** `SELECT COUNT(*) FROM ...`, one row. Run it through `QuillRepository.counted`, or take `.value` to use it as a
      * scalar subquery.
      */
    inline def countRows: Query[Long] = quote(q.map(_ => countStar))
  }

  extension [T](q: DynamicQuery[T]) {

    /** [[countRows]] for a query built with `dynamicQuerySchema`. */
    def countRows: DynamicQuery[Long] = q.map(_ => quote(countStar))
  }
}
