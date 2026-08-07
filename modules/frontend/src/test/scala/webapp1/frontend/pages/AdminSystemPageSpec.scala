package webapp1.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import zio.test._

/** The system overview and the audit log render before — and without — an answer from the API.
  *
  * Both pages are almost entirely `child.maybe <-- signal.map(...)` over a loaded response, which is exactly the shape
  * that renders nothing at all if a signal is wired wrong, and exactly the shape jsdom can check cheaply. There is no
  * backend here: `EndpointClient.run` turns the failed call into a `Left`, so the pages settle into their empty state.
  */
object AdminSystemPageSpec extends ZIOSpecDefault {

  private def withPage[A](page: => HtmlElement)(use: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val rootNode  = L.render(container, page)
    try {
      use(container)
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  def spec = {
    suite("admin system pages")(
      test("the system page renders its heading and maintenance actions with no backend") {
        val (headings, buttons) = withPage(AdminSystemPage.render()) { container =>
          (
            container.querySelectorAll("h1").toList.map(_.textContent),
            container.querySelectorAll("button").toList.map(_.textContent),
          )
        }

        assertTrue(
          headings.exists(_.contains("System overview")),
          buttons.exists(_.contains("Prune")),
          buttons.exists(_.contains("Clear every rate-limit lock")),
        )
      },
      test("the system page offers the three admin tabs") {
        val tabs = withPage(AdminSystemPage.render()) { container =>
          container.querySelectorAll(".tab").toList.map(_.textContent)
        }

        assertTrue(tabs == List("Users", "Audit log", "System"))
      },
      test("the audit page renders its filters and its empty state with no backend") {
        val (headings, text) = withPage(AdminAuditPage.render()) { container =>
          (container.querySelectorAll("h1").toList.map(_.textContent), container.textContent)
        }

        assertTrue(headings.exists(_.contains("Audit log")), text.contains("No entries."))
      },
      test("the audit page's action filter offers every recorded action") {
        val options = withPage(AdminAuditPage.render()) { container =>
          container.querySelectorAll("select option").toList.map(_.textContent)
        }

        assertTrue(
          options.contains("Every action"),
          options.contains("user.create"),
          options.contains("user.lockout_cleared"),
          options.contains("system.prune"),
        )
      },
    )
  }
}
