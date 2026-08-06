package webapp1.frontend

import com.raquo.laminar.api.L._
import org.scalajs.dom

object Main {
  def main(args: Array[String]): Unit = {
    Option(dom.document.getElementById("app")) match {
      case Some(container) =>
        render(container, App.render())
      case None            =>
        // index.html and this id are shipped together, so this only fires if the host page is wrong.
        dom.console.error("webapp1: no #app element to mount into")
    }
  }
}
