package gathedge.frontend.listing

import gathedge.frontend.components.SortHeader
import gathedge.shared.dto.{AllGameSort, Paging}
import zio.test._

/** [[AllGameQuery]]'s `refines`/`reset` rules, mirroring [[MyPlayQuerySpec]] — the same "does this change deserve its
  * own step in the browser's history" question, for the games listing.
  */
object AllGameQuerySpec extends ZIOSpecDefault {

  private val quiz = AllGameQuery(search = "quiz")

  def spec = {
    suite("AllGameQuery")(
      test("typing a term out further refines it") {
        assertTrue(
          AllGameQuery(search = "qu").refines(AllGameQuery(search = "q")),
          quiz.refines(AllGameQuery(search = "qui")),
        )
      },
      test("the first search and the cleared box are not refinements") {
        assertTrue(
          !quiz.refines(AllGameQuery()),
          !AllGameQuery().refines(quiz),
        )
      },
      test("a page, a size, a column or the favorites toggle is never a refinement, whatever the search says") {
        assertTrue(
          !quiz.copy(page = 2).refines(quiz),
          !quiz.copy(pageSize = 50).refines(quiz),
          !quiz.copy(sort = SortHeader.Sort.descending(AllGameSort.name)).refines(quiz),
          !quiz.copy(favoritesOnly = true).refines(quiz),
          !quiz.refines(quiz),
        )
      },
      test("the favorites toggle survives reset but returns to the first page") {
        assertTrue(
          AllGameQuery(page = 4).reset(_.copy(favoritesOnly = true)) == AllGameQuery(favoritesOnly = true)
        )
      },
      test("any change but a page turn returns to the first page") {
        assertTrue(
          AllGameQuery(page = 4).reset(_.copy(search = "quiz")) == AllGameQuery(search = "quiz"),
          AllGameQuery(page = 4).reset(_.copy(search = "quiz")).page == Paging.firstPage,
          AllGameQuery.default.page == Paging.firstPage,
        )
      },
    )
  }
}
