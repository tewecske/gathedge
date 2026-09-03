package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.shared.domain.LanguageProfile

/** A daisyUI `join` of btn-styled radio inputs for a language's articles — one click sets the article prefix on
  * `textVar` instead of typing it. Picking one replaces any article already at the front of the text and refocuses the
  * input so the reader can keep typing the word straight after it. Extracted from `GamePlayPage` and `TagCreatePage`,
  * which carried the identical markup twice, one hard-coded to German's three articles.
  *
  * Options come from `profile.genders`, so a two-gender language offers two radios, not three.
  */
object ArticlePicker {

  def render(groupName: String, profile: LanguageProfile, textVar: Var[String], refocus: () => Unit): HtmlElement = {
    div(
      cls := "join",
      profile.genders.flatMap(gender => {
        profile.article(gender).map { article =>
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
      }),
    )
  }
}
