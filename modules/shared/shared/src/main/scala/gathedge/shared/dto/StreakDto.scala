package gathedge.shared.dto

import gathedge.shared.domain.StreakState
import zio.json.*

/** The account's daily play streak, from `GET /api/me/streak`.
  *
  * `current` is what the header shows and is `0` when `state` is `Inactive`. `longest` and `totalDays` feed the profile
  * page.
  */
final case class StreakResponse(current: Int, longest: Int, totalDays: Int, state: StreakState) derives JsonCodec
