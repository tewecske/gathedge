package gathedge.backend.http

import gathedge.backend.service.{AuthService, WordService}
import gathedge.shared.api.WordEndpoints
import gathedge.shared.domain.{PartOfSpeech, TranslationFilter, User, WordLanguage}
import gathedge.shared.dto.{
  AddTranslationRequest,
  BulkUploadConfirmRequest,
  BulkUploadConfirmResponse,
  BulkUploadPreviewRequest,
  CreateTagRequest,
  CreateTagWithPairsRequest,
  CreateWordRequest,
  Paging,
  RenameTagRequest,
  SetGenderRequest,
  SortDirection,
  TagImportRequest,
}
import zio.*
import zio.http.*

/** The vocabulary: the dictionary anybody may browse, and the tags and translations an account owns.
  *
  * '''This is the only route file with a public half that still knows who the caller is.''' The two reads are wrapped
  * in `optionalUser` rather than `authenticated`, so a visitor with no session gets the same words and no tags, while a
  * reader who has one gets their own tags marked on the rows. Everything that writes is guarded normally — a visitor
  * reaches those by minting a guest first (`POST /api/guest`), which is an ordinary session.
  *
  * The aspects are on the `Routes` values, never on an individual `handler`: several handlers here take path
  * parameters, and attaching a context-providing aspect to one of those compiles and then throws `ClassCastException`
  * at request time, because the handler is handed a bare `Request` where it expects the `(param, Request)` tuple.
  */
object WordRoutes {

  /** The reader, when there is one. Supplied by `optionalUser`. */
  private def reader: URIO[Option[User], Option[Long]] = ZIO.service[Option[User]].map(_.map(_.id))

  /** The signed-in account. Supplied by `authenticated`. */
  private def userId: URIO[User, Long] = ZIO.service[User].map(_.id)

  /** An empty `q=` is the search box after it has been cleared, which is not a filter. */
  private def searchTerm(requested: Option[String]): Option[String] = {
    requested.map(_.trim).filter(_.nonEmpty)
  }

  /** Language codes are read leniently, exactly like `sort`: an unrecognised one falls back rather than failing the
    * request. These arrive from the address bar, where a stale bookmark is likelier than an attack, and the answer to
    * "de-AT" is the German list rather than a 400.
    */
  private def languageOf(requested: Option[String]): Option[WordLanguage] = {
    requested.flatMap(WordLanguage.fromString)
  }

  /** The listing's eleven query parameters, as the endpoint hands them over.
    *
    * Taken as one tuple rather than eleven arguments because zio-http's `handler` only unrolls functions up to arity
    * seven; past that the single-argument constructor is what applies, and the tuple has to be named and destructured.
    */
  private type ListQuery = (
    Option[Int],
    Option[Int],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[Long],
    Option[Boolean],
    Option[String],
    Option[Boolean],
  )

  private val listRoute = {
    WordEndpoints.list.implementHandler(
      handler { (input: ListQuery) =>
        val (page, pageSize, sort, dir, q, lang, target, pos, tag, mine, tr, main) = input
        reader.flatMap { who =>
          WordService.list(
            page = Paging.boundedPage(page),
            pageSize = Paging.boundedPageSize(pageSize),
            language = languageOf(lang),
            search = searchTerm(q),
            partOfSpeech = pos.flatMap(PartOfSpeech.fromString),
            tagId = tag,
            mine = mine.getOrElse(false),
            // Something has to be translated *into*; the browser always names it, and English is the hub the
            // dictionary is built around when nothing else was said.
            target = languageOf(target).getOrElse(WordLanguage.En),
            translationFilter = tr.flatMap(TranslationFilter.fromString).getOrElse(TranslationFilter.All),
            mainOnly = main.getOrElse(false),
            sort = sort,
            descending = SortDirection.isDescending(dir),
            reader = who,
          )
        }
      }
    )
  }

  private val getRoute = {
    WordEndpoints.get.implementHandler(
      handler { (id: Long) =>
        reader.flatMap(who => WordService.detail(id, who).mapError(ApiFailures.word))
      }
    )
  }

  private val createRoute = {
    WordEndpoints.create.implementHandler(
      handler { (body: CreateWordRequest) =>
        userId.flatMap(id => WordService.create(body, id).mapError(ApiFailures.word))
      }
    )
  }

  private val addTranslationRoute = {
    WordEndpoints.addTranslation.implementHandler(
      handler { (wordId: Long, body: AddTranslationRequest) =>
        userId.flatMap(id => WordService.addTranslation(wordId, body.translation, id).mapError(ApiFailures.word))
      }
    )
  }

  private val setGenderRoute = {
    WordEndpoints.setGender.implementHandler(
      handler { (wordId: Long, body: SetGenderRequest) =>
        userId.flatMap(id => WordService.setGender(wordId, body.gender, id).mapError(ApiFailures.word))
      }
    )
  }

  private val removeTranslationRoute = {
    WordEndpoints.removeTranslation.implementHandler(
      handler { (wordId: Long, translationId: Long) =>
        userId.flatMap(id => WordService.removeTranslation(wordId, translationId, id).mapError(ApiFailures.word))
      }
    )
  }

  private val listTagsRoute = {
    WordEndpoints.listTags.implementHandler(handler((_: Unit) => userId.flatMap(WordService.listTags)))
  }

