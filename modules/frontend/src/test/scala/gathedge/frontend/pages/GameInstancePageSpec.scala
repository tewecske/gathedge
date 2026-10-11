package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import gathedge.frontend.api.ApiError
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{Locale, Theme, User, WordLanguage}
import gathedge.shared.dto.GameDetail
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom
import zio.test._

import scala.concurrent.Future

/** The play-variant picker under jsdom, with no backend. Most of this spec renders the page with the game read answered
  * by a stub (`fetchGame`), to pin who sees the owner's controls: the owner (`GameDetail.ownedByMe`) and a global
  * administrator, nobody else. Every other request still fails, since jsdom has no server. The picker's own interactive
  * behaviour (swap arrow, word-limit mutual exclusion, preference select) is exercised end-to-end by the
  * `e2e/tests/game.spec.ts` suite instead.
  */
object GameInstancePageSpec extends ZIOSpecDefault {

  /** Never actually called here — nothing in this spec opens the QR modal — but `GameInstancePage.render` takes it as
    * an ordinary parameter rather than reaching for `QRCode` itself, precisely so a real `qrcode` import is never part
    * of what this spec links. See [[GameInstancePage.render]]'s own doc comment, copied in spirit from
    * `WordsPageSpec.stubRecognize`/[[gathedge.frontend.ocr.ImageOcr.Recognize]].
    */
  private def stubGenerateQr(text: String): Future[String] = {
    Future.failed(new RuntimeException("QR generation is not exercised in this spec"))
  }

  private val game = GameDetail("brave-otter", "Brave otter", WordLanguage.De, WordLanguage.Hu, Nil)

  private def account(isAdmin: Boolean): User = {
    User(
      id = 1L,
      email = Some("reader@example.com"),
      isAdmin = isAdmin,
      theme = Theme.Light,
      locale = Locale.En,
      createdAt = "2026-01-01T00:00:00Z",
      emailVerified = true,
      isGuest = false,
    )
  }

  /** Which of the owner's controls the title shows: rename, delete, and the results link. */
  private final case class Controls(rename: Boolean, delete: Boolean, results: Boolean)

  /** Mounts the page for `detail`, answered at once by a stub, with `user` signed in (or nobody), and reads the owner's
    * controls while it is still mounted — `assertTrue` evaluates its arguments only after this returns. `AppState` is a
    * global, so the session is cleared again whatever happens.
    */
  private def controlsFor(detail: GameDetail, user: Option[User]): Controls = {
    user.foreach(AppState.setUser)
    val container                             = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val fetchGame: GameInstancePage.FetchGame = _ => EventStream.fromValue(Right(detail): Either[ApiError, GameDetail])
    val rootNode                              = L.render(container, GameInstancePage.render(detail.slug, stubGenerateQr, fetchGame))
    try {
      def button(label: String) = container.querySelector(s"""button[aria-label="$label"]""") != null
      val links                 = container.querySelectorAll("a").toList.map(_.textContent)
      Controls(
        rename = button(UiKeys.gameInstanceRenameEdit),
        delete = button(UiKeys.gameInstanceDeleteButton),
        results = links.contains(UiKeys.gameInstanceViewResults),
      )
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
      AppState.clearUser()
    }
  }

  private val none = Controls(rename = false, delete = false, results = false)
  private val all  = Controls(rename = true, delete = true, results = true)

  def spec = {
    suite("GameInstancePage")(
      test("an anonymous reader sees no rename, delete or results control") {
        val seen = controlsFor(game, user = None)
        assertTrue(seen == none)
      },
      test("a signed-in reader who does not own the game sees none of them") {
        val seen = controlsFor(game, user = Some(account(isAdmin = false)))
        assertTrue(seen == none)
      },
      test("the owner sees all three") {
        val seen = controlsFor(game.copy(ownedByMe = true), user = Some(account(isAdmin = false)))
        assertTrue(seen == all)
      },
      test("a global administrator sees all three on a game they do not own") {
        val seen = controlsFor(game, user = Some(account(isAdmin = true)))
        assertTrue(seen == all)
      },
      test("mounts and unmounts cleanly for an unknown slug") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, GameInstancePage.render("no-such-slug", stubGenerateQr))
        rootNode.unmount()
        dom.document.body.removeChild(container)
        assertTrue(true)
      },
      test("WordLimitChoice.toLimit maps each radio to its wordLimit") {
        assertTrue(
          WordLimitChoice.toLimit(WordLimitChoice.All, "") == None,
          WordLimitChoice.toLimit(WordLimitChoice.Ten, "") == Some(10),
          WordLimitChoice.toLimit(WordLimitChoice.Twenty, "") == Some(20),
          WordLimitChoice.toLimit(WordLimitChoice.Custom, " 3 ") == Some(3),
          WordLimitChoice.toLimit(WordLimitChoice.Custom, "") == None,
          WordLimitChoice.toLimit(WordLimitChoice.Custom, "0") == None,
          WordLimitChoice.toLimit(WordLimitChoice.Custom, "-4") == None,
          WordLimitChoice.toLimit(WordLimitChoice.Custom, "x") == None,
        )
      },
      test("WordLimitChoice.fromLimit gives back the radio and text toLimit reads") {
        val limits = List(None, Some(10), Some(20), Some(7))
        assertTrue(
          WordLimitChoice.fromLimit(None) == (WordLimitChoice.All, ""),
          WordLimitChoice.fromLimit(Some(10)) == (WordLimitChoice.Ten, ""),
          WordLimitChoice.fromLimit(Some(20)) == (WordLimitChoice.Twenty, ""),
          WordLimitChoice.fromLimit(Some(7)) == (WordLimitChoice.Custom, "7"),
          limits.forall(limit => (WordLimitChoice.toLimit.tupled)(WordLimitChoice.fromLimit(limit)) == limit),
        )
      },
    )
  }
}
