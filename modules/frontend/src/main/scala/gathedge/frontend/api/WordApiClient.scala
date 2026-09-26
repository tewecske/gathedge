package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.frontend.listing.TagEntryQuery
import gathedge.shared.api.WordPaths
import gathedge.shared.domain.{Gender, PartOfSpeech, Tag, TagEntryFilter, TagScope, TranslationFilter, WordLanguage}
import gathedge.shared.dto.{
  AddTranslationRequest,
  BulkImportRequest,
  BulkImportResponse,
  BulkUploadConfirmRequest,
  BulkUploadConfirmResponse,
  BulkDeletePairsRequest,
  BulkDeleteWordsRequest,
  BulkUploadManualPair,
  BulkUploadManualWord,
  BulkUploadSelectedTranslation,
  ColumnLanguageCheckRequest,
  ColumnLanguageCheckResponse,
  ColumnSample,
  CreateTagRequest,
  LanguageCheckRequest,
  LanguageCheckResponse,
  TabularImportRequest,
  TabularImportResponse,
  TabularRow,
  CreateTagWithPairsRequest,
  CreateWordRequest,
  NewTranslation,
  PairRef,
  PairSelectionResponse,
  RenameTagRequest,
  SetGenderRequest,
  SetTagLanguagesRequest,
  TagEntry,
  TagEntryPage,
  TagEntryEditRequest,
  TagEntryInput,
  TagEntryResponse,
  TagExportFile,
  TagImportChoice,
  TagImportRequest,
  TagImportResponse,
  TagPage,
  TagPairInput,
  TagPairWord,
  TagResponse,
  WordDetail,
  WordPage,
}
import zio.json._

import HttpClient.query

/** The vocabulary's calls, over [[HttpClient]] the same way [[ApiClient]] is.
  *
  * A separate file only because the resource is a separate one; nothing here knows anything but its own path and
  * method.
  *
  * The two reads answer for a caller with no session as well — they are the pair the server guards with `optionalUser`
  * — so a page may issue them before anybody has signed in and get words back with no tags marked.
  */
object WordApiClient {

  def list(
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
    search: Option[String] = None,
    language: Option[WordLanguage] = None,
    target: Option[WordLanguage] = None,
    partOfSpeech: Option[PartOfSpeech] = None,
    mine: Option[Boolean] = None,
    translationFilter: Option[TranslationFilter] = None,
    mainOnly: Option[Boolean] = None,
  ): EventStream[Either[ApiError, WordPage]] = {
    HttpClient.call[WordPage](
      WordPaths
        .list()
        .withQuery(
          query(
            "page"     -> page,
            "pageSize" -> pageSize,
            "sort"     -> sort,
            "dir"      -> dir,
            "q"        -> search,
            "lang"     -> language.map(WordLanguage.code),
            "target"   -> target.map(WordLanguage.code),
            "pos"      -> partOfSpeech.map(PartOfSpeech.code),
            "mine"     -> mine,
            "tr"       -> translationFilter.map(TranslationFilter.code),
            "main"     -> mainOnly,
          )
        )
    )
  }

  def get(id: Long): EventStream[Either[ApiError, WordDetail]] = {
    HttpClient.call[WordDetail](WordPaths.get(id))
  }

  def create(request: CreateWordRequest): EventStream[Either[ApiError, WordDetail]] = {
    HttpClient.call[WordDetail](WordPaths.create(), Some(request.toJson))
  }

  def addTranslation(wordId: Long, translation: NewTranslation): EventStream[Either[ApiError, WordDetail]] = {
    HttpClient.call[WordDetail](WordPaths.addTranslation(wordId), Some(AddTranslationRequest(translation).toJson))
  }

  /** Fills in the article a noun was imported without. Answers the whole word again, so the caller replaces what it is
    * showing rather than patching the one field.
    */
  def setGender(wordId: Long, gender: Gender): EventStream[Either[ApiError, WordDetail]] = {
    HttpClient.call[WordDetail](WordPaths.setGender(wordId), Some(SetGenderRequest(gender).toJson))
  }

