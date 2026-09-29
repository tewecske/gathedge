package gathedge.frontend.components

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom
import zio.test._

/** The confirm modal runs its action on the confirm button only. Cancel and the backdrop close it and run nothing. */
object ConfirmDialogSpec extends ZIOSpecDefault {

  private def withDialog[A](use: (ConfirmDialog, dom.Element) => A): A = {
    val dialog    = new ConfirmDialog()
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val rootNode  = L.render(container, dialog.render())
    try {
      use(dialog, container)
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  private def isOpen(container: dom.Element): Boolean = {
    container.querySelector(".modal").classList.contains("modal-open")
  }

  private def buttons(container: dom.Element): List[dom.html.Button] = {
    container.querySelectorAll(".modal-box button").toList.map(_.asInstanceOf[dom.html.Button])
  }

  private def askDelete(dialog: ConfirmDialog, count: Var[Int], danger: Boolean = true): Unit = {
    dialog.ask("Delete", "Delete it?", danger)(count.update(_ + 1))
  }

  def spec = {
    suite("ConfirmDialog")(
      test("the modal is closed until a button asks") {
        val (open, boxes) = withDialog((_, container) => (isOpen(container), buttons(container).size))
        assertTrue(!open, boxes == 0)
      },
      test("ask opens the modal with the label as title, the message, Cancel and the confirm button") {
        val (open, heading, message, labels) = withDialog { (dialog, container) =>
          askDelete(dialog, Var(0))
          (
            isOpen(container),
            container.querySelector(".modal-box h3").textContent,
            container.querySelector(".modal-box p").textContent,
            buttons(container).map(_.textContent),
          )
        }
        assertTrue(
          open,
          heading == "Delete",
          message == "Delete it?",
          labels == List(UiKeys.commonCancel, "Delete"),
        )
      },
      test("the confirm button runs the action once and closes the modal") {
        val count = Var(0)
        val open  = withDialog { (dialog, container) =>
          askDelete(dialog, count)
          buttons(container).last.click()
          isOpen(container)
        }
        assertTrue(count.now() == 1, !open)
      },
      test("Cancel and the backdrop close the modal and run nothing") {
        val count                  = Var(0)
        val (afterCancel, afterBg) = withDialog { (dialog, container) =>
          askDelete(dialog, count)
          buttons(container).head.click()
          val cancelled = isOpen(container)
          askDelete(dialog, count)
          container.querySelector(".modal-backdrop").asInstanceOf[dom.html.Element].click()
          (cancelled, isOpen(container))
        }
        assertTrue(count.now() == 0, !afterCancel, !afterBg)
      },
      test("a delete gets btn-error, a warning that is not a delete does not") {
        val (danger, plain) = withDialog { (dialog, container) =>
          askDelete(dialog, Var(0))
          val first = buttons(container).last.classList.contains("btn-error")
          askDelete(dialog, Var(0), danger = false)
          (first, buttons(container).last.classList.contains("btn-error"))
        }
        assertTrue(danger, !plain)
      },
    )
  }
}
