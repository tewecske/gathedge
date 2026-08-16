package gathedge.backend.service

import gathedge.backend.config.{AppConfig, QuotaSection}
import gathedge.backend.db.{TagRow, WordRepository, WordRow, WordTagPairRow, WordTranslationRow}
import gathedge.shared.domain.{Gender, PartOfSpeech, Tag, Word, WordLanguage}
import gathedge.shared.dto.{
  CreateWordRequest,
  NewTranslation,
  PairSelectionResponse,
  TagResponse,
  TaggedPair,
  TranslationEntry,
  TranslationOption,
  WordDetail,
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
  * caller owns — but writing through one is not: [[tagWord]], [[untagWord]], [[selectPair]] and [[deselectPair]] all go
  * through [[requireOwnTag]], so a reader may filter or [[copyTag]] somebody else's tag but never attach a word to it.
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
}

object WordService {

  /** How many translations a listing row carries. The screen shows a line, not a dictionary entry; the detail page is
    * where the rest of them are.
    */
  val translationsPerRow = 3

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
    sort: Option[String],
    descending: Boolean,
    reader: Option[Long],
  ): URIO[WordService, WordPage] = {
    ZIO.serviceWithZIO[WordService](
      _.list(page, pageSize, language, search, partOfSpeech, tagId, mine, target, sort, descending, reader)
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

  val live: URLayer[WordRepository & AppConfig, WordService] = {
    ZLayer.fromFunction((repo: WordRepository, config: AppConfig) => WordServiceLive(repo, config.quotas))
  }

  /** What a word row a user typed is marked as, against the dictionary's own. */
  val userSource       = "user"
  val dictionarySource = "dictionary"

  /** Origins a translation edge can have. `pivot` is a German–Hungarian pair inferred through a shared English sense
    * rather than asserted anywhere, and is marked so a screen can say so.
    */
  val dictionaryOrigin = "dictionary"
  val pivotOrigin      = "pivot"
  val userOrigin       = "user"

  /** The rank a word nobody has ranked gets. Matches the column default: a sentinel rather than NULL, because the two
    * dialects put NULLs in different places in an `ORDER BY` and this column decides the listing's own order.
    */
  val unrankedFrequency = 999999999
}

final case class WordServiceLive(repo: WordRepository, quotas: QuotaSection) extends WordService {

  private def toDomain(row: WordRow): Word = {
    Word(
      row.id,
      WordLanguage.fromString(row.language).getOrElse(WordLanguage.En),
      row.text,
      PartOfSpeech.fromString(row.partOfSpeech).getOrElse(PartOfSpeech.Other),
      Gender.fromColumn(row.gender),
    )
  }

  private def toTag(row: TagRow, wordCount: Long, ownedByMe: Boolean): Tag = {
    Tag(row.id, row.name, wordCount, ownedByMe)
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
        rows         <- repo
                          .listPage(
                            offset,
                            pageSize,
                            language.map(WordLanguage.code),
                            search,
                            partOfSpeech.map(PartOfSpeech.code),
                            tagId,
                            taggedBy,
                            sort,
                            descending,
                          )
                          .orDie
        total        <- repo
                          .countMatching(
                            language.map(WordLanguage.code),
                            search,
                            partOfSpeech.map(PartOfSpeech.code),
                            tagId,
                            taggedBy,
                          )
                          .orDie
        ids           = rows.map(_.id)
        // Three batch queries for the whole page rather than three per row.
        translations <- repo.translationsOf(ids, WordLanguage.code(target)).orDie
        links        <- ZIO.foreach(reader)(userId => repo.tagsFor(userId, ids)).map(_.toList.flatten).orDie
        marked       <- ZIO.foreach(reader)(userId => repo.pairsFor(userId, ids)).map(_.toList.flatten).orDie
        byWord        = sortTranslations(translations).groupBy { case (edge, _) => edge.sourceWordId }
        tagsByWord    = links.groupBy(_.wordId)
        pairsByWord   = marked.groupBy(_.wordId)
      } yield WordPage(
        items = rows.map { row =>
          val rowPairs = pairsByWord.getOrElse(row.id, Nil)
          val offered  = translationsShown(byWord.getOrElse(row.id, Nil), rowPairs)
          val shownIds = offered.map(_.id).toSet
          WordSummary(
            word = toDomain(row),
            translations = offered.map(word => TranslationOption(word.id, Word.display(word))),
            tagIds = tagsByWord.getOrElse(row.id, Nil).map(_.tagId),
            // Only the marks this row can render: one whose answer is in a language the listing is not translating
            // into would otherwise ship to a client with no chip to put it on.
            pairs = rowPairs
              .filter(pair => shownIds.contains(pair.translationWordId))
              .map(pair => TaggedPair(pair.tagId, pair.translationWordId))
              .distinct,
          )
        },
        total = total,
      )
    }
  }

  private def detailOf(row: WordRow, reader: Option[Long]): UIO[WordDetail] = {
    for {
      translations <- repo.allTranslationsOf(row.id).orDie
      tags         <- ZIO.foreach(reader)(userId => repo.tagsOfWord(userId, row.id)).map(_.toList.flatten).orDie
      marked       <- ZIO.foreach(reader)(userId => repo.pairsFor(userId, List(row.id))).map(_.toList.flatten).orDie
      // Carried with a count of zero: the detail screen renders these as chips on one word, where "lesson1 (37)"
      // would be answering a question nobody asked. The tag bar gets the real counts from `listTags`. `tagsOfWord`
      // only ever answers the reader's own tags, so every one of them is owned.
      counted       = tags.map(tag => toTag(tag, 0L, ownedByMe = true))
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

  def create(request: CreateWordRequest, userId: Long): IO[WordFailure, WordDetail] = {
    for {
      row     <- ensure(request.language, request.text, request.partOfSpeech, request.gender, userId)
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
      _       <- ZIO.when(pending.nonEmpty)(
                   // `pairTranslation` writes one row per direction, so each genuinely new mark adds two.
                   repo.countPairsOwnedBy(userId).orDie.flatMap(pairQuota(_, pending.size.toLong * 2L)).unit
                 )
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
    repo
      .listTags(userId)
      .orDie
      .map(rows => Tag.sorted(rows.map { case (row, count, ownedByMe) => toTag(row, count, ownedByMe) }))
  }

  /** The name-half of creating a tag, shared by [[createTag]] and [[copyTag]]: valid, and not already the caller's,
    * before either goes anywhere near a quota or a write.
    */
  private def prepareTagName(name: String, userId: Long): IO[WordFailure, (String, String)] = {
    for {
      valid    <- ZIO
                    .fromEither(Validation.validateTagName(name))
                    .mapError(error => WordFailure.ValidationError(Map("name" -> error)))
      normal    = Tag.normalize(valid)
      existing <- repo.findTag(userId, normal).orDie
      _        <- ZIO.when(existing.isDefined)(ZIO.fail(WordFailure.DuplicateTag))
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
    } yield TagResponse(toTag(row, 0L, ownedByMe = true), warning)
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
      TagResponse(toTag(row, wordCount, ownedByMe = true), tagWarning.orElse(pairWarning))
    }
  }

  def deleteTag(tagId: Long, userId: Long): IO[WordFailure, Unit] = {
    repo
      .deleteTag(tagId, userId)
      .orDie
      .flatMap(rows => ZIO.when(rows == 0L)(ZIO.fail(WordFailure.TagNotFound)))
      .unit
  }

  /** Every tag operation checks the tag belongs to the caller, and answers `TagNotFound` when it does not: whose tag a
    * given id is is not something an account may learn by trying.
    */
  private def requireOwnTag(tagId: Long, userId: Long): IO[WordFailure, TagRow] = {
    repo
      .findTagById(tagId)
      .orDie
      .someOrFail(WordFailure.TagNotFound)
      .filterOrFail(_.userId == userId)(WordFailure.TagNotFound)
  }

  def tagWord(wordId: Long, tagId: Long, userId: Long): IO[WordFailure, Unit] = {
    for {
      _   <- requireOwnTag(tagId, userId)
      _   <- repo.findWordById(wordId).orDie.someOrFail(WordFailure.NotFound)
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _   <- repo.tagWord(wordId, tagId, now).orDie
    } yield ()
  }

  def untagWord(wordId: Long, tagId: Long, userId: Long): IO[WordFailure, Unit] = {
    for {
      _ <- requireOwnTag(tagId, userId)
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
      _       <- requireOwnTag(tagId, userId)
      _       <- repo.findWordById(wordId).orDie.someOrFail(WordFailure.NotFound)
      _       <- requireTranslationOf(wordId, translationWordId)
      already <- pairAlreadyMarked(userId, wordId, tagId, translationWordId)
      // `pairTranslation` writes one row per direction, so a genuinely new mark adds two.
      warning <- if (already) ZIO.succeed(None) else repo.countPairsOwnedBy(userId).orDie.flatMap(pairQuota(_, 2))
      _       <- pairInTag(wordId, tagId, translationWordId)
    } yield PairSelectionResponse(warning)
  }

  def deselectPair(wordId: Long, tagId: Long, translationWordId: Long, userId: Long): IO[WordFailure, Unit] = {
    for {
      _ <- requireOwnTag(tagId, userId)
      // Unmarking something that is not marked is nothing to do, not a failure — the rule `untagWord` follows, and what
      // lets the chip be safe to double-click.
      _ <- repo.unpairTranslation(wordId, tagId, translationWordId).orDie
    } yield ()
  }
}
