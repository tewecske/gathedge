package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import zio.test._

object ResetPasswordPageSpec extends ZIOSpecDefault {

  def spec = {
    suite("ResetPasswordPage")(
      test("renders a password field and a submit button") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, ResetPasswordPage.render("some-token"))

        val hasPasswordInput = container.querySelector("input[type=password]") != null
        val hasSubmit        = container.querySelector("button[type=submit]") != null

        rootNode.unmount()
        dom.document.body.removeChild(container)

        assertTrue(hasPasswordInput, hasSubmit)
      }
    )
  }
}
