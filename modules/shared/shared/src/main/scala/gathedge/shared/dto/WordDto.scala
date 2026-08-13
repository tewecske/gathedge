package gathedge.shared.dto

import gathedge.shared.domain.{Gender, PartOfSpeech, Tag, Word, WordLanguage}
import zio.json.*

/** One translation as the listing offers it: the id of the word it points at, and the text already rendered.
  *
  * The id names a **word**, not a translation edge, because that is what a practice answer is about — the edge belongs
  * to whoever typed it, while the answer belongs to the reader's tag. `text` is `Word.display`, so a German noun
  * arrives with its article on it.
  */
final case class TranslationOption(wordId: Long, text: String) derives JsonCodec

/** One translation the reader has marked as a practice answer, and the tag they marked it in.
  *
  * Carried per row rather than resolved server-side, because '''the collect tag never reaches the server''': which tag
  * a click files into is page-local state in `localStorage` (see `WordsPage.storedCollectTag`), so nobody can ask
  * "which is selected for tag X". The row carries every one of the reader's marks on that word and the browser filters
  * by the tag it is collecting into — the same shape [[WordSummary.tagIds]] has, for the same reason.
  */
final case class TaggedPair(tagId: Long, translationWordId: Long) derives JsonCodec

/** One row of the browse-and-tag listing.
  *
  * Carries its translations already rendered into the target language the caller asked for, the ids of the reader's own
  * tags on it, and which of those translations they have marked as practice answers — the three things the screen shows
  * beside a word, all of which would otherwise be a query per row. A caller with no session gets an empty `tagIds` and
  * an empty `pairs`, which is what lets the listing be public.
  */
final case class WordSummary(
  word: Word,
  translations: List[TranslationOption],
  tagIds: List[Long],
  pairs: List[TaggedPair],
) derives JsonCodec

/** One translation edge, as the detail page shows it.
  *
  * `origin` says where it came from: `dictionary` for what Wiktionary asserts directly, `pivot` for a German–Hungarian
  * pair derived through a shared English sense (there is no free direct data for that pair, so it is the only way it
  * exists), and `user` for one somebody typed. `ownedByMe` is what decides whether the delete button is offered — a
  * reader may remove their own edge and nobody else's.
  */
final case class TranslationEntry(
  id: Long,
  word: Word,
  origin: String,
  ownedByMe: Boolean,
) derives JsonCodec

/** Everything the word screen shows about one word: the word itself, every translation anybody has recorded for it, and
  * the reader's own tags on it.
  */
final case class WordDetail(
  word: Word,
  translations: List[TranslationEntry],
  tags: List[Tag],
) derives JsonCodec

/** One page of the vocabulary, counted the way [[UserPage]] is: `total` counts what the filter matches, not what the
  * table holds, because that is what decides how many pages there are.
  */
final case class WordPage(items: List[WordSummary], total: Long) derives JsonCodec

/** Adds a word somebody typed, along with whatever translations and tags they gave it.
  *
  * The endpoint behind it is "ensure and attach" rather than "create or conflict": a word that already exists is
  * returned as it stands, with everyone's translations on it, and the caller's own additions are layered on top. That
  * is the requirement that another user adding the same word is shown what is already known about it.
  */
final case class CreateWordRequest(
  language: WordLanguage,
  text: String,
  partOfSpeech: PartOfSpeech,
  gender: Option[Gender],
  translations: List[NewTranslation],
  tagIds: List[Long],
) derives JsonCodec

/** The other half of a translation the caller is adding: a word in the target language, which is looked up and created
  * if it is not there yet.
  */
final case class NewTranslation(
  language: WordLanguage,
  text: String,
  partOfSpeech: Option[PartOfSpeech],
  gender: Option[Gender],
) derives JsonCodec

final case class AddTranslationRequest(translation: NewTranslation) derives JsonCodec

final case class CreateTagRequest(name: String) derives JsonCodec

/** The columns `GET /api/words` will order by.
  *
  * `rank` is corpus frequency, which is also the listing's own order — commonest first, since that is the useful thing
  * to be shown when a search matches a hundred words. Translations are absent for the reason the audit trail's target
  * is: they are a list rendered into one cell, and no `ORDER BY` produces them.
  */
object WordSort {
  val text: String = "text"
  val pos: String  = "pos"
  val rank: String = "rank"

  val all: List[String] = List(text, pos, rank)
}
