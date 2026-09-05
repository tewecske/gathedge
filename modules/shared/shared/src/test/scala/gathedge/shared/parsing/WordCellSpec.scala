package gathedge.shared.parsing

import gathedge.shared.domain.{Gender, PartOfSpeech, WordLanguage}
import zio.test.*

/** What reaches `words.text` is what this decides, so a marker left behind here becomes a permanent duplicate row in
  * the dictionary — `helfen` and `helfen +D` as two unrelated words.
  */
object WordCellSpec extends ZIOSpecDefault {

  private val deMarkers = MarkerVocabulary.forPair(WordLanguage.De, WordLanguage.Hu)
  private val huMarkers = MarkerVocabulary.forPair(WordLanguage.Hu, WordLanguage.De)
  private val enMarkers = MarkerVocabulary.forPair(WordLanguage.En, WordLanguage.De)

  private def german(raw: String)    = WordCell.parseWord(raw, WordLanguage.De, deMarkers)
  private def hungarian(raw: String) = WordCell.parseWord(raw, WordLanguage.Hu, huMarkers)
  private def english(raw: String)   = WordCell.parseWord(raw, WordLanguage.En, enMarkers)

  private def one(cell: WordCell): ParsedWord = cell.words.head

  private def texts(cell: WordCell): List[String] = cell.words.map(_.text)

