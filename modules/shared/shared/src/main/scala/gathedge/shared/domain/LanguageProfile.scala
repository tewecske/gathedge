package gathedge.shared.domain

/** How one [[WordLanguage]] handles grammatical gender and its articles.
  *
  * This is the one place that names an article. Nothing outside this file may write `"der"`, `"el"` or any other
  * article literal — every display, strip, or picker call goes through here, so a fifth language is one new entry
  * rather than a grep for `WordLanguage.De`.
  *
  * `declinedArticles` is the whole grammar table — one article per (case, number, gender) cell. A lemma stands in
  * [[FormSlot.citation]], so `der Tisch` and the dative plural `den Tischen` come out of one lookup rather than out of
  * two rules. `articleForms` is its inverse for reading: it maps every article a reader may type back to a gender, and
  * carries the ambiguous declined forms (`den`, `dem`, `des`) that the citation cell alone would not explain.
  */
final case class LanguageProfile(
  genders: List[Gender],
  declinedArticles: Map[FormSlot, Map[Gender, String]],
  articleForms: Map[String, Gender],
  capitalizesNouns: Boolean,
) {

  def hasGenders: Boolean = genders.nonEmpty

  /** The article this language shows a lemma with — its declension table's citation cell, never a second list that
    * could drift out of step with it.
    */
  def article(gender: Gender): Option[String] = declinedArticle(gender, FormSlot.citation)

  /** The citation article of every gender this language has. */
  def definiteArticles: Map[Gender, String] = declinedArticles.getOrElse(FormSlot.citation, Map.empty)

  /** The definite article a noun of this gender takes in this declension cell — `die` for a feminine nominative
    * singular, `den` for any dative plural, `des` for a masculine genitive singular.
    *
    * [[FormSlot.citation]] answers exactly what [[article]] does, which is what lets a lemma and a declined form go
    * through one rule rather than two. A genderless language has no table at all and answers `None` everywhere, so a
    * Hungarian case — and Hungarian has eighteen — never produces an article that does not exist.
    */
  def declinedArticle(gender: Gender, slot: FormSlot): Option[String] = {
    declinedArticles.get(slot).flatMap(_.get(gender))
  }

  /** Every article this language can put in front of a noun, in a stable order — the full set an `All` article mode
    * offers. Deduplicated, since German's `der` is the masculine nominative singular, the feminine genitive singular
    * and the genitive plural all at once.
    */
  def allArticles: List[String] = {
    FormSlot.all.flatMap(slot => genders.flatMap(gender => declinedArticle(gender, slot))).distinct
  }

  /** The articles this language allows in one declension cell, in gender order and deduplicated — what a `FormSpecific`
    * article mode offers. A nominative singular gives three (`der`, `die`, `das`), a dative singular two (`dem`,
    * `der`), and every plural cell exactly one, since a German plural article ignores gender.
    */
  def articlesFor(slot: FormSlot): List[String] = {
    genders.flatMap(gender => declinedArticle(gender, slot)).distinct
  }

  /** A gendered noun with its article in front, anything else (or a genderless language) as it stands. */
  def display(text: String, gender: Option[Gender]): String = {
    displayIn(text, gender, FormSlot.citation)
  }

  /** [[display]] for a noun standing in a named declension cell: `die Sache` in the nominative singular, `den Sachen`
    * in the dative plural. The slot decides the article, the gender decides which column of that slot's row is read,
    * and a word with neither keeps the text it came with.
    */
  def displayIn(text: String, gender: Option[Gender], slot: FormSlot): String = {
    gender.flatMap(g => declinedArticle(g, slot)).map(a => s"$a $text").getOrElse(text)
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

  val ungendered: LanguageProfile =
    LanguageProfile(Nil, Map.empty, Map.empty, capitalizesNouns = false)

  /** Builds a declension table from its two halves, the shape a grammar book prints one number per block. */
  private def declension(
    singular: Map[GrammaticalCase, Map[Gender, String]],
    plural: Map[GrammaticalCase, Map[Gender, String]],
  ): Map[FormSlot, Map[Gender, String]] = {
    singular.map { case (c, byGender) => FormSlot(c, GrammaticalNumber.Singular) -> byGender } ++
      plural.map { case (c, byGender) => FormSlot(c, GrammaticalNumber.Plural) -> byGender }
  }

  /** One article for every gender — how a German plural cell is written, since `den Sachen` and `den Tischen` really do
    * share an article.
    */
  private def everyGender(article: String): Map[Gender, String] = {
    Gender.all.map(gender => gender -> article).toMap
  }

  /** The same cell repeated across all four cases — how a language whose articles do not decline for case is written.
    */
  private def everyCase(byGender: Map[Gender, String]): Map[GrammaticalCase, Map[Gender, String]] = {
    GrammaticalCase.all.map(c => c -> byGender).toMap
  }

  private val german: LanguageProfile = LanguageProfile(
    genders = List(Gender.Masculine, Gender.Feminine, Gender.Neuter),
    declinedArticles = declension(
      singular = Map(
        GrammaticalCase.Nominative -> Map(Gender.Masculine -> "der", Gender.Feminine -> "die", Gender.Neuter -> "das"),
        GrammaticalCase.Genitive   -> Map(Gender.Masculine -> "des", Gender.Feminine -> "der", Gender.Neuter -> "des"),
        GrammaticalCase.Dative     -> Map(Gender.Masculine -> "dem", Gender.Feminine -> "der", Gender.Neuter -> "dem"),
        GrammaticalCase.Accusative -> Map(Gender.Masculine -> "den", Gender.Feminine -> "die", Gender.Neuter -> "das"),
      ),
      plural = Map(
        GrammaticalCase.Nominative -> everyGender("die"),
        GrammaticalCase.Genitive   -> everyGender("der"),
        GrammaticalCase.Dative     -> everyGender("den"),
        GrammaticalCase.Accusative -> everyGender("die"),
      ),
    ),
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
    // Spanish articles do not decline for case, so every case of a number holds the same pair. The table still has
    // four cases rather than one, because the slot a caller asks with comes from a `word_forms.relation` string that
    // may well name a case this language ignores.
    declinedArticles = declension(
      singular = everyCase(Map(Gender.Masculine -> "el", Gender.Feminine -> "la")),
      plural = everyCase(Map(Gender.Masculine -> "los", Gender.Feminine -> "las")),
    ),
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
