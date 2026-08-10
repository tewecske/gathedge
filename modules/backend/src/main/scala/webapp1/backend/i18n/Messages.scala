package webapp1.backend.i18n

import webapp1.shared.domain.Locale
import webapp1.shared.domain.Locale.code
import webapp1.shared.i18n.{MessageCatalog, MessageRef}
import zio.*
import zio.json.*

import java.nio.charset.StandardCharsets

/** The server's side of the message catalogs.
  *
  * Deliberately small, because the server localizes almost nothing: every `ApiFailure` travels as a [[MessageRef]] and
  * is worded by the browser, which is what keeps `ApiFailures`' mappers free of a locale parameter. The exception is
  * transactional email, which has no browser to render it — that alone is why this exists.
  */
trait Messages {

  def catalog(locale: Locale): MessageCatalog
}

object Messages {

  def catalog(locale: Locale): URIO[Messages, MessageCatalog] = {
    ZIO.serviceWith[Messages](_.catalog(locale))
  }

  /** Classpath location of one catalog. The files live under `web/public/locales` in the repo and reach the jar via
    * `Compile / unmanagedResourceDirectories` in `build.sbt`, which lands them at the classpath root.
    */
  private[i18n] def resourcePath(locale: Locale): String = s"/messages.${locale.code}.json"

  private def load(locale: Locale): Task[MessageCatalog] = {
    val path = resourcePath(locale)
    for {
      text    <- ZIO.attemptBlocking {
                   Option(getClass.getResourceAsStream(path)) match {
                     case None         =>
                       throw new RuntimeException(s"Message catalog '$path' is not on the classpath")
                     case Some(stream) =>
                       try {
                         new String(stream.readAllBytes().nn, StandardCharsets.UTF_8)
                       } finally {
                         stream.close()
                       }
                   }
                 }
      entries <- ZIO
                   .fromEither(text.fromJson[Map[String, String]])
                   .mapError(error => new RuntimeException(s"Message catalog '$path' is not valid JSON: $error"))
    } yield MessageCatalog(locale, entries)
  }

  /** Loads every catalog once at startup and fails the boot if any is missing or malformed.
    *
    * Fail-fast rather than lazy-and-degrade, for the same reason the Flyway migration runs before the server binds: a
    * deployment whose Hungarian catalog did not make it into the image should not discover that one email at a time.
    */
  val live: TaskLayer[Messages] = {
    ZLayer.fromZIO(
      ZIO
        .foreach(Locale.all)(locale => load(locale).map(catalog => locale -> catalog))
        .map(loaded => MessagesLive(loaded.toMap))
    )
  }
}

final case class MessagesLive(catalogs: Map[Locale, MessageCatalog]) extends Messages {

  def catalog(locale: Locale): MessageCatalog = {
    catalogs.getOrElse(locale, MessageCatalog(locale, Map.empty))
  }
}
