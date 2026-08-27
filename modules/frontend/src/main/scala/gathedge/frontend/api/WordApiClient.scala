package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.domain.{PartOfSpeech, Tag, TranslationFilter, WordLanguage}
import gathedge.shared.dto.{
  AddTranslationRequest,
  BulkUploadConfirmRequest,
  BulkUploadConfirmResponse,
  BulkUploadManualPair,
  BulkUploadManualWord,
  BulkUploadSelectedTranslation,
  CreateTagRequest,
  CreateWordRequest,
  NewTranslation,
  PairSelectionResponse,
  RenameTagRequest,
  TagResponse,
  WordDetail,
  WordPage,
}
import zio.json._

import HttpClient.query

/** The vocabulary's calls. A separate file only because the resource is a separate one; the shared `WordEndpoints`
  * description stays the backend's and the OpenAPI document's source of truth, pinned by `ApiPathParitySpec`.
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
    HttpClient.get[WordPage](
      "/api/words" + query(
        "page"     -> page,
        "pageSize" -> pageSize,
        "sort"     -> sort,
        "dir"      -> dir,
        "q"        -> search,
        "lang"     -> language.map(WordLanguage.code),
        "target"   -> target.map(WordLanguage.code),
        "pos"      -> partOfSpeech.map(PartOfSpeech.code),
        "tag"      -> tagId,
        "mine"     -> mine,
        "tr"       -> translationFilter.map(TranslationFilter.code),
        "main"     -> mainOnly,
      )
    )
  }

  def get(id: Long): EventStream[Either[ApiError, WordDetail]] = {
    HttpClient.get[WordDetail](s"/api/words/$id")
  }

  def create(request: CreateWordRequest): EventStream[Either[ApiError, WordDetail]] = {
    HttpClient.post[WordDetail]("/api/words", Some(request.toJson))
  }

  def addTranslation(wordId: Long, translation: NewTranslation): EventStream[Either[ApiError, WordDetail]] = {
    HttpClient.post[WordDetail](s"/api/words/$wordId/translations", Some(AddTranslationRequest(translation).toJson))
  }

  def removeTranslation(wordId: Long, translationId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/words/$wordId/translations/$translationId")
  }

  def listTags: EventStream[Either[ApiError, List[Tag]]] = {
    HttpClient.get[List[Tag]]("/api/tags")
  }

  def createTag(name: String): EventStream[Either[ApiError, TagResponse]] = {
    HttpClient.post[TagResponse]("/api/tags", Some(CreateTagRequest(name).toJson))
  }

  def renameTag(tagId: Long, name: String): EventStream[Either[ApiError, TagResponse]] = {
    HttpClient.put[TagResponse](s"/api/tags/$tagId", Some(RenameTagRequest(name).toJson))
  }

  def deleteTag(tagId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/tags/$tagId")
  }

  /** Seeds a tag of the caller's own from another tag's name, whoever owns it, and copies its word/pair snapshot with
    * it.
    */
  def copyTag(tagId: Long): EventStream[Either[ApiError, TagResponse]] = {
    HttpClient.post[TagResponse](s"/api/tags/$tagId/copy")
  }

  /** Idempotent, which is what lets the listing's row toggle fire on every click without tracking what is in flight. */
  def tagWord(wordId: Long, tagId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.PUT, s"/api/words/$wordId/tags/$tagId")
  }

  def untagWord(wordId: Long, tagId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/words/$wordId/tags/$tagId")
  }

  /** Marks a translation as a practice answer for a word, inside the tag the page is collecting into. Idempotent for
    * the reason [[tagWord]] is, and it files both words under the tag as a side effect.
    */
  def selectPair(
    wordId: Long,
    tagId: Long,
    translationWordId: Long,
  ): EventStream[Either[ApiError, PairSelectionResponse]] = {
    HttpClient.put[PairSelectionResponse](s"/api/words/$wordId/tags/$tagId/translations/$translationWordId")
  }

  def deselectPair(wordId: Long, tagId: Long, translationWordId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/words/$wordId/tags/$tagId/translations/$translationWordId")
  }

  /** Commits what the reader chose out of a bulk-upload preview — the confirm half only, since the preview itself needs
    * upload-progress reporting `HttpClient` has no hook for, and speaks to its endpoint directly (`BulkUploadDialog`).
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
    HttpClient.post[BulkUploadConfirmResponse](
      s"/api/words/tags/$tagId/bulk-upload/confirm",
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
