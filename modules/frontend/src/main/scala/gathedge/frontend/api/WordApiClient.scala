package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.WordEndpoints
import gathedge.shared.domain.{Gender, PartOfSpeech, Tag, TranslationFilter, WordLanguage}
import gathedge.shared.dto.{
  AddTranslationRequest,
  BulkUploadConfirmRequest,
  BulkUploadConfirmResponse,
  BulkUploadManualPair,
  BulkUploadManualWord,
  BulkUploadSelectedTranslation,
  CreateTagRequest,
  CreateTagWithPairsRequest,
  CreateWordRequest,
  NewTranslation,
  PairSelectionResponse,
  RenameTagRequest,
  SetGenderRequest,
  TagExportFile,
  TagImportChoice,
  TagImportRequest,
  TagImportResponse,
  TagResponse,
  WordDetail,
  WordPage,
}

import EndpointClient.{executor, run}

/** The vocabulary's calls, generated from `WordEndpoints` the same way [[ApiClient]] is from `AuthEndpoints`.
  *
  * A separate file only because the resource is a separate one; nothing here knows a path or a method.
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
    tagId: Option[Long] = None,
    mine: Option[Boolean] = None,
    translationFilter: Option[TranslationFilter] = None,
    mainOnly: Option[Boolean] = None,
  ): EventStream[Either[ApiError, WordPage]] = {
    run(
      executor(
        WordEndpoints.list(
          page,
          pageSize,
          sort,
          dir,
          search,
          language.map(WordLanguage.code),
          target.map(WordLanguage.code),
          partOfSpeech.map(PartOfSpeech.code),
          tagId,
          mine,
          translationFilter.map(TranslationFilter.code),
          mainOnly,
        )
      )
    )
  }

  def get(id: Long): EventStream[Either[ApiError, WordDetail]] = {
    run(executor(WordEndpoints.get(id)))
  }

  def create(request: CreateWordRequest): EventStream[Either[ApiError, WordDetail]] = {
    run(executor(WordEndpoints.create(request)))
  }

  def addTranslation(wordId: Long, translation: NewTranslation): EventStream[Either[ApiError, WordDetail]] = {
    run(executor(WordEndpoints.addTranslation(wordId, AddTranslationRequest(translation))))
  }

  /** Fills in the article a noun was imported without. Answers the whole word again, so the caller replaces what it is
    * showing rather than patching the one field.
    */
  def setGender(wordId: Long, gender: Gender): EventStream[Either[ApiError, WordDetail]] = {
    run(executor(WordEndpoints.setGender(wordId, SetGenderRequest(gender))))
  }

  def removeTranslation(wordId: Long, translationId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(WordEndpoints.removeTranslation(wordId, translationId)))
  }

  def listTags: EventStream[Either[ApiError, List[Tag]]] = {
    run(executor(WordEndpoints.listTags(())))
  }

  def createTag(name: String): EventStream[Either[ApiError, TagResponse]] = {
    run(executor(WordEndpoints.createTag(CreateTagRequest(name))))
  }

  /** Creates a tag together with every bilingual pair the tag-creation page assembled, as one request. */
  def createTagWithPairs(request: CreateTagWithPairsRequest): EventStream[Either[ApiError, TagResponse]] = {
    run(executor(WordEndpoints.createTagWithPairs(request)))
  }

  def renameTag(tagId: Long, name: String): EventStream[Either[ApiError, TagResponse]] = {
    run(executor(WordEndpoints.renameTag(tagId, RenameTagRequest(name))))
  }

  def deleteTag(tagId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(WordEndpoints.deleteTag(tagId)))
  }

  /** Seeds a tag of the caller's own from another tag's name, whoever owns it, and copies its word/pair snapshot with
    * it.
    */
  def copyTag(tagId: Long): EventStream[Either[ApiError, TagResponse]] = {
    run(executor(WordEndpoints.copyTag(tagId)))
  }

  /** The whole of one tag as a portable file — for `Download`. Any tag, whoever owns it. */
  def exportTag(tagId: Long): EventStream[Either[ApiError, TagExportFile]] = {
    run(executor(WordEndpoints.exportTag(tagId)))
  }

  /** Every tag the caller owns, in one file. */
  def exportOwnedTags: EventStream[Either[ApiError, TagExportFile]] = {
    run(executor(WordEndpoints.exportOwnedTags(())))
  }

  /** Rebuilds the tags in `file` under the caller's account. `resolutions` decides what to do about each tag whose name
    * the caller already owns (keyed by the normalized name); an empty map means "there are no clashes", which the
    * dialog checks for before it ever calls this.
    */
  def importTags(
    file: TagExportFile,
    resolutions: Map[String, TagImportChoice],
  ): EventStream[Either[ApiError, TagImportResponse]] = {
    run(executor(WordEndpoints.importTags(TagImportRequest(file, resolutions))))
  }

  /** Idempotent, which is what lets the listing's row toggle fire on every click without tracking what is in flight. */
  def tagWord(wordId: Long, tagId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(WordEndpoints.tagWord(wordId, tagId)))
  }

  def untagWord(wordId: Long, tagId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(WordEndpoints.untagWord(wordId, tagId)))
  }

  /** Marks a translation as a practice answer for a word, inside the tag the page is collecting into. Idempotent for
    * the reason [[tagWord]] is, and it files both words under the tag as a side effect.
    */
  def selectPair(
    wordId: Long,
    tagId: Long,
    translationWordId: Long,
  ): EventStream[Either[ApiError, PairSelectionResponse]] = {
    run(executor(WordEndpoints.selectPair(wordId, tagId, translationWordId)))
  }

  def deselectPair(wordId: Long, tagId: Long, translationWordId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(WordEndpoints.deselectPair(wordId, tagId, translationWordId)))
  }

  /** Commits what the reader chose out of a bulk-upload preview — the confirm half only, since the preview itself needs
    * upload-progress reporting `EndpointClient` has no hook for, and speaks to its endpoint directly
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
    run(
      executor(
        WordEndpoints.bulkUploadConfirm(
          tagId,
          BulkUploadConfirmRequest(
            sourceLanguage,
            targetLanguage,
            acceptedWordIds,
            selectedTranslations,
            manualPairs,
            standaloneWords,
          ),
        )
      )
    )
  }
}
