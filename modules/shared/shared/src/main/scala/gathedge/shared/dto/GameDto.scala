package gathedge.shared.dto

import gathedge.shared.domain.WordLanguage
import zio.json.*

/** What `POST /api/games` needs: the language pair to draw words from, and which of the caller's eligible tags to build
  * the game out of.
  */
final case class CreateGameRequest(sourceLanguage: WordLanguage, targetLanguage: WordLanguage, tagIds: List[Long])
    derives JsonCodec

/** `POST /api/games`'s answer: just enough to navigate to the game and show its name — the caller already knows
  * everything else it just sent. The full [[GameDetail]] is a separate `GET`.
  */
final case class GameCreated(slug: String, name: String) derives JsonCodec

final case class RenameGameRequest(name: String) derives JsonCodec

/** A game as a caller may see it: no owner-only data, no id — `slug` is what a reader addresses it by. */
final case class GameDetail(
  slug: String,
  name: String,
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  tagNames: List[String],
) derives JsonCodec
