package gathedge.backend.service

/** Damerau-Levenshtein distance — insertions, deletions, substitutions, and adjacent transpositions each cost one —
  * used only by [[WordServiceLive.suggestionsFor]] to guess which dictionary word a bulk-upload OCR pass misread.
  * Transposition is included because a swapped adjacent pair ("wno" for "own") is the single most common OCR misread
  * plain Levenshtein would score as two edits instead of one, which is enough to miss it at a distance-2 threshold.
  */
object EditDistance {

  /** `None` once the length gap alone rules out every alignment within `maxDistance` — the cheap check that matters
    * more than the DP loop itself, since callers run this once per (token, candidate) pair in an already
    * length-narrowed bucket. `Some(n)` otherwise, `n <= maxDistance`.
    */
  def within(a: String, b: String, maxDistance: Int): Option[Int] = {
    if (Math.abs(a.length - b.length) > maxDistance) {
      None
    } else {
      val d = distance(a, b)
      Option.when(d <= maxDistance)(d)
    }
  }

  private def distance(a: String, b: String): Int = {
    val rows = a.length + 1
    val cols = b.length + 1
    val d    = Array.ofDim[Int](rows, cols)

    for (i <- 0 until rows) d(i)(0) = i
    for (j <- 0 until cols) d(0)(j) = j

    for (i <- 1 until rows) {
      for (j <- 1 until cols) {
        val cost = if (a(i - 1) == b(j - 1)) 0 else 1
        var best = Math.min(d(i - 1)(j) + 1, Math.min(d(i)(j - 1) + 1, d(i - 1)(j - 1) + cost))
        if (i > 1 && j > 1 && a(i - 1) == b(j - 2) && a(i - 2) == b(j - 1)) {
          best = Math.min(best, d(i - 2)(j - 2) + cost)
        }
        d(i)(j) = best
      }
    }

    d(a.length)(b.length)
  }
}
