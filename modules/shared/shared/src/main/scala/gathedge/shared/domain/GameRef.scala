package gathedge.shared.domain

import zio.json.*

/** A game named by the two things a link to it needs: the `slug` a reader addresses it by, and the name to show.
  *
  * In `domain` rather than `dto` because [[Tag]] carries one — the game a wordlist already has — and a domain row may
  * not reach into the DTO package for a field. `GET /api/games/same-tags` answers a list of these directly, the same
  * way `GameEndpoints.setup` answers domain [[Tag]]s.
  */
final case class GameRef(slug: String, name: String) derives JsonCodec
