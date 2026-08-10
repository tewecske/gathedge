package webapp1.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.shared.i18n.{MessageKeys, UiKeys}
import zio.test._

object GroupsPageSpec extends ZIOSpecDefault {

  /** No catalog is loaded under jsdom, so a message resolves to its key; see `AdminUsersPageSpec` on why that is the
    * right thing to assert here. Which *field* it belongs to is covered by the element it renders under.
    */
  private val groupNameRequired = MessageKeys.fieldRequired

  def spec = {
    suite("GroupsPage")(
      test("renders a heading and a create-group form, without a backend") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, GroupsPage.render())

        val hasHeading = container.textContent.contains(UiKeys.groupsTitle)
        val hasInput   = container.querySelector("input") != null
        val hasButton  = container.querySelector("button") != null

        rootNode.unmount()
        dom.document.body.removeChild(container)

        assertTrue(hasHeading, hasInput, hasButton)
      },
      test("submitting a blank name shows a field error, but only after the submit") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, GroupsPage.render())

        val beforeSubmit = Option(container.querySelector(".text-error")).map(_.textContent)
        container.querySelector("form").asInstanceOf[dom.html.Form].dispatchEvent(new dom.Event("submit"))
        val afterSubmit  = Option(container.querySelector(".text-error")).map(_.textContent)

        rootNode.unmount()
        dom.document.body.removeChild(container)

        assertTrue(beforeSubmit.isEmpty, afterSubmit.exists(_.contains(groupNameRequired)))
      },
    )
  }
}
