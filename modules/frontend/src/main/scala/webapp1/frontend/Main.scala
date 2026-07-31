package webapp1.frontend

import com.raquo.laminar.api.L._
import org.scalajs.dom

object Main {
  def main(args: Array[String]): Unit = {
    val container = dom.document.getElementById("app")
    render(container, App.render())
  }
}
