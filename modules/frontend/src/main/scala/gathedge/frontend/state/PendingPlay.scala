package gathedge.frontend.state

import gathedge.shared.dto.GameVariantDto

/** What `GamePlayPage` needs to start straight into `Phase.Playing` with no extra fetch of its own: `wordCount` (only
  * ever returned once, by `startPlay`'s `PlayStarted` — `GamePrompt` never carries it), `gameName` for its
  * `GameHeader`, and the resolved `variant` this play actually runs under — its `targetLanguage` is already the answer
  * language post-swap, which is what both the gender-article picker's visibility and the header's word-config line read
  * off it. Precomputed by whichever caller already had the game and the chosen variant in hand at `startPlay` time,
  * rather than re-fetched here.
  */
final case class PlayHandoff(gameName: String, wordCount: Int, variant: GameVariantDto)

/** A one-shot hand-off from wherever a play is started (`GameInstancePage`'s picker, or `GameReplay.start`'s two
  * callers) to the freshly navigated-to `GamePlayPage`. In-memory, not `localStorage`: this only needs to survive one
  * client-side navigation within the same page load, the same reasoning the old `PendingReplay` (which this replaces)
  * used.
  */
object PendingPlay {

  private var pending: Option[(Long, PlayHandoff)] = None

  def set(playId: Long, handoff: PlayHandoff): Unit = {
    pending = Some(playId -> handoff)
  }

  /** Consumes the hint if it matches `playId` — one-shot, so a later plain visit to the same play never re-triggers it.
    * Absent on a raw URL visit, refresh, or back/forward navigation; `GamePlayPage` falls back to the picker in that
    * case, the same as a mid-play refresh already does today.
    */
  def take(playId: Long): Option[PlayHandoff] = {
    pending match {
      case Some((`playId`, handoff)) =>
        pending = None
        Some(handoff)
      case _                         =>
        None
    }
  }
}
