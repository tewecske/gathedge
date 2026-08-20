package gathedge.backend.tools

import gathedge.backend.config.AppConfig
import gathedge.backend.db.{
  DataSourceFactory,
  DbDialect,
  FlywayMigrator,
  WordFormRow,
  WordRepository,
  WordRow,
  WordTranslationRow,
}
import gathedge.backend.service.WordService
import gathedge.shared.domain.{Gender, PartOfSpeech, WordLanguage}
import zio.*
import zio.stream.{ZPipeline, ZStream}

import java.io.{FileInputStream, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import java.util.concurrent.TimeUnit

import javax.sql.DataSource

import WiktextractParser.{ParsedForm, ParsedPair, ParsedWord}

/** Loads the shared dictionary: English, German and Hungarian words with their parts of speech, German gender, and the
  * translations between them.
  *
  * Run it, rather than a migration: a migration is schema, and this is a few hundred thousand rows of somebody else's
  * data that a deployment may want more or less of. It is idempotent, so a second run against a populated database
  * inserts nothing.
  *
  * {{{
  * # the committed sample — a few dozen everyday words, enough for the dev stack and the e2e suite
  * sbt "backend/runMain gathedge.backend.tools.DictionaryImport --seed"
  *
  * # the real thing, from https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz (2.6 GB gzipped)
  * sbt "backend/runMain gathedge.backend.tools.DictionaryImport --raw ~/raw-wiktextract-data.jsonl.gz --limit 50000 \
  *        --frequencies data/frequency"
  *
  * # rebuild the committed sample from a dump
  * sbt "backend/runMain gathedge.backend.tools.DictionaryImport --raw ~/raw...gz --limit 2000 --export data/dictionary/seed.tsv"
  *
  * # load a seed built elsewhere (`--seed <path>`, `.gz` understood) — how a deployment gets the real
  * # dictionary without the 2.6 GB dump ever reaching the server; scripts/build-dictionary-seed.sh
  * # produces the file
  * gathedge-dictionary-import --seed /tmp/seed-20000.tsv.gz
  * }}}
  *
  * '''Licence.''' The data is extracted from Wiktionary and is CC BY-SA 4.0. It carries an attribution requirement that
  * the word list page satisfies (`ui.words.attribution`), and share-alike terms that apply to the imported tables.
  */
object DictionaryImport extends ZIOAppDefault {

  override val bootstrap: ZLayer[Any, Any, Unit] = AppConfig.configProvider

  /** How many rows go to the database in one statement batch. Large enough that the round trips disappear, small enough
    * that a failure is not a half-hour of work lost.
    */
  private val batchSize = 500

  /** Where a bare `--seed` reads from: relative to the repository root, which is what `Compile / run / baseDirectory`
    * in build.sbt pins it to. A deployment has no repository, so `--seed <path>` is how it names a seed built
    * elsewhere.
    */
  private val defaultSeedPath = "data/dictionary/seed.tsv"

  final case class Options(
    seed: Boolean = false,
    seedPath: Option[String] = None,
    raw: Option[String] = None,
    limit: Int = 50000,
    frequencies: Option[String] = None,
    exportTo: Option[String] = None,
    languages: Set[WordLanguage] = WordLanguage.all.toSet,
  )

  def parseArgs(args: List[String]): Either[String, Options] = {
    def loop(rest: List[String], options: Options): Either[String, Options] = {
      rest match {
        case Nil                                                =>
          Right(options)
        // The path is optional, so the next token has to be looked at: anything beginning with `--`
        // is the following option rather than this one's argument, which is what keeps
        // `--seed --raw dump.gz` the mutually-exclusive error it has always been.
        case "--seed" :: path :: tail if !path.startsWith("--") =>
          loop(tail, options.copy(seed = true, seedPath = Some(path)))
        case "--seed" :: tail                                   =>
          loop(tail, options.copy(seed = true))
        case "--raw" :: path :: tail                            =>
          loop(tail, options.copy(raw = Some(path)))
        case "--limit" :: value :: tail                         =>
          value.toIntOption match {
            case None        =>
              Left(s"--limit needs a number, got '$value'")
            case Some(limit) =>
              loop(tail, options.copy(limit = limit))
          }
        case "--frequencies" :: path :: tail                    =>
          loop(tail, options.copy(frequencies = Some(path)))
        case "--export" :: path :: tail                         =>
          loop(tail, options.copy(exportTo = Some(path)))
        case "--languages" :: value :: tail                     =>
          val parsed = value.split(',').toList.flatMap(code => WordLanguage.fromString(code.trim))
          if (parsed.isEmpty)
            Left(s"--languages needs codes out of en,de,hu; got '$value'")
          else
            loop(tail, options.copy(languages = parsed.toSet))
        case unknown :: _                                       =>
          Left(s"Unrecognised argument '$unknown'")
      }
    }
    loop(args, Options()).flatMap { options =>
      if (options.seed == options.raw.isDefined)
        Left("Give exactly one of --seed and --raw <path>")
      else
        Right(options)
    }
  }

  /** Everything one import holds in memory before any of it reaches the database.
    *
    * Deliberately in memory: the whole point of `--limit` is that the result is bounded — tens of thousands of words
    * per language, not the dump's millions — and having the lot at once is what makes the German–Hungarian pivot
    * possible at all.
    */
  final case class Collected(words: Map[ParsedWord, Int], pairs: List[ParsedPair], forms: List[ParsedForm]) {

    def withWord(word: ParsedWord, rank: Int): Collected = {
      // The best rank wins: a word reached both as a headword and as somebody's translation keeps the one that says
      // it is common, because that is what decides where it lands in a search.
      val existing = words.get(word)
      if (existing.exists(_ <= rank))
        this
      else
        copy(words = words.updated(word, rank))
    }
  }

  object Collected {
    val empty: Collected = Collected(Map.empty, Nil, Nil)
  }

  // -- Reading a wiktextract dump ---------------------------------------------------------------

  /** Corpus frequency, as `hermitdave/FrequencyWords` publishes it: one `word count` pair per line, commonest first.
    * The rank is the line number, so it needs no parsing of the counts.
    *
    * Optional. Without it every word is unranked and the listing falls back to alphabetical within a search, which is
    * usable but much worse — the point of a ranked dictionary is that typing `hau` puts `Haus` above `Haubitze`.
    */
  private def readFrequencies(directory: String, language: WordLanguage): Task[Map[String, Int]] = {
    val path = s"$directory/${WordLanguage.code(language)}_50k.txt"
    ZIO
      .attemptBlocking {
        val source = scala.io.Source.fromFile(path, StandardCharsets.UTF_8.name)
        try {
          source
            .getLines()
            .map(_.trim.takeWhile(_ != ' '))
            .filter(_.nonEmpty)
            .zipWithIndex
            .map { case (word, index) => (word.toLowerCase, index + 1) }
            .toMap
        } finally source.close()
      }
      .catchAll(error => {
        ZIO
          .logWarning(
            s"No frequency list at $path (${error.getMessage}); ${WordLanguage.code(language)} stays unranked"
          )
          .as(Map.empty[String, Int])
      })
  }

  private def rankOf(frequencies: Map[WordLanguage, Map[String, Int]], word: ParsedWord): Int = {
    frequencies
      .getOrElse(word.language, Map.empty)
      .getOrElse(word.text.toLowerCase, WordService.unrankedFrequency)
  }

  /** Streams the dump, decoding only the lines that mention a language being imported.
    *
    * The substring test before the JSON decode is what makes this minutes rather than an hour: the file is 22.9 GB of
    * which the three languages here are a small fraction, and decoding a line is far more expensive than scanning it.
    */
  private def readDump(path: String, options: Options): Task[Collected] = {
    val bytes = {
      ZStream
        .fromInputStreamZIO(
          ZIO
            .attemptBlockingIO {
              val stream = new FileInputStream(path)
              if (path.endsWith(".gz"))
                new GZIPInputStream(stream, 1 << 16)
              else
                stream
            }
            .map(identity[java.io.InputStream])
        )
        .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
    }

    bytes
      .filter(line => WiktextractParser.mayConcern(line, options.languages))
      .map(WiktextractParser.parse)
      .runFold(Collected.empty) { case (collected, entry) =>
        val withWord      = entry.word
          .filter(w => options.languages.contains(w.language))
          .fold(collected)(w => collected.withWord(w, WordService.unrankedFrequency))
        val relevantPairs = entry.pairs.filter(pair =>
          options.languages.contains(pair.source.language) && options.languages.contains(pair.target.language)
        )
        val withPairs     = relevantPairs.foldLeft(withWord)((acc, pair) =>
          acc.withWord(pair.source, WordService.unrankedFrequency).withWord(pair.target, WordService.unrankedFrequency)
        )
        // The form-word itself is not added to `collected.words` here -- that happens in `select`, once the
        // frequency cut has run, so a lemma's whole case/tense table is not materialised for every one of the
        // dump's millions of entries before `--limit` narrows anything down.
        val relevantForms = entry.forms.filter(form => options.languages.contains(form.lemma.language))
        withPairs.copy(pairs = relevantPairs ++ withPairs.pairs, forms = relevantForms ++ withPairs.forms)
      }
  }

  /** Parts of speech specific enough for a shared English sense to reliably mean the same thing on both sides. A
    * pronoun or article has no finer bucket than [[PartOfSpeech.Other]] in this application's POS enum, and function
    * words are exactly where pivoting goes wrong: German `die` (article, also the relative pronoun "who"/"which")
    * pivoted onto Hungarian `mik`/`miket` ("what", plural) through a shared, too-generic English sense. Content words
    * carry enough of their own meaning that a shared sense is trustworthy; anything landing in `Other` does not.
    */
  private val pivotablePos: Set[PartOfSpeech] =
    Set(PartOfSpeech.Noun, PartOfSpeech.Verb, PartOfSpeech.Adjective, PartOfSpeech.Adverb)

  /** German ↔ Hungarian, which no free source states directly.
    *
    * Both sides come out of the *English* entry that names them, so two translations of the same English sense are
    * translations of each other. It is an inference rather than an assertion, which is why the rows are marked `pivot`:
    * a screen can rank them below what the dictionary actually says, and a reader can see that it did.
    */
  def pivot(pairs: List[ParsedPair]): List[ParsedPair] = {
    val bySense = pairs
      .filter(pair => {
        pair.source.language == WordLanguage.En && pair.sense.exists(_.trim.nonEmpty) &&
        pivotablePos.contains(pair.source.partOfSpeech)
      })
      .groupBy(pair => (pair.source.key, pair.sense.map(_.trim.toLowerCase)))

    bySense.values.toList.flatMap { group =>
      val german    = group.map(_.target).filter(_.language == WordLanguage.De).distinct
      val hungarian = group.map(_.target).filter(_.language == WordLanguage.Hu).distinct
      for {
        de <- german
        hu <- hungarian
      } yield ParsedPair(de, hu, group.headOption.flatMap(_.sense))
    }
  }

  /** Cuts the collected words down to what was asked for, and ranks them.
    *
    * With a frequency list, `--limit` means "the commonest N", which is the useful reading: a vocabulary trainer wants
    * the words people actually say, and the tail of Wiktionary is mostly technical terms and archaisms. A word outside
    * the cut is still kept if something inside it translates to it — dropping those would leave translations pointing
    * at nothing.
    */
  def select(collected: Collected, frequencies: Map[WordLanguage, Map[String, Int]], limit: Int): Collected = {
    val ranked    = collected.words.keys.map(word => (word, rankOf(frequencies, word))).toMap
    val kept      = ranked.filter { case (_, rank) => rank <= limit }.keySet
    val pairs     = collected.pairs.filter(pair => kept.contains(pair.source) || kept.contains(pair.target))
    val needed    = kept ++ pairs.flatMap(pair => List(pair.source, pair.target))
    // A form is kept only if its lemma's own word row is going to exist -- whether that word made the frequency
    // cut directly, or is kept only because something translates to or from it. Anything whose lemma did not
    // survive contributes no form either.
    val forms     = collected.forms.filter(form => needed.contains(form.lemma))
    val baseRanks = needed.map(word => (word, ranked.getOrElse(word, WordService.unrankedFrequency))).toMap
    // A form has no frequency entry of its own -- "Häuser" is never a line in a frequency list -- so it inherits its
    // lemma's rank instead of the sentinel, which is what makes a common word's plural searchable near it rather than
    // buried at the bottom of every result.
    val allRanks  = forms.foldLeft(baseRanks) { (acc, form) =>
      if (acc.contains(form.form)) acc
      else acc.updated(form.form, acc.getOrElse(form.lemma, WordService.unrankedFrequency))
    }
    Collected(allRanks, pairs, forms)
  }

  /** Wiktextract sometimes gives one spelling more than one entry that this application's schema cannot tell apart by
    * sense, only by shape: a German noun stated once with its article and once without, or a homograph that is not a
    * real word in its own right so much as wiktextract's placeholder for "this spelling, some other part of speech."
    * Both collapse onto the entry that actually carries information. Grouped on `text.toLowerCase` because the two
    * entries do not always agree on case (`Frau` the noun, `frau` the pronoun).
    *
    *   - Within one part of speech, a gendered and a genderless entry for the same spelling are the same headword twice
    *     — [[WiktextractParser.genderOf]] finds the article on the entry that states it, so a gendered entry surviving
    *     means some entry did. The genderless duplicate is dropped.
    *   - An `Other`-tagged entry that carries no translation of its own is dropped once the same spelling already has
    *     an entry in a real part of speech that does: `Other` is this application's catch-all for parts of speech it
    *     does not model, and an untranslated one adds nothing a learner can use.
    *
    * Whatever is dropped takes its pairs and forms with it, the same way [[select]] does.
    */
  def dedupeHomographs(collected: Collected): Collected = {
    def hasTranslation(word: ParsedWord): Boolean = {
      collected.pairs.exists(pair => pair.source == word || pair.target == word)
    }

    val dropped = collected.words.keys
      .groupBy(word => (word.language, word.text.toLowerCase))
      .values
      .flatMap { homographs =>
        val genderless = homographs
          .groupBy(_.partOfSpeech)
          .values
          .filter(_.exists(_.gender.isDefined))
          .flatMap(_.filter(_.gender.isEmpty))

        val survivors         = homographs.toSet -- genderless
        val hasRealSense      = survivors.exists(word => word.partOfSpeech != PartOfSpeech.Other && hasTranslation(word))
        val untranslatedOther = {
          if (hasRealSense)
            survivors.filter(word => word.partOfSpeech == PartOfSpeech.Other && !hasTranslation(word))
          else
            Set.empty[ParsedWord]
        }

        genderless ++ untranslatedOther
      }
      .toSet

    if (dropped.isEmpty)
      collected
    else {
      Collected(
        words = collected.words -- dropped,
        pairs = collected.pairs.filterNot(pair => dropped.contains(pair.source) || dropped.contains(pair.target)),
        forms = collected.forms.filterNot(form => dropped.contains(form.lemma) || dropped.contains(form.form)),
      )
    }
  }

  /** Every lemma's forms, keyed by relation, one form per relation. When a lemma states the same relation more than
    * once (a table cell repeated under two spellings) the alphabetically first wins, so a re-import derives the same
    * edge rather than picking whichever happened to sort first in a `HashMap`.
    */
  private def formsByRelation(forms: List[ParsedForm]): Map[ParsedWord, Map[String, ParsedWord]] = {
    forms
      .groupBy(_.lemma)
      .view
      .mapValues(_.groupBy(_.relation).view.mapValues(_.map(_.form).minBy(_.text)).toMap)
      .toMap
  }

  /** Translations between two lemmas' forms, inferred through the lemma pair itself: if `Haus` translates to `house`,
    * and both have a form tagged `plural` (`Häuser`, `houses`), those two forms translate to each other too. No source
    * states this directly — a plural is its own dictionary entry, not a row in a translation table — so it is derived
    * here the same way [[pivot]] derives German–Hungarian from a shared English sense, and stored with its own origin
    * so a screen can say where it came from.
    *
    * Runs over every lemma pair passed in, direct and pivoted alike, so a German plural picks up a Hungarian one
    * through the same pivoted `Haus`/`ház` pair its singular already relies on.
    */
  def formPairs(pairs: List[ParsedPair], forms: List[ParsedForm]): List[ParsedPair] = {
    val byLemma = formsByRelation(forms)
    pairs.flatMap { pair =>
      val sourceForms = byLemma.getOrElse(pair.source, Map.empty)
      val targetForms = byLemma.getOrElse(pair.target, Map.empty)
      sourceForms.keySet.intersect(targetForms.keySet).toList.sorted.map { relation =>
        ParsedPair(sourceForms(relation), targetForms(relation), pair.sense)
      }
    }
  }

  // -- The seed file ----------------------------------------------------------------------------

  /** A flat TSV with a record-type column, gzipped if the name says so.
    *
    * Chosen over JSON because it is diffable: the committed sample is reviewed like any other file in the repository,
    * and a one-word change should read as a one-line change.
    *
    * Three record types, one per line:
    *   - `W  language text pos gender rank` — a word.
    *   - `T  <word columns> <word columns> sense` — a translation pair, source then target.
    *   - `F  <word columns> <word columns> relation` — a form relation, lemma then form, `relation` in the sense
    *     column's place.
    */
  object SeedFormat {

    def encode(collected: Collected): List[String] = {
      val words = {
        collected.words.toList.sortBy { case (word, rank) => (rank, word.key.toString) }.map { case (word, rank) =>
          List(
            "W",
            WordLanguage.code(word.language),
            word.text,
            PartOfSpeech.code(word.partOfSpeech),
            Gender.toColumn(word.gender),
            rank.toString,
          ).mkString("\t")
        }
      }
      val pairs = {
        collected.pairs.distinct.sortBy(pair => (pair.source.key.toString, pair.target.key.toString)).map { pair =>
          List(
            "T",
            WordLanguage.code(pair.source.language),
            pair.source.text,
            PartOfSpeech.code(pair.source.partOfSpeech),
            Gender.toColumn(pair.source.gender),
            WordLanguage.code(pair.target.language),
            pair.target.text,
            PartOfSpeech.code(pair.target.partOfSpeech),
            Gender.toColumn(pair.target.gender),
            pair.sense.getOrElse(""),
          ).mkString("\t")
        }
      }
      val forms = {
        collected.forms.distinct
          .sortBy(form => (form.lemma.key.toString, form.form.key.toString, form.relation))
          .map { form =>
            List(
              "F",
              WordLanguage.code(form.lemma.language),
              form.lemma.text,
              PartOfSpeech.code(form.lemma.partOfSpeech),
              Gender.toColumn(form.lemma.gender),
              WordLanguage.code(form.form.language),
              form.form.text,
              PartOfSpeech.code(form.form.partOfSpeech),
              Gender.toColumn(form.form.gender),
              form.relation,
            ).mkString("\t")
          }
      }
      words ++ pairs ++ forms
    }

    private def wordAt(columns: Array[String], offset: Int): Option[ParsedWord] = {
      for {
        language <- columns.lift(offset).flatMap(WordLanguage.fromString)
        text     <- columns.lift(offset + 1).map(_.trim).filter(_.nonEmpty)
        pos      <- columns.lift(offset + 2).flatMap(PartOfSpeech.fromString)
        gender    = columns.lift(offset + 3).flatMap(value => Gender.fromColumn(value))
      } yield ParsedWord(language, text, pos, gender)
    }

    /** Blank lines and lines starting with `#` are comments, so the committed sample can be annotated. */
    def decode(lines: List[String]): Collected = {
      lines.foldLeft(Collected.empty) { (collected, line) =>
        val trimmed = line.trim
        if (trimmed.isEmpty || trimmed.startsWith("#"))
          collected
        else {
          val columns = line.split('\t')
          columns.headOption match {
            case Some("W") =>
              wordAt(columns, 1) match {
                case None       =>
                  collected
                case Some(word) =>
                  collected.withWord(
                    word,
                    columns.lift(5).flatMap(_.toIntOption).getOrElse(WordService.unrankedFrequency),
                  )
              }
            case Some("T") =>
              (wordAt(columns, 1), wordAt(columns, 5)) match {
                case (Some(source), Some(target)) =>
                  val sense = columns.lift(9).map(_.trim).filter(_.nonEmpty)
                  collected
                    .withWord(source, WordService.unrankedFrequency)
                    .withWord(target, WordService.unrankedFrequency)
                    .copy(pairs = ParsedPair(source, target, sense) :: collected.pairs)
                case _                            =>
                  collected
              }
            case Some("F") =>
              (wordAt(columns, 1), wordAt(columns, 5)) match {
                case (Some(lemma), Some(form)) =>
                  columns.lift(9).map(_.trim).filter(_.nonEmpty) match {
                    case None           =>
                      collected
                    case Some(relation) =>
                      collected
                        .withWord(lemma, WordService.unrankedFrequency)
                        .withWord(form, WordService.unrankedFrequency)
                        .copy(forms = ParsedForm(lemma, form, relation) :: collected.forms)
                  }
                case _                         =>
                  collected
              }
            case _         =>
              collected
          }
        }
      }
    }
  }

  private def readSeed(path: String): Task[Collected] = {
    ZIO.attemptBlocking {
      val stream = {
        if (path.endsWith(".gz"))
          new GZIPInputStream(new FileInputStream(path))
        else
          new FileInputStream(path)
      }
      val source = scala.io.Source.fromInputStream(stream, StandardCharsets.UTF_8.name)
      try SeedFormat.decode(source.getLines().toList)
      finally source.close()
    }
  }

  private def writeSeed(path: String, collected: Collected): Task[Unit] = {
    ZIO.attemptBlocking {
      val stream = {
        if (path.endsWith(".gz"))
          new GZIPOutputStream(new FileOutputStream(path))
        else
          new FileOutputStream(path)
      }
      try stream.write(SeedFormat.encode(collected).mkString("\n").getBytes(StandardCharsets.UTF_8))
      finally stream.close()
    }
  }

  // -- Writing to the database ------------------------------------------------------------------

  private def toRow(word: ParsedWord, rank: Int, now: Long): WordRow = {
    WordRow(
      id = 0L,
      language = WordLanguage.code(word.language),
      text = word.text,
      textNorm = word.text.toLowerCase,
      partOfSpeech = PartOfSpeech.code(word.partOfSpeech),
      gender = Gender.toColumn(word.gender),
      frequencyRank = rank,
      source = WordService.dictionarySource,
      createdBy = None,
      createdAt = now,
    )
  }

  /** Collapses the words that differ only in case, keeping the commonest reading of each.
    *
    * `ParsedWord` compares on the text as written, but the database's identity is `text_norm` — the lowercased form —
    * so `Grammy` and `grammy` are two values here and one row there. Inserting both in a single batch violates
    * `words_language_text_norm_part_of_speech_gender_key`; across two batches it does not, because each batch reads
    * what is already stored first, which is why this only shows up on a real dump. About one word in 250 of the English
    * side collides.
    *
    * The best rank wins, as everywhere else in this importer, and the tie is broken on the text so that a run is
    * reproducible — uppercase sorts first, which keeps `Sie` over `sie` and matches how a dictionary would print it.
    */
  def dedupeByKey(words: List[(ParsedWord, Int)]): List[(ParsedWord, Int)] = {
    words
      .groupBy { case (word, _) => word.key }
      .values
      .map(_.minBy { case (word, rank) => (rank, word.text) })
      .toList
      .sortBy { case (word, rank) => (rank, word.text) }
  }

  /** Inserts the words that are not there yet, then answers every word's id.
    *
    * Idempotence without an `ON CONFLICT` clause, which the two dialects spell differently: read the batch's keys
    * first, insert what is missing, read back what was inserted.
    */
  private def storeWords(collected: Collected, now: Long): RIO[WordRepository, Map[ParsedWord, Long]] = {
    val byLanguage = collected.words.toList.groupBy { case (word, _) => word.language }
    ZIO
      .foreach(byLanguage.toList) { case (language, words) =>
        val code = WordLanguage.code(language)
        ZIO
          .foreach(dedupeByKey(words).grouped(batchSize).toList) { batch =>
            val norms = batch.map { case (word, _) => word.text.toLowerCase }.distinct
            for {
              existing <- WordRepository.findWordsByKeys(code, norms)
              known     = existing.map(row => (row.language, row.textNorm, row.partOfSpeech, row.gender)).toSet
              missing   = batch.collect { case (word, rank) if !known.contains(word.key) => toRow(word, rank, now) }
              _        <- WordRepository.insertWords(missing)
              // Read back rather than trusting generated keys from a batch insert: `getGeneratedKeys` after an
              // `executeBatch` is not something both drivers agree about.
              stored   <- WordRepository.findWordsByKeys(code, norms)
            } yield stored.map(row => ((row.language, row.textNorm, row.partOfSpeech, row.gender), row.id)).toMap
          }
          .map(_.foldLeft(Map.empty[(String, String, String, String), Long])(_ ++ _))
      }
      .map { maps =>
        val byKey = maps.foldLeft(Map.empty[(String, String, String, String), Long])(_ ++ _)
        collected.words.keys.flatMap(word => byKey.get(word.key).map(id => (word, id))).toMap
      }
  }

  /** Writes both directions of every pair that is not already recorded. */
  private def storePairs(
    pairs: List[ParsedPair],
    ids: Map[ParsedWord, Long],
    origin: String,
    now: Long,
  ): RIO[WordRepository, Long] = {
    val edges = pairs.flatMap { pair =>
      for {
        source <- ids.get(pair.source)
        target <- ids.get(pair.target)
        if source != target
      } yield (source, target)
    }.distinct

    ZIO
      .foreach(edges.grouped(batchSize).toList) { batch =>
        val sources = batch.flatMap { case (source, target) => List(source, target) }.distinct
        for {
          known <- WordRepository.existingTranslationPairs(sources).map(_.toSet)
          // Distinct for the same reason dedupeByKey exists: `word_translations` is unique on
          // (source_word_id, target_word_id, created_by), and two collected pairs can reach the same id pair once
          // case variants have collapsed onto one row.
          rows   = batch
                     .flatMap { case (source, target) => List((source, target), (target, source)) }
                     .distinct
                     .filterNot(known.contains)
                     .map { case (from, to) => WordTranslationRow(0L, from, to, origin, None, now) }
          _     <- WordRepository.insertTranslations(rows)
        } yield rows.size.toLong
      }
      .map(_.sum)
  }

  /** The `(lemmaId, formId, relation)` edges this batch of forms resolves to, once ids are known: a form whose lemma or
    * form-word did not get stored contributes nothing, and a form spelled identically to its own lemma (English `put`'s
    * past tense is `put`) is dropped, the same rule [[storePairs]] applies to a translation pair that would otherwise
    * link a word to itself.
    *
    * Pulled out as a pure function, like [[dedupeByKey]] and [[pivot]], so it is unit-testable without a database.
    */
  def formEdges(forms: List[ParsedForm], ids: Map[ParsedWord, Long]): List[(Long, Long, String)] = {
    forms.flatMap { form =>
      for {
        lemmaId <- ids.get(form.lemma)
        formId  <- ids.get(form.form)
        if lemmaId != formId
      } yield (lemmaId, formId, form.relation)
    }.distinct
  }

  /** Writes every form relation that is not already recorded. Unlike [[storePairs]], a form relation is directional and
    * stored once -- `formsOf`/`lemmaOf` answer the two directions with two different queries, rather than two rows.
    */
  private def storeForms(forms: List[ParsedForm], ids: Map[ParsedWord, Long], now: Long): RIO[WordRepository, Long] = {
    val edges = formEdges(forms, ids)

    ZIO
      .foreach(edges.grouped(batchSize).toList) { batch =>
        val lemmaIds = batch.map { case (lemmaId, _, _) => lemmaId }.distinct
        for {
          known <- WordRepository.existingFormRelations(lemmaIds).map(_.toSet)
          rows   = batch.filterNot(known.contains).map { case (lemmaId, formId, relation) =>
                     WordFormRow(0L, lemmaId, formId, relation, now)
                   }
          _     <- WordRepository.insertForms(rows)
        } yield rows.size.toLong
      }
      .map(_.sum)
  }

  private def store(collected: Collected): RIO[WordRepository & DataSource & AppConfig, Unit] = {
    for {
      config     <- ZIO.service[AppConfig]
      dataSource <- ZIO.service[DataSource]
      // The same migration `Main` runs, and for the same reason it is safe to run twice: without it a fresh clone has
      // to start the server once before it can load the dictionary, which is a footgun rather than a step.
      _          <- FlywayMigrator.migrate(dataSource, DbDialect.Postgresql, Some(config.db.schema))
      now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
      ids        <- storeWords(collected, now)
      _          <- ZIO.logInfo(s"Stored ${ids.size} word(s)")
      direct     <- storePairs(collected.pairs, ids, WordService.dictionaryOrigin, now)
      _          <- ZIO.logInfo(s"Stored $direct direct translation row(s)")
      // The pivot runs over the collected pairs rather than the stored ones, so a re-import derives the same set and
      // inserts none of it a second time.
      pivoted     = pivot(collected.pairs)
      inferred   <- storePairs(pivoted, ids, WordService.pivotOrigin, now)
      _          <- ZIO.logInfo(s"Stored $inferred inferred German-Hungarian row(s)")
      forms      <- storeForms(collected.forms, ids, now)
      _          <- ZIO.logInfo(s"Stored $forms form relation row(s)")
      formLinked <- storePairs(formPairs(collected.pairs ++ pivoted, collected.forms), ids, WordService.formOrigin, now)
      _          <- ZIO.logInfo(s"Stored $formLinked form-to-form translation row(s)")
    } yield ()
  }

  private def load(options: Options): Task[Collected] = {
    options.raw match {
      case None       =>
        readSeed(options.seedPath.getOrElse(defaultSeedPath))
      case Some(path) =>
        for {
          frequencies <- ZIO
                           .foreach(options.frequencies.toList) { directory =>
                             ZIO
                               .foreach(options.languages.toList)(language =>
                                 readFrequencies(directory, language).map((language, _))
                               )
                               .map(_.toMap)
                           }
                           .map(_.headOption.getOrElse(Map.empty[WordLanguage, Map[String, Int]]))
          collected   <- readDump(path, options)
          _           <- ZIO.logInfo(
                           s"Read ${collected.words.size} word(s), ${collected.pairs.size} pair(s), " +
                             s"${collected.forms.size} form(s)"
                         )
        } yield select(collected, frequencies, options.limit)
    }
  }

  def run: ZIO[ZIOAppArgs, Any, Any] = {
    for {
      args      <- ZIOAppArgs.getArgs
      options   <- ZIO.fromEither(parseArgs(args.toList)).mapError(new IllegalArgumentException(_))
      collected <- load(options).map(dedupeHomographs)
      _         <- ZIO.logInfo(
                     s"Importing ${collected.words.size} word(s), ${collected.pairs.size} pair(s), " +
                       s"${collected.forms.size} form(s)"
                   )
      _         <- ZIO.foreachDiscard(options.exportTo)(path => writeSeed(path, collected))
      // Exporting is an offline transformation of a dump into the committed sample: it touches no database, so it
      // does not need one to be running.
      _         <- ZIO
                     .when(options.exportTo.isEmpty)(store(collected))
                     .provide(AppConfig.live, DataSourceFactory.postgresLive, WordRepository.live)
      _         <- ZIO.logInfo("Dictionary import finished")
    } yield ()
  }
}
