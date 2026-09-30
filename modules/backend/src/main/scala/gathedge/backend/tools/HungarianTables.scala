package gathedge.backend.tools

import gathedge.backend.tools.WiktextractParser.RawForm
import gathedge.shared.domain.WordLanguage

/** Reads the Hungarian tables wiktextract could not.
  *
  * The regular Hungarian conjugation templates (`hu-conj-ok`, `hu-conj-ek`, ...) come out of wiktextract with every
  * cell tagged `error-unrecognized-form`. The cells hold the right forms in the right order, but the tags are wrong:
  * each person tag sits one cell off (`látok`, "I see", is tagged second person), and no cell names its mood or tense.
  * About 4,700 verbs, 1.4 million cells, are affected. The table's layout is fixed, so this object rebuilds the tags
  * from each cell's position instead.
  *
  * The layout, block by block: six persons with the indefinite conjugation, six with the definite one, then the
  * `-lak`/`-lek` form ("I … you"). The blocks come in this order: present, past, archaic past, archaic future,
  * conditional, subjunctive. The infinitive, its six personal forms, the verbal noun, the participles and the causative
  * follow. A cell can repeat (`adsz`, `adsz`: the template writes 2nd person informal and formal) or hold alternatives
  * (`adj or` `adjál`, `or adnók`), and English notes sit between the blocks.
  *
  * Each block is checked before it is used, and a block that fails its check is dropped, never guessed at:
  *
  *   - a block must come to exactly six persons, once repeats are merged and alternatives joined;
  *   - a potential table's present 3rd person singular must end in `-hat`/`-het` (an `-ik` verb adds `adózhatik`);
  *   - the past block's cells must carry the `past` tag (the one tag the dump does get right);
  *   - every conditional 3rd person singular must end in `-na`/`-ne`, and every subjunctive one in `-on`/`-en`/`-ön`
  *     (or `-ék`, the second form an `-ik` verb has).
  *
  * The archaic past and future are not taught and not imported. Present and past are the first two blocks, and the
  * conditional and subjunctive are the last two, so a broken archaic row costs nothing else.
  *
  * The second conjugation table of a verb is its potential (`adhat`, "can give"), and gets the `potential` tag.
  */
object HungarianTables {

  /** One piece of a lemma's `forms[]`: a row read on its own, or a whole conjugation table to rebuild. */
  enum Chunk {
    case Row(raw: RawForm, tags: List[String])
    case Rebuilt(table: List[(String, List[String])], potential: Boolean)
  }

  private val persons: List[List[String]] = {
    List(
      List("first-person", "singular"),
      List("second-person", "singular"),
      List("third-person", "singular"),
      List("first-person", "plural"),
      List("second-person", "plural"),
      List("third-person", "plural"),
    )
  }

  private val hungarianWord = "[\\p{L}\\p{M}]+".r

  private def isError(tag: String): Boolean = tag.startsWith("error-")

  private def isTableStart(raw: RawForm, tags: List[String]): Boolean = {
    raw.source.contains("conjugation") && tags.contains("table-tags")
  }

  /** Splits a lemma's rows into chunks. Only a Hungarian conjugation table with an `error-*` cell is rebuilt; every
    * other row, in every language, stays a [[Chunk.Row]].
    */
  def split(language: WordLanguage, rows: List[(RawForm, List[String])]): List[Chunk] = {
    if (language != WordLanguage.Hu)
      rows.map { case (raw, tags) => Chunk.Row(raw, tags) }
    else {
      val (chunks, _) = tablesOf(rows).foldLeft((List.empty[Chunk], 0)) {
        case ((acc, tableIndex), Left((raw, tags)))                                  =>
          (acc :+ Chunk.Row(raw, tags), tableIndex)
        case ((acc, tableIndex), Right(table)) if table.exists(_._2.exists(isError)) =>
          val cells = table.collect {
            case (raw, tags) if !tags.contains("table-tags") && !tags.contains("inflection-template") =>
              (raw.form.trim, tags.filterNot(isError))
          }
          (acc :+ Chunk.Rebuilt(cells, potential = tableIndex % 2 == 1), tableIndex + 1)
        case ((acc, tableIndex), Right(table))                                       =>
          (acc ++ table.map { case (raw, tags) => Chunk.Row(raw, tags) }, tableIndex + 1)
      }
      chunks
    }
  }

