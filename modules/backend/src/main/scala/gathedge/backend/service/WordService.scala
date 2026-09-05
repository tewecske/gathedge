package gathedge.backend.service

import gathedge.backend.config.{AppConfig, LanguageCheckSection, QuotaSection}
import gathedge.backend.db.{
  GroupRepository,
  TagEntryRow,
  TagRow,
  TextSearch,
  WordFormRow,
  WordRepository,
  WordRow,
  WordTagPairRow,
  WordTagRow,
  WordTranslationRow,
}
import gathedge.backend.security.SecurityLog
import gathedge.shared.domain.{
  Gender,
  GrammarTag,
  GroupRef,
  LanguageProfile,
  PartOfSpeech,
  Tag,
  TranslationFilter,
  Word,
  WordLanguage,
}
import gathedge.shared.parsing.{ExtraCell, MarkerVocabulary, WordCell}
import gathedge.shared.dto.{
  BulkImportResponse,
  ColumnLanguageCheckResponse,
  ColumnLanguageGuess,
  ColumnSample,
  LanguageCheckResponse,
  LanguageHit,
  TabularImportResponse,
  TabularRow,
  BulkUploadManualPair,
  BulkUploadManualWord,
  BulkUploadMatch,
  BulkUploadPreviewResponse,
  BulkUploadSelectedTranslation,
  BulkUploadSuggestion,
  CreateWordRequest,
  NewTranslation,
  PairRef,
  ReplacePairRequest,
  TagEntry,
  TagEntryResponse,
  TagExportEntry,
  TagExportFile,
  TagExportTag,
  TagExportWord,
  TagImportChoice,
  TagImportRequest,
  TagImportResponse,
  TagImportResult,
  TagPairInput,
  TagPairWord,
  PairSelectionResponse,
  TagResponse,
  TaggedPair,
  TranslationEntry,
  TranslationOption,
  WordDetail,
  WordFormEntry,
  WordFormPreview,
  WordFormRef,
  WordPage,
  WordSummary,
}
import gathedge.shared.i18n.{MessageKeys, MessageRef}
import gathedge.shared.validation.Validation
import zio.*

import java.util.concurrent.TimeUnit

enum WordFailure {
  case ValidationError(fieldErrors: Map[String, MessageRef])
  case NotFound
  case TagNotFound

  /** The account already has a tag by that name, compared case-insensitively. */
  case DuplicateTag

  /** The account has already recorded that exact translation. Somebody else's identical one is not a conflict:
    * translations are per-account and additive.
    */
  case DuplicateTranslation

  /** Setting a noun's gender was refused before it was tried: the word already has one, it is not a noun, or the gender
    * is not one its language has. All three are the same answer to the caller, because the screen offers the control on
    * none of them — a request that reached here was not made from it.
    */
  case GenderNotApplicable

  /** Setting a noun's gender would collide with the row that already holds that identity — `das Haus` beside a blank
    * `Haus`. Nothing is merged: the two rows carry different translations, tags and marks, and choosing which survives
    * is not a decision one reader gets to make for everybody.
    */
  case GenderConflict

  /** The account already owns as many tags as `limit` (`AppConfig.quotas.tagsPerUserHard`) allows. Carries the limit
    * itself, since `ApiFailures` mints no signature that takes an `AppConfig` to look it up.
    */
  case TagQuotaExceeded(limit: Int)

  /** The account's tags already carry as many `word_tag_pairs` rows, summed across every tag it owns, as `limit`
    * (`AppConfig.quotas.wordPairsPerUserHard`) allows.
    */
  case PairQuotaExceeded(limit: Int)

  /** A word or a translation was attached to a tag whose language pair does not admit it: only a word in the tag's
    * source language may be tagged, and only a translation in its target language may be marked. The words page locks
    * its language selects to the collect tag, so a reader reaches this only past that.
    */
  case LanguageMismatch

  /** The tag's language pair was asked to change after the tag already held a `word_tag_pairs` row. It is fixed at
    * creation and editable only while the tag has no practice pair.
    */
  case LanguagesLocked
}

/** [[WordService.bulkUploadPreview]]/[[WordService.bulkUploadConfirm]]'s shared failure surface — separate from
  * [[WordFailure]] because it needs a status ([[gathedge.shared.api.ApiFailure.TooManyRequests]]) nothing else in this
  * file raises, and mirrors it rather than widening it for the reason recorded on `ApiFailures`: a shared mapping would
  * force every other endpoint over [[WordFailure]] to describe a 429 it cannot produce.
  */
enum BulkUploadFailure {
  case TagNotFound

  /** The upload's own text, not one of `content`'s words: empty, or over [[WordService.maxBulkUploadBytes]]. */
  case ValidationError(fieldErrors: Map[String, MessageRef])
  case RateLimited
}

/** [[WordService.importTags]]'s failure surface. Mirrors [[BulkUploadFailure]] (it shares that endpoint's 429 budget)
  * and adds [[NameConflict]] for the "you already have a tag by that name, and have not said what to do about it" case
  * the import flow re-submits past. `TagQuotaExceeded`/`PairQuotaExceeded` reuse [[WordFailure]]'s own wording via
  * `ApiFailures`.
  */
enum TagImportFailure {

  /** One or more tags in the file have names the account already owns and `resolutions` did not cover. Carries the
    * clashing names for the message.
    */
  case NameConflict(names: List[String])

  /** The file itself is unusable — wrong version, or a name that fails validation. */
  case ValidationError(fieldErrors: Map[String, MessageRef])
  case TagQuotaExceeded(limit: Int)
  case PairQuotaExceeded(limit: Int)
  case RateLimited
}

/** Browsing the shared dictionary, and the per-account layer on top of it: tags, and translations somebody typed.
  *
  * Three rules the whole feature rests on:
  *
  *   - '''A word belongs to nobody.''' Rows imported from the dictionary and rows a user typed live in one table and
  *     are found by the same search. Adding a word that already exists is not a conflict but the ordinary case — it
  *     answers the existing row, with everyone's translations on it.
  *   - '''Tagging is what "mine" means.''' There is no separate collection: a word is in an account's vocabulary
  *     exactly while one of its tags is on it.
  *   - '''Reading needs no account.''' Every read here takes an `Option[Long]` reader; `None` is a visitor with no
  *     session, who sees the same words and no tags.
  *
  * A tag itself is visible to every account once created — [[listTags]] answers the whole table, marking which rows the
  * caller owns — but writing through one is not, unless the caller has another way in: [[tagWord]], [[untagWord]],
  * [[selectPair]], [[deselectPair]] and bulk upload all go through `requireEditableTag`, open to the tag's owner or any
  * member of the group it belongs to (see `gathedge.backend.db.GroupRepository`). [[renameTag]] and [[deleteTag]] stay
  * narrower — `requireOwnTag`, the owner alone, group or no group — so a reader may filter, [[copyTag]], or (if a
  * member) add to somebody else's tag, but never rename or delete one that isn't theirs.
  */
trait WordService {

  def list(
    page: Int,
    pageSize: Int,
    language: Option[WordLanguage],
    search: Option[String],
    partOfSpeech: Option[PartOfSpeech],
    tagId: Option[Long],
    mine: Boolean,
    target: WordLanguage,
    translationFilter: TranslationFilter,
    mainOnly: Boolean,
    sort: Option[String],
    descending: Boolean,
    reader: Option[Long],
  ): UIO[WordPage]

  def detail(id: Long, reader: Option[Long]): IO[WordFailure, WordDetail]

  /** Ensures the word exists, then attaches the caller's translations and tags to it. */
  def create(request: CreateWordRequest, userId: Long): IO[WordFailure, WordDetail]

  def addTranslation(wordId: Long, translation: NewTranslation, userId: Long): IO[WordFailure, WordDetail]
  def removeTranslation(wordId: Long, translationId: Long, userId: Long): IO[WordFailure, Unit]

  /** Fills in the article a noun was imported without — the only edit any word accepts.
    *
    * Only a blank is filled. A word already carrying a gender answers `GenderNotApplicable`, not a correction: `words`
    * rows belong to nobody, so one reader must not rewrite an article the rest are learning from.
    */
  def setGender(wordId: Long, gender: Gender, userId: Long): IO[WordFailure, WordDetail]

  def listTags(userId: Long): UIO[List[Tag]]

  /** `TagQuotaExceeded` is the hard half of the tag quota; a write that only crosses the soft threshold succeeds with
    * [[gathedge.shared.dto.TagResponse.warning]] set instead. `LanguageMismatch` (raised as a 400) is `source` and
    * `target` being the same language.
    */
  def createTag(
    name: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): IO[WordFailure, TagResponse]

  /** Creates a tag and every bilingual pair the tag-creation page assembled, as one logical write.
    *
    * `DuplicateTag`/`TagQuotaExceeded`/`PairQuotaExceeded` follow [[createTag]]/[[copyTag]]'s own rules; `NotFound` is
    * a `TagPairWord.Existing` naming no word; `LanguageMismatch` is `source == target` or a pair whose sides are not in
    * the tag's `sourceLanguage` / `targetLanguage`. Both quotas are checked before anything is written, and every word
    * on either side is checked — an `Existing` id resolved, a `New` word's text validated — before the first `New` word
    * is created, so a request that fails does so having written nothing.
    */
  def createTagWithPairs(
    name: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    pairs: List[TagPairInput],
    userId: Long,
  ): IO[WordFailure, TagResponse]

  /** `TagNotFound` covers a tag that does not exist or is not the caller's, the same as every other tag-scoped write.
    * `DuplicateTag` is the new name colliding with a *different* tag of the caller's own, compared case-insensitively —
    * renaming a tag to the name it already has is a no-op, not a conflict.
    */
  def renameTag(tagId: Long, name: String, userId: Long): IO[WordFailure, TagResponse]

  def deleteTag(tagId: Long, userId: Long): IO[WordFailure, Unit]

  /** Rewrites a tag's language pair — the editor's language selects. `TagNotFound` is a tag that is not the caller's;
    * `LanguageMismatch` is `source == target`; `LanguagesLocked` (a 409) is a tag that already holds a `word_tag_pairs`
    * row, whose pair is fixed for good.
    */
  def setTagLanguages(
    tagId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): IO[WordFailure, TagResponse]

  /** Seeds a new tag of the caller's own from another account's name, and copies the source tag's word memberships and
    * practice pairs into it as a snapshot — independent of the source from the moment this returns. `TagNotFound`
    * covers a source tag that does not exist; `DuplicateTag` covers the ordinary case of the copier already having a
    * tag by that name; `TagQuotaExceeded`/`PairQuotaExceeded` cover the copy's one new tag and its copied pairs pushing
    * the copier past a *hard* quota threshold, checked before anything is written so a blocked copy leaves nothing
    * behind. A *soft* threshold instead succeeds, with a warning.
    */
  def copyTag(tagId: Long, userId: Long): IO[WordFailure, TagResponse]

  /** Idempotent, both ways round: the listing's one-click toggle must be safe to click twice. */
  def tagWord(wordId: Long, tagId: Long, userId: Long): IO[WordFailure, Unit]
  def untagWord(wordId: Long, tagId: Long, userId: Long): IO[WordFailure, Unit]

  /** Marks one of a word's translations as a practice answer inside one of the caller's tags.
    *
    * Idempotent like [[tagWord]], and it files both words under the tag as a side effect — a pair whose answer is not
    * itself collected is a question with a missing half. `NotFound` covers both a word that is not there and a
    * translation the word does not have; `TagNotFound` covers somebody else's tag; `PairQuotaExceeded` is the hard half
    * of the pair quota. A pair already marked is nothing new to write, so it never counts against the quota, and a
    * write that only crosses the *soft* threshold succeeds with [[gathedge.shared.dto.PairSelectionResponse.warning]]
    * set.
    */
  def selectPair(
    wordId: Long,
    tagId: Long,
    translationWordId: Long,
    userId: Long,
  ): IO[WordFailure, PairSelectionResponse]

  /** Unmarks it. Both words keep the tag: taking a word out of a vocabulary is the tick's job. */
  def deselectPair(wordId: Long, tagId: Long, translationWordId: Long, userId: Long): IO[WordFailure, Unit]

  // -- The unified tag editor --------------------------------------------------------------------

  /** One tag's rows for the editor, in the order they were added (a bulk import keeps the pasted text's order). Any
    * signed-in caller may read them — tag contents are world-visible — so `TagNotFound` is only an id that names
    * nothing, not somebody else's tag.
    */
  def tagEntries(tagId: Long, userId: Long): IO[WordFailure, List[TagEntry]]

  /** Adds one bilingual pair to a tag, written straight away. Either side may be a brand-new word, created on the fly.
    * Charges the pair quota exactly as [[selectPair]] does — the tag owner's, not the caller's, and never for a pair
    * already marked. On the first row it also fixes the tag's language pair, which locks from then on.
    */
  def addPair(tagId: Long, pair: TagPairInput, userId: Long): IO[WordFailure, TagEntryResponse]

  /** Replaces one editor row's pair in place. `request.oldTargetWordId` is `None` for an unmatched row that had no pair
    * yet — filling that in is charged the pair quota; a genuine swap is net-zero and is not. The new pair's `exact`
    * flag is cleared: a hand-edited pair is no longer an exact import match.
    */
  def replacePair(tagId: Long, request: ReplacePairRequest, userId: Long): IO[WordFailure, TagEntryResponse]

  /** Removes one editor row. With `targetWordId`, only that one `(source, target)` practice pair goes, both directions,
    * and a side is dropped only when the tag pairs it with nothing else and did not import it — so a word with several
    * marked translations keeps its other rows. Without it — an answer-less row — the source word and every pair naming
    * it go, and any orphaned answer word too (unless it was imported). Idempotent either way.
    */
  def removeEntry(tagId: Long, sourceWordId: Long, targetWordId: Option[Long], userId: Long): IO[WordFailure, Unit]

  /** Removes a batch of editor rows in one call — each `PairRef` follows [[removeEntry]]'s rule. The tag is checked
    * once; a `PairRef` that names nothing is skipped, so the whole call is idempotent.
    */
  def removeEntries(tagId: Long, pairs: List[PairRef], userId: Long): IO[WordFailure, Unit]

  /** Deletes words from the dictionary outright, not just from the tag. Keeps only the ids `userId` minted (`source =
    * user`, `created_by = userId`) that carry no tag but this one; every other id is left untouched. A word a game
    * still references is skipped too. Idempotent — safe to send a whole selection to.
    */
  def deleteWords(tagId: Long, wordIds: List[Long], userId: Long): IO[WordFailure, Unit]

  /** Tokenizes `content`, matches it against the dictionary in both languages, and writes the result into the tag in
    * text order: an exact pair (a word and its dictionary translation both present) is marked with an "exact" flag;
    * every other token becomes an answer-less row — a dictionary word tagged as-is, or a new `sourceLanguage` word.
    * Every membership it writes is flagged "imported". No preview: the reader reviews on the editor with its filters.
    *
    * Not pair-quota-gated, for the same reason [[bulkUploadConfirm]] is not — a batch a reader confirmed is not the
    * place to refuse half of it. Shares [[bulkUploadPreview]]'s rate-limit budget and token cap.
    */
  def bulkImport(
    tagId: Long,
    content: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): IO[BulkUploadFailure, BulkImportResponse]

  /** Samples up to `AppConfig.languageCheck.sampleSize` distinct words from `content` and looks each one up in
    * `sourceLanguage`'s and `targetLanguage`'s dictionaries, in one query per language. Answers how many were sampled,
    * how many were in neither, and whether that miss count is within `AppConfig.languageCheck.unrecognizedThreshold` —
    * the editor warns before a [[bulkImport]] when it is not. Writes nothing; names no tag, so it needs no
    * `requireEditableTag`. Empty text is `acceptable` with a zero sample.
    */
  def checkLanguage(
    content: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
  ): UIO[LanguageCheckResponse]

  /** Writes a delimited paste into the tag one row at a time, '''taking each row's pairing as given''' rather than
    * inferring it the way [[bulkImport]] must — the reader put the two cells on one line, so they are marked as a
    * practice pair whether or not `word_translations` already links them. A word the dictionary has never seen pairs
    * just as well as one it has, which is the whole point of the tabular path.
    *
    * Each cell goes through `shared.parsing.WordCell`, so a marker never reaches `text_norm`, an article yields the
    * gender, and a cell holding two or more words becomes one `PartOfSpeech.Phrase` entry. An extra column may add the
    * gender, or an inflected word that becomes a `word_forms` row when it also names its relation.
    *
    * Rows are written in order, since `tagMemberships` answers in insertion order and that is what the editor shows. A
    * row whose source cell parses to nothing is skipped rather than failing the batch. Not pair-quota-gated, for the
    * same reason [[bulkImport]] is not; bounded by [[WordService.maxTabularRows]] and the shared rate-limit budget.
    */
  def tabularImport(
    tagId: Long,
    rows: List[TabularRow],
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): IO[BulkUploadFailure, TabularImportResponse]

