package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.domain.WordLanguage
import gathedge.shared.dto.GameVariantDto

/** Starts a fresh play reusing a past `GameVariantDto` exactly — `MyPlayHistoryPage`'s "Play again" and
  * `GamePlayPage`'s own finished-screen "Play again" both need this. [[GameApiClient.startPlay]] only takes a
  * `swapDirection` relative to the game's own stored direction, not raw languages, so this composes a
  * [[GameApiClient.get]] (to learn that stored direction) with the [[GameApiClient.startPlay]] call itself, rather than
  * living as a single generated endpoint wrapper the way the rest of `GameApiClient` does.
  *
  * No guest-mint (`asReader`) wrapping: every caller already has an established session by the time it calls this — the
  * history page requires auth, and the finished screen is reached only after the original `startPlay`'s own `asReader`
  * already ran.
  */
object GameReplay {

  /** Everything `GamePlayPage` needs to start straight into `Phase.Playing`: `slug`/`playId` for the route it's about
    * to be navigated to, and `wordCount`/`showGenderPicker` for `PendingPlay`'s hand-off.
    */
  final case class Started(slug: String, playId: Long, wordCount: Int, showGenderPicker: Boolean)

  def start(slug: String, variant: GameVariantDto): EventStream[Either[ApiError, Started]] = {
    GameApiClient.get(slug).flatMapSwitch {
      case Right(detail) =>
        val swap           = variant.sourceLanguage != detail.sourceLanguage
        val answerLanguage = if (swap) detail.sourceLanguage else detail.targetLanguage
        val showGender     = variant.includeDefiniteArticles && answerLanguage == WordLanguage.De
        GameApiClient
          .startPlay(slug, swap, variant.wordLimit, variant.includeDefiniteArticles, variant.wordPreference)
          .map(_.map(started => Started(slug, started.playId, started.wordCount, showGender)))
      case Left(err)     =>
        EventStream.fromValue(Left(err))
    }
  }
}
