package gathedge.shared

/** What this deployment calls itself.
  *
  * The two halves are separate because they end up in different kinds of place. [[appName]] is prose shown to a reader
  * and may be anything — spaces, accents, capitals. [[slug]] is an identifier: it matches the Scala package root, the
  * sbt project name, the Docker image names and the database name, and it reaches a URL (the OpenAPI document is served
  * at `/api/docs/openapi/<slug>-api.json`), so it has to stay `[a-z][a-z0-9]*`.
  *
  * `scripts/init-project.sh` rewrites both when a new project is started from this skeleton — the slug by renaming it
  * everywhere it occurs, the display name by editing the literal below. `web/index.html`'s `<title>` is the one place
  * the name is repeated rather than read from here, because that document is parsed before the bundle loads; the script
  * sets it too.
  */
object Branding {

  /** Human-facing name: the navbar wordmark and the browser tab title. */
  val appName: String = "GathEdge"

  /** Lowercase identifier, matching the package root and the artifact names. */
  val slug: String = "gathedge"

  /** The project's public source repository. Reached from the About page. */
  val githubUrl: String = "https://github.com/tewecske/gathedge"

  /** The licence under which the application's own source code is released. Reached from the About page. */
  val licenseUrl: String = "https://www.gnu.org/licenses/agpl-3.0.html"

  /** The licence under which the bundled vocabulary data is shared. Reached from the About page. */
  val sourceLicenseUrl: String = "https://creativecommons.org/licenses/by-sa/4.0/"

  /** The public word-frequency lists the dictionary draws from. Reached from the About page. */
  val sourceFrequencyWordsUrl: String = "https://github.com/hermitdave/FrequencyWords"

  /** The Wiktionary dump the dictionary draws from. Reached from the About page. */
  val sourceWiktextractUrl: String = "https://github.com/tatuylonen/wiktextract"

  /** Wiktionary itself. Reached from the About page. */
  val sourceWiktionaryUrl: String = "https://www.wiktionary.org/"

  /** The kaikki.org dictionary derived from Wiktionary. Reached from the About page. */
  val sourceKaikkiUrl: String = "https://kaikki.org/dictionary/"

  /** The person who created and maintains this deployment. Shown on the About page. */
  val authorName: String = "Levente Hortobágyi"

  /** The contact address of the maintainer. Shown on the About page. */
  val authorEmail: String = "leventewe@gmail.com"
}
