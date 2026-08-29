package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{Gender, LanguageProfile}
import gathedge.shared.i18n.UiKeys

/** The `<select>` a word-add form offers for a noun's gender: the article itself is both the value and the label, since
  * `der`/`el` is part of the word being learned, not copy that gets translated. Extracted from `WordsPage` and
  * `WordDetailPage`, which carried the identical markup twice.
  *
  * Options come from `profile.genders`, so a language with two genders offers two, not the union across every language
  * this app teaches.
  */
object ArticleSelect {

  def render(profile: LanguageProfile, target: Var[Option[Gender]]): HtmlElement = {
    label(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(UiKeys.wordsAddGender)),
      select(
        cls    := "select select-sm w-24",
        option(value := "", I18n.t(UiKeys.wordsAddGenderNone)),
        profile.genders.flatMap(gender => profile.article(gender).map(article => option(value := article, article))),
        controlled(
          value <-- target.signal.map(gender => gender.flatMap(profile.article).getOrElse("")),
          onChange.mapToValue --> Observer[String] { article =>
            target.set(profile.genders.find(gender => profile.article(gender).contains(article)))
          },
        ),
      ),
    )
  }
}
