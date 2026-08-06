package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.{AppRouter, Page}

object ForbiddenPage {
  def render(): HtmlElement = {
    centeredMessage(
      "Access denied",
      "You're signed in, but this page requires administrator rights.",
      Page.Home,
      "Back home",
    )
  }
}

object NotFoundPage {
  def render(): HtmlElement = {
    centeredMessage("Page not found", "That page doesn't exist.", Page.Home, "Back home")
  }
}

private def centeredMessage(title: String, message: String, target: Page, linkText: String): HtmlElement = {
  div(
    cls := "min-h-screen flex items-center justify-center bg-base-200 p-4",
    div(
      cls := "card w-full max-w-sm bg-base-100 shadow-xl",
      div(
        cls := "card-body items-center text-center",
        h1(cls := "card-title", title),
        p(message),
        a(cls  := "btn btn-primary mt-4", AppRouter.router.navigateTo(target), linkText),
      ),
    ),
  )
}
