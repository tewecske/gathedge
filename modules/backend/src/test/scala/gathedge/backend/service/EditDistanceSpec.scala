package gathedge.backend.service

import zio.test.*

object EditDistanceSpec extends ZIOSpecDefault {

  def spec = {
    suite("EditDistance")(
      test("identical strings are distance 0") {
        assertTrue(EditDistance.within("haus", "haus", 2).contains(0))
      },
      test("one substitution is distance 1") {
        assertTrue(EditDistance.within("haus", "haus".updated(1, 'o'), 2).contains(1))
      },
      test("one insertion is distance 1") {
        assertTrue(EditDistance.within("haus", "hause", 2).contains(1))
      },
      test("one deletion is distance 1") {
        assertTrue(EditDistance.within("haus", "hau", 2).contains(1))
      },
      // The whole reason this is Damerau-Levenshtein and not plain Levenshtein: a swapped adjacent pair is the
      // single most common OCR misread, and plain Levenshtein scores it 2, not 1.
      test("an adjacent transposition is distance 1, not 2") {
        assertTrue(EditDistance.within("haus", "hasu", 2).contains(1))
      },
      test("a distance beyond the bound is None") {
        assertTrue(EditDistance.within("haus", "xyzzy", 2).isEmpty)
      },
      test("the length-gap short-circuit agrees with the DP result") {
        assertTrue(EditDistance.within("a", "abcd", 2).isEmpty)
      },
      test("empty strings are distance 0 from each other") {
        assertTrue(EditDistance.within("", "", 2).contains(0))
      },
      test("an empty string against a longer one is its length") {
        assertTrue(EditDistance.within("", "ab", 2).contains(2))
      },
    )
  }
}
