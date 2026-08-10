package webapp1.shared.domain

import zio.json.*

/** The languages the application is translated into.
  *
  * Lives in `shared` for the same reason [[OAuthProvider]] does: four places need the same vocabulary — the
  * `users.locale` column, the URL prefix the SPA is served under (`/hu/groups`), the catalog filename the frontend
  * fetches and the backend reads off its classpath, and the `Locale` field on the wire-safe [[User]].
  *
  * Adding a third language is this enum plus a `messages.<code>.json`; `MessagesSpec` then fails until the new catalog
  * has every key.
  */
enum Locale derives JsonCodec, CanEqual {
  case En,
    Hu
}

object Locale {

  /** Used wherever nothing better is known: a request with no `X-Locale`, a URL with no prefix that somehow reached the
    * router anyway, a stored value that no longer parses.
    */
  val default: Locale = En

  val all: List[Locale] = List(En, Hu)

  /** Lower-case ISO 639-1 form. The URL prefix, the `users.locale` column, the `lang` attribute and the catalog
    * filename all use this. Kept explicit rather than derived from `toString` so renaming a case cannot silently orphan
    * every stored row — same reasoning as [[OAuthProvider.wireName]].
    */
  def languageCode(locale: Locale): String = {
    locale match {
      case En =>
        "en"
      case Hu =>
        "hu"
    }
  }

  def fromString(s: String): Option[Locale] = {
    s.toLowerCase match {
      case "en" =>
        Some(En)
      case "hu" =>
        Some(Hu)
      case _    =>
        None
    }
  }

  /** What the language picker calls it: the endonym, deliberately *not* translated. A Hungarian speaker looking at an
    * English UI has to be able to find their own language in the list, which is why "Magyar" is never "Hungarian".
    */
  def displayName(locale: Locale): String = {
    locale match {
      case En =>
        "English"
      case Hu =>
        "Magyar"
    }
  }

  /** Best match for one BCP 47 language tag — an `Accept-Language` entry, or a `navigator.language` value.
    *
    * Only the primary subtag is considered, so `hu-HU` and `en-GB` land on the right catalog. Region and script are
    * dropped rather than matched, since there is one catalog per language.
    */
  def fromLanguageTag(tag: String): Option[Locale] = {
    fromString(tag.trim.takeWhile(ch => ch != '-' && ch != '_'))
  }

  /** First understood language in an `Accept-Language` header, in the order the client listed them.
    *
    * The `q` weights are ignored: clients emit them in descending preference order anyway, and honouring them properly
    * would mean a parser for a header that only ever seeds a default the URL immediately overrides.
    */
  def fromAcceptLanguage(header: String): Option[Locale] = {
    header
      .split(',')
      .iterator
      .map(entry => entry.takeWhile(_ != ';'))
      .flatMap(tag => fromLanguageTag(tag).iterator)
      .nextOption()
  }

  extension (locale: Locale) {
    def code: String = languageCode(locale)

    def display: String = displayName(locale)

    /** The URL prefix every route is mounted under, leading slash included: `/en`, `/hu`. Waypoint calls this a route's
      * `basePath` and requires exactly this shape.
      */
    def urlPrefix: String = "/" + languageCode(locale)
  }
}
