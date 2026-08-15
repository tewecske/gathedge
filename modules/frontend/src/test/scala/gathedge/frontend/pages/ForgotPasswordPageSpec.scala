package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import zio.test._

object ForgotPasswordPageSpec extends ZIOSpecDefault {

  def spec = {
    suite("ForgotPasswordPage")(
      test("renders an email field, a submit button and a link back to sign in") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, ForgotPasswordPage.render())

        val hasEmailInput = container.querySelector("input[type=email]") != null
        val hasSubmit     = container.querySelector("button[type=submit]") != null
        val hasSignInLink = container.querySelector("a") != null

        rootNode.unmount()
        dom.document.body.removeChild(container)

        assertTrue(hasEmailInput, hasSubmit, hasSignInLink)
      }
    )
  }
}
