package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.i18n.I18n
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.i18n.UiKeys

object ForbiddenPage {
  def render(): HtmlElement = {
    centeredMessage(
      I18n.t(UiKeys.forbiddenTitle),
      I18n.t(UiKeys.forbiddenBody),
      Page.Home,
      I18n.t(UiKeys.statusBackHome),
    )
  }
}

object NotFoundPage {
  def render(): HtmlElement = {
    centeredMessage(
      I18n.t(UiKeys.notFoundTitle),
      I18n.t(UiKeys.notFoundBody),
      Page.Home,
      I18n.t(UiKeys.statusBackHome),
    )
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
