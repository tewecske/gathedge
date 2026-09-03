package gathedge.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import gathedge.shared.domain.{LanguageProfile, TranslationFilter, WordLanguage}
import gathedge.shared.dto.WordSort
import zio.*

import javax.sql.DataSource

/** The vocabulary's five tables — `words`, `word_translations`, `tags`, `word_tags`, `word_tag_pairs` — in one
  * repository.
  *
  * They are together rather than in four files because they are written together: adding a word with a translation has
  * to insert into two of them atomically, and a transaction does not compose across repositories (see
  * [[QuillRepository]]). Reads are batched by word id for the same reason a listing does not fetch per row.
  *
  * '''Nothing here logs a word or a search term.''' A search is a fragment of somebody's vocabulary and a tag name is
  * whatever they typed, so both fall under the rule [[QuillRepository.logged]] states for addresses: log ids and
  * counts.
  */
trait WordRepository {

  /** The one word matching a natural key, if it is there. The key is the whole of a word's identity: same spelling,
    * same part of speech and same gender is the same word, whoever typed it.
    */
  def findWord(language: String, textNorm: String, partOfSpeech: String, gender: String): Task[Option[WordRow]]

  def findWordById(id: Long): Task[Option[WordRow]]

  /** Looks the word up and inserts it only if it is absent, answering the row either way. Safe against two callers
    * racing on the same new word: the loser's insert trips the unique index and re-reads.
    */
  def ensureWord(row: WordRow): Task[WordRow]

  /** Fills in the gender of a word that has none, answering how many rows that changed.
    *
    * The `gender = ''` half of the filter is the guard, not a formality: it is what makes "fill a blank" atomic, so two
    * callers racing on the same imported noun cannot both win and the loser is told by the row count rather than by a
    * later read. Changing a gender that is already set is not offered at all — `words` rows belong to nobody.
    *
    * Answers `0` for a word that is not there, and for one somebody else has just given a gender to. A unique-index
    * violation is possible and is the caller's to interpret: gender is part of a word's identity, so this is an
    * identity change that can collide with a row already holding it.
    */
  def setWordGender(id: Long, gender: String): Task[Long]

  /** One page of the listing. `search` is matched as a **prefix** of the accent-folded text, which is what an
    * autocomplete needs and what `idx_words_search` answers.
    *
    * @param tagId
    *   narrows to words carrying one tag; @param taggedBy narrows to words the account has tagged at all.
    * @param translationFilter
    *   narrows to words carrying a translation, either into `targetLanguage` specifically or into any language at all;
    *   `targetLanguage` is ignored under [[TranslationFilter.All]]/[[TranslationFilter.HasAny]].
    */
  def listPage(
    offset: Int,
    limit: Int,
    language: Option[String],
    search: Option[String],
    partOfSpeech: Option[String],
    tagId: Option[Long],
    taggedBy: Option[Long],
    translationFilter: TranslationFilter,
    targetLanguage: String,
    mainOnly: Boolean,
    sort: Option[String],
    descending: Boolean,
  ): Task[List[WordRow]]

  /** What [[listPage]] would return across every page — the number the page buttons are counted off. */
  def countMatching(
    language: Option[String],
    search: Option[String],
    partOfSpeech: Option[String],
    tagId: Option[Long],
    taggedBy: Option[Long],
    translationFilter: TranslationFilter,
    targetLanguage: String,
    mainOnly: Boolean,
  ): Task[Long]

  /** Every translation of those words into one language, as the edge and the word it points at. One query for a whole
    * page.
    */
  def translationsOf(wordIds: List[Long], targetLanguage: String): Task[List[(WordTranslationRow, WordRow)]]

  /** Every translation of one word, in any language, for the detail screen. */
  def allTranslationsOf(wordId: Long): Task[List[(WordTranslationRow, WordRow)]]

  /** Which of those words carry a translation into any language at all — the wider check
    * [[gathedge.shared.domain.TranslationFilter.HasAny]] and `BulkUploadMatch.hasAnyTranslation` both ask, batched the
    * same shape [[translationsOf]] is.
    */
  def wordIdsWithAnyTranslation(wordIds: List[Long]): Task[Set[Long]]

  def findTranslationById(id: Long): Task[Option[WordTranslationRow]]

  /** Records a translation in **both** directions as one unit of work, and answers the forward edge. */
  def insertTranslationPair(
    sourceWordId: Long,
    targetWordId: Long,
    origin: String,
    createdBy: Option[Long],
    createdAt: Long,
  ): Task[WordTranslationRow]

  /** Removes an edge and its mirror, but only if `ownerId` typed it. Returns rows affected. */
  def deleteTranslationPair(id: Long, ownerId: Long): Task[Long]

  def findTranslation(sourceWordId: Long, targetWordId: Long, createdBy: Option[Long]): Task[Option[WordTranslationRow]]

  /** Every tag in the system, not only `viewerId`'s own — tags are globally visible for filtering and copying, even
    * though only the owner may attach or detach words with one. Each row carries the count every account's writes
    * contribute (a tag's word count is not per-viewer) and whether `viewerId` is the one who made it, which is what the
    * two tag dropdowns mark and sort on.
    */
  def listTags(viewerId: Long): Task[List[(TagRow, Long, Boolean)]]
  def findTag(userId: Long, nameNorm: String): Task[Option[TagRow]]
  def findTagById(id: Long): Task[Option[TagRow]]
  def insertTag(userId: Long, name: String, nameNorm: String, createdAt: Long): Task[TagRow]

  /** One unit of work: seeds `name`/`nameNorm` as a new tag owned by `userId` and writes every resolved pair into it —
    * the tag row and its `word_tags`/`word_tag_pairs` rows in a single transaction, so the pair rows' foreign keys see
    * the new tag and a failure writes nothing. `pairs` are already-resolved `(wordId, translationWordId)` pairs.
    */
  def createTagWithPairs(
    userId: Long,
    name: String,
    nameNorm: String,
    createdAt: Long,
    pairs: List[(Long, Long)],
  ): Task[TagRow]

  /** Renames `id`, scoped to `userId` the same way [[deleteTag]] is — a row count of `0` means either the tag does not
    * exist or it is not the caller's, which `WordService.renameTag` cannot tell apart and does not need to.
    */
  def updateTag(id: Long, userId: Long, name: String, nameNorm: String): Task[Long]

  def deleteTag(id: Long, userId: Long): Task[Long]

  /** Attaches or detaches a tag's group — the only write to `tags.group_id`, called by `GroupService.attachTag`/
    * `.detachTag`. Not scoped to a caller's `userId` here; that check belongs to the service, which already knows
    * whether the caller may act on this particular tag and group.
    */
  def setTagGroup(tagId: Long, groupId: Option[Long]): Task[Long]

  /** How many words carry `tagId` — the count [[listTags]] computes for every tag at once, resolved here for the one
    * tag `WordService.renameTag` just wrote, so its answer carries the same number a fresh [[listTags]] would.
    */
  def countWordsInTag(tagId: Long): Task[Long]

  /** How many tags `userId` owns — one half of `WordService.checkQuota`'s tag limit. */
  def countTagsOwnedBy(userId: Long): Task[Long]

  /** How many `word_tag_pairs` rows `userId` owns, summed across every tag they own — the other half, for the pair
    * limit. Counts rows, not marks: [[pairTranslation]] writes one per direction, so a single chip click contributes
    * two.
    */
  def countPairsOwnedBy(userId: Long): Task[Long]

  /** How many `word_tag_pairs` rows one tag carries, whoever owns it — what [[WordService.copyTag]] checks the pair
    * quota against before copying them, since that is exactly how many new rows the copy would add.
    */
  def countPairsInTag(tagId: Long): Task[Long]

  /** Seeds `name`/`nameNorm` as a new tag owned by `userId`, and copies `sourceId`'s word memberships and practice
    * pairs into it — a snapshot, not a live link: the copy and the source are independent from the moment this returns.
    * Answers the new tag along with how many words and how many pair rows it copied, which is what
    * `WordService.copyTag` checks the caller's quotas against before deciding whether to call this at all.
    *
    * One unit of work for the reason [[untagWord]] is: three tables belong to this repository, and a copy that inserted
    * the tag but not what it holds would leave an orphan behind if a later statement in it failed.
    */
  def copyTag(sourceId: Long, userId: Long, name: String, nameNorm: String, createdAt: Long): Task[(TagRow, Long, Long)]

  /** Idempotent: tagging a word that already carries the tag is nothing to do, not a conflict. That is what lets the
    * listing's one-click toggle be safe to double-click.
    */
  def tagWord(wordId: Long, tagId: Long, createdAt: Long): Task[Unit]

  /** Removes the tag from the word — and with it every practice pair naming that word inside that tag, in both
    * directions. A pair whose word is no longer in the tag is a question with a missing half, and it is invisible to
    * the listing, so nothing else would ever offer to clear it.
    */
  def untagWord(wordId: Long, tagId: Long): Task[Long]

  /** Which of those words the account has tagged, and with which of its tags — its own, plus any tag attached to a
    * group the account belongs to, since [[gathedge.backend.service.WordService.requireEditableTag]] lets a fellow
    * member write to those too. One query per page.
    */
  def tagsFor(userId: Long, wordIds: List[Long]): Task[List[WordTagRow]]

  /** The account's tags on one word, for the detail screen — its own, plus any group tag it may edit, the same widening
    * [[tagsFor]] applies.
    */
  def tagsOfWord(userId: Long, wordId: Long): Task[List[TagRow]]

  /** Every word that carries `tagId`, whoever tagged it — the membership half of a tag export. Order is not
    * significant; the exporter sorts.
    */
  def wordsInTag(tagId: Long): Task[List[WordRow]]

  /** Every `word_tag_pairs` row of one tag — the marked-translation half of a tag export. The list form of
    * [[countPairsInTag]].
    */
  def pairsInTag(tagId: Long): Task[List[WordTagPairRow]]

  /** The word rows for a set of ids, in one query — used to resolve a tag's practice-pair targets for export. */
  def findWordsByIds(ids: List[Long]): Task[List[WordRow]]

