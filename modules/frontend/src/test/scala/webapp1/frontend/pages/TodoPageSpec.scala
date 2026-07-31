package webapp1.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.shared.domain.{Theme, User}
import zio.test._

object TodoPageSpec extends ZIOSpecDefault {

  private val testUser = User(1L, "user@example.com", isAdmin = false, Theme.Light, "2026-01-01T00:00:00Z")

  def spec = suite("TodoPage")(
    test("renders the three status columns and an add-item form, without a backend") {
      val container = dom.document.createElement("div")
      dom.document.body.appendChild(container)
      // No backend is running; mounting must not throw even though the initial
      // GET /api/todos will fail (ApiClient.networkSafe turns that into a Left).
      val rootNode = L.render(container, TodoPage.render(testUser))

      val text          = container.textContent
      val hasAddInput   = container.querySelector("input") != null
      val hasAllColumns = text.contains("To Do") && text.contains("In Progress") && text.contains("Done")

      rootNode.unmount()
      dom.document.body.removeChild(container)

      assertTrue(hasAddInput, hasAllColumns)
    }
  )
}
