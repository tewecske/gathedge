package gathedge.shared.parsing

import gathedge.shared.domain.{Gender, WordLanguage}

/** The abbreviations a vocabulary list writes beside a word to state its gender or the case it governs — `m`, `w`,
  * `hn`, `+D`, `(G)`.
  *
  * '''A marker is written in the reader's language, not the column's.''' A Hungarian learner's German list marks gender
  * `hn`/`nn`/`sn`; a German-written list uses `m`/`w`/`s`, where `w` is feminine and `f` is not; an English-written one
  * uses `m`/`f`/`n`. So every language's abbreviations must be recognised on any column, which makes collisions between
  * them unavoidable and [[MarkerVocabulary.forPair]] the thing that resolves them.
  *
  * Same shape as `LanguageProfile`: one entry per language, so a fifth language is one new entry here rather than a
  * grep. '''Articles are deliberately absent''' — `der`/`die`/`el`/`la` come from `LanguageProfile.articleForms`, which
  * is the only file permitted to name an article, and [[WordCell]] reads gender from the union of the two.
  *
  * `relations` values are canonicalised to the strings `word_forms.relation` already carries (see
  * `gathedge.shared.domain.GrammarTag`), so a parsed marker renders through `Labels.grammarRelation` with no new code.
  */
final case class MarkerVocabulary(
  genders: Map[String, Gender],
  relations: Map[String, String],
) {

  /** The gender this token names, if any. Keys are already normalised, so callers pass a raw token. */
  def gender(token: String): Option[Gender] = genders.get(MarkerVocabulary.normalize(token))

  /** The canonical `word_forms.relation` tag this token names, if any. */
  def relation(token: String): Option[String] = relations.get(MarkerVocabulary.normalize(token))

  /** True when the token means anything at all here — what [[WordCell]] uses to decide whether a stray token is a
    * marker to lift out or an ordinary word to keep.
    */
  def knows(token: String): Boolean = {
    val key = MarkerVocabulary.normalize(token)
    genders.contains(key) || relations.contains(key)
  }

  /** `this` wins every key it defines; `other` fills in the rest. The whole of [[MarkerVocabulary.forPair]]'s
    * precedence is built from this one operation.
    */
  def orElse(other: MarkerVocabulary): MarkerVocabulary = {
    MarkerVocabulary(
      genders = other.genders ++ genders,
      relations = other.relations ++ relations,
    )
  }
}

object MarkerVocabulary {

  /** How a token becomes a lookup key: lower-cased, with a trailing abbreviation dot dropped, so `Gen.` and `gen` are
    * one entry rather than two. Nothing else is stripped here — the surrounding `+`/`(` punctuation is [[WordCell]]'s
    * business, since it is what tells a marker from a word in the first place.
    */
  def normalize(token: String): String = {
    val trimmed = token.trim.toLowerCase
    if (trimmed.endsWith(".")) trimmed.dropRight(1) else trimmed
  }

  private def vocabulary(
    masculine: List[String],
    feminine: List[String],
    neuter: List[String],
    relations: List[(String, List[String])],
  ): MarkerVocabulary = {
    val genderEntries = List(
      masculine -> Gender.Masculine,
      feminine  -> Gender.Feminine,
      neuter    -> Gender.Neuter,
    ).flatMap { case (tokens, gender) => tokens.map(token => normalize(token) -> gender) }

    val relationEntries = relations.flatMap { case (canonical, tokens) =>
      tokens.map(token => normalize(token) -> canonical)
    }

    MarkerVocabulary(genderEntries.toMap, relationEntries.toMap)
  }

  private val english: MarkerVocabulary = vocabulary(
    masculine = List("m", "masc", "masculine"),
    feminine = List("f", "fem", "feminine"),
    neuter = List("n", "neut", "neuter"),
    relations = List(
      "genitive"     -> List("gen", "genitive"),
      "dative"       -> List("dat", "dative"),
      "accusative"   -> List("acc", "accusative"),
      "nominative"   -> List("nom", "nominative"),
      "plural"       -> List("pl", "plural"),
      "singular"     -> List("sg", "sing", "singular"),
      "third-person" -> List("3", "3rd", "third"),
    ),
  )

