package webapp1.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import zio.test._

/** Mounts SignUpPage into a real (jsdom) document and confirms client-side
  * validation renders an error without touching the network — proves the
  * frontend zio-test + jsdom wiring works before M2/M3 add more pages/specs.
  */
object SignUpPageSpec extends ZIOSpecDefault {

  def spec = suite("SignUpPage")(
    test("shows a validation error for a too-short password") {
      val container = dom.document.createElement("div")
      dom.document.body.appendChild(container)
      val rootNode = L.render(container, SignUpPage.render())

      val emailInput    = container.querySelector("input[type=email]").asInstanceOf[dom.html.Input]
      val passwordInput = container.querySelector("input[type=password]").asInstanceOf[dom.html.Input]
      val submitButton  = container.querySelector("button").asInstanceOf[dom.html.Button]

      emailInput.value = "user@example.com"
      emailInput.dispatchEvent(new dom.Event("input"))
      passwordInput.value = "short"
      passwordInput.dispatchEvent(new dom.Event("input"))
      submitButton.dispatchEvent(new dom.Event("click"))

      val alertText = Option(container.querySelector(".alert")).map(_.textContent).getOrElse("")

      rootNode.unmount()
      dom.document.body.removeChild(container)

      assertTrue(alertText.contains("at least 8 characters"))
    }
  )
}