  /** Groups rows into conjugation tables: a table runs from its `table-tags` header to the next header or the first row
    * from another source. A row outside any table is `Left`.
    */
  private def tablesOf(
    rows: List[(RawForm, List[String])]
  ): List[Either[(RawForm, List[String]), List[(RawForm, List[String])]]] = {
    rows.foldLeft(List.empty[Either[(RawForm, List[String]), List[(RawForm, List[String])]]]) {
      case (acc, row @ (raw, tags)) if isTableStart(raw, tags)                         =>
        acc :+ Right(List(row))
      case (acc :+ Right(table), row @ (raw, _)) if raw.source.contains("conjugation") =>
        acc :+ Right(table :+ row)
      case (acc, row)                                                                  =>
        acc :+ Left(row)
    }
  }

  /** What a cell holds: the forms in it, and whether it joins the cell before or after it (`adj or` / `adjál`, `adnánk`
    * / `or adnók`). A form marked `*` is rare: it joins its neighbour's slot and is not imported. `None` for a note or
    * a placeholder.
    */
  private final case class Cell(forms: List[String], joinsNext: Boolean, joinsPrevious: Boolean)

  private def cellOf(text: String): Option[Cell] = {
    if (text == "or")
      Some(Cell(Nil, joinsNext = true, joinsPrevious = false))
    else {
      val joinsNext     = text.endsWith(" or")
      val joinsPrevious = text.startsWith("or ")
      val alternatives  = WiktextractParser.alternativesOf(text)
      val (rare, plain) = alternatives.partition(_.endsWith("*"))
      Option.when((plain ++ rare.map(_.stripSuffix("*"))).forall(hungarianWord.matches) && alternatives.nonEmpty)(
        Cell(plain, joinsNext, joinsPrevious || (plain.isEmpty && rare.nonEmpty))
      )
    }
  }

  /** Merges a block's cells into its person slots: a repeated cell is one slot, a joined cell adds to its neighbour's.
    * `None` if a note or placeholder sits inside the block.
    */
  private def slotsOf(texts: List[String]): Option[List[List[String]]] = {
    texts
      .foldLeft(Option((List.empty[List[String]], false))) {
        case (None, _)                      =>
          None
        case (Some((slots, pending)), text) =>
          cellOf(text).map { cell =>
            if (cell.forms.isEmpty)
              (slots, pending || cell.joinsNext)
            else if ((cell.joinsPrevious || pending) && slots.nonEmpty)
              (slots.init :+ (slots.last ++ cell.forms.filterNot(slots.last.contains)), cell.joinsNext)
            else if (slots.nonEmpty && cell.forms.forall(slots.last.contains))
              (slots, cell.joinsNext)
            else
              (slots :+ cell.forms, cell.joinsNext)
          }
      }
      .map(_._1)
  }

  private enum Kind {
    case Indefinite, Definite, Lak, Infinitive, Placeholder, Note, Other
  }

  /** One tense's worth of a table: its six indefinite persons, its six definite ones, and its `-lak` form. `definite`
    * is `None` until the table has said something about it; `Some(Nil)` when it has none (an intransitive verb) or its
    * run did not come to six.
    */
  private final case class Block(
    indefinite: List[List[String]],
    definite: Option[List[List[String]]],
    lak: Option[String],
  )

  /** Classifies each cell before blocks are grouped. The order of the tests matters: a note can carry any tag, and the
    * `-lak` cell right after a definite block is tagged like neither.
    */
  private def kindsOf(cells: List[(String, List[String])]): List[Kind] = {
    cells
      .foldLeft((List.empty[Kind], false)) { case ((kinds, afterDefinite), (text, tags)) =>
        val kind               = {
          if (text == "-") Kind.Placeholder
          else if (cellOf(text).isEmpty) Kind.Note
          else if (text.startsWith("or ") && kinds.lastOption.exists(k => k == Kind.Indefinite || k == Kind.Definite))
            kinds.last
          else if (afterDefinite && !tags.contains("definite") && (text.endsWith("lak") || text.endsWith("lek")))
            Kind.Lak
          else if (tags.contains("infinitive")) Kind.Infinitive
          else if (tags.contains("definite")) Kind.Definite
          else if (tags.contains("indefinite")) Kind.Indefinite
          else Kind.Other
        }
        val stillAfterDefinite = kind == Kind.Definite || (kind == Kind.Placeholder && afterDefinite)
        (kinds :+ kind, stillAfterDefinite)
      }
      ._1
  }

