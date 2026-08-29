package gathedge.shared.domain

/** How one [[WordLanguage]] handles grammatical gender and its articles.
  *
  * This is the one place that names an article. Nothing outside this file may write `"der"`, `"el"` or any other
  * article literal — every display, strip, or picker call goes through here, so a fifth language is one new entry
  * rather than a grep for `WordLanguage.De`.
  *
  * `articleForms` deliberately carries more than [[definiteArticles]]' values: German's declined `den`/`dem`/`des` and
  * Spanish's plural `los`/`las` are recognised on the way in even though only the singular nominative form
  * ([[definiteArticles]]) is ever shown, since `words` rows are lemmas and a plural belongs to `word_forms` instead.
  */
final case class LanguageProfile(
  genders: List[Gender],
  definiteArticles: Map[Gender, String],
  articleForms: Map[String, Gender],
  capitalizesNouns: Boolean,
) {

  def hasGenders: Boolean = genders.nonEmpty

  def article(gender: Gender): Option[String] = definiteArticles.get(gender)

  /** A gendered noun with its article in front, anything else (or a genderless language) as it stands. */
  def display(text: String, gender: Option[Gender]): String = {
    gender.flatMap(article).map(a => s"$a $text").getOrElse(text)
  }

  /** Splits a leading article off `text`, answering the bare word and the gender it names. Recognises every form in
    * [[articleForms]], not only the ones [[display]] shows — so a plural or declined article a reader typed still
    * strips correctly. A token with no such prefix (or a lone article with nothing after it) passes through unchanged.
    */
  def strip(text: String): (String, Option[Gender]) = {
    if (articleForms.isEmpty)
      (text, None)
    else {
      text.trim.split("\\s+", 2) match {
        case Array(head, rest) if articleForms.contains(head.toLowerCase) => (rest, articleForms.get(head.toLowerCase))
        case _                                                            => (text, None)
      }
    }
  }

  /** Capitalizes a noun exactly when this language's nouns are always capitalized (German). */
  def capitalize(text: String, gender: Option[Gender]): String = {
    if (capitalizesNouns && gender.isDefined) text.capitalize else text
  }
}

object LanguageProfile {

  val ungendered: LanguageProfile = LanguageProfile(Nil, Map.empty, Map.empty, capitalizesNouns = false)

  private val german: LanguageProfile = LanguageProfile(
    genders = List(Gender.Masculine, Gender.Feminine, Gender.Neuter),
    definiteArticles = Map(Gender.Masculine -> "der", Gender.Feminine -> "die", Gender.Neuter -> "das"),
    articleForms = Map(
      "der" -> Gender.Masculine,
      "den" -> Gender.Masculine,
      "dem" -> Gender.Masculine,
      "des" -> Gender.Masculine,
      "die" -> Gender.Feminine,
      "das" -> Gender.Neuter,
    ),
    capitalizesNouns = true,
  )

  private val spanish: LanguageProfile = LanguageProfile(
    genders = List(Gender.Masculine, Gender.Feminine),
    definiteArticles = Map(Gender.Masculine -> "el", Gender.Feminine -> "la"),
    articleForms = Map(
      "el"  -> Gender.Masculine,
      "los" -> Gender.Masculine,
      "la"  -> Gender.Feminine,
      "las" -> Gender.Feminine,
    ),
    capitalizesNouns = false,
  )

  def of(language: WordLanguage): LanguageProfile = {
    language match {
      case WordLanguage.De                   =>
        german
      case WordLanguage.Es                   =>
        spanish
      case WordLanguage.En | WordLanguage.Hu =>
        ungendered
    }
  }
}
