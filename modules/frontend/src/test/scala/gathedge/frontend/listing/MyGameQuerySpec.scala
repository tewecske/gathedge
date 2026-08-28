package gathedge.frontend.listing

import gathedge.frontend.components.SortHeader
import gathedge.shared.dto.{MyGameSort, Paging}
import zio.test._

/** [[MyGameQuery]]'s `refines`/`reset` rules, mirroring [[MyPlayQuerySpec]] — the same "does this change deserve its
  * own step in the browser's history" question, for the owner's own games listing.
  */
object MyGameQuerySpec extends ZIOSpecDefault {

  private val quiz = MyGameQuery(search = "quiz")

  def spec = {
    suite("MyGameQuery")(
      test("typing a term out further refines it") {
        assertTrue(
          MyGameQuery(search = "qu").refines(MyGameQuery(search = "q")),
          quiz.refines(MyGameQuery(search = "qui")),
        )
      },
      test("the first search and the cleared box are not refinements") {
        assertTrue(
          !quiz.refines(MyGameQuery()),
          !MyGameQuery().refines(quiz),
        )
      },
      test("a page, a size or a column is never a refinement, whatever the search says") {
        assertTrue(
          !quiz.copy(page = 2).refines(quiz),
          !quiz.copy(pageSize = 50).refines(quiz),
          !quiz.copy(sort = SortHeader.Sort.descending(MyGameSort.name)).refines(quiz),
          !quiz.refines(quiz),
        )
      },
      test("any change but a page turn returns to the first page") {
        assertTrue(
          MyGameQuery(page = 4).reset(_.copy(search = "quiz")) == MyGameQuery(search = "quiz"),
          MyGameQuery(page = 4).reset(_.copy(search = "quiz")).page == Paging.firstPage,
          MyGameQuery.default.page == Paging.firstPage,
        )
      },
    )
  }
}
