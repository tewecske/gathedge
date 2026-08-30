package gathedge.frontend.components

import gathedge.shared.domain.{PartOfSpeech, WordLanguage}
import gathedge.shared.dto.{TagExportEntry, TagExportFile, TagExportTag, TagExportWord}
import zio.json._
import zio.test._

/** The two pure decisions the import dialog makes before it ever calls the server: is this a file it can read, and
  * which of its tags clash with names the account already owns.
  */
object TagImportDialogSpec extends ZIOSpecDefault {

  private val file = TagExportFile(
    TagExportFile.currentVersion,
    0L,
    List(
      TagExportTag(
        "Lesson1",
        List(TagExportEntry(TagExportWord(WordLanguage.De, "Haus", PartOfSpeech.Noun, None), Nil)),
      ),
      TagExportTag("weather", Nil),
    ),
  )

  def spec = {
    suite("TagImportDialog")(
      test("parse accepts a current-version file and rejects junk or a future version") {
        assertTrue(
          TagImportDialog.parse(file.toJson) == Right(file),
          TagImportDialog.parse("not json").isLeft,
          TagImportDialog.parse(file.copy(version = TagExportFile.currentVersion + 1).toJson).isLeft,
        )
      },
      test("clashes are the file tags whose normalized name the account already owns") {
        assertTrue(
          TagImportDialog.clashes(file, Set("lesson1")).map(_.name) == List("Lesson1"),
          TagImportDialog.clashes(file, Set("lesson1", "weather")).map(_.name) == List("Lesson1", "weather"),
          TagImportDialog.clashes(file, Set.empty).isEmpty,
        )
      },
    )
  }
}
