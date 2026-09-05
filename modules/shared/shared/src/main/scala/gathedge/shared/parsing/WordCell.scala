package gathedge.shared.parsing

import gathedge.shared.domain.{Gender, LanguageProfile, PartOfSpeech, WordLanguage}

/** One word a cell named, with everything the cell said about it already applied.
  *
  * A cell can name more than one: `tető/padlás` and `kedvező, megéri` are two translations written on one line, and
  * `Jurist(in)` is a masculine word and its feminine counterpart. Each becomes its own [[ParsedWord]] and its own
  * `words` row, because that is what they are — the alternative is one row whose `text_norm` is `tető/padlás`, which no
  * search or practice prompt could ever match.
  */
final case class ParsedWord(
  text: String,
  gender: Option[Gender],
  partOfSpeech: PartOfSpeech,
)

/** One language column's cell, parsed: every word it named, the note it carried, and what its markers said.
  *
  * `text` is what reaches `words.text`, so it must be free of `+D`, `(G)` and the like — that is the whole point of
  * parsing them out. A marker left in the text would land in `text_norm` and split `helfen` from `helfen +D` into two
  * unrelated dictionary rows.
  */
final case class WordCell(
  words: List[ParsedWord],
  comment: Option[String],
  relations: List[String],
) {

  /** The first word this cell named, or `""` for a cell that named none. What a caller wanting one representative
    * string — the language check's dictionary probe — asks for.
    */
  def text: String = words.headOption.map(_.text).getOrElse("")

  /** The same cell with the genders an extra column supplied, and everything that follows from them re-derived.
    *
    * Necessary because the part of speech depends on the gender — a one-word cell is a noun exactly when a gender is
    * known — and an extra column is read after [[WordCell.parseWord]] has already had to decide. Without this, `Hund`
    * in one column with `hn` in the next stays a genderless `Other`, and `WordService.ensure` then discards the gender
    * for not being a noun's.
    *
    * The genders are consumed '''positionally''', then the first one fills in for the rest. That is what makes `r/e`
    * beside `Jurist(in)` read the way it was written: the masculine word first, the feminine one second. A single `e`
    * beside `Schneiderin/näherin` still reaches both.
    *
    * A gender already found on the word itself wins: an article is on the word, an extra column is a note beside it.
    */
  def withExtra(extra: Option[ExtraCell], language: WordLanguage): WordCell = {
    val supplied = extra.toList.flatMap(_.genders).filter(LanguageProfile.of(language).genders.contains)
    if (supplied.isEmpty) this
    else {
      val updated = words.zipWithIndex.map { case (word, index) =>
        val merged = word.gender.orElse(supplied.lift(index)).orElse(supplied.headOption)
        if (merged == word.gender) word else WordCell.classified(word.text, merged, language)
      }
      copy(words = updated)
    }
  }
}

/** An extra column's cell: metadata about the word beside it, never a word to import on its own.
  *
  * `genders` is a list rather than one gender because `r/e` — "der or die", a person whose word has both — is written
  * on every list of professions. Order is the order it was written in, which is what lets [[WordCell.withExtra]] hand
  * the second one to a `(in)` counterpart.
  *
  * `formWords` are the leftover runs that no marker explains — an actual inflected word such as `hilft` or `Häuser`.
  * They become `word_forms` rows, but only when `relations` also names what they are: a form with no relation has
  * nothing to be filed under, since `word_forms.relation` is `NOT NULL` and part of its UNIQUE key.
  */
final case class ExtraCell(
  genders: List[Gender],
  relations: List[String],
  formWords: List[String],
)

object WordCell {

  /** A parenthesised or bracketed group: `(G)`, `[pl]`, `(3rd, sg)`, `(in)`, `(növény)`. What it means depends on what
    * is inside it and on whether it touches the word — see [[lift]].
    */
  private val groupPattern = """[(\[]([^)\]]*)[)\]]""".r

  /** The attached government notation: `+D`, `+ G`, `an+D`. The sign is what marks it as a marker rather than a word.
    *
    * '''`/` is deliberately not a sign here.''' It is written between alternatives — `tető/padlás`,
    * `Schneiderin/näherin` — far more often than before a case letter, and reading it as a sign silently deleted the
    * second alternative.
    */
  private val signedPattern = """\+\s*(\p{L}+|\d+)""".r

  /** What separates alternative translations written into one cell. */
  private val alternativeSeparator = """\s*[/,]\s*"""

  /** What separates markers inside one group or one extra-column cell. `/` stays a separator here: an extra column has
    * no alternatives to confuse it with, which is exactly what makes `r/e` two gender markers rather than a word.
    */
  private val markerSeparator = """[,;/\s]+"""

  /** A fragment that is nothing but an ending — the `-e` of `Sohn, -e`, once the comma has split it off. */
  private val bareSuffix = """^-\s*(\p{L}{1,3})$""".r

  /** An ending written at the end of a fragment rather than after a separator — the `- e` of `Held - e`.
    *
    * The space before the `-` is required. Without it every hyphenated word ending in a short run (`Vor-Ort`) would be
    * read as a stem plus an ending.
    */
  private val trailingSuffix = """^(.*\p{L})\s+-\s*(\p{L}{1,3})$""".r