  /** German is the one that most needs its own entry: `w` (weiblich) is feminine and `s` (sächlich) is neuter, so a
    * table built only from English abbreviations misreads half of every German-written list. `f` still resolves, since
    * `feminin` is written too.
    *
    * `r`/`e`/`s` are the article's own last letter — de'''r''' / di'''e''' / da'''s''' — which is how a gender column
    * is written in most German course material and in every Hungarian-schooled learner's list. They are here rather
    * than in `LanguageProfile` because they are an abbreviation of the article, not an article: nobody writes `r Hund`
    * and expects to read it aloud. `s` was already neuter through `sächlich`, so only two keys are new.
    *
    * `sich` is a relation for the same reason `es` is: the reflexive pronoun beside a verb states which form the entry
    * is, and the word column already holds the verb.
    */
  private val german: MarkerVocabulary = vocabulary(
    masculine = List("m", "mask", "maskulin", "männlich", "maennlich", "r"),
    feminine = List("w", "weiblich", "f", "fem", "feminin", "e"),
    neuter = List("s", "sächlich", "saechlich", "n", "neutrum"),
    relations = List(
      "genitive"     -> List("g", "gen", "genitiv"),
      "dative"       -> List("d", "dat", "dativ"),
      "accusative"   -> List("a", "akk", "akkusativ"),
      "nominative"   -> List("nom", "nominativ"),
      "plural"       -> List("pl", "plural", "mehrzahl"),
      "singular"     -> List("sg", "singular", "einzahl"),
      "third-person" -> List("3", "es"),
      "reflexive"    -> List("sich"),
    ),
  )

  /** Hungarian has no gender of its own, so these markers are only ever seen describing the *other* column's word —
    * which is exactly why they have to be recognised regardless of which language a column holds.
    */
  private val hungarian: MarkerVocabulary = vocabulary(
    masculine = List("hn", "hímnem", "himnem"),
    feminine = List("nn", "nőnem", "nonem"),
    neuter = List("sn", "semlegesnem"),
    relations = List(
      "genitive"   -> List("birt", "birtokos"),
      "dative"     -> List("rész", "resz", "részes", "reszes"),
      "accusative" -> List("tárgy", "targy", "tárgyeset", "targyeset"),
      "nominative" -> List("alany", "alanyeset"),
      "plural"     -> List("tsz", "többes", "tobbes"),
      "singular"   -> List("esz", "egyes"),
    ),
  )

  private val spanish: MarkerVocabulary = vocabulary(
    masculine = List("m", "masc", "masculino"),
    feminine = List("f", "fem", "femenino"),
    neuter = Nil,
    relations = List(
      "genitive"     -> List("gen", "genitivo"),
      "plural"       -> List("pl", "plural"),
      "singular"     -> List("sg", "sing", "singular"),
      "third-person" -> List("3", "tercera"),
    ),
  )

  /** The symbolic government notation, which means the same thing whoever wrote the list — a German convention that
    * travels into lists written in any of the four languages. Always active, under every pairing, so it never depends
    * on a collision being resolved one way.
    *
    * The bare letters are here without their `+`/`(` wrapper: [[WordCell]] strips that punctuation before looking a
    * token up, since the punctuation is what identified the token as a marker.
    */
  private val symbolic: MarkerVocabulary = vocabulary(
    masculine = Nil,
    feminine = Nil,
    neuter = Nil,
    relations = List(
      "genitive"   -> List("g"),
      "dative"     -> List("d"),
      "accusative" -> List("a"),
    ),
  )

  def of(language: WordLanguage): MarkerVocabulary = {
    language match {
      case WordLanguage.En =>
        english
      case WordLanguage.De =>
        german
      case WordLanguage.Es =>
        spanish
      case WordLanguage.Hu =>
        hungarian
    }
  }

  /** The vocabulary one column is read with: its own language wins every collision, the tag's other language fills in
    * next, and the remaining languages fill in last so an abbreviation from neither is still understood.
    *
    * That last tier is what makes `hn` work on a German column in a Hungarian→German list. The tier order is what makes
    * `n` neuter there rather than the first letter of `nőnem`, and `w` feminine rather than unknown.
    */
  def forPair(primary: WordLanguage, secondary: WordLanguage): MarkerVocabulary = {
    val rest = WordLanguage.all.filterNot(language => language == primary || language == secondary)
    val tail = rest.foldLeft(symbolic)((acc, language) => acc.orElse(of(language)))
    of(primary).orElse(of(secondary)).orElse(tail)
  }
}
