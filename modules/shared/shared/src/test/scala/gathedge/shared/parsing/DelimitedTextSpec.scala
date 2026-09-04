package gathedge.shared.parsing

import zio.test.*

/** [[DelimitedText.sniff]] answering `None` is what keeps ordinary pasted prose on the free-text import path, so the
  * negative cases here matter as much as the positive ones.
  */
object DelimitedTextSpec extends ZIOSpecDefault {

  def spec = {
    suite("DelimitedText")(
      test("recognises a tab-separated list") {
        val content = "kutya\tder Hund\nmacska\tdie Katze\njó napot\tguten Tag"
        assertTrue(
          DelimitedText.sniff(content).contains(Delimiter.Tab),
          DelimitedText.parse(content, Delimiter.Tab) == List(
            List("kutya", "der Hund"),
            List("macska", "die Katze"),
            List("jó napot", "guten Tag"),
          ),
        )
      },
      test("recognises comma- and semicolon-separated lists") {
        assertTrue(
          DelimitedText.sniff("dog,Hund\ncat,Katze\nhouse,Haus").contains(Delimiter.Comma),
          DelimitedText.sniff("dog;Hund\ncat;Katze\nhouse;Haus").contains(Delimiter.Semicolon),
        )
      },
      test("prefers the tab when a row contains both a tab and a comma") {
        // A comma inside a cell is ordinary punctuation; a tab almost never is. Getting this backwards would split
        // "guten Tag, hallo" into two columns.
        val content = "kutya\tder Hund, a kutya\nmacska\tdie Katze, a macska"
        assertTrue(
          DelimitedText.sniff(content).contains(Delimiter.Tab),
          DelimitedText.parse(content, Delimiter.Tab).head == List("kutya", "der Hund, a kutya"),
        )
      },
      test("a one-column word list is not a table") {
        assertTrue(DelimitedText.sniff("Hund\nKatze\nHaus\nBaum").isEmpty)
      },
      test("prose with occasional commas is not a table") {
        val prose = {
          """The quick brown fox jumps over the lazy dog.
            |Some lines have a comma, and some do not.
            |Most of them do not.
            |This one does not either.
            |Nor this one.""".stripMargin
        }
        assertTrue(DelimitedText.sniff(prose).isEmpty)
      },
      test("empty content is not a table") {
        assertTrue(DelimitedText.sniff("").isEmpty, DelimitedText.sniff("   \n  \n").isEmpty)
      },
      test("a quoted field keeps its delimiter and its doubled quote") {
        val content = "\"Hund, der\",dog\n\"say \"\"hi\"\"\",hallo"
        assertTrue(
          DelimitedText.parse(content, Delimiter.Comma) == List(
            List("Hund, der", "dog"),
            List("say \"hi\"", "hallo"),
          )
        )
      },
      test("a quoted field may span newlines") {
        val content = "\"line one\nline two\",dog\nHaus,house"
        assertTrue(
          DelimitedText.parse(content, Delimiter.Comma) == List(
            List("line one\nline two", "dog"),
            List("Haus", "house"),
          )
        )
      },
      test("blank rows are dropped and short rows padded to the widest") {
        val content = "a\tb\tc\n\n\nd\te"
        assertTrue(
          DelimitedText.parse(content, Delimiter.Tab) == List(
            List("a", "b", "c"),
            List("d", "e", ""),
          )
        )
      },
      test("carriage returns from a Windows file are trimmed") {
        val content = "kutya\tder Hund\r\nmacska\tdie Katze\r\n"
        assertTrue(
          DelimitedText.sniff(content).contains(Delimiter.Tab),
          DelimitedText.parse(content, Delimiter.Tab) == List(
            List("kutya", "der Hund"),
            List("macska", "die Katze"),
          ),
        )
      },
      test("a mostly-uniform table survives one ragged row") {
        // 80% agreement, so a stray note at the end of a real export must not disqualify the whole file.
        val content = "a\tb\nc\td\ne\tf\ng\th\ni"
        assertTrue(DelimitedText.sniff(content).contains(Delimiter.Tab))
      },
      test("a third column is preserved for the extra-column mapping") {
        val content = "kutya\tder Hund\thn\nmacska\tKatze\tw"
        assertTrue(
          DelimitedText.parse(content, Delimiter.Tab) == List(
            List("kutya", "der Hund", "hn"),
            List("macska", "Katze", "w"),
          )
        )
      },
    )
  }
}