  // -- Practice pairs ---------------------------------------------------------------------------

  /** Marks `translationWordId` as a practice answer for `wordId` inside `tagId`, as one unit of work: both words gain
    * the tag, and the pair is recorded in both directions.
    *
    * The memberships are part of the same write rather than the caller's job, because a pair whose answer is not itself
    * in the vocabulary is a question the reader could never have collected the answer to. Idempotent in every part, so
    * a double-click is nothing to do rather than a conflict.
    */
  def pairTranslation(wordId: Long, tagId: Long, translationWordId: Long, createdAt: Long): Task[Unit]

  /** Removes the pair and its mirror. The two words keep the tag — taking a word out of a vocabulary is what
    * [[untagWord]] is for. Returns rows affected.
    */
  def unpairTranslation(wordId: Long, tagId: Long, translationWordId: Long): Task[Long]

  /** Which translations the account has marked, across a whole page of words — under its own tags and under any group
    * tag it may edit, the same widening [[tagsFor]] applies. One query per page, the same shape as [[tagsFor]].
    */
  def pairsFor(userId: Long, wordIds: List[Long]): Task[List[WordTagPairRow]]

  // -- The unified tag editor ----------------------------------------------------------------------

  /** [[tagWord]] with the membership marked `imported`; promotes an existing hand-added row's flag. */
  def importWord(wordId: Long, tagId: Long, createdAt: Long): Task[Unit]

  /** [[pairTranslation]] with the pair marked `exact` and both memberships `imported`; promotes existing rows. */
  def importPair(wordId: Long, tagId: Long, translationWordId: Long, createdAt: Long): Task[Unit]

  /** One tag's memberships oldest-first (`word_tags.id` ascending), carrying the `imported` flag. */
  def tagMemberships(tagId: Long): Task[List[WordTagRow]]

  /** Deletes one editor row whole — the source word, its pairs in the tag both ways, and any partner word left with
    * nothing referencing it (unless that partner was `imported`). One transaction.
    */
  def removeEntry(tagId: Long, sourceWordId: Long): Task[Unit]

  /** Deletes one `(source, target)` practice pair from a tag, both directions, then drops whichever side the tag no
    * longer pairs and did not `import` — so a source word with other marked translations keeps its remaining rows. One
    * transaction. Idempotent.
    */
  def removePair(tagId: Long, sourceWordId: Long, targetWordId: Long): Task[Unit]

  /** Fills a tag's language pair, but only while it has none — the pair locks after the first row. Rows affected. */
  def setTagLanguages(tagId: Long, sourceLanguage: String, targetLanguage: String): Task[Long]

  /** The editor's rows: each source word, its marked answer if any, and the two import flags — ordered by
    * `word_tags.id` so a bulk import's text order survives, bidirectional pairs collapsed to one row.
    */
  def tagEntries(tagId: Long): Task[List[TagEntryRow]]

  /** Replaces one editor row's pair in one transaction: drops the old pair (both directions) and any now-orphaned,
    * non-imported membership among the old words, then links the resolved new pair. `oldTargetWordId` is `None` for an
    * unmatched row that had no pair yet.
    */
  def replacePair(
    tagId: Long,
    oldSourceWordId: Long,
    oldTargetWordId: Option[Long],
    newSourceWordId: Long,
    newTargetWordId: Long,
    createdAt: Long,
  ): Task[Unit]

  // -- The dictionary importer's bulk path ------------------------------------------------------
  // Batched and explicit-column, so no generated key has to come back: `getGeneratedKeys` after an
  // `executeBatch` is not something both drivers agree about. The importer inserts, then re-reads
  // the ids it needs by natural key.

  def insertWords(rows: List[WordRow]): Task[Long]
  def insertTranslations(rows: List[WordTranslationRow]): Task[Long]

  /** The rows already present for a batch of normalised texts in one language — how the importer stays idempotent
    * without an `ON CONFLICT` clause the two dialects spell differently.
    */
  def findWordsByKeys(language: String, textNorms: List[String]): Task[List[WordRow]]

  /** Every dictionary word in `language` whose `textNorm` length falls in `[minLength, maxLength]` — the candidate pool
    * bulk-upload's suggestion pass narrows edit-distance comparisons to, one batched query per language pass rather
    * than one per token, the same "one query, not N" shape as [[findWordsByKeys]].
    */
  def findWordsByLengthRange(language: String, minLength: Int, maxLength: Int): Task[List[WordRow]]

  /** Which `(source, target)` pairs already exist among those source words, so a re-run inserts nothing twice. */
  def existingTranslationPairs(sourceWordIds: List[Long]): Task[List[(Long, Long)]]

  def insertForms(rows: List[WordFormRow]): Task[Long]

  /** Which `(lemma, form, relation)` triples already exist for those lemma ids, so a re-run inserts nothing twice --
    * the forms equivalent of [[existingTranslationPairs]].
    */
  def existingFormRelations(lemmaWordIds: List[Long]): Task[List[(Long, Long, String)]]

  /** Every form of one word -- what a future "show inflections" screen would call. */
  def formsOf(lemmaWordId: Long): Task[List[WordFormRow]]

  /** Every lemma one word is a form of -- the reverse direction of [[formsOf]]. */
  def lemmaOf(formWordId: Long): Task[List[WordFormRow]]

  /** Every form relation whose *form* word is one of `formWordIds`, joined to the lemma's own row. Batched, the same
    * shape [[translationsOf]] is -- what a listing row's Main word column and a variant's detail-page Main word block
    * are both built from.
    */
  def lemmaContextOf(formWordIds: List[Long]): Task[List[(WordFormRow, WordRow)]]

  /** Every form relation whose *lemma* is one of `lemmaWordIds`, joined to the form's own row. Batched, the same shape
    * [[translationsOf]] is -- what a listing row's Variants column (capped by `WordService.wordFormsPerRow`) and a
    * lemma's detail-page Forms section (uncapped) are both built from.
    */
  def formsContextOf(lemmaWordIds: List[Long]): Task[List[(WordFormRow, WordRow)]]

  /** Every `(form word, relation)` pair claiming more than `threshold` distinct lemmas -- the shape of the bug where a
    * mislabeled wiktextract tag (e.g. a German conjugation table's `auxiliary` note) turns one common word into a
    * "form" of hundreds of others, which then floods every listing page that word appears on with context rows.
    */
  def formFanOutAnomalies(threshold: Int): Task[List[(WordRow, String, Long)]]

  /** Deletes every `word_forms` row for one `(form word, relation)` pair -- the cleanup action for
    * [[formFanOutAnomalies]].
    */
  def deleteWordForms(formWordId: Long, relation: String): Task[Long]

  def countWords: Task[Long]
  def countTranslations: Task[Long]
  def countTags: Task[Long]
  def countWordForms: Task[Long]
}

object WordRepository {

  def findWord(
    language: String,
    textNorm: String,
    partOfSpeech: String,
    gender: String,
  ): RIO[WordRepository, Option[WordRow]] =
    ZIO.serviceWithZIO[WordRepository](_.findWord(language, textNorm, partOfSpeech, gender))

  def findWordById(id: Long): RIO[WordRepository, Option[WordRow]] =
    ZIO.serviceWithZIO[WordRepository](_.findWordById(id))

  def ensureWord(row: WordRow): RIO[WordRepository, WordRow] =
    ZIO.serviceWithZIO[WordRepository](_.ensureWord(row))

