package gathedge.frontend.facades

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** Thin facade over the browser's Web Speech API, the speaking half only. scalajs-dom does not declare it.
  *
  * Only what the word page's fallback voice uses is declared: speak one utterance in one language, stop it, and list
  * the voices. The voice list fills in late in some browsers, which announce it with a `voiceschanged` event on
  * [[SpeechSynthesis]] itself.
  */
@js.native
@JSGlobal("SpeechSynthesisUtterance")
class SpeechSynthesisUtterance(text: String) extends js.Object {

  /** A BCP 47 tag. The primary subtag alone (`de`) lets the browser pick any voice of that language. */
  var lang: String = js.native

  var voice: SpeechSynthesisVoice = js.native
}

@js.native
trait SpeechSynthesisVoice extends js.Object {
  val name: String = js.native
  val lang: String = js.native
}

@js.native
trait SpeechSynthesis extends dom.EventTarget {
  def speak(utterance: SpeechSynthesisUtterance): Unit = js.native
  def cancel(): Unit                                   = js.native
  def getVoices(): js.Array[SpeechSynthesisVoice]      = js.native
}

object SpeechSynthesis {

  /** `None` in a browser without the API, and in the test runner. */
  def available: Option[SpeechSynthesis] = {
    val synth = dom.window.asInstanceOf[js.Dynamic].speechSynthesis
    Option.when(!js.isUndefined(synth) && synth != null)(synth.asInstanceOf[SpeechSynthesis])
  }
}
