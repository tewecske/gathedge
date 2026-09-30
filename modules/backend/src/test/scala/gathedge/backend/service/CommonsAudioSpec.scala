package gathedge.backend.service

import gathedge.backend.db.WordAudioRow
import zio.test._

/** The URLs `CommonsAudio` derives must be the ones Commons serves. The expected values are copied from the wiktextract
  * dump's own `ogg_url`/`mp3_url` fields, which Wiktionary's renderer built, so they are the ground truth.
  */
object CommonsAudioSpec extends ZIOSpecDefault {

  def spec = {
    suite("CommonsAudio")(
      test("an ordinary name hashes to the dump's own directory") {
        assertTrue(
          CommonsAudio.originalUrl("De-gratis.ogg") ==
            "https://upload.wikimedia.org/wikipedia/commons/8/88/De-gratis.ogg",
          CommonsAudio.mp3Url("De-gratis.ogg") ==
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/8/88/De-gratis.ogg/De-gratis.ogg.mp3",
        )
      },
      test("the name is normalised first: upper-case first letter, underscores for spaces") {
        assertTrue(
          CommonsAudio.normalise("en-us-portmanteau-1.ogg") == "En-us-portmanteau-1.ogg",
          CommonsAudio.originalUrl("en-us-portmanteau-1.ogg") ==
            "https://upload.wikimedia.org/wikipedia/commons/f/f2/En-us-portmanteau-1.ogg",
          CommonsAudio.originalUrl("Ja-hana-flower or nose etc.ogg") ==
            "https://upload.wikimedia.org/wikipedia/commons/4/4f/Ja-hana-flower_or_nose_etc.ogg",
        )
      },
      test("brackets and non-ASCII letters are percent-encoded, and hashed as UTF-8") {
        assertTrue(
          CommonsAudio.mp3Url("LL-Q150 (fra)-Lyokoï-gratis.wav") ==
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/e/e6/" +
            "LL-Q150_%28fra%29-Lyoko%C3%AF-gratis.wav/LL-Q150_%28fra%29-Lyoko%C3%AF-gratis.wav.mp3",
          CommonsAudio.originalUrl("Ko-후.ogg") ==
            "https://upload.wikimedia.org/wikipedia/commons/1/1a/Ko-%ED%9B%84.ogg",
        )
      },
      test("the DTO plays the MP3 first and links the file page") {
        val dto = CommonsAudio.toDto(WordAudioRow(1L, 2L, "De-gratis.ogg", "Germany, Berlin", 0L))
        assertTrue(
          dto.playUrls.headOption.exists(_.endsWith(".mp3")),
          dto.playUrls.size == 2,
          dto.filePageUrl == "https://commons.wikimedia.org/wiki/File:De-gratis.ogg",
          dto.region == "Germany, Berlin",
        )
      },
    )
  }
}
