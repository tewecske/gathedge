package webapp1.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.shared.i18n.MessageKeys
import zio.test._

/** Mounts SignUpPage into a real (jsdom) document and confirms client-side validation renders an error without touching
  * the network — proves the frontend zio-test + jsdom wiring works before M2/M3 add more pages/specs.
  */
object SignUpPageSpec extends ZIOSpecDefault {

  /** No catalog is loaded under jsdom, so a message resolves to its key; see `AdminUsersPageSpec` on why that is the
    * right thing to assert here.
    */
  private val passwordTooShort = MessageKeys.passwordTooShort

  def spec = {
    suite("SignUpPage")(
      test("shows a validation error for a too-short password") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, SignUpPage.render())

        val emailInput    = container.querySelector("input[type=email]").asInstanceOf[dom.html.Input]
        val passwordInput = container.querySelector("input[type=password]").asInstanceOf[dom.html.Input]
        val signUpForm    = container.querySelector("form").asInstanceOf[dom.html.Form]

        emailInput.value = "user@example.com"
        emailInput.dispatchEvent(new dom.Event("input"))
        passwordInput.value = "short"
        passwordInput.dispatchEvent(new dom.Event("input"))
        // The page submits via the form element (so Enter works), not via a click handler.
        signUpForm.dispatchEvent(new dom.Event("submit"))

        val alertText = Option(container.querySelector(".alert")).map(_.textContent).getOrElse("")

        rootNode.unmount()
        dom.document.body.removeChild(container)

        assertTrue(alertText.contains(passwordTooShort))
      }
    )
  }
}