  /** Samples each column of a delimited paste and reports how many of its words each study language's dictionary knows,
    * so the mapping step can suggest which column holds which language.
    *
    * Scored against '''every''' `WordLanguage`, not only a tag's two: the point is to be able to say "this column looks
    * German" about a column nobody has assigned yet. Writes nothing, names no tag.
    */
  def checkColumnLanguages(columns: List[ColumnSample]): UIO[ColumnLanguageCheckResponse]

  /** Scans `content` for words already in the dictionary, in each of `sourceLanguage` and `targetLanguage` — matching
    * `sourceLanguage` first, then whatever is left against `targetLanguage` — and answers what it found: every match,
    * with whichever of its translations into the *other* declared language the dictionary already has, plus every token
    * that matched neither. '''Writes nothing''': this is the reader's chance to see what an upload would do before any
    * of it happens, since matching a whole file's worth of substrings unsupervised turned dictionary pollution into the
    * ordinary case.
    *
    * Unlike every other read in this file, a single call here can scan thousands of tokens, so it carries its own
    * rate-limit budget ([[gathedge.backend.service.RateLimitKey.wordUpload]]) and its own size/token caps
    * ([[WordService.maxBulkUploadBytes]], [[WordService.maxBulkUploadTokens]]), shared with [[bulkUploadConfirm]].
    */
  def bulkUploadPreview(
    tagId: Long,
    content: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    fuzzyMatching: Boolean,
    userId: Long,
  ): IO[BulkUploadFailure, BulkUploadPreviewResponse]

  /** The write [[bulkUploadPreview]] only previews: tags every accepted matched word (and marks its known translations
    * into the other language as practice pairs), and for every manually paired token — one the reader assigned a
    * language and linked to one on the other side themselves — creates both words if the dictionary does not have them
    * yet (the same "ensure" [[create]] does for a single word), links them as a translation, and tags and marks both.
    * Answers how many distinct words were touched.
    *
    * Neither tag membership nor dictionary creation is otherwise quota-gated (see the note on [[create]]), so this
    * shares [[bulkUploadPreview]]'s rate-limit budget and token cap as the only thing bounding one call's size.
    */
  def bulkUploadConfirm(
    tagId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    acceptedWordIds: List[Long],
    selectedTranslations: List[BulkUploadSelectedTranslation],
    manualPairs: List[BulkUploadManualPair],
    standaloneWords: List[BulkUploadManualWord],
    userId: Long,
  ): IO[BulkUploadFailure, Int]

  /** The whole of one tag as a portable [[gathedge.shared.dto.TagExportFile]] — its name, the words it holds, and the
    * practice pairs marked in it — so it can be rebuilt elsewhere with [[importTags]]. Any tag is exportable, whoever
    * owns it; `TagNotFound` is an id that names nothing.
    */
  def exportTag(tagId: Long): IO[WordFailure, TagExportFile]

  /** Every tag `userId` owns, in one file. */
  def exportOwnedTags(userId: Long): UIO[TagExportFile]

  /** Rebuilds the tags in `file` under `userId`'s account: each word is matched by identity and created in this
    * dictionary when missing, memberships and practice pairs are written, and both quotas are checked before any write
    * the way [[copyTag]] checks them. `NameConflict` is a tag whose name the caller already owns with no matching entry
    * in `resolutions` — the caller re-submits with a per-name [[gathedge.shared.dto.TagImportChoice]].
    * `ValidationError` is a file that is not version [[gathedge.shared.dto.TagExportFile.currentVersion]], or a rename
    * to an invalid name. Shares [[bulkUploadPreview]]'s rate-limit budget, since one call can create many rows.
    */
  def importTags(
    file: TagExportFile,
    resolutions: Map[String, TagImportChoice],
    userId: Long,
  ): IO[TagImportFailure, TagImportResponse]
}

object WordService {

  /** How many translations a listing row carries. The screen shows a line, not a dictionary entry; the detail page is
    * where the rest of them are.
    */
  val translationsPerRow = 3

  /** How many of a lemma's forms a listing row's Variants column carries, for the reason [[translationsPerRow]] caps
    * translations: the cell is a line, not a declension table — the detail page's Forms section is where the rest are.
    */
  val wordFormsPerRow = 3

  /** Where a word goes when the reader tagged one without choosing a tag. Not a translated string: it is a row in
    * `tags` like any other, which the reader can rename or delete.
    */
  val defaultTagName = "saved"

  def list(
    page: Int,
    pageSize: Int,
    language: Option[WordLanguage],
    search: Option[String],
    partOfSpeech: Option[PartOfSpeech],
    tagId: Option[Long],
    mine: Boolean,
    target: WordLanguage,
    translationFilter: TranslationFilter,
    mainOnly: Boolean,
    sort: Option[String],
    descending: Boolean,
    reader: Option[Long],
  ): URIO[WordService, WordPage] = {
    ZIO.serviceWithZIO[WordService](
      _.list(
        page,
        pageSize,
        language,
        search,
        partOfSpeech,
        tagId,
        mine,
        target,
        translationFilter,
        mainOnly,
        sort,
        descending,
        reader,
      )
    )
  }

  def detail(id: Long, reader: Option[Long]): ZIO[WordService, WordFailure, WordDetail] =
    ZIO.serviceWithZIO[WordService](_.detail(id, reader))

  def create(request: CreateWordRequest, userId: Long): ZIO[WordService, WordFailure, WordDetail] =
    ZIO.serviceWithZIO[WordService](_.create(request, userId))

  def addTranslation(
    wordId: Long,
    translation: NewTranslation,
    userId: Long,
  ): ZIO[WordService, WordFailure, WordDetail] =
    ZIO.serviceWithZIO[WordService](_.addTranslation(wordId, translation, userId))

  def removeTranslation(wordId: Long, translationId: Long, userId: Long): ZIO[WordService, WordFailure, Unit] =
    ZIO.serviceWithZIO[WordService](_.removeTranslation(wordId, translationId, userId))

  def setGender(wordId: Long, gender: Gender, userId: Long): ZIO[WordService, WordFailure, WordDetail] =
    ZIO.serviceWithZIO[WordService](_.setGender(wordId, gender, userId))

  def listTags(userId: Long): URIO[WordService, List[Tag]] =
    ZIO.serviceWithZIO[WordService](_.listTags(userId))

  def createTag(
    name: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): ZIO[WordService, WordFailure, TagResponse] =
    ZIO.serviceWithZIO[WordService](_.createTag(name, sourceLanguage, targetLanguage, userId))

  def createTagWithPairs(
    name: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    pairs: List[TagPairInput],
    userId: Long,
  ): ZIO[WordService, WordFailure, TagResponse] =
    ZIO.serviceWithZIO[WordService](_.createTagWithPairs(name, sourceLanguage, targetLanguage, pairs, userId))

  def renameTag(tagId: Long, name: String, userId: Long): ZIO[WordService, WordFailure, TagResponse] =
    ZIO.serviceWithZIO[WordService](_.renameTag(tagId, name, userId))

  def deleteTag(tagId: Long, userId: Long): ZIO[WordService, WordFailure, Unit] =
    ZIO.serviceWithZIO[WordService](_.deleteTag(tagId, userId))

  def setTagLanguages(
    tagId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): ZIO[WordService, WordFailure, TagResponse] =
    ZIO.serviceWithZIO[WordService](_.setTagLanguages(tagId, sourceLanguage, targetLanguage, userId))

  def copyTag(tagId: Long, userId: Long): ZIO[WordService, WordFailure, TagResponse] =
    ZIO.serviceWithZIO[WordService](_.copyTag(tagId, userId))

  def tagWord(wordId: Long, tagId: Long, userId: Long): ZIO[WordService, WordFailure, Unit] =
    ZIO.serviceWithZIO[WordService](_.tagWord(wordId, tagId, userId))

  def untagWord(wordId: Long, tagId: Long, userId: Long): ZIO[WordService, WordFailure, Unit] =
    ZIO.serviceWithZIO[WordService](_.untagWord(wordId, tagId, userId))

  def selectPair(
    wordId: Long,
    tagId: Long,
    translationWordId: Long,
    userId: Long,
  ): ZIO[WordService, WordFailure, PairSelectionResponse] =
    ZIO.serviceWithZIO[WordService](_.selectPair(wordId, tagId, translationWordId, userId))

  def deselectPair(
    wordId: Long,
    tagId: Long,
    translationWordId: Long,
    userId: Long,
  ): ZIO[WordService, WordFailure, Unit] =
    ZIO.serviceWithZIO[WordService](_.deselectPair(wordId, tagId, translationWordId, userId))

  def tagEntries(tagId: Long, userId: Long): ZIO[WordService, WordFailure, List[TagEntry]] =
    ZIO.serviceWithZIO[WordService](_.tagEntries(tagId, userId))

  def addPair(tagId: Long, pair: TagPairInput, userId: Long): ZIO[WordService, WordFailure, TagEntryResponse] =
    ZIO.serviceWithZIO[WordService](_.addPair(tagId, pair, userId))

  def replacePair(
    tagId: Long,
    request: ReplacePairRequest,
    userId: Long,
  ): ZIO[WordService, WordFailure, TagEntryResponse] =
    ZIO.serviceWithZIO[WordService](_.replacePair(tagId, request, userId))

  def removeEntry(
    tagId: Long,
    sourceWordId: Long,
    targetWordId: Option[Long],
    userId: Long,
  ): ZIO[WordService, WordFailure, Unit] =
    ZIO.serviceWithZIO[WordService](_.removeEntry(tagId, sourceWordId, targetWordId, userId))

  def removeEntries(
    tagId: Long,
    pairs: List[PairRef],
    userId: Long,
  ): ZIO[WordService, WordFailure, Unit] =
    ZIO.serviceWithZIO[WordService](_.removeEntries(tagId, pairs, userId))

  def deleteWords(
    tagId: Long,
    wordIds: List[Long],
    userId: Long,
  ): ZIO[WordService, WordFailure, Unit] =
    ZIO.serviceWithZIO[WordService](_.deleteWords(tagId, wordIds, userId))

  def bulkImport(
    tagId: Long,
    content: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): ZIO[WordService, BulkUploadFailure, BulkImportResponse] =
    ZIO.serviceWithZIO[WordService](_.bulkImport(tagId, content, sourceLanguage, targetLanguage, userId))

  def checkLanguage(
    content: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
  ): URIO[WordService, LanguageCheckResponse] =
    ZIO.serviceWithZIO[WordService](_.checkLanguage(content, sourceLanguage, targetLanguage))

  def tabularImport(
    tagId: Long,
    rows: List[TabularRow],
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): ZIO[WordService, BulkUploadFailure, TabularImportResponse] =
    ZIO.serviceWithZIO[WordService](_.tabularImport(tagId, rows, sourceLanguage, targetLanguage, userId))

  def checkColumnLanguages(columns: List[ColumnSample]): URIO[WordService, ColumnLanguageCheckResponse] =
    ZIO.serviceWithZIO[WordService](_.checkColumnLanguages(columns))

  def bulkUploadPreview(
    tagId: Long,
    content: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
    fuzzyMatching: Boolean = true,
  ): ZIO[WordService, BulkUploadFailure, BulkUploadPreviewResponse] = {
    ZIO.serviceWithZIO[WordService](
      _.bulkUploadPreview(tagId, content, sourceLanguage, targetLanguage, fuzzyMatching, userId)
    )
  }

  def bulkUploadConfirm(
    tagId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    acceptedWordIds: List[Long],
    selectedTranslations: List[BulkUploadSelectedTranslation],
    manualPairs: List[BulkUploadManualPair],
    standaloneWords: List[BulkUploadManualWord],
    userId: Long,
  ): ZIO[WordService, BulkUploadFailure, Int] = {
    ZIO.serviceWithZIO[WordService](
      _.bulkUploadConfirm(
        tagId,
        sourceLanguage,
        targetLanguage,
        acceptedWordIds,
        selectedTranslations,
        manualPairs,
        standaloneWords,
        userId,
      )
    )
  }

  /** How one tag in an import file will be applied: created new (possibly under a renamed name), or merged into a tag
    * the caller already owns.
    */
  enum ImportPlan {
    case Create(newName: String, tag: TagExportTag)
    case Merge(existing: TagRow, tag: TagExportTag)

    def fileTag: TagExportTag = this match {
      case Create(_, t) => t
      case Merge(_, t)  => t
    }

    def created: Boolean = this match {
      case Create(_, _) => true
      case Merge(_, _)  => false
    }
  }

  def exportTag(tagId: Long): ZIO[WordService, WordFailure, TagExportFile] =
    ZIO.serviceWithZIO[WordService](_.exportTag(tagId))

  def exportOwnedTags(userId: Long): URIO[WordService, TagExportFile] =
    ZIO.serviceWithZIO[WordService](_.exportOwnedTags(userId))

  def importTags(
    file: TagExportFile,
    resolutions: Map[String, TagImportChoice],
    userId: Long,
  ): ZIO[WordService, TagImportFailure, TagImportResponse] =
    ZIO.serviceWithZIO[WordService](_.importTags(file, resolutions, userId))

  val live: URLayer[WordRepository & GroupRepository & AppConfig & RateLimiter, WordService] = {
    ZLayer.fromFunction((repo: WordRepository, groupRepo: GroupRepository, config: AppConfig, limiter: RateLimiter) =>
      WordServiceLive(repo, groupRepo, config.quotas, config.languageCheck, limiter)
    )
  }

  /** What a word row a user typed is marked as, against the dictionary's own. */
  val userSource       = "user"
  val dictionarySource = "dictionary"

  /** Origins a translation edge can have. `pivot` is a non-English pair (German–Hungarian, German–Spanish, …) inferred
    * through a shared English sense rather than asserted anywhere; `form` is a form-to-form pair inferred through its
    * lemmas' own translation (a plural paired with a plural because the singulars translate each other) — both marked
    * so a screen can say so.
    */
  val dictionaryOrigin = "dictionary"
  val pivotOrigin      = "pivot"
  val formOrigin       = "form"
  val userOrigin       = "user"

  /** The rank a word nobody has ranked gets. Matches the column default: a sentinel rather than NULL, because the two
    * dialects put NULLs in different places in an `ORDER BY` and this column decides the listing's own order.
    */
  val unrankedFrequency = 999999999

  /** [[bulkUpload]]'s size cap on `content` itself, measured the way [[Validation.utf8Length]] measures a password — in
    * bytes, since that is what the reader was told the limit was. The request body may be somewhat larger than this
    * once the JSON envelope and the two language codes are added.
    */
  val maxBulkUploadBytes = 2 * 1024 * 1024

  /** Bounds how many distinct words one [[bulkUpload]] call may touch. Neither tag membership nor dictionary creation
    * is otherwise quota-gated (see the note on [[create]]), so this is the only thing standing between an upload of
    * arbitrary text and an unbounded batch of sequential inserts.
    */
  val maxBulkUploadTokens = 2000

  /** Bounds how many rows one [[tabularImport]] call may write — [[maxBulkUploadTokens]]' counterpart on the tabular
    * path, and the only bound on it besides the shared rate limit, since a tabular import is no more quota-gated than a
    * free-text one. A row costs more than a token (up to two words, a translation edge and a pair), so the same number
    * is a stricter budget rather than a looser one.
    */
  val maxTabularRows = 2000

  /** Damerau-Levenshtein distance a bulk-upload token may be from a dictionary word and still be offered as a
    * suggestion — 2 catches the common single-substitution/transposition/insertion OCR misread without matching
    * unrelated words.
    */
  val maxSuggestionDistance = 2

  /** How many suggestions [[WordServiceLive.suggestionsFor]] offers per token, closest distance first, ties broken by
    * frequency rank.
    */
  val maxSuggestionsPerToken = 3

  /** Below this length, a distance-[[maxSuggestionDistance]] match is too loose to be a useful suggestion. */
  val minSuggestionTokenLength = 3

  /** Bounds how many still-unmatched tokens one preview call attempts suggestions for — the CPU-cost analogue of
    * [[maxBulkUploadTokens]]. A token past this bound is still listed as unmatched, just with no correction offered.
    */
  val maxSuggestionTokens = 300
}

/** What one row of a [[WordService.tabularImport]] turned into, so the response can count the whole batch without a
  * second pass over the database.
  *
  * `written` is false only for a row that was skipped outright — its source cell parsed to nothing, or the write failed
  * and was swallowed. A row that legitimately produced an answer-less entry is still `written`, which is why this is a
  * named flag rather than something inferred from the other three being zero.
  */
private final case class RowOutcome(
  written: Boolean = false,
  paired: Boolean = false,
  minted: Int = 0,
  forms: Int = 0,
) {

  def add(minted: Int, forms: Int): RowOutcome =
    copy(minted = this.minted + minted, forms = this.forms + forms)

  /** Totals only. `written`/`paired` are counted per row by the caller, so they are meaningless in a fold and stay
    * false here.
    */
  def merge(other: RowOutcome): RowOutcome =
    RowOutcome(minted = minted + other.minted, forms = forms + other.forms)
}

private object RowOutcome {
  val skipped: RowOutcome = RowOutcome()
}