  def setWordGender(id: Long, gender: String): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.setWordGender(id, gender))

  def listPage(
    offset: Int,
    limit: Int,
    language: Option[String],
    search: Option[String],
    partOfSpeech: Option[String],
    tagId: Option[Long],
    taggedBy: Option[Long],
    translationFilter: TranslationFilter,
    targetLanguage: String,
    mainOnly: Boolean,
    sort: Option[String],
    descending: Boolean,
  ): RIO[WordRepository, List[WordRow]] = {
    ZIO.serviceWithZIO[WordRepository](
      _.listPage(
        offset,
        limit,
        language,
        search,
        partOfSpeech,
        tagId,
        taggedBy,
        translationFilter,
        targetLanguage,
        mainOnly,
        sort,
        descending,
      )
    )
  }

  def countMatching(
    language: Option[String],
    search: Option[String],
    partOfSpeech: Option[String],
    tagId: Option[Long],
    taggedBy: Option[Long],
    translationFilter: TranslationFilter,
    targetLanguage: String,
    mainOnly: Boolean,
  ): RIO[WordRepository, Long] = {
    ZIO.serviceWithZIO[WordRepository](
      _.countMatching(language, search, partOfSpeech, tagId, taggedBy, translationFilter, targetLanguage, mainOnly)
    )
  }

  def translationsOf(
    wordIds: List[Long],
    targetLanguage: String,
  ): RIO[WordRepository, List[(WordTranslationRow, WordRow)]] =
    ZIO.serviceWithZIO[WordRepository](_.translationsOf(wordIds, targetLanguage))

  def allTranslationsOf(wordId: Long): RIO[WordRepository, List[(WordTranslationRow, WordRow)]] =
    ZIO.serviceWithZIO[WordRepository](_.allTranslationsOf(wordId))

  def wordIdsWithAnyTranslation(wordIds: List[Long]): RIO[WordRepository, Set[Long]] =
    ZIO.serviceWithZIO[WordRepository](_.wordIdsWithAnyTranslation(wordIds))

  def findTranslationById(id: Long): RIO[WordRepository, Option[WordTranslationRow]] =
    ZIO.serviceWithZIO[WordRepository](_.findTranslationById(id))

  def insertTranslationPair(
    sourceWordId: Long,
    targetWordId: Long,
    origin: String,
    createdBy: Option[Long],
    createdAt: Long,
  ): RIO[WordRepository, WordTranslationRow] = {
    ZIO.serviceWithZIO[WordRepository](
      _.insertTranslationPair(sourceWordId, targetWordId, origin, createdBy, createdAt)
    )
  }

  def deleteTranslationPair(id: Long, ownerId: Long): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.deleteTranslationPair(id, ownerId))

  def findTranslation(
    sourceWordId: Long,
    targetWordId: Long,
    createdBy: Option[Long],
  ): RIO[WordRepository, Option[WordTranslationRow]] =
    ZIO.serviceWithZIO[WordRepository](_.findTranslation(sourceWordId, targetWordId, createdBy))

  def listTags(viewerId: Long): RIO[WordRepository, List[(TagRow, Long, Boolean)]] =
    ZIO.serviceWithZIO[WordRepository](_.listTags(viewerId))

  def findTag(userId: Long, nameNorm: String): RIO[WordRepository, Option[TagRow]] =
    ZIO.serviceWithZIO[WordRepository](_.findTag(userId, nameNorm))

  def findTagById(id: Long): RIO[WordRepository, Option[TagRow]] =
    ZIO.serviceWithZIO[WordRepository](_.findTagById(id))

  def insertTag(userId: Long, name: String, nameNorm: String, createdAt: Long): RIO[WordRepository, TagRow] =
    ZIO.serviceWithZIO[WordRepository](_.insertTag(userId, name, nameNorm, createdAt))

  def createTagWithPairs(
    userId: Long,
    name: String,
    nameNorm: String,
    createdAt: Long,
    pairs: List[(Long, Long)],
  ): RIO[WordRepository, TagRow] =
    ZIO.serviceWithZIO[WordRepository](_.createTagWithPairs(userId, name, nameNorm, createdAt, pairs))

  def updateTag(id: Long, userId: Long, name: String, nameNorm: String): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.updateTag(id, userId, name, nameNorm))

  def deleteTag(id: Long, userId: Long): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.deleteTag(id, userId))

  def setTagGroup(tagId: Long, groupId: Option[Long]): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.setTagGroup(tagId, groupId))

  def countWordsInTag(tagId: Long): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.countWordsInTag(tagId))

  def countTagsOwnedBy(userId: Long): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.countTagsOwnedBy(userId))

  def countPairsOwnedBy(userId: Long): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.countPairsOwnedBy(userId))

  def countPairsInTag(tagId: Long): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.countPairsInTag(tagId))

  def copyTag(
    sourceId: Long,
    userId: Long,
    name: String,
    nameNorm: String,
    createdAt: Long,
  ): RIO[WordRepository, (TagRow, Long, Long)] =
    ZIO.serviceWithZIO[WordRepository](_.copyTag(sourceId, userId, name, nameNorm, createdAt))

  def tagWord(wordId: Long, tagId: Long, createdAt: Long): RIO[WordRepository, Unit] =
    ZIO.serviceWithZIO[WordRepository](_.tagWord(wordId, tagId, createdAt))

  def untagWord(wordId: Long, tagId: Long): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.untagWord(wordId, tagId))

  def tagsFor(userId: Long, wordIds: List[Long]): RIO[WordRepository, List[WordTagRow]] =
    ZIO.serviceWithZIO[WordRepository](_.tagsFor(userId, wordIds))

  def tagsOfWord(userId: Long, wordId: Long): RIO[WordRepository, List[TagRow]] =
    ZIO.serviceWithZIO[WordRepository](_.tagsOfWord(userId, wordId))

  def wordsInTag(tagId: Long): RIO[WordRepository, List[WordRow]] =
    ZIO.serviceWithZIO[WordRepository](_.wordsInTag(tagId))

  def pairsInTag(tagId: Long): RIO[WordRepository, List[WordTagPairRow]] =
    ZIO.serviceWithZIO[WordRepository](_.pairsInTag(tagId))

  def findWordsByIds(ids: List[Long]): RIO[WordRepository, List[WordRow]] =
    ZIO.serviceWithZIO[WordRepository](_.findWordsByIds(ids))

  def pairTranslation(
    wordId: Long,
    tagId: Long,
    translationWordId: Long,
    createdAt: Long,
  ): RIO[WordRepository, Unit] =
    ZIO.serviceWithZIO[WordRepository](_.pairTranslation(wordId, tagId, translationWordId, createdAt))

  def unpairTranslation(wordId: Long, tagId: Long, translationWordId: Long): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.unpairTranslation(wordId, tagId, translationWordId))

  def pairsFor(userId: Long, wordIds: List[Long]): RIO[WordRepository, List[WordTagPairRow]] =
    ZIO.serviceWithZIO[WordRepository](_.pairsFor(userId, wordIds))

  def importWord(wordId: Long, tagId: Long, createdAt: Long): RIO[WordRepository, Unit] =
    ZIO.serviceWithZIO[WordRepository](_.importWord(wordId, tagId, createdAt))

  def importPair(wordId: Long, tagId: Long, translationWordId: Long, createdAt: Long): RIO[WordRepository, Unit] =
    ZIO.serviceWithZIO[WordRepository](_.importPair(wordId, tagId, translationWordId, createdAt))

  def tagMemberships(tagId: Long): RIO[WordRepository, List[WordTagRow]] =
    ZIO.serviceWithZIO[WordRepository](_.tagMemberships(tagId))

  def removeEntry(tagId: Long, sourceWordId: Long): RIO[WordRepository, Unit] =
    ZIO.serviceWithZIO[WordRepository](_.removeEntry(tagId, sourceWordId))

  def removePair(tagId: Long, sourceWordId: Long, targetWordId: Long): RIO[WordRepository, Unit] =
    ZIO.serviceWithZIO[WordRepository](_.removePair(tagId, sourceWordId, targetWordId))

  def setTagLanguages(tagId: Long, sourceLanguage: String, targetLanguage: String): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.setTagLanguages(tagId, sourceLanguage, targetLanguage))

  def tagEntries(tagId: Long): RIO[WordRepository, List[TagEntryRow]] =
    ZIO.serviceWithZIO[WordRepository](_.tagEntries(tagId))

  def replacePair(
    tagId: Long,
    oldSourceWordId: Long,
    oldTargetWordId: Option[Long],
    newSourceWordId: Long,
    newTargetWordId: Long,
    createdAt: Long,
  ): RIO[WordRepository, Unit] = {
    ZIO.serviceWithZIO[WordRepository](
      _.replacePair(tagId, oldSourceWordId, oldTargetWordId, newSourceWordId, newTargetWordId, createdAt)
    )
  }

  def insertWords(rows: List[WordRow]): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.insertWords(rows))

  def insertTranslations(rows: List[WordTranslationRow]): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.insertTranslations(rows))

  def findWordsByKeys(language: String, textNorms: List[String]): RIO[WordRepository, List[WordRow]] =
    ZIO.serviceWithZIO[WordRepository](_.findWordsByKeys(language, textNorms))

  def findWordsByLengthRange(language: String, minLength: Int, maxLength: Int): RIO[WordRepository, List[WordRow]] =
    ZIO.serviceWithZIO[WordRepository](_.findWordsByLengthRange(language, minLength, maxLength))

  def existingTranslationPairs(sourceWordIds: List[Long]): RIO[WordRepository, List[(Long, Long)]] =
    ZIO.serviceWithZIO[WordRepository](_.existingTranslationPairs(sourceWordIds))

  def insertForms(rows: List[WordFormRow]): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.insertForms(rows))

  def existingFormRelations(lemmaWordIds: List[Long]): RIO[WordRepository, List[(Long, Long, String)]] =
    ZIO.serviceWithZIO[WordRepository](_.existingFormRelations(lemmaWordIds))

  def formsOf(lemmaWordId: Long): RIO[WordRepository, List[WordFormRow]] =
    ZIO.serviceWithZIO[WordRepository](_.formsOf(lemmaWordId))

  def lemmaOf(formWordId: Long): RIO[WordRepository, List[WordFormRow]] =
    ZIO.serviceWithZIO[WordRepository](_.lemmaOf(formWordId))

  def lemmaContextOf(formWordIds: List[Long]): RIO[WordRepository, List[(WordFormRow, WordRow)]] =
    ZIO.serviceWithZIO[WordRepository](_.lemmaContextOf(formWordIds))

  def formsContextOf(lemmaWordIds: List[Long]): RIO[WordRepository, List[(WordFormRow, WordRow)]] =
    ZIO.serviceWithZIO[WordRepository](_.formsContextOf(lemmaWordIds))

  def formFanOutAnomalies(threshold: Int): RIO[WordRepository, List[(WordRow, String, Long)]] =
    ZIO.serviceWithZIO[WordRepository](_.formFanOutAnomalies(threshold))

  def deleteWordForms(formWordId: Long, relation: String): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.deleteWordForms(formWordId, relation))

  val live: ZLayer[DataSource, Nothing, WordRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new WordRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): WordRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, WordRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new WordRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): WordRepository
  )
}

final class WordRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with WordRepository {
  import ctx._

  private inline def words        = quote(querySchema[WordRow]("words"))
  private inline def translations = quote(querySchema[WordTranslationRow]("word_translations"))
  private inline def wordForms    = quote(querySchema[WordFormRow]("word_forms"))
  private inline def tags         = quote(querySchema[TagRow]("tags"))
  private inline def wordTags     = quote(querySchema[WordTagRow]("word_tags"))
  private inline def wordTagPairs = quote(querySchema[WordTagPairRow]("word_tag_pairs"))
  // Read-only view of a table GroupRepository owns — see that trait's own note on reading across repositories. Needed
  // here so a reader's tick/mark enrichment (`tagsFor`/`pairsFor`) can widen from "tags I own" to "tags I may edit",
  // which now includes any tag attached to a group I belong to.
  private inline def groupMembers = quote(querySchema[GroupMemberRow]("group_members"))

  // -- Words ------------------------------------------------------------------------------------

  def findWord(language: String, textNorm: String, partOfSpeech: String, gender: String): Task[Option[WordRow]] = {
    val q = quote(
      words.filter(word => {
        word.language == lift(language) && word.textNorm == lift(textNorm) &&
        word.partOfSpeech == lift(partOfSpeech) && word.gender == lift(gender)
      })
    )
    logged(run(ctx.run(q)).map(_.headOption))(found => s"words.findWord lang=$language found=${found.isDefined}")
  }

  def findWordById(id: Long): Task[Option[WordRow]] = {
    logged(run(ctx.run(quote(words.filter(_.id == lift(id))))).map(_.headOption)) { found =>
      s"words.findWordById id=$id found=${found.isDefined}"
    }
  }