  /** Consecutive cells of one kind. Placeholders join a definite run (an intransitive verb's empty definite block). */
  private def runsOf(cells: List[(String, List[String])]): List[(Kind, List[String])] = {
    cells.zip(kindsOf(cells)).foldLeft(List.empty[(Kind, List[String])]) {
      case (runs :+ ((last, texts)), ((text, _), kind))
          if (kind == last && (kind == Kind.Indefinite || kind == Kind.Definite || kind == Kind.Infinitive)) ||
            (kind == Kind.Placeholder && (last == Kind.Definite || last == Kind.Placeholder)) =>
        runs :+ ((last, texts :+ text))
      case (runs, ((text, _), kind)) =>
        runs :+ ((kind, List(text)))
    }
  }

  /** The finite blocks in table order, `None` for an indefinite run that did not come to a multiple of six. A run of
    * twelve is two blocks with no definite forms between them (an intransitive verb's archaic future and conditional).
    */
  private def blocksOf(runs: List[(Kind, List[String])]): List[Option[Block]] = {
    runs.foldLeft(List.empty[Option[Block]]) {
      case (blocks, (Kind.Indefinite, texts))                                        =>
        slotsOf(texts) match {
          case Some(slots) if slots.nonEmpty && slots.size % 6 == 0 =>
            blocks ++ slots.grouped(6).map(six => Some(Block(six, None, None)))
          case _ =>
            blocks :+ None
        }
      case (blocks :+ Some(block), (Kind.Definite, texts)) if block.definite.isEmpty =>
        blocks :+ Some(block.copy(definite = Some(slotsOf(texts).filter(_.size == 6).getOrElse(Nil))))
      case (blocks :+ Some(block), (Kind.Placeholder, _)) if block.definite.isEmpty  =>
        blocks :+ Some(block.copy(definite = Some(Nil)))
      case (blocks :+ Some(block), (Kind.Lak, List(text))) if block.lak.isEmpty      =>
        blocks :+ Some(block.copy(lak = Some(text)))
      case (blocks, _)                                                               =>
        blocks
    }
  }

  private def thirdSingular(block: Block): List[String] = block.indefinite(2)

  /** The rebuilt forms of one conjugation table, each with its tags. */
  def conjugation(cells: List[(String, List[String])], potential: Boolean): List[(String, List[String])] = {
    val runs   = runsOf(cells)
    val blocks = blocksOf(runs)
    val finite = {
      if (blocks.size < 4)
        Nil
      else {
        val present     = blocks.head.filter(block =>
          !potential || thirdSingular(block).exists(form => form.endsWith("hat") || form.endsWith("het"))
        )
        val past        = blocks(1).filter(block => {
          block.indefinite.flatten.forall(form => {
            cells.exists { case (text, tags) =>
              tags.contains("past") && WiktextractParser.alternativesOf(text).contains(form)
            }
          })
        })
        val conditional = blocks(blocks.size - 2).filter(block =>
          thirdSingular(block).forall(form => form.endsWith("na") || form.endsWith("ne"))
        )
        val subjunctive = blocks.last.filter(block =>
          thirdSingular(block).forall(form => List("on", "en", "ön", "ék").exists(form.endsWith))
        )
        List(
          present     -> List("indicative", "present"),
          past        -> List("indicative", "past"),
          conditional -> List("conditional", "present"),
          subjunctive -> List("subjunctive", "present"),
        ).flatMap { case (block, mood) => block.toList.flatMap(finiteForms(_, mood)) }
      }
    }
    val rows   = finite ++ infinitives(runs) ++ nonFinite(cells)
    if (potential) rows.map { case (form, tags) => (form, tags :+ "potential") } else rows
  }

