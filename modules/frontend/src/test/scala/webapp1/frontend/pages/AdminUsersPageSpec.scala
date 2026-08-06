package webapp1.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import zio.test._

/** Covers the form-state shape of the create-user form: field errors stay hidden until the first submit attempt, then
  * track the inputs live.
  */
object AdminUsersPageSpec extends ZIOSpecDefault {

  private def withPage[A](use: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val rootNode  = L.render(container, AdminUsersPage.render())
    try {
      use(container)
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  private def fieldErrors(container: dom.Element): List[String] = {
    container.querySelectorAll(".text-error").toList.map(_.textContent)
  }

  private def fill(container: dom.Element, selector: String, text: String): Unit = {
    val input = container.querySelector(selector).asInstanceOf[dom.html.Input]
    input.value = text
    input.dispatchEvent(new dom.Event("input"))
  }

  private def submit(container: dom.Element): Unit = {
    container.querySelector("form").asInstanceOf[dom.html.Form].dispatchEvent(new dom.Event("submit"))
    ()
  }

  def spec = {
    suite("AdminUsersPage")(
      test("shows no field errors before the first submit, however invalid the input") {
        val errors = withPage { container =>
          fill(container, "input[type=email]", "not-an-email")
          fill(container, "input[type=password]", "short")
          fieldErrors(container)
        }

        assertTrue(errors.isEmpty)
      },
      test("submitting an invalid form shows an error under each bad field") {
        val errors = withPage { container =>
          fill(container, "input[type=email]", "not-an-email")
          fill(container, "input[type=password]", "short")
          submit(container)
          fieldErrors(container)
        }

        assertTrue(
          errors.exists(_.contains("Invalid email format")),
          errors.exists(_.contains("at least 8 characters")),
        )
      },
      test("after a failed submit, fixing a field clears only that field's error") {
        val errors = withPage { container =>
          fill(container, "input[type=email]", "not-an-email")
          fill(container, "input[type=password]", "short")
          submit(container)
          fill(container, "input[type=email]", "user@example.com")
          fieldErrors(container)
        }

        assertTrue(!errors.exists(_.contains("email")), errors.exists(_.contains("at least 8 characters")))
      },
    )
  }
}
