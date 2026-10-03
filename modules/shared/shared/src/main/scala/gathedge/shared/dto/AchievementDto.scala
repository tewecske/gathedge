package gathedge.shared.dto

import zio.json.*

/** One achievement as it stands for the signed-in account, from `GET /api/me/achievements`.
  *
  * `code` names an entry of `Achievements.all`. `tier` is `0` for an achievement not started. `count` is the progress
  * the tier is measured on. `nextThreshold` is the count the next tier needs, and `None` once a one-off is earned.
  * `unlockedAt` is when the account reached the current tier, in epoch millis.
  */
final case class AchievementProgress(
  code: String,
  tier: Int,
  count: Int,
  nextThreshold: Option[Int],
  unlockedAt: Option[Long],
) derives JsonCodec

/** Every achievement of the catalog, started or not, in catalog order. */
final case class AchievementsResponse(achievements: List[AchievementProgress]) derives JsonCodec

/** One tier the account reached just now. A play that crosses two rungs at once gives two of these. */
final case class AchievementUnlock(code: String, tier: Int, unlockedAt: Long) derives JsonCodec