  private def insertWord(row: WordRow): Task[WordRow] = {
    val inserted = run(ctx.run(quote(words.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id)))(word => s"words.insert id=${word.id} lang=${row.language}")
  }

  def ensureWord(row: WordRow): Task[WordRow] = {
    val lookup = findWord(row.language, row.textNorm, row.partOfSpeech, row.gender)
    lookup.flatMap {
      case Some(existing) =>
        ZIO.succeed(existing)
      case None           =>
        // A concurrent caller may have inserted the same word between the lookup and here; the unique index is what
        // decides, and the loser simply reads the winner's row.
        insertWord(row).catchAll(error => lookup.flatMap(ZIO.fromOption(_).orElseFail(error)).mapError(identity))
    }
  }

  def setWordGender(id: Long, gender: String): Task[Long] = {
    val q = quote(words.filter(word => word.id == lift(id) && word.gender == lift("")).update(_.gender -> lift(gender)))
    logged(run(ctx.run(q)))(rows => s"words.setGender id=$id gender=$gender rows=$rows")
  }

  /** Every article form of every language this app teaches, longest first so `"los"` is not shadowed by a shorter form
    * that happens to be a prefix of it — none are today, but the ordering costs nothing and stays correct if one ever
    * is. Built once from [[LanguageProfile]] rather than naming an article here, so a new language's articles are
    * stripped by the search box with no change to this file.
    */
  private val leadingArticle = {
    val forms = WordLanguage.all
      .flatMap(language => LanguageProfile.of(language).articleForms.keys)
      .distinct
      .sortBy(-_.length)
    if (forms.isEmpty) None else Some(("^(?:" + forms.mkString("|") + ")\\s+").r)
  }

  /** The prefix pattern behind the search box, or `None` when it is empty.
    *
    * A prefix rather than a substring: it is what an autocomplete means, and it is the shape `idx_words_search`
    * answers. Text is stored lowercased and accent-folded in `textSearch`, so lowercasing and folding the needle the
    * same way is the whole of the case- and accent-insensitivity, with no `lower()` for the two dialects to disagree
    * about — the rule `UserRepository.emailPattern` follows. This means "hau" finds "häuser" and "o" finds "ő". A
    * leading article is stripped first, since `textSearch` holds only the noun, not its gender article — stripping
    * every language's articles rather than only the listing's own costs nothing and keeps this independent of whichever
    * `language` filter the caller passed.
    */
  private def searchPattern(search: Option[String]): Option[String] = {
    search
      .map(needle => leadingArticle.fold(needle.trim.toLowerCase)(_.replaceFirstIn(needle.trim.toLowerCase, "")))
      .map(needle => TextSearch.fold(needle))
      .filter(_.nonEmpty)
      .map(needle => s"$needle%")
  }

  /** True when the account has this word under any of its own tags. */
  private inline def taggedByUser = quote { (wordId: Long, userId: Long) =>
    wordTags
      .filter(link =>
        link.wordId == wordId && tags.filter(tag => tag.id == link.tagId && tag.userId == userId).nonEmpty
      )
      .nonEmpty
  }

  /** True when the word has a translation edge into `language` — a correlated subquery, doubly nested rather than
    * joined: Quill's Dynamic Query cannot synthesize a `.join` inside a `filterOpt` closure ("free variables"), so this
    * follows [[taggedByUser]]'s shape of one `.filter(...).nonEmpty` inside another instead of [[translationsOf]]'s
    * ordinary join.
    */
  private inline def hasTranslationInto = quote { (wordId: Long, language: String) =>
    translations
      .filter(edge => {
        edge.sourceWordId == wordId && words
          .filter(word => word.id == edge.targetWordId && word.language == language)
          .nonEmpty
      })
      .nonEmpty
  }

  /** True when the word has a translation edge into any language at all. */
  private inline def hasAnyTranslationEdge = quote { (wordId: Long) =>
    translations.filter(edge => edge.sourceWordId == wordId).nonEmpty
  }

  /** The narrowing [[listPage]] and [[countMatching]] share, so the total counts the set the page is cut from.
    * `targetLanguage` is read only under [[TranslationFilter.HasTarget]].
    */
  private def matching(
    language: Option[String],
    search: Option[String],
    partOfSpeech: Option[String],
    tagId: Option[Long],
    taggedBy: Option[Long],
    translationFilter: TranslationFilter,
    targetLanguage: String,
    mainOnly: Boolean,
  ): DynamicQuery[WordRow] = {
    val base = dynamicQuerySchema[WordRow]("words")
      .filterOpt(language)((word, value) => quote(word.language == unquote(value)))
      .filterOpt(searchPattern(search))((word, pattern) => quote(word.textSearch.like(unquote(pattern))))
      .filterOpt(partOfSpeech)((word, value) => quote(word.partOfSpeech == unquote(value)))
      .filterOpt(tagId)((word, value) =>
        quote(wordTags.filter(link => link.wordId == word.id && link.tagId == unquote(value)).nonEmpty)
      )
      .filterOpt(taggedBy)((word, userId) => quote(taggedByUser(word.id, unquote(userId))))
      // A column, not a `NOT EXISTS` over `word_forms`: as a row predicate it combines with the `ORDER BY ... LIMIT`
      // below, which `idx_words_main_rank` then answers outright. `WordRow.isForm` says who keeps it true.
      .filterOpt(Option.when(mainOnly)(true))((word, _) => quote(!word.isForm))
    translationFilter match {
      case TranslationFilter.All       =>
        base
      case TranslationFilter.HasTarget =>
        base.filterOpt(Some(targetLanguage))((word, lang) => quote(hasTranslationInto(word.id, unquote(lang))))
      case TranslationFilter.HasAny    =>
        base.filterOpt(Some(true))((word, _) => quote(hasAnyTranslationEdge(word.id)))
    }
  }

  /** The `dto.WordSort` vocabulary as an `ORDER BY`, defaulting to the listing's own order: commonest first.
    *
    * That default is what makes a two-letter search useful — a hundred matches with the everyday word at the top — and
    * it is why `frequency_rank` carries a large sentinel rather than NULL for the words nobody has ranked.
    *
    * `WordSort.added` is the one value that orders by something outside `words` — the tick that filed the word under
    * the narrowed tag — so it needs `tagId`, and without one it falls back to that same default rather than failing,
    * the way an unrecognized sort does.
    */
  private def ordered(
    query: DynamicQuery[WordRow],
    sort: Option[String],
    descending: Boolean,
    tagId: Option[Long],
  ): DynamicQuery[WordRow] = {
    (sort, tagId) match {
      case (Some(WordSort.added), Some(tag)) =>
        // A join, not the correlated `.nonEmpty` the filters use: an `ORDER BY` needs the link row itself, not the
        // answer to whether it exists. It is a join at the top level rather than inside a `filterOpt` closure, which
        // is the shape Quill's Dynamic Query refuses (see `hasTranslationInto`). `word_tags` is
        // UNIQUE(word_id, tag_id) and this narrows to one tag, so it matches each word at most once — the page holds
        // the same rows `countMatching` counted, in a different order.
        query
          .join(quote(wordTags.filter(link => link.tagId == lift(tag))))
          .on((word, link) => quote(word.id == link.wordId))
          .sortBy(_._2.createdAt)(using ordering(descending))
          .map(_._1)
      case (Some(WordSort.text), _)          =>
        query.sortBy(_.textNorm)(using ordering(descending))
      case (Some(WordSort.pos), _)           =>
        query.sortBy(_.partOfSpeech)(using ordering(descending))
      case (Some(WordSort.rank), _)          =>
        query.sortBy(_.frequencyRank)(using ordering(descending))
      case _                                 =>
        query.sortBy(word => (word.frequencyRank, word.textNorm))(using Ord.asc)
    }
  }

  def listPage(
    offset: Int,
    limit: Int,
    language: Option[String],
    search: Option[String],
    partOfSpeech: Option[String],
    tagId: Option[Long],
    taggedBy: Option[Long],
    translationFilter: TranslationFilter,
    targetLanguage: String,
    mainOnly: Boolean,
    sort: Option[String],
    descending: Boolean,
  ): Task[List[WordRow]] = {
    val page = ordered(
      matching(language, search, partOfSpeech, tagId, taggedBy, translationFilter, targetLanguage, mainOnly),
      sort,
      descending,
      tagId,
    ).drop(offset).take(limit)
    // The search term is a fragment of somebody's vocabulary and stays out of the message, like an address.
    logged(run(ctx.run(page))) { rows =>
      s"words.listPage offset=$offset limit=$limit lang=${language.getOrElse("-")} rows=${rows.size}"
    }
  }

  def countMatching(
    language: Option[String],
    search: Option[String],
    partOfSpeech: Option[String],
    tagId: Option[Long],
    taggedBy: Option[Long],
    translationFilter: TranslationFilter,
    targetLanguage: String,
    mainOnly: Boolean,
  ): Task[Long] = {
    // A bare `.size`, deliberately: it renders `COUNT(*)`, which needs no column at all and so is answered by
    // V20's partial indexes as an index-only scan. `.map(_.id).size` renders `COUNT(id)`, and `id` is in none of
    // those indexes — measured on the real dictionary it drops to a bitmap heap scan, 2405 buffers against 112.
    val q = matching(language, search, partOfSpeech, tagId, taggedBy, translationFilter, targetLanguage, mainOnly).size
    logged(run(ctx.run(q)))(count => s"words.countMatching count=$count")
  }

  // -- Translations -----------------------------------------------------------------------------

  def translationsOf(wordIds: List[Long], targetLanguage: String): Task[List[(WordTranslationRow, WordRow)]] = {
    if (wordIds.isEmpty)
      ZIO.succeed(Nil)
    else {
      val q = quote {
        translations
          .join(words)
          .on((edge, word) => edge.targetWordId == word.id)
          .filter { case (edge, word) =>
            liftQuery(wordIds).contains(edge.sourceWordId) && word.language == lift(targetLanguage)
          }
      }
      logged(run(ctx.run(q)))(rows => s"words.translationsOf words=${wordIds.size} rows=${rows.size}")
    }
  }

