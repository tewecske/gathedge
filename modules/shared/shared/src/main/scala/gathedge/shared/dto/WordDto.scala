package gathedge.shared.dto

import gathedge.shared.domain.{Gender, PartOfSpeech, Tag, Word, WordLanguage}
import gathedge.shared.i18n.MessageRef
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
  * a click files into is page-local state in `localStorage` (see `WordCollect.storedCollectTag`), so nobody can ask
  * "which is selected for tag X". The row carries every one of the reader's marks on that word and the browser filters
  * by the tag it is collecting into — the same shape [[WordSummary.tagIds]] has, for the same reason.
  */
final case class TaggedPair(tagId: Long, translationWordId: Long) derives JsonCodec

/** One `word_forms` relation resolved to the word on the other end, with nothing else attached — a passive link plus a
  * type label. Used where the direction is shown but not interacted with: [[WordSummary.mainWord]] and
  * [[WordDetail.mainWords]]. `relation` is the raw canonical tag string (e.g. `"dative,definite,plural"`); rendering it
  * into words is `Labels.grammarRelation` on the client, so it follows the reader's own locale.
  */
final case class WordFormRef(word: Word, relation: String) derives JsonCodec

/** One entry of a lemma's [[WordSummary.variants]] column: the form, its relation, and whether it is the row a search
  * landed on directly — the ★ marker the listing shows when this exact word id is also present as its own row on the
  * same page. Still a passive link: the interactive tick/chip controls live on that other row, not here.
  */
final case class WordFormPreview(word: Word, relation: String, matched: Boolean) derives JsonCodec

/** One entry of [[WordDetail.forms]]: the form, its relation, and the reader's own tags on *that* word — what lets the
  * Forms section give each entry its own live tick, the same control the page's own title carries for itself. `tagIds`
  * is empty for a caller with no session, the same rule [[WordSummary.tagIds]] follows.
  */
final case class WordFormEntry(word: Word, relation: String, tagIds: List[Long]) derives JsonCodec

/** One row of the browse-and-tag listing.
  *
  * Carries its translations already rendered into the target language the caller asked for, the ids of the reader's own
  * tags on it, and which of those translations they have marked as practice answers — the three things the screen shows
  * beside a word, all of which would otherwise be a query per row. A caller with no session gets an empty `tagIds` and
  * an empty `pairs`, which is what lets the listing be public.
  *
  * `mainWord` is populated only when this row is itself an inflected/declined form of another word; `variants` and
  * `variantsTotal` only when this row is a lemma with forms of its own, `variants` capped to
  * `WordService.wordFormsPerRow` and `variantsTotal` carrying the real count for a "+N more" indicator. `isContext`
  * marks a lemma row that was not itself part of the search match, but was added alongside a variant that did match, so
  * the reader sees the word in relation to its lemma — it is excluded from [[WordPage.total]] and pagination, since it
  * is context, not a match.
  */
final case class WordSummary(
  word: Word,
  translations: List[TranslationOption],
  tagIds: List[Long],
  pairs: List[TaggedPair],
  mainWord: Option[WordFormRef],
  variants: List[WordFormPreview],
  variantsTotal: Int,
  isContext: Boolean,
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

/** Everything the word screen shows about one word: the word itself, every translation anybody has recorded for it, the
  * reader's own tags on it, and which of those translations they have marked as practice answers.
  *
  * `pairs` is carried for the reason [[WordSummary.pairs]] is, and filtered by the browser the same way: the tag a
  * click files into is page-local state that never reaches the server. Unlike the listing's, it is not narrowed to the
  * translations being shown — this screen shows every one of them, in both other languages.
  *
  * `mainWords` names every lemma this word is a form of — ordinarily zero or one. `forms` lists every form of this
  * word, uncapped (unlike [[WordSummary.variants]]'s listing-row cap): the detail screen is where the whole set lives.
  */
final case class WordDetail(
  word: Word,
  translations: List[TranslationEntry],
  tags: List[Tag],
  pairs: List[TaggedPair],
  mainWords: List[WordFormRef],
  forms: List[WordFormEntry],
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

/** [[gathedge.shared.api.WordEndpoints.createTag]]/`.copyTag`'s answer: the tag itself, plus a non-fatal warning when
  * the write pushed the caller's own usage past one of `AppConfig.quotas`' *soft* thresholds — how many tags they own,
  * for `createTag`, or that count together with how many `word_tag_pairs` rows [[copyTag]]'s snapshot added.
  *
  * `None` is the ordinary case; crossing a *hard* threshold instead answers 409 and never reaches here — see
  * `WordFailure.TagQuotaExceeded`/`PairQuotaExceeded`.
  */
final case class TagResponse(tag: Tag, warning: Option[MessageRef]) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.selectPair]]'s answer — no useful payload of its own (unlike [[TagResponse]],
  * marking a pair does not mint anything the caller needs the id of), but the same non-fatal warning when it pushed the
  * caller's total pair count past the soft threshold. The reason this endpoint answers a body at all rather than the
  * 204 every other idempotent toggle here does: a 204 must never carry one (RFC 9110 §8.6), and a warning is exactly
  * that.
  */
