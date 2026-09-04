package gathedge.shared.parsing

import gathedge.shared.domain.{Gender, LanguageProfile, PartOfSpeech, WordLanguage}

/** One language column's cell, parsed: the word itself with every marker lifted off, plus what those markers said.
  *
  * `text` is what reaches `words.text`, so it must be free of `+D`, `(G)` and the like — that is the whole point of
  * parsing them out. A marker left in the text would land in `text_norm` and split `helfen` from `helfen +D` into two
  * unrelated dictionary rows.
  */
final case class WordCell(
  text: String,
  gender: Option[Gender],
  partOfSpeech: PartOfSpeech,
  relations: List[String],
) {

  /** The same cell with a gender an extra column supplied, and everything that follows from it re-derived.
    *
    * Necessary because the part of speech depends on the gender — a one-word cell is a noun exactly when a gender is
    * known — and an extra column is read after [[WordCell.parseWord]] has already had to decide. Without this, `Hund`
    * in one column with `hn` in the next stays a genderless `Other`, and `WordService.ensure` then discards the gender
    * for not being a noun's.
    *
    * A gender already found on the word itself wins: an article is on the word, an extra column is a note beside it.
    */
  def withGender(extra: Option[Gender], language: WordLanguage): WordCell = {
    val merged = gender.orElse(extra.filter(LanguageProfile.of(language).genders.contains))
    if (merged == gender) this else WordCell.classified(text, merged, relations, language)
  }
}

/** An extra column's cell: metadata about the word beside it, never a word to import on its own.
  *
  * `formWords` are the leftover runs that no marker explains — an actual inflected word such as `hilft` or `Häuser`.
  * They become `word_forms` rows, but only when `relations` also names what they are: a form with no relation has
  * nothing to be filed under, since `word_forms.relation` is `NOT NULL` and part of its UNIQUE key.
  */
final case class ExtraCell(
  gender: Option[Gender],
  relations: List[String],
  formWords: List[String],
)

object WordCell {

  /** A parenthesised or bracketed group: `(G)`, `[pl]`, `(3rd, sg)`. The inner text is split further, so one group may
    * carry several markers.
    */
  private val groupPattern = """[(\[]([^)\]]*)[)\]]""".r

  /** The attached government notation: `+D`, `+ G`, `/A`. The sign is what marks it as a marker rather than a word, so
    * the sign is required — see the note on [[parseWord]] about bare tokens.
    */
  private val signedPattern = """[+/]\s*(\p{L}+|\d+)""".r

  /** What separates markers inside one group or one extra-column cell. */
  private val markerSeparator = """[,;/\s]+"""

  /** Splits a marker blob into candidate tokens, dropping the `+` a signed marker may still carry. */
  private def markerTokens(blob: String): List[String] = {
    blob
      .split(markerSeparator)
      .iterator
      .map(_.trim.stripPrefix("+"))
      .filter(_.nonEmpty)
      .toList
  }

  /** Everything a group or signed marker contributed, in one pass: the first gender named, and every relation named.
    * Unrecognised tokens are dropped — a list may carry notation this vocabulary has never seen, and refusing the row
    * over it would lose the word as well.
    */
  private def classify(tokens: List[String], markers: MarkerVocabulary): (Option[Gender], List[String]) = {
    val gender    = tokens.flatMap(markers.gender).headOption
    val relations = tokens.flatMap(markers.relation).distinct.sorted
    (gender, relations)
  }

  private def collapse(text: String): String = text.trim.replaceAll("""\s+""", " ")

  /** Lifts every decorated marker out of `raw`, answering what was left and what the markers said. */
  private def lift(raw: String, markers: MarkerVocabulary): (String, Option[Gender], List[String]) = {
    // `Match.group` is nullable under explicit nulls: a group that did not participate answers null, which cannot
    // happen for these two single-group patterns but still has to be spelled out.
    val groups  = groupPattern.findAllMatchIn(raw).flatMap(m => Option(m.group(1))).toList
    val signed  = signedPattern.findAllMatchIn(raw).flatMap(m => Option(m.group(1))).toList
    val without = signedPattern.replaceAllIn(groupPattern.replaceAllIn(raw, " "), " ")

    val (gender, relations) = classify(groups.flatMap(markerTokens) ++ signed.flatMap(markerTokens), markers)
    (collapse(without), gender, relations)
  }

  /** Reads one language column's cell.
    *
    * '''Only decorated markers are recognised here.''' A bare trailing token is kept as part of the word, because the
    * word column is the one place a marker abbreviation can also be a real word: German `es` is both a third-person
    * marker and the pronoun starting `es regnet`. Undecorated markers belong in the extra column, which [[parseExtra]]
    * reads with no such ambiguity.
    *
    * Order matters. The article is stripped ''after'' the markers, so `der Hund (m)` loses both and reduces to one
    * word; and the word count that decides [[PartOfSpeech.Phrase]] runs ''after'' the article is gone, so `der Hund`
    * stays a `Noun` while `guten Tag` becomes a phrase. That is the "not just a definite article and a word" rule, with
    * no special case needed for it.
    */
  def parseWord(raw: String, language: WordLanguage, markers: MarkerVocabulary): WordCell = {
    val profile                             = LanguageProfile.of(language)
    val (withoutMarkers, marked, relations) = lift(raw, markers)
    val (bare, fromArticle)                 = profile.strip(withoutMarkers)

    // The article is on the word itself, so it outranks a marker somebody wrote beside it. A gender the language does
    // not have is dropped here rather than at the database, the same rule `WordService.ensure` applies.
    val gender = fromArticle.orElse(marked).filter(profile.genders.contains)

    classified(collapse(bare), gender, relations, language)
  }

  /** The part of the parse that depends on the gender: how many words are left decides [[PartOfSpeech.Phrase]], and a
    * single word is a noun exactly when a gender is known. Shared with [[WordCell.withGender]], so an extra column's
    * gender produces the same answer as one written on the word itself.
    */
  private def classified(
    text: String,
    gender: Option[Gender],
    relations: List[String],
    language: WordLanguage,
  ): WordCell = {
    val words        = if (text.isEmpty) 0 else text.split(" ").length
    val partOfSpeech = {
      if (words >= 2) PartOfSpeech.Phrase
      else if (gender.isDefined) PartOfSpeech.Noun
      else PartOfSpeech.Other
    }

    WordCell(LanguageProfile.of(language).capitalize(text, gender), gender, partOfSpeech, relations)
  }

  /** Reads an extra column's cell, where every token is metadata and nothing is a word to import.
    *
    * Bare tokens '''are''' classified here, unlike in [[parseWord]]: there is no word to confuse them with. A token the
    * vocabulary knows is a marker; an alphabetic run it does not know is a form word.
    */
  def parseExtra(raw: String, language: WordLanguage, markers: MarkerVocabulary): ExtraCell = {
    val (leftover, groupGender, groupRelations) = lift(raw, markers)
    val tokens                                  = markerTokens(leftover)
    val (known, unknown)                        = tokens.partition(markers.knows)
    val (bareGender, bareRelations)             = classify(known, markers)

    val gender = groupGender.orElse(bareGender).filter(LanguageProfile.of(language).genders.contains)

    ExtraCell(
      gender = gender,
      relations = (groupRelations ++ bareRelations).distinct.sorted,
      formWords = unknown.filter(_.exists(_.isLetter)),
    )
  }
}