  /** Marks where a suffix group was lifted from, so it survives the split into alternatives still attached to the word
    * it was written against. A control character, so no real cell can contain one.
    */
  private val suffixMark = '\u0001'

  /** Splits a marker blob into candidate tokens, dropping the `+` a signed marker may still carry. */
  private def markerTokens(blob: String): List[String] = {
    blob
      .split(markerSeparator)
      .iterator
      .map(_.trim.stripPrefix("+"))
      .filter(_.nonEmpty)
      .toList
  }

  private def collapse(text: String): String = text.trim.replaceAll("""\s+""", " ")

  /** A captured regex group as a plain `String`. Explicit nulls types every group as `String | Null`, since a pattern
    * may hold a group that did not participate in the match; none of the patterns here has one.
    */
  private def captured(group: String | Null): String = Option(group).getOrElse("")

  /** What one cell's decorations said, and the text left once they were lifted out. */
  private final case class Lifted(
    text: String,
    genders: List[Gender],
    relations: List[String],
    comments: List[String],
  )

  /** Lifts every decoration out of `raw`, deciding for each parenthesised group which of the three things it is.
    *
    * A group whose every token the vocabulary knows is a '''marker''' — `(pl)`, `(w)`, `(3rd, sg)` — whatever it is
    * written against.
    *
    * Otherwise, a group '''touching''' the word with no space between is a '''suffix''': `Jurist(in)` is how every
    * German list writes "and its feminine counterpart", and `jogász(nő)` is how Hungarian does. The suffix stays
    * attached to its own word through [[suffixMark]], since the text is about to be split into alternatives.
    *
    * A group standing '''apart''' from the word is a '''comment''' — `levél (növény)` says which sense of `levél` this
    * row means. It is a note for the reader, so it is lifted off the word and carried separately rather than dropped.
    */
  private def lift(raw: String, markers: MarkerVocabulary): Lifted = {
    val out       = new StringBuilder
    val genders   = List.newBuilder[Gender]
    val relations = List.newBuilder[String]
    val comments  = List.newBuilder[String]
    var cursor    = 0

    groupPattern.findAllMatchIn(raw).foreach { matched =>
      out.append(raw.substring(cursor, matched.start))
      cursor = matched.end
      // `Match.group` is nullable under explicit nulls: a group that did not participate answers null, which cannot
      // happen for this single-group pattern but still has to be spelled out.
      val body   = Option(matched.group(1)).getOrElse("")
      val tokens = markerTokens(body)

      if (tokens.nonEmpty && tokens.forall(markers.knows)) {
        genders ++= tokens.flatMap(markers.gender)
        relations ++= tokens.flatMap(markers.relation)
        out.append(' ')
      } else if (
        matched.start > 0 && raw.charAt(matched.start - 1).isLetter &&
        tokens.sizeIs == 1 && tokens.head.forall(_.isLetter)
      ) {
        out.append(suffixMark).append(tokens.head)
      } else if (body.trim.nonEmpty) {
        comments += collapse(body)
        out.append(' ')
      } else out.append(' ')
    }
    out.append(raw.substring(cursor))

    val stripped = out.toString
    val signed   = signedPattern.findAllMatchIn(stripped).flatMap(m => Option(m.group(1))).toList.flatMap(markerTokens)

    Lifted(
      text = signedPattern.replaceAllIn(stripped, " "),
      genders = genders.result() ++ signed.flatMap(markers.gender),
      relations = (relations.result() ++ signed.flatMap(markers.relation)).distinct.sorted,
      comments = comments.result(),
    )
  }

  /** One alternative, before the article and the gender have been read off it. */
  private final case class Draft(text: String, suffixes: List[String])

  /** Splits one cell's leftover text into the alternatives it names, each carrying whatever endings were written
    * against it.
    *
    * Three notations reach the same place. `Jurist(in)` arrives already marked by [[lift]]. `Held - e` writes the
    * ending at the end of the fragment. `Sohn, -e` writes it after a separator, so it arrives as a fragment of its own
    * and is folded back onto the one before it — which is also why an ending can never be mistaken for an alternative.
    */
  private def drafts(text: String): List[Draft] = {
    collapse(text)
      .split(alternativeSeparator)
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .foldLeft(List.empty[Draft])((acc, fragment) => {
        // A regex group answers `String | Null` under explicit nulls, so every captured group is unwrapped here even
        // though these patterns have no optional group that could go missing.
        fragment match {
          case bareSuffix(ending) =>
            acc match {
              case Nil          =>
                Nil
              case last :: rest =>
                last.copy(suffixes = last.suffixes :+ captured(ending)) :: rest
            }
          case _                  =>
            val marked           = fragment.split(suffixMark).toList.map(_.trim).filter(_.nonEmpty)
            val (head, attached) = (marked.headOption.getOrElse(""), marked.drop(1))
            val (stem, trailing) = head match {
              case trailingSuffix(base, ending) =>
                (captured(base), List(captured(ending)))
              case other                        =>
                (other, Nil)
            }
            if (stem.isEmpty) acc else Draft(stem, attached ++ trailing) :: acc
        }
      })
      .reverse
  }

