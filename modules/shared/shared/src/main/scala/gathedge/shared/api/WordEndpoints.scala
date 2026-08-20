package gathedge.shared.api

import gathedge.shared.domain.Tag
import gathedge.shared.dto.{
  AddTranslationRequest,
  BulkUploadConfirmRequest,
  BulkUploadConfirmResponse,
  BulkUploadPreviewRequest,
  BulkUploadPreviewResponse,
  CreateTagRequest,
  CreateWordRequest,
  PairSelectionResponse,
  TagResponse,
  WordDetail,
  WordPage,
}
import zio.http.{Method, Status}
import zio.http.codec.{HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, outFailure, withCodecError}
import ApiSchemas.given

/** The vocabulary: browsing the dictionary, tagging words, and adding what it does not have.
  *
  * Two of these are **public**, which no other resource in the application is: [[list]] and [[get]] answer without a
  * session, because the whole design of the feature is that a visitor can search the dictionary before deciding whether
  * to keep anything. They consequently declare no 401 — there is no session for the `authenticated` aspect to reject —
  * and are guarded by `RouteSupport.optionalUser` instead, which hands the handler an `Option[User]` and leaves
  * `tagIds` empty when there is nobody to have tagged anything.
  *
  * Everything that writes is ordinary: guarded by `authenticated`, and so declaring 401. A visitor with no session
  * reaches them by minting a guest first ([[AuthEndpoints.createGuest]]), which is a session like any other.
  */
object WordEndpoints {

  private val wordId        = PathCodec.long("id")
  private val tagId         = PathCodec.long("tagId")
  private val translationId = PathCodec.long("translationId")

  /** A `words.id`, and deliberately not the same thing as [[translationId]] above, which is a `word_translations.id` —
    * an edge somebody recorded. A practice answer names the word itself, because the answer belongs to the reader's tag
    * while the edge belongs to whoever typed it. Two similar-looking paths, two different keys.
    */
  private val translationWordId = PathCodec.long("translationWordId")

  private val noContent = HttpCodec.status(Status.NoContent)

  // Optional rather than defaulted, for the reason recorded on `AdminEndpoints`: a defaulted codec writes the default
  // into the OpenAPI document as though the caller had to send it, while `dto.Paging` is where the defaults live.
  private val pageQuery     = HttpCodec.query[Int]("page").optional
  private val pageSizeQuery = HttpCodec.query[Int]("pageSize").optional
  private val sortQuery     = HttpCodec.query[String]("sort").optional
  private val dirQuery      = HttpCodec.query[String]("dir").optional
  private val searchQuery   = HttpCodec.query[String]("q").optional
  private val langQuery     = HttpCodec.query[String]("lang").optional
  private val targetQuery   = HttpCodec.query[String]("target").optional
  private val posQuery      = HttpCodec.query[String]("pos").optional
  private val tagQuery      = HttpCodec.query[Long]("tag").optional
  private val mineQuery     = HttpCodec.query[Boolean]("mine").optional
  private val trQuery       = HttpCodec.query[String]("tr").optional

  /** The browse-and-tag listing, paged and counted by the database.
    *
    * `lang` narrows to one study language and `target` picks which language the rendered translations are in; both are
    * plain strings rather than a codec over `WordLanguage`, so an unrecognised one falls back to the default the way an
    * unrecognised `sort` does, rather than failing a request a stale bookmark produced. `tr` narrows to words carrying
    * a translation — `target` for `TranslationFilter.HasTarget`, any language for `HasAny` — the same lenient-string
    * treatment, defaulting to `All` for anything unrecognised.
    *
    * The only declared failure is the 400 `withCodecError` answers for a query parameter that does not decode — `page`
    * or `tag` given as prose. Nothing else can fail: a filter that matches nothing is an empty page, not an error.
    */
  val list = {
    Endpoint(Method.GET / "api" / "words")
      .query(pageQuery)
      .query(pageSizeQuery)
      .query(sortQuery)
      .query(dirQuery)
      .query(searchQuery)
      .query(langQuery)
      .query(targetQuery)
      .query(posQuery)
      .query(tagQuery)
      .query(mineQuery)
      .query(trQuery)
      .withCodecError
      .out[WordPage]
      .outFailure(failure.badRequest)
  }

