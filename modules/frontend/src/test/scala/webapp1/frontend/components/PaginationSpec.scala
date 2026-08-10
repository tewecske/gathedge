package webapp1.frontend.components

import zio.test._

/** The arithmetic behind the page buttons, which is the part of paging that can be wrong without looking wrong.
  *
  * All of it is pure by design: the component reads signals and writes observers but decides nothing, so every rule
  * about which rows a page holds and which buttons it offers can be stated here rather than driven through jsdom.
  */
object PaginationSpec extends ZIOSpecDefault {

  private val rows = (1 to 45).toList

  def spec = {
    suite("Pagination")(
      test("a partial last page still counts as a page") {
        assertTrue(
          Pagination.pageCount(0, 20) == 0,
          Pagination.pageCount(1, 20) == 1,
          Pagination.pageCount(20, 20) == 1,
          Pagination.pageCount(21, 20) == 2,
          Pagination.pageCount(45, 20) == 3,
        )
      },
      test("an empty listing has no last page to jump to") {
        assertTrue(
          Pagination.lastPage(0, 20) == 0,
          Pagination.lastPage(45, 20) == 2,
          Pagination.lastPage(45, 100) == 0,
        )
      },
      // The reason paging needs no correcting write-back when a list shrinks under it.
      test("a page index past the end clamps to the last page rather than showing nothing") {
        assertTrue(
          Pagination.clampPage(9, 3) == 2,
          Pagination.clampPage(-1, 3) == 0,
          Pagination.clampPage(5, 0) == 0,
          Pagination.slice(rows, page = 9, pageSize = 20) == (41 to 45).toList,
        )
      },
      test("consecutive pages partition the rows, last one short") {
        val pages = (0 until Pagination.pageCount(rows.size, 20)).toList.map(Pagination.slice(rows, _, 20))

        assertTrue(
          pages.map(_.size) == List(20, 20, 5),
          pages.flatten == rows,
        )
      },
      test("a short listing offers every page and elides nothing") {
        assertTrue(
          Pagination.pageItems(pageCount = 0, current = 0) == List.empty[Option[Int]],
          Pagination.pageItems(pageCount = 3, current = 1) == List(Some(0), Some(1), Some(2)),
        )
      },
      // The first and last page stay reachable in one click however long the listing gets; the middle collapses.
      test("a long listing keeps the ends and the current page's neighbourhood") {
        assertTrue(
          Pagination.pageItems(pageCount = 20, current = 0) == List(Some(0), Some(1), None, Some(19)),
          Pagination.pageItems(pageCount = 20, current = 10) ==
            List(Some(0), None, Some(9), Some(10), Some(11), None, Some(19)),
          Pagination.pageItems(pageCount = 20, current = 19) == List(Some(0), None, Some(18), Some(19)),
        )
      },
      // Whatever the elision does, the row of buttons has to stay a row of buttons: ascending, no repeats, nothing
      // pointing off the end, and the page being read always among them.
      test("every window is ordered, distinct, in range, and contains the current page") {
        val windows = {
          for {
            count   <- (0 to 25).toList
            current <- (0 until count).toList
          } yield (count, current, Pagination.pageItems(count, current).flatten)
        }

        assertTrue(
          windows.forall { case (_, _, pages) => pages == pages.distinct },
          windows.forall { case (_, _, pages) => pages == pages.sorted },
          windows.forall { case (count, _, pages) => pages.forall(page => page >= 0 && page < count) },
          windows.forall { case (_, current, pages) => pages.contains(current) },
        )
      },
    )
  }
}