  private val createTagRoute = {
    WordEndpoints.createTag.implementHandler(
      handler { (body: CreateTagRequest) =>
        userId.flatMap(id => WordService.createTag(body.name, id).mapError(ApiFailures.word))
      }
    )
  }

  private val createTagWithPairsRoute = {
    WordEndpoints.createTagWithPairs.implementHandler(
      handler { (body: CreateTagWithPairsRequest) =>
        userId.flatMap(id => WordService.createTagWithPairs(body.name, body.pairs, id).mapError(ApiFailures.word))
      }
    )
  }

  private val renameTagRoute = {
    WordEndpoints.renameTag.implementHandler(
      handler { (tagId: Long, body: RenameTagRequest) =>
        userId.flatMap(id => WordService.renameTag(tagId, body.name, id).mapError(ApiFailures.word))
      }
    )
  }

  private val deleteTagRoute = {
    WordEndpoints.deleteTag.implementHandler(
      handler((tagId: Long) => userId.flatMap(id => WordService.deleteTag(tagId, id).mapError(ApiFailures.word)))
    )
  }

  private val copyTagRoute = {
    WordEndpoints.copyTag.implementHandler(
      handler((tagId: Long) => userId.flatMap(id => WordService.copyTag(tagId, id).mapError(ApiFailures.word)))
    )
  }

  private val exportTagRoute = {
    WordEndpoints.exportTag.implementHandler(
      handler((tagId: Long) => userId.flatMap(_ => WordService.exportTag(tagId).mapError(ApiFailures.word)))
    )
  }

  private val exportOwnedTagsRoute = {
    WordEndpoints.exportOwnedTags.implementHandler(
      handler((_: Unit) => userId.flatMap(WordService.exportOwnedTags))
    )
  }

  private val importTagsRoute = {
    WordEndpoints.importTags.implementHandler(
      handler { (body: TagImportRequest) =>
        userId.flatMap(id => WordService.importTags(body.file, body.resolutions, id).mapError(ApiFailures.tagImport))
      }
    )
  }

  private val tagWordRoute = {
    WordEndpoints.tagWord.implementHandler(
      handler { (wordId: Long, tagId: Long) =>
        userId.flatMap(id => WordService.tagWord(wordId, tagId, id).mapError(ApiFailures.word))
      }
    )
  }

  private val untagWordRoute = {
    WordEndpoints.untagWord.implementHandler(
      handler { (wordId: Long, tagId: Long) =>
        userId.flatMap(id => WordService.untagWord(wordId, tagId, id).mapError(ApiFailures.word))
      }
    )
  }

  private val selectPairRoute = {
    WordEndpoints.selectPair.implementHandler(
      handler { (wordId: Long, tagId: Long, translationWordId: Long) =>
        userId.flatMap(id => WordService.selectPair(wordId, tagId, translationWordId, id).mapError(ApiFailures.word))
      }
    )
  }

  private val deselectPairRoute = {
    WordEndpoints.deselectPair.implementHandler(
      handler { (wordId: Long, tagId: Long, translationWordId: Long) =>
        userId.flatMap(id => WordService.deselectPair(wordId, tagId, translationWordId, id).mapError(ApiFailures.word))
      }
    )
  }

  private val bulkUploadPreviewRoute = {
    WordEndpoints.bulkUploadPreview.implementHandler(
      handler { (tagId: Long, body: BulkUploadPreviewRequest) =>
        userId.flatMap(id => {
          WordService
            .bulkUploadPreview(tagId, body.content, body.sourceLanguage, body.targetLanguage, id, body.fuzzyMatching)
            .mapError(ApiFailures.bulkUpload)
        })
      }
    )
  }

  private val bulkUploadConfirmRoute = {
    WordEndpoints.bulkUploadConfirm.implementHandler(
      handler { (tagId: Long, body: BulkUploadConfirmRequest) =>
        userId.flatMap(id => {
          WordService
            .bulkUploadConfirm(
              tagId,
              body.sourceLanguage,
              body.targetLanguage,
              body.acceptedWordIds,
              body.selectedTranslations,
              body.manualPairs,
              body.standaloneWords,
              id,
            )
            .map(BulkUploadConfirmResponse.apply)
            .mapError(ApiFailures.bulkUpload)
        })
      }
    )
  }

  /** Two `Routes` values because they are guarded differently, `++`'d and then given the CSRF check together — the
    * arrangement `AuthRoutes` uses for the same reason.
    */
  private val publicRoutes = Routes(listRoute, getRoute) @@ RouteSupport.optionalUser

  private val sessionRoutes = {
    Routes(
      createRoute,
      addTranslationRoute,
      setGenderRoute,
      removeTranslationRoute,
      listTagsRoute,
      createTagRoute,
      renameTagRoute,
      deleteTagRoute,
      copyTagRoute,
      exportTagRoute,
      exportOwnedTagsRoute,
      importTagsRoute,
      createTagWithPairsRoute,
      tagWordRoute,
      untagWordRoute,
      selectPairRoute,
      deselectPairRoute,
      bulkUploadPreviewRoute,
      bulkUploadConfirmRoute,
    ) @@ RouteSupport.authenticated
  }

  val routes: Routes[AuthService & WordService, Response] = {
    (publicRoutes ++ sessionRoutes) @@ RouteSupport.csrf
  }
}
