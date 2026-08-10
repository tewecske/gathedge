package webapp1.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.listing.UserQuery
import webapp1.shared.i18n.MessageKeys
import zio.test._

/** Covers the form-state shape of the create-user form: field errors stay hidden until the first submit attempt, then
  * track the inputs live.
  */
object AdminUsersPageSpec extends ZIOSpecDefault {

  /** The specs run with no catalog loaded — jsdom has no server to fetch one from — so a message resolves to its own
    * key. Asserting on the key is the stronger statement anyway: it says the form routed the *right message* to the
    * right field, which is the page's job. That the key has real copy behind it in both languages is `MessagesSpec`'s
    * job, and it can read the catalogs, which this cannot.
    */
  private val passwordTooShort = MessageKeys.passwordTooShort

  /** The page reads its listing state from the URL and writes it back. A `Var` stands in for the router and closes the
    * same loop, so a page turn or a search still lands where the page expects it.
    */
  private def withPage[A](use: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val queryVar  = Var(UserQuery())
    val rootNode  = L.render(container, AdminUsersPage.render(queryVar.signal, queryVar.writer))
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
          errors.exists(_.contains(MessageKeys.emailInvalid)),
          errors.exists(_.contains(passwordTooShort)),
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

        assertTrue(!errors.exists(_.contains("email")), errors.exists(_.contains(passwordTooShort)))
      },
    )
  }
}
