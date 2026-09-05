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
/** `mainWordId`/`variantType` link the new word into `word_forms` as an inflected/declined form of an existing word —
  * `mainWordId` names the lemma, `variantType` its relation to it (`"plural"`, `"past"`, …). Both optional and only
  * meaningful together: a `variantType` with no `mainWordId` names nothing to link, so [[WordService.create]] links
  * only when both are given.
  */
final case class CreateWordRequest(
  language: WordLanguage,
  text: String,
  partOfSpeech: PartOfSpeech,
  gender: Option[Gender],
  translations: List[NewTranslation],
  tagIds: List[Long],
  mainWordId: Option[Long] = None,
  variantType: Option[String] = None,
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

/** Fills in the article a noun was imported without.
  *
  * `gender` is required rather than optional: this endpoint fills a blank, it never clears one. Gender is part of a
  * word's identity (`UNIQUE (language, text_norm, part_of_speech, gender)`), so clearing one would be a second identity
  * change with a second collision to answer for, and nothing asks for it -- an article that turned out to be wrong is a
  * correction, which is refused outright.
  */
final case class SetGenderRequest(gender: Gender) derives JsonCodec

final case class CreateTagRequest(name: String) derives JsonCodec

/** One side of a pair the tag-creation page is building.
  *
  * `Existing` names a word already in the dictionary by id — the ordinary case, where the page offered it from an
  * autocomplete and the reader picked it. `New` carries the bare facts needed to create a word that is not there yet:
  * the page's "type it out and press Enter" path, when no dictionary match exists, opens a part-of-speech (and, for a
  * noun, gender) picker and arrives here.
  */
enum TagPairWord derives JsonCodec {
  case Existing(id: Long)
  case New(language: WordLanguage, text: String, partOfSpeech: PartOfSpeech, gender: Option[Gender])
}

/** One bilingual pair the reader assembled on the tag-creation page: a source word in the page's source language and a
  * target word in its target language. Either side may be new to the dictionary.
  */
final case class TagPairInput(source: TagPairWord, target: TagPairWord) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.createTagWithPairs]]'s body: a tag name and the whole ordered list of pairs the
  * reader built. Sent once, so the tag and every pair it carries are written as one unit of work rather than a create
  * followed by N pair writes that could leave a half-built tag if one failed.
  */
final case class CreateTagWithPairsRequest(name: String, pairs: List[TagPairInput]) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.renameTag]]'s body: the tag's new name, validated and de-duplicated the same way
  * [[CreateTagRequest]]'s is.
  */
final case class RenameTagRequest(name: String) derives JsonCodec

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

/** One row of the unified tag editor ([[gathedge.shared.api.WordEndpoints.tagEntries]]): a source word, the answer
  * translation marked for it inside this tag (absent for an "unmatched" row that has no pair yet), and the provenance
  * flags. `imported` is on the source word's membership, `exact` on the pair; `createdByMe` and `inMyOtherTags` are
  * computed per reader (they are not stored).
  *
  * Three of the editor's filters are mutually-exclusive buckets read off `imported`/`exact`, OR'd together: `exact` =
  * `exact`; `non-exact` = `imported` with a `target` but not `exact`; `unmatched` = `imported` with no `target`. A row
  * with `imported = false` was added by hand and shows only when no bucket is active. Two more filters AND on top:
  * "imported by me" = `createdByMe && imported` (a word this reader minted that a bulk import wrote); "only in this tag"
  * = `!inMyOtherTags`.
  *
  *   - `createdByMe` — the source word's `source` is `user` and its `created_by` is this reader (a word never in the
  *     dictionary, minted by them either by hand or by their own import).
  *   - `inMyOtherTags` — the source word is also a member of at least one other tag this reader owns.
  *
  * `otherTranslations` are the source word's other known translations into the tag's target language — what the target
  * picker's chip row offers when the row is edited. Ordered best-first, the marked answer excluded.
  *
  * `comment`/`targetComment` are the notes the reader wrote beside each side — the `(növény)` of `levél (növény)`. One
  * per side because either cell of an imported line may have carried one and they say different things. They are the
  * reader's own, held on `word_tags`, and never a property of the shared word.
  */