  def removeTranslation(wordId: Long, translationId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(WordPaths.removeTranslation(wordId, translationId))
  }

  def listTags: EventStream[Either[ApiError, List[Tag]]] = {
    HttpClient.call[List[Tag]](WordPaths.listTags())
  }

  /** One wordlist, by id — the fine-grained counterpart of [[listTags]] for a caller who already knows which tag it
    * wants, such as the wordlist detail page.
    */
  def getTag(tagId: Long): EventStream[Either[ApiError, Tag]] = {
    HttpClient.call[Tag](WordPaths.getTag(tagId))
  }

  /** The catalog's own paged/sorted/filtered listing — `GET /api/tags/page`, [[TagsPage]]'s own call. [[listTags]]
    * above stays as it is for every dropdown and collect bar, which still want the whole unpaged table.
    */
  def listTagsPage(
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
    search: Option[String] = None,
    scope: Option[TagScope] = None,
  ): EventStream[Either[ApiError, TagPage]] = {
    HttpClient.call[TagPage](
      WordPaths
        .listTagsPage()
        .withQuery(
          query(
            "page"     -> page,
            "pageSize" -> pageSize,
            "sort"     -> sort,
            "dir"      -> dir,
            "q"        -> search,
            "scope"    -> scope.map(TagScope.code),
          )
        )
    )
  }

  def createTag(
    name: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
  ): EventStream[Either[ApiError, TagResponse]] = {
    HttpClient
      .call[TagResponse](WordPaths.createTag(), Some(CreateTagRequest(name, sourceLanguage, targetLanguage).toJson))
  }

  /** Creates a tag together with every bilingual pair the tag-creation page assembled, as one request. */
  def createTagWithPairs(request: CreateTagWithPairsRequest): EventStream[Either[ApiError, TagResponse]] = {
    HttpClient.call[TagResponse](WordPaths.createTagWithPairs(), Some(request.toJson))
  }

  def renameTag(tagId: Long, name: String): EventStream[Either[ApiError, TagResponse]] = {
    HttpClient.call[TagResponse](WordPaths.renameTag(tagId), Some(RenameTagRequest(name).toJson))
  }

  /** Sets a tag's language pair — the editor's language selects, usable only before the tag has a practice pair. */
  def setTagLanguages(
    tagId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
  ): EventStream[Either[ApiError, TagResponse]] = {
    HttpClient.call[TagResponse](
      WordPaths.setTagLanguages(tagId),
      Some(SetTagLanguagesRequest(sourceLanguage, targetLanguage).toJson),
    )
  }

  def deleteTag(tagId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(WordPaths.deleteTag(tagId))
  }

  /** Seeds a tag of the caller's own from another tag's name, whoever owns it, and copies its word/pair snapshot with
    * it.
    */
  def copyTag(tagId: Long): EventStream[Either[ApiError, TagResponse]] = {
    HttpClient.call[TagResponse](WordPaths.copyTag(tagId))
  }

  /** The whole of one tag as a portable file — for `Download`. Any tag, whoever owns it. */
  def exportTag(tagId: Long): EventStream[Either[ApiError, TagExportFile]] = {
    HttpClient.call[TagExportFile](WordPaths.exportTag(tagId))
  }

  /** Every tag the caller owns, in one file. */
  def exportOwnedTags: EventStream[Either[ApiError, TagExportFile]] = {
    HttpClient.call[TagExportFile](WordPaths.exportOwnedTags())
  }

  /** Rebuilds the tags in `file` under the caller's account. `resolutions` decides what to do about each tag whose name
    * the caller already owns (keyed by the normalized name); an empty map means "there are no clashes", which the
    * dialog checks for before it ever calls this.
    */
  def importTags(
    file: TagExportFile,
    resolutions: Map[String, TagImportChoice],
  ): EventStream[Either[ApiError, TagImportResponse]] = {
    HttpClient.call[TagImportResponse](WordPaths.importTags(), Some(TagImportRequest(file, resolutions).toJson))
  }

