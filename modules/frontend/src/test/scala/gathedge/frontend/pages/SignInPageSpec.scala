package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import zio.test._

object SignInPageSpec extends ZIOSpecDefault {

  def spec = {
    suite("SignInPage")(
      test("renders email/password fields and a sign-up link") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, SignInPage.render())

        val hasEmailInput    = container.querySelector("input[type=email]") != null
        val hasPasswordInput = container.querySelector("input[type=password]") != null
        val hasSignUpLink    = container.querySelector("a") != null

        rootNode.unmount()
        dom.document.body.removeChild(container)

        assertTrue(hasEmailInput, hasPasswordInput, hasSignUpLink)
      }
    )
  }
}
