package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import gathedge.frontend.api.ApiError
import gathedge.frontend.listing.AllGameQuery
import gathedge.shared.domain.{Tag, WordLanguage}
import org.scalajs.dom
import zio.test._

import scala.collection.mutable.ListBuffer

/** The wordlist filter's one read. The page fetches a wordlist only for a `tagId` the URL names, and one at a time. A
  * stub stands in for `GET /api/tags/{id}`: it records each id and answers from [[replies]], so a spec can hold an
  * answer back. The games listing itself still calls the real server, which jsdom cannot reach; it fails quietly.
  *
  * A pick in the picker carries its own `Tag` and fetches nothing. That path needs a live search to fill the dropdown,
  * so `e2e/tests/game.spec.ts` covers it instead.
  */
object AllGamesPageSpec extends ZIOSpecDefault {

  private val five  =
    Tag(5L, "five", 3L, ownedByMe = true, sourceLanguage = WordLanguage.De, targetLanguage = WordLanguage.Hu)
  private val seven =
    Tag(7L, "seven", 3L, ownedByMe = true, sourceLanguage = WordLanguage.En, targetLanguage = WordLanguage.Hu)

  private final case class Harness(
    container: dom.Element,
    queryVar: Var[AllGameQuery],
    calls: ListBuffer[Long],
    replies: EventBus[Tag],
  )

  /** Mounts the page with a `Var` for the router. Each fetch answers the first [[Harness.replies]] event for its id. */
  private def withPage[A](query: AllGameQuery)(use: Harness => A): A = {
    val container                       = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val queryVar                        = Var(query)
    val calls                           = ListBuffer.empty[Long]
    val replies                         = new EventBus[Tag]()
    val fetchTag: AllGamesPage.FetchTag = id => {
      calls += id
      replies.events.filter(_.id == id).take(1).map(tag => Right(tag): Either[ApiError, Tag])
    }
    val rootNode                        = L.render(container, AllGamesPage.render(queryVar.signal, queryVar.writer, fetchTag))
    try {
      use(Harness(container, queryVar, calls, replies))
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  private def pickerText(c: dom.Element): String = {
    c.querySelector("input[type=text]").asInstanceOf[dom.html.Input].value
  }

  /** True when both language selects are locked — the page's own sign that it holds the chosen wordlist. */
  private def languagesLocked(c: dom.Element): Boolean = {
    val selects = c.querySelectorAll("select.w-28").toList.map(_.asInstanceOf[dom.html.Select])
    selects.nonEmpty && selects.forall(_.disabled)
  }

  /** What a spec asserts on, read while the page is still mounted: `assertTrue` evaluates its arguments only after
    * [[withPage]] has unmounted it.
    */
  private final case class Seen(calls: List[Long], picker: String, locked: Boolean)

  private def seen(h: Harness): Seen = Seen(h.calls.toList, pickerText(h.container), languagesLocked(h.container))

  def spec = {
    suite("AllGamesPage")(
      test("no wordlist in the URL fetches none") {
        val after = withPage(AllGameQuery())(seen)
        assertTrue(after == Seen(Nil, "", locked = false))
      },
      test("a wordlist in the URL is fetched once, and fills the picker and locks the languages") {
        val (waiting, after) = withPage(AllGameQuery(tagId = Some(5L))) { h =>
          val waiting = seen(h)
          h.replies.emit(five)
          (waiting, seen(h))
        }
        assertTrue(waiting == Seen(List(5L), "", locked = false), after == Seen(List(5L), "five", locked = true))
      },
      test("a query change that keeps the same wordlist fetches nothing more") {
        val after = withPage(AllGameQuery(tagId = Some(5L))) { h =>
          h.replies.emit(five)
          h.queryVar.update(_.copy(search = "x"))
          h.queryVar.update(_.copy(page = 2))
          seen(h)
        }
        assertTrue(after.calls == List(5L))
      },
      test("another wordlist is fetched, and the held one is not shown for it meanwhile") {
        val (waiting, after) = withPage(AllGameQuery(tagId = Some(5L))) { h =>
          h.replies.emit(five)
          h.queryVar.update(_.copy(tagId = Some(7L)))
          val waiting = seen(h)
          h.replies.emit(seven)
          (waiting, seen(h))
        }
        assertTrue(
          waiting.calls == List(5L, 7L),
          !waiting.locked,
          after == Seen(List(5L, 7L), "seven", locked = true),
        )
      },
      test("clearing the wordlist fetches nothing and unlocks the languages") {
        val after = withPage(AllGameQuery(tagId = Some(5L))) { h =>
          h.replies.emit(five)
          h.queryVar.update(_.copy(tagId = None))
          seen(h)
        }
        assertTrue(after.calls == List(5L), !after.locked)
      },
      test("an answer that arrives after the filter moved on is dropped") {
        val after = withPage(AllGameQuery(tagId = Some(5L))) { h =>
          h.queryVar.update(_.copy(tagId = None))
          h.replies.emit(five)
          seen(h)
        }
        assertTrue(after == Seen(List(5L), "", locked = false))
      },
    )
  }
}
