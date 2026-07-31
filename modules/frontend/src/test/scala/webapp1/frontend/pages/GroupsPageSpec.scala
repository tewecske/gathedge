package webapp1.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.shared.domain.{Theme, User}
import zio.test._

object GroupsPageSpec extends ZIOSpecDefault {

  private val testUser = User(1L, "user@example.com", isAdmin = false, Theme.Light, "2026-01-01T00:00:00Z")

  def spec = suite("GroupsPage")(
    test("renders a heading and a create-group form, without a backend") {
      val container = dom.document.createElement("div")
      dom.document.body.appendChild(container)
      val rootNode = L.render(container, GroupsPage.render(testUser))

      val hasHeading = container.textContent.contains("Groups")
      val hasInput   = container.querySelector("input") != null
      val hasButton  = container.querySelector("button") != null

      rootNode.unmount()
      dom.document.body.removeChild(container)

      assertTrue(hasHeading, hasInput, hasButton)
    }
  )
}
