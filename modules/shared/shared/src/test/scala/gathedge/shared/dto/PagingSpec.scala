package gathedge.shared.dto

import zio.test.*

/** The arithmetic both ends of a paged listing do on the same numbers.
  *
  * It lives in `shared` for the same reason the object does: the browser draws its buttons off `pageCount` while the
  * server turns `page` into an `OFFSET`, and the two disagreeing is a page of rows nobody asked for.
  */
object PagingSpec extends ZIOSpecDefault {

  def spec = {
    suite("Paging")(
      test("a partial last page still counts as a page") {
        assertTrue(
          Paging.pageCount(0L, 20) == 0,
          Paging.pageCount(1L, 20) == 1,
          Paging.pageCount(20L, 20) == 1,
          Paging.pageCount(21L, 20) == 2,
          Paging.pageCount(45L, 20) == 3,
        )
      },
      // The cap is what stops `pageSize` reaching the SQL `LIMIT` unbounded, so it is the assertion that matters here.
      test("a page size is clamped to what the control can actually offer") {
        assertTrue(
          Paging.boundedPageSize(None) == Paging.defaultPageSize,
          Paging.pageSizes.forall(size => Paging.boundedPageSize(Some(size)) == size),
          Paging.boundedPageSize(Some(100000)) == Paging.maxPageSize,
          Paging.boundedPageSize(Some(0)) == 1,
          Paging.boundedPageSize(Some(-5)) == 1,
          Paging.maxPageSize == Paging.pageSizes.max,
          Paging.pageSizes.contains(Paging.defaultPageSize),
        )
      },
      // Deliberately *not* clamped at the top: the server has not counted anything yet when it reads this, and an
      // empty page with an honest total is what lets the browser correct itself.
      //
      // One-based: `page=2` is the second page, in the URL and in the request alike, and the first page skips nothing.
      test("a page number is floored at the first page and left alone above it") {
        assertTrue(
          Paging.firstPage == 1,
          Paging.boundedPage(None) == Paging.firstPage,
          Paging.boundedPage(Some(0)) == Paging.firstPage,
          Paging.boundedPage(Some(-1)) == Paging.firstPage,
          Paging.boundedPage(Some(7)) == 7,
          Paging.offset(Paging.firstPage, 20) == 0,
          Paging.offset(2, 20) == 20,
          Paging.offset(4, 20) == 60,
          Paging.offset(-3, 20) == 0,
        )
      },
      test("consecutive offsets partition the rows, last page short") {
        val total  = 45L
        val size   = 20
        val ranges = {
          (Paging.firstPage to Paging.pageCount(total, size)).toList
            .map(page => (Paging.offset(page, size), math.min(Paging.offset(page, size) + size, total.toInt)))
        }

        assertTrue(
          ranges == List((0, 20), (20, 40), (40, 45)),
          ranges.map { case (from, to) => to - from }.sum == total.toInt,
        )
      },
      test("a sort direction round-trips through the wire vocabulary") {
        assertTrue(
          SortDirection.of(true) == SortDirection.descending,
          SortDirection.of(false) == SortDirection.ascending,
          SortDirection.isDescending(Some(SortDirection.descending)),
          !SortDirection.isDescending(Some(SortDirection.ascending)),
          // Anything the server does not recognise is ascending rather than a failure — see `AdminEndpoints`.
          !SortDirection.isDescending(None),
          !SortDirection.isDescending(Some("sideways")),
        )
      },
      test("the sortable columns are distinct and non-empty") {
        assertTrue(
          UserSort.all == UserSort.all.distinct,
          AuditSort.all == AuditSort.all.distinct,
          (UserSort.all ++ AuditSort.all).forall(_.nonEmpty),
        )
      },
    )
  }
}