  def allTranslationsOf(wordId: Long): Task[List[(WordTranslationRow, WordRow)]] = {
    val q = quote {
      translations
        .join(words)
        .on((edge, word) => edge.targetWordId == word.id)
        .filter { case (edge, _) => edge.sourceWordId == lift(wordId) }
    }
    logged(run(ctx.run(q)))(rows => s"words.allTranslationsOf id=$wordId rows=${rows.size}")
  }

  def wordIdsWithAnyTranslation(wordIds: List[Long]): Task[Set[Long]] = {
    if (wordIds.isEmpty)
      ZIO.succeed(Set.empty)
    else {
      val q = quote {
        translations.filter(edge => liftQuery(wordIds).contains(edge.sourceWordId)).map(_.sourceWordId).distinct
      }
      logged(run(ctx.run(q)).map(_.toSet)) { ids =>
        s"wordTranslations.withAny requested=${wordIds.size} rows=${ids.size}"
      }
    }
  }

  def findTranslationById(id: Long): Task[Option[WordTranslationRow]] = {
    logged(run(ctx.run(quote(translations.filter(_.id == lift(id))))).map(_.headOption)) { found =>
      s"wordTranslations.findById id=$id found=${found.isDefined}"
    }
  }

  def findTranslation(
    sourceWordId: Long,
    targetWordId: Long,
    createdBy: Option[Long],
  ): Task[Option[WordTranslationRow]] = {
    val q = quote(
      translations.filter(edge => {
        edge.sourceWordId == lift(sourceWordId) && edge.targetWordId == lift(targetWordId) &&
        edge.createdBy == lift(createdBy)
      })
    )
    logged(run(ctx.run(q)).map(_.headOption))(found => s"wordTranslations.find found=${found.isDefined}")
  }

  def insertTranslationPair(
    sourceWordId: Long,
    targetWordId: Long,
    origin: String,
    createdBy: Option[Long],
    createdAt: Long,
  ): Task[WordTranslationRow] = {
    val forward  = WordTranslationRow(0L, sourceWordId, targetWordId, origin, createdBy, createdAt)
    val back     = WordTranslationRow(0L, targetWordId, sourceWordId, origin, createdBy, createdAt)
    // Both directions or neither: a half-recorded pair would make the practice screen able to ask a question one way
    // round and not the other.
    val inserted = transaction(
      for {
        id <- ctx.run(quote(translations.insertValue(lift(forward)).returningGenerated(_.id)))
        _  <- ctx.run(quote(translations.insertValue(lift(back)).returningGenerated(_.id)))
      } yield id
    )
    logged(inserted.map(id => forward.copy(id = id))) { row =>
      s"wordTranslations.insertPair id=${row.id} source=$sourceWordId target=$targetWordId origin=$origin"
    }
  }

  def deleteTranslationPair(id: Long, ownerId: Long): Task[Long] = {
    val owned   = quote(
      translations.filter(edge => edge.id == lift(id) && edge.createdBy.contains(lift(ownerId)))
    )
    val deleted = transaction(
      for {
        found <- ctx.run(owned)
        rows  <- found.headOption match {
                   case None       =>
                     ZIO.succeed(0L)
                   case Some(edge) =>
                     val mirror = quote(
                       translations.filter(other => {
                         other.sourceWordId == lift(edge.targetWordId) &&
                         other.targetWordId == lift(edge.sourceWordId) &&
                         other.createdBy.contains(lift(ownerId))
                       })
                     )
                     ctx.run(owned.delete).zipWith(ctx.run(mirror.delete))(_ + _)
                 }
      } yield rows
    )
    logged(deleted)(rows => s"wordTranslations.deletePair id=$id owner=$ownerId rows=$rows")
  }

  // -- Tags -------------------------------------------------------------------------------------

  def listTags(viewerId: Long): Task[List[(TagRow, Long, Boolean)]] = {
    val allTags = quote(tags.sortBy(_.nameNorm)(using Ord.asc))
    // Not joined against `tags` by owner: a `word_tags` row's `tag_id` already names a tag one particular account
    // owns, so grouping it alone already answers "how many words carry this tag" for every tag at once.
    val counts  = quote(wordTags.groupBy(_.tagId).map { case (tagId, links) => (tagId, links.size) })
    val listed  = for {
      rows   <- run(ctx.run(allTags))
      byTag  <- run(ctx.run(counts))
      counted = byTag.toMap
    } yield rows.map(tag => (tag, counted.getOrElse(tag.id, 0L), tag.userId == viewerId))
    logged(listed)(rows => s"tags.list viewer=$viewerId rows=${rows.size}")
  }

  def findTag(userId: Long, nameNorm: String): Task[Option[TagRow]] = {
    val q = quote(tags.filter(tag => tag.userId == lift(userId) && tag.nameNorm == lift(nameNorm)))
    logged(run(ctx.run(q)).map(_.headOption))(found => s"tags.find user=$userId found=${found.isDefined}")
  }

  def findTagById(id: Long): Task[Option[TagRow]] = {
    logged(run(ctx.run(quote(tags.filter(_.id == lift(id))))).map(_.headOption)) { found =>
      s"tags.findById id=$id found=${found.isDefined}"
    }
  }

  def insertTag(userId: Long, name: String, nameNorm: String, createdAt: Long): Task[TagRow] = {
    val row      = TagRow(0L, userId, name, nameNorm, createdAt)
    val inserted = run(ctx.run(quote(tags.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id)))(tag => s"tags.insert id=${tag.id} user=$userId")
  }

  def updateTag(id: Long, userId: Long, name: String, nameNorm: String): Task[Long] = {
    val q = quote {
      tags
        .filter(tag => tag.id == lift(id) && tag.userId == lift(userId))
        .update(_.name -> lift(name), _.nameNorm -> lift(nameNorm))
    }
    logged(run(ctx.run(q)))(rows => s"tags.update id=$id user=$userId rows=$rows")
  }

  def deleteTag(id: Long, userId: Long): Task[Long] = {
    val q = quote(tags.filter(tag => tag.id == lift(id) && tag.userId == lift(userId)).delete)
    logged(run(ctx.run(q)))(rows => s"tags.delete id=$id user=$userId rows=$rows")
  }

  def setTagGroup(tagId: Long, groupId: Option[Long]): Task[Long] = {
    val q = quote(tags.filter(_.id == lift(tagId)).update(_.groupId -> lift(groupId)))
    logged(run(ctx.run(q)))(rows => s"tags.setGroup id=$tagId group=$groupId rows=$rows")
  }

  def countWordsInTag(tagId: Long): Task[Long] = {
    val q = quote(wordTags.filter(_.tagId == lift(tagId)).size)
    logged(run(ctx.run(q)))(count => s"wordTags.countInTag tag=$tagId count=$count")
  }

  def countTagsOwnedBy(userId: Long): Task[Long] = {
    val q = quote(tags.filter(_.userId == lift(userId)).size)
    logged(run(ctx.run(q)))(count => s"tags.countOwnedBy user=$userId count=$count")
  }

  def countPairsOwnedBy(userId: Long): Task[Long] = {
    val q = quote {
      wordTagPairs.filter(pair => tags.filter(tag => tag.id == pair.tagId && tag.userId == lift(userId)).nonEmpty).size
    }
    logged(run(ctx.run(q)))(count => s"wordTagPairs.countOwnedBy user=$userId count=$count")
  }

  def countPairsInTag(tagId: Long): Task[Long] = {
    val q = quote(wordTagPairs.filter(_.tagId == lift(tagId)).size)
    logged(run(ctx.run(q)))(count => s"wordTagPairs.countInTag tag=$tagId count=$count")
  }

  def copyTag(
    sourceId: Long,
    userId: Long,
    name: String,
    nameNorm: String,
    createdAt: Long,
  ): Task[(TagRow, Long, Long)] = {
    val copied = transaction(
      for {
        source      <- ctx.run(quote(tags.filter(_.id == lift(sourceId)))).map(_.headOption)
        newTag       = TagRow(
                         0L,
                         userId,
                         name,
                         nameNorm,
                         createdAt,
                         sourceLanguage = source.flatMap(_.sourceLanguage),
                         targetLanguage = source.flatMap(_.targetLanguage),
                       )
        newId       <- ctx.run(quote(tags.insertValue(lift(newTag)).returningGenerated(_.id)))
        sourceWords <- ctx.run(quote(wordTags.filter(_.tagId == lift(sourceId))))
        sourcePairs <- ctx.run(quote(wordTagPairs.filter(_.tagId == lift(sourceId))))
        newLinks     = sourceWords.map(link => WordTagRow(0L, link.wordId, newId, createdAt))
        newPairs     = sourcePairs.map(pair => WordTagPairRow(0L, pair.wordId, newId, pair.translationWordId, createdAt))
        _           <- ZIO.unless(newLinks.isEmpty) {
                         ctx.run(quote {
                           liftQuery(newLinks).foreach(row => {
                             wordTags.insert(_.wordId -> row.wordId, _.tagId -> row.tagId, _.createdAt -> row.createdAt)
                           })
                         })
                       }
        _           <- ZIO.unless(newPairs.isEmpty) {
                         ctx.run(quote {
                           liftQuery(newPairs).foreach(row => {
                             wordTagPairs.insert(
                               _.wordId            -> row.wordId,
                               _.tagId             -> row.tagId,
                               _.translationWordId -> row.translationWordId,
                               _.createdAt         -> row.createdAt,
                             )
                           })
                         })
                       }
      } yield (newTag.copy(id = newId), newLinks.size.toLong, newPairs.size.toLong)
    )
    logged(copied) { case (tag, words, pairs) =>
      s"tags.copy source=$sourceId id=${tag.id} user=$userId words=$words pairs=$pairs"
    }
  }

