package gathedge.frontend.listing

import gathedge.shared.domain.{TagScope, WordLanguage}
import gathedge.shared.dto.Paging
import zio.test._

/** [[TagQuery]]'s `narrowed` and `reset` rules — what shows the catalog's "Reset filters" and where a filter change
  * lands.
  */
object TagQuerySpec extends ZIOSpecDefault {

  def spec = {
    suite("TagQuery")(
      test("a scope or a language narrows the listing; a search term does not") {
        assertTrue(
          !TagQuery.default.narrowed,
          !TagQuery(search = "verbs").narrowed,
          TagQuery(scope = TagScope.Mine).narrowed,
          TagQuery(language1 = Some(WordLanguage.De)).narrowed,
          TagQuery(language2 = Some(WordLanguage.Hu)).narrowed,
        )
      },
      test("choosing a language starts again at the first page") {
        assertTrue(
          TagQuery(page = 4).reset(_.copy(language1 = Some(WordLanguage.Es))) ==
            TagQuery(language1 = Some(WordLanguage.Es)),
          TagQuery(page = 4).reset(_.copy(language2 = Some(WordLanguage.Fr))).page == Paging.firstPage,
        )
      },
    )
  }
}
