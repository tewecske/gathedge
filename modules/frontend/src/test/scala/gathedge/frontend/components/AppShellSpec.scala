package gathedge.frontend.components

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.Page
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{Locale, Theme, User}
import gathedge.shared.i18n.UiKeys
import zio.test._

/** The shell's guest banner: who sees it, and where.
  *
  * A guest account lives in one browser's cookie jar, so the warning is mounted once in the shell rather than on the
  * pages that happen to be about words — which is the whole of this change, and the whole of what is asserted here.
  *
  * With no catalog loaded a message resolves to its own key, so the assertions are on `UiKeys` constants. That the keys
  * have real copy behind them in both languages is `MessagesSpec`'s job.
  */
object AppShellSpec extends ZIOSpecDefault {

  private def user(isGuest: Boolean): User = {
    User(
      id = 1L,
      email = Option.when(!isGuest)("reader@example.com"),
      isAdmin = false,
      theme = Theme.Light,
      locale = Locale.En,
      createdAt = "2026-01-01T00:00:00Z",
      emailVerified = true,
      isGuest = isGuest,
    )
  }

  /** `AppState` is global, so the session is cleared again whatever happens. */
  private def signedInAs[A](isGuest: Boolean)(body: => A): A = {
    AppState.setUser(user(isGuest))
    try body
    finally AppState.clearUser()
  }

  private def withShell[A](shell: => HtmlElement)(use: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val rootNode  = L.render(container, shell)
    try {
      use(container)
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  /** The banner alone: the account menu repeats its two controls, so the whole shell's text would always hold them. */
  private def bannerText(c: dom.Element): String = {
    Option(c.querySelector("[role=status]")).map(_.textContent).getOrElse("")
  }

  private def clickButton(c: dom.Element, label: String): Unit = {
    val buttons = c.querySelectorAll("button")
    (0 until buttons.length)
      .map(buttons(_).asInstanceOf[dom.html.Button])
      .find(_.textContent.trim == label)
      .foreach(_.click())
  }

  def spec = {
    suite("AppShell")(
      test("a guest is warned on any page, not only the ones about words") {
        AppShell.guestBannerOpenVar.set(false)
        val text = signedInAs(isGuest = true) {
          withShell(AppShell.render(Page.About, div()))(_.textContent)
        }
        assertTrue(text.contains(UiKeys.guestBannerTitle), text.contains(UiKeys.guestBannerOpen))
      },
      // The full text took a screenful on a phone, on every page, so the banner starts as its title and one button.
      test("the banner starts as one line, and Open shows the full text and the two ways out") {
        AppShell.guestBannerOpenVar.set(false)
        val (closed, opened) = signedInAs(isGuest = true) {
          withShell(AppShell.render(Page.About, div())) { c =>
            val before = bannerText(c)
            clickButton(c, UiKeys.guestBannerOpen)
            (before, bannerText(c))
          }
        }
        assertTrue(
          !closed.contains(UiKeys.guestBannerHint),
          !closed.contains(UiKeys.guestGetCode),
          opened.contains(UiKeys.guestBannerTitle),
          opened.contains(UiKeys.guestBannerHint),
          opened.contains(UiKeys.guestGetCode),
          opened.contains(UiKeys.guestUpgrade),
          !opened.contains(UiKeys.guestBannerOpen),
        )
      },
      test("Close makes the banner one line again, and the open state lasts to the next page") {
        AppShell.guestBannerOpenVar.set(false)
        val (nextPage, afterClose) = signedInAs(isGuest = true) {
          withShell(AppShell.render(Page.About, div()))(c => clickButton(c, UiKeys.guestBannerOpen))
          withShell(AppShell.render(Page.Settings, div())) { c =>
            val shown = bannerText(c)
            clickButton(c, UiKeys.guestBannerClose)
            (shown, bannerText(c))
          }
        }
        assertTrue(
          nextPage.contains(UiKeys.guestBannerHint),
          !afterClose.contains(UiKeys.guestBannerHint),
          afterClose.contains(UiKeys.guestBannerTitle),
          afterClose.contains(UiKeys.guestBannerOpen),
        )
      },
      test("a guest can reach Settings to change its name") {
        val text = signedInAs(isGuest = true) {
          withShell(AppShell.render(Page.About, div()))(_.textContent)
        }
        assertTrue(text.contains(UiKeys.settingsTitle))
      },
      test("an ordinary account is not warned") {
        val text = signedInAs(isGuest = false) {
          withShell(AppShell.render(Page.About, div()))(_.textContent)
        }
        assertTrue(!text.contains(UiKeys.guestBannerTitle))
      },
      // Sign-in and sign-up render through `renderPublic`, and a guest who reached sign-up is already performing the
      // upgrade the banner asks for.
      test("the signed-out shell carries no banner, guest session or not") {
        val text = signedInAs(isGuest = true) {
          withShell(AppShell.renderPublic(div()))(_.textContent)
        }
        assertTrue(!text.contains(UiKeys.guestBannerTitle))
      },
    )
  }
}
