package gathedge.shared.i18n

import zio.json.*

/** A message the server has *not* rendered: a catalog key plus its arguments.
  *
  * This is what makes the API translatable without the server knowing any language. Every `ApiFailure` carries one
  * alongside its English `message`, and the side that has a catalog — the browser, or the backend's `Messages` service
  * when it composes an email — turns it into prose. The server therefore never reads `Accept-Language` for wording, and
  * `ApiFailures`' mappers keep their locale-free signatures.
  *
  * The same type is the failure channel of [[gathedge.shared.validation.Validation]], which runs on both platforms, so
  * a form and the endpoint behind it produce byte-identical messages by construction rather than by two translations
  * that have to be kept in step.
  *
  * @param key
  *   a catalog key, e.g. `auth.invalidCredentials`. Constants for every key the backend can mint live in
  *   [[MessageKeys]].
  * @param args
  *   positional substitutions for the `{0}`, `{1}`, … placeholders in the catalog entry, in order.
  */
final case class MessageRef(key: String, args: List[String] = Nil) derives JsonCodec

object MessageRef {

  /** Marks an argument that is itself a catalog key rather than literal text; see [[keyArg]]. */
  val keyArgPrefix: String = "@"

  /** An argument that should be *translated* before substitution, rather than inserted verbatim.
    *
    * `Validation.validateNonBlank` is the reason this exists: its message names the offending field, and a field label
    * needs translating just as much as the sentence around it. So the caller passes a label key and the resolver
    * expands it:
    *
    * {{{
    * MessageRef(MessageKeys.validationRequired, List(MessageRef.keyArg("field.email")))
    * // en: "Email is required"     hu: "Kötelező mező: e-mail cím"
    * }}}
    *
    * The `@` sentinel is what separates the two kinds of argument. Sniffing for "looks like a catalog key" instead
    * would misfire the day a user types something dotted into a text field, and a literal that silently resolves as a
    * key is a much worse failure than one that does not.
    */
  def keyArg(key: String): String = keyArgPrefix + key
}
