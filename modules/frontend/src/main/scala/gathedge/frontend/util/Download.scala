package gathedge.frontend.util

import org.scalajs.dom

import scala.scalajs.js

/** Hands the browser a file to save. The application has no download endpoint — an export is data the page already
  * holds — so this builds a `Blob`, points a hidden `<a download>` at it, clicks it, and revokes the object URL. The
  * one place in the frontend that does this.
  */
object Download {

  /** Save `content` as `filename` with the given MIME type (default `application/json`). */
  def text(filename: String, content: String, mimeType: String = "application/json"): Unit = {
    val blob   = new dom.Blob(
      js.Array[dom.BlobPart](content),
      new dom.BlobPropertyBag { `type` = mimeType },
    )
    val url    = dom.URL.createObjectURL(blob)
    val anchor = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
    anchor.href = url
    anchor.setAttribute("download", filename)
    anchor.style.display = "none"
    dom.document.body.appendChild(anchor)
    anchor.click()
    dom.document.body.removeChild(anchor)
    dom.URL.revokeObjectURL(url)
  }

  /** A filesystem-safe version of `name` for use as a download filename — spaces and separators collapsed to `-`. */
  def slug(name: String): String = {
    val cleaned = name.trim.toLowerCase.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "")
    if (cleaned.isEmpty) "tag" else cleaned
  }
}