  /** One membership row, inserted only if it is not already there, and answering whether it was.
    *
    * Kept as a `ZIO[DataSource, …]` rather than a `Task` so it can take part in [[pairTranslation]]'s transaction:
    * [[QuillRepository.run]] discharges the requirement, and a query that has already had its own environment supplied
    * takes its own connection instead of the transaction's.
    */
  private def linkOnce(
    wordId: Long,
    tagId: Long,
    createdAt: Long,
    imported: Boolean = false,
  ): ZIO[DataSource, Throwable, Boolean] = {
    val existing = quote(wordTags.filter(link => link.wordId == lift(wordId) && link.tagId == lift(tagId)))
    val row      = WordTagRow(0L, wordId, tagId, createdAt, imported)
    ctx.run(existing).map(_.headOption).flatMap {
      case Some(link) =>
        // An import may promote a hand-added membership to `imported`; nothing ever clears the flag.
        if (imported && !link.imported) {
          ctx
            .run(quote {
              wordTags.filter(l => l.wordId == lift(wordId) && l.tagId == lift(tagId)).update(_.imported -> true)
            })
            .as(false)
        } else ZIO.succeed(false)
      case None       =>
        ctx.run(quote(wordTags.insertValue(lift(row)).returningGenerated(_.id))).as(true)
    }
  }

  def tagWord(wordId: Long, tagId: Long, createdAt: Long): Task[Unit] = {
    logged(run(linkOnce(wordId, tagId, createdAt)))(added => s"wordTags.tag word=$wordId tag=$tagId added=$added").unit
  }

  /** [[tagWord]], but the membership is marked `imported` — and an existing hand-added one is promoted to it. */
  def importWord(wordId: Long, tagId: Long, createdAt: Long): Task[Unit] = {
    logged(run(linkOnce(wordId, tagId, createdAt, imported = true))) { added =>
      s"wordTags.import word=$wordId tag=$tagId added=$added"
    }.unit
  }

  /** One tag's memberships, oldest first (`word_tags.id` ascending == insertion order == a bulk import's text order),
    * carrying the `imported` flag — what the editor's row list is ordered and badged by.
    */
  def tagMemberships(tagId: Long): Task[List[WordTagRow]] = {
    val q = quote(wordTags.filter(_.tagId == lift(tagId)).sortBy(_.id)(using Ord.asc))
    logged(run(ctx.run(q)))(rows => s"wordTags.memberships tag=$tagId rows=${rows.size}")
  }

  /** Fills a tag's language pair, but only while it has none — the pair locks after the first row is added. Rows
    * affected: `0` when the tag already has languages (or does not exist), `1` on the first write.
    */
  def setTagLanguages(tagId: Long, sourceLanguage: String, targetLanguage: String): Task[Long] = {
    val q = quote {
      tags
        .filter(tag => tag.id == lift(tagId) && tag.sourceLanguage.isEmpty && tag.targetLanguage.isEmpty)
        .update(_.sourceLanguage -> lift(Option(sourceLanguage)), _.targetLanguage -> lift(Option(targetLanguage)))
    }
    logged(run(ctx.run(q)))(rows => s"tags.setLanguages id=$tagId rows=$rows")
  }

  def untagWord(wordId: Long, tagId: Long): Task[Long] = {
    // The word's practice pairs in this tag go with it, in both directions: a pair naming a word the tag no longer
    // holds is a question with a missing half, and it renders nowhere, so nothing would ever offer to clear it. Both
    // tables belong to this repository, so the two deletes are one unit of work.
    val pairs   = quote(
      wordTagPairs
        .filter(pair => {
          pair.tagId == lift(tagId) &&
          (pair.wordId == lift(wordId) || pair.translationWordId == lift(wordId))
        })
        .delete
    )
    val link    = quote(wordTags.filter(row => row.wordId == lift(wordId) && row.tagId == lift(tagId)).delete)
    val removed = transaction(ctx.run(pairs) *> ctx.run(link))
    logged(removed)(rows => s"wordTags.untag word=$wordId tag=$tagId rows=$rows")
  }

  def tagsFor(userId: Long, wordIds: List[Long]): Task[List[WordTagRow]] = {
    if (wordIds.isEmpty)
      ZIO.succeed(Nil)
    else {
      val q = quote {
        wordTags.filter(link => {
          liftQuery(wordIds).contains(link.wordId) &&
          tags
            .filter(tag => {
              tag.id == link.tagId &&
              (tag.userId == lift(userId) ||
                tag.groupId
                  .exists(gid => groupMembers.filter(m => m.groupId == gid && m.userId == lift(userId)).nonEmpty))
            })
            .nonEmpty
        })
      }
      logged(run(ctx.run(q)))(rows => s"wordTags.forWords user=$userId words=${wordIds.size} rows=${rows.size}")
    }
  }

  def tagsOfWord(userId: Long, wordId: Long): Task[List[TagRow]] = {
    val q = quote {
      tags.filter(tag => {
        (tag.userId == lift(userId) ||
          tag.groupId.exists(gid => groupMembers.filter(m => m.groupId == gid && m.userId == lift(userId)).nonEmpty)) &&
        wordTags.filter(link => link.tagId == tag.id && link.wordId == lift(wordId)).nonEmpty
      })
    }
    logged(run(ctx.run(q)))(rows => s"tags.ofWord user=$userId word=$wordId rows=${rows.size}")
  }

  def wordsInTag(tagId: Long): Task[List[WordRow]] = {
    val q = quote(
      words.filter(word => wordTags.filter(link => link.tagId == lift(tagId) && link.wordId == word.id).nonEmpty)
    )
    logged(run(ctx.run(q)))(rows => s"words.inTag tag=$tagId rows=${rows.size}")
  }

  def pairsInTag(tagId: Long): Task[List[WordTagPairRow]] = {
    val q = quote(wordTagPairs.filter(_.tagId == lift(tagId)))
    logged(run(ctx.run(q)))(rows => s"wordTagPairs.inTag tag=$tagId rows=${rows.size}")
  }

  def findWordsByIds(ids: List[Long]): Task[List[WordRow]] = {
    if (ids.isEmpty)
      ZIO.succeed(Nil)
    else {
      val q = quote(words.filter(word => liftQuery(ids).contains(word.id)))
      logged(run(ctx.run(q)))(rows => s"words.byIds n=${ids.size} rows=${rows.size}")
    }
  }

  // -- Practice pairs ---------------------------------------------------------------------------

  /** One pair row, inserted only if it is not already there. A `ZIO[DataSource, …]` for the reason [[linkOnce]] is.
    * `exact` promotes an existing row's flag the same way [[linkOnce]]'s `imported` does; nothing clears it.
    */
  private def pairOnce(
    wordId: Long,
    tagId: Long,
    translationWordId: Long,
    createdAt: Long,
    exact: Boolean = false,
  ): ZIO[DataSource, Throwable, Unit] = {
    val existing = quote(
      wordTagPairs.filter(pair => {
        pair.wordId == lift(wordId) && pair.tagId == lift(tagId) &&
        pair.translationWordId == lift(translationWordId)
      })
    )
    val row      = WordTagPairRow(0L, wordId, tagId, translationWordId, createdAt, exact)
    ctx.run(existing).map(_.headOption).flatMap {
      case Some(pair) =>
        if (exact && !pair.exact) {
          ctx
            .run(quote {
              wordTagPairs
                .filter(p =>
                  p.wordId == lift(wordId) && p.tagId == lift(tagId) && p.translationWordId == lift(translationWordId)
                )
                .update(_.exact -> true)
            })
            .unit
        } else ZIO.unit
      case None       =>
        ctx.run(quote(wordTagPairs.insertValue(lift(row)).returningGenerated(_.id))).unit
    }
  }

  /** The four rows a bilingual pair adds inside one tag, in both directions, each inserted only if it is not already
    * there. A `ZIO[DataSource, …]` so both [[pairTranslation]] and [[createTagWithPairs]] can run it inside their own
    * transaction. `exact`/`imported` mark a pair a bulk import matched exactly and the memberships it wrote.
    */
  private def linkPair(
    wordId: Long,
    tagId: Long,
    translationWordId: Long,
    createdAt: Long,
    exact: Boolean = false,
    imported: Boolean = false,
  ): ZIO[DataSource, Throwable, Unit] = {
    for {
      _ <- linkOnce(wordId, tagId, createdAt, imported)
      _ <- linkOnce(translationWordId, tagId, createdAt, imported)
      _ <- pairOnce(wordId, tagId, translationWordId, createdAt, exact)
      _ <- pairOnce(translationWordId, tagId, wordId, createdAt, exact)
    } yield ()
  }

  def pairTranslation(wordId: Long, tagId: Long, translationWordId: Long, createdAt: Long): Task[Unit] = {
    // Four writes or none. A pair whose answer is not itself in the tag is a question the reader could never have
    // collected the answer to, and a half-recorded pair would be answerable one way round and not the other.
    val marked = transaction(linkPair(wordId, tagId, translationWordId, createdAt))
    logged(marked)(_ => s"wordTagPairs.pair word=$wordId tag=$tagId translation=$translationWordId")
  }

  /** [[pairTranslation]], but the pair is marked `exact` and both memberships `imported` — a bulk import's exact match.
    * Existing rows are promoted, never re-inserted.
    */
  def importPair(wordId: Long, tagId: Long, translationWordId: Long, createdAt: Long): Task[Unit] = {
    val marked = transaction(linkPair(wordId, tagId, translationWordId, createdAt, exact = true, imported = true))
    logged(marked)(_ => s"wordTagPairs.import word=$wordId tag=$tagId translation=$translationWordId")
  }

