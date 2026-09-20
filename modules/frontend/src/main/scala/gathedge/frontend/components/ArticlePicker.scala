package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.shared.domain.LanguageProfile

/** A daisyUI `join` of btn-styled radio inputs for a language's articles — one click sets the article prefix on
  * `textVar` instead of typing it. Picking one replaces any article already at the front of the text and refocuses the
  * input so the reader can keep typing the word straight after it. Extracted from `GamePlayPage` and `TagCreatePage`,
  * which carried the identical markup twice, one hard-coded to German's three articles.
  *
  * `articles` is the list of buttons to offer, which the caller reads off the profile: the citation cell's articles
  * where a lemma is being written, and the cell the game is asking for where a play narrowed them. Passed in rather
  * than derived here, because only the caller knows which declension cell it is in.
  */
object ArticlePicker {

  def render(
    groupName: String,
    profile: LanguageProfile,
    articles: List[String],
    textVar: Var[String],
    refocus: () => Unit,
  ): HtmlElement = {
    div(
      cls := "join",
      articles.map { article =>
        {
          input(
            typ        := "radio",
            cls        := "join-item btn btn-xs",
            nameAttr   := groupName,
            aria.label := article,
            controlled(
              checked <-- textVar.signal.map { text =>
                val low = text.trim.toLowerCase
                low == article || low.startsWith(article + " ")
              },
              onClick.mapToUnit --> Observer[Unit] { _ =>
                // `strip` leaves a lone article untouched (by design — "der" alone is not a gendered word), so switching
                // articles on a field that holds only one has to drop it here, or the new one lands in front of the old.
                val current = textVar.now().trim
                val bare    =
                  if (profile.articleForms.contains(current.toLowerCase)) "" else profile.strip(current)._1
                textVar.set(s"$article $bare")
                refocus()
              },
            ),
          )
        }
      },
    )
  }
}