  def spec = {
    suite("WordCell")(
      suite("parseWord")(
        test("an article yields the gender and leaves the bare noun") {
          val word = one(german("der Hund"))
          assertTrue(
            word.text == "Hund",
            word.gender.contains(Gender.Masculine),
            word.partOfSpeech == PartOfSpeech.Noun,
          )
        },
        test("a signed government marker is stripped off the text and not stored") {
          // The decision on record: the marker must not reach `text_norm`, but there is nowhere to keep it either.
          val cell = german("helfen +D")
          assertTrue(
            texts(cell) == List("helfen"),
            cell.relations == List("dative"),
            one(cell).gender.isEmpty,
            one(cell).partOfSpeech == PartOfSpeech.Other,
          )
        },
        test("a parenthesised government marker is stripped the same way") {
          assertTrue(
            texts(german("gedenken (G)")) == List("gedenken"),
            german("gedenken (G)").relations == List("genitive"),
          )
        },
        test("a parenthesised gender marker sets the gender") {
          val word = one(german("Haus (n)"))
          assertTrue(
            word.text == "Haus",
            word.gender.contains(Gender.Neuter),
            word.partOfSpeech == PartOfSpeech.Noun,
          )
        },
        test("German `w` reads as feminine, not as an unknown token") {
          val word = one(german("Katze (w)"))
          assertTrue(word.text == "Katze", word.gender.contains(Gender.Feminine))
        },
        test("a Hungarian-written gender marker works on a German word") {
          assertTrue(
            one(german("Hund (hn)")).gender.contains(Gender.Masculine),
            one(german("Katze (nn)")).gender.contains(Gender.Feminine),
          )
        },
        test("an article outranks a marker that disagrees with it") {
          // The article is on the word itself; the marker is somebody's note beside it.
          assertTrue(one(german("die Katze (m)")).gender.contains(Gender.Feminine))
        },
        test("two or more words become a phrase") {
          val word = one(german("guten Tag"))
          assertTrue(word.text == "guten Tag", word.partOfSpeech == PartOfSpeech.Phrase)
        },
        test("a whole sentence is one phrase, not its words") {
          val word = one(german("Wie geht es dir?"))
          assertTrue(word.text == "Wie geht es dir?", word.partOfSpeech == PartOfSpeech.Phrase)
        },
        test("an article plus one noun stays a noun, not a phrase") {
          // "not just a definite article and a word" — the article is gone before the words are counted.
          assertTrue(one(german("der Hund")).partOfSpeech == PartOfSpeech.Noun)
        },
        test("markers do not count towards the phrase threshold") {
          assertTrue(one(german("helfen +D")).partOfSpeech == PartOfSpeech.Other)
        },
        test("a phrase keeps its article, since only a leading one is stripped") {
          val word = one(german("die Vereinigten Staaten"))
          assertTrue(word.text == "Vereinigten Staaten", word.partOfSpeech == PartOfSpeech.Phrase)
        },
        test("a gender the language does not have is dropped") {
          // Hungarian has no genders and English none the profile lists, so a stray marker must not invent one.
          assertTrue(
            one(hungarian("kutya (hn)")).gender.isEmpty,
            texts(hungarian("kutya (hn)")) == List("kutya"),
            one(english("dog (m)")).gender.isEmpty,
            one(english("dog (m)")).partOfSpeech == PartOfSpeech.Other,
          )
        },
        test("a German noun is capitalized once its gender is known") {
          assertTrue(texts(german("der hund")) == List("Hund"))
        },
        test("an empty or marker-only cell parses to no words at all") {
          assertTrue(german("").words.isEmpty, german("  ").words.isEmpty, german("(m)").words.isEmpty)
        },
        test("a bare trailing token is kept as part of the word") {
          // German `es` is both a third-person marker and the pronoun that starts `es regnet`. Only decorated markers
          // are lifted out here, so the sentence survives intact.
          val cell = german("es regnet")
          assertTrue(
            texts(cell) == List("es regnet"),
            one(cell).partOfSpeech == PartOfSpeech.Phrase,
            cell.relations.isEmpty,
          )
        },
        test("surrounding whitespace is collapsed") {
          assertTrue(
            texts(german("  der   Hund  ")) == List("Hund"),
            texts(german(" guten   Tag ")) == List("guten Tag"),
          )
        },
      ),
      suite("alternatives")(
        test("a slash separates two translations rather than marking a case") {
          // The regression this exists for: `/` used to read as a government sign, which silently deleted `padlás`.
          assertTrue(
            texts(hungarian("tető/padlás")) == List("tető", "padlás"),
            texts(german("Schneiderin/näherin")) == List("Schneiderin", "näherin"),
          )
        },
        test("a comma separates them too") {
          assertTrue(texts(hungarian("kedvező, megéri")) == List("kedvező", "megéri"))
        },
        test("each alternative is classified on its own") {
          val cell = hungarian("jó illata van, illatozik")
          assertTrue(
            texts(cell) == List("jó illata van", "illatozik"),
            cell.words.map(_.partOfSpeech) == List(PartOfSpeech.Phrase, PartOfSpeech.Other),
          )
        },
      ),
      suite("endings")(
        test("a group touching the word is an ending, and yields a second word") {
          // `Jurist(in)` names two lemmas, not one word called `Jurist(in)`.
          assertTrue(texts(german("Jurist(in)")) == List("Jurist", "Juristin"))
        },
        test("Hungarian writes the same notation") {
          assertTrue(texts(hungarian("jogász(nő)")) == List("jogász", "jogásznő"))
        },
        test("an ending after a separator folds back onto the word before it") {
          assertTrue(texts(german("Sohn, -e")) == List("Sohn", "Sohne"))
        },
        test("an ending written with a spaced hyphen does too") {
          assertTrue(texts(german("Held - e")) == List("Held", "Helde"))
        },
        test("a hyphenated word is not read as a stem plus an ending") {
          // The space before the `-` is what separates the two notations.
          assertTrue(texts(german("Vor-Ort")) == List("Vor-Ort"))
        },
        test("a group the vocabulary knows stays a marker even when it touches the word") {
          assertTrue(
            texts(german("Katze(w)")) == List("Katze"),
            one(german("Katze(w)")).gender.contains(Gender.Feminine),
          )
        },
      ),
      suite("comments")(
        test("a group standing apart from the word is the reader's note") {
          val cell = hungarian("levél (növény)")
          assertTrue(texts(cell) == List("levél"), cell.comment.contains("növény"))
        },
        test("the note is lifted off a phrase too") {
          val cell = hungarian("szenvedni vmiben (betegség)")
          assertTrue(texts(cell) == List("szenvedni vmiben"), cell.comment.contains("betegség"))
        },
        test("a known marker is never a note") {
          val cell = german("Geschwister (pl)")
          assertTrue(cell.comment.isEmpty, cell.relations == List("plural"))
        },
        test("a word with nothing beside it carries no note") {
          assertTrue(german("Hund").comment.isEmpty)
        },
      ),
      suite("gender letters")(
        test("a leading article abbreviation in the word column is read as the gender") {
          // `e Suche` is the article column written into the word column, which is not ambiguous: no one-letter word in
          // any of the four languages is also a gender marker.
          val word = one(german("e Suche"))
          assertTrue(
            word.text == "Suche",
            word.gender.contains(Gender.Feminine),
            word.partOfSpeech == PartOfSpeech.Noun,
          )
        },
        test("it never fires on a language with no genders") {
          // Hungarian `e ház` is "this house"; nothing may be stripped off it.
          assertTrue(texts(hungarian("e ház")) == List("e ház"))
        },
        test("it never eats the only word") {
          assertTrue(texts(german("e")) == List("e"))
        },
      ),
      suite("withExtra")(
        test("a gender from an extra column makes a one-word cell a noun") {
          // Without this the word stays `Other`, and `WordService.ensure` then throws the gender away for not being a
          // noun's — so `Hund` beside `hn` would import genderless.
          val merged = german("Hund").withExtra(Some(ExtraCell(List(Gender.Masculine), Nil, Nil)), WordLanguage.De)
          assertTrue(
            one(merged).gender.contains(Gender.Masculine),
            one(merged).partOfSpeech == PartOfSpeech.Noun,
            one(merged).text == "Hund",
          )
        },
        test("it capitalizes the word the language would capitalize") {
          val merged = german("hund").withExtra(Some(ExtraCell(List(Gender.Masculine), Nil, Nil)), WordLanguage.De)
          assertTrue(texts(merged) == List("Hund"))
        },
        test("a gender on the word itself outranks the extra column's") {
          val merged = german("die Katze").withExtra(Some(ExtraCell(List(Gender.Masculine), Nil, Nil)), WordLanguage.De)
          assertTrue(one(merged).gender.contains(Gender.Feminine))
        },
        test("two genders are handed out in the order they were written") {
          // `r/e` beside `Jurist(in)`: the masculine word first, its feminine counterpart second.
          val merged = german("Jurist(in)")
            .withExtra(Some(ExtraCell(List(Gender.Masculine, Gender.Feminine), Nil, Nil)), WordLanguage.De)
          assertTrue(merged.words.map(_.gender) == List(Some(Gender.Masculine), Some(Gender.Feminine)))
        },
        test("one gender reaches every word the cell named") {
          val merged = german("Schneiderin/näherin")
            .withExtra(Some(ExtraCell(List(Gender.Feminine), Nil, Nil)), WordLanguage.De)
          assertTrue(merged.words.map(_.gender) == List(Some(Gender.Feminine), Some(Gender.Feminine)))
        },
        test("a phrase stays a phrase, gender or not") {
          val merged = german("guten Tag").withExtra(Some(ExtraCell(List(Gender.Masculine), Nil, Nil)), WordLanguage.De)
          assertTrue(one(merged).partOfSpeech == PartOfSpeech.Phrase)
        },
        test("a gender the language does not have changes nothing") {
          val plain = hungarian("kutya")
          assertTrue(plain.withExtra(Some(ExtraCell(List(Gender.Masculine), Nil, Nil)), WordLanguage.Hu) == plain)
        },
        test("no extra column leaves the cell untouched") {
          val plain = german("helfen +D")
          assertTrue(plain.withExtra(None, WordLanguage.De) == plain)
        },
      ),
      suite("parseExtra")(
        test("a bare gender abbreviation is a marker, not a form word") {
          val cell = WordCell.parseExtra("m", WordLanguage.De, deMarkers)
          assertTrue(cell.genders == List(Gender.Masculine), cell.formWords.isEmpty, cell.relations.isEmpty)
        },
        test("German `w` and `s` read as feminine and neuter") {
          assertTrue(
            WordCell.parseExtra("w", WordLanguage.De, deMarkers).genders == List(Gender.Feminine),
            WordCell.parseExtra("s", WordLanguage.De, deMarkers).genders == List(Gender.Neuter),
          )
        },
        test("the article's own last letter is a gender marker") {
          // `r`/`e`/`s` — der/die/das — is how a German gender column is written in course material.
          assertTrue(
            WordCell.parseExtra("r", WordLanguage.De, deMarkers).genders == List(Gender.Masculine),
            WordCell.parseExtra("e", WordLanguage.De, deMarkers).genders == List(Gender.Feminine),
          )
        },
        test("`r/e` names both genders, in the order written") {
          val cell = WordCell.parseExtra("r/e", WordLanguage.De, deMarkers)
          assertTrue(cell.genders == List(Gender.Masculine, Gender.Feminine), cell.formWords.isEmpty)
        },
        test("`sich` is the reflexive marker, not a form word") {
          val cell = WordCell.parseExtra("sich", WordLanguage.De, deMarkers)
          assertTrue(cell.relations == List("reflexive"), cell.formWords.isEmpty, cell.genders.isEmpty)
        },
        test("`es` is the third-person marker") {
          assertTrue(WordCell.parseExtra("es", WordLanguage.De, deMarkers).relations == List("third-person"))
        },
        test("a Hungarian abbreviation describes the German word beside it") {
          assertTrue(WordCell.parseExtra("hn", WordLanguage.De, deMarkers).genders == List(Gender.Masculine))
        },
        test("an unknown word is a form word, a known token beside it is its relation") {
          val cell = WordCell.parseExtra("hilft (3)", WordLanguage.De, deMarkers)
          assertTrue(cell.formWords == List("hilft"), cell.relations == List("third-person"))
        },
        test("several markers in one cell are all collected, sorted and deduplicated") {
          val cell = WordCell.parseExtra("pl, dat, pl", WordLanguage.De, deMarkers)
          assertTrue(cell.relations == List("dative", "plural"))
        },
        test("a form word with no relation is still reported, for the caller to reject") {
          // `word_forms.relation` is NOT NULL and part of its UNIQUE key, so the service drops this; the parser does
          // not, because deciding is not its job.
          val cell = WordCell.parseExtra("Häuser", WordLanguage.De, deMarkers)
          assertTrue(cell.formWords == List("Häuser"), cell.relations.isEmpty)
        },
        test("an empty cell yields nothing at all") {
          val cell = WordCell.parseExtra("", WordLanguage.De, deMarkers)
          assertTrue(cell.genders.isEmpty, cell.relations.isEmpty, cell.formWords.isEmpty)
        },
        test("a gender the language does not have is dropped here too") {
          assertTrue(WordCell.parseExtra("m", WordLanguage.Hu, huMarkers).genders.isEmpty)
        },
      ),
    )
  }
}
