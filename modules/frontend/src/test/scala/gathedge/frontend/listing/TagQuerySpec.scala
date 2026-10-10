package gathedge.frontend.listing

import gathedge.shared.domain.WordLanguage
import gathedge.shared.dto.Paging
import zio.test._

/** [[TagQuery]]'s `reset` rule for the language filter — where a filter change lands. */
object TagQuerySpec extends ZIOSpecDefault {

  def spec = {
    suite("TagQuery")(
      test("choosing a language starts again at the first page") {
        assertTrue(
          TagQuery(page = 4).reset(_.copy(language1 = Some(WordLanguage.Es))) ==
            TagQuery(language1 = Some(WordLanguage.Es)),
          TagQuery(page = 4).reset(_.copy(language2 = Some(WordLanguage.Fr))).page == Paging.firstPage,
        )
      }
    )
  }
}
