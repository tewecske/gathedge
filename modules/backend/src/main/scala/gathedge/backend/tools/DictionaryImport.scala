package gathedge.backend.tools

import gathedge.backend.config.AppConfig
import gathedge.backend.db.{
  DataSourceFactory,
  FlywayMigrator,
  TextSearch,
  WordAudioRow,
  WordFormRow,
  WordLinkRow,
  WordRepository,
  WordRow,
  WordSource,
  WordTranslationRow,
}
import gathedge.backend.service.WordService
import gathedge.shared.domain.{Gender, PartOfSpeech, WordLanguage, WordLinkKind}
import zio.*
import zio.stream.{ZPipeline, ZStream}

import java.io.{BufferedWriter, FileInputStream, FileOutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import java.util.concurrent.TimeUnit

import javax.sql.DataSource

import WiktextractParser.{ParsedAudio, ParsedEntry, ParsedForm, ParsedPair, ParsedWord}

/** Loads the shared dictionary: English, German, Spanish and Hungarian words with their parts of speech, gender where
  * the language has one, and the translations between them.
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
  * # cut the dump into one shard per language once, then export from the shards without the dump
  * sbt "backend/runMain gathedge.backend.tools.DictionaryImport --raw ~/raw...gz --extract data/dictionary/shards"
  * sbt "backend/runMain gathedge.backend.tools.DictionaryImport --shards data/dictionary/shards --limit 20000 \
  *        --frequencies data/frequency --export target/dictionary/seed-20000.tsv.gz"
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
    shards: Option[String] = None,
    extractTo: Option[String] = None,
    limit: Int = 50000,
    frequencies: Option[String] = None,
    exportTo: Option[String] = None,
    languages: Set[WordLanguage] = WordLanguage.all.toSet,
    includeAltOf: Boolean = false,
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
        case "--shards" :: path :: tail                         =>
          loop(tail, options.copy(shards = Some(path)))
        case "--extract" :: path :: tail                        =>
          loop(tail, options.copy(extractTo = Some(path)))
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
          if (parsed.isEmpty) {
            Left(
              s"--languages needs codes out of ${WordLanguage.all.map(WordLanguage.code).mkString(",")}; got '$value'"
            )
          } else
            loop(tail, options.copy(languages = parsed.toSet))
        case "--include-alt-of" :: tail                         =>
          loop(tail, options.copy(includeAltOf = true))
        case unknown :: _                                       =>
          Left(s"Unrecognised argument '$unknown'")
      }
    }
    loop(args, Options()).flatMap { options =>
      val modes = List(options.seed, options.raw.isDefined, options.shards.isDefined).count(identity)
      if (modes != 1)
        Left("Give exactly one of --seed, --raw <path> and --shards <dir>")
      else if (options.extractTo.isDefined && (options.raw.isEmpty || options.exportTo.isDefined))
        Left("--extract <dir> needs --raw <path>, and writes shards rather than a seed, so it takes no --export")
      else
        Right(options)
    }
  }

  /** Words, pairs, forms and recordings held together: one language's share of an export ([[slice]]), or a whole seed
    * being rewritten.
    */
  final case class Collected(
    words: Map[ParsedWord, Int],
    pairs: List[ParsedPair],
    forms: List[ParsedForm],
    audio: List[ParsedAudio] = Nil,
  ) {

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

  /** How many lines go to one decoding task. Decoding is most of a pass's time, and every line is independent, so a
    * pass decodes on every core. [[ZStream.mapZIOPar]] keeps the chunks in the file's order.
    */
  private val decodeChunk = 1000

  private val decodeParallelism = java.lang.Runtime.getRuntime.availableProcessors.max(1)

  private def decoded[A](lines: ZStream[Any, Throwable, String])(decode: String => A): ZStream[Any, Throwable, A] = {
    lines.grouped(decodeChunk).mapZIOPar(decodeParallelism)(chunk => ZIO.attempt(chunk.map(decode))).flattenChunks
  }

  /** Where a pass reads the dump's lines from. Each pass names the languages it reads. */
  trait DumpSource {
    def lines(languages: Set[WordLanguage]): ZStream[Any, Throwable, String]
  }

  object DumpSource {

    /** The whole dump, read again on every pass. A line of another language gets through when a translation row names
      * one of these. It adds nothing, since every record is keyed to the entry's own language, but it costs a decode.
      */
    def dump(path: String): DumpSource = {
      new DumpSource {
        def lines(languages: Set[WordLanguage]) = fileLines(path).filter(WiktextractParser.mayConcern(_, languages))
      }
    }

    /** The shards [[extract]] cut. A pass reads only its own languages' files. */
    def shards(directory: String): DumpSource = {
      new DumpSource {
        def lines(languages: Set[WordLanguage]) = {
          ZStream
            .fromIterable(languages.toList.sortBy(WordLanguage.code))
            .flatMap(language => fileLines(shardPath(directory, language)))
        }
      }
    }
  }

  def shardPath(directory: String, language: WordLanguage): String = {
    s"$directory/${WordLanguage.code(language)}.jsonl.gz"
  }

  /** Cuts the dump into one gzipped shard per language. A shard holds the lines whose entry is in that language, so the
    * English shard holds every translation table. A later export reads the shards instead of the 2.6 GB dump, and
    * adding a language reads the dump once more, for that language alone.
    *
    * Each shard is written under a `.part` name and renamed at the end, so an interrupted run leaves no half shard.
    */
  private[tools] def extract(dump: String, directory: String, languages: Set[WordLanguage]): Task[Unit] = {
    val ordered = languages.toList.sortBy(WordLanguage.code)
    ZIO.scoped {
      for {
        _       <- ZIO.attemptBlocking(Files.createDirectories(Path.of(directory)))
        writers <- ZIO
                     .foreach(ordered) { language =>
                       openWriter(shardPath(directory, language) + ".part", gzip = true).map(language -> _)
                     }
                     .map(_.toMap)
        counts  <- decoded(fileLines(dump).filter(WiktextractParser.mayConcern(_, languages))) { line =>
                     WiktextractParser.languageOf(line).filter(languages.contains).map(_ -> line)
                   }.collectSome.chunks
                     .runFoldZIO(Map.empty[WordLanguage, Long]) { (counts, chunk) =>
                       ZIO.attemptBlocking {
                         chunk.foreach { case (language, line) =>
                           writers(language).write(line)
                           writers(language).write('\n')
                         }
                         chunk.groupBy(_._1).foldLeft(counts) { case (acc, (language, lines)) =>
                           acc.updated(language, acc.getOrElse(language, 0L) + lines.size)
                         }
                       }
                     }
        _       <- ZIO.attemptBlocking(writers.values.foreach(_.close()))
        _       <- ZIO.foreachDiscard(ordered) { language =>
                     val path = shardPath(directory, language)
                     ZIO.attemptBlocking(
                       Files.move(Path.of(path + ".part"), Path.of(path), StandardCopyOption.REPLACE_EXISTING)
                     ) *> ZIO.logInfo(s"Wrote $path: ${counts.getOrElse(language, 0L)} line(s)")
                   }
      } yield ()
    }
  }

  /** What the first pass keeps: the words inside the frequency cut, and the pairs with an end inside it. Both are known
    * line by line, since a word's rank is its text's line in the frequency list. So the dump's long tail is read and
    * dropped here, never held.
    */
  final case class Heads(kept: Set[ParsedWord], pairs: List[ParsedPair]) {

    def add(entry: ParsedEntry, languages: Set[WordLanguage], inCut: ParsedWord => Boolean): Heads = {
      val word  = entry.word.filter(w => languages.contains(w.language) && inCut(w))
      val pairs = entry.pairs.filter(pair => {
        languages.contains(pair.source.language) && languages.contains(pair.target.language) &&
        (inCut(pair.source) || inCut(pair.target))
      })
      if (word.isEmpty && pairs.isEmpty)
        this
      else
        Heads(kept ++ word ++ pairs.flatMap(pair => List(pair.source, pair.target)).filter(inCut), pairs ++ this.pairs)
    }
  }

  object Heads {
    val empty: Heads = Heads(Set.empty, Nil)
  }

  /** Reads a dump into a seed file, one language at a time.
    *
    * English is the hub. Every pair comes from an English entry's translation table, and a word's forms, recordings and
    * homographs never leave its own language. So the first pass reads only the words and the pairs, and cuts them as it
    * goes. The second pass runs once per language: it reads that language's forms and recordings, keeps those of the
    * words the cut kept, dedupes the language's homographs, writes its lines and lets them go. Only the pairs and one
    * language's share are held at once. The pairs are written last, when every language has said which of its words a
    * dedupe moved.
    *
    * The file holds what [[select]] and [[dedupeHomographs]] make of the whole dump read at once; only the line order
    * differs, since each language's lines come together.
    */
  private[tools] def exportDump(
    source: DumpSource,
    options: Options,
    frequencies: Map[WordLanguage, Map[String, Int]],
    path: String,
  ): Task[Unit] = {
    val languages = options.languages.toList.sortBy(WordLanguage.code)
    val inCut     = (word: ParsedWord) => rankOf(frequencies, word) <= options.limit
    ZIO.scoped {
      for {
        _             <- ZIO.logInfo(s"Reading words and pairs, keeping the commonest ${options.limit} word(s)...")
        heads         <- decoded(source.lines(options.languages))(WiktextractParser.parseHead)
                           .runFold(Heads.empty)((heads, entry) => heads.add(entry, options.languages, inCut))
        (ranks, pairs) = cut(heads.kept, heads.pairs, frequencies, options.limit)
        _             <- ZIO.logInfo(s"Kept ${ranks.size} word(s), ${pairs.size} pair(s)")
        writer        <- openWriter(path, gzip = path.endsWith(".gz"))
        plans         <- ZIO.foreach(languages) { language =>
                           val code = WordLanguage.code(language)
                           for {
                             _              <- ZIO.logInfo(s"Reading $code forms and recordings...")
                             read           <- decoded(source.lines(Set(language)))(
                                                 WiktextractParser.parse(_, options.includeAltOf)
                                               ).runFold((List.empty[ParsedForm], List.empty[ParsedAudio])) {
                                                 case ((forms, audio), entry) =>
                                                   (
                                                     entry.forms.filter(form => {
                                                       form.lemma.language == language && ranks.contains(form.lemma)
                                                     }) ++ forms,
                                                     entry.audio.filter(_.word.language == language) ++ audio,
                                                   )
                                               }
                             (forms, audio)  = read
                             (written, plan) = slice(language, ranks, pairs, forms, audio)
                             _              <- writeLines(
                                                 writer,
                                                 SeedFormat.wordLines(written.words) ++
                                                   SeedFormat.formLines(written.forms) ++
                                                   SeedFormat.audioLines(written.audio),
                                               )
                             heap           <- GcStats.line
                             _              <- ZIO.logInfo(
                                                 s"Wrote $code: ${written.words.size} word(s), " +
                                                   s"${written.forms.size} form(s), ${written.audio.size} " +
                                                   s"recording(s); $heap"
                                               )
                           } yield plan
                         }
        resolved       = plans.foldLeft(HomographPlan.empty)(_ ++ _).applyToPairs(pairs)
        _             <- writeLines(writer, SeedFormat.pairLines(resolved))
        _             <- ZIO.logInfo(s"Wrote ${resolved.size} pair(s)")
      } yield ()
    }
  }

  /** One language's share of the import, deduped, and the plan that deduped it, for the pairs to follow later. `forms`
    * and `audio` are the language's own; `ranks` and `pairs` are every language's.
    */
  def slice(
    language: WordLanguage,
    ranks: Map[ParsedWord, Int],
    pairs: List[ParsedPair],
    forms: List[ParsedForm],
    audio: List[ParsedAudio],
  ): (Collected, HomographPlan) = {
    val own      = ranks.filter { case (word, _) => word.language == language }
    // The pairs only tell the dedupe which words are translated; they are written with every language's plan.
    val touching = pairs.filter(pair => pair.source.language == language || pair.target.language == language)
    val selected = withForms(own, touching, forms, audio)
    val plan     = HomographPlan.of(selected)
    (plan.applyTo(selected).copy(pairs = Nil), plan)
  }

  /** Parts of speech specific enough for a shared English sense to reliably mean the same thing on both sides. A
    * pronoun or article has no finer bucket than [[PartOfSpeech.Other]] in this application's POS enum, and function
    * words are exactly where pivoting goes wrong: German `die` (article, also the relative pronoun "who"/"which")
    * pivoted onto Hungarian `mik`/`miket` ("what", plural) through a shared, too-generic English sense. Content words
    * carry enough of their own meaning that a shared sense is trustworthy; anything landing in `Other` does not.
    */
  private val pivotablePos: Set[PartOfSpeech] =
    Set(PartOfSpeech.Noun, PartOfSpeech.Verb, PartOfSpeech.Adjective, PartOfSpeech.Adverb)

  /** Every non-English study language, pairwise, on senses no free source states directly (German ↔ Hungarian, German ↔
    * Spanish, Spanish ↔ Hungarian, and so on for any language this app later adds).
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
      val byLanguage = group.map(_.target).distinct.filterNot(_.language == WordLanguage.En).groupBy(_.language)
      // Sorted by wire code for determinism -- `storePairs` writes both directions regardless, so this only fixes
      // which direction a test (or a diff of `--export`'s output) sees.
      val languages  = byLanguage.keys.toList.sortBy(WordLanguage.code)
      for {
        (langA, langB) <- languages.zipWithIndex.flatMap { case (langA, i) => languages.drop(i + 1).map(langA -> _) }
        a              <- byLanguage(langA)
        b              <- byLanguage(langB)
      } yield ParsedPair(a, b, group.headOption.flatMap(_.sense))
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
    val (ranks, pairs) = cut(collected.words.keys, collected.pairs, frequencies, limit)
    withForms(ranks, pairs, collected.forms, collected.audio)
  }

  /** [[select]]'s first half: the words inside the cut, the pairs with an end inside it, and every word those pairs
    * name, each with its rank.
    */
  def cut(
    words: Iterable[ParsedWord],
    pairs: List[ParsedPair],
    frequencies: Map[WordLanguage, Map[String, Int]],
    limit: Int,
  ): (Map[ParsedWord, Int], List[ParsedPair]) = {
    val kept   = words.filter(word => rankOf(frequencies, word) <= limit).toSet
    val linked = pairs.filter(pair => kept.contains(pair.source) || kept.contains(pair.target))
    val needed = kept ++ linked.flatMap(pair => List(pair.source, pair.target))
    (needed.map(word => (word, rankOf(frequencies, word))).toMap, linked)
  }

  /** [[select]]'s second half: the forms and recordings of the words [[cut]] kept. */
  def withForms(
    ranks: Map[ParsedWord, Int],
    pairs: List[ParsedPair],
    forms: List[ParsedForm],
    audio: List[ParsedAudio],
  ): Collected = {
    // A form is kept only if its lemma's own word row is going to exist -- whether that word made the frequency
    // cut directly, or is kept only because something translates to or from it. Anything whose lemma did not
    // survive contributes no form either.
    val keptForms = forms.filter(form => ranks.contains(form.lemma))
    // A form has no frequency entry of its own -- "Häuser" is never a line in a frequency list -- so it inherits its
    // lemma's rank instead of the sentinel, which is what makes a common word's plural searchable near it rather than
    // buried at the bottom of every result.
    val allRanks  = keptForms.foldLeft(ranks) { (acc, form) =>
      if (acc.contains(form.form)) acc
      else acc.updated(form.form, acc.getOrElse(form.lemma, WordService.unrankedFrequency))
    }
    // A recording is kept only if its word is: it has nothing else to hang on.
    val keptAudio = audio.filter(audio => allRanks.contains(audio.word))
    Collected(allRanks, pairs, keptForms, keptAudio)
  }

  /** Wiktextract sometimes gives one spelling more than one entry that this application's schema cannot tell apart by
    * sense, only by shape: a German noun stated once with its article and once without, or a homograph that is not a
    * real word in its own right so much as wiktextract's placeholder for "this spelling, some other part of speech."
    * Grouped on `text.toLowerCase` because the two entries do not always agree on case (`Frau` the noun, `frau` the
    * pronoun).
    *
    *   - Within one part of speech, a gendered and a genderless entry for the same spelling are the same headword twice
    *     — [[WiktextractParser.genderOf]] finds the article on the entry that states it, so a gendered entry surviving
    *     means some entry did. The genderless duplicate is *redirected* onto the gendered one rather than dropped
    *     outright: its pairs and forms are the same headword's, so `Frau`'s plural stays imported even when it was
    *     `Frau`-without-gender that stated `forms[]`, not `Frau`-`die`. Ties (more than one gendered entry for the same
    *     spelling and part of speech, which does not happen in practice) resolve deterministically by gender then text,
    *     rather than duplicating the redirect onto every one of them.
    *   - An `Other`-tagged entry that carries no translation of its own is dropped outright once the same spelling
    *     already has an entry in a real part of speech that does: `Other` is this application's catch-all for parts of
    *     speech it does not model, an untranslated one adds nothing a learner can use, and — unlike the gender case —
    *     it is not the same headword as the survivor, so there is nothing of its own worth keeping.
    */
  def dedupeHomographs(collected: Collected): Collected = {
    val plan = HomographPlan.of(collected)
    if (plan.isEmpty)
      collected
    else
      plan.applyTo(collected)
  }

  /** What [[dedupeHomographs]] decided: which words move onto a survivor, and which go. A word's homographs are all in
    * its own language, so one language's plan never touches another's words. That is what lets [[exportDump]] dedupe a
    * language at a time and apply every plan to the pairs at the end.
    */
  final case class HomographPlan(redirects: Map[ParsedWord, ParsedWord], dropped: Set[ParsedWord]) {

    def isEmpty: Boolean = redirects.isEmpty && dropped.isEmpty

    def ++(other: HomographPlan): HomographPlan = {
      HomographPlan(redirects ++ other.redirects, dropped ++ other.dropped)
    }

    private def resolve(word: ParsedWord): ParsedWord = redirects.getOrElse(word, word)

    def applyToPairs(pairs: List[ParsedPair]): List[ParsedPair] = {
      pairs
        .filterNot(pair => dropped.contains(pair.source) || dropped.contains(pair.target))
        .map(pair => pair.copy(source = resolve(pair.source), target = resolve(pair.target)))
        .distinct
    }

    def applyTo(collected: Collected): Collected = {
      Collected(
        words = collected.words.filter { case (word, _) => !redirects.contains(word) && !dropped.contains(word) },
        pairs = applyToPairs(collected.pairs),
        forms = collected.forms
          .filterNot(form => dropped.contains(form.lemma) || dropped.contains(form.form))
          .map(form => form.copy(lemma = resolve(form.lemma), form = resolve(form.form)))
          .distinct,
        // A redirected entry's recordings are the survivor's: it is the same headword, spelled the same way.
        audio = collected.audio
          .filterNot(audio => dropped.contains(audio.word))
          .map(audio => audio.copy(word = resolve(audio.word)))
          .distinct,
      )
    }
  }

  object HomographPlan {

    val empty: HomographPlan = HomographPlan(Map.empty, Set.empty)

    def of(collected: Collected): HomographPlan = {
      val translated: Set[ParsedWord]               =
        collected.pairs.iterator.flatMap(pair => Iterator(pair.source, pair.target)).toSet
      def hasTranslation(word: ParsedWord): Boolean = translated.contains(word)

      val byHomograph = collected.words.keys
        .groupBy(word => (word.language, word.text.toLowerCase))
        .values
        .toList

      val redirects: Map[ParsedWord, ParsedWord] = byHomograph.flatMap { homographs =>
        homographs.groupBy(_.partOfSpeech).values.flatMap { posGroup =>
          posGroup.filter(_.gender.isDefined).toList.sortBy(word => (Gender.toColumn(word.gender), word.text)) match {
            case survivor :: _ =>
              posGroup.filter(_.gender.isEmpty).map(_ -> survivor)
            case Nil           =>
              Nil
          }
        }
      }.toMap

      val dropped = byHomograph.flatMap { homographs =>
        // Filtered against the map, not `-- redirects.keySet`: that walks every redirect once per spelling group,
        // which is quadratic and never finishes on the whole dump.
        val survivors    = homographs.toSet.filterNot(redirects.contains)
        val hasRealSense = survivors.exists(word => word.partOfSpeech != PartOfSpeech.Other && hasTranslation(word))
        if (hasRealSense)
          survivors.filter(word => word.partOfSpeech == PartOfSpeech.Other && !hasTranslation(word))
        else
          Set.empty[ParsedWord]
      }.toSet

      HomographPlan(redirects, dropped)
    }
  }

  /** One stored form of a lemma, as [[formPairs]] reads it back: ids, the relation, and the form's text. */
  final case class LemmaForm(lemmaId: Long, relation: String, formId: Long, formText: String)

  /** Translations between two lemmas' forms, inferred through the lemma pair itself: if `Haus` translates to `house`,
    * and both have a form tagged `plural` (`Häuser`, `houses`), those two forms translate to each other too. No source
    * states this directly — a plural is its own dictionary entry, not a row in a translation table — so it is derived
    * here the same way [[pivot]] derives German–Hungarian from a shared English sense, and stored with its own origin
    * so a screen can say where it came from.
    *
    * Runs over every lemma pair passed in, direct and pivoted alike, so a German plural picks up a Hungarian one
    * through the same pivoted `Haus`/`ház` pair its singular already relies on.
    *
    * On ids, over forms already stored, so a batch of pairs needs only its own lemmas' forms in memory. A linked word
    * ([[WordLinks]]) is never a stored form, so it never pairs.
    */
  def formPairs(pairs: List[(Long, Long)], forms: List[LemmaForm]): List[(Long, Long)] = {
    // A self-form (`Künstler`, plural `Künstler`) is the lemma's own row, which carries the singular's gender: pairing
    // it would give `artists` the translation `der Künstler`. When a lemma states one relation more than once (a table
    // cell repeated under two spellings) the alphabetically first wins, so a re-import derives the same edge.
    val byLemma = forms
      .filter(form => form.formId != form.lemmaId)
      .groupBy(_.lemmaId)
      .view
      .mapValues(_.groupBy(_.relation).view.mapValues(_.minBy(form => (form.formText, form.formId)).formId).toMap)
      .toMap
    pairs.flatMap { case (source, target) =>
      val sourceForms = byLemma.getOrElse(source, Map.empty)
      val targetForms = byLemma.getOrElse(target, Map.empty)
      sourceForms.keySet.intersect(targetForms.keySet).toList.sorted.map { relation =>
        (sourceForms(relation), targetForms(relation))
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
    *   - `A  <word columns> file region` — a recording of the word, as a Wikimedia Commons file name.
    */
  object SeedFormat {

    def encode(collected: Collected): List[String] = {
      wordLines(collected.words) ++ pairLines(collected.pairs) ++ formLines(collected.forms) ++
        audioLines(collected.audio)
    }

    private def wordColumns(word: ParsedWord): List[String] = {
      List(
        WordLanguage.code(word.language),
        word.text,
        PartOfSpeech.code(word.partOfSpeech),
        Gender.toColumn(word.gender),
      )
    }

    def wordLines(words: Map[ParsedWord, Int]): List[String] = {
      words.toList.sortBy { case (word, rank) => (rank, word.key.toString) }.map { case (word, rank) =>
        ("W" :: wordColumns(word) ::: List(rank.toString)).mkString("\t")
      }
    }

    def pairLines(pairs: List[ParsedPair]): List[String] = {
      pairs.distinct.sortBy(pair => (pair.source.key.toString, pair.target.key.toString)).map { pair =>
        ("T" :: wordColumns(pair.source) ::: wordColumns(pair.target) ::: List(pair.sense.getOrElse("")))
          .mkString("\t")
      }
    }

    def formLines(forms: List[ParsedForm]): List[String] = {
      forms.distinct.sortBy(form => (form.lemma.key.toString, form.form.key.toString, form.relation)).map { form =>
        ("F" :: wordColumns(form.lemma) ::: wordColumns(form.form) ::: List(form.relation)).mkString("\t")
      }
    }

    def audioLines(audio: List[ParsedAudio]): List[String] = {
      audio.distinct.sortBy(audio => (audio.word.key.toString, audio.fileName)).map { audio =>
        ("A" :: wordColumns(audio.word) ::: List(audio.fileName, audio.region)).mkString("\t")
      }
    }

    private def wordAt(columns: Array[String], offset: Int): Option[ParsedWord] = {
      for {
        language <- columns.lift(offset).flatMap(WordLanguage.fromString)
        text     <- columns.lift(offset + 1).map(_.trim).filter(_.nonEmpty)
        pos      <- columns.lift(offset + 2).flatMap(PartOfSpeech.fromString)
        gender    = columns.lift(offset + 3).flatMap(value => Gender.fromColumn(value))
      } yield ParsedWord(language, text, pos, gender)
    }

    /** One line of the file, decoded. */
    enum Record {
      case Word(word: ParsedWord, rank: Int)
      case Pair(pair: ParsedPair)
      case Form(form: ParsedForm)
      case Audio(audio: ParsedAudio)
    }

    /** One line's record, or `None` for a comment, a blank line or a line that does not parse. Blank lines and lines
      * starting with `#` are comments, so the committed sample can be annotated.
      */
    def decodeLine(line: String): Option[Record] = {
      val trimmed = line.trim
      if (trimmed.isEmpty || trimmed.startsWith("#"))
        None
      else {
        val columns = line.split('\t')
        columns.headOption match {
          case Some("W") =>
            wordAt(columns, 1).map { word =>
              Record.Word(word, columns.lift(5).flatMap(_.toIntOption).getOrElse(WordService.unrankedFrequency))
            }
          case Some("T") =>
            for {
              source <- wordAt(columns, 1)
              target <- wordAt(columns, 5)
            } yield Record.Pair(ParsedPair(source, target, columns.lift(9).map(_.trim).filter(_.nonEmpty)))
          case Some("F") =>
            for {
              lemma    <- wordAt(columns, 1)
              form     <- wordAt(columns, 5)
              relation <- columns.lift(9).map(_.trim).filter(_.nonEmpty)
            } yield Record.Form(ParsedForm(lemma, form, relation))
          case Some("A") =>
            for {
              word     <- wordAt(columns, 1)
              fileName <- columns.lift(5).map(_.trim).filter(_.nonEmpty)
            } yield Record.Audio(ParsedAudio(word, fileName, columns.lift(6).map(_.trim).getOrElse("")))
          case _         =>
            None
        }
      }
    }

    /** The whole file in memory. A word named only by a pair, form or recording comes in unranked. */
    def decode(lines: List[String]): Collected = {
      lines.iterator.flatMap(decodeLine).foldLeft(Collected.empty) { (collected, record) =>
        record match {
          case Record.Word(word, rank) =>
            collected.withWord(word, rank)
          case Record.Pair(pair)       =>
            collected
              .withWord(pair.source, WordService.unrankedFrequency)
              .withWord(pair.target, WordService.unrankedFrequency)
              .copy(pairs = pair :: collected.pairs)
          case Record.Form(form)       =>
            collected
              .withWord(form.lemma, WordService.unrankedFrequency)
              .withWord(form.form, WordService.unrankedFrequency)
              .copy(forms = form :: collected.forms)
          case Record.Audio(audio)     =>
            collected
              .withWord(audio.word, WordService.unrankedFrequency)
              .copy(audio = audio :: collected.audio)
        }
      }
    }
  }

  /** A text file's lines, gunzipped if the name ends in `.gz`: a seed, a shard or the dump. */
  private def fileLines(path: String): ZStream[Any, Throwable, String] = {
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

  /** The whole seed in memory: what `--seed --export` rewrites. A plain `--seed` streams it instead ([[Records.seed]]).
    */
  private def readSeed(path: String): Task[Collected] = {
    fileLines(path).runCollect.map(lines => SeedFormat.decode(lines.toList))
  }

  /** What [[store]] writes, one kind at a time. Each call starts the stream again, so a seed file is read once per kind
    * rather than held whole: the whole dump's seed is 13 million lines, more objects than a small server's heap holds.
    */
  trait Records {
    def words: ZStream[Any, Throwable, (ParsedWord, Int)]
    def pairs: ZStream[Any, Throwable, ParsedPair]
    def forms: ZStream[Any, Throwable, ParsedForm]
    def audio: ZStream[Any, Throwable, ParsedAudio]
  }

  object Records {

    /** A seed file, streamed. Each kind reads only its own lines: the record type is the line's first column.
      *
      * No homograph dedupe: `--export` writes a seed after [[dedupeHomographs]], which finds nothing on its own output.
      * A word that only a pair, form or recording names is stored unranked when that record reaches it, as
      * [[SeedFormat.decode]] ranks it.
      */
    def seed(path: String): Records = {
      def of[A](prefix: String)(pick: PartialFunction[SeedFormat.Record, A]): ZStream[Any, Throwable, A] = {
        fileLines(path).filter(_.startsWith(prefix)).map(SeedFormat.decodeLine).collect {
          case Some(record) if pick.isDefinedAt(record) => pick(record)
        }
      }
      new Records {
        def words = of("W\t") { case SeedFormat.Record.Word(word, rank) => (word, rank) }
        def pairs = of("T\t") { case SeedFormat.Record.Pair(pair) => pair }
        def forms = of("F\t") { case SeedFormat.Record.Form(form) => form }
        def audio = of("A\t") { case SeedFormat.Record.Audio(audio) => audio }
      }
    }
  }

  /** A writer for a seed or a shard, closed when the scope ends. */
  private def openWriter(path: String, gzip: Boolean): ZIO[Scope, Throwable, BufferedWriter] = {
    ZIO.fromAutoCloseable(ZIO.attemptBlocking {
      val stream = {
        if (gzip)
          new GZIPOutputStream(new FileOutputStream(path), 1 << 16)
        else
          new FileOutputStream(path)
      }
      new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), 1 << 16)
    })
  }

  /** Line by line: the whole dump's seed is past 2 GB as one string, which no byte array can hold. */
  private def writeLines(writer: BufferedWriter, lines: Iterable[String]): Task[Unit] = {
    ZIO.attemptBlocking {
      lines.foreach { line =>
        writer.write(line)
        writer.write('\n')
      }
    }
  }

  private def writeSeed(path: String, collected: Collected): Task[Unit] = {
    ZIO.scoped(openWriter(path, gzip = path.endsWith(".gz")).flatMap(writeLines(_, SeedFormat.encode(collected))))
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
      textSearch = TextSearch.fold(word.text.toLowerCase),
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

  /** The ids of a batch of words, inserting the ones not stored yet with the rank they come with.
    *
    * Idempotence without an `ON CONFLICT` clause: read the batch's keys first, insert what is missing, read back what
    * was inserted. Every pass calls it on its own batch's words, so no pass needs every word's id in memory. A word an
    * earlier batch stored is found, not inserted again, which also keeps the first of two case variants (`Grammy`,
    * `grammy`) that fall in different batches; [[dedupeByKey]] handles the two in one batch.
    */
  private def idsOf(words: Iterable[(ParsedWord, Int)], now: Long): RIO[WordRepository, Map[ParsedWord, Long]] = {
    def keyOf(row: WordRow) = (row.language, row.textNorm, row.partOfSpeech, row.gender)
    val byLanguage          = dedupeByKey(words.toList).groupBy { case (word, _) => word.language }
    ZIO
      .foreach(byLanguage.toList) { case (language, batch) =>
        val code  = WordLanguage.code(language)
        val norms = batch.map { case (word, _) => word.text.toLowerCase }.distinct
        for {
          existing <- WordRepository.findWordsByKeys(code, norms)
          known     = existing.map(keyOf).toSet
          missing   = batch.collect { case (word, rank) if !known.contains(word.key) => toRow(word, rank, now) }
          // Read back rather than trusting generated keys from a batch insert: `getGeneratedKeys` after an
          // `executeBatch` is not something both drivers agree about.
          stored   <- {
            if (missing.isEmpty)
              ZIO.succeed(existing)
            else
              WordRepository.insertWords(missing) *> WordRepository.findWordsByKeys(code, norms)
          }
        } yield stored.map(row => (keyOf(row), row.id))
      }
      .map { found =>
        val byKey = found.flatten.toMap
        words.iterator.flatMap { case (word, _) => byKey.get(word.key).map(id => (word, id)) }.toMap
      }
  }

  /** [[idsOf]] for words that come with no rank of their own: a pair's, a form's or a recording's. */
  private def idsOfUnranked(words: Iterable[ParsedWord], now: Long): RIO[WordRepository, Map[ParsedWord, Long]] = {
    idsOf(words.map(word => (word, WordService.unrankedFrequency)), now)
  }

  /** Inserts the words that are not there yet, a batch at a time. Answers how many words it saw stored. */
  private def storeWords(words: ZStream[Any, Throwable, (ParsedWord, Int)], now: Long): RIO[WordRepository, Long] = {
    words.grouped(batchSize).mapZIO(batch => idsOf(batch, now).map(_.size.toLong)).runSum
  }

  /** Writes both directions of every edge that is not already recorded. */
  private def storeEdges(edges: List[(Long, Long)], origin: String, now: Long): RIO[WordRepository, Long] = {
    val sources = edges.flatMap { case (source, target) => List(source, target) }.distinct
    for {
      known <- WordRepository.existingTranslationPairs(sources).map(_.toSet)
      // Distinct for the same reason dedupeByKey exists: `word_translations` is unique on
      // (source_word_id, target_word_id, created_by), and two collected pairs can reach the same id pair once
      // case variants have collapsed onto one row.
      rows   = edges
                 .filter { case (source, target) => source != target }
                 .flatMap { case (source, target) => List((source, target), (target, source)) }
                 .distinct
                 .filterNot(known.contains)
                 .map { case (from, to) => WordTranslationRow(0L, from, to, origin, None, now) }
      _     <- WordRepository.insertTranslations(rows)
    } yield rows.size.toLong
  }

  /** A batch of pairs as `(source id, target id)`. */
  private def pairIds(batch: Iterable[ParsedPair], now: Long): RIO[WordRepository, List[(Long, Long)]] = {
    idsOfUnranked(batch.flatMap(pair => List(pair.source, pair.target)), now).map { ids =>
      batch.toList.flatMap(pair => ids.get(pair.source).zip(ids.get(pair.target)))
    }
  }

  /** Writes both directions of every pair that is not already recorded, a batch at a time. */
  private def storePairs(pairs: List[ParsedPair], origin: String, now: Long): RIO[WordRepository, Long] = {
    ZIO
      .foreach(pairs.grouped(batchSize).toList) { batch =>
        pairIds(batch, now).flatMap(edges => storeEdges(edges, origin, now))
      }
      .map(_.sum)
  }

  /** Writes the [[formPairs]] of every pair, a batch at a time, reading each batch's lemmas' forms back from the
    * database. Runs after [[storeForms]], so the forms are there. Only the dictionary's own forms count: a reader's
    * form row is theirs, not a fact to derive shared translations from.
    */
  private def storeFormPairs(pairs: List[ParsedPair], now: Long): RIO[WordRepository, Long] = {
    ZIO
      .foreach(pairs.grouped(batchSize).toList) { batch =>
        for {
          edges  <- pairIds(batch, now)
          stored <-
            WordRepository.formsContextOf(edges.flatMap { case (source, target) => List(source, target) }.distinct)
          forms   = stored.collect {
                      case (form, word) if form.origin == WordSource.dictionary =>
                        LemmaForm(form.lemmaWordId, form.relation, form.formWordId, word.text)
                    }
          count  <- storeEdges(formPairs(edges, forms), WordService.formOrigin, now)
        } yield count
      }
      .map(_.sum)
  }

  /** The `word_forms` rows for these forms. A form spelled like its own lemma (`Künstler`, plural `Künstler`; `put`,
    * past `put`) is kept as a self-link: it is how the word page knows the plural, and it does not make the word a form
    * (see `WordRepository.insertForms`). A linked word ([[WordLinks]]) is no form, and goes to [[linkEdges]]. A form
    * whose lemma or form word has no id contributes nothing.
    *
    * Pulled out as a pure function, like [[dedupeByKey]] and [[pivot]], so it is unit-testable without a database.
    */
  def formEdges(forms: List[ParsedForm], ids: Map[ParsedWord, Long]): List[(Long, Long, String)] = {
    forms
      .filter(form => WordLinks.of(form).isEmpty)
      .flatMap { form =>
        for {
          lemmaId <- ids.get(form.lemma)
          formId  <- ids.get(form.form)
        } yield (lemmaId, formId, form.relation)
      }
      .distinct
  }

  /** The `word_links` rows for the forms that are linked words, both directions of each. */
  def linkEdges(forms: List[ParsedForm], ids: Map[ParsedWord, Long]): List[(Long, Long, String)] = {
    forms.flatMap { form =>
      for {
        (kind, back) <- WordLinks.of(form).toList
        lemmaId      <- ids.get(form.lemma).toList
        formId       <- ids.get(form.form).toList
        if lemmaId != formId
        edge         <- List((lemmaId, formId, WordLinkKind.code(kind)), (formId, lemmaId, WordLinkKind.code(back)))
      } yield edge
    }.distinct
  }

  private def storeLinkEdges(edges: List[(Long, Long, String)], now: Long): RIO[WordRepository, Long] = {
    for {
      known <- WordRepository.existingLinks(edges.map { case (wordId, _, _) => wordId }.distinct).map(_.toSet)
      rows   = edges.filterNot(known.contains).map { case (wordId, linkedId, kind) =>
                 WordLinkRow(0L, wordId, linkedId, kind, now)
               }
      _     <- WordRepository.insertLinks(rows)
    } yield rows.size.toLong
  }

  private def storeFormEdges(edges: List[(Long, Long, String)], now: Long): RIO[WordRepository, Long] = {
    for {
      known <- WordRepository.existingFormRelations(edges.map { case (lemmaId, _, _) => lemmaId }.distinct).map(_.toSet)
      rows   = edges.filterNot(known.contains).map { case (lemmaId, formId, relation) =>
                 WordFormRow(0L, lemmaId, formId, relation, now)
               }
      _     <- WordRepository.insertForms(rows)
    } yield rows.size.toLong
  }

  /** Writes every form relation and every word link that is not already recorded, a batch at a time. Answers the two
    * counts. Unlike a translation, a form relation is directional and stored once -- `formsOf`/`lemmaOf` answer the two
    * directions with two different queries, rather than two rows.
    */
  private def storeForms(forms: ZStream[Any, Throwable, ParsedForm], now: Long): RIO[WordRepository, (Long, Long)] = {
    forms
      .grouped(batchSize)
      .mapZIO { chunk =>
        val batch = chunk.toList
        for {
          ids   <- idsOfUnranked(batch.flatMap(form => List(form.lemma, form.form)), now)
          forms <- storeFormEdges(formEdges(batch, ids), now)
          links <- storeLinkEdges(linkEdges(batch, ids), now)
        } yield (forms, links)
      }
      .runFold((0L, 0L)) { case ((forms, links), (moreForms, moreLinks)) => (forms + moreForms, links + moreLinks) }
  }

  /** Writes every recording that is not already recorded, a batch at a time. */
  private def storeAudio(audio: ZStream[Any, Throwable, ParsedAudio], now: Long): RIO[WordRepository, Long] = {
    audio
      .grouped(batchSize)
      .mapZIO { batch =>
        for {
          ids   <- idsOfUnranked(batch.map(_.word), now)
          rows   = batch.toList
                     .flatMap(a => ids.get(a.word).map(id => (id, a.fileName, a.region)))
                     .distinctBy { case (id, fileName, _) => (id, fileName) }
          known <- WordRepository.existingAudio(rows.map { case (id, _, _) => id }.distinct).map(_.toSet)
          fresh  = rows.filterNot { case (id, fileName, _) => known.contains((id, fileName)) }.map {
                     case (id, fileName, region) => WordAudioRow(0L, id, fileName, region, now)
                   }
          _     <- WordRepository.insertAudio(fresh)
        } yield fresh.size.toLong
      }
      .runSum
  }

  /** Writes one import, one kind of record at a time and a batch at a time. Only the translation pairs are held whole,
    * since the pivot joins every pair of an English sense; they are a few hundred thousand where the forms are
    * millions.
    */
  private def store(records: Records): RIO[WordRepository & DataSource & AppConfig, Unit] = {
    for {
      config        <- ZIO.service[AppConfig]
      dataSource    <- ZIO.service[DataSource]
      // The same migration `Main` runs, and for the same reason it is safe to run twice: without it a fresh clone has
      // to start the server once before it can load the dictionary, which is a footgun rather than a step.
      _             <- FlywayMigrator.migrate(dataSource, Some(config.db.schema))
      now           <- Clock.currentTime(TimeUnit.MILLISECONDS)
      words         <- storeWords(records.words, now)
      _             <- ZIO.logInfo(s"Stored $words word(s)")
      pairs         <- records.pairs.runCollect.map(_.toList)
      direct        <- storePairs(pairs, WordService.dictionaryOrigin, now)
      _             <- ZIO.logInfo(s"Stored $direct direct translation row(s)")
      // The pivot runs over the read pairs rather than the stored ones, so a re-import derives the same set and inserts
      // none of it a second time.
      pivoted        = pivot(pairs)
      inferred      <- storePairs(pivoted, WordService.pivotOrigin, now)
      _             <- ZIO.logInfo(s"Stored $inferred inferred German-Hungarian row(s)")
      stored        <- storeForms(records.forms, now)
      (forms, links) = stored
      _             <- ZIO.logInfo(s"Stored $forms form relation row(s)")
      _             <- ZIO.logInfo(s"Stored $links word link row(s)")
      formLinked    <- storeFormPairs(pairs ++ pivoted, now)
      _             <- ZIO.logInfo(s"Stored $formLinked form-to-form translation row(s)")
      audio         <- storeAudio(records.audio, now)
      _             <- ZIO.logInfo(s"Stored $audio recording row(s)")
    } yield ()
  }

  private def loadFrequencies(options: Options): Task[Map[WordLanguage, Map[String, Int]]] = {
    ZIO
      .foreach(options.frequencies.toList) { directory =>
        ZIO
          .foreach(options.languages.toList)(language => readFrequencies(directory, language).map((language, _)))
          .map(_.toMap)
      }
      .map(_.headOption.getOrElse(Map.empty[WordLanguage, Map[String, Int]]))
  }

  /** A dump or its shards, turned into a seed. Without `--export` the seed goes to a temporary file and is then
    * streamed into the database, the way a deployment loads one, so the heap does not grow with the import.
    */
  private def importDump(source: DumpSource, options: Options): Task[Unit] = {
    val missingShards = {
      options.shards.toList.flatMap(directory => {
        options.languages.toList.map(shardPath(directory, _)).filterNot(path => Files.exists(Path.of(path)))
      })
    }
    for {
      _           <- ZIO.when(missingShards.nonEmpty)(
                       ZIO.fail(
                         new IllegalArgumentException(
                           s"No shard at ${missingShards.mkString(", ")}; cut them first with --raw <dump> --extract <dir>"
                         )
                       )
                     )
      frequencies <- loadFrequencies(options)
      _           <- options.exportTo match {
                       case Some(path) =>
                         exportDump(source, options, frequencies, path)
                       case None       =>
                         ZIO.acquireReleaseWith(
                           ZIO.attemptBlocking(Files.createTempFile("gathedge-seed", ".tsv.gz"))
                         )(path => ZIO.attemptBlocking(Files.deleteIfExists(path)).orDie) { path =>
                           exportDump(source, options, frequencies, path.toString) *>
                             store(Records.seed(path.toString))
                               .provide(AppConfig.live, DataSourceFactory.postgresLive, WordRepository.live)
                         }
                     }
    } yield ()
  }

  /** `--seed <path> --export <path>`: reads a seed whole, dedupes it and writes it again. */
  private def rewriteSeed(options: Options, exportTo: String): Task[Unit] = {
    for {
      loaded   <- readSeed(options.seedPath.getOrElse(defaultSeedPath))
      _        <- ZIO.logInfo("Deduping homographs...")
      collected = dedupeHomographs(loaded)
      _        <- ZIO.logInfo(s"Writing seed to $exportTo...")
      _        <- writeSeed(exportTo, collected)
    } yield ()
  }

  def run: ZIO[ZIOAppArgs, Any, Any] = {
    val imported = for {
      args    <- ZIOAppArgs.getArgs
      options <- ZIO.fromEither(parseArgs(args.toList)).mapError(new IllegalArgumentException(_))
      _       <- (options.raw, options.shards, options.extractTo, options.exportTo) match {
                   case (Some(dump), _, Some(directory), _) =>
                     extract(dump, directory, options.languages)
                   case (Some(dump), _, None, _)            =>
                     importDump(DumpSource.dump(dump), options)
                   case (_, Some(directory), _, _)          =>
                     importDump(DumpSource.shards(directory), options)
                   case (_, _, _, Some(exportTo))           =>
                     rewriteSeed(options, exportTo)
                   // A seed is loaded the way a server loads one: streamed, so the heap it needs does not grow with
                   // the seed.
                   case _                                   =>
                     val path = options.seedPath.getOrElse(defaultSeedPath)
                     ZIO.logInfo(s"Streaming seed $path...") *>
                       store(Records.seed(path)).provide(AppConfig.live, DataSourceFactory.postgresLive, WordRepository.live)
                 }
      _       <- ZIO.logInfo("Dictionary import finished")
    } yield ()

    GcStats.logPeriodically().forkDaemon.flatMap(gcFiber => imported.ensuring(gcFiber.interrupt))
  }
}
