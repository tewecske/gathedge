package webapp1.frontend.components

import webapp1.shared.dto.{SortDirection, UserSort}
import zio.test._

/** The three-state cycle a column heading walks through, and what each state asks the server for.
  *
  * The third state is the one worth pinning: without a way back to *unordered* there is no way back to the listing's
  * own order, and for the audit trail that order — most recent first — is what the page is opened to see.
  */
object SortHeaderSpec extends ZIOSpecDefault {

  import SortHeader.Sort

  def spec = {
    suite("SortHeader")(
      test("clicking one column cycles ascending, descending, off") {
        val first  = SortHeader.next(Sort.unsorted, UserSort.email)
        val second = SortHeader.next(first, UserSort.email)
        val third  = SortHeader.next(second, UserSort.email)

        assertTrue(
          first == Sort.ascending(UserSort.email),
          second == Sort.descending(UserSort.email),
          third == Sort.unsorted,
        )
      },
      // Carrying the previous column's direction over would mean a click can land on descending without ever having
      // shown ascending, which is not what the glyph on the heading just said would happen.
      test("moving to another column starts it ascending, whichever way the last one was") {
        assertTrue(
          SortHeader.next(Sort.descending(UserSort.email), UserSort.created) == Sort.ascending(UserSort.created),
          SortHeader.next(Sort.ascending(UserSort.email), UserSort.created) == Sort.ascending(UserSort.created),
        )
      },
      test("a column reports its own direction and nothing about the others") {
        val sorted = Sort.descending(UserSort.email)

        assertTrue(
          sorted.directionOf(UserSort.email).contains(true),
          sorted.directionOf(UserSort.created).isEmpty,
          Sort.ascending(UserSort.email).directionOf(UserSort.email).contains(false),
          Sort.unsorted.directionOf(UserSort.email).isEmpty,
        )
      },
      // An unsorted listing sends no `dir` either: a direction with no column to apply it to would be noise in the
      // request and a parameter the server would have to decide what to ignore.
      test("only a sorted column puts a direction on the wire") {
        assertTrue(
          Sort.unsorted.wire.isEmpty,
          Sort.ascending(UserSort.email).wire.contains(SortDirection.ascending),
          Sort.descending(UserSort.email).wire.contains(SortDirection.descending),
        )
      },
    )
  }
}
