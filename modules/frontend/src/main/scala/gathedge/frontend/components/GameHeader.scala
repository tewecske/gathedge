package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.shared.domain.WordLanguage
import gathedge.shared.dto.GameVariantDto

/** The game-identity line — name, then language pair (and, once a variant is known, the word config played under it) —
  * shared by `GamePlayPage` (both its playing and finished phases) and `GameResultsPage`, so the same play reads
  * identically wherever its game is named. `GameInstancePage`'s own picker card keeps its own richer header (rename,
  * tags, share, QR): those are owner chrome, not identity, and stay page-specific.
  */
object GameHeader {

  /** For a specific play, whose variant already carries the resolved (post-swap) language pair — `Labels.variant`
    * renders the same "source → target · preference" string every other play-visibility surface uses.
    */
  def render(name: String, variant: GameVariantDto): HtmlElement = {
    render(name, Labels.variant(variant))
  }

  /** For the base game, which has no variant of its own — word count, direction and preference are all play-time
    * choices now (see `CreateGameRequest`'s doc comment), so only the stored language pair is shown.
    */
  def render(name: String, sourceLanguage: WordLanguage, targetLanguage: WordLanguage): HtmlElement = {
    render(name, s"${Labels.language(sourceLanguage)} → ${Labels.language(targetLanguage)}")
  }

  private def render(name: String, subtitle: String): HtmlElement = {
    div(
      h2(cls := "card-title text-xl", name),
      p(cls  := "text-sm opacity-70", subtitle),
    )
  }
}
