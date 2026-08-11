package webapp1.frontend.listing

import webapp1.frontend.components.SortHeader
import webapp1.shared.dto.{Paging, UserSort}
import zio.test._

/** Which listing changes are worth a step of their own in the browser's history.
  *
  * `App` asks exactly this question before every write to the address bar: a refinement replaces the entry, everything
  * else pushes one. Get it wrong in one direction and the back button walks through the letters of a search term; get
  * it wrong in the other and it leaves the screen while a filter is still on it.
  */
object UserQuerySpec extends ZIOSpecDefault {

  private val bob = UserQuery(search = "bob")

  def spec = {
    suite("UserQuery")(
      test("typing a term out further refines it") {
        assertTrue(
          UserQuery(search = "bo").refines(UserQuery(search = "b")),
          bob.refines(UserQuery(search = "bo")),
          // Not only longer: correcting a term is the same pause in the same search.
          UserQuery(search = "rob").refines(bob),
        )
      },
      test("the first search and the cleared box are not refinements") {
        assertTrue(
          !bob.refines(UserQuery()),
          !UserQuery().refines(bob),
        )
      },
      test("a page, a size or a column is never a refinement, whatever the search says") {
        assertTrue(
          !bob.copy(page = 2).refines(bob),
          !bob.copy(pageSize = 50).refines(bob),
          !bob.copy(sort = SortHeader.Sort.descending(UserSort.email)).refines(bob),
          // Same query, so there is nothing to return to either.
          !bob.refines(bob),
        )
      },
      // Every writer but "turn the page" goes through `reset`, because page 4 of the old listing says nothing about
      // the new one — and the first page is one, not zero.
      test("any change but a page turn returns to the first page") {
        assertTrue(
          UserQuery(page = 4).reset(_.copy(search = "bob")) == UserQuery(search = "bob"),
          UserQuery(page = 4).reset(_.copy(search = "bob")).page == Paging.firstPage,
          UserQuery.default.page == Paging.firstPage,
        )
      },
    )
  }
}
