package gathedge.backend.service

import gathedge.backend.db.WordAudioRow
import gathedge.shared.dto.WordAudio

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Where a Wikimedia Commons file lives, derived from its name alone.
  *
  * `word_audio` stores only the file name. Commons puts each upload under the first one and two hex digits of the MD5
  * of its normalised title (`De-gratis.ogg` is under `8/88/`). It also serves an MP3 transcode of every audio file
  * under `transcoded/`. So one name gives the file, the transcode and the file page, and nothing about a URL is stored.
  *
  * The file page names the author and the licence. The licences on Commons audio (CC0, CC BY, CC BY-SA) accept a link
  * to it as the attribution, which is why every recording the page plays carries that link.
  */
object CommonsAudio {

  private val uploadBase = "https://upload.wikimedia.org/wikipedia/commons"
  private val pageBase   = "https://commons.wikimedia.org/wiki/File:"

  /** MediaWiki's title normalisation for a file name: trimmed, spaces as underscores, first letter upper case. The dump
    * gives names as a wiki page cites them (`en-us-portmanteau-1.ogg`), and the hash is over the normal form
    * (`En-us-portmanteau-1.ogg`).
    */
  def normalise(fileName: String): String = {
    val underscored = fileName.trim.replace(' ', '_')
    if (underscored.isEmpty)
      underscored
    else {
      val first = underscored.codePointAt(0)
      new String(Character.toChars(Character.toUpperCase(first))) + underscored.substring(Character.charCount(first))
    }
  }

  private def encode(name: String): String = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")

  private def hashPath(name: String): String = {
    val digest = MessageDigest.getInstance("MD5").digest(name.getBytes(StandardCharsets.UTF_8))
    val hex    = digest.map(b => f"${b & 0xff}%02x").mkString
    s"${hex.take(1)}/${hex.take(2)}"
  }

  /** The file as uploaded. */
  def originalUrl(fileName: String): String = {
    val name = normalise(fileName)
    s"$uploadBase/${hashPath(name)}/${encode(name)}"
  }

  /** The MP3 transcode Commons keeps of every audio upload. */
  def mp3Url(fileName: String): String = {
    val name    = normalise(fileName)
    val encoded = encode(name)
    s"$uploadBase/transcoded/${hashPath(name)}/$encoded/$encoded.mp3"
  }

  def filePageUrl(fileName: String): String = s"$pageBase${encode(normalise(fileName))}"

  def toDto(row: WordAudioRow): WordAudio = {
    WordAudio(
      fileName = normalise(row.fileName),
      region = row.region,
      playUrls = List(mp3Url(row.fileName), originalUrl(row.fileName)),
      filePageUrl = filePageUrl(row.fileName),
    )
  }
}
