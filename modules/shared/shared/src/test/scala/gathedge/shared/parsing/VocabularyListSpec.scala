package gathedge.shared.parsing

import gathedge.shared.domain.WordLanguage
import zio.test.*

/** One real hand-written vocabulary list, parsed end to end.
  *
  * Every other spec here pins one rule against a fixture chosen to isolate it. This one pins what all of them do
  * together to a file nobody wrote for a test: a Hungarian learner's German list, three columns, the article column
  * first, and every notation the writer happened to reach for — abbreviated articles, `r/e` for a word that has both,
  * `+D` government, `(pl)` and `(Pl.)`, a `(növény)` sense note, endings written three different ways, and several
  * lines whose translation cell holds two translations rather than one.
  *
  * The value is the coverage no isolated fixture gives: a rule that quietly misreads a notation it was not written for
  * shows up here as a changed line, not as a silently duplicated `words` row six months later.
  */
object VocabularyListSpec extends ZIOSpecDefault {

  /** The list as pasted: tab-separated, article column first, and a header row that names the two languages. */
  private val list = {
    """Névelő	Német	Magyar
      |	streng	szigorú
      |	gehacktes Fleisch	vagdalthús
      |e	Bohne	bab
      |r	Mais	kukorica
      |	skaten	korcsolyázni
      |r/e	Jurist(in)	jogász(nő)
      |e	Geschwister (pl)	testvérek
      |r	Strafzettel	büntetőcédula
      |e	Geschmacksache	ízlés kérdése
      |	abwechslungsreich	változatos
      |	sich beschweren	panaszkodni
      |	an der Wand	a falon
      |	sich bewegen	mozogni
      |	vorne	előtt
      |r/e	Rentner	nyugdíjas
      |r/e	Held - e	hős
      |	ähnlich	hasonló
      |e	Büroarbeit	irodai munka
      |es	duftet	jó illata van, illatozik
      |	es stinkt	büdös
      |	günstig	kedvező, megéri
      |s	Regal	polc
      |	es gibt+A	van, létezik
      |	in der Sonne liegen	napozni
      |	Schlange stehen	a sorban álni
      |s	Brautpaar	esküvői pár
      |	morgens	reggelenként
      |	zur Arbeit gehen	munkába menni
      |	selbstverstädlich	magától érthetődő
      |r	Sohn, -e	fiúgyermek
      |e	Tochter	lánygyermek
      |s	Blatt	levél (növény)
      |s	Dach	tető/padlás
      |	aufgrund +G	vmi következtében
      |sich	vorstellen	bemutatkozni
      |	gehören+D	tartozni valahova
      |	mitnehmen	magával vinni
      |	im Internet	interneten
      |	sich vorbereiten für +A	felkészülni
      |	e Suche	kereső
      |e	Schneiderin/näherin	varrónő
      |	Fußgänger	gyalogos
      |e	Straße überqueren	átmenni az úton
      |	Kann ich Ihnnen helfen?	segíthetek?
      |e	Nachrichten	hírek (Pl.)
      |	leiden an+D	szenvedni vmiben (betegség)""".stripMargin
  }

  private val grid = DelimitedText.parse(list, Delimiter.Tab)

  private val germanMarkers    = MarkerVocabulary.forPair(WordLanguage.De, WordLanguage.Hu)
  private val hungarianMarkers = MarkerVocabulary.forPair(WordLanguage.Hu, WordLanguage.De)

  /** One row rendered the way the importer will write it: the German words with their gender and part of speech, the
    * Hungarian words the same way, and any note or relation either side carried. Comparing whole lines rather than
    * field by field is deliberate — a rule that improves one cell and breaks its neighbour shows up as two changed
    * lines, which is the thing this spec exists to catch.
    */
  private def render(row: List[String]): String = {
    val extra  = WordCell.parseExtra(row(0), WordLanguage.De, germanMarkers)
    val german = WordCell.parseWord(row(1), WordLanguage.De, germanMarkers).withExtra(Some(extra), WordLanguage.De)
    val magyar = WordCell.parseWord(row(2), WordLanguage.Hu, hungarianMarkers)

    def side(cell: WordCell): String = {
      val words = cell.words
        .map(word => word.text + word.gender.fold("")(gender => s":$gender") + s"/${word.partOfSpeech}")
        .mkString(" + ")
      words + cell.comment.fold("")(note => s" #$note")
    }

    val relations = (extra.relations ++ german.relations ++ magyar.relations).distinct.sorted
    side(german) + " = " + side(magyar) + (if (relations.isEmpty) "" else s" [${relations.mkString(",")}]")
  }

