package webapp1.frontend.i18n

import org.scalajs.dom
import webapp1.shared.domain.Locale
import webapp1.shared.domain.Locale.code
import webapp1.shared.i18n.{MessageCatalog, MessageRef}
import zio.json.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

/** Every user-facing string in the frontend comes through here.
  *
  * Synchronous by construction: [[load]] runs once in `Main` and the app is not mounted until it has finished, so no
  * element is ever built before its catalog exists. That is only possible because the locale comes from the URL rather
  * than from `/api/me` — see [[CurrentLocale]].
  */
object I18n {

  /** A `var` rather than a `Var`: this is written exactly once, before the first render, and read from ordinary
    * (non-reactive) code all over the page tree. Making it observable would suggest it can change during a page's life,
    * which it cannot — changing language reloads the document.
    */
  private var catalog: MessageCatalog = MessageCatalog(CurrentLocale.value, Map.empty)

  def locale: Locale = catalog.locale

  private[i18n] def install(loaded: MessageCatalog): Unit = {
    catalog = loaded
  }

  /** Where the catalogs are served from: `web/public/locales`, which Vite serves in dev and nginx serves out of the
    * built image, both with no configuration. The backend reads the very same files off its classpath.
    */
  private def url(locale: Locale): String = s"/locales/messages.${locale.code}.json"

  /** Fetches the catalog for `locale` and installs it. Never fails the caller: a page rendering its own keys is bad,
    * but a blank page because one asset 404'd is worse, and the keys say exactly what went wrong.
    */
  def load(locale: Locale): Future[Unit] = {
    dom
      .fetch(url(locale))
      .toFuture
      .flatMap(response => response.text().toFuture)
      .map(_.fromJson[Map[String, String]])
      .transform {
        case Success(Right(entries)) =>
          Success(install(MessageCatalog(locale, entries)))
        case Success(Left(error))    =>
          dom.console.error(s"i18n: ${url(locale)} did not parse: $error")
          Success(())
        case Failure(error)          =>
          dom.console.error(s"i18n: could not load ${url(locale)}: ${error.getMessage}")
          Success(())
      }
  }

  /** Looks up `key`, substituting `args` for its `{0}`, `{1}`, … placeholders.
    *
    * A missing key renders as the key itself, and says so in the console. `MessagesSpec` is what actually prevents it —
    * this is the safety net for the gap that spec cannot see, which is a key only the frontend uses.
    */
  def t(key: String, args: Any*): String = {
    if (!catalog.contains(key)) {
      dom.console.warn(s"i18n: no '$key' in the ${catalog.locale.code} catalog")
    }
    catalog(key, args.map(_.toString)*)
  }

  /** Looks up `key` without warning when it is absent.
    *
    * For the one shape where a miss is expected rather than a bug: a label derived from a stored code (a
    * `login_attempts.outcome`, an `audit_log.action`) that a newer build may have written and this catalog has never
    * heard of. Everything else should go through [[t]], whose warning is the safety net.
    */
  def get(key: String): Option[String] = catalog.get(key)

  /** A count-sensitive message; see `MessageCatalog.plural` for why the choice is per-language. */
  def plural(baseKey: String, count: Long, args: Any*): String = {
    catalog.plural(baseKey, count, args.map(_.toString)*)
  }

  /** The worded message from a failed `Validation` check, or `None` if it passed.
    *
    * `Validation` fails with a `MessageRef` rather than a string precisely so the form and the endpoint behind it say
    * the same thing in the same language; this is the form's side of that.
    */
  def errorOf(result: Either[MessageRef, ?]): Option[String] = {
    result.left.toOption.map(resolve)
  }

  /** Words a message the *server* minted. This is the other half of the codes-on-the-wire decision: `ApiFailures`
    * chooses a key and never a language, and the wording is chosen here, where the catalog is.
    */
  def resolve(ref: MessageRef): String = {
    if (!catalog.contains(ref.key)) {
      dom.console.warn(s"i18n: no '${ref.key}' in the ${catalog.locale.code} catalog")
    }
    catalog.resolve(ref)
  }
}
