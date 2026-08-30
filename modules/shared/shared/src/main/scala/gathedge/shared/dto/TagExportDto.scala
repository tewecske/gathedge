package gathedge.shared.dto

import gathedge.shared.domain.{Gender, PartOfSpeech, WordLanguage}
import zio.json.*

/** One word as an export file names it: the four fields that make up a word's identity (`UNIQUE (language, text_norm,
  * part_of_speech, gender)`), and nothing else. `text` is the '''bare''' dictionary text — article-free, the way
  * `words.text` is stored — not a display form. Import matches on these fields and creates the word when the target
  * dictionary has never heard of it, the same "ensure" a typed word goes through.
  */
final case class TagExportWord(
  language: WordLanguage,
  text: String,
  partOfSpeech: PartOfSpeech,
  gender: Option[Gender],
) derives JsonCodec

/** One word a tag holds, plus whichever of its translations the tag marks as a practice answer. `marked` is the chip
  * state (`word_tag_pairs`) for this word inside this tag; it is not de-duplicated against the other direction, since
  * import writes each mark in both directions and doing so twice is idempotent.
  */
final case class TagExportEntry(word: TagExportWord, marked: List[TagExportWord]) derives JsonCodec

/** One exported tag: its name and every word it holds. */
final case class TagExportTag(name: String, entries: List[TagExportEntry]) derives JsonCodec

/** A tag export file. `tags` has one element for a single-tag export and one per owned tag for "export all"; import
  * reads both shapes the same way. `version` is `1`; import refuses anything else. `exportedAt` is epoch millis, for
  * the reader's information only.
  */
final case class TagExportFile(version: Int, exportedAt: Long, tags: List[TagExportTag]) derives JsonCodec

object TagExportFile {

  /** The only version this build writes or reads. */
  val currentVersion = 1
}

/** What to do about one exported tag whose name the importing account already owns (compared case-insensitively).
  * Absent from [[TagImportRequest.resolutions]] means "not decided yet", which is what makes the first import call fail
  * with [[gathedge.shared.api.ApiFailure.Conflict]] listing the clashing names.
  */
enum TagImportChoice derives JsonCodec {

  /** Add the file's words and marks into the existing tag of that name. */
  case Merge

  /** Import under `newName` instead, as a new tag. */
  case Rename(newName: String)
}

/** [[gathedge.shared.api.WordEndpoints.importTags]]'s body: the parsed file, plus a per-clashing-name decision. The
  * keys of `resolutions` are normalized tag names (`Tag.normalize`). A first attempt sends it empty; if the server
  * answers 409, the client fills in a choice for every clashing name and re-submits.
  */
final case class TagImportRequest(
  file: TagExportFile,
  resolutions: Map[String, TagImportChoice] = Map.empty,
) derives JsonCodec

/** What one tag's import did. `created` is `false` when the tag was merged into an existing one. `newDictionaryWords`
  * counts words this import had to add to the target dictionary.
  */
final case class TagImportResult(
  tagName: String,
  created: Boolean,
  wordsAdded: Int,
  pairsAdded: Int,
  newDictionaryWords: Int,
) derives JsonCodec

/** [[gathedge.shared.api.WordEndpoints.importTags]]'s answer: one [[TagImportResult]] per tag in the file. */
final case class TagImportResponse(results: List[TagImportResult]) derives JsonCodec
