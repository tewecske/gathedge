package gathedge.frontend.state

import gathedge.shared.domain.WordLanguage

/** The wordlists the wordlist catalog ticked, handed to `GameSetupPage` so it opens with them already chosen.
  *
  * In memory and one-shot, like [[PendingPlay]]: it only has to survive the one client-side navigation. A refresh or a
  * hand-typed URL opens the setup screen empty, as before.
  */
final case class GameSetupSeed(source: WordLanguage, target: WordLanguage, tagIds: Set[Long])

object GameSetupSeed {

  private var pending: Option[GameSetupSeed] = None

  def set(seed: GameSetupSeed): Unit = {
    pending = Some(seed)
  }

  /** Consumes the seed, so a later plain visit to the setup screen never sees it. */
  def take(): Option[GameSetupSeed] = {
    val seed = pending
    pending = None
    seed
  }
}