  /** Replaces one editor row's pair in a single transaction. Drops the old pair in both directions, drops the old
    * source/target memberships that are now orphaned and were not `imported` (an imported orphan stays as its own
    * "unmatched" row), then links the new pair. `oldTargetWordId` is `None` for an unmatched row that had no pair. A
    * word that also appears in the new pair is left alone, so its membership id and flags survive the edit.
    */
  def replacePair(
    tagId: Long,
    oldSourceWordId: Long,
    oldTargetWordId: Option[Long],
    newSourceWordId: Long,
    newTargetWordId: Long,
    createdAt: Long,
  ): Task[Unit] = {
    val keep    = Set(newSourceWordId, newTargetWordId)
    val toPrune = (oldSourceWordId :: oldTargetWordId.toList).distinct.filterNot(keep.contains)
    val work    = transaction(
      for {
        _ <- oldTargetWordId match {
               case Some(oldTarget) =>
                 ctx.run(quote {
                   wordTagPairs
                     .filter(pair => {
                       pair.tagId == lift(tagId) &&
                       ((pair.wordId == lift(oldSourceWordId) && pair.translationWordId == lift(oldTarget)) ||
                         (pair.wordId == lift(oldTarget) && pair.translationWordId == lift(oldSourceWordId)))
                     })
                     .delete
                 })
               case None            =>
                 ZIO.succeed(0L)
             }
        // The reader is explicitly editing this row, so a now-orphaned old side goes even if a bulk import wrote it —
        // unlike [[removeEntry]], which leaves an imported orphan as its own row.
        _ <- ZIO.foreachDiscard(toPrune)(pruneOrphanMembership(tagId, _, force = true))
        _ <- linkPair(newSourceWordId, tagId, newTargetWordId, createdAt)
      } yield ()
    )
    logged(work)(_ => s"wordTagPairs.replace tag=$tagId oldSource=$oldSourceWordId newSource=$newSourceWordId")
  }

  /** Deletes one editor row whole: the source word's membership, every `word_tag_pairs` row naming it in this tag (both
    * directions), and each partner word's membership when nothing else in the tag references it and it was not
    * `imported`. One transaction. Unlike [[untagWord]] — which the listing's tick uses and which deliberately leaves
    * the other word collected — a row is a pair, and removing it should not strand its answer half as a stray row.
    */
  def removeEntry(tagId: Long, sourceWordId: Long): Task[Unit] = {
    val work = transaction(
      for {
        pairs   <- ctx.run(quote {
                     wordTagPairs.filter(pair => {
                       pair.tagId == lift(tagId) &&
                       (pair.wordId == lift(sourceWordId) || pair.translationWordId == lift(sourceWordId))
                     })
                   })
        partners = pairs.flatMap(p => List(p.wordId, p.translationWordId)).filterNot(_ == sourceWordId).distinct
        _       <- ctx.run(quote {
                     wordTagPairs
                       .filter(pair => {
                         pair.tagId == lift(tagId) &&
                         (pair.wordId == lift(sourceWordId) || pair.translationWordId == lift(sourceWordId))
                       })
                       .delete
                   })
        _       <- ctx.run(quote {
                     wordTags.filter(link => link.wordId == lift(sourceWordId) && link.tagId == lift(tagId)).delete
                   })
        _       <- ZIO.foreachDiscard(partners)(pruneOrphanMembership(tagId, _))
      } yield ()
    )
    logged(work)(_ => s"wordTags.removeEntry tag=$tagId source=$sourceWordId")
  }

  /** Deletes one `(source, target)` pair from a tag — both `word_tag_pairs` directions — then prunes whichever side the
    * tag now pairs with nothing and did not `import`. A source word with other marked translations keeps those rows,
    * and its membership, because a pair still names it. One transaction; idempotent.
    */
  def removePair(tagId: Long, sourceWordId: Long, targetWordId: Long): Task[Unit] = {
    val work = transaction(
      for {
        _ <- ctx.run(quote {
               wordTagPairs
                 .filter(pair => {
                   pair.tagId == lift(tagId) &&
                   ((pair.wordId == lift(sourceWordId) && pair.translationWordId == lift(targetWordId)) ||
                     (pair.wordId == lift(targetWordId) && pair.translationWordId == lift(sourceWordId)))
                 })
                 .delete
             })
        _ <- pruneOrphanMembership(tagId, sourceWordId)
        _ <- pruneOrphanMembership(tagId, targetWordId)
      } yield ()
    )
    logged(work)(_ => s"wordTagPairs.removePair tag=$tagId source=$sourceWordId target=$targetWordId")
  }

  /** Removes a `word_tags` row that no `word_tag_pairs` row in the tag still names. `force = false` keeps an `imported`
    * membership — it stands as its own "unmatched" row; `force = true` removes it anyway, which is what [[replacePair]]
    * wants for the side it is explicitly discarding. A `ZIO[DataSource, …]` so it joins its caller's transaction.
    */
  private def pruneOrphanMembership(
    tagId: Long,
    wordId: Long,
    force: Boolean = false,
  ): ZIO[DataSource, Throwable, Unit] = {
    val remaining = quote {
      wordTagPairs
        .filter(pair => {
          pair.tagId == lift(tagId) &&
          (pair.wordId == lift(wordId) || pair.translationWordId == lift(wordId))
        })
        .size
    }
    ctx.run(remaining).flatMap { left =>
      ZIO
        .when(left == 0L) {
          if (force)
            ctx.run(quote(wordTags.filter(l => l.wordId == lift(wordId) && l.tagId == lift(tagId)).delete))
          else {
            ctx.run(quote {
              wordTags.filter(l => l.wordId == lift(wordId) && l.tagId == lift(tagId) && !l.imported).delete
            })
          }
        }
        .unit
    }
  }

  /** One tag's rows for the editor: each source word, its marked answer (if any) and the pair's `exact` flag, plus
    * whether the source membership was `imported`. Bidirectional `word_tag_pairs` are collapsed to one row on the
    * `sourceLanguage` side (or the lower word id when the tag has no language pair yet); memberships that no pair names
    * become their own answer-less rows. Ordered by `word_tags.id`, so a bulk import's text order survives.
    */
  def tagEntries(tagId: Long): Task[List[TagEntryRow]] = {
    val assembled = for {
      tagRow      <- run(ctx.run(quote(tags.filter(_.id == lift(tagId))))).map(_.headOption)
      memberships <- run(ctx.run(quote(wordTags.filter(_.tagId == lift(tagId)).sortBy(_.id)(using Ord.asc))))
      pairRows    <- run(ctx.run(quote(wordTagPairs.filter(_.tagId == lift(tagId)))))
      wordIds      = (memberships.map(_.wordId) ++ pairRows.flatMap(p => List(p.wordId, p.translationWordId))).distinct
      wordRows    <- if (wordIds.isEmpty) ZIO.succeed(List.empty[WordRow])
                     else run(ctx.run(quote(words.filter(w => liftQuery(wordIds).contains(w.id)))))
    } yield {
      val byId       = wordRows.map(w => w.id -> w).toMap
      val orderOf    = memberships.zipWithIndex.map { case (m, i) => m.wordId -> i }.toMap
      val importedOf = memberships.map(m => m.wordId -> m.imported).toMap
      val srcLang    = tagRow.flatMap(_.sourceLanguage)

      // One row per undirected pair, keyed on the chosen source side.
      val chosen = pairRows
        .flatMap { pair =>
          (byId.get(pair.wordId), byId.get(pair.translationWordId)) match {
            case (Some(a), Some(b)) =>
              val aIsSource = srcLang match {
                case Some(code) if a.language == code && b.language != code => true
                case Some(code) if b.language == code && a.language != code => false
                case _                                                      => a.id <= b.id
              }
              if (aIsSource) Some(TagEntryRow(a, Some(b), importedOf.getOrElse(a.id, false), pair.exact))
              else None
            case _                  => None
          }
        }
        .distinctBy(row => (row.source.id, row.target.map(_.id)))

      val pairedIds = chosen.flatMap(row => row.source.id :: row.target.toList.map(_.id)).toSet
      val loose     = memberships
        .filterNot(m => pairedIds.contains(m.wordId))
        .flatMap(m => byId.get(m.wordId).map(w => TagEntryRow(w, None, m.imported, exact = false)))

      (chosen ++ loose).sortBy(row =>
        (orderOf.getOrElse(row.source.id, Int.MaxValue), row.target.map(_.id).getOrElse(-1L))
      )
    }
    logged(assembled)(rows => s"tags.entries tag=$tagId rows=${rows.size}")
  }

  /** Seeds the tag and its pairs in one transaction. Inserting the tag and then calling [[pairTranslation]] would leave
    * the tag on a connection whose commit the pair rows cannot see, so the pair rows' `tag_id` foreign key would fail —
    * the whole tag-creation write has to be a single unit of work.
    */
  def createTagWithPairs(
    userId: Long,
    name: String,
    nameNorm: String,
    createdAt: Long,
    pairs: List[(Long, Long)],
  ): Task[TagRow] = {
    val row     = TagRow(0L, userId, name, nameNorm, createdAt)
    val written = transaction(
      for {
        id <- ctx.run(quote(tags.insertValue(lift(row)).returningGenerated(_.id)))
        _  <- ZIO.foreach(pairs) { case (sourceId, targetId) =>
                linkPair(sourceId, id, targetId, createdAt)
              }
      } yield id
    )
    logged(written.map(id => row.copy(id = id))) { tag =>
      s"tags.createWithPairs id=${tag.id} user=$userId pairs=${pairs.size}"
    }
  }

  def unpairTranslation(wordId: Long, tagId: Long, translationWordId: Long): Task[Long] = {
    val forward = quote(
      wordTagPairs
        .filter(pair => {
          pair.wordId == lift(wordId) && pair.tagId == lift(tagId) &&
          pair.translationWordId == lift(translationWordId)
        })
        .delete
    )
    val back    = quote(
      wordTagPairs
        .filter(pair => {
          pair.wordId == lift(translationWordId) && pair.tagId == lift(tagId) &&
          pair.translationWordId == lift(wordId)
        })
        .delete
    )
    val removed = transaction(ctx.run(forward).zipWith(ctx.run(back))(_ + _))
    logged(removed) { rows =>
      s"wordTagPairs.unpair word=$wordId tag=$tagId translation=$translationWordId rows=$rows"
    }
  }

  def pairsFor(userId: Long, wordIds: List[Long]): Task[List[WordTagPairRow]] = {
    if (wordIds.isEmpty)
      ZIO.succeed(Nil)
    else {
      val q = quote {
        wordTagPairs.filter(pair => {
          liftQuery(wordIds).contains(pair.wordId) &&
          tags
            .filter(tag => {
              tag.id == pair.tagId &&
              (tag.userId == lift(userId) ||
                tag.groupId
                  .exists(gid => groupMembers.filter(m => m.groupId == gid && m.userId == lift(userId)).nonEmpty))
            })
            .nonEmpty
        })
      }
      logged(run(ctx.run(q)))(rows => s"wordTagPairs.forWords user=$userId words=${wordIds.size} rows=${rows.size}")
    }
  }