final case class PairSelectionResponse(warning: Option[MessageRef]) derives JsonCodec

/** What a bulk upload preview asks: the file's raw text, and which two languages to scan it for.
  *
  * Free-form text rather than a structured word-pair list — [[gathedge.backend.service.WordService.bulkUploadPreview]]
  * tokenizes it and matches whatever it finds in each language, writing nothing.
  */
final case class BulkUploadPreviewRequest(
  content: String,
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
) derives JsonCodec

/** One word [[gathedge.backend.service.WordService.bulkUploadPreview]] found already in the dictionary, plus whichever
  * of its translations the dictionary already has into the *other* of the two declared languages — shown so the reader
  * can tell a genuine match from a coincidental substring before accepting it.
  */
final case class BulkUploadMatch(word: Word, translations: List[TranslationOption]) derives JsonCodec

/** A dictionary word within [[gathedge.backend.service.WordService.maxSuggestionDistance]] edits of `token`, offered
  * because [[gathedge.backend.service.WordService.bulkUploadPreview]] found it in neither declared language exactly —
  * the common case for an OCR misread. `candidate` carries the same shape as an exact [[BulkUploadMatch]] (the word,
  * plus its own dictionary translations into the *other* declared language), so accepting a suggestion is the same
  * `acceptedWordIds`/`selectedTranslations` path [[BulkUploadConfirmRequest]] already has for a real match. `token` is
  * the original, likely-misread text, kept for context; `distance` is the raw edit count, closest first.
  */
final case class BulkUploadSuggestion(token: String, candidate: BulkUploadMatch, distance: Int) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.bulkUploadPreview]]'s answer: every exact match, every plausible correction for
  * a token that matched neither language exactly, and every token that matched neither exactly nor near enough — the
  * three lists the reader reviews before anything is written.
  */
final case class BulkUploadPreviewResponse(
  matched: List[BulkUploadMatch],
  suggestions: List[BulkUploadSuggestion],
  unmatched: List[String],
) derives JsonCodec

/** One pair the reader linked by hand: an unmatched token they assigned to `sourceLanguage`, and one they assigned to
  * `targetLanguage` — the two request-level languages [[gathedge.backend.service.WordService.bulkUploadConfirm]] was
  * also given, so which language each side is in is never repeated per pair.
  */
final case class BulkUploadManualPair(sourceText: String, targetText: String) derives JsonCodec

/** An unmatched token the reader assigned a language but never paired with a translation — imported on its own, with no
  * translation link, rather than dropped for want of a pair.
  */
final case class BulkUploadManualWord(text: String, language: WordLanguage) derives JsonCodec

/** Which one of an accepted matched word's [[BulkUploadMatch.translations]] the reader actually picked — a matched word
  * can carry several dictionary translations, but only the one the reader chose gets marked, not all of them.
  * `translationId` is a [[TranslationOption.wordId]].
  */
final case class BulkUploadSelectedTranslation(wordId: Long, translationId: Long) derives JsonCodec

/** What a bulk upload confirms: which of the previewed matches to keep, which unmatched tokens the reader linked by
  * hand, and which unmatched tokens they assigned a language but left unpaired. `acceptedWordIds` names words only —
  * [[gathedge.backend.service.WordService.bulkUploadConfirm]] re-derives which translations belong to each one rather
  * than trusting a client-supplied list of translation ids; `selectedTranslations` narrows that re-derived set down to
  * the single one the reader picked for each accepted word, when it had more than one.
  */
final case class BulkUploadConfirmRequest(
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  acceptedWordIds: List[Long],
  selectedTranslations: List[BulkUploadSelectedTranslation],
  manualPairs: List[BulkUploadManualPair],
  standaloneWords: List[BulkUploadManualWord],
) derives JsonCodec

/** How many distinct words the confirm step tagged or created. The tag's own name is not echoed back — the caller
  * already knows it, from whichever tag it was collecting into.
  */
final case class BulkUploadConfirmResponse(addedCount: Int) derives JsonCodec

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
