package gathedge.shared.domain

import zio.test.*

/** The ordering both tag dropdowns render in: the reader's own tags before anyone else's, alphabetically within each
  * group. Stated here as a table because `WordCollect` and `WordsPage` both trust it rather than re-deriving it.
  */
object TagSpec extends ZIOSpecDefault {

  def spec = {
    suite("Tag")(
      test("own tags sort before anyone else's, alphabetically within each group") {
        val mineB   = Tag(1L, "b-tag", 2L, ownedByMe = true)
        val mineA   = Tag(2L, "a-tag", 0L, ownedByMe = true)
        val othersC = Tag(3L, "c-tag", 5L, ownedByMe = false)
        val othersA = Tag(4L, "a-other", 1L, ownedByMe = false)
        assertTrue(
          Tag.sorted(List(othersC, mineB, othersA, mineA)) == List(mineA, mineB, othersA, othersC)
        )
      },
      test("sorting is case-insensitive, and a reader with no tags of their own gets an empty group") {
        val upper = Tag(1L, "Berlin", 0L, ownedByMe = false)
        val lower = Tag(2L, "amsterdam", 0L, ownedByMe = false)
        assertTrue(
          Tag.sorted(List(upper, lower)) == List(lower, upper),
          Tag.sorted(Nil) == Nil,
        )
      },
    )
  }
}