final case class WordServiceLive(
  repo: WordRepository,
  groupRepo: GroupRepository,
  quotas: QuotaSection,
  languageCheck: LanguageCheckSection,
  limiter: RateLimiter,
) extends WordService {

  private def toDomain(row: WordRow): Word = {
    Word(
      row.id,
      WordLanguage.fromString(row.language).getOrElse(WordLanguage.En),
      row.text,
      PartOfSpeech.fromString(row.partOfSpeech).getOrElse(PartOfSpeech.Other),
      Gender.fromColumn(row.gender),
    )
  }

  // `editableByMe` has no default mirroring `ownedByMe` — Scala cannot read a sibling parameter's value from within
  // the same parameter list's default expression — so every call site below states it explicitly.
  private def toTag(
    row: TagRow,
    wordCount: Long,
    ownedByMe: Boolean,
    group: Option[GroupRef] = None,
    editableByMe: Boolean = false,
  ): Tag = {
    val (source, target) = tagLanguages(row)
    Tag(row.id, row.name, wordCount, ownedByMe, group, editableByMe, source, target)
  }

  /** The tag's language pair as an enum pair. The column is `NOT NULL` and only ever holds a `WordLanguage.code`, but
    * it is free text, so an unreadable value falls back to the deployment default rather than failing the read.
    */
  private def tagLanguages(row: TagRow): (WordLanguage, WordLanguage) = {
    (
      WordLanguage.fromString(row.sourceLanguage).getOrElse(WordLanguage.De),
      WordLanguage.fromString(row.targetLanguage).getOrElse(WordLanguage.Hu),
    )
  }

  /** [[toTag]]'s group resolution for a single tag, for the call sites that only ever have one row in hand
    * (`createTag`/`copyTag`/`renameTag`). `None` short-circuits with no query, since a brand-new tag never has one.
    */
  private def resolveGroupRef(groupId: Option[Long]): UIO[Option[GroupRef]] = {
    groupId match {
      case None      =>
        ZIO.succeed(None)
      case Some(gid) =>
        groupRepo.findGroupById(gid).orDie.map(_.map(g => GroupRef(g.id, g.name)))
    }
  }

  /** Batched form of [[resolveGroupRef]], for a whole page of tags at once — one query rather than one per row, the
    * same reason [[gathedge.backend.db.WordRepository.listTags]] batches its own word counts.
    */
  private def resolveGroupRefs(rows: List[TagRow]): UIO[Map[Long, GroupRef]] = {
    val ids = rows.flatMap(_.groupId).distinct
    if (ids.isEmpty)
      ZIO.succeed(Map.empty)
    else
      groupRepo.findGroupsByIds(ids).orDie.map(_.map(g => g.id -> GroupRef(g.id, g.name)).toMap)
  }

  /** Dictionary entries first, then what somebody typed, and pivoted pairs last — the order of how much each is worth
    * trusting. Ties break on frequency, so the everyday word leads.
    */
  private def rankOf(edge: WordTranslationRow): Int = {
    edge.origin match {
      case WordService.dictionaryOrigin =>
        0
      case WordService.userOrigin       =>
        1
      case _                            =>
        2
    }
  }

  private def sortTranslations(rows: List[(WordTranslationRow, WordRow)]): List[(WordTranslationRow, WordRow)] = {
    rows.sortBy { case (edge, word) => (rankOf(edge), word.frequencyRank, word.textNorm) }
  }

  /** The translations a listing row offers: the best few, plus every one the reader has already marked as a practice
    * answer.
    *
    * The cap is what keeps the cell a line rather than a dictionary entry — the detail page is where the rest are. But
    * a marked translation falling outside it would be a choice the reader made, cannot see, and can no longer undo, so
    * the union is not a nicety: it is what makes the chip reversible.
    *
    * Deduplicated on the rendered text rather than on the word id, keeping the best-ranked of each: two `words` rows
    * can render alike, and the row used to show one entry per distinct string. Each survivor's id is then unique, which
    * is what a chip acts on.
    */
  private def translationsShown(
    rows: List[(WordTranslationRow, WordRow)],
    marked: List[WordTagPairRow],
  ): List[Word] = {
    val selected = marked.map(_.translationWordId).toSet
    val options  = rows.map { case (_, word) => toDomain(word) }.distinctBy(Word.display)
    options.zipWithIndex.collect {
      case (word, index) if index < WordService.translationsPerRow || selected.contains(word.id) =>
        word
    }
  }

  def list(
    page: Int,
    pageSize: Int,
    language: Option[WordLanguage],
    search: Option[String],
    partOfSpeech: Option[PartOfSpeech],
    tagId: Option[Long],
    mine: Boolean,
    target: WordLanguage,
    translationFilter: TranslationFilter,
    mainOnly: Boolean,
    sort: Option[String],
    descending: Boolean,
    reader: Option[Long],
  ): UIO[WordPage] = {
    val taggedBy = reader.filter(_ => mine)
    val offset   = gathedge.shared.dto.Paging.offset(page, pageSize)
    // "Only mine" with no session is not an error but an empty answer — a visitor has tagged nothing. Left to the
    // filter it would read as "no narrowing at all", i.e. the whole dictionary, which is the opposite of what was
    // asked for.
    if (mine && reader.isEmpty)
      ZIO.succeed(WordPage(Nil, 0L))
    else {
      for {
        rows              <- repo
                               .listPage(
                                 offset,
                                 pageSize,
                                 language.map(WordLanguage.code),
                                 search,
                                 partOfSpeech.map(PartOfSpeech.code),
                                 tagId,
                                 taggedBy,
                                 translationFilter,
                                 WordLanguage.code(target),
                                 mainOnly,
                                 sort,
                                 descending,
                               )
                               .orDie
        total             <- repo
                               .countMatching(
                                 language.map(WordLanguage.code),
                                 search,
                                 partOfSpeech.map(PartOfSpeech.code),
                                 tagId,
                                 taggedBy,
                                 translationFilter,
                                 WordLanguage.code(target),
                                 mainOnly,
                               )
                               .orDie
        ids                = rows.map(_.id)
        // Three batch queries for the whole page rather than three per row.
        translations      <- repo.translationsOf(ids, WordLanguage.code(target)).orDie
        links             <- ZIO.foreach(reader)(userId => repo.tagsFor(userId, ids)).map(_.toList.flatten).orDie
        marked            <- ZIO.foreach(reader)(userId => repo.pairsFor(userId, ids)).map(_.toList.flatten).orDie
        // Every row's lemma (if it is itself a form) and every row's forms (if it is a lemma) -- what fills the
        // listing's Main word/Variants columns. A row reached only as a variant's lemma (`contextLemmaIds`) was not
        // itself part of the page `repo.listPage` returned, so it needs the same three batch reads over again.
        lemmaLinks        <- repo.lemmaContextOf(ids).orDie
        contextLemmaIds    = lemmaLinks.map { case (_, lemma) => lemma.id }.distinct.filterNot(ids.contains)
        formLinks         <- repo.formsContextOf((ids ++ contextLemmaIds).distinct).orDie
        extraTranslations <- repo.translationsOf(contextLemmaIds, WordLanguage.code(target)).orDie
        extraTagLinks     <-
          ZIO.foreach(reader)(userId => repo.tagsFor(userId, contextLemmaIds)).map(_.toList.flatten).orDie
        extraMarked       <- ZIO.foreach(reader)(userId => repo.pairsFor(userId, contextLemmaIds)).map(_.toList.flatten).orDie
        byWord             = sortTranslations(translations).groupBy { case (edge, _) => edge.sourceWordId }
        extraByWord        = sortTranslations(extraTranslations).groupBy { case (edge, _) => edge.sourceWordId }
        tagsByWord         = links.groupBy(_.wordId)
        extraTagsByWord    = extraTagLinks.groupBy(_.wordId)
        pairsByWord        = marked.groupBy(_.wordId)
        extraPairsByWord   = extraMarked.groupBy(_.wordId)
        pageIds            = ids.toSet
        mainWordByForm     = {
          lemmaLinks
            .groupBy { case (form, _) => form.formWordId }
            .view
            .mapValues(_.headOption.map { case (form, lemma) => WordFormRef(toDomain(lemma), form.relation) })
            .toMap
        }
        variantsByLemma    = formLinks.groupBy { case (form, _) => form.lemmaWordId }
        lemmaRowById       = lemmaLinks.map { case (_, lemma) => lemma.id -> lemma }.toMap
      } yield {
        // Matched entries first (the ★ the reader searched for), then the lemma's own commonest-first order --
        // capped for a listing row, uncapped nowhere on this path (that is `detailOf`'s job).
        def variantsFor(lemmaId: Long): (List[WordFormPreview], Int) = {
          // `word_forms` is unique on (lemma, form, relation), not (lemma, form): a dirty wiktextract tag set can
          // link the same form word to the same lemma under two different relations. Deduplicated on the form
          // word's id, same shape as `translationsShown`'s dedup, so each survivor's id is unique -- what the
          // listing's Variants column keys its rows on.
          val all    = variantsByLemma.getOrElse(lemmaId, Nil).distinctBy { case (_, word) => word.id }
          val sorted = all.sortBy { case (_, word) => (!pageIds.contains(word.id), word.frequencyRank, word.textNorm) }
          val shown  = sorted.take(WordService.wordFormsPerRow).map { case (form, word) =>
            WordFormPreview(toDomain(word), form.relation, matched = pageIds.contains(word.id))
          }
          (shown, all.size)
        }

        def summaryOf(
          row: WordRow,
          byWordTranslations: Map[Long, List[(WordTranslationRow, WordRow)]],
          byWordTags: Map[Long, List[WordTagRow]],
          byWordPairs: Map[Long, List[WordTagPairRow]],
          isContext: Boolean,
        ): WordSummary = {
          val rowPairs                  = byWordPairs.getOrElse(row.id, Nil)
          val offered                   = translationsShown(byWordTranslations.getOrElse(row.id, Nil), rowPairs)
          val shownIds                  = offered.map(_.id).toSet
          val (variants, variantsTotal) = variantsFor(row.id)
          WordSummary(
            word = toDomain(row),
            translations = offered.map(word => TranslationOption(word.id, Word.display(word))),
            tagIds = byWordTags.getOrElse(row.id, Nil).map(_.tagId),
            // Only the marks this row can render: one whose answer is in a language the listing is not translating
            // into would otherwise ship to a client with no chip to put it on.
            pairs = rowPairs
              .filter(pair => shownIds.contains(pair.translationWordId))
              .map(pair => TaggedPair(pair.tagId, pair.translationWordId))
              .distinct,
            mainWord = mainWordByForm.get(row.id).flatten,
            variants = variants,
            variantsTotal = variantsTotal,
            isContext = isContext,
          )
        }

        val pageSummaries    = rows.map(row => summaryOf(row, byWord, tagsByWord, pairsByWord, isContext = false))
        // Context rows are listed first: a reader who searched a variant's spelling sees "here is the lemma" right
        // above the row they actually matched. They are not part of `rows`, so they cannot double-count against
        // `total`/pagination -- they are context, not a match.
        val contextSummaries = contextLemmaIds.flatMap(id => {
          lemmaRowById
            .get(id)
            .map(row => summaryOf(row, extraByWord, extraTagsByWord, extraPairsByWord, isContext = true))
        })
        WordPage(items = contextSummaries ++ pageSummaries, total = total)
      }
    }
  }

  private def detailOf(row: WordRow, reader: Option[Long]): UIO[WordDetail] = {
    for {
      translations <- repo.allTranslationsOf(row.id).orDie
      tags         <- ZIO.foreach(reader)(userId => repo.tagsOfWord(userId, row.id)).map(_.toList.flatten).orDie
      marked       <- ZIO.foreach(reader)(userId => repo.pairsFor(userId, List(row.id))).map(_.toList.flatten).orDie
      mainLinks    <- repo.lemmaContextOf(List(row.id)).orDie
      formLinks    <- repo.formsContextOf(List(row.id)).orDie
      formTags     <- ZIO
                        .foreach(reader)(userId => repo.tagsFor(userId, formLinks.map { case (_, word) => word.id }))
                        .map(_.toList.flatten)
                        .orDie
      // Carried with a count of zero: the detail screen renders these as chips on one word, where "lesson1 (37)"
      // would be answering a question nobody asked. The tag bar gets the real counts from `listTags`. `tagsOfWord`
      // answers the reader's own tags and any group tag they may edit, so every row here is editable but not
      // necessarily owned — `ownedByMe` is read off the row itself rather than assumed.
      groupRefs    <- resolveGroupRefs(tags)
      counted       = tags.map(tag => {
                        toTag(
                          tag,
                          0L,
                          ownedByMe = reader.contains(tag.userId),
                          tag.groupId.flatMap(groupRefs.get),
                          editableByMe = true,
                        )
                      })
      tagsByForm    = formTags.groupBy(_.wordId)
    } yield WordDetail(
      word = toDomain(row),
      translations = sortTranslations(translations).map { case (edge, word) =>
        TranslationEntry(
          id = edge.id,
          word = toDomain(word),
          origin = edge.origin,
          ownedByMe = reader.isDefined && edge.createdBy == reader,
        )
      },
      tags = counted,
      // Every mark on this word, in whichever tag: this screen shows every translation, so unlike the listing
      // (which narrows them to the three it offers) there is no chip a mark could arrive without.
      pairs = marked.map(pair => TaggedPair(pair.tagId, pair.translationWordId)).distinct,
      mainWords = mainLinks.map { case (form, lemma) => WordFormRef(toDomain(lemma), form.relation) },
      // Grouped and ordered by GrammarTag's category priority -- the same numbering the frontend groups by, so the two
      // never disagree about which category of forms comes first.
      forms = formLinks
        .map { case (form, word) => (form, word, GrammarTag.categoryOf(form.relation)) }
        .sortBy { case (_, word, category) => (GrammarTag.priorityOf(category), word.textNorm) }
        .map { case (form, word, _) =>
          WordFormEntry(toDomain(word), form.relation, tagsByForm.getOrElse(word.id, Nil).map(_.tagId))
        },
    )
  }

  def detail(id: Long, reader: Option[Long]): IO[WordFailure, WordDetail] = {
    for {
      row    <- repo.findWordById(id).orDie.someOrFail(WordFailure.NotFound)
      detail <- detailOf(row, reader)
    } yield detail
  }

  /** The three conditions a word must meet before its gender may be filled in, read straight off the row.
    *
    * They are [[ensure]]'s `keptGender` rule the other way round: a gender is kept there exactly when the language has
    * genders and the word is a noun, so those are the words that can be missing one. The third — the gender being one
    * the language actually has — is what keeps `Neuter` out of Spanish, which has two genders and not three.
    */
  private def genderFillable(row: WordRow, gender: Gender): Boolean = {
    val language = WordLanguage.fromString(row.language)
    row.gender.isEmpty &&
    row.partOfSpeech == PartOfSpeech.code(PartOfSpeech.Noun) &&
    language.exists(l => LanguageProfile.of(l).genders.contains(gender))
  }

  /** Fills in a missing gender, or says why it cannot.
    *
    * The conflict is checked twice on purpose. The lookup before the write is what turns the ordinary case -- `das
    * Haus` already being its own row -- into a 409 the reader can act on rather than a unique-index defect. The retry
    * after a failed write covers the race the lookup cannot: another caller taking that identity in between. Between
    * them sits [[WordRepository.setWordGender]]'s own `gender = ''` guard, which answers `0` rather than overwriting
    * when somebody else filled the blank first -- also a conflict from this caller's side, since the word they were
    * looking at is no longer the word they are editing.
    *
    * `userId` names the account for the log line only. Nothing here is scoped to it: a word belongs to nobody, so
    * filling in an article it was imported without is open to any session, exactly as adding a word is.
    */
  def setGender(wordId: Long, gender: Gender, userId: Long): IO[WordFailure, WordDetail] = {
    val code = Gender.code(gender)

    def conflicting(row: WordRow): UIO[Option[WordRow]] = {
      repo.findWord(row.language, row.textNorm, row.partOfSpeech, code).orDie
    }

    for {
      row     <- repo.findWordById(wordId).orDie.someOrFail(WordFailure.NotFound)
      _       <- ZIO.unless(genderFillable(row, gender))(ZIO.fail(WordFailure.GenderNotApplicable))
      taken   <- conflicting(row)
      _       <- ZIO.when(taken.isDefined)(ZIO.fail(WordFailure.GenderConflict))
      updated <- repo
                   .setWordGender(wordId, code)
                   .catchAll(error => {
                     conflicting(row).flatMap {
                       case Some(_) => ZIO.succeed(0L)
                       case None    => ZIO.die(error)
                     }
                   })
      _       <- ZIO.when(updated == 0L)(ZIO.fail(WordFailure.GenderConflict))
      _       <- ZIO.logInfo(s"words.setGender id=$wordId gender=$code user=$userId")
      filled  <- repo.findWordById(wordId).orDie.someOrFail(WordFailure.NotFound)
      detail  <- detailOf(filled, Some(userId))
    } yield detail
  }

  /** Turns a typed word into the row it identifies, creating it if the dictionary has never heard of it.
    *
    * Gender is only kept for a noun in a language that has gender at all: an English noun with an article attached
    * would be a second, unfindable copy of the same word.
    */
  private def ensure(
    language: WordLanguage,
    text: String,
    partOfSpeech: PartOfSpeech,
    gender: Option[Gender],
    userId: Long,
  ): IO[WordFailure, WordRow] = {
    ensureCounted(language, text, partOfSpeech, gender, userId).map { case (row, _) => row }
  }

  /** [[ensure]], also answering whether the word had to be created — what [[tabularImport]] counts as new to the
    * dictionary. The body lives here rather than in [[ensure]] so the two can never derive a different identity key.
    */
  private def ensureCounted(
    language: WordLanguage,
    text: String,
    partOfSpeech: PartOfSpeech,
    gender: Option[Gender],
    userId: Long,
  ): IO[WordFailure, (WordRow, Boolean)] = {
    val trimmed    = text.trim
    val keptGender = {
      if (LanguageProfile.of(language).hasGenders && partOfSpeech == PartOfSpeech.Noun)
        gender
      else
        None
    }
    for {
      valid <- ZIO
                 .fromEither(Validation.validateWordText(trimmed))
                 .mapError(error => WordFailure.ValidationError(Map("text" -> error)))
      now   <- Clock.currentTime(TimeUnit.MILLISECONDS)
      row   <- repo
                 .ensureWordCounted(
                   WordRow(
                     id = 0L,
                     language = WordLanguage.code(language),
                     text = valid,
                     textNorm = valid.toLowerCase,
                     partOfSpeech = PartOfSpeech.code(partOfSpeech),
                     gender = Gender.toColumn(keptGender),
                     frequencyRank = WordService.unrankedFrequency,
                     source = WordService.userSource,
                     createdBy = Some(userId),
                     createdAt = now,
                     textSearch = TextSearch.fold(valid.toLowerCase),
                   )
                 )
                 .orDie
    } yield row
  }

  /** The target word of a translation the caller is adding, and whether the edge was new.
    *
    * `false` means they had already recorded it — still the translation they mean, which is why this answers the word
    * rather than failing. [[addTranslation]] turns that into the 409; [[create]] does not, because a duplicate is no
    * reason to refuse a request that is about adding a *word*, and it still needs the word's id to mark it for
    * practice.
    */
  private def linkOrExisting(
    source: WordRow,
    translation: NewTranslation,
    userId: Long,
  ): IO[WordFailure, (WordRow, Boolean)] = {
    for {
      target <- ensure(
                  translation.language,
                  translation.text,
                  // A translation with no part of speech given takes the source word's: a noun translates to a noun.
                  translation.partOfSpeech.getOrElse(decode(source.partOfSpeech)),
                  translation.gender,
                  userId,
                )
      _      <- ZIO.when(target.id == source.id)(ZIO.fail(WordFailure.ValidationError(Map.empty)))
      known  <- repo.findTranslation(source.id, target.id, Some(userId)).orDie
      added  <- ZIO
                  .when(known.isEmpty)(
                    for {
                      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
                      _   <- repo
                               .insertTranslationPair(source.id, target.id, WordService.userOrigin, Some(userId), now)
                               .orDie
                    } yield ()
                  )
                  .map(_.isDefined)
    } yield (target, added)
  }

  private def link(source: WordRow, translation: NewTranslation, userId: Long): IO[WordFailure, Unit] = {
    linkOrExisting(source, translation, userId).flatMap { case (_, added) =>
      if (added)
        ZIO.unit
      else
        ZIO.fail(WordFailure.DuplicateTranslation)
    }
  }

  private def decode(code: String): PartOfSpeech = PartOfSpeech.fromString(code).getOrElse(PartOfSpeech.Other)

  /** Links `row` into `word_forms` as an inflected/declined form of `request.mainWordId`, under `request.variantType` —
    * only when both are given, since a `variantType` naming nothing to link is nothing to do. `NotFound` covers a
    * `mainWordId` that names no word; the language mismatch check exists because an inflection always shares its
    * lemma's language, and a caller past the frontend's own same-language search could otherwise link across two.
    * Idempotent like every other write [[create]] makes: re-submitting the same word with the same main word and
    * variant type links nothing new.
    */
  private def linkMainWord(row: WordRow, request: CreateWordRequest): IO[WordFailure, Unit] = {
    (request.mainWordId, request.variantType) match {
      case (Some(mainWordId), Some(variantType)) =>
        for {
          main     <- repo.findWordById(mainWordId).orDie.someOrFail(WordFailure.NotFound)
          _        <- ZIO.when(main.language != row.language)(
                        ZIO.fail(
                          WordFailure
                            .ValidationError(Map("mainWordId" -> MessageRef(MessageKeys.wordMainWordLanguageMismatch)))
                        )
                      )
          existing <- repo.formsOf(mainWordId).orDie
          _        <- ZIO.unless(existing.exists(form => form.formWordId == row.id && form.relation == variantType))(
                        for {
                          now <- Clock.currentTime(TimeUnit.MILLISECONDS)
                          _   <- repo.insertForms(List(WordFormRow(0L, mainWordId, row.id, variantType, now))).orDie
                        } yield ()
                      )
        } yield ()
      case _                                     =>
        ZIO.unit
    }
  }

  def create(request: CreateWordRequest, userId: Long): IO[WordFailure, WordDetail] = {
    for {
      row     <- ensure(request.language, request.text, request.partOfSpeech, request.gender, userId)
      _       <- linkMainWord(row, request)
      // A translation the caller has already recorded is not a reason to refuse the whole request: they are adding a
      // word, and the duplicate simply already says what they meant.
      targets <- ZIO.foreach(request.translations)(translation => {
                   linkOrExisting(row, translation, userId).map { case (target, _) => target }
                 })
      // Tag membership is never quota-gated — only the marks below are — so this always goes through, even when the
      // pair quota below refuses every mark.
      _       <- ZIO.foreachDiscard(request.tagIds)(tagId => tagWord(row.id, tagId, userId))
      // A translation somebody bothered to type is the answer they want to be asked for, so it is marked as one
      // straight away — the same state clicking its chip on the listing produces. `tagWord` above has already checked
      // each tag belongs to the caller. A combination already marked (possible when a translation already existed) is
      // idempotent and never charged, mirroring `selectPair`. The quota is checked once for the whole batch, before
      // any mark is written, so a request that would cross the *hard* threshold leaves no pairs behind at all.
      pending <- ZIO.filter(request.tagIds.flatMap(tagId => targets.map(target => (tagId, target))))({
                   case (tagId, target) => pairAlreadyMarked(userId, row.id, tagId, target.id).map(marked => !marked)
                 })
      _       <- ZIO.when(pending.nonEmpty) {
                   for {
                     // `tagWord` above has already confirmed the caller may write to each tag, but not who owns it —
                     // a group tag may belong to somebody else, and quotas are charged to that owner, not the caller.
                     owners    <- ZIO.foreach(pending.map { case (tagId, _) => tagId }.distinct) { tagId =>
                                    repo.findTagById(tagId).orDie.someOrFail(WordFailure.NotFound).map(tagId -> _.userId)
                                  }
                     ownerByTag = owners.toMap
                     // `pairTranslation` writes one row per direction, so each genuinely new mark adds two, checked
                     // once per owner rather than once for the whole batch, so one group tag's owner being near their
                     // limit cannot block a mark in an unrelated tag of the caller's own.
                     _         <- ZIO.foreachDiscard(pending.groupBy { case (tagId, _) => ownerByTag(tagId) }) {
                                    case (ownerId, owed) =>
                                      repo.countPairsOwnedBy(ownerId).orDie.flatMap(pairQuota(_, owed.size.toLong * 2L))
                                  }
                   } yield ()
                 }
      _       <- ZIO.foreachDiscard(pending) { case (tagId, target) => pairInTag(row.id, tagId, target.id) }
      detail  <- detailOf(row, Some(userId))
    } yield detail
  }

  def addTranslation(wordId: Long, translation: NewTranslation, userId: Long): IO[WordFailure, WordDetail] = {
    for {
      row    <- repo.findWordById(wordId).orDie.someOrFail(WordFailure.NotFound)
      _      <- link(row, translation, userId)
      detail <- detailOf(row, Some(userId))
    } yield detail
  }

  def removeTranslation(wordId: Long, translationId: Long, userId: Long): IO[WordFailure, Unit] = {
    for {
      edge <- repo.findTranslationById(translationId).orDie.someOrFail(WordFailure.NotFound)
      // Naming the wrong word is as much a miss as naming no edge at all.
      _    <- ZIO.unless(edge.sourceWordId == wordId)(ZIO.fail(WordFailure.NotFound))
      rows <- repo.deleteTranslationPair(translationId, userId).orDie
      // Somebody else's edge, or the dictionary's: from this caller's side there is no such translation of theirs.
      _    <- ZIO.when(rows == 0L)(ZIO.fail(WordFailure.NotFound))
    } yield ()
  }

  def listTags(userId: Long): UIO[List[Tag]] = {
    for {
      rows          <- repo.listTags(userId).orDie
      groupRefs     <- resolveGroupRefs(rows.map { case (row, _, _) => row })
      memberships   <- groupRepo.listMembershipsFor(userId).orDie
      // A tag not owned by the caller is still theirs to edit if it sits in a group they belong to — the same test
      // `WordService.requireEditableTag` makes a write against, restated here so the tag bar/collect picker can offer
      // it without the reader having to click first and find out.
      memberGroupIds = memberships.map(_.groupId).toSet
    } yield Tag.sorted(rows.map { case (row, count, ownedByMe) =>
      val editableByMe = ownedByMe || row.groupId.exists(memberGroupIds.contains)
      toTag(row, count, ownedByMe, row.groupId.flatMap(groupRefs.get), editableByMe)
    })
  }

  /** The name-half of creating a tag, shared by [[createTag]], [[copyTag]] and [[renameTag]]: valid, and not already
    * the caller's, before either goes anywhere near a quota or a write. `excludeTagId` is [[renameTag]]'s own id, so a
    * rename that keeps (or case-changes) the tag's current name is not flagged as colliding with itself.
    */
  private def prepareTagName(
    name: String,
    userId: Long,
    excludeTagId: Option[Long] = None,
  ): IO[WordFailure, (String, String)] = {
    for {
      valid    <- ZIO
                    .fromEither(Validation.validateTagName(name))
                    .mapError(error => WordFailure.ValidationError(Map("name" -> error)))
      normal    = Tag.normalize(valid)
      existing <- repo.findTag(userId, normal).orDie
      _        <- ZIO.when(existing.exists(tag => !excludeTagId.contains(tag.id)))(ZIO.fail(WordFailure.DuplicateTag))
    } yield (valid, normal)
  }

  /** The arithmetic behind every quota check here, so tags and pairs are blocked and warned by exactly the same rule:
    * an account may hold up to `hard` items total; a write whose resulting total (`currentCount + adding`) would exceed
    * it is refused outright, and one that would only reach or pass `soft` still succeeds, carrying a warning.
    *
    * `Left` names nothing — the caller already knows which quota it is checking, and picks the failure case and the
    * `MessageRef` itself, since the same rule feeds two different [[WordFailure]] cases.
    */
  private def checkQuota(currentCount: Long, adding: Long, soft: Int, hard: Int): Either[Unit, Boolean] = {
    val newTotal = currentCount + adding
    if (newTotal > hard)
      Left(())
    else
      Right(newTotal >= soft)
  }

  private def tagQuota(currentCount: Long, adding: Long): IO[WordFailure, Option[MessageRef]] = {
    checkQuota(currentCount, adding, quotas.tagsPerUserSoft, quotas.tagsPerUserHard) match {
      case Left(())      =>
        ZIO.fail(WordFailure.TagQuotaExceeded(quotas.tagsPerUserHard))
      case Right(warned) =>
        ZIO.succeed(
          Option.when(warned)(
            MessageRef(
              MessageKeys.wordTagQuotaWarning,
              List((currentCount + adding).toString, quotas.tagsPerUserHard.toString),
            )
          )
        )
    }
  }

  private def pairQuota(currentCount: Long, adding: Long): IO[WordFailure, Option[MessageRef]] = {
    checkQuota(currentCount, adding, quotas.wordPairsPerUserSoft, quotas.wordPairsPerUserHard) match {
      case Left(())      =>
        ZIO.fail(WordFailure.PairQuotaExceeded(quotas.wordPairsPerUserHard))
      case Right(warned) =>
        ZIO.succeed(
          Option.when(warned)(
            MessageRef(
              MessageKeys.wordPairQuotaWarning,
              List((currentCount + adding).toString, quotas.wordPairsPerUserHard.toString),
            )
          )
        )
    }
  }

  /** `source` and `target` must differ — the same check on both sides of the wire
    * ([[Validation.validateTagLanguages]]). Its `MessageRef` is surfaced as a plain 400, not a field error, since
    * neither box on the form owns it.
    */
  private def requireDistinctLanguages(source: WordLanguage, target: WordLanguage): IO[WordFailure, Unit] = {
    ZIO
      .fromEither(Validation.validateTagLanguages(source, target))
      .mapError(_ => WordFailure.LanguageMismatch)
      .unit
  }

  def createTag(
    name: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): IO[WordFailure, TagResponse] = {
    for {
      _              <- requireDistinctLanguages(sourceLanguage, targetLanguage)
      prepared       <- prepareTagName(name, userId)
      (valid, normal) = prepared
      owned          <- repo.countTagsOwnedBy(userId).orDie
      warning        <- tagQuota(owned, 1)
      now            <- Clock.currentTime(TimeUnit.MILLISECONDS)
      row            <-
        repo
          .insertTag(userId, valid, normal, now, WordLanguage.code(sourceLanguage), WordLanguage.code(targetLanguage))
          .orDie
    } yield TagResponse(toTag(row, 0L, ownedByMe = true, editableByMe = true), warning)
  }

  /** [[createTagWithPairs]]'s write: name, both quotas, and every word on either side are checked first; only then is
    * anything created. The two passes are the point — a `New` word is a write, so checking pair two's `Existing` id
    * while pair one's new word was already in the dictionary would leave that word behind on a request that answered
    * 404.
    */
  def createTagWithPairs(
    name: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    pairs: List[TagPairInput],
    userId: Long,
  ): IO[WordFailure, TagResponse] = {
    for {
      _              <- requireDistinctLanguages(sourceLanguage, targetLanguage)
      prepared       <- prepareTagName(name, userId)
      (valid, normal) = prepared
      ownedTags      <- repo.countTagsOwnedBy(userId).orDie
      tagWarning     <- tagQuota(ownedTags, 1)
      ownedPairs     <- repo.countPairsOwnedBy(userId).orDie
      pairWarning    <- pairQuota(ownedPairs, pairs.size * 2)
      checked        <- ZIO.foreach(pairs)(checkPair(_, sourceLanguage, targetLanguage))
      resolved       <- ZIO.foreach(checked)(createPair(_, userId))
      now            <- Clock.currentTime(TimeUnit.MILLISECONDS)
      tagRow         <- repo
                          .createTagWithPairs(
                            userId,
                            valid,
                            normal,
                            now,
                            WordLanguage.code(sourceLanguage),
                            WordLanguage.code(targetLanguage),
                            resolved,
                          )
                          .orDie
      wordCount       = resolved.flatMap { case (s, t) => List(s, t) }.toSet.size
    } yield TagResponse(toTag(tagRow, wordCount.toLong, ownedByMe = true), tagWarning.orElse(pairWarning))
  }

  /** One side of a pair that is known to be writable but is not written yet: `Left` is the id of a word that exists,
    * `Right` is a new word whose text has already validated.
    */
  private type CheckedWord = Either[Long, TagPairWord.New]

  /** Both sides of one pair, checked and not yet written, against the tag's language pair — order-independent: the two
    * sides must be the tag's two languages, one each, whichever way round.
    */
  private def checkPair(
    pair: TagPairInput,
    source: WordLanguage,
    target: WordLanguage,
  ): IO[WordFailure, (CheckedWord, CheckedWord)] = {
    val pairLanguages = Set(source, target)
    for {
      s <- checkWord(pair.source, pairLanguages)
      t <- checkWord(pair.target, pairLanguages)
      _ <- ZIO.unless(Set(s._2, t._2) == pairLanguages)(ZIO.fail(WordFailure.LanguageMismatch))
    } yield (s._1, t._1)
  }

  /** Every way one side can fail, decided without writing: an `Existing` side must name a real word, a `New` side must
    * carry text [[ensure]] would accept, and either must be in one of the tag's two languages. Answers the resolved
    * side together with its language, so [[checkPair]] can then require the two sides to cover both.
    */
  private def checkWord(ref: TagPairWord, allowed: Set[WordLanguage]): IO[WordFailure, (CheckedWord, WordLanguage)] = {
    val allowedCodes = allowed.map(WordLanguage.code)
    ref match {
      case TagPairWord.Existing(id) =>
        repo
          .findWordById(id)
          .orDie
          .someOrFail(WordFailure.NotFound)
          .filterOrFail(row => allowedCodes.contains(row.language))(WordFailure.LanguageMismatch)
          .map(row => (Left(row.id), WordLanguage.fromString(row.language).getOrElse(WordLanguage.En)))
      case word: TagPairWord.New    =>
        ZIO
          .fromEither(Validation.validateWordText(word.text.trim))
          .mapError(error => WordFailure.ValidationError(Map("text" -> error)))
          .filterOrFail(_ => allowed.contains(word.language))(WordFailure.LanguageMismatch)
          .as((Right(word), word.language))
    }
  }

  private def createPair(pair: (CheckedWord, CheckedWord), userId: Long): IO[WordFailure, (Long, Long)] = {
    for {
      sourceId <- createWord(pair._1, userId)
      targetId <- createWord(pair._2, userId)
    } yield (sourceId, targetId)
  }

  private def createWord(checked: CheckedWord, userId: Long): IO[WordFailure, Long] = {
    checked match {
      case Left(id)    => ZIO.succeed(id)
      case Right(word) => ensure(word.language, word.text, word.partOfSpeech, word.gender, userId).map(_.id)
    }
  }

  /** A snapshot copy: every word the source tag carries and every practice pair marked inside it travel into the new
    * tag as one unit of work ([[gathedge.backend.db.WordRepository.copyTag]]), and the two are independent from that
    * moment on.
    *
    * Both quotas are checked '''before''' that write — one new tag, and as many new pair rows as the source tag carries
    * — so a copy that would cross either *hard* threshold fails with nothing written at all, rather than a tag left
    * behind with half its pairs. Crossing only a *soft* one still succeeds; if both did, the tag warning wins, since
    * [[gathedge.shared.dto.TagResponse]] carries one.
    */
  def copyTag(tagId: Long, userId: Long): IO[WordFailure, TagResponse] = {
    for {
      source          <- repo.findTagById(tagId).orDie.someOrFail(WordFailure.TagNotFound)
      prepared        <- prepareTagName(source.name, userId)
      (valid, normal)  = prepared
      ownedTags       <- repo.countTagsOwnedBy(userId).orDie
      ownedPairs      <- repo.countPairsOwnedBy(userId).orDie
      copiedPairCount <- repo.countPairsInTag(tagId).orDie
      tagWarning      <- tagQuota(ownedTags, 1)
      pairWarning     <- pairQuota(ownedPairs, copiedPairCount)
      now             <- Clock.currentTime(TimeUnit.MILLISECONDS)
      copied          <- repo.copyTag(tagId, userId, valid, normal, now).orDie
    } yield {
      val (row, wordCount, _) = copied
      TagResponse(toTag(row, wordCount, ownedByMe = true, editableByMe = true), tagWarning.orElse(pairWarning))
    }
  }

  def renameTag(tagId: Long, name: String, userId: Long): IO[WordFailure, TagResponse] = {
    for {
      existing        <- requireOwnTag(tagId, userId)
      prepared        <- prepareTagName(name, userId, excludeTagId = Some(tagId))
      (valid, normal)  = prepared
      rows            <- repo.updateTag(tagId, userId, valid, normal).orDie
      _               <- ZIO.when(rows == 0L)(ZIO.fail(WordFailure.TagNotFound))
      wordCount       <- repo.countWordsInTag(tagId).orDie
      group           <- resolveGroupRef(existing.groupId)
      (source, target) = tagLanguages(existing)
    } yield TagResponse(
      Tag(tagId, valid, wordCount, ownedByMe = true, group, editableByMe = true, source, target),
      None,
    )
  }

  def setTagLanguages(
    tagId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): IO[WordFailure, TagResponse] = {
    for {
      existing  <- requireOwnTag(tagId, userId)
      _         <- requireDistinctLanguages(sourceLanguage, targetLanguage)
      rows      <- repo
                     .setTagLanguages(tagId, WordLanguage.code(sourceLanguage), WordLanguage.code(targetLanguage))
                     .orDie
      // `0` rows on a tag that exists means it already holds a `word_tag_pairs` row — the pair is locked.
      _         <- ZIO.when(rows == 0L)(ZIO.fail(WordFailure.LanguagesLocked))
      wordCount <- repo.countWordsInTag(tagId).orDie
      group     <- resolveGroupRef(existing.groupId)
    } yield TagResponse(
      Tag(
        tagId,
        existing.name,
        wordCount,
        ownedByMe = true,
        group,
        editableByMe = true,
        sourceLanguage,
        targetLanguage,
      ),
      None,
    )
  }

  def deleteTag(tagId: Long, userId: Long): IO[WordFailure, Unit] = {
    repo
      .deleteTag(tagId, userId)
      .orDie
      .flatMap(rows => ZIO.when(rows == 0L)(ZIO.fail(WordFailure.TagNotFound)))
      .unit
  }

  /** Renaming/deleting a tag: checks the tag belongs to the caller, and answers `TagNotFound` when it does not — whose
    * tag a given id is is not something an account may learn by trying. Deliberately narrower than
    * [[requireEditableTag]]: structural changes to a tag (its name, its existence) stay the owner's alone even when the
    * tag belongs to a group, unlike editing its content.
    */
  private def requireOwnTag(tagId: Long, userId: Long): IO[WordFailure, TagRow] = {
    repo
      .findTagById(tagId)
      .orDie
      .someOrFail(WordFailure.TagNotFound)
      .filterOrFail(_.userId == userId)(WordFailure.TagNotFound)
  }

  /** Editing a tag's *content* — putting a word on it, marking a practice pair, bulk-uploading into it: the owner, or
    * any member (admin or plain member alike) of the group it belongs to, per the write-access rule agreed for
    * classroom collaboration. `TagNotFound` covers both "no such tag" and "not the owner and not in its group" — same
    * 404-hides-existence rule [[requireOwnTag]] follows, just with one more way in.
    */
  private def requireEditableTag(tagId: Long, userId: Long): IO[WordFailure, TagRow] = {
    for {
      tag     <- repo.findTagById(tagId).orDie.someOrFail(WordFailure.TagNotFound)
      allowed <- if (tag.userId == userId)
                   ZIO.succeed(true)
                 else {
                   tag.groupId match {
                     case Some(groupId) =>
                       groupRepo.findMembership(groupId, userId).orDie.map(_.isDefined)
                     case None          =>
                       ZIO.succeed(false)
                   }
                 }
      _       <- ZIO.unless(allowed)(ZIO.fail(WordFailure.TagNotFound))
    } yield tag
  }

  def tagWord(wordId: Long, tagId: Long, userId: Long): IO[WordFailure, Unit] = {
    for {
      tag  <- requireEditableTag(tagId, userId)
      word <- repo.findWordById(wordId).orDie.someOrFail(WordFailure.NotFound)
      // The word has to be in one of the tag's two languages — either side is fine.
      _    <- ZIO.unless(word.language == tag.sourceLanguage || word.language == tag.targetLanguage)(
                ZIO.fail(WordFailure.LanguageMismatch)
              )
      now  <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _    <- repo.tagWord(wordId, tagId, now).orDie
    } yield ()
  }

  def untagWord(wordId: Long, tagId: Long, userId: Long): IO[WordFailure, Unit] = {
    for {
      _ <- requireEditableTag(tagId, userId)
      // Removing a tag that is not on the word is nothing to do, not a failure — the same rule as putting one on.
      _ <- repo.untagWord(wordId, tagId).orDie
    } yield ()
  }

  /** The translation has to be one the word actually has: an arbitrary pair of word ids is not a translation, and the
    * practice screen would be asking a question with nothing behind it. Reuses `allTranslationsOf`, which also proves
    * the translation word exists, so there is no second lookup.
    */
  private def requireTranslationOf(wordId: Long, translationWordId: Long): IO[WordFailure, Unit] = {
    repo
      .allTranslationsOf(wordId)
      .orDie
      .flatMap(edges => {
        ZIO.unless(edges.exists { case (edge, _) => edge.targetWordId == translationWordId })(
          ZIO.fail(WordFailure.NotFound)
        )
      })
      .unit
  }

  /** The write itself, with the checks already done — shared with [[create]], which has just inserted the edge it would
    * otherwise re-read.
    */
  private def pairInTag(wordId: Long, tagId: Long, translationWordId: Long): UIO[Unit] = {
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _   <- repo.pairTranslation(wordId, tagId, translationWordId, now).orDie
    } yield ()
  }

  /** Whether `translationWordId` is already marked for `wordId` inside `tagId` — reusing [[WordRepository.pairsFor]]
    * rather than adding a lookup of its own, since it already answers exactly that filtered to one owner. Idempotent
    * writes never count against the pair quota: [[WordRepository.pairTranslation]] adds nothing when the pair is
    * already there, so there is nothing new to charge for.
    */
  private def pairAlreadyMarked(userId: Long, wordId: Long, tagId: Long, translationWordId: Long): UIO[Boolean] = {
    repo
      .pairsFor(userId, List(wordId))
      .orDie
      .map(_.exists(pair => pair.tagId == tagId && pair.translationWordId == translationWordId))
  }

  def selectPair(
    wordId: Long,
    tagId: Long,
    translationWordId: Long,
    userId: Long,
  ): IO[WordFailure, PairSelectionResponse] = {
    for {
      tag     <- requireEditableTag(tagId, userId)
      word    <- repo.findWordById(wordId).orDie.someOrFail(WordFailure.NotFound)
      _       <- requireTranslationOf(wordId, translationWordId)
      // The two words have to be the tag's two languages, one each — whichever way round.
      answer  <- repo.findWordById(translationWordId).orDie.someOrFail(WordFailure.NotFound)
      _       <- ZIO.unless(Set(word.language, answer.language) == Set(tag.sourceLanguage, tag.targetLanguage))(
                   ZIO.fail(WordFailure.LanguageMismatch)
                 )
      already <- pairAlreadyMarked(userId, wordId, tagId, translationWordId)
      // `pairTranslation` writes one row per direction, so a genuinely new mark adds two. Charged against the tag's
      // *owner* (`tag.userId`), not the caller — quotas stay per-account regardless of who in the group is doing the
      // marking, per the classroom write-access rule.
      warning <- if (already) ZIO.succeed(None) else repo.countPairsOwnedBy(tag.userId).orDie.flatMap(pairQuota(_, 2))
      _       <- pairInTag(wordId, tagId, translationWordId)
    } yield PairSelectionResponse(warning)
  }

  def deselectPair(wordId: Long, tagId: Long, translationWordId: Long, userId: Long): IO[WordFailure, Unit] = {
    for {
      _ <- requireEditableTag(tagId, userId)
      // Unmarking something that is not marked is nothing to do, not a failure — the rule `untagWord` follows, and what
      // lets the chip be safe to double-click.
      _ <- repo.unpairTranslation(wordId, tagId, translationWordId).orDie
    } yield ()
  }

  // -- The unified tag editor -------------------------------------------------------------------

  /** [[TagEntryRow]]s dressed for the wire: each source word's other known translations into the tag's target language
    * become the row's `otherTranslations` (the edited row's target picker reads them), the marked answer excluded, plus
    * the two per-reader flags `createdByMe` and `inMyOtherTags` the editor's "imported by me" / "only in this tag"
    * filters read. Two batch queries for the whole list.
    */
  private def toTagEntries(tag: TagRow, rows: List[TagEntryRow], viewerId: Long): UIO[List[TagEntry]] = {
    val targetLang = tagLanguages(tag)._2
    val sourceIds  = rows.map(_.source.id).distinct
    for {
      known         <- translationsInto(sourceIds, targetLang)
      otherTagWords <- repo.sourceWordsInMyOtherTags(viewerId, tag.id, sourceIds).orDie
    } yield rows.map { row =>
      val others = known.getOrElse(row.source.id, Nil).filterNot(option => row.target.exists(_.id == option.wordId))
      TagEntry(
        toDomain(row.source),
        row.target.map(toDomain),
        row.imported,
        row.exact,
        createdByMe = row.source.source == WordService.userSource && row.source.createdBy.contains(viewerId),
        inMyOtherTags = otherTagWords.contains(row.source.id),
        others,
        row.comment,
        row.targetComment,
      )
    }
  }

  private def oneTagEntry(tag: TagRow, row: TagEntryRow, viewerId: Long): UIO[TagEntry] = {
    toTagEntries(tag, List(row), viewerId).map(_.head)
  }

  /** The row `(sourceId, targetId)` as the editor will show it after a write — read back from
    * [[gathedge.backend.db.WordRepository.tagEntries]] so `imported`/`exact` are whatever the write left them, with a
    * plain fallback for the rare stale-read case.
    */
  private def entryAfterWrite(tag: TagRow, sourceId: Long, targetId: Option[Long], viewerId: Long): UIO[TagEntry] = {
    for {
      rows   <- repo.tagEntries(tag.id).orDie
      source <- repo.findWordById(sourceId).orDie
      target <- targetId match {
                  case Some(id) => repo.findWordById(id).orDie
                  case None     => ZIO.succeed(None)
                }
      found   = rows.find(row => row.source.id == sourceId && row.target.map(_.id) == targetId)
      row     = found.orElse(source.map(s => TagEntryRow(s, target, imported = false, exact = false)))
      entry  <- row match {
                  case Some(r) => oneTagEntry(tag, r, viewerId)
                  case None    =>
                    ZIO.succeed(
                      TagEntry(
                        Word(sourceId, WordLanguage.En, "", PartOfSpeech.Other, None),
                        None,
                        imported = false,
                        exact = false,
                        createdByMe = false,
                        inMyOtherTags = false,
                        Nil,
                      )
                    )
                }
    } yield entry
  }

  def tagEntries(tagId: Long, userId: Long): IO[WordFailure, List[TagEntry]] = {
    for {
      tag  <- repo.findTagById(tagId).orDie.someOrFail(WordFailure.TagNotFound)
      rows <- repo.tagEntries(tagId).orDie
      out  <- toTagEntries(tag, rows, userId)
    } yield out
  }

  def addPair(tagId: Long, pair: TagPairInput, userId: Long): IO[WordFailure, TagEntryResponse] = {
    for {
      tag                 <- requireEditableTag(tagId, userId)
      (source, target)     = tagLanguages(tag)
      checked             <- checkPair(pair, source, target)
      resolved            <- createPair(checked, userId)
      (sourceId, targetId) = resolved
      already             <- pairAlreadyMarked(userId, sourceId, tagId, targetId)
      warning             <- if (already) ZIO.succeed(None)
                             else repo.countPairsOwnedBy(tag.userId).orDie.flatMap(pairQuota(_, 2))
      _                   <- pairInTag(sourceId, tagId, targetId)
      entry               <- entryAfterWrite(tag, sourceId, Some(targetId), userId)
    } yield TagEntryResponse(entry, warning)
  }

  def replacePair(tagId: Long, request: ReplacePairRequest, userId: Long): IO[WordFailure, TagEntryResponse] = {
    for {
      tag                       <- requireEditableTag(tagId, userId)
      (source, target)           = tagLanguages(tag)
      checked                   <- checkPair(TagPairInput(request.next.source, request.next.target), source, target)
      resolved                  <- createPair(checked, userId)
      (newSourceId, newTargetId) = resolved
      already                   <- pairAlreadyMarked(userId, newSourceId, tagId, newTargetId)
      // A genuine swap is net-zero on `word_tag_pairs`; only filling in a row that had no pair is a new charge.
      warning                   <- if (already || request.oldTargetWordId.isDefined) ZIO.succeed(None)
                                   else repo.countPairsOwnedBy(tag.userId).orDie.flatMap(pairQuota(_, 2))
      now                       <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _                         <- repo
                                     .replacePair(tagId, request.oldSourceWordId, request.oldTargetWordId, newSourceId, newTargetId, now)
                                     .orDie
      entry                     <- entryAfterWrite(tag, newSourceId, Some(newTargetId), userId)
    } yield TagEntryResponse(entry, warning)
  }

  def removeEntry(
    tagId: Long,
    sourceWordId: Long,
    targetWordId: Option[Long],
    userId: Long,
  ): IO[WordFailure, Unit] = {
    for {
      _ <- requireEditableTag(tagId, userId)
      _ <- targetWordId match {
             case Some(targetId) => repo.removePair(tagId, sourceWordId, targetId).orDie
             case None           => repo.removeEntry(tagId, sourceWordId).orDie
           }
    } yield ()
  }

  def removeEntries(tagId: Long, pairs: List[PairRef], userId: Long): IO[WordFailure, Unit] = {
    for {
      _ <- requireEditableTag(tagId, userId)
      _ <- ZIO.foreachDiscard(pairs) {
             case PairRef(sourceWordId, Some(targetId)) => repo.removePair(tagId, sourceWordId, targetId).orDie
             case PairRef(sourceWordId, None)           => repo.removeEntry(tagId, sourceWordId).orDie
           }
    } yield ()
  }

  def deleteWords(tagId: Long, wordIds: List[Long], userId: Long): IO[WordFailure, Unit] = {
    for {
      _         <- requireEditableTag(tagId, userId)
      rows      <- repo.findWordsByIds(wordIds.distinct).orDie
      mine       = rows.filter(w => w.source == WordService.userSource && w.createdBy.contains(userId)).map(_.id)
      elsewhere <- repo.wordsInOtherTags(tagId, mine).orDie
      // A word another tag still holds is left alone. A word a game references makes the `words` delete fail on
      // Postgres (RESTRICT); `.either` per id skips it rather than failing the batch.
      _         <- ZIO.foreachDiscard(mine.filterNot(elsewhere.contains))(id => repo.deleteOwnedWord(id, userId).either)
    } yield ()
  }

  /** Unicode-letter runs, lowercased and deduplicated in the order they first appear, capped at
    * [[WordService.maxBulkUploadTokens]] — what turns an uploaded file's raw text into candidate dictionary words.
    * Apostrophes and hyphens are kept mid-word (`don't`, `mother-in-law`) but never lead a token.
    */
  private val bulkUploadTokenPattern = """\p{L}[\p{L}'’-]*""".r

  /** Every article form either of an upload's two declared languages recognises, for [[tokenize]]'s regex and
    * [[dedupeArticledVariants]]'s redisplay — built from [[LanguageProfile]] rather than naming an article here, so a
    * third gendered language merges into an upload's token scan with no change to this file.
    */
  private def articleForms(languages: List[WordLanguage]): Set[String] = {
    languages.flatMap(language => LanguageProfile.of(language).articleForms.keys).toSet
  }

  /** Same as [[bulkUploadTokenPattern]], but a leading article immediately before a word is consumed together with it
    * as one token — the merged alternative comes first so `findAllIn` prefers it over matching the article alone. Built
    * only from `forms` actually in play for this upload (see [[bulkUploadPreview]]), since "die" and "el" are ordinary
    * English/other-language words otherwise.
    */
  private def bulkUploadArticledTokenPattern(forms: Set[String]) = {
    val alternation = forms.toList.sortBy(-_.length).mkString("|")
    ("""(?i)\b(?:""" + alternation + """)\b\s+\p{L}[\p{L}'’-]*|\p{L}[\p{L}'’-]*""").r
  }

  private def tokenize(content: String, languages: List[WordLanguage]): List[String] = {
    val forms   = articleForms(languages)
    val pattern = if (forms.isEmpty) bulkUploadTokenPattern else bulkUploadArticledTokenPattern(forms)
    pattern.findAllIn(content).map(_.toLowerCase).toList.distinct.take(WordService.maxBulkUploadTokens)
  }

  /** Splits a token's leading article off in `language`'s own terms, answering the bare word and the gender it names —
    * what [[matchTokens]] looks the word up by, and what [[confirmManualPair]]/[[confirmStandaloneWord]] create it
    * with. A token with no such prefix (or a lone article with nothing after it) passes through unchanged. A thin
    * wrapper over [[LanguageProfile.strip]], kept as its own name since every call site already reads as "strip the
    * article".
    */
  private def stripArticle(token: String, language: WordLanguage): (String, Option[Gender]) = {
    LanguageProfile.of(language).strip(token)
  }

  /** Collapses tokens that share a bare word into one, so a reader who typed both `"Hund"` and `"der Hund"` in the same
    * upload sees one leftover entry, not two. Which of `languages` the article belongs to is not yet known at this
    * point — an upload's leftovers are unmatched against *both* declared languages' dictionaries — so the article
    * actually typed is kept verbatim for display rather than reconstructed from a gender, and capitalization follows
    * whichever language claims that article. Order-preserving by each group's first occurrence, since [[tokenize]]'s
    * own dedup is.
    */
  private def dedupeArticledVariants(tokens: List[String], languages: List[WordLanguage]): List[String] = {
    val forms = articleForms(languages)

    def split(token: String): (String, Option[String]) = {
      if (forms.isEmpty)
        (token, None)
      else {
        token.trim.split("\\s+", 2) match {
          case Array(article, rest) if forms.contains(article.toLowerCase) => (rest, Some(article.toLowerCase))
          case _                                                           => (token, None)
        }
      }
    }

    def capitalizes(article: String): Boolean = {
      languages.exists(language => {
        val profile = LanguageProfile.of(language)
        profile.capitalizesNouns && profile.articleForms.contains(article)
      })
    }

    tokens
      .map(token => token -> split(token))
      .groupBy { case (_, (bare, _)) => bare }
      .toList
      .sortBy { case (_, group) => tokens.indexOf(group.head._1) }
      .map { case (bare, group) =>
        group.collectFirst { case (_, (_, Some(article))) => article } match {
          case Some(article) => article + " " + (if (capitalizes(article)) bare.capitalize else bare)
          case None          => bare
        }
      }
  }

  /** Collapses dictionary rows sharing one `textNorm` down to at most one noun and one non-noun -- a reader thinks of
    * "bitte" as one word, not the five senses/tagging gaps [[matchTokens]]'s bare-key lookup happens to return. Two
    * rows survive only for a genuine noun/non-noun split (`der See`/`die See` never collide here, since gender is part
    * of a noun's own identity, not a second sense sharing a bare key with a non-noun). Within each half, the row
    * carrying a translation wins -- more useful to show than a bare duplicate -- then the one with a gender set (a real
    * German noun always has an article; a genderless noun row alongside a gendered one is an import gap, not a second
    * sense), then the commoner by [[WordRow.frequencyRank]] for a deterministic tie.
    */
  private def collapseHomonyms(rows: List[WordRow], translations: Map[Long, List[TranslationOption]]): List[WordRow] = {
    val nounCode                                         = PartOfSpeech.code(PartOfSpeech.Noun)
    def best(candidates: List[WordRow]): Option[WordRow] = {
      candidates
        .sortBy(row => (translations.getOrElse(row.id, Nil).isEmpty, row.gender.isEmpty, row.frequencyRank))
        .headOption
    }
    rows
      .groupBy(_.textNorm)
      .toList
      .sortBy { case (_, group) => rows.indexWhere(_.textNorm == group.head.textNorm) }
      .flatMap { case (_, group) =>
        val (nouns, others) = group.partition(_.partOfSpeech == nounCode)
        List(best(nouns), best(others)).flatten
      }
  }

  /** Checked at the top of both [[bulkUploadPreview]] and [[bulkUploadConfirm]]: one shared rate-limit budget (a full
    * upload is a preview call and a confirm call, so a five-attempt window covers two or three whole uploads) and the
    * same `TagNotFound` a caller of [[tagWord]]/[[selectPair]] gets — bulk-uploading is content editing like they are,
    * so it goes through [[requireEditableTag]] too: the owner, or any member of the tag's group.
    */
  private def bulkUploadGuard(tagId: Long, userId: Long): IO[BulkUploadFailure, TagRow] = {
    val rateLimitKey = RateLimitKey.wordUpload(userId)
    for {
      blocked <- limiter.isBlocked(rateLimitKey)
      _       <- ZIO.when(blocked) {
                   SecurityLog.warn(s"Rate limit exceeded on bulk word upload for user $userId") *>
                     ZIO.fail(BulkUploadFailure.RateLimited)
                 }
      _       <- limiter.recordFailure(rateLimitKey)
      tag     <- requireEditableTag(tagId, userId).mapError(_ => BulkUploadFailure.TagNotFound)
    } yield tag
  }

  /** Tokenizes `content` and writes every token into the tag in text order — the review-free replacement for the
    * [[bulkUploadPreview]]/[[bulkUploadConfirm]] round-trip. An exact pair (a source word and its dictionary
    * translation into the target language both present in the text) is marked with the pair's `exact` flag; a source-
    * or target-language match with no such pair is tagged answer-less; a token in neither dictionary becomes a new
    * `sourceLanguage` word, tagged answer-less. Every membership carries the `imported` flag. Homonyms are collapsed
    * the same way [[bulkUploadPreview]] collapses them.
    */
  def bulkImport(
    tagId: Long,
    content: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): IO[BulkUploadFailure, BulkImportResponse] = {
    val invalidFile   =
      BulkUploadFailure.ValidationError(Map("content" -> MessageRef(MessageKeys.wordBulkUploadInvalidFile)))
    val languageClash =
      BulkUploadFailure.ValidationError(Map("sourceLanguage" -> MessageRef(MessageKeys.wordTagLanguageMismatch)))
    val languages     = List(sourceLanguage, targetLanguage)
    val bareIn        = (language: WordLanguage) => (s: String) => stripArticle(s, language)._1.toLowerCase

    def tagAnswerless(wordId: Long, now: Long): UIO[List[Long]] =
      repo.importWord(wordId, tagId, now).orDie.as(List(wordId))

    def markExact(sourceId: Long, targetWordId: Long, now: Long): UIO[List[Long]] =
      repo.importPair(sourceId, tagId, targetWordId, now).orDie.as(List(sourceId, targetWordId))

    def createAndTag(display: String, now: Long): UIO[List[Long]] = {
      val (bare, gender) = stripArticle(display, sourceLanguage)
      val profile        = LanguageProfile.of(sourceLanguage)
      val text           = profile.capitalize(bare, gender)
      val partOfSpeech   = if (gender.isDefined) PartOfSpeech.Noun else PartOfSpeech.Other
      ensure(sourceLanguage, text, partOfSpeech, gender, userId)
        .flatMap(row => repo.importWord(row.id, tagId, now).orDie.as(List(row.id)))
        .catchAll(_ => ZIO.succeed(Nil))
    }

    for {
      tag                     <- bulkUploadGuard(tagId, userId)
      // The import's two languages have to be the tag's two, whichever way round; new words are minted in
      // `sourceLanguage`.
      _                       <- ZIO.when(
                                   Set(WordLanguage.code(sourceLanguage), WordLanguage.code(targetLanguage)) !=
                                     Set(tag.sourceLanguage, tag.targetLanguage)
                                 )(ZIO.fail(languageClash))
      trimmed                  = content.trim
      _                       <- ZIO.when(trimmed.isEmpty || Validation.utf8Length(trimmed) > WordService.maxBulkUploadBytes)(
                                   ZIO.fail(invalidFile)
                                 )
      tokens                   = tokenize(trimmed, languages)
      sourceMatched           <- matchTokens(sourceLanguage, tokens)
      (sourceMatchesRaw, rem1) = sourceMatched
      targetMatched           <- matchTokens(targetLanguage, rem1)
      (targetMatchesRaw, rem2) = targetMatched
      unmatched                = dedupeArticledVariants(rem2, languages)
      sourceTranslations      <- translationsInto(sourceMatchesRaw.map(_.id), targetLanguage)
      targetTranslations      <- translationsInto(targetMatchesRaw.map(_.id), sourceLanguage)
      sourceMatches            = collapseHomonyms(sourceMatchesRaw, sourceTranslations)
      targetMatches            = collapseHomonyms(targetMatchesRaw, targetTranslations)
      importedTargetIds        = targetMatches.map(_.id).toSet
      exactBySource            = sourceMatches.flatMap { row =>
                                   sourceTranslations
                                     .getOrElse(row.id, Nil)
                                     .find(option => importedTargetIds.contains(option.wordId))
                                     .map(option => row.id -> option.wordId)
                                 }.toMap
      dupTargetIds             = exactBySource.values.toSet
      now                     <- Clock.currentTime(TimeUnit.MILLISECONDS)
      tokenPos                 = (row: WordRow, language: WordLanguage) =>
                                   tokens.indexWhere(token => stripArticle(token, language)._1 == row.textNorm)
      exactActions             = exactBySource.toList.flatMap { case (sourceId, targetWordId) =>
                                   sourceMatches
                                     .find(_.id == sourceId)
                                     .map(row => tokenPos(row, sourceLanguage) -> markExact(sourceId, targetWordId, now))
                                 }
      sourceWordActions        = sourceMatches
                                   .filterNot(row => exactBySource.contains(row.id))
                                   .map(row => tokenPos(row, sourceLanguage) -> tagAnswerless(row.id, now))
      targetWordActions        = targetMatches
                                   .filterNot(row => dupTargetIds.contains(row.id))
                                   .map(row => tokenPos(row, targetLanguage) -> tagAnswerless(row.id, now))
      unmatchedActions         = unmatched.map { display =>
                                   val bare = bareIn(sourceLanguage)(display)
                                   val pos  = tokens.indexWhere(token => bareIn(sourceLanguage)(token) == bare)
                                   (if (pos < 0) tokens.size else pos) -> createAndTag(display, now)
                                 }
      ordered                  = (exactActions ++ sourceWordActions ++ targetWordActions ++ unmatchedActions)
                                   .sortBy(_._1)
                                   .map(_._2)
      written                 <- ZIO.foreach(ordered)(identity)
    } yield BulkImportResponse(written.flatten.toSet.size, exactBySource.size, unmatched.size)
  }

  /** Samples the pasted text and reports whether enough of it is in the tag's two languages. Tokenized the same way a
    * real [[bulkImport]] would tokenize it (so an article-led German noun counts once), a random
    * [[LanguageCheckSection.sampleSize]] of the distinct tokens is looked up by bare word — one
    * [[WordRepository.findWordsByKeys]] query per language, the "one query, not N" shape [[matchTokens]] uses — and a
    * token in neither result set is a miss. `acceptable` is false once the misses pass
    * [[LanguageCheckSection.unrecognizedThreshold]].
    */
  def checkLanguage(
    content: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
  ): UIO[LanguageCheckResponse] = {
    val languages = List(sourceLanguage, targetLanguage)
    val tokens    = tokenize(content.trim, languages)
    if (tokens.isEmpty) {
      ZIO.succeed(LanguageCheckResponse(sampled = 0, unrecognized = 0, acceptable = true))
    } else {
      def bareKeys(sample: List[String], language: WordLanguage): List[String] =
        sample.map(token => stripArticle(token, language)._1)

      def norms(language: WordLanguage, keys: List[String]): UIO[Set[String]] =
        repo.findWordsByKeys(WordLanguage.code(language), keys).orDie.map(_.map(_.textNorm).toSet)

      for {
        shuffled <- Random.shuffle(tokens)
        sample    = shuffled.take(languageCheck.sampleSize)
        srcNorms <- norms(sourceLanguage, bareKeys(sample, sourceLanguage))
        tgtNorms <- norms(targetLanguage, bareKeys(sample, targetLanguage))
        misses    = sample.count(token => {
                      !srcNorms.contains(stripArticle(token, sourceLanguage)._1) &&
                      !tgtNorms.contains(stripArticle(token, targetLanguage)._1)
                    })
      } yield LanguageCheckResponse(
        sampled = sample.size,
        unrecognized = misses,
        acceptable = misses <= languageCheck.unrecognizedThreshold,
      )
    }
  }

  def tabularImport(
    tagId: Long,
    rows: List[TabularRow],
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): IO[BulkUploadFailure, TabularImportResponse] = {
    val invalidFile   =
      BulkUploadFailure.ValidationError(Map("rows" -> MessageRef(MessageKeys.wordBulkUploadInvalidFile)))
    val languageClash =
      BulkUploadFailure.ValidationError(Map("sourceLanguage" -> MessageRef(MessageKeys.wordTagLanguageMismatch)))

    // One vocabulary per side, each with its own language winning a collision: German `w` must read as feminine on
    // the German column even when the other column is Hungarian, whose `nn` must still be understood there too.
    val sourceMarkers = MarkerVocabulary.forPair(sourceLanguage, targetLanguage)
    val targetMarkers = MarkerVocabulary.forPair(targetLanguage, sourceLanguage)

    /** Writes the `word_forms` rows an extra column asked for. A form word needs a relation to be filed under —
      * `word_forms.relation` is `NOT NULL` and part of its UNIQUE key — so an unlabelled one is dropped, as is a
      * relation with no word to attach it to. Idempotent against `existingFormRelations`, the same guard
      * [[linkMainWord]] applies.
      */
    def writeForms(lemma: WordRow, extra: Option[ExtraCell], language: WordLanguage, now: Long): UIO[Int] = {
      val wanted = extra.toList.flatMap(cell => {
        val relation = cell.relations.distinct.sorted.mkString(",")
        if (relation.isEmpty) Nil else cell.formWords.map(_ -> relation)
      })
      if (wanted.isEmpty)
        ZIO.succeed(0)
      else {
        for {
          known   <- repo.existingFormRelations(List(lemma.id)).orDie
          present  = known.map { case (lemmaId, formId, relation) => (lemmaId, formId, relation) }.toSet
          written <- ZIO.foreach(wanted) { case (text, relation) =>
                       ensure(language, text, decode(lemma.partOfSpeech), None, userId)
                         .flatMap(form => {
                           if (form.id == lemma.id || present.contains((lemma.id, form.id, relation)))
                             ZIO.succeed(0)
                           else {
                             repo
                               .insertForms(List(WordFormRow(0L, lemma.id, form.id, relation, now)))
                               .orDie
                               .as(1)
                           }
                         })
                         .catchAll(_ => ZIO.succeed(0))
                     }
        } yield written.sum
      }
    }

    /** Records the pair the row asserts, unless the caller had already recorded it. Written from the two rows already
      * in hand rather than through [[linkOrExisting]], which would `ensure` the target a second time. Both directions
      * go in, as everywhere else — `insertTranslationPair` writes the mirror.
      */
    def linkRows(source: WordRow, target: WordRow, now: Long): UIO[Unit] = {
      if (source.id == target.id)
        ZIO.unit
      else {
        repo
          .findTranslation(source.id, target.id, Some(userId))
          .orDie
          .flatMap(known => {
            ZIO.when(known.isEmpty)(
              repo.insertTranslationPair(source.id, target.id, WordService.userOrigin, Some(userId), now).orDie
            )
          })
          .unit
      }
    }

    /** Every word one cell named, minted or found, with the count of the ones that were new. */
    def ensureCells(cell: WordCell, language: WordLanguage): IO[WordFailure, (List[WordRow], Int)] = {
      ZIO
        .foreach(cell.words)(word => ensureCounted(language, word.text, word.partOfSpeech, word.gender, userId))
        .map(results => (results.map(_._1), results.count(_._2)))
    }

    /** Records the reader's note against every word the side named, but only when they wrote one: a cell with no note
      * must not clear the note a previous import or a hand edit left on the same membership.
      */
    def writeComment(rows: List[WordRow], comment: Option[String]): UIO[Unit] = {
      comment match {
        case None       =>
          ZIO.unit
        case Some(note) =>
          ZIO.foreachDiscard(rows)(word => repo.setTagComment(word.id, tagId, Some(note)).orDie)
      }
    }

    /** One row's writes, or [[RowOutcome.skipped]] when the row had no source word to hang anything on.
      *
      * A cell can name several words — `tető/padlás` is two translations, `Jurist(in)` is a word and its feminine
      * counterpart — and every one of them is paired with every one the other cell named. The reader put them on one
      * line, which is the assertion that any of them answers any of the others; splitting the line into separate rows
      * instead would claim an alignment the file never stated.
      */
    def writeRow(row: TabularRow, now: Long): UIO[RowOutcome] = {
      val sourceExtra = row.sourceExtra.map(WordCell.parseExtra(_, sourceLanguage, sourceMarkers))
      val targetExtra = row.targetExtra.map(WordCell.parseExtra(_, targetLanguage, targetMarkers))

      // The extra column's genders are folded in before the part of speech is read: a one-word cell is a noun only once
      // a gender is known, and `ensure` discards a gender that does not belong to one.
      val sourceCell = WordCell
        .parseWord(row.source, sourceLanguage, sourceMarkers)
        .withExtra(sourceExtra, sourceLanguage)
      val targetCell = WordCell
        .parseWord(row.target, targetLanguage, targetMarkers)
        .withExtra(targetExtra, targetLanguage)

      if (sourceCell.words.isEmpty)
        ZIO.succeed(RowOutcome.skipped)
      else {
        val effect = for {
          sourced             <- ensureCells(sourceCell, sourceLanguage)
          (sources, srcMinted) = sourced
          targeted            <- ensureCells(targetCell, targetLanguage)
          (targets, tgtMinted) = targeted
          _                   <- if (targets.isEmpty)
                                   ZIO.foreachDiscard(sources)(src => repo.importWord(src.id, tagId, now).orDie)
                                 else {
                                   // The reader asserted these pairs by putting both cells on one line, so each edge is
                                   // recorded even for a word the dictionary has never heard of. That is the whole
                                   // difference from `bulkImport`, which can only mark a pair the dictionary knew.
                                   ZIO.foreachDiscard(sources)(src => {
                                     ZIO.foreachDiscard(targets)(tgt =>
                                       linkRows(src, tgt, now) *> repo.importPair(src.id, tagId, tgt.id, now).orDie
                                     )
                                   })
                                 }
          _                   <- writeComment(sources, sourceCell.comment)
          _                   <- writeComment(targets, targetCell.comment)
          // Forms hang off the stem — the first word the cell named — since an ending or an alternative is a word in
          // its own right, not something a `word_forms` row was written about.
          srcForms            <- ZIO.foreach(sources.headOption.toList)(writeForms(_, sourceExtra, sourceLanguage, now))
          tgtForms            <- ZIO.foreach(targets.headOption.toList)(writeForms(_, targetExtra, targetLanguage, now))
        } yield RowOutcome(
          written = true,
          paired = targets.nonEmpty,
          minted = srcMinted + tgtMinted,
          forms = srcForms.sum + tgtForms.sum,
        )

        // A row the dictionary or validation rejects is skipped, never fatal: one bad line must not cost the reader
        // the other two thousand. The same leniency `bulkImport.createAndTag` applies per token.
        effect.catchAll(_ => ZIO.succeed(RowOutcome.skipped))
      }
    }

    for {
      tag     <- bulkUploadGuard(tagId, userId)
      _       <- ZIO.when(
                   Set(WordLanguage.code(sourceLanguage), WordLanguage.code(targetLanguage)) !=
                     Set(tag.sourceLanguage, tag.targetLanguage)
                 )(ZIO.fail(languageClash))
      _       <- ZIO.when(rows.isEmpty || rows.size > WordService.maxTabularRows)(ZIO.fail(invalidFile))
      _       <- ZIO.when(Validation.utf8Length(rows.map(cells).mkString) > WordService.maxBulkUploadBytes)(
                   ZIO.fail(invalidFile)
                 )
      now     <- Clock.currentTime(TimeUnit.MILLISECONDS)
      // Strictly sequential: `tagMemberships` answers in insertion order, and that order is the reader's own row
      // order, which the editor shows back to them.
      written <- ZIO.foreach(rows)(row => writeRow(row, now))
    } yield {
      val totals = written.foldLeft(RowOutcome.skipped)((acc, row) => acc.merge(row))
      TabularImportResponse(
        rows = written.count(_.written),
        pairs = written.count(_.paired),
        newWords = totals.minted,
        forms = totals.forms,
      )
    }
  }

  /** Every cell of one row, for the size check — the same bound the free-text path applies to its `content`. */
  private def cells(row: TabularRow): String = {
    row.source + row.target + row.sourceExtra.getOrElse("") + row.targetExtra.getOrElse("")
  }

  def checkColumnLanguages(columns: List[ColumnSample]): UIO[ColumnLanguageCheckResponse] = {
    // Every study language, not just a tag's two: the mapping step's job is to say what a column *is*, including one
    // the reader has not assigned yet.
    ZIO
      .foreach(columns)(column => {
        val values = column.values.map(_.trim).filter(_.nonEmpty).distinct
        if (values.isEmpty)
          ZIO.succeed(ColumnLanguageGuess(column.index, sampled = 0, hits = Nil, best = None))
        else {
          for {
            shuffled <- Random.shuffle(values)
            sample    = shuffled.take(languageCheck.sampleSize)
            hits     <- ZIO.foreach(WordLanguage.all)(language => {
                          // Parsed the same way the import will parse it, so a marker or an article never counts as
                          // part of the word being looked up.
                          val markers = MarkerVocabulary.forPair(language, language)
                          val keys    = sample.map(value => WordCell.parseWord(value, language, markers).text.toLowerCase)
                          repo
                            .findWordsByKeys(WordLanguage.code(language), keys)
                            .orDie
                            .map(found => {
                              val norms = found.map(_.textNorm).toSet
                              LanguageHit(language, keys.count(norms.contains))
                            })
                        })
          } yield ColumnLanguageGuess(
            index = column.index,
            sampled = sample.size,
            hits = hits,
            best = hits.filter(_.matched > 0).maxByOption(_.matched).map(_.language),
          )
        }
      })
      .map(ColumnLanguageCheckResponse.apply)
  }

  /** The tokens already in the dictionary for `language`, and whatever is left. Looks each token up by its bare word
    * (see [[stripArticle]]) since `textNorm` never carries an article, but keeps the original token — article and all —
    * in the unmatched remainder, so a reader still sees `"der tisch"` rather than a bare `"tisch"`.
    */
  private def matchTokens(language: WordLanguage, tokens: List[String]): UIO[(List[WordRow], List[String])] = {
    val keyed = tokens.map(token => token -> stripArticle(token, language)._1)
    repo.findWordsByKeys(WordLanguage.code(language), keyed.map(_._2)).orDie.map { existing =>
      val matchedNorms = existing.map(_.textNorm).toSet
      (existing, keyed.collect { case (display, bare) if !matchedNorms.contains(bare) => display })
    }
  }

  /** For up to [[WordService.maxSuggestionTokens]] of `tokens`, up to [[WordService.maxSuggestionsPerToken]] dictionary
    * words in `language` within [[WordService.maxSuggestionDistance]] edits — one batched
    * [[WordRepository.findWordsByLengthRange]] query for the whole call, not one per token, the same "one query, not N"
    * shape [[matchTokens]] itself uses. Looks up by the token's bare word (see [[stripArticle]]), the same key
    * [[matchTokens]] uses, since `textNorm` never carries an article. A token shorter than
    * [[WordService.minSuggestionTokenLength]], or with no candidate within the bound, is simply absent from the result
    * map — [[matchTokens]]'s own leniency toward "found nothing" applies here too.
    */
  private def suggestionsFor(language: WordLanguage, tokens: List[String]): UIO[Map[String, List[(WordRow, Int)]]] = {
    val keyed = tokens
      .map(token => token -> stripArticle(token, language)._1)
      .filter { case (_, bare) => bare.length >= WordService.minSuggestionTokenLength }
      .take(WordService.maxSuggestionTokens)
    if (keyed.isEmpty) {
      ZIO.succeed(Map.empty)
    } else {
      val lengths = keyed.map { case (_, bare) => bare.length }
      val minLen  = math.max(1, lengths.min - WordService.maxSuggestionDistance)
      val maxLen  = lengths.max + WordService.maxSuggestionDistance
      repo.findWordsByLengthRange(WordLanguage.code(language), minLen, maxLen).orDie.map { candidates =>
        keyed.flatMap { case (display, bare) =>
          val nearby =
            candidates.filter(c => math.abs(c.textNorm.length - bare.length) <= WordService.maxSuggestionDistance)
          val scored =
            nearby.flatMap(c => EditDistance.within(bare, c.textNorm, WordService.maxSuggestionDistance).map(c -> _))
          val top    = scored.sortBy { case (c, d) => (d, c.frequencyRank) }.take(WordService.maxSuggestionsPerToken)
          Option.when(top.nonEmpty)(display -> top)
        }.toMap
      }
    }
  }

  /** Each word's existing translations into `language` — one batch query, the same [[WordRepository.translationsOf]]
    * the listing itself reads, grouped back onto the word that owns them.
    */
  private def translationsInto(wordIds: List[Long], language: WordLanguage): UIO[Map[Long, List[TranslationOption]]] = {
    if (wordIds.isEmpty)
      ZIO.succeed(Map.empty)
    else {
      repo
        .translationsOf(wordIds, WordLanguage.code(language))
        .orDie
        .map(
          _.groupBy { case (edge, _) => edge.sourceWordId }.view
            .mapValues(_.map { case (_, word) => TranslationOption(word.id, Word.display(toDomain(word))) })
            .toMap
        )
    }
  }

  def bulkUploadPreview(
    tagId: Long,
    content: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    fuzzyMatching: Boolean,
    userId: Long,
  ): IO[BulkUploadFailure, BulkUploadPreviewResponse] = {
    val invalidFile =
      BulkUploadFailure.ValidationError(Map("content" -> MessageRef(MessageKeys.wordBulkUploadInvalidFile)))
    for {
      _                             <- bulkUploadGuard(tagId, userId)
      trimmed                        = content.trim
      _                             <- ZIO.when(trimmed.isEmpty || Validation.utf8Length(trimmed) > WordService.maxBulkUploadBytes)(
                                         ZIO.fail(invalidFile)
                                       )
      tokens                         = tokenize(trimmed, List(sourceLanguage, targetLanguage))
      sourceMatched                 <- matchTokens(sourceLanguage, tokens)
      (sourceMatches, remaining1)    = sourceMatched
      targetMatched                 <- matchTokens(targetLanguage, remaining1)
      (targetMatches, remaining2Raw) = targetMatched
      matchedIds                     = (sourceMatches.map(_.id) ++ targetMatches.map(_.id)).toSet
      remaining2                     = dedupeArticledVariants(remaining2Raw, List(sourceLanguage, targetLanguage))
      // Near-miss corrections are only worth computing for text a camera read. A `.txt` file or a paste is exact, so
      // the client sends `fuzzyMatching = false` and every unmatched token stays in `unmatched`.
      sourceSuggested0              <- if (fuzzyMatching) suggestionsFor(sourceLanguage, remaining2)
                                       else ZIO.succeed(Map.empty[String, List[(WordRow, Int)]])
      sourceSuggested                = sourceSuggested0.view
                                         .mapValues(_.filterNot { case (row, _) => matchedIds.contains(row.id) })
                                         .filter { case (_, cs) => cs.nonEmpty }
                                         .toMap
      remaining3                     = remaining2.filterNot(sourceSuggested.contains)
      targetSuggested0              <- if (fuzzyMatching) suggestionsFor(targetLanguage, remaining3)
                                       else ZIO.succeed(Map.empty[String, List[(WordRow, Int)]])
      targetSuggested                = targetSuggested0.view
                                         .mapValues(_.filterNot { case (row, _) => matchedIds.contains(row.id) })
                                         .filter { case (_, cs) => cs.nonEmpty }
                                         .toMap
      unmatched                      = remaining3.filterNot(targetSuggested.contains)
      sourceTranslations            <- translationsInto(sourceMatches.map(_.id), targetLanguage)
      targetTranslations            <- translationsInto(targetMatches.map(_.id), sourceLanguage)
      sourceSuggestionIds            = sourceSuggested.values.flatten.map { case (row, _) => row.id }.toList.distinct
      targetSuggestionIds            = targetSuggested.values.flatten.map { case (row, _) => row.id }.toList.distinct
      sourceSuggestionTranslations  <- translationsInto(sourceSuggestionIds, targetLanguage)
      targetSuggestionTranslations  <- translationsInto(targetSuggestionIds, sourceLanguage)
      // Wider than any of the maps above, which are each restricted to one counterpart language: this is what answers
      // `BulkUploadMatch.hasAnyTranslation`, one batch query for every candidate this preview shows.
      candidateIds                   =
        (matchedIds ++ sourceSuggestionIds ++ targetSuggestionIds).toList
      anyTranslationIds             <- repo.wordIdsWithAnyTranslation(candidateIds).orDie
      // A source match whose dictionary translation into the target language was itself an imported token: badge it
      // "exact" and drop that standalone target row, so a reader who typed both a word and its translation sees one
      // row, not two. Compared by word id, so it is blind to how each side renders its article.
      importedTargetIds              = targetMatches.map(_.id).toSet
      // For each source match, the id of its dictionary translation that was itself an imported token, if any. This is
      // both the "exact" test and what the browser marks — it never has to guess from list order.
      exactTranslationBySource       = sourceMatches.flatMap { row =>
                                         sourceTranslations
                                           .getOrElse(row.id, Nil)
                                           .find(t => importedTargetIds.contains(t.wordId))
                                           .map(t => row.id -> t.wordId)
                                       }.toMap
      dupTargetIds                   = exactTranslationBySource.values.toSet
      targetMatchesDeduped           = targetMatches.filterNot(row => dupTargetIds.contains(row.id))
      // Also put that translation at the head of its match's list, so it reads first as well as being pre-selected.
      sourceTranslationsOrdered      = sourceTranslations.map { case (wordId, options) =>
                                         wordId -> options.sortBy(option => !importedTargetIds.contains(option.wordId))
                                       }
      buildMatch                     = { (row: WordRow, translationMap: Map[Long, List[TranslationOption]]) =>
        BulkUploadMatch(
          toDomain(row),
          translationMap.getOrElse(row.id, Nil),
          anyTranslationIds.contains(row.id),
          exactTranslationBySource.contains(row.id),
          exactTranslationBySource.get(row.id),
        )
      }
      // First appearance in the uploaded text, stripping the article in the row's own language — `textNorm` never
      // carries one. A token matched in the source language never re-matches in the target, so every row maps to a
      // distinct token, and interleaving the two sides by this index restores the reader's own order.
      tokenPos                       = { (row: WordRow, language: WordLanguage) =>
        tokens.indexWhere(token => stripArticle(token, language)._1 == row.textNorm)
      }
      matched                        = {
        val sourceOrdered = {
          collapseHomonyms(sourceMatches, sourceTranslationsOrdered)
            .map(row => buildMatch(row, sourceTranslationsOrdered) -> tokenPos(row, sourceLanguage))
        }
        val targetOrdered = {
          collapseHomonyms(targetMatchesDeduped, targetTranslations)
            .map(row => buildMatch(row, targetTranslations) -> tokenPos(row, targetLanguage))
        }
        (sourceOrdered ++ targetOrdered).sortBy(_._2).map(_._1)
      }
      suggestions                    = {
        sourceSuggested.toList.sortBy { case (token, _) => remaining2.indexOf(token) }.flatMap {
          case (token, candidates) =>
            candidates.map { case (row, distance) =>
              BulkUploadSuggestion(token, buildMatch(row, sourceSuggestionTranslations), distance)
            }
        } ++
          targetSuggested.toList.sortBy { case (token, _) => remaining3.indexOf(token) }.flatMap {
            case (token, candidates) =>
              candidates.map { case (row, distance) =>
                BulkUploadSuggestion(token, buildMatch(row, targetSuggestionTranslations), distance)
              }
          }
      }
    } yield BulkUploadPreviewResponse(matched, suggestions, unmatched)
  }

  /** Tags an accepted matched word, and marks only the one translation the reader picked out of whatever
    * [[bulkUploadPreview]] showed alongside it (`selectedTranslationId`) — re-derived against the dictionary's current
    * translations into the *other* of the two declared languages rather than trusting the id on its own, the same way
    * [[BulkUploadConfirmRequest]]'s own scaladoc describes for `acceptedWordIds`. A word with no selection, or whose
    * selection no longer matches a real translation, is still tagged, just with nothing paired. `pairInTag` tags the
    * translation word too, so its id is answered alongside `wordId`. Answers no ids for a word id that no longer exists
    * (the rare race of a preview going stale), the same leniency [[matchTokens]]'s callers already have toward one bad
    * entry not sinking the whole batch.
    */
  private def acceptMatch(
    wordId: Long,
    selectedTranslationId: Option[Long],
    tagId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    now: Long,
  ): UIO[List[Long]] = {
    repo.findWordById(wordId).orDie.flatMap {
      case None       =>
        ZIO.succeed(Nil)
      case Some(word) =>
        val other = if (word.language == WordLanguage.code(sourceLanguage)) targetLanguage else sourceLanguage
        for {
          _            <- repo.tagWord(wordId, tagId, now).orDie
          translations <- repo.translationsOf(List(wordId), WordLanguage.code(other)).orDie
          selected      = translations.collectFirst {
                            case (_, target) if selectedTranslationId.contains(target.id) => target.id
                          }
          _            <- ZIO.foreachDiscard(selected)(pairInTag(wordId, tagId, _))
        } yield wordId :: selected.toList
    }
  }

  /** Creates both sides of a manually paired word if the dictionary does not have them yet, links them as a translation
    * (unless the caller already has, e.g. confirming the same upload twice), and tags and marks both — by reusing
    * [[ensure]]/[[linkOrExisting]]/[[pairInTag]] exactly as [[create]] does for a single typed word. Whichever side's
    * language has genders has its leading article stripped (see [[stripArticle]]) and created as a noun carrying that
    * gender; the other side is created exactly as typed. Leniently answers no ids for a pair either side of which fails
    * validation, the same way a bad token in the old single-shot upload was dropped rather than failing the batch.
    */
  private def confirmManualPair(
    pair: BulkUploadManualPair,
    tagId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): UIO[List[Long]] = {
    val (sourceStripped, sourceGender) = stripArticle(pair.sourceText, sourceLanguage)
    val (targetStripped, targetGender) = stripArticle(pair.targetText, targetLanguage)
    val sourceText                     = LanguageProfile.of(sourceLanguage).capitalize(sourceStripped, sourceGender)
    val targetText                     = LanguageProfile.of(targetLanguage).capitalize(targetStripped, targetGender)
    val sourcePos                      = if (sourceGender.isDefined) PartOfSpeech.Noun else PartOfSpeech.Other
    val targetPos                      = if (targetGender.isDefined) Some(PartOfSpeech.Noun) else None
    (for {
      sourceRow     <- ensure(sourceLanguage, sourceText, sourcePos, sourceGender, userId)
      linked        <- linkOrExisting(sourceRow, NewTranslation(targetLanguage, targetText, targetPos, targetGender), userId)
      (targetRow, _) = linked
      _             <- pairInTag(sourceRow.id, tagId, targetRow.id)
    } yield List(sourceRow.id, targetRow.id)).catchAll(_ => ZIO.succeed(Nil))
  }

  /** Creates an unmatched token the reader assigned a language to but never paired with a translation, and tags it — no
    * translation link, unlike [[confirmManualPair]]. A language with genders gets the same article-stripping/noun-
    * gender treatment. Leniently answers no ids on validation failure, the same as [[confirmManualPair]].
    */
  private def confirmStandaloneWord(word: BulkUploadManualWord, tagId: Long, userId: Long): UIO[List[Long]] = {
    val (stripped, gender) = stripArticle(word.text, word.language)
    val text               = LanguageProfile.of(word.language).capitalize(stripped, gender)
    val pos                = if (gender.isDefined) PartOfSpeech.Noun else PartOfSpeech.Other
    (for {
      row <- ensure(word.language, text, pos, gender, userId)
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _   <- repo.tagWord(row.id, tagId, now).orDie
    } yield List(row.id)).catchAll(_ => ZIO.succeed(Nil))
  }

  def bulkUploadConfirm(
    tagId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    acceptedWordIds: List[Long],
    selectedTranslations: List[BulkUploadSelectedTranslation],
    manualPairs: List[BulkUploadManualPair],
    standaloneWords: List[BulkUploadManualWord],
    userId: Long,
  ): IO[BulkUploadFailure, Int] = {
    val selectedByWordId = selectedTranslations.map(sel => sel.wordId -> sel.translationId).toMap
    for {
      _          <- bulkUploadGuard(tagId, userId)
      now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
      accepted   <- ZIO.foreach(acceptedWordIds.distinct) { wordId =>
                      acceptMatch(wordId, selectedByWordId.get(wordId), tagId, sourceLanguage, targetLanguage, now)
                    }
      manual     <- ZIO.foreach(manualPairs.distinct)(confirmManualPair(_, tagId, sourceLanguage, targetLanguage, userId))
      standalone <- ZIO.foreach(standaloneWords.distinct)(confirmStandaloneWord(_, tagId, userId))
    } yield (accepted.flatten ++ manual.flatten ++ standalone.flatten).distinct.size
  }

  // -- Tag export / import -----------------------------------------------------------------------

  private def exportWord(row: WordRow): TagExportWord = {
    val w = toDomain(row)
    TagExportWord(w.language, w.text, w.partOfSpeech, w.gender)
  }

  /** One tag's name, its words, and the translations marked in it — the unit both [[exportTag]] and [[exportOwnedTags]]
    * build. Entries are ordered by the word's own identity so two exports of the same tag are byte-identical.
    */
  private def buildTagExport(tag: TagRow): Task[TagExportTag] = {
    for {
      members     <- repo.wordsInTag(tag.id)
      pairs       <- repo.pairsInTag(tag.id)
      targets     <- repo.findWordsByIds(pairs.map(_.translationWordId).distinct)
      targetById   = targets.map(row => row.id -> row).toMap
      markedByWord = pairs
                       .groupBy(_.wordId)
                       .view
                       .mapValues(_.flatMap(pair => targetById.get(pair.translationWordId)))
                       .toMap
    } yield {
      val entries          = members
        .sortBy(row => (row.language, row.textNorm, row.partOfSpeech, row.gender))
        .map(row => {
          val marked = markedByWord
            .getOrElse(row.id, Nil)
            .sortBy(t => (t.language, t.textNorm, t.partOfSpeech, t.gender))
            .map(exportWord)
          TagExportEntry(exportWord(row), marked)
        })
      val (source, target) = tagLanguages(tag)
      TagExportTag(tag.name, entries, Some(source), Some(target))
    }
  }

  def exportTag(tagId: Long): IO[WordFailure, TagExportFile] = {
    for {
      tag  <- repo.findTagById(tagId).orDie.someOrFail(WordFailure.TagNotFound)
      body <- buildTagExport(tag).orDie
      now  <- Clock.currentTime(TimeUnit.MILLISECONDS)
    } yield TagExportFile(TagExportFile.currentVersion, now, List(body))
  }

  def exportOwnedTags(userId: Long): UIO[TagExportFile] = {
    for {
      rows   <- repo.listTags(userId).orDie
      owned   = rows.collect { case (tag, _, true) => tag }.sortBy(_.nameNorm)
      bodies <- ZIO.foreach(owned)(tag => buildTagExport(tag).orDie)
      now    <- Clock.currentTime(TimeUnit.MILLISECONDS)
    } yield TagExportFile(TagExportFile.currentVersion, now, bodies)
  }

  /** Any [[ensure]]/[[linkOrExisting]] failure during an import is a bad word inside the file — reshaped to this enum's
    * own validation case so the whole request answers 400 with field errors.
    */
  private def importValidation(failure: WordFailure): TagImportFailure = {
    failure match {
      case WordFailure.ValidationError(fieldErrors) => TagImportFailure.ValidationError(fieldErrors)
      case _                                        => TagImportFailure.ValidationError(Map.empty)
    }
  }

  private def importQuota(failure: WordFailure): TagImportFailure = {
    failure match {
      case WordFailure.TagQuotaExceeded(limit)  => TagImportFailure.TagQuotaExceeded(limit)
      case WordFailure.PairQuotaExceeded(limit) => TagImportFailure.PairQuotaExceeded(limit)
      case other                                => importValidation(other)
    }
  }

  /** Shares [[bulkUploadGuard]]'s rate-limit budget: an import, like an upload, can create an unbounded batch of rows
    * from one request.
    */
  private def importGuard(userId: Long): IO[TagImportFailure, Unit] = {
    val rateLimitKey = RateLimitKey.wordUpload(userId)
    for {
      blocked <- limiter.isBlocked(rateLimitKey)
      _       <- ZIO.when(blocked) {
                   SecurityLog.warn(s"Rate limit exceeded on tag import for user $userId") *>
                     ZIO.fail(TagImportFailure.RateLimited)
                 }
      _       <- limiter.recordFailure(rateLimitKey)
    } yield ()
  }

  /** `ensure`'s own rule for which gender it would actually store, mirrored here so the pre-check lookup uses the same
    * key.
    */
  private def importKeptGender(word: TagExportWord): Option[Gender] = {
    if (LanguageProfile.of(word.language).hasGenders && word.partOfSpeech == PartOfSpeech.Noun)
      word.gender
    else
      None
  }

  /** The word row a file entry names, and whether this import had to create it. */
  private def resolveImportWord(word: TagExportWord, userId: Long): IO[TagImportFailure, (WordRow, Boolean)] = {
    val text = word.text.trim
    repo
      .findWord(
        WordLanguage.code(word.language),
        text.toLowerCase,
        PartOfSpeech.code(word.partOfSpeech),
        Gender.toColumn(importKeptGender(word)),
      )
      .orDie
      .flatMap {
        case Some(row) => ZIO.succeed((row, false))
        case None      =>
          ensure(word.language, text, word.partOfSpeech, word.gender, userId)
            .mapError(importValidation)
            .map(row => (row, true))
      }
  }

  private def importMark(
    member: WordRow,
    target: TagExportWord,
    tagId: Long,
    userId: Long,
  ): IO[TagImportFailure, Unit] = {
    val translation = NewTranslation(target.language, target.text.trim, Some(target.partOfSpeech), target.gender)
    (for {
      linked        <- linkOrExisting(member, translation, userId)
      (targetRow, _) = linked
      _             <- pairInTag(member.id, tagId, targetRow.id)
    } yield ()).mapError(importValidation)
  }

  def importTags(
    file: TagExportFile,
    resolutions: Map[String, TagImportChoice],
    userId: Long,
  ): IO[TagImportFailure, TagImportResponse] = {
    val badFile = TagImportFailure.ValidationError(Map("file" -> MessageRef(MessageKeys.wordTagImportInvalidFile)))
    for {
      _            <- importGuard(userId)
      _            <- ZIO.when(
                        file.version < TagExportFile.minReadableVersion || file.version > TagExportFile.currentVersion
                      )(ZIO.fail(badFile))
      ownedRows    <- repo.listTags(userId).orDie
      ownedByNorm   = ownedRows.collect { case (tag, _, true) => tag.nameNorm -> tag }.toMap
      // Decide each file tag: rename target, merge target, or a plain new tag. A clashing name with no entry in
      // `resolutions` is what the caller re-submits past.
      clashes       = file.tags
                        .map(ft => Tag.normalize(ft.name))
                        .filter(norm => ownedByNorm.contains(norm) && !resolutions.contains(norm))
                        .distinct
      _            <- ZIO.when(clashes.nonEmpty) {
                        ZIO.fail(
                          TagImportFailure.NameConflict(
                            file.tags.map(_.name).filter(n => clashes.contains(Tag.normalize(n))).distinct
                          )
                        )
                      }
      plans        <- ZIO.foreach(file.tags)(planFor(_, ownedByNorm, resolutions))
      _            <- rejectCollidingNewNames(plans, ownedByNorm)
      newTags       = plans.count(_.created)
      pairRows      = file.tags.flatMap(_.entries).flatMap(_.marked).size * 2
      ownedTags    <- repo.countTagsOwnedBy(userId).orDie
      ownedPairs   <- repo.countPairsOwnedBy(userId).orDie
      _            <- tagQuota(ownedTags, newTags.toLong).mapError(importQuota)
      _            <- pairQuota(ownedPairs, pairRows.toLong).mapError(importQuota)
      distinctWords = file.tags.flatMap(_.entries).flatMap(entry => entry.word :: entry.marked).distinct
      resolved     <- ZIO.foreach(distinctWords)(word => resolveImportWord(word, userId).map(word -> _)).map(_.toMap)
      now          <- Clock.currentTime(TimeUnit.MILLISECONDS)
      results      <- ZIO.foreach(plans)(plan => applyPlan(plan, resolved, now, userId))
    } yield TagImportResponse(results)
  }

  private def planFor(
    fileTag: TagExportTag,
    ownedByNorm: Map[String, TagRow],
    resolutions: Map[String, TagImportChoice],
  ): IO[TagImportFailure, WordService.ImportPlan] = {
    val norm = Tag.normalize(fileTag.name)
    ownedByNorm.get(norm) match {
      case None           =>
        ZIO.succeed(WordService.ImportPlan.Create(fileTag.name, fileTag))
      case Some(existing) =>
        resolutions.get(norm) match {
          case Some(TagImportChoice.Merge)           => ZIO.succeed(WordService.ImportPlan.Merge(existing, fileTag))
          case Some(TagImportChoice.Rename(newName)) =>
            ZIO
              .fromEither(Validation.validateTagName(newName))
              .mapBoth(
                err => TagImportFailure.ValidationError(Map("name" -> err)),
                _ => WordService.ImportPlan.Create(newName, fileTag),
              )
          case None                                  =>
            // Covered by the `clashes` check above; kept total.
            ZIO.fail(TagImportFailure.NameConflict(List(fileTag.name)))
        }
    }
  }

  /** A rename target (or a plain new tag whose name a *rename* elsewhere also chose) must not land on a name the
    * account already owns, nor on another new tag in the same import.
    */
  private def rejectCollidingNewNames(
    plans: List[WordService.ImportPlan],
    ownedByNorm: Map[String, TagRow],
  ): IO[TagImportFailure, Unit] = {
    val newNames = plans.collect { case WordService.ImportPlan.Create(name, _) => name }
    val norms    = newNames.map(Tag.normalize)
    val ownedHit = newNames.filter(name => ownedByNorm.contains(Tag.normalize(name)))
    val dupHit   = norms.diff(norms.distinct)
    val bad      = (ownedHit ++ newNames.filter(name => dupHit.contains(Tag.normalize(name)))).distinct
    ZIO.when(bad.nonEmpty)(ZIO.fail(TagImportFailure.NameConflict(bad))).unit
  }

  private def applyPlan(
    plan: WordService.ImportPlan,
    resolved: Map[TagExportWord, (WordRow, Boolean)],
    now: Long,
    userId: Long,
  ): IO[TagImportFailure, TagImportResult] = {
    val fileTag = plan.fileTag
    // A version-1 file carried no pair; default it to this deployment's direction.
    val source  = fileTag.sourceLanguage.getOrElse(WordLanguage.De)
    val target  = fileTag.targetLanguage.getOrElse(WordLanguage.Hu)
    for {
      tagRow <- plan match {
                  case WordService.ImportPlan.Merge(existing, _) => ZIO.succeed(existing)
                  case WordService.ImportPlan.Create(name, _)    =>
                    repo
                      .insertTag(
                        userId,
                        name,
                        Tag.normalize(name),
                        now,
                        WordLanguage.code(source),
                        WordLanguage.code(target),
                      )
                      .orDie
                }
      _      <- ZIO.foreachDiscard(fileTag.entries)(entry => {
                  val (memberRow, _) = resolved(entry.word)
                  // Keep the tag's one-language rule even on a hand-edited file: skip an entry, or a mark, in the wrong
                  // language rather than letting it in.
                  ZIO.when(memberRow.language == tagRow.sourceLanguage) {
                    repo.tagWord(memberRow.id, tagRow.id, now).orDie *>
                      ZIO.foreachDiscard(
                        entry.marked.filter(mark => resolved(mark)._1.language == tagRow.targetLanguage)
                      )(mark => importMark(memberRow, mark, tagRow.id, userId))
                  }
                })
    } yield {
      val words   = fileTag.entries.map(_.word).distinct
      val newDict =
        fileTag.entries.flatMap(entry => entry.word :: entry.marked).distinct.count(word => resolved(word)._2)
      TagImportResult(
        tagRow.name,
        created = plan.created,
        wordsAdded = words.size,
        pairsAdded = fileTag.entries.flatMap(_.marked).size,
        newDictionaryWords = newDict,
      )
    }
  }
}
