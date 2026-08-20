package gathedge.backend.db

import java.text.Normalizer

/** Strips the vocabulary's accents down to plain Latin, so the search box works from an unaccented keyboard: typing
  * "hau" still finds "häuser", and "o" still finds "ő". Applied on both sides of the `LIKE` — see `WordRow.textSearch`
  * and `WordRepositoryLive.searchPattern` — never on `textNorm`, which stays the exact lowercased spelling the identity
  * key and the unique index are built on.
  */
object TextSearch {
  private val combiningMarks = "\\p{Mn}+".r

  def fold(text: String): String = {
    combiningMarks.replaceAllIn(Normalizer.normalize(text, Normalizer.Form.NFD), "")
  }
}