  // -- Bulk import ------------------------------------------------------------------------------

  def insertWords(rows: List[WordRow]): Task[Long] = {
    if (rows.isEmpty)
      ZIO.succeed(0L)
    else {
      // Columns named one by one rather than `insertValue`, so the generated `id` is left out without asking for it
      // back: a batch that returns generated keys is not something both drivers handle the same way.
      val q = quote {
        liftQuery(rows).foreach(row => {
          words.insert(
            _.language      -> row.language,
            _.text          -> row.text,
            _.textNorm      -> row.textNorm,
            _.partOfSpeech  -> row.partOfSpeech,
            _.gender        -> row.gender,
            _.frequencyRank -> row.frequencyRank,
            _.source        -> row.source,
            _.createdBy     -> row.createdBy,
            _.createdAt     -> row.createdAt,
            _.textSearch    -> row.textSearch,
          )
        })
      }
      logged(run(ctx.run(q)).map(_.sum))(inserted => s"words.insertBatch rows=${rows.size} inserted=$inserted")
    }
  }

  def insertTranslations(rows: List[WordTranslationRow]): Task[Long] = {
    if (rows.isEmpty)
      ZIO.succeed(0L)
    else {
      val q = quote {
        liftQuery(rows).foreach(row => {
          translations.insert(
            _.sourceWordId -> row.sourceWordId,
            _.targetWordId -> row.targetWordId,
            _.origin       -> row.origin,
            _.createdBy    -> row.createdBy,
            _.createdAt    -> row.createdAt,
          )
        })
      }
      logged(run(ctx.run(q)).map(_.sum)) { inserted =>
        s"wordTranslations.insertBatch rows=${rows.size} inserted=$inserted"
      }
    }
  }

  def findWordsByKeys(language: String, textNorms: List[String]): Task[List[WordRow]] = {
    if (textNorms.isEmpty)
      ZIO.succeed(Nil)
    else {
      val q = quote {
        words.filter(word => word.language == lift(language) && liftQuery(textNorms).contains(word.textNorm))
      }
      logged(run(ctx.run(q)))(rows => s"words.findByKeys lang=$language asked=${textNorms.size} rows=${rows.size}")
    }
  }

  def findWordsByLengthRange(language: String, minLength: Int, maxLength: Int): Task[List[WordRow]] = {
    // Quill's own `.length` on a quoted String lowers to `LEN(...)`, a SQL Server spelling neither SQLite nor
    // Postgres has — `LENGTH(...)` is the one function both dialects agree on.
    val q = quote {
      words.filter(word => {
        val len = infix"LENGTH(${word.textNorm})".as[Int]
        word.language == lift(language) && len >= lift(minLength) && len <= lift(maxLength)
      })
    }
    logged(run(ctx.run(q))) { rows =>
      s"words.findByLengthRange lang=$language range=[$minLength,$maxLength] rows=${rows.size}"
    }
  }

  def existingTranslationPairs(sourceWordIds: List[Long]): Task[List[(Long, Long)]] = {
    if (sourceWordIds.isEmpty)
      ZIO.succeed(Nil)
    else {
      val q = quote {
        translations
          .filter(edge => liftQuery(sourceWordIds).contains(edge.sourceWordId))
          .map(edge => (edge.sourceWordId, edge.targetWordId))
      }
      logged(run(ctx.run(q)))(rows => s"wordTranslations.existingPairs sources=${sourceWordIds.size} rows=${rows.size}")
    }
  }

  def insertForms(rows: List[WordFormRow]): Task[Long] = {
    if (rows.isEmpty)
      ZIO.succeed(0L)
    else {
      val q       = quote {
        liftQuery(rows).foreach(row => {
          wordForms.insert(
            _.lemmaWordId -> row.lemmaWordId,
            _.formWordId  -> row.formWordId,
            _.relation    -> row.relation,
            _.createdAt   -> row.createdAt,
          )
        })
      }
      // `words.is_form` is derived from the rows just written, so it is set in the same transaction: a reader must
      // never see a `word_forms` row whose form word still counts as a main word. One `UPDATE ... WHERE id IN (...)`
      // for the whole batch, which is what keeps this affordable on `DictionaryImport`'s bulk path.
      val formIds = rows.map(_.formWordId).distinct
      val flag    = quote {
        words.filter(row => liftQuery(formIds).contains(row.id) && !row.isForm).update(_.isForm -> true)
      }
      val written = transaction(ctx.run(q).map(_.sum) <* ctx.run(flag))
      logged(written)(inserted => s"wordForms.insertBatch rows=${rows.size} inserted=$inserted")
    }
  }

  def existingFormRelations(lemmaWordIds: List[Long]): Task[List[(Long, Long, String)]] = {
    if (lemmaWordIds.isEmpty)
      ZIO.succeed(Nil)
    else {
      val q = quote {
        wordForms
          .filter(row => liftQuery(lemmaWordIds).contains(row.lemmaWordId))
          .map(row => (row.lemmaWordId, row.formWordId, row.relation))
      }
      logged(run(ctx.run(q)))(rows => s"wordForms.existingRelations lemmas=${lemmaWordIds.size} rows=${rows.size}")
    }
  }

  def formsOf(lemmaWordId: Long): Task[List[WordFormRow]] = {
    val q = quote(wordForms.filter(_.lemmaWordId == lift(lemmaWordId)))
    logged(run(ctx.run(q)))(rows => s"wordForms.formsOf lemma=$lemmaWordId rows=${rows.size}")
  }

  def lemmaOf(formWordId: Long): Task[List[WordFormRow]] = {
    val q = quote(wordForms.filter(_.formWordId == lift(formWordId)))
    logged(run(ctx.run(q)))(rows => s"wordForms.lemmaOf form=$formWordId rows=${rows.size}")
  }

  def lemmaContextOf(formWordIds: List[Long]): Task[List[(WordFormRow, WordRow)]] = {
    if (formWordIds.isEmpty)
      ZIO.succeed(Nil)
    else {
      val q = quote {
        wordForms
          .join(words)
          .on((form, word) => form.lemmaWordId == word.id)
          .filter { case (form, _) => liftQuery(formWordIds).contains(form.formWordId) }
      }
      logged(run(ctx.run(q)))(rows => s"wordForms.lemmaContextOf forms=${formWordIds.size} rows=${rows.size}")
    }
  }

  def formsContextOf(lemmaWordIds: List[Long]): Task[List[(WordFormRow, WordRow)]] = {
    if (lemmaWordIds.isEmpty)
      ZIO.succeed(Nil)
    else {
      val q = quote {
        wordForms
          .join(words)
          .on((form, word) => form.formWordId == word.id)
          .filter { case (form, _) => liftQuery(lemmaWordIds).contains(form.lemmaWordId) }
      }
      logged(run(ctx.run(q)))(rows => s"wordForms.formsContextOf lemmas=${lemmaWordIds.size} rows=${rows.size}")
    }
  }

  // No `HAVING` here: Quill's Dynamic Query has no clause for it in this codebase's usage, and `listTags`' own
  // count-then-filter-in-Scala is the established shape for it -- `word_forms` is small enough that pulling every
  // edge for an admin-only, on-demand diagnostic costs nothing worth a second query.
  def formFanOutAnomalies(threshold: Int): Task[List[(WordRow, String, Long)]] = {
    val edges   = quote(wordForms.map(row => (row.formWordId, row.relation, row.lemmaWordId)))
    val flagged = for {
      rows      <- run(ctx.run(edges))
      counted    = rows
                     .groupBy { case (formWordId, relation, _) => (formWordId, relation) }
                     .view
                     .mapValues { group => group.map { case (_, _, lemmaWordId) => lemmaWordId }.distinct.size.toLong }
                     .filter { case (_, count) => count > threshold }
                     .toList
      formIds    = counted.map { case ((formWordId, _), _) => formWordId }.distinct
      formWords <- if (formIds.isEmpty)
                     ZIO.succeed(Nil)
                   else
                     run(ctx.run(quote(words.filter(word => liftQuery(formIds).contains(word.id)))))
      byId       = formWords.map(word => word.id -> word).toMap
    } yield counted.flatMap { case ((formWordId, relation), count) =>
      byId.get(formWordId).map(word => (word, relation, count))
    }
    logged(flagged)(rows => s"wordForms.fanOutAnomalies threshold=$threshold rows=${rows.size}")
  }

  def deleteWordForms(formWordId: Long, relation: String): Task[Long] = {
    val q         = quote(
      wordForms.filter(row => row.formWordId == lift(formWordId) && row.relation == lift(relation)).delete
    )
    // The word goes back to being a main word only once *no* relation names it as a form any more — deleting one
    // `(form, relation)` pair may well leave others. Counted and cleared as two statements inside the delete's own
    // transaction rather than one `UPDATE` with a correlated `NOT EXISTS`, which is a shape nothing else here renders.
    val remaining = quote(wordForms.filter(row => row.formWordId == lift(formWordId)).size)
    val clear     = quote(words.filter(row => row.id == lift(formWordId)).update(_.isForm -> false))
    val deleted   = transaction(
      for {
        rows <- ctx.run(q)
        left <- ctx.run(remaining)
        _    <- ZIO.when(left == 0L)(ctx.run(clear))
      } yield rows
    )
    logged(deleted)(rows => s"wordForms.delete form=$formWordId relation=$relation rows=$rows")
  }

  def countWords: Task[Long] = {
    logged(run(ctx.run(quote(words.size))))(count => s"words.count count=$count")
  }

  def countTranslations: Task[Long] = {
    logged(run(ctx.run(quote(translations.size))))(count => s"wordTranslations.count count=$count")
  }

  def countTags: Task[Long] = {
    logged(run(ctx.run(quote(tags.size))))(count => s"tags.count count=$count")
  }

  def countWordForms: Task[Long] = {
    logged(run(ctx.run(quote(wordForms.size))))(count => s"wordForms.count count=$count")
  }
}
