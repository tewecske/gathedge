package gathedge.backend.service

import gathedge.backend.db.{GameRepository, GameRow}
import gathedge.shared.domain.GameRef
import zio.*

/** The two questions asked about a wordlist set and the games built from it: which game is built from one wordlist
  * alone, and which games are built from exactly a given set.
  *
  * An object over the repository rather than a method on either service, for the same reason [[GlobalAdmin]] is one:
  * two services need it and neither owns the rule. `WordService` reads [[soloGameByTag]] to fill `Tag.soloGame` on the
  * two wordlist reads — which is what spares the wordlist pages a second request for it — and `GameService` answers
  * `GET /api/games/same-tags` from [[withExactTags]].
  *
  * Both start from the same two queries — the games carrying any of the asked-for wordlists, then those games' own tags
  * — so the cost does not grow with the number of wordlists asked about.
  */
object TagGames {

  /** For each of `tagIds`, the game built from that wordlist and nothing else. A wordlist with no such game is simply
    * absent from the map. Where a wordlist has several — allowed, just not recommended — the oldest is named, so every
    * reader is sent to the same game.
    */
  def soloGameByTag(repo: GameRepository, tagIds: List[Long]): UIO[Map[Long, GameRef]] = {
    val wanted = tagIds.toSet
    carryingAnyOf(repo, tagIds).map { rows =>
      rows
        .collect {
          case (game, tags) if tags.sizeIs == 1 && wanted.contains(tags.head) =>
            (tags.head, game)
        }
        .groupBy { case (tagId, _) => tagId }
        .flatMap { case (tagId, pairs) =>
          oldestFirst(pairs.map { case (_, game) => game }).headOption.map(game => tagId -> refOf(game))
        }
    }
  }

  /** The games whose wordlist set is '''exactly''' `tagIds`, oldest first — not the games that merely carry one of
    * them, which is what the games listing's own `tag` filter answers. Empty for an empty `tagIds`: no game is built
    * from no wordlist.
    */
  def withExactTags(repo: GameRepository, tagIds: List[Long]): UIO[List[GameRef]] = {
    val wanted = tagIds.toSet
    if (wanted.isEmpty)
      ZIO.succeed(Nil)
    else {
      carryingAnyOf(repo, tagIds).map { rows =>
        oldestFirst(rows.collect { case (game, tags) if tags == wanted => game }).map(refOf)
      }
    }
  }

  /** The oldest game first, which is how both questions above pick one game out of several. `id` breaks a tie between
    * two games created in the same millisecond. Shared with `GameService.duplicateTagGames`, whose report lists each
    * set's games the same way round.
    */
  def oldestFirst(games: List[GameRow]): List[GameRow] = games.sortBy(game => (game.createdAt, game.id))

  /** The candidates both questions narrow: every game carrying at least one of `tagIds`, paired with its own whole tag
    * set.
    */
  private def carryingAnyOf(repo: GameRepository, tagIds: List[Long]): UIO[List[(GameRow, Set[Long])]] = {
    for {
      candidates <- repo.gamesWithAnyTag(tagIds).orDie
      tagsByGame <- repo.tagsOfGames(candidates.map(_.id)).orDie
    } yield candidates.map(game => (game, tagsByGame.getOrElse(game.id, Nil).map(_.id).toSet))
  }

  private def refOf(game: GameRow): GameRef = GameRef(game.slug, game.name)
}