final case class TagEntry(
  source: Word,
  target: Option[Word],
  imported: Boolean,
  exact: Boolean,
  createdByMe: Boolean,
  inMyOtherTags: Boolean,
  otherTranslations: List[TranslationOption],
  comment: Option[String] = None,
  targetComment: Option[String] = None,
) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.addPair]]/`.replacePair`'s answer: the row as it now stands, plus the same
  * soft-quota warning [[PairSelectionResponse]] carries when the write crossed the pair quota's soft threshold.
  */
final case class TagEntryResponse(entry: TagEntry, warning: Option[MessageRef]) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.replacePair]]'s body: which row is being edited (its old source word id, and its
  * old answer word id when it had one), and the pair it should become. `next` reuses [[TagPairInput]] — either side may
  * be an existing word or one to create.
  */
final case class ReplacePairRequest(
  oldSourceWordId: Long,
  oldTargetWordId: Option[Long],
  next: TagPairInput,
) derives JsonCodec

/** One editor row to remove: its source word, and its answer word when the row has one. `targetWordId = None` removes
  * the whole source entry and every pair naming it; with a target only that one pair goes. Same contract as
  * [[gathedge.shared.api.WordEndpoints.deletePair]]'s path/query, one per list entry.
  */
final case class PairRef(sourceWordId: Long, targetWordId: Option[Long]) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.bulkDeletePairs]]'s body: the rows a multiselect chose, removed in one request
  * instead of one call each. Idempotent — a `PairRef` that names nothing is skipped.
  */
final case class BulkDeletePairsRequest(pairs: List[PairRef]) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.bulkDeleteWords]]'s body: source word ids a multiselect chose to delete from the
  * dictionary outright, not just untag. The server keeps only the ones the caller minted (`source = user`,
  * `created_by = caller`) and that are in no tag but this one; anything else in the list is left alone. Idempotent.
  */
final case class BulkDeleteWordsRequest(wordIds: List[Long]) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.bulkImport]]'s body: the pasted/uploaded free text and the two languages to scan
  * it for. Unlike the old preview/confirm round-trip this writes straight away — every token becomes a row in the tag,
  * in text order — and the reader reviews the result on the editor with its filters.
  */
final case class BulkImportRequest(
  content: String,
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.bulkImport]]'s answer: how many distinct words the import tagged or created, how
  * many exact pairs it marked, and how many tokens matched no dictionary word (created as answer-less rows).
  */
final case class BulkImportResponse(added: Int, exactPairs: Int, unmatched: Int) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.languageCheck]]'s body: the free text a reader is about to bulk-import, and the
  * tag's two declared languages. The server samples a fixed number of distinct words from the text and looks each one
  * up in both languages' dictionaries; if too few are recognised, the editor warns that the text may be the wrong
  * language before the import runs.
  */
final case class LanguageCheckRequest(
  content: String,
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.languageCheck]]'s answer: how many distinct words were sampled, how many of them
  * were in neither declared language's dictionary, and whether that count is low enough to import without a warning.
  * The sample size and the tolerated miss count are server config (the `language-check` section).
  */
final case class LanguageCheckResponse(sampled: Int, unrecognized: Int, acceptable: Boolean) derives JsonCodec

/** One row of a delimited paste, already split into the columns the reader mapped.
  *
  * The pairing is '''asserted''', not inferred: the reader put these two cells on one line, so they belong together
  * whether or not the dictionary agrees. That is the whole difference from [[BulkImportRequest]], which can only mark a
  * pair that `word_translations` already links.
  *
  * Each side may carry an extra column holding gender or grammatical markers — one per side, since a marker describes a
  * specific word. Both cells are raw text; `shared.parsing.WordCell` does the parsing, server-side, so the browser's
  * preview and the import can never disagree about what a cell meant.
  */
final case class TabularRow(
  source: String,
  target: String,
  sourceExtra: Option[String],
  targetExtra: Option[String],
) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.tabularImport]]'s body: the mapped rows in the reader's own order, and the two
  * languages their columns were assigned. Row order is preserved all the way into the editor, so it is load-bearing.
  */
final case class TabularImportRequest(
  rows: List[TabularRow],
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.tabularImport]]'s answer: how many rows were written, how many of them became a
  * marked pair, how many dictionary words had to be minted, and how many `word_forms` rows an extra column produced.
  *
  * `rows` can be lower than what was sent — a row whose source cell parses to nothing is skipped rather than failing
  * the import.
  */