  /** What every data row of the list above becomes. One line per row, in the reader's own order. */
  private val expected = List(
    "streng/Other = szigorú/Other",
    "gehacktes Fleisch/Phrase = vagdalthús/Other",
    "Bohne:Feminine/Noun = bab/Other",
    "Mais:Masculine/Noun = kukorica/Other",
    "skaten/Other = korcsolyázni/Other",
    "Jurist:Masculine/Noun + Juristin:Feminine/Noun = jogász/Other + jogásznő/Other",
    "Geschwister:Feminine/Noun = testvérek/Other [plural]",
    "Strafzettel:Masculine/Noun = büntetőcédula/Other",
    "Geschmacksache:Feminine/Noun = ízlés kérdése/Phrase",
    "abwechslungsreich/Other = változatos/Other",
    "sich beschweren/Phrase = panaszkodni/Other",
    "an der Wand/Phrase = a falon/Phrase",
    "sich bewegen/Phrase = mozogni/Other",
    "vorne/Other = előtt/Other",
    "Rentner:Masculine/Noun = nyugdíjas/Other",
    "Held:Masculine/Noun + Helde:Feminine/Noun = hős/Other",
    "ähnlich/Other = hasonló/Other",
    "Büroarbeit:Feminine/Noun = irodai munka/Phrase",
    "duftet/Other = jó illata van/Phrase + illatozik/Other [third-person]",
    "es stinkt/Phrase = büdös/Other",
    "günstig/Other = kedvező/Other + megéri/Other",
    "Regal:Neuter/Noun = polc/Other",
    "es gibt/Phrase = van/Other + létezik/Other [accusative]",
    "in der Sonne liegen/Phrase = napozni/Other",
    "Schlange stehen/Phrase = a sorban álni/Phrase",
    "Brautpaar:Neuter/Noun = esküvői pár/Phrase",
    "morgens/Other = reggelenként/Other",
    "zur Arbeit gehen/Phrase = munkába menni/Phrase",
    "selbstverstädlich/Other = magától érthetődő/Phrase",
    "Sohn:Masculine/Noun + Sohne:Masculine/Noun = fiúgyermek/Other",
    "Tochter:Feminine/Noun = lánygyermek/Other",
    "Blatt:Neuter/Noun = levél/Other #növény",
    "Dach:Neuter/Noun = tető/Other + padlás/Other",
    "aufgrund/Other = vmi következtében/Phrase [genitive]",
    "vorstellen/Other = bemutatkozni/Other [reflexive]",
    "gehören/Other = tartozni valahova/Phrase [dative]",
    "mitnehmen/Other = magával vinni/Phrase",
    "im Internet/Phrase = interneten/Other",
    "sich vorbereiten für/Phrase = felkészülni/Other [accusative]",
    "Suche:Feminine/Noun = kereső/Other",
    "Schneiderin:Feminine/Noun + Näherin:Feminine/Noun = varrónő/Other",
    "Fußgänger/Other = gyalogos/Other",
    "Straße überqueren:Feminine/Phrase = átmenni az úton/Phrase",
    "Kann ich Ihnnen helfen?/Phrase = segíthetek?/Other",
    "Nachrichten:Feminine/Noun = hírek/Other [plural]",
    "leiden an/Phrase = szenvedni vmiben/Phrase #betegség [dative]",
  )

  def spec = {
    suite("a real vocabulary list")(
      test("it is recognised as a table") {
        // The bug this pins: the article column is blank on more than half the rows, and counting *filled* cells read
        // that as a mixture of two- and three-column rows, which fell under the agreement bar and sent the whole file
        // down the free-text path with no column mapping step at all.
        assertTrue(DelimitedText.sniff(list).contains(Delimiter.Tab), grid.map(_.size).distinct == List(3))
      },
      test("its first row is a header and its rest are not") {
        assertTrue(
          ColumnHeading.isHeaderRow(grid.head),
          grid.tail.forall(row => !ColumnHeading.isHeaderRow(row)),
          ColumnHeading.language("Német").contains(WordLanguage.De),
          ColumnHeading.language("Magyar").contains(WordLanguage.Hu),
        )
      },
      test("every row parses to the words, genders, notes and relations it states") {
        assertTrue(grid.tail.map(render) == expected)
      },
    )
  }
}
