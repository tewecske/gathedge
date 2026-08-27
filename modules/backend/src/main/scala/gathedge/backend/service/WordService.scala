package gathedge.backend.service

import gathedge.backend.config.{AppConfig, QuotaSection}
import gathedge.backend.db.{
  GroupRepository,
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
import gathedge.shared.domain.{Gender, GrammarTag, GroupRef, PartOfSpeech, Tag, TranslationFilter, Word, WordLanguage}
import gathedge.shared.dto.{
  BulkUploadManualPair,
  BulkUploadManualWord,
  BulkUploadMatch,
  BulkUploadPreviewResponse,
  BulkUploadSelectedTranslation,
  BulkUploadSuggestion,
  CreateWordRequest,
  NewTranslation,
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

  /** The account already owns as many tags as `limit` (`AppConfig.quotas.tagsPerUserHard`) allows. Carries the limit
    * itself, since `ApiFailures` mints no signature that takes an `AppConfig` to look it up.
    */
  case TagQuotaExceeded(limit: Int)

  /** The account's tags already carry as many `word_tag_pairs` rows, summed across every tag it owns, as `limit`
    * (`AppConfig.quotas.wordPairsPerUserHard`) allows.
    */
  case PairQuotaExceeded(limit: Int)
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

  def listTags(userId: Long): UIO[List[Tag]]

  /** `TagQuotaExceeded` is the hard half of the tag quota; a write that only crosses the soft threshold succeeds with
    * [[gathedge.shared.dto.TagResponse.warning]] set instead.
    */
  def createTag(name: String, userId: Long): IO[WordFailure, TagResponse]

  /** Creates a tag and every bilingual pair the tag-creation page assembled, as one logical write.
    *
    * `DuplicateTag`/`TagQuotaExceeded`/`PairQuotaExceeded` follow [[createTag]]/[[copyTag]]'s own rules; `NotFound` is
    * a `TagPairWord.Existing` naming no word. Both quotas are checked before anything is written, and every word on
    * either side is resolved (a missing `Existing` id, or a `New` word created) before the tag row itself is inserted,
    * so a request that fails does so having written nothing.
    */
  def createTagWithPairs(name: String, pairs: List[TagPairInput], userId: Long): IO[WordFailure, TagResponse]

  /** `TagNotFound` covers a tag that does not exist or is not the caller's, the same as every other tag-scoped write.
    * `DuplicateTag` is the new name colliding with a *different* tag of the caller's own, compared case-insensitively —
    * renaming a tag to the name it already has is a no-op, not a conflict.
    */
  def renameTag(tagId: Long, name: String, userId: Long): IO[WordFailure, TagResponse]

  def deleteTag(tagId: Long, userId: Long): IO[WordFailure, Unit]

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

  def listTags(userId: Long): URIO[WordService, List[Tag]] =
    ZIO.serviceWithZIO[WordService](_.listTags(userId))

  def createTag(name: String, userId: Long): ZIO[WordService, WordFailure, TagResponse] =
    ZIO.serviceWithZIO[WordService](_.createTag(name, userId))

  def createTagWithPairs(
    name: String,
    pairs: List[TagPairInput],
    userId: Long,
  ): ZIO[WordService, WordFailure, TagResponse] =
    ZIO.serviceWithZIO[WordService](_.createTagWithPairs(name, pairs, userId))

  def renameTag(tagId: Long, name: String, userId: Long): ZIO[WordService, WordFailure, TagResponse] =
    ZIO.serviceWithZIO[WordService](_.renameTag(tagId, name, userId))

  def deleteTag(tagId: Long, userId: Long): ZIO[WordService, WordFailure, Unit] =
    ZIO.serviceWithZIO[WordService](_.deleteTag(tagId, userId))

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

  def bulkUploadPreview(
    tagId: Long,
    content: String,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): ZIO[WordService, BulkUploadFailure, BulkUploadPreviewResponse] =
    ZIO.serviceWithZIO[WordService](_.bulkUploadPreview(tagId, content, sourceLanguage, targetLanguage, userId))

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

  val live: URLayer[WordRepository & GroupRepository & AppConfig & RateLimiter, WordService] = {
    ZLayer.fromFunction((repo: WordRepository, groupRepo: GroupRepository, config: AppConfig, limiter: RateLimiter) =>
      WordServiceLive(repo, groupRepo, config.quotas, limiter)
    )
  }

  /** What a word row a user typed is marked as, against the dictionary's own. */
  val userSource       = "user"
  val dictionarySource = "dictionary"

  /** Origins a translation edge can have. `pivot` is a German–Hungarian pair inferred through a shared English sense
    * rather than asserted anywhere; `form` is a form-to-form pair inferred through its lemmas' own translation (a
    * plural paired with a plural because the singulars translate each other) — both marked so a screen can say so.
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

final case class WordServiceLive(
  repo: WordRepository,
  groupRepo: GroupRepository,
  quotas: QuotaSection,
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
    Tag(row.id, row.name, wordCount, ownedByMe, group, editableByMe)
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

  /** Turns a typed word into the row it identifies, creating it if the dictionary has never heard of it.
    *
    * Gender is only kept for German nouns: an English noun with an article attached would be a second, unfindable copy
    * of the same word.
    */
  private def ensure(
    language: WordLanguage,
    text: String,
    partOfSpeech: PartOfSpeech,
    gender: Option[Gender],
    userId: Long,
  ): IO[WordFailure, WordRow] = {
    val trimmed    = text.trim
    val keptGender = {
      if (language == WordLanguage.De && partOfSpeech == PartOfSpeech.Noun)
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
                 .ensureWord(
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

  def createTag(name: String, userId: Long): IO[WordFailure, TagResponse] = {
    for {
      prepared       <- prepareTagName(name, userId)
      (valid, normal) = prepared
      owned          <- repo.countTagsOwnedBy(userId).orDie
      warning        <- tagQuota(owned, 1)
      now            <- Clock.currentTime(TimeUnit.MILLISECONDS)
      row            <- repo.insertTag(userId, valid, normal, now).orDie
    } yield TagResponse(toTag(row, 0L, ownedByMe = true, editableByMe = true), warning)
  }

  /** [[createTagWithPairs]]'s write: both quotas checked, every word resolved, and only then the tag row and its pairs.
    * A missing `Existing` id or a `New` word that fails validation therefore surfaces before any tag is written.
    */
  def createTagWithPairs(name: String, pairs: List[TagPairInput], userId: Long): IO[WordFailure, TagResponse] = {
    for {
      prepared       <- prepareTagName(name, userId)
      (valid, normal) = prepared
      ownedTags      <- repo.countTagsOwnedBy(userId).orDie
      tagWarning     <- tagQuota(ownedTags, 1)
      ownedPairs     <- repo.countPairsOwnedBy(userId).orDie
      pairWarning    <- pairQuota(ownedPairs, pairs.size * 2)
      resolved       <- ZIO.foreach(pairs)(resolvePair(_, userId))
      now            <- Clock.currentTime(TimeUnit.MILLISECONDS)
      tagRow         <- repo.createTagWithPairs(userId, valid, normal, now, resolved).orDie
      wordCount       = resolved.flatMap { case (s, t) => List(s, t) }.toSet.size
    } yield TagResponse(toTag(tagRow, wordCount.toLong, ownedByMe = true), tagWarning.orElse(pairWarning))
  }

  /** Resolves one pair's two sides to word ids, failing before any write if an `Existing` side names no word. */
  private def resolvePair(pair: TagPairInput, userId: Long): IO[WordFailure, (Long, Long)] = {
    for {
      sourceId <- resolveWord(pair.source, userId)
      targetId <- resolveWord(pair.target, userId)
    } yield (sourceId, targetId)
  }

  private def resolveWord(ref: TagPairWord, userId: Long): IO[WordFailure, Long] = {
    ref match {
      case TagPairWord.Existing(id)                              => repo.findWordById(id).orDie.someOrFail(WordFailure.NotFound).map(_.id)
      case TagPairWord.New(language, text, partOfSpeech, gender) =>
        ensure(language, text, partOfSpeech, gender, userId).map(_.id)
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
      existing       <- requireOwnTag(tagId, userId)
      prepared       <- prepareTagName(name, userId, excludeTagId = Some(tagId))
      (valid, normal) = prepared
      rows           <- repo.updateTag(tagId, userId, valid, normal).orDie
      _              <- ZIO.when(rows == 0L)(ZIO.fail(WordFailure.TagNotFound))
      wordCount      <- repo.countWordsInTag(tagId).orDie
      group          <- resolveGroupRef(existing.groupId)
    } yield TagResponse(Tag(tagId, valid, wordCount, ownedByMe = true, group, editableByMe = true), None)
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
      _   <- requireEditableTag(tagId, userId)
      _   <- repo.findWordById(wordId).orDie.someOrFail(WordFailure.NotFound)
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _   <- repo.tagWord(wordId, tagId, now).orDie
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
      _       <- repo.findWordById(wordId).orDie.someOrFail(WordFailure.NotFound)
      _       <- requireTranslationOf(wordId, translationWordId)
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

  /** Unicode-letter runs, lowercased and deduplicated in the order they first appear, capped at
    * [[WordService.maxBulkUploadTokens]] — what turns an uploaded file's raw text into candidate dictionary words.
    * Apostrophes and hyphens are kept mid-word (`don't`, `mother-in-law`) but never lead a token.
    */
  private val bulkUploadTokenPattern = """\p{L}[\p{L}'’-]*""".r

  /** Same as [[bulkUploadTokenPattern]], but a leading `der`/`die`/`das` immediately before a word is consumed together
    * with it as one token — the merged alternative comes first so `findAllIn` prefers it over matching the article
    * alone. Used only when German is one of the upload's two declared languages (see [[bulkUploadPreview]]), since
    * "die" and "das" are ordinary English/other-language words otherwise.
    */
  private val bulkUploadGermanTokenPattern =
    """(?i)\b(?:der|die|das)\b\s+\p{L}[\p{L}'’-]*|\p{L}[\p{L}'’-]*""".r

  private def tokenize(content: String, mergeGermanArticles: Boolean): List[String] = {
    val pattern = if (mergeGermanArticles) bulkUploadGermanTokenPattern else bulkUploadTokenPattern
    pattern.findAllIn(content).map(_.toLowerCase).toList.distinct.take(WordService.maxBulkUploadTokens)
  }

  /** Splits a token's leading `der`/`die`/`das` off, answering the bare word and the gender it names — what
    * [[matchTokens]] looks the word up by, and what [[confirmManualPair]]/[[confirmStandaloneWord]] create it with. A
    * token with no such prefix (or a lone article with nothing after it) passes through unchanged.
    */
  private def stripArticle(token: String): (String, Option[Gender]) = {
    token.trim.split("\\s+", 2) match {
      case Array(article, rest) if Gender.fromString(article).isDefined => (rest, Gender.fromString(article))
      case _                                                            => (token, None)
    }
  }

  /** German nouns are always capitalized; every other word from an upload stays as typed/lowercased. Applied only at
    * word creation ([[confirmManualPair]]/[[confirmStandaloneWord]]), never at [[matchTokens]]'s lookup key, since
    * `textNorm` is the lowercase form regardless of a word's display casing.
    */
  private def capitalizeGermanNoun(text: String, gender: Option[Gender]): String = {
    if (gender.isDefined) text.capitalize else text
  }

  /** Collapses tokens that share a bare word (see [[stripArticle]]) into one, so a reader who typed both `"Hund"` and
    * `"der Hund"` in the same upload sees one leftover entry, not two. If any variant carried an article, that marks
    * the word a noun: the merged entry is shown articled and capitalized, the same convention [[capitalizeGermanNoun]]
    * applies at word creation. Order-preserving by each group's first occurrence, since [[tokenize]]'s own dedup is.
    */
  private def dedupeGermanVariants(tokens: List[String]): List[String] = {
    tokens
      .map(token => token -> stripArticle(token))
      .groupBy { case (_, (bare, _)) => bare }
      .toList
      .sortBy { case (_, group) => tokens.indexOf(group.head._1) }
      .map { case (bare, group) =>
        group.collectFirst { case (_, (_, Some(gender))) => gender } match {
          case Some(gender) => Gender.article(gender) + " " + bare.capitalize
          case None         => bare
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
  private def bulkUploadGuard(tagId: Long, userId: Long): IO[BulkUploadFailure, Unit] = {
    val rateLimitKey = RateLimitKey.wordUpload(userId)
    for {
      blocked <- limiter.isBlocked(rateLimitKey)
      _       <- ZIO.when(blocked) {
                   SecurityLog.warn(s"Rate limit exceeded on bulk word upload for user $userId") *>
                     ZIO.fail(BulkUploadFailure.RateLimited)
                 }
      _       <- limiter.recordFailure(rateLimitKey)
      _       <- requireEditableTag(tagId, userId).mapError(_ => BulkUploadFailure.TagNotFound)
    } yield ()
  }

  /** The tokens already in the dictionary for `language`, and whatever is left. Looks each token up by its bare word
    * (see [[stripArticle]]) since `textNorm` never carries an article, but keeps the original token — article and all —
    * in the unmatched remainder, so a reader still sees `"der tisch"` rather than a bare `"tisch"`.
    */
  private def matchTokens(language: WordLanguage, tokens: List[String]): UIO[(List[WordRow], List[String])] = {
    val keyed = tokens.map(token => token -> stripArticle(token)._1)
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
      .map(token => token -> stripArticle(token)._1)
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
      mergeGermanArticles            = sourceLanguage == WordLanguage.De || targetLanguage == WordLanguage.De
      tokens                         = tokenize(trimmed, mergeGermanArticles)
      sourceMatched                 <- matchTokens(sourceLanguage, tokens)
      (sourceMatches, remaining1)    = sourceMatched
      targetMatched                 <- matchTokens(targetLanguage, remaining1)
      (targetMatches, remaining2Raw) = targetMatched
      matchedIds                     = (sourceMatches.map(_.id) ++ targetMatches.map(_.id)).toSet
      remaining2                     = dedupeGermanVariants(remaining2Raw)
      sourceSuggested0              <- suggestionsFor(sourceLanguage, remaining2)
      sourceSuggested                = sourceSuggested0.view
                                         .mapValues(_.filterNot { case (row, _) => matchedIds.contains(row.id) })
                                         .filter { case (_, cs) => cs.nonEmpty }
                                         .toMap
      remaining3                     = remaining2.filterNot(sourceSuggested.contains)
      targetSuggested0              <- suggestionsFor(targetLanguage, remaining3)
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
      buildMatch                     = { (row: WordRow, translationMap: Map[Long, List[TranslationOption]]) =>
        BulkUploadMatch(toDomain(row), translationMap.getOrElse(row.id, Nil), anyTranslationIds.contains(row.id))
      }
      matched                        = {
        collapseHomonyms(sourceMatches, sourceTranslations).map(row => buildMatch(row, sourceTranslations)) ++
          collapseHomonyms(targetMatches, targetTranslations).map(row => buildMatch(row, targetTranslations))
      }
      suggestions                    = {
        sourceSuggested.toList.flatMap { case (token, candidates) =>
          candidates.map { case (row, distance) =>
            BulkUploadSuggestion(token, buildMatch(row, sourceSuggestionTranslations), distance)
          }
        } ++
          targetSuggested.toList.flatMap { case (token, candidates) =>
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
    * [[ensure]]/[[linkOrExisting]]/[[pairInTag]] exactly as [[create]] does for a single typed word. Whichever side is
    * German has its leading article stripped (see [[stripArticle]]) and created as a noun carrying that gender; the
    * other side is created exactly as typed. Leniently answers no ids for a pair either side of which fails validation,
    * the same way a bad token in the old single-shot upload was dropped rather than failing the batch.
    */
  private def confirmManualPair(
    pair: BulkUploadManualPair,
    tagId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    userId: Long,
  ): UIO[List[Long]] = {
    val (sourceStripped, sourceGender) =
      if (sourceLanguage == WordLanguage.De) stripArticle(pair.sourceText) else (pair.sourceText, None)
    val (targetStripped, targetGender) =
      if (targetLanguage == WordLanguage.De) stripArticle(pair.targetText) else (pair.targetText, None)
    val sourceText                     = capitalizeGermanNoun(sourceStripped, sourceGender)
    val targetText                     = capitalizeGermanNoun(targetStripped, targetGender)
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
    * translation link, unlike [[confirmManualPair]]. German gets the same article-stripping/noun-gender treatment.
    * Leniently answers no ids on validation failure, the same as [[confirmManualPair]].
    */
  private def confirmStandaloneWord(word: BulkUploadManualWord, tagId: Long, userId: Long): UIO[List[Long]] = {
    val (stripped, gender) = if (word.language == WordLanguage.De) stripArticle(word.text) else (word.text, None)
    val text               = capitalizeGermanNoun(stripped, gender)
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
}
