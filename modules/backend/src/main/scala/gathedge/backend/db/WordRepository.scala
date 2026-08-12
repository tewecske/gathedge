package gathedge.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import gathedge.shared.dto.WordSort
import zio.*

import javax.sql.DataSource

/** The vocabulary's four tables — `words`, `word_translations`, `tags`, `word_tags` — in one repository.
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

  /** One page of the listing. `search` is matched as a **prefix** of the normalised text, which is what an autocomplete
    * needs and what `idx_words_lookup` answers.
    *
    * @param tagId
    *   narrows to words carrying one tag; @param taggedBy narrows to words the account has tagged at all.
    */
  def listPage(
    offset: Int,
    limit: Int,
    language: Option[String],
    search: Option[String],
    partOfSpeech: Option[String],
    tagId: Option[Long],
    taggedBy: Option[Long],
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
  ): Task[Long]

  /** Every translation of those words into one language, as the edge and the word it points at. One query for a whole
    * page.
    */
  def translationsOf(wordIds: List[Long], targetLanguage: String): Task[List[(WordTranslationRow, WordRow)]]

  /** Every translation of one word, in any language, for the detail screen. */
  def allTranslationsOf(wordId: Long): Task[List[(WordTranslationRow, WordRow)]]

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

  def listTags(userId: Long): Task[List[(TagRow, Long)]]
  def findTag(userId: Long, nameNorm: String): Task[Option[TagRow]]
  def findTagById(id: Long): Task[Option[TagRow]]
  def insertTag(userId: Long, name: String, nameNorm: String, createdAt: Long): Task[TagRow]
  def deleteTag(id: Long, userId: Long): Task[Long]

  /** Idempotent: tagging a word that already carries the tag is nothing to do, not a conflict. That is what lets the
    * listing's one-click toggle be safe to double-click.
    */
  def tagWord(wordId: Long, tagId: Long, createdAt: Long): Task[Unit]

  def untagWord(wordId: Long, tagId: Long): Task[Long]

  /** Which of those words the account has tagged, and with which of its tags. One query per page. */
  def tagsFor(userId: Long, wordIds: List[Long]): Task[List[WordTagRow]]

  /** The account's tags on one word, for the detail screen. */
  def tagsOfWord(userId: Long, wordId: Long): Task[List[TagRow]]

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

  /** Which `(source, target)` pairs already exist among those source words, so a re-run inserts nothing twice. */
  def existingTranslationPairs(sourceWordIds: List[Long]): Task[List[(Long, Long)]]

  def countWords: Task[Long]
  def countTranslations: Task[Long]
  def countTags: Task[Long]
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

  def listPage(
    offset: Int,
    limit: Int,
    language: Option[String],
    search: Option[String],
    partOfSpeech: Option[String],
    tagId: Option[Long],
    taggedBy: Option[Long],
    sort: Option[String],
    descending: Boolean,
  ): RIO[WordRepository, List[WordRow]] = {
    ZIO.serviceWithZIO[WordRepository](
      _.listPage(offset, limit, language, search, partOfSpeech, tagId, taggedBy, sort, descending)
    )
  }

  def countMatching(
    language: Option[String],
    search: Option[String],
    partOfSpeech: Option[String],
    tagId: Option[Long],
    taggedBy: Option[Long],
  ): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.countMatching(language, search, partOfSpeech, tagId, taggedBy))

  def translationsOf(
    wordIds: List[Long],
    targetLanguage: String,
  ): RIO[WordRepository, List[(WordTranslationRow, WordRow)]] =
    ZIO.serviceWithZIO[WordRepository](_.translationsOf(wordIds, targetLanguage))

  def allTranslationsOf(wordId: Long): RIO[WordRepository, List[(WordTranslationRow, WordRow)]] =
    ZIO.serviceWithZIO[WordRepository](_.allTranslationsOf(wordId))

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

  def listTags(userId: Long): RIO[WordRepository, List[(TagRow, Long)]] =
    ZIO.serviceWithZIO[WordRepository](_.listTags(userId))

  def findTag(userId: Long, nameNorm: String): RIO[WordRepository, Option[TagRow]] =
    ZIO.serviceWithZIO[WordRepository](_.findTag(userId, nameNorm))

  def findTagById(id: Long): RIO[WordRepository, Option[TagRow]] =
    ZIO.serviceWithZIO[WordRepository](_.findTagById(id))

  def insertTag(userId: Long, name: String, nameNorm: String, createdAt: Long): RIO[WordRepository, TagRow] =
    ZIO.serviceWithZIO[WordRepository](_.insertTag(userId, name, nameNorm, createdAt))

  def deleteTag(id: Long, userId: Long): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.deleteTag(id, userId))

  def tagWord(wordId: Long, tagId: Long, createdAt: Long): RIO[WordRepository, Unit] =
    ZIO.serviceWithZIO[WordRepository](_.tagWord(wordId, tagId, createdAt))

  def untagWord(wordId: Long, tagId: Long): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.untagWord(wordId, tagId))

  def tagsFor(userId: Long, wordIds: List[Long]): RIO[WordRepository, List[WordTagRow]] =
    ZIO.serviceWithZIO[WordRepository](_.tagsFor(userId, wordIds))

  def tagsOfWord(userId: Long, wordId: Long): RIO[WordRepository, List[TagRow]] =
    ZIO.serviceWithZIO[WordRepository](_.tagsOfWord(userId, wordId))

  def insertWords(rows: List[WordRow]): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.insertWords(rows))

  def insertTranslations(rows: List[WordTranslationRow]): RIO[WordRepository, Long] =
    ZIO.serviceWithZIO[WordRepository](_.insertTranslations(rows))

  def findWordsByKeys(language: String, textNorms: List[String]): RIO[WordRepository, List[WordRow]] =
    ZIO.serviceWithZIO[WordRepository](_.findWordsByKeys(language, textNorms))

  def existingTranslationPairs(sourceWordIds: List[Long]): RIO[WordRepository, List[(Long, Long)]] =
    ZIO.serviceWithZIO[WordRepository](_.existingTranslationPairs(sourceWordIds))

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
  private inline def tags         = quote(querySchema[TagRow]("tags"))
  private inline def wordTags     = quote(querySchema[WordTagRow]("word_tags"))

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

  /** The prefix pattern behind the search box, or `None` when it is empty.
    *
    * A prefix rather than a substring: it is what an autocomplete means, and it is the shape `idx_words_lookup`
    * answers. Text is stored lowercased, so lowercasing the needle is the whole of the case-insensitivity, with no
    * `lower()` for the two dialects to disagree about — the rule `UserRepository.emailPattern` follows.
    */
  private def searchPattern(search: Option[String]): Option[String] = {
    search.map(_.trim.toLowerCase).filter(_.nonEmpty).map(needle => s"$needle%")
  }

  /** True when the account has this word under any of its own tags. */
  private inline def taggedByUser = quote { (wordId: Long, userId: Long) =>
    wordTags
      .filter(link =>
        link.wordId == wordId && tags.filter(tag => tag.id == link.tagId && tag.userId == userId).nonEmpty
      )
      .nonEmpty
  }

  /** The narrowing [[listPage]] and [[countMatching]] share, so the total counts the set the page is cut from. */
  private def matching(
    language: Option[String],
    search: Option[String],
    partOfSpeech: Option[String],
    tagId: Option[Long],
    taggedBy: Option[Long],
  ): DynamicQuery[WordRow] = {
    dynamicQuerySchema[WordRow]("words")
      .filterOpt(language)((word, value) => quote(word.language == unquote(value)))
      .filterOpt(searchPattern(search))((word, pattern) => quote(word.textNorm.like(unquote(pattern))))
      .filterOpt(partOfSpeech)((word, value) => quote(word.partOfSpeech == unquote(value)))
      .filterOpt(tagId)((word, value) =>
        quote(wordTags.filter(link => link.wordId == word.id && link.tagId == unquote(value)).nonEmpty)
      )
      .filterOpt(taggedBy)((word, userId) => quote(taggedByUser(word.id, unquote(userId))))
  }

  /** The `dto.WordSort` vocabulary as an `ORDER BY`, defaulting to the listing's own order: commonest first.
    *
    * That default is what makes a two-letter search useful — a hundred matches with the everyday word at the top — and
    * it is why `frequency_rank` carries a large sentinel rather than NULL for the words nobody has ranked.
    */
  private def ordered(
    query: DynamicQuery[WordRow],
    sort: Option[String],
    descending: Boolean,
  ): DynamicQuery[WordRow] = {
    sort match {
      case Some(WordSort.text) =>
        query.sortBy(_.textNorm)(using ordering(descending))
      case Some(WordSort.pos)  =>
        query.sortBy(_.partOfSpeech)(using ordering(descending))
      case Some(WordSort.rank) =>
        query.sortBy(_.frequencyRank)(using ordering(descending))
      case _                   =>
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
    sort: Option[String],
    descending: Boolean,
  ): Task[List[WordRow]] = {
    val page = ordered(matching(language, search, partOfSpeech, tagId, taggedBy), sort, descending)
      .drop(offset)
      .take(limit)
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
  ): Task[Long] = {
    val q = matching(language, search, partOfSpeech, tagId, taggedBy).size
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

  def listTags(userId: Long): Task[List[(TagRow, Long)]] = {
    val ownTags = quote(tags.filter(_.userId == lift(userId)).sortBy(_.nameNorm)(using Ord.asc))
    val counts  = quote {
      wordTags
        .filter(link => tags.filter(tag => tag.id == link.tagId && tag.userId == lift(userId)).nonEmpty)
        .groupBy(_.tagId)
        .map { case (tagId, links) => (tagId, links.size) }
    }
    val listed  = for {
      rows   <- run(ctx.run(ownTags))
      byTag  <- run(ctx.run(counts))
      counted = byTag.toMap
    } yield rows.map(tag => (tag, counted.getOrElse(tag.id, 0L)))
    logged(listed)(rows => s"tags.list user=$userId rows=${rows.size}")
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

  def deleteTag(id: Long, userId: Long): Task[Long] = {
    val q = quote(tags.filter(tag => tag.id == lift(id) && tag.userId == lift(userId)).delete)
    logged(run(ctx.run(q)))(rows => s"tags.delete id=$id user=$userId rows=$rows")
  }

  def tagWord(wordId: Long, tagId: Long, createdAt: Long): Task[Unit] = {
    val existing = quote(wordTags.filter(link => link.wordId == lift(wordId) && link.tagId == lift(tagId)))
    val row      = WordTagRow(0L, wordId, tagId, createdAt)
    val linked   = run(ctx.run(existing)).flatMap { found =>
      if (found.nonEmpty)
        ZIO.succeed(false)
      else
        run(ctx.run(quote(wordTags.insertValue(lift(row)).returningGenerated(_.id)))).as(true)
    }
    logged(linked)(added => s"wordTags.tag word=$wordId tag=$tagId added=$added").unit
  }

  def untagWord(wordId: Long, tagId: Long): Task[Long] = {
    val q = quote(wordTags.filter(link => link.wordId == lift(wordId) && link.tagId == lift(tagId)).delete)
    logged(run(ctx.run(q)))(rows => s"wordTags.untag word=$wordId tag=$tagId rows=$rows")
  }

  def tagsFor(userId: Long, wordIds: List[Long]): Task[List[WordTagRow]] = {
    if (wordIds.isEmpty)
      ZIO.succeed(Nil)
    else {
      val q = quote {
        wordTags.filter(link => {
          liftQuery(wordIds).contains(link.wordId) &&
            tags.filter(tag => tag.id == link.tagId && tag.userId == lift(userId)).nonEmpty
        })
      }
      logged(run(ctx.run(q)))(rows => s"wordTags.forWords user=$userId words=${wordIds.size} rows=${rows.size}")
    }
  }

  def tagsOfWord(userId: Long, wordId: Long): Task[List[TagRow]] = {
    val q = quote {
      tags.filter(tag => {
        tag.userId == lift(userId) &&
          wordTags.filter(link => link.tagId == tag.id && link.wordId == lift(wordId)).nonEmpty
      })
    }
    logged(run(ctx.run(q)))(rows => s"tags.ofWord user=$userId word=$wordId rows=${rows.size}")
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

  def countWords: Task[Long] = {
    logged(run(ctx.run(quote(words.size))))(count => s"words.count count=$count")
  }

  def countTranslations: Task[Long] = {
    logged(run(ctx.run(quote(translations.size))))(count => s"wordTranslations.count count=$count")
  }

  def countTags: Task[Long] = {
    logged(run(ctx.run(quote(tags.size))))(count => s"tags.count count=$count")
  }
}
