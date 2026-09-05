package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import zio.test._

object SignInPageSpec extends ZIOSpecDefault {

  def spec = {
    suite("SignInPage")(
      test("renders an identifier field, a password field and a sign-up link") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, SignInPage.render())

        // Not `input[type=email]`: the first field takes an address *or* a username, and the browser's own email
        // validation would refuse a username before the form was ever posted. Matched by name, since the
        // transfer-code box below it is a text input too.
        val hasIdentifierInput = container.querySelector("input[name=identifier]") != null
        val hasPasswordInput   = container.querySelector("input[type=password]") != null
        val hasSignUpLink      = container.querySelector("a") != null

        rootNode.unmount()
        dom.document.body.removeChild(container)

        assertTrue(hasIdentifierInput, hasPasswordInput, hasSignUpLink)
      }
    )
  }
}
