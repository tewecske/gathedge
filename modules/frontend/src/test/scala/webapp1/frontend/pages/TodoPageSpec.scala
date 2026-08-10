package webapp1.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.shared.i18n.UiKeys
import zio.test._

object TodoPageSpec extends ZIOSpecDefault {

  def spec = {
    suite("TodoPage")(
      test("renders the three status columns and an add-item form, without a backend") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        // No backend is running; mounting must not throw even though the initial
        // GET /api/todos will fail (ApiClient.networkSafe turns that into a Left).
        val rootNode  = L.render(container, TodoPage.render())

        val text          = container.textContent
        val hasAddInput   = container.querySelector("input") != null
        // Keys, not copy: no catalog is loaded under jsdom, so `I18n.t` renders the key itself.
        // That is the stronger assertion anyway — it says the column asked for the right message,
        // and `MessagesSpec` separately proves the key has real text behind it in both languages.
        val hasAllColumns = {
          text.contains(UiKeys.todoStatusToDo) &&
          text.contains(UiKeys.todoStatusInProgress) &&
          text.contains(UiKeys.todoStatusDone)
        }

        rootNode.unmount()
        dom.document.body.removeChild(container)

        assertTrue(hasAddInput, hasAllColumns)
      },
      // Covers the reactive wiring, not just the initial render: submitting blank text is
      // rejected client-side, so the alert appears synchronously with no network involved.
      test("submitting a blank item shows a validation error and leaves the button enabled") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, TodoPage.render())

        val addForm   = container.querySelector("form").asInstanceOf[dom.html.Form]
        val addButton = container.querySelector("form button").asInstanceOf[dom.html.Button]
        addForm.dispatchEvent(new dom.Event("submit"))

        val alertText    = Option(container.querySelector(".alert")).map(_.textContent).getOrElse("")
        val stillEnabled = !addButton.disabled

        rootNode.unmount()
        dom.document.body.removeChild(container)

        assertTrue(alertText.contains("required"), stillEnabled)
      },
    )
  }
}
