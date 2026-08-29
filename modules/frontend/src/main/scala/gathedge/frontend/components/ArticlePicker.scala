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
              checked <-- textVar.signal.map(_.toLowerCase.startsWith(article + " ")),
              onClick.mapToUnit --> Observer[Unit] { _ =>
                textVar.set(s"$article ${profile.strip(textVar.now())._1}")
                refocus()
              },
            ),
          )
        }
      }),
    )
  }
}