  /** One word with every translation anybody has recorded for it, and the reader's own tags on it. Public, like
    * [[list]]; a caller with no session simply has no tags.
    */
  val get = {
    Endpoint(Method.GET / "api" / "words" / wordId).withCodecError
      .out[WordDetail]
      .outErrors(failure.badRequest, failure.notFound)
  }

  /** Adds a word somebody typed — or finds the one already there.
    *
    * Deliberately **not** "create or 409": a word that exists is answered as it stands, with everyone's translations on
    * it, and whatever the request adds is layered on top. Answering 409 would make the common case (two learners adding
    * the same word) look like an error, when it is the case the shared dictionary exists to serve.
    */
  val create = {
    Endpoint(Method.POST / "api" / "words")
      .in[CreateWordRequest]
      .withCodecError
      .out[WordDetail](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** Adds a translation of the caller's own. 409 is their *own* duplicate — the same pair from somebody else is not a
    * conflict, since a translation is per-account and additive.
    */
  val addTranslation = {
    Endpoint(Method.POST / "api" / "words" / wordId / "translations")
      .in[AddTranslationRequest]
      .withCodecError
      .out[WordDetail]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Removes one of the caller's own translations. A dictionary edge belongs to nobody and cannot be removed here;
    * asking to is a 404 rather than a 403, since from the caller's side there is no such edge of theirs.
    *
    * The 204 is `.outCodec(HttpCodec.status(...))` rather than `.out[Unit](Status.NoContent)`, for the reason recorded
    * on [[AdminEndpoints.deleteUser]].
    */
  val removeTranslation = {
    Endpoint(Method.DELETE / "api" / "words" / wordId / "translations" / translationId).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  /** The reader's tags, with how many words each holds — what the tag bar is built from. */
  val listTags = {
    Endpoint(Method.GET / "api" / "tags").out[List[Tag]].outFailure(failure.unauthorized)
  }

  /** 409 covers two things a caller cannot tell apart from the status alone — a name the account already has, compared
    * case-insensitively, and the account already owning as many tags as `AppConfig.quotas` allows — so `error.key`
    * (`words.tagExists` vs `words.tagQuotaExceeded`) is what a form branches on. 400 covers a blank name, one over the
    * column's width, and one of the names the practice screen reserves for itself. The answer carries a warning instead
    * of failing when the write only crossed the quota's *soft* threshold.
    */
  val createTag = {
    Endpoint(Method.POST / "api" / "tags")
      .in[CreateTagRequest]
      .withCodecError
      .out[TagResponse](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized, failure.conflict)
  }

  val deleteTag = {
    Endpoint(Method.DELETE / "api" / "tags" / tagId).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  /** Seeds a tag of the caller's own from any tag's name, including one they do not own — the only write in this
    * resource that takes somebody else's id and succeeds. It is a *snapshot* copy: every word the source tag carries
    * and every practice pair marked inside it are copied into the new tag as one unit of work, and the two are
    * independent from that moment on — a later change to either tag leaves the other exactly as it was.
    *
    * 404 is a source tag that does not exist. 409 covers three things a caller cannot tell apart from the status alone
    * — the caller already having a tag by that name, the copy's one new tag pushing them past `AppConfig.quotas`' tag
    * limit, or its copied pairs pushing them past the pair limit — distinguished the same way [[createTag]]'s is, by
    * `error.key`. Both limits are checked before anything is written, so a copy that would cross either *hard*
    * threshold writes nothing at all; crossing only a *soft* one still succeeds, with a warning on the answer.
    */
  val copyTag = {
    Endpoint(Method.POST / "api" / "tags" / tagId / "copy").withCodecError
      .out[TagResponse](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Puts a tag on a word — the one-click action the listing is built around, which is why it is idempotent: clicking a
    * row that is already tagged is not a conflict, it is nothing to do.
    */
  val tagWord = {
    Endpoint(Method.PUT / "api" / "words" / wordId / "tags" / tagId).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  val untagWord = {
    Endpoint(Method.DELETE / "api" / "words" / wordId / "tags" / tagId).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  /** Marks one of the word's translations as an answer the practice screen should check against, inside one of the
    * caller's tags — the chip on the listing's translations column.
    *
    * Idempotent like [[tagWord]], and it puts *both* words under the tag as a side effect, since a pair whose answer is
    * not itself collected is a question with a missing half. Several translations may be marked for the same word and
    * tag — a word usually has more than one sense worth learning — which is why the translation is a path segment
    * rather than a field on the tag link.
    *
    * The tag is in the path but never in the address bar: which tag a click files into is page-local browser state, so
    * it is the client that decides, and the server is never asked what is selected "for the current tag".
    *
    * 404 answers three things at once — no such word, no such translation of it, and a tag that is not the caller's —
    * because which of them it was is not something an account may learn by trying. 409 is the account already owning as
    * many `word_tag_pairs` rows, summed across every tag it holds, as `AppConfig.quotas` allows; marking a pair already
    * there is idempotent and never counts against it, since nothing new is written. Answers `Ok` rather than the
    * `NoContent` every other idempotent toggle here does, because the body may carry a warning when the write only
    * crossed the quota's *soft* threshold — a 204 must never carry one (RFC 9110 §8.6).
    */
  val selectPair = {
    Endpoint(
      Method.PUT / "api" / "words" / wordId / "tags" / tagId / "translations" / translationWordId
    ).withCodecError
      .out[PairSelectionResponse]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Unmarks it. Both directions of the pair go; the two words stay under the tag, since taking a word out of a
    * vocabulary is what the row's tick is for.
    */
  val deselectPair = {
    Endpoint(
      Method.DELETE / "api" / "words" / wordId / "tags" / tagId / "translations" / translationWordId
    ).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  /** Scans an uploaded file's free text for words already in the dictionary, in each of the two languages named —
    * `sourceLanguage` first, then whatever is left against `targetLanguage` — and answers every match, with its known
    * translations into the other language, plus every token that matched neither. '''Writes nothing''': this is the
    * reader's chance to review before [[bulkUploadConfirm]] commits any of it.
    *
    * 404 is the tag: whoever's it is, or whether it exists at all, is not something a caller may learn by trying — the
    * same rule every other write in this file follows, even though this call writes nothing itself. 429 is this
    * endpoint's own budget (`RateLimitKey.wordUpload`), shared with [[bulkUploadConfirm]]: unlike every other call
    * here, a single one can scan up to `WordService.maxBulkUploadTokens` tokens at once.
    */
  val bulkUploadPreview = {
    Endpoint(Method.POST / "api" / "words" / "tags" / tagId / "bulk-upload" / "preview")
      .in[BulkUploadPreviewRequest]
      .withCodecError
      .out[BulkUploadPreviewResponse]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.tooManyRequests)
  }

  /** Commits what the reader chose out of a [[bulkUploadPreview]]: tags every accepted matched word (marking its known
    * translations as practice pairs), and for every manually linked pair, creates whichever side the dictionary does
    * not have yet, links them as a translation, and tags and marks both — into one of the caller's own tags. One batch
    * write rather than "ensure and attach" per word, since a caller confirming a review expects one outcome for the
    * whole of it.
    *
    * 404 and 429 follow [[bulkUploadPreview]]'s own rules exactly, sharing its rate-limit budget.
    */
  val bulkUploadConfirm = {
    Endpoint(Method.POST / "api" / "words" / "tags" / tagId / "bulk-upload" / "confirm")
      .in[BulkUploadConfirmRequest]
      .withCodecError
      .out[BulkUploadConfirmResponse]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.tooManyRequests)
  }

  /** For `DocsRoutes`, which needs every description as one heterogeneous collection. */
  val all: List[Endpoint[?, ?, ?, ?, ?]] = {
    List(
      list,
      get,
      create,
      addTranslation,
      removeTranslation,
      listTags,
      createTag,
      deleteTag,
      copyTag,
      tagWord,
      untagWord,
      selectPair,
      deselectPair,
      bulkUploadPreview,
      bulkUploadConfirm,
    )
  }

  /** The two that answer without a session. `DocsRoutes` marks every other operation as needing the session cookie, and
    * `OpenApiSpec` pins both halves of that split.
    */
  val public: List[Endpoint[?, ?, ?, ?, ?]] = List(list, get)
}
