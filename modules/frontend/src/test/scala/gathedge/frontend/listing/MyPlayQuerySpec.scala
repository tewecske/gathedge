package gathedge.frontend.listing

import gathedge.frontend.components.SortHeader
import gathedge.shared.dto.{GamePlaySort, Paging}
import zio.test._

/** [[MyPlayQuery]]'s `refines`/`reset` rules, mirroring [[GamePlayQuerySpec]] — the same "does this change deserve its
  * own step in the browser's history" question, for the caller's own cross-game history.
  */
object MyPlayQuerySpec extends ZIOSpecDefault {

  private val quiz = MyPlayQuery(search = "quiz")

  def spec = {
    suite("MyPlayQuery")(
      test("typing a term out further refines it") {
        assertTrue(
          MyPlayQuery(search = "qu").refines(MyPlayQuery(search = "q")),
          quiz.refines(MyPlayQuery(search = "qui")),
        )
      },
      test("the first search and the cleared box are not refinements") {
        assertTrue(
          !quiz.refines(MyPlayQuery()),
          !MyPlayQuery().refines(quiz),
        )
      },
      test("a page, a size or a column is never a refinement, whatever the search says") {
        assertTrue(
          !quiz.copy(page = 2).refines(quiz),
          !quiz.copy(pageSize = 50).refines(quiz),
          !quiz.copy(sort = SortHeader.Sort.descending(GamePlaySort.score)).refines(quiz),
          !quiz.refines(quiz),
        )
      },
      test("any change but a page turn returns to the first page") {
        assertTrue(
          MyPlayQuery(page = 4).reset(_.copy(search = "quiz")) == MyPlayQuery(search = "quiz"),
          MyPlayQuery(page = 4).reset(_.copy(search = "quiz")).page == Paging.firstPage,
          MyPlayQuery.default.page == Paging.firstPage,
        )
      },
    )
  }
}
