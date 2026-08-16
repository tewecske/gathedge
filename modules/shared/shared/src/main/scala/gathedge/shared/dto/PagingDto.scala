package gathedge.shared.dto

import gathedge.shared.domain.User
import zio.json.*

/** One page of a listing, the vocabulary for asking for it, and the arithmetic both ends do on the answer.
  *
  * Paging is done by the database, not by the browser: a listing endpoint answers with the rows of one page plus how
  * many rows match in total, and the page control counts its buttons off that total. The alternative — hand the whole
  * table over and slice it client-side — is what these replaced, and it stops working at exactly the size where paging
  * starts to matter.
  *
  * Everything here is shared so the two ends cannot disagree about what a page is: the same `defaultPageSize` seeds the
  * dropdown and fills in an absent `pageSize` query parameter, and the same [[Paging.pageCount]] draws the buttons and
  * could bound a request.
  */

/** One page of accounts. `total` counts what matches the *filter*, not what the table holds, since that is what decides
  * how many pages there are.
  */
final case class UserPage(items: List[User], total: Long) derives JsonCodec

/** One page of the audit trail, counted the same way. */
final case class AuditPage(items: List[AuditEntry], total: Long) derives JsonCodec

/** How big a page is, and what a caller may ask for.
  *
  * The cap is the real protection rather than tidiness: `pageSize` reaches the SQL `LIMIT` and `page` the `OFFSET`, so
  * an unbounded one is a caller's licence to ask for the whole table in one request. Both are clamped rather than
  * rejected — a nonsensical page number is a stale link or a hand-edited URL, and answering the nearest sensible page
  * is more useful than a 400.
  */
object Paging {

  val defaultPageSize: Int = 20

  /** What the page-size dropdown offers. The largest is also the cap, so no caller can ask for more than the UI can. */
  val pageSizes: List[Int] = List(10, 20, 50, 100)

  val maxPageSize: Int = pageSizes.max

  /** The index of the first page — **one**, in the URL, in the API and in the browser alike.
    *
    * It is a named constant rather than a literal because it is the one number every layer has to agree on: the address
    * bar says `page=2` for the second page, the request repeats it, and only [[offset]] turns it into rows to skip. A
    * zero-based index anywhere else would show `page=1` for the second page, which is what a reader reports as a bug.
    */
  val firstPage: Int = 1

  def boundedPageSize(requested: Option[Int]): Int = {
    requested.getOrElse(defaultPageSize).max(1).min(maxPageSize)
  }

  /** One-based, and never below the first page. Past the end is *not* clamped here — the server does not know the total
    * until it has counted, and an empty page with an honest total is what lets the browser correct itself.
    */
  def boundedPage(requested: Option[Int]): Int = {
    requested.getOrElse(firstPage).max(firstPage)
  }

  /** How many rows to skip to reach `page`. The only place the one-based index becomes an offset. */
  def offset(page: Int, pageSize: Int): Int = {
    (boundedPage(Some(page)) - firstPage) * pageSize
  }

  /** How many pages `total` rows fill, a partial last page included. Zero rows is zero pages, not one. */
  def pageCount(total: Long, pageSize: Int): Int = {
    if (total <= 0L || pageSize <= 0)
      0
    else
      ((total + pageSize - 1) / pageSize).toInt
  }
}

/** Which way a listing is ordered. Plain strings for the same reason as [[AuditAction]]: they travel as query
  * parameters, and an unrecognised one falls back to the listing's own order rather than failing the request.
  */
object SortDirection {
  val ascending: String  = "asc"
  val descending: String = "desc"

  def isDescending(value: Option[String]): Boolean = value.contains(descending)

  def of(descending: Boolean): String = {
    if (descending)
      SortDirection.descending
    else
      ascending
  }
}

/** The columns `GET /api/admin/users` will order by.
  *
  * Not every column on the screen is here: the sign-in column shows whether the rate limiter is currently holding
  * failures against an address, which lives in a process-local `Ref` and is joined in by the browser, so there is no
  * `ORDER BY` that could produce it.
  */
object UserSort {
  val email: String     = "email"
  val admin: String     = "admin"
  val confirmed: String = "confirmed"
  val created: String   = "created"

  val all: List[String] = List(email, admin, confirmed, created)
}

/** The columns `GET /api/admin/audit` will order by. Target and detail are absent: the first is two fields rendered as
  * one, and neither has an order anybody would ask for.
  */
object AuditSort {
  val occurredAt: String = "occurredAt"
  val actor: String      = "actor"
  val action: String     = "action"
  val ip: String         = "ip"

  val all: List[String] = List(occurredAt, actor, action, ip)
}
