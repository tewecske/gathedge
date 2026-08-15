package gathedge.frontend.components

import com.raquo.laminar.api.L._
import org.scalajs.dom
import org.scalajs.dom.html

import scala.scalajs.js

/** Loads the Cloudflare Turnstile script once per page and renders the widget into a Laminar element.
  *
  * Turnstile is an imperative, script-tag global, so it cannot be a pure Laminar element: the widget has to be handed a
  * real DOM node, which only exists once the element is mounted. This class is the seam — `render()` returns the
  * container, the mount callback loads the script and renders the widget, and the token the widget produces is written
  * to the enclosing form's `tokenObserver`.
  *
  * A token is single-use: Cloudflare consumes it at the `siteverify` call, so after every submit the form fires the
  * `resetStream`, which clears the spent token and renders a fresh challenge. Clearing on the expired/error callbacks
  * too means a form never sends a token the widget has already invalidated.
  */
class CaptchaField(
  siteKey: String,
  tokenObserver: Observer[Option[String]],
  resetStream: EventStream[Unit],
) {
  private var widgetId: Option[String] = None

  private def doReset(): Unit = {
    widgetId.foreach { id =>
      if (!js.isUndefined(js.Dynamic.global.turnstile)) {
        js.Dynamic.global.turnstile.reset(id)
      }
    }
    tokenObserver.onNext(None)
  }

  def render(): HtmlElement = {
    div(
      onMountCallback { ctx =>
        CaptchaLoader.load { () =>
          renderInto(ctx.thisNode.ref)
        }
      },
      resetStream --> Observer(_ => doReset()),
    )
  }

  private def renderInto(node: dom.Element): Unit = {
    val onToken   = ((token: String) => tokenObserver.onNext(Some(token))): js.Function1[String, Unit]
    val onExpired = (() => tokenObserver.onNext(None)): js.Function0[Unit]
    val options   = js.Dynamic.literal(
      sitekey = siteKey,
      callback = onToken,
    )
    // Two option keys are kebab-case and cannot be a literal's named argument; `updateDynamic` sets them by name.
    options.updateDynamic("expired-callback")(onExpired)
    options.updateDynamic("error-callback")(onExpired)
    widgetId = Some(js.Dynamic.global.turnstile.render(node, options).asInstanceOf[String])
  }
}

/** Injects the Turnstile `api.js` script at most once per page load, and runs the callbacks queued for it.
  *
  * More than one form can hold a [[CaptchaField]], but a browser only loads one page at a time, so the pending list
  * holds at most one callback in practice; a list keeps the loading state honest anyway. A failed load is silent — the
  * forms degrade to "captcha off" (the widget simply never appears), and the server, not the browser, is what refuses a
  * missing token.
  */
private object CaptchaLoader {
  private val scriptUrl = "https://challenges.cloudflare.com/turnstile/v0/api.js"

  private var pending: List[() => Unit] = Nil
  private var loaded: Boolean           = false

  def load(callback: () => Unit): Unit = {
    if (loaded) {
      callback()
    } else {
      pending = callback :: pending
      if (pending.sizeIs == 1) {
        inject()
      }
    }
  }

  private def inject(): Unit = {
    val script = dom.document.createElement("script").asInstanceOf[html.Script]
    script.src = scriptUrl
    script.async = true
    script.addEventListener(
      "load",
      (_: dom.Event) => {
        loaded = true
        val waiting = pending
        pending = Nil
        waiting.reverse.foreach(_.apply())
      },
    )
    script.addEventListener(
      "error",
      (_: dom.Event) => {
        pending = Nil
      },
    )
    dom.document.body.appendChild(script)
  }
}