  /** Idempotent, which is what lets the listing's row toggle fire on every click without tracking what is in flight. */
  def tagWord(wordId: Long, tagId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(WordPaths.tagWord(wordId, tagId))
  }

  def untagWord(wordId: Long, tagId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(WordPaths.untagWord(wordId, tagId))
  }

  /** Marks a translation as a practice answer for a word, inside the tag the page is collecting into. Idempotent for
    * the reason [[tagWord]] is, and it files both words under the tag as a side effect.
    */
  def selectPair(
    wordId: Long,
    tagId: Long,
    translationWordId: Long,
  ): EventStream[Either[ApiError, PairSelectionResponse]] = {
    HttpClient.call[PairSelectionResponse](WordPaths.selectPair(wordId, tagId, translationWordId))
  }

  def deselectPair(wordId: Long, tagId: Long, translationWordId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(WordPaths.deselectPair(wordId, tagId, translationWordId))
  }

  /** The unified tag editor's rows, in the order they were added. */
  def tagEntries(tagId: Long): EventStream[Either[ApiError, List[TagEntry]]] = {
    HttpClient.call[List[TagEntry]](WordPaths.tagEntries(tagId))
  }

  /** One page of them, narrowed by the editor's own chips — `TagEditorPage`'s own call. [[tagEntries]] above stays as
    * the unpaged read of a whole wordlist, the same split [[listTags]]/[[listTagsPage]] draw.
    */
  def tagEntriesPage(tagId: Long, query: TagEntryQuery): EventStream[Either[ApiError, TagEntryPage]] = {
    val matchParam = Option(TagEntryFilter.codes(query.buckets)).filter(_.nonEmpty)
    HttpClient.call[TagEntryPage](
      WordPaths
        .tagEntriesPage(tagId)
        .withQuery(
          HttpClient.query(
            "page"     -> Some(query.page),
            "pageSize" -> Some(query.pageSize),
            "match"    -> matchParam,
            "mine"     -> Option.when(query.importedByMe)(true),
            "unique"   -> Option.when(query.uniqueToTag)(true),
          )
        )
    )
  }

  /** Adds one row to a wordlist, saved at once: a pair or one word alone, with its part of speech, notes and form-of
    * links. Either word may be one to create (`TagPairWord.New`).
    */
  def addEntry(tagId: Long, entry: TagEntryInput): EventStream[Either[ApiError, TagEntryResponse]] = {
    HttpClient.call[TagEntryResponse](WordPaths.addEntry(tagId), Some(entry.toJson))
  }

  /** Replaces one editor row in place with `entry`. `oldTargetWordId` is `None` for a row that had no answer. */
  def editEntry(
    tagId: Long,
    oldSourceWordId: Long,
    oldTargetWordId: Option[Long],
    entry: TagEntryInput,
  ): EventStream[Either[ApiError, TagEntryResponse]] = {
    HttpClient.call[TagEntryResponse](
      WordPaths.editEntry(tagId),
      Some(TagEntryEditRequest(oldSourceWordId, oldTargetWordId, entry).toJson),
    )
  }

  /** The form types a main word of this language and part of speech can have, commonest first. */
  def formRelations(language: WordLanguage, partOfSpeech: PartOfSpeech): EventStream[Either[ApiError, List[String]]] = {
    HttpClient.call[List[String]](
      WordPaths
        .formRelations()
        .withQuery(
          HttpClient.query("lang" -> Some(WordLanguage.code(language)), "pos" -> Some(PartOfSpeech.code(partOfSpeech)))
        )
    )
  }