final case class TabularImportResponse(rows: Int, pairs: Int, newWords: Int, forms: Int) derives JsonCodec

/** One column offered for language detection: its position in the grid, and the cell values to sample.
  *
  * The browser sends the values rather than the raw text because it has already parsed the grid to show a preview;
  * re-splitting it server-side would be a second parser to keep in step with the first.
  */
final case class ColumnSample(index: Int, values: List[String]) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.columnLanguageCheck]]'s body: every column of a delimited paste, so the mapping
  * step can suggest which language each one holds.
  */
final case class ColumnLanguageCheckRequest(columns: List[ColumnSample]) derives JsonCodec

/** How many of one column's sampled words were found in one language's dictionary.
  *
  * A list of pairs rather than a `Map[WordLanguage, Int]`: a keyed map is the shape most likely to be encoded
  * differently by the two codec stacks `ApiEndpointsSpec` pins against each other.
  */
final case class LanguageHit(language: WordLanguage, matched: Int) derives JsonCodec

/** What the check concluded about one column: how many words it sampled, how each language scored, and the best guess.
  *
  * `best` is empty when nothing matched at all — a column of words the dictionary has never seen, which is ordinary for
  * a hand-written list and must not stop the import.
  */
final case class ColumnLanguageGuess(
  index: Int,
  sampled: Int,
  hits: List[LanguageHit],
  best: Option[WordLanguage],
) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.columnLanguageCheck]]'s answer, one entry per column asked about. */
final case class ColumnLanguageCheckResponse(columns: List[ColumnLanguageGuess]) derives JsonCodec

/** What a bulk upload preview asks: the file's raw text, and which two languages to scan it for.
  *
  * Free-form text rather than a structured word-pair list — [[gathedge.backend.service.WordService.bulkUploadPreview]]
  * tokenizes it and matches whatever it finds in each language, writing nothing.
  *
  * `fuzzyMatching` asks the server to also offer near-miss corrections for tokens that matched nothing exactly — worth
  * it for text a camera read (OCR), pointless noise for a `.txt` file or a paste, which are exact. Default `true` so an
  * older client keeps the old behaviour.
  */
final case class BulkUploadPreviewRequest(
  content: String,
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  fuzzyMatching: Boolean = true,
) derives JsonCodec

/** One word [[gathedge.backend.service.WordService.bulkUploadPreview]] found already in the dictionary, plus whichever
  * of its translations the dictionary already has into the *other* of the two declared languages — shown so the reader
  * can tell a genuine match from a coincidental substring before accepting it.
  *
  * `hasAnyTranslation` is wider than `translations.nonEmpty`: it is true the moment the dictionary has recorded the
  * word in '''any''' language, even one neither declared language names, which is what the reader's "any language"
  * filter narrows to.
  *
  * `translationInImport` marks a source-language match whose dictionary translation into the target language was
  * '''also''' one of the imported tokens. That target word is then dropped from [[BulkUploadPreviewResponse.matched]]
  * rather than shown a second time on its own, and this match carries an "exact" badge instead. Always `false` for a
  * [[BulkUploadSuggestion.candidate]].
  *
  * `exactTranslationWordId` names that imported translation (a [[TranslationOption.wordId]]) when `translationInImport`
  * is true, so the browser marks the pair the upload already confirmed rather than defaulting to the first option.
  * `translations` is also ordered to put it first.
  */
final case class BulkUploadMatch(
  word: Word,
  translations: List[TranslationOption],
  hasAnyTranslation: Boolean,
  translationInImport: Boolean = false,
  exactTranslationWordId: Option[Long] = None,
) derives JsonCodec

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
  *
  * `added` is the odd one out: not a column of `words` at all, but the moment the word was ticked into a tag
  * (`word_tags.created_at`). It answers "what landed in this tag recently", which is what a reader wants after an
  * import, and it means nothing without a `tag` — asked for without one, the listing keeps its own order.
  */
object WordSort {
  val text: String  = "text"
  val pos: String   = "pos"
  val rank: String  = "rank"
  val added: String = "added"

  val all: List[String] = List(text, pos, rank, added)
}
