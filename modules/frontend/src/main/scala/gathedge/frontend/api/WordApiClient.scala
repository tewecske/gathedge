package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.WordEndpoints
import gathedge.shared.domain.{PartOfSpeech, Tag, WordLanguage}
import gathedge.shared.dto.{
  AddTranslationRequest,
  CreateTagRequest,
  CreateWordRequest,
  NewTranslation,
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

  def removeTranslation(wordId: Long, translationId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(WordEndpoints.removeTranslation(wordId, translationId)))
  }

  def listTags: EventStream[Either[ApiError, List[Tag]]] = {
    run(executor(WordEndpoints.listTags(())))
  }

  def createTag(name: String): EventStream[Either[ApiError, Tag]] = {
    run(executor(WordEndpoints.createTag(CreateTagRequest(name))))
  }

  def deleteTag(tagId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(WordEndpoints.deleteTag(tagId)))
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
  def selectPair(wordId: Long, tagId: Long, translationWordId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(WordEndpoints.selectPair(wordId, tagId, translationWordId)))
  }

  def deselectPair(wordId: Long, tagId: Long, translationWordId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(WordEndpoints.deselectPair(wordId, tagId, translationWordId)))
  }
}