  /** Removes one editor row. With `targetWordId`, only that one pair goes and the source word keeps its other marked
    * translations; without it, the source word and every pair naming it go. Idempotent.
    */
  def deletePair(
    tagId: Long,
    sourceWordId: Long,
    targetWordId: Option[Long] = None,
  ): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(
      WordPaths.deletePair(tagId, sourceWordId).withQuery(HttpClient.query("targetWordId" -> targetWordId))
    )
  }

  /** Removes a batch of editor rows in one request — the multiselect's bulk delete. Each `(sourceWordId, targetWordId)`
    * follows [[deletePair]]'s rule: a `None` target removes the whole source entry.
    */
  def deletePairsBulk(
    tagId: Long,
    pairs: List[(Long, Option[Long])],
  ): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(
      WordPaths.bulkDeletePairs(tagId),
      Some(BulkDeletePairsRequest(pairs.map { case (source, target) => PairRef(source, target) }).toJson),
    )
  }

  /** Deletes words from the dictionary outright — the multiselect's "delete words". The server keeps only the ids the
    * caller minted that carry no other tag; anything else is ignored, so the whole selection is safe to send.
    */
  def deleteWords(tagId: Long, wordIds: List[Long]): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(WordPaths.bulkDeleteWords(tagId), Some(BulkDeleteWordsRequest(wordIds).toJson))
  }

  /** Tokenizes free text and writes every token into the tag in text order, then answers the counts. */
  def bulkImport(
    tagId: Long,
    content: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
  ): EventStream[Either[ApiError, BulkImportResponse]] = {
    HttpClient.call[BulkImportResponse](
      WordPaths.bulkImport(tagId),
      Some(BulkImportRequest(content, sourceLanguage, targetLanguage).toJson),
    )
  }

  /** Sanity-checks the pasted text's language against the tag's pair before an import — the server samples it and
    * answers how many sampled words it recognised.
    */
  def checkLanguage(
    content: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
  ): EventStream[Either[ApiError, LanguageCheckResponse]] = {
    HttpClient.call[LanguageCheckResponse](
      WordPaths.languageCheck(),
      Some(LanguageCheckRequest(content, sourceLanguage, targetLanguage).toJson),
    )
  }

  /** Writes a mapped table into the tag, one asserted pair per row, and answers the counts. */
  def tabularImport(
    tagId: Long,
    rows: List[TabularRow],
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
  ): EventStream[Either[ApiError, TabularImportResponse]] = {
    HttpClient.call[TabularImportResponse](
      WordPaths.tabularImport(tagId),
      Some(TabularImportRequest(rows, sourceLanguage, targetLanguage).toJson),
    )
  }

  /** Asks which language each column of a delimited paste looks like, so the mapping step can suggest the roles instead
    * of leaving the reader to assign every column by hand.
    */
  def checkColumnLanguages(
    columns: List[ColumnSample]
  ): EventStream[Either[ApiError, ColumnLanguageCheckResponse]] = {
    HttpClient.call[ColumnLanguageCheckResponse](
      WordPaths.columnLanguageCheck(),
      Some(ColumnLanguageCheckRequest(columns).toJson),
    )
  }

  /** Commits what the reader chose out of a bulk-upload preview — the confirm half only, since the preview itself needs
    * upload-progress reporting [[HttpClient]] has no hook for, and speaks to its endpoint directly
    * (`BulkUploadDialog`).
    */
  def bulkUploadConfirm(
    tagId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    acceptedWordIds: List[Long],
    selectedTranslations: List[BulkUploadSelectedTranslation],
    manualPairs: List[BulkUploadManualPair],
    standaloneWords: List[BulkUploadManualWord],
  ): EventStream[Either[ApiError, BulkUploadConfirmResponse]] = {
    HttpClient.call[BulkUploadConfirmResponse](
      WordPaths.bulkUploadConfirm(tagId),
      Some(
        BulkUploadConfirmRequest(
          sourceLanguage,
          targetLanguage,
          acceptedWordIds,
          selectedTranslations,
          manualPairs,
          standaloneWords,
        ).toJson
      ),
    )
  }
}
