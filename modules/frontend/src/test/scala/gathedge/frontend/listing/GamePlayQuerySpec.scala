package gathedge.frontend.listing

import gathedge.frontend.components.SortHeader
import gathedge.shared.dto.{GamePlaySort, Paging}
import zio.test._

/** [[GamePlayQuery]]'s `refines`/`reset` rules, mirroring [[UserQuerySpec]] — the same "does this change deserve its
  * own step in the browser's history" question, for the owner-facing results listing.
  */
object GamePlayQuerySpec extends ZIOSpecDefault {

  private val alice = GamePlayQuery(search = "alice")

  def spec = {
    suite("GamePlayQuery")(
      test("typing a term out further refines it") {
        assertTrue(
          GamePlayQuery(search = "al").refines(GamePlayQuery(search = "a")),
          alice.refines(GamePlayQuery(search = "al")),
        )
      },
      test("the first search and the cleared box are not refinements") {
        assertTrue(
          !alice.refines(GamePlayQuery()),
          !GamePlayQuery().refines(alice),
        )
      },
      test("a page, a size or a column is never a refinement, whatever the search says") {
        assertTrue(
          !alice.copy(page = 2).refines(alice),
          !alice.copy(pageSize = 50).refines(alice),
          !alice.copy(sort = SortHeader.Sort.descending(GamePlaySort.score)).refines(alice),
          !alice.refines(alice),
        )
      },
      test("any change but a page turn returns to the first page") {
        assertTrue(
          GamePlayQuery(page = 4).reset(_.copy(search = "alice")) == GamePlayQuery(search = "alice"),
          GamePlayQuery(page = 4).reset(_.copy(search = "alice")).page == Paging.firstPage,
          GamePlayQuery.default.page == Paging.firstPage,
        )
      },
    )
  }
}
