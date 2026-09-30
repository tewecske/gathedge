package gathedge.frontend.components

import com.raquo.airstream.web.DomEventStream
import com.raquo.laminar.api.L._
import com.raquo.laminar.codecs.Codec
import gathedge.frontend.facades.{SpeechSynthesis, SpeechSynthesisUtterance, SpeechSynthesisVoice}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.WordLanguage
import gathedge.shared.dto.WordAudio
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom

/** How a word sounds: a play button per recording, or the browser's own voice where there is none.
  *
  * A recording plays from Wikimedia Commons through an `<audio>` element with one `<source>` per URL, so the browser
  * itself falls back from the MP3 transcode to the upload. Each recording carries a link to its Commons file page. That
  * page names the author and the licence, and the link is the attribution the licence asks for.
  *
  * The fallback is shown only when the browser has a voice for the word's language. Without one it would read the word
  * in some other language's voice, which teaches the wrong sound.
  */
object Pronunciation {

  /** Whether a voice speaks `language`. Browsers write the tag as `de-DE`, `de_DE` or `de`, in any case. */
  def speaks(voiceLang: String, language: WordLanguage): Boolean = {
    voiceLang.toLowerCase.split("[-_]").headOption.contains(WordLanguage.code(language))
  }

  def render(text: String, language: WordLanguage, audio: List[WordAudio]): HtmlElement = {
    if (audio.nonEmpty)
      div(cls := "flex flex-wrap items-center gap-2 mt-1", audio.map(renderRecording))
    else
      div(cls := "mt-1", SpeechSynthesis.available.map(synth => renderSpeech(synth, text, language)))
  }

  private def renderRecording(recording: WordAudio): HtmlElement = {
    val player = audioTag(
      htmlAttr("preload", Codec.stringAsIs) := "none",
      recording.playUrls.map(url => sourceTag(src := url)),
    )
    span(
      cls := "inline-flex items-center gap-1",
      player,
      button(
        cls        := "btn btn-xs btn-outline",
        typ        := "button",
        title      := recording.fileName,
        aria.label := I18n.t(UiKeys.wordDetailAudioListen),
        onClick --> (_ => {
          player.ref.currentTime = 0
          player.ref.play()
        }),
        "▶ ",
        if (recording.region.nonEmpty) recording.region else I18n.t(UiKeys.wordDetailAudioListen),
      ),
      a(
        cls        := "link link-hover text-xs opacity-70",
        href       := recording.filePageUrl,
        target     := "_blank",
        rel        := "noopener noreferrer",
        title      := I18n.t(UiKeys.wordDetailAudioSourceHint),
        I18n.t(UiKeys.wordDetailAudioSource),
      ),
    )
  }

  private def renderSpeech(synth: SpeechSynthesis, text: String, language: WordLanguage): HtmlElement = {
    // The list is empty until the browser has loaded it in some browsers, so it is read again on `voiceschanged`.
    val voices = {
      EventStream
        .merge(EventStream.unit(), DomEventStream[dom.Event](synth, "voiceschanged"))
        .map(_ => synth.getVoices().toList.find(voice => speaks(voice.lang, language)))
        .toSignal(None)
    }
    span(
      child.maybe <-- voices.map(_.map(voice => speechButton(synth, text, language, voice)))
    )
  }

  private def speechButton(
    synth: SpeechSynthesis,
    text: String,
    language: WordLanguage,
    voice: SpeechSynthesisVoice,
  ): HtmlElement = {
    button(
      cls   := "btn btn-xs btn-outline",
      typ   := "button",
      title := voice.name,
      onClick --> (_ => {
        val utterance = new SpeechSynthesisUtterance(text)
        utterance.lang = WordLanguage.code(language)
        utterance.voice = voice
        synth.cancel()
        synth.speak(utterance)
      }),
      "▶ ",
      I18n.t(UiKeys.wordDetailAudioSynthetic),
    )
  }
}
