package gathedge.backend.service

import zio.*

import java.nio.charset.StandardCharsets

/** The bundled wordlist a game's `slug`/`name` are generated from — see [[GameService]].
  *
  * Deliberately not the imported dictionary: a slug word has to be a plain, everyday English word regardless of which
  * languages a game studies, and `words` carries no such guarantee (it might not even contain English).
  */
trait GameWordList {
  def adjectives: List[String]
  def nouns: List[String]
}

object GameWordList {

  /** Classpath location of one wordlist file, one word per line. */
  private def resourcePath(name: String): String = s"/gamewords/$name.txt"

  private def load(name: String): Task[List[String]] = {
    val path = resourcePath(name)
    ZIO.attemptBlocking {
      Option(getClass.getResourceAsStream(path)) match {
        case None         =>
          throw new RuntimeException(s"Game wordlist '$path' is not on the classpath")
        case Some(stream) =>
          try {
            val text = new String(stream.readAllBytes().nn, StandardCharsets.UTF_8)
            text.linesIterator.map(_.trim).filter(_.nonEmpty).toList
          } finally {
            stream.close()
          }
      }
    }
  }

  /** Loads both wordlists once at startup and fails the boot if either is missing or empty — the same fail-fast
    * reasoning as [[gathedge.backend.i18n.Messages.live]]: a deployment that shipped without its wordlist should not
    * discover that the first time somebody tries to create a game.
    */
  val live: TaskLayer[GameWordList] = {
    ZLayer.fromZIO(
      for {
        adjectives <- load("adjectives")
        nouns      <- load("nouns")
        _          <- ZIO.when(adjectives.isEmpty || nouns.isEmpty)(
                        ZIO.fail(new RuntimeException("Game wordlist is present but empty"))
                      )
      } yield GameWordListLive(adjectives, nouns)
    )
  }
}

final case class GameWordListLive(adjectives: List[String], nouns: List[String]) extends GameWordList
