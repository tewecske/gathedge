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

  def spec = {
    suite("WordCell")(
      suite("parseWord")(
        test("an article yields the gender and leaves the bare noun") {
          val cell = german("der Hund")
          assertTrue(
            cell.text == "Hund",
            cell.gender.contains(Gender.Masculine),
            cell.partOfSpeech == PartOfSpeech.Noun,
          )
        },
        test("a signed government marker is stripped off the text and not stored") {
          // The decision on record: the marker must not reach `text_norm`, but there is nowhere to keep it either.
          val cell = german("helfen +D")
          assertTrue(
            cell.text == "helfen",
            cell.relations == List("dative"),
            cell.gender.isEmpty,
            cell.partOfSpeech == PartOfSpeech.Other,
          )
        },
        test("a parenthesised government marker is stripped the same way") {
          assertTrue(german("gedenken (G)").text == "gedenken", german("gedenken (G)").relations == List("genitive"))
        },
        test("a parenthesised gender marker sets the gender") {
          val cell = german("Haus (n)")
          assertTrue(
            cell.text == "Haus",
            cell.gender.contains(Gender.Neuter),
            cell.partOfSpeech == PartOfSpeech.Noun,
          )
        },
        test("German `w` reads as feminine, not as an unknown token") {
          val cell = german("Katze (w)")
          assertTrue(cell.text == "Katze", cell.gender.contains(Gender.Feminine))
        },
        test("a Hungarian-written gender marker works on a German word") {
          assertTrue(
            german("Hund (hn)").gender.contains(Gender.Masculine),
            german("Katze (nn)").gender.contains(Gender.Feminine),
          )
        },
        test("an article outranks a marker that disagrees with it") {
          // The article is on the word itself; the marker is somebody's note beside it.
          assertTrue(german("die Katze (m)").gender.contains(Gender.Feminine))
        },
        test("two or more words become a phrase") {
          val cell = german("guten Tag")
          assertTrue(
            cell.text == "guten Tag",
            cell.partOfSpeech == PartOfSpeech.Phrase,
          )
        },
        test("a whole sentence is one phrase, not its words") {
          val cell = german("Wie geht es dir?")
          assertTrue(cell.text == "Wie geht es dir?", cell.partOfSpeech == PartOfSpeech.Phrase)
        },
        test("an article plus one noun stays a noun, not a phrase") {
          // "not just a definite article and a word" — the article is gone before the words are counted.
          assertTrue(german("der Hund").partOfSpeech == PartOfSpeech.Noun)
        },
        test("markers do not count towards the phrase threshold") {
          assertTrue(german("helfen +D").partOfSpeech == PartOfSpeech.Other)
        },
        test("a phrase keeps its article, since only a leading one is stripped") {
          val cell = german("die Vereinigten Staaten")
          assertTrue(cell.text == "Vereinigten Staaten", cell.partOfSpeech == PartOfSpeech.Phrase)
        },
        test("a gender the language does not have is dropped") {
          // Hungarian has no genders and English none the profile lists, so a stray marker must not invent one.
          assertTrue(
            hungarian("kutya (hn)").gender.isEmpty,
            hungarian("kutya (hn)").text == "kutya",
            english("dog (m)").gender.isEmpty,
            english("dog (m)").partOfSpeech == PartOfSpeech.Other,
          )
        },
        test("a German noun is capitalized once its gender is known") {
          assertTrue(german("der hund").text == "Hund")
        },
        test("an unknown marker is dropped rather than kept as text") {
          val cell = german("Hund (ugs.)")
          assertTrue(cell.text == "Hund", cell.relations.isEmpty)
        },
        test("an empty or marker-only cell parses to no text") {
          assertTrue(german("").text.isEmpty, german("  ").text.isEmpty, german("(m)").text.isEmpty)
        },
        test("a bare trailing token is kept as part of the word") {
          // German `es` is both a third-person marker and the pronoun that starts `es regnet`. Only decorated markers
          // are lifted out here, so the sentence survives intact.
          val cell = german("es regnet")
          assertTrue(cell.text == "es regnet", cell.partOfSpeech == PartOfSpeech.Phrase, cell.relations.isEmpty)
        },
        test("surrounding whitespace is collapsed") {
          assertTrue(german("  der   Hund  ").text == "Hund", german(" guten   Tag ").text == "guten Tag")
        },
      ),
      suite("withGender")(
        test("a gender from an extra column makes a one-word cell a noun") {
          // Without this the word stays `Other`, and `WordService.ensure` then throws the gender away for not being a
          // noun's — so `Hund` beside `hn` would import genderless.
          val merged = german("Hund").withGender(Some(Gender.Masculine), WordLanguage.De)
          assertTrue(
            merged.gender.contains(Gender.Masculine),
            merged.partOfSpeech == PartOfSpeech.Noun,
            merged.text == "Hund",
          )
        },
        test("it capitalizes the word the language would capitalize") {
          assertTrue(german("hund").withGender(Some(Gender.Masculine), WordLanguage.De).text == "Hund")
        },
        test("a gender on the word itself outranks the extra column's") {
          assertTrue(
            german("die Katze").withGender(Some(Gender.Masculine), WordLanguage.De).gender.contains(Gender.Feminine)
          )
        },
        test("a phrase stays a phrase, gender or not") {
          val merged = german("guten Tag").withGender(Some(Gender.Masculine), WordLanguage.De)
          assertTrue(merged.partOfSpeech == PartOfSpeech.Phrase)
        },
        test("a gender the language does not have changes nothing") {
          val plain = hungarian("kutya")
          assertTrue(plain.withGender(Some(Gender.Masculine), WordLanguage.Hu) == plain)
        },
        test("no extra gender leaves the cell untouched") {
          val plain = german("helfen +D")
          assertTrue(plain.withGender(None, WordLanguage.De) == plain)
        },
      ),
      suite("parseExtra")(
        test("a bare gender abbreviation is a marker, not a form word") {
          val cell = WordCell.parseExtra("m", WordLanguage.De, deMarkers)
          assertTrue(cell.gender.contains(Gender.Masculine), cell.formWords.isEmpty, cell.relations.isEmpty)
        },
        test("German `w` and `s` read as feminine and neuter") {
          assertTrue(
            WordCell.parseExtra("w", WordLanguage.De, deMarkers).gender.contains(Gender.Feminine),
            WordCell.parseExtra("s", WordLanguage.De, deMarkers).gender.contains(Gender.Neuter),
          )
        },
        test("a Hungarian abbreviation describes the German word beside it") {
          assertTrue(WordCell.parseExtra("hn", WordLanguage.De, deMarkers).gender.contains(Gender.Masculine))
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
          assertTrue(cell.gender.isEmpty, cell.relations.isEmpty, cell.formWords.isEmpty)
        },
        test("a gender the language does not have is dropped here too") {
          assertTrue(WordCell.parseExtra("m", WordLanguage.Hu, huMarkers).gender.isEmpty)
        },
      ),
    )
  }
}