  private def finiteForms(block: Block, mood: List[String]): List[(String, List[String])] = {
    def persons(slots: List[List[String]], conjugation: String) = {
      slots.zip(this.persons).flatMap { case (forms, person) =>
        forms.map(form => (form, mood ++ (conjugation :: person)))
      }
    }
    persons(block.indefinite, "indefinite") ++
      block.definite.toList.flatMap(persons(_, "definite")) ++
      block.lak.toList.map(form => (form, mood ++ List("first-person", "singular", "object-second-person")))
  }

  /** The first infinitive cell is the plain infinitive (`adni`); the six after it are its personal forms (`adnom`). */
  private def infinitives(runs: List[(Kind, List[String])]): List[(String, List[String])] = {
    runs.collectFirst { case (Kind.Infinitive, first :: personal) => (first, personal) }.toList.flatMap {
      case (first, personal) =>
        val plain = Option.when(hungarianWord.matches(first))((first, List("infinitive"))).toList
        val rest  = slotsOf(personal).filter(_.size == 6).toList.flatMap { slots =>
          slots.zip(persons).flatMap { case (forms, person) => forms.map(form => (form, "infinitive" :: person)) }
        }
        plain ++ rest
    }
  }

  /** The verbal noun, the participles, the adverbial participle and the causative. Their tags are as unreliable as the
    * rest, so a participle is placed by its ending: `-andó`/`-endő` future, `-ó`/`-ő` present, `-t` past.
    */
  private def nonFinite(cells: List[(String, List[String])]): List[(String, List[String])] = {
    val afterNoun = cells.dropWhile { case (_, tags) => !tags.contains("noun-from-verb") }
    afterNoun.flatMap { case (text, tags) =>
      val forms = cellOf(text).toList.flatMap(_.forms)
      if (tags.contains("noun-from-verb")) forms.map(form => (form, List("noun-from-verb")))
      else if (tags.contains("adverbial")) forms.map(form => (form, List("adverbial", "participle")))
      else if (tags.contains("causative")) forms.map(form => (form, List("causative")))
      else {
        forms.flatMap { form =>
          if (form.endsWith("andó") || form.endsWith("endő")) List((form, List("participle", "future")))
          else if (form.endsWith("ó") || form.endsWith("ő")) List((form, List("participle", "present")))
          else if (form.endsWith("t")) List((form, List("participle", "past")))
          else Nil
        }
      }
    }
  }

  /** The dump names Hungarian's two conjugations two ways: `definite`/`indefinite` on the inflected forms' own pages
    * (`látom`: "first-person singular indicative present definite of lát"), `object-definite`/`object-indefinite` in
    * the tables it can read. One fact must be one relation, or the same form would be stored twice under two labels, so
    * a Hungarian row always gets the first pair. The rebuilt tables use it too.
    */
  def canonicalTags(language: WordLanguage, tags: List[String]): List[String] = {
    if (language != WordLanguage.Hu)
      tags
    else {
      tags.map {
        case "object-definite"   =>
          "definite"
        case "object-indefinite" =>
          "indefinite"
        case other               =>
          other
      }
    }
  }

  /** The `-é` and `-éi` forms of a Hungarian noun (`házé`, "the house's"; `házéi`, "the house's ones"). The dump tags
    * them only with the possessor's number and `error-unrecognized-form`; the ending says which of the two it is.
    * `None` for any other row.
    */
  def possessor(language: WordLanguage, raw: RawForm, tags: List[String]): Option[List[String]] = {
    val text = raw.form.trim
    val rest = tags.filterNot(isError)
    Option
      .when(
        language == WordLanguage.Hu && raw.source.contains("declension") && tags.exists(isError) &&
          (rest == List("singular") || rest == List("plural"))
      ) {
        if (text.endsWith("éi")) Some(List("possessor", rest.head, "possessed-many"))
        else if (text.endsWith("é")) Some(List("possessor", rest.head, "possessed-single"))
        else None
      }
      .flatten
  }
}
