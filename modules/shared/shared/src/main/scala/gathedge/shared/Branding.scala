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
}