  /** Reads one language column's cell.
    *
    * '''Only decorated markers are recognised here.''' A bare trailing token is kept as part of the word, because the
    * word column is the one place a marker abbreviation can also be a real word: German `es` is both a third-person
    * marker and the pronoun starting `es regnet`. Undecorated markers belong in the extra column, which [[parseExtra]]
    * reads with no such ambiguity. The one exception is a '''leading''' single letter that names a gender the language
    * has — `e Suche`, the article abbreviation written into the word column rather than beside it. It is unambiguous
    * because a one-letter word that is also a gender marker is not a word in any of the four languages.
    *
    * Order matters. The article is stripped ''after'' the markers, so `der Hund (m)` loses both and reduces to one
    * word; and the word count that decides [[PartOfSpeech.Phrase]] runs ''after'' the article is gone, so `der Hund`
    * stays a `Noun` while `guten Tag` becomes a phrase. That is the "not just a definite article and a word" rule, with
    * no special case needed for it.
    */
  def parseWord(raw: String, language: WordLanguage, markers: MarkerVocabulary): WordCell = {
    val lifted = lift(raw, markers)
    val words  = drafts(lifted.text).flatMap(draft => expand(draft, lifted.genders.headOption, language, markers))

    WordCell(
      words = words,
      comment = Option(lifted.comments.mkString("; ")).filter(_.nonEmpty),
      relations = lifted.relations,
    )
  }

  /** One alternative into the words it stands for: itself, then one per ending written against it.
    *
    * A suffixed form is a word in its own right, not a `word_forms` row. `Juristin` is a different lemma from `Jurist`,
    * and `Söhne` written as `Sohn, -e` is the reader asserting a second thing to learn, so both are paired with the
    * translation on the same line. The stem is capitalised before the ending is appended, so the ending never decides
    * the casing.
    */
  private def expand(
    draft: Draft,
    marked: Option[Gender],
    language: WordLanguage,
    markers: MarkerVocabulary,
  ): List[ParsedWord] = {
    val profile             = LanguageProfile.of(language)
    val (bare, fromArticle) = profile.strip(draft.text)
    val (text, fromLetter)  = if (fromArticle.isDefined) (bare, None) else stripGenderLetter(bare, language, markers)

    // The article is on the word itself, so it outranks a marker somebody wrote beside it. A gender the language does
    // not have is dropped here rather than at the database, the same rule `WordService.ensure` applies.
    val gender = fromArticle.orElse(fromLetter).orElse(marked).filter(profile.genders.contains)
    val stem   = classified(collapse(text), gender, language)

    if (stem.text.isEmpty) Nil
    else stem :: draft.suffixes.map(ending => classified(stem.text + ending.toLowerCase, gender, language))
  }

  /** Drops a leading `r`/`e`/`s`-style gender letter, answering what it said. Only fires when something is left to be
    * the word and when the language actually has that gender, so Hungarian `e ház` keeps its `e`.
    */
  private def stripGenderLetter(
    text: String,
    language: WordLanguage,
    markers: MarkerVocabulary,
  ): (String, Option[Gender]) = {
    val tokens = collapse(text).split(" ").toList
    val gender = tokens.headOption
      .filter(_.length == 1)
      .flatMap(markers.gender)
      .filter(LanguageProfile.of(language).genders.contains)

    if (gender.isDefined && tokens.sizeIs > 1) (tokens.tail.mkString(" "), gender) else (text, None)
  }

  /** The part of the parse that depends on the gender: how many words are left decides [[PartOfSpeech.Phrase]], and a
    * single word is a noun exactly when a gender is known. Shared with [[WordCell.withExtra]], so an extra column's
    * gender produces the same answer as one written on the word itself.
    */
  private def classified(text: String, gender: Option[Gender], language: WordLanguage): ParsedWord = {
    val words        = if (text.isEmpty) 0 else text.split(" ").length
    val partOfSpeech = {
      if (words >= 2) PartOfSpeech.Phrase
      else if (gender.isDefined) PartOfSpeech.Noun
      else PartOfSpeech.Other
    }

    ParsedWord(LanguageProfile.of(language).capitalize(text, gender), gender, partOfSpeech)
  }

  /** Reads an extra column's cell, where every token is metadata and nothing is a word to import.
    *
    * Bare tokens '''are''' classified here, unlike in [[parseWord]]: there is no word to confuse them with. A token the
    * vocabulary knows is a marker; an alphabetic run it does not know is a form word.
    */
  def parseExtra(raw: String, language: WordLanguage, markers: MarkerVocabulary): ExtraCell = {
    val lifted           = lift(raw, markers)
    val tokens           = markerTokens(lifted.text)
    val (known, unknown) = tokens.partition(markers.knows)

    ExtraCell(
      genders = (lifted.genders ++ known.flatMap(markers.gender))
        .filter(LanguageProfile.of(language).genders.contains)
        .distinct,
      relations = (lifted.relations ++ known.flatMap(markers.relation)).distinct.sorted,
      formWords = unknown.filter(_.exists(_.isLetter)),
    )
  }
}
