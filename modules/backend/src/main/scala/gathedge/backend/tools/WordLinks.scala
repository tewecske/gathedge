package gathedge.backend.tools

import gathedge.backend.tools.WiktextractParser.ParsedForm
import gathedge.shared.domain.{Gender, PartOfSpeech, WordLinkKind}

/** Which rows of a lemma's `forms[]` are not forms at all, but other words linked to it.
  *
  * Wiktionary lists two kinds of related word in the same array as a word's inflections:
  *
  *   - a noun's counterpart of another gender (`Künstler` lists `Künstlerin`, tagged `feminine` alone);
  *   - a diminutive or augmentative (`Haus` lists `Häuschen`, tagged `diminutive,neuter`).
  *
  * Each is a word of its own, with its own plural and translations. Stored as a form, it would be marked a form and
  * drop out of the "main words only" listing; `Künstler` and `Künstlerin` list each other, so both did. These rows go
  * to `word_links` instead, in both directions.
  *
  * A gender tag with anything else beside it (`feminine,plural`) is the counterpart's plural, an ordinary form, and
  * stays one. `rare` does not change what a row is.
  *
  * `V32__word_links.sql` moves rows an earlier import stored as forms by the same rule. The two must agree.
  */
object WordLinks {

  private val genderTags: Map[String, Gender] = {
    Map("masculine" -> Gender.Masculine, "feminine" -> Gender.Feminine, "neuter" -> Gender.Neuter)
  }

  /** The link one form row stands for, as the kind read from the lemma and the kind read back from the form; `None` for
    * an ordinary form.
    */
  def of(form: ParsedForm): Option[(WordLinkKind, WordLinkKind)] = {
    val tags = form.relation.split(',').toSet - "rare"
    if (tags.contains("diminutive"))
      Some((WordLinkKind.Diminutive, WordLinkKind.DiminutiveOf))
    else if (tags.contains("augmentative"))
      Some((WordLinkKind.Augmentative, WordLinkKind.AugmentativeOf))
    else if (form.lemma.partOfSpeech != PartOfSpeech.Noun || tags.size != 1)
      None
    else {
      genderTags.get(tags.head).map { gender =>
        (WordLinkKind.ofGender(gender), WordLinkKind.ofGender(form.lemma.gender.getOrElse(opposite(gender))))
      }
    }
  }

  /** The lemma's own gender when the dump does not state it: a feminine counterpart's base is masculine, and the other
    * way round.
    */
  private def opposite(gender: Gender): Gender = {
    gender match {
      case Gender.Feminine =>
        Gender.Masculine
      case _               =>
        Gender.Feminine
    }
  }
}
