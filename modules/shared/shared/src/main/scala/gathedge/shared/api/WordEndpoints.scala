package gathedge.shared.api

import gathedge.shared.domain.Tag
import gathedge.shared.dto.{
  AddTranslationRequest,
  BulkImportRequest,
  BulkImportResponse,
  BulkUploadConfirmRequest,
  BulkUploadConfirmResponse,
  BulkUploadPreviewRequest,
  BulkUploadPreviewResponse,
  CreateTagRequest,
  CreateTagWithPairsRequest,
  CreateWordRequest,
  LanguageCheckRequest,
  LanguageCheckResponse,
  PairSelectionResponse,
  RenameTagRequest,
  ReplacePairRequest,
  SetGenderRequest,
  TagEntry,
  TagEntryResponse,
  TagExportFile,
  TagImportRequest,
  TagImportResponse,
  TagPairInput,
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
  private val sourceWordId  = PathCodec.long("sourceWordId")

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
  private val mainQuery     = HttpCodec.query[Boolean]("main").optional

  private val targetWordIdQuery = HttpCodec.query[Long]("targetWordId").optional

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
      .query(mainQuery)
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
    *
    * 404 covers two things a caller cannot tell apart from the status alone: `mainWordId` naming no word, and a
    * `tagIds` entry naming a tag that is not the caller's — the same rule every other tag-scoped write in this file
    * follows.
    */
  val create = {
    Endpoint(Method.POST / "api" / "words")
      .in[CreateWordRequest]
      .withCodecError
      .out[WordDetail](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
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

  /** Fills in the article a noun was imported without — the one thing about an existing word that may be changed.
    *
    * Only a blank may be filled: a word whose gender is already set answers 400, because `words` rows belong to nobody
    * and one reader must not rewrite an article everybody else is learning from. 400 also covers a gender the word's
    * language does not have, and a word that is not a noun.
    *
    * 409 is the identity collision. Gender is part of `UNIQUE (language, text_norm, part_of_speech, gender)`, so
    * setting one on `Haus` when `das Haus` is already its own row would be a duplicate. Nothing is merged; the caller
    * is told the other word exists.
    */
  val setGender = {
    Endpoint(Method.PUT / "api" / "words" / wordId / "gender")
      .in[SetGenderRequest]
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

  /** Creates a tag together with every bilingual pair the reader assembled on the tag-creation page, as one write.
    *
    * Differs from [[createTag]] (which makes an empty tag) and from calling [[selectPair]] once per pair in three ways
    * a caller cannot see from the status alone: it checks the tag quota and the pair quota *together, before any
    * write*, so a request that would cross a hard threshold writes nothing rather than leaving a half-built tag; it
    * accepts a pair whose either side is a brand-new word (`TagPairWord.New`), creating that word on the fly; and it
    * stores a pair with no requirement that a `word_translations` edge already link the two — the reader may pair any
    * two words they chose. 404 is a `TagPairWord.Existing` naming no word; 409 covers the same two cases
    * [[createTag]]'s does (a name the account already has, case-insensitively, and the tag limit) plus the pair limit,
    * distinguished the same way by `error.key`.
    */
  val createTagWithPairs = {
    Endpoint(Method.POST / "api" / "tags" / "with-pairs")
      .in[CreateTagWithPairsRequest]
      .withCodecError
      .out[TagResponse](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Renames one of the caller's own tags. Follows [[createTag]]'s own rules for the name itself — 400 for blank, over
    * width, or reserved; 409 for a name the caller already has on a *different* tag of theirs, compared case-
    * insensitively — and 404 for a tag that is not theirs, the same as every other tag-scoped write here.
    */
  val renameTag = {
    Endpoint(Method.PUT / "api" / "tags" / tagId)
      .in[RenameTagRequest]
      .withCodecError
      .out[TagResponse]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
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
  /** The whole of one tag as a portable JSON file — its name, every word it holds, and every practice pair marked in it
    * — so it can be rebuilt in another instance of the application ([[importTags]]), the cross-database counterpart of
    * [[copyTag]]. Any tag is exportable, whoever owns it, since tag contents are visible to everyone; 404 is a tag id
    * that names nothing.
    */
  val exportTag = {
    Endpoint(Method.GET / "api" / "tags" / tagId / "export").withCodecError
      .out[TagExportFile]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  /** Every tag the caller owns, in one [[TagExportFile]] — an account-wide backup. `{tagId}` in [[exportTag]] is a
    * `Long`, so the literal `export` segment here never collides with it.
    */
  val exportOwnedTags = {
    Endpoint(Method.GET / "api" / "tags" / "export")
      .out[TagExportFile]
      .outFailure(failure.unauthorized)
  }

  /** Rebuilds the tags in a [[TagExportFile]] under the caller's account: words are matched by identity and created in
    * this dictionary when missing, memberships and practice pairs are written, and both quotas are checked up front the
    * way [[copyTag]] checks them. 409 is either a quota hard limit (`error.key` `words.tagQuotaExceeded` /
    * `words.pairQuotaExceeded`) or a tag whose name the caller already owns and has not yet said what to do about
    * (`words.tagImportConflict`); the client re-submits with a per-name choice in `resolutions`. 429 is
    * [[bulkUploadPreview]]'s rate-limit budget, shared because one call can create many rows.
    */
  val importTags = {
    Endpoint(Method.POST / "api" / "tags" / "import")
      .in[TagImportRequest]
      .withCodecError
      .out[TagImportResponse]
      .outErrors(failure.badRequest, failure.unauthorized, failure.conflict, failure.tooManyRequests)
  }

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

  /** The unified tag editor's rows, in the order they were added (a bulk import keeps the pasted text's order): each
    * source word, the answer translation marked for it in this tag if any, and the two import provenance flags. Any
    * signed-in caller may read a tag's rows — tag contents are world-visible — so 404 is only an id that names nothing.
    */
  val tagEntries = {
    Endpoint(Method.GET / "api" / "tags" / tagId / "entries").withCodecError
      .out[List[TagEntry]]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  /** Adds one bilingual pair to a tag, saved immediately — the unified editor's add-row action. Either side may be a
    * brand-new word (`TagPairWord.New`), created on the fly, exactly as [[createTagWithPairs]] allows. 404 is a tag
    * that is not the caller's (or their group's) or a `TagPairWord.Existing` naming no word; 409 is the pair quota's
    * hard limit, with a soft-threshold crossing carried as a warning on the answer instead.
    */
  val addPair = {
    Endpoint(Method.POST / "api" / "tags" / tagId / "pairs")
      .in[TagPairInput]
      .withCodecError
      .out[TagEntryResponse](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Replaces one editor row's pair in place — the row's inline edit. The body names the row (its old source word id,
    * and its old answer word id when it had one) and the pair it should become. The pair's `exact` flag is cleared: a
    * hand-edited pair is no longer an exact import match. Same 404/409 rules as [[addPair]].
    */
  val replacePair = {
    Endpoint(Method.PUT / "api" / "tags" / tagId / "pairs")
      .in[ReplacePairRequest]
      .withCodecError
      .out[TagEntryResponse]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Removes one editor row. `targetWordId` names the row's answer half: with it, only that one practice pair goes
    * (both directions), and each side's membership is dropped only when the tag no longer pairs it — so a word with
    * several marked translations keeps its other rows. Without it — an answer-less row — the source word and every pair
    * naming it go, the same effect as [[untagWord]]. Idempotent either way.
    */
  val deletePair = {
    Endpoint(Method.DELETE / "api" / "tags" / tagId / "pairs" / sourceWordId)
      .query(targetWordIdQuery)
      .withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  /** Tokenizes free text, matches it against the dictionary in both declared languages, and '''writes the result
    * straight into the tag''' in text order: an exact pair (a word and its dictionary translation both present) is
    * marked as a practice pair with an "exact" flag; every other token becomes an answer-less row — a dictionary word
    * tagged as-is, or a new word created in `sourceLanguage`. Every membership it writes carries an "imported" flag.
    * The reader reviews the result on the editor with its filters — there is no preview round-trip.
    *
    * 404 is the tag, whoever's it is. 429 is `RateLimitKey.wordUpload`'s budget: one call can scan up to
    * `WordService.maxBulkUploadTokens` tokens.
    */
  val bulkImport = {
    Endpoint(Method.POST / "api" / "tags" / tagId / "bulk-import")
      .in[BulkImportRequest]
      .withCodecError
      .out[BulkImportResponse]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.tooManyRequests)
  }

  /** Samples a fixed number of distinct words from `content` and looks each one up in `sourceLanguage`'s and
    * `targetLanguage`'s dictionaries. Answers how many were sampled, how many matched neither, and whether that miss
    * count is inside the tolerance — the editor calls this before a [[bulkImport]] and warns the reader when
    * `acceptable` is false, so a German paste into an English→Hungarian tag is caught server-side rather than guessed
    * at in the browser. '''Writes nothing'''. The sample size and the tolerated miss count are server config (the
    * `language-check` config section). No 404: it names no tag.
    */
  val languageCheck = {
    Endpoint(Method.POST / "api" / "words" / "language-check")
      .in[LanguageCheckRequest]
      .withCodecError
      .out[LanguageCheckResponse]
      .outErrors(failure.badRequest, failure.unauthorized)
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
    * not have yet, links them as a translation, and tags and marks both — into `tagId`, which the caller must own or
    * belong to the group of. One batch write rather than "ensure and attach" per word, since a caller confirming a
    * review expects one outcome for the whole of it.
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
      setGender,
      removeTranslation,
      listTags,
      createTag,
      createTagWithPairs,
      renameTag,
      deleteTag,
      copyTag,
      exportTag,
      exportOwnedTags,
      importTags,
      tagWord,
      untagWord,
      selectPair,
      deselectPair,
      tagEntries,
      addPair,
      replacePair,
      deletePair,
      bulkImport,
      languageCheck,
      bulkUploadPreview,
      bulkUploadConfirm,
    )
  }

  /** The two that answer without a session. `DocsRoutes` marks every other operation as needing the session cookie, and
    * `OpenApiSpec` pins both halves of that split.
    */
  val public: List[Endpoint[?, ?, ?, ?, ?]] = List(list, get)
}
