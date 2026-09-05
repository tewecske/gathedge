package gathedge.shared.parsing

/** The delimiters a pasted or uploaded vocabulary list might use between columns.
  *
  * Ordered by how strongly each one implies a table: a tab is almost never ordinary punctuation, a semicolon rarely is,
  * and a comma very often is. [[DelimitedText.sniff]] resolves a tie in that order for the same reason.
  */
enum Delimiter derives CanEqual {
  case Tab, Semicolon, Comma
}

object Delimiter {

  val all: List[Delimiter] = List(Tab, Semicolon, Comma)

  def char(delimiter: Delimiter): Char = {
    delimiter match {
      case Tab       =>
        '\t'
      case Semicolon =>
        ';'
      case Comma     =>
        ','
    }
  }
}

/** Turns a delimited paste or upload into a grid, and decides whether it is one at all.
  *
  * Lives in `shared` rather than the frontend because both sides need it: the browser parses to build the column
  * mapping preview, and `sharedJVM/test` is a far better place to pin the parser's behaviour than a Scala.js suite.
  */
object DelimitedText {

  /** How many lines [[sniff]] looks at. Enough to see a consistent shape, few enough that sniffing a two-megabyte paste
    * three times over stays cheap.
    */
  private val sniffLines = 50

  /** How much of the sample must agree on a field count before the content counts as a table. A real spreadsheet export
    * is uniform; prose that happens to contain commas is not.
    */
  private val agreement = 0.8

  /** Answers which delimiter this content is built on, or `None` when it is not a table.
    *
    * `None` is the important answer: it is what keeps ordinary pasted prose on the existing free-text import path
    * rather than dragging the reader through a column mapping step for a single-column word list.
    */
  def sniff(content: String): Option[Delimiter] = {
    val sample = content.linesIterator.take(sniffLines).mkString("\n")

    Delimiter.all
      .map(delimiter => delimiter -> score(sample, delimiter))
      .collectFirst { case (delimiter, Some(_)) => delimiter }
  }

  /** The modal field count of `sample` under `delimiter`, when that mode is a real table: at least two columns, agreed
    * on by at least [[agreement]] of the rows.
    *
    * Scored on [[split]]'s '''unpadded''' rows, and on how many fields a row has rather than how many of them are
    * filled. Both halves of that are load-bearing. A real vocabulary list leaves cells empty — an article column is
    * blank on every adjective in the file — so counting filled cells reads a uniform three-column table as a mixture of
    * twos and threes and drops it below [[agreement]]. Padding would break it the other way: once every row is widened
    * to the widest, prose containing one stray comma reports two fields on every line and passes.
    */
  private def score(sample: String, delimiter: Delimiter): Option[Int] = {
    val rows = split(sample, delimiter)
    if (rows.isEmpty) None
    else {
      val widths       = rows.map(_.size)
      val (mode, hits) = widths
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toList
        .sortBy { case (width, count) => (-count, -width) }
        .head
      Option.when(mode >= 2 && hits.toDouble / rows.size >= agreement)(mode)
    }
  }

  /** Splits `content` into rows of fields.
    *
    * RFC 4180 quoting: a field wrapped in `"` may contain the delimiter and newlines, and `""` inside it is one literal
    * quote. Wholly blank rows are dropped, a trailing `\r` is trimmed, and short rows are padded to the widest row so
    * every row can be indexed by column.
    */
  def parse(content: String, delimiter: Delimiter): List[List[String]] = {
    val parsed = split(content, delimiter)
    val width  = parsed.map(_.size).maxOption.getOrElse(0)
    parsed.map(row => row.padTo(width, ""))
  }

  /** [[parse]] without the padding: every row exactly as many fields as it was written with. Only [[score]] wants this
    * — a caller indexing by column wants the padding — but the distinction is what tells a table from prose.
    */
  private def split(content: String, delimiter: Delimiter): List[List[String]] = {
    val separator = Delimiter.char(delimiter)
    val rows      = List.newBuilder[List[String]]
    val fields    = List.newBuilder[String]
    val field     = new StringBuilder
    var quoted    = false
    var index     = 0

    def endField(): Unit = {
      fields += field.toString.stripSuffix("\r").trim
      field.clear()
    }

    def endRow(): Unit = {
      endField()
      val row = fields.result()
      fields.clear()
      if (row.exists(_.nonEmpty)) rows += row
    }

    while (index < content.length) {
      val ch = content.charAt(index)
      if (quoted) {
        if (ch == '"') {
          if (index + 1 < content.length && content.charAt(index + 1) == '"') {
            field += '"'
            index += 1
          } else quoted = false
        } else field += ch
      } else if (ch == '"') quoted = true
      else if (ch == separator) endField()
      else if (ch == '\n') endRow()
      else field += ch
      index += 1
    }
    endRow()
    rows.result()
  }
}
