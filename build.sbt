import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import org.scalajs.linker.interface.ModuleKind

val scala3Version = "3.8.4"
val zioVersion = "2.1.26"
val zioHttpVersion = "3.11.3"
val zioJsonVersion = "0.9.1"
// Matches what zio-http 3.11.3 pulls in; only the derivation module has to be requested explicitly.
val zioSchemaVersion = "1.8.5"
val zioConfigVersion = "4.0.8"
val zioLoggingVersion = "2.5.3"
val laminarVersion = "17.2.1"
val waypointVersion = "10.0.0-M7"
val sqliteJdbcVersion = "3.53.2.0"
val jbcryptVersion = "0.4"
val angusMailVersion = "2.0.3"
val logbackVersion = "1.5.38"
val quillVersion = "4.8.6"
val postgresqlVersion = "42.7.13"
val hikariCpVersion = "7.1.0"
val flywayVersion = "13.1.0"
val testcontainersScalaVersion = "0.44.1"

ThisBuild / scalaVersion := scala3Version
ThisBuild / organization := "tewe.gathedge"
ThisBuild / version := "0.1.0-SNAPSHOT"

// quill-jdbc-zio 4.8.6 depends on an older zio-json than we use directly; both are
// binary-compatible for the subset of the API in play here, so accept the highest
// version (normal "latest wins" resolution) instead of treating the eviction as a
// hard build error.
ThisBuild / evictionErrorLevel := Level.Warn

/** Reads `.env` into a `Map` for the forked dev JVM.
  *
  * `.env` is `docker compose`'s file and nothing else ever read it: `sbt` has no notion of it, so a `GOOGLE_CLIENT_ID`
  * filled in there reached the container but never `~backend/reStart`, and every `${?ENV_VAR}` override in
  * `application.conf` silently kept its default. Most of them default to something workable in dev, which is why this
  * went unnoticed — the OAuth ones default to `""`, and an unconfigured provider is one `/api/auth/providers` does not
  * offer, so the sign-in buttons simply did not render.
  *
  * Values are taken literally: no `export` prefix, no quote-stripping beyond one matched pair, no `${VAR}`
  * interpolation. `docker compose` is stricter than that; anything fancy enough to diverge belongs in the shell
  * environment, which still wins (see `reStart / envVars` below).
  *
  * Being a setting, it is read when the build loads — edit `.env` and `reload`, since a re-`reStart` alone will not
  * pick it up.
  */
def dotEnv(baseDir: File): Map[String, String] = {
  val file = baseDir / ".env"
  if (!file.exists) {
    Map.empty[String, String]
  } else {
    IO.readLines(file)
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#") && line.contains("="))
      .map { line =>
        val (key, rest) = line.span(_ != '=')
        val value = rest.drop(1).trim
        val unquoted = {
          if (value.length >= 2 && (value.startsWith("\"") && value.endsWith("\"")))
            value.substring(1, value.length - 1)
          else
            value
        }
        (key.trim, unquoted)
      }
      .toMap
  }
}

lazy val commonSettings = Seq(
  scalacOptions ++=
    Seq(
      "-encoding",
      "UTF-8", // source files are in UTF-8
      "-deprecation", // warn about use of deprecated APIs
      "-unchecked", // warn about unchecked type parameters
      "-feature", // warn about misused language features
      "-Werror",
      "-Yexplicit-nulls",
      "-noindent",
    ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
)

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("modules/shared"))
  .settings(commonSettings)
  .settings(
    name := "shared",
    libraryDependencies ++=
      Seq(
        "dev.zio" %%% "zio-json" % zioJsonVersion,
        // Endpoint API spike (admin user management): the endpoint descriptions live here so the
        // backend implementation and any client are checked against one definition. zio-http
        // publishes a Scala.js artifact, so this still cross-compiles.
        "dev.zio" %%% "zio-http" % zioHttpVersion,
        "dev.zio" %%% "zio-schema-derivation" % zioSchemaVersion,
        "dev.zio" %%% "zio-test" % zioVersion % Test,
        "dev.zio" %%% "zio-test-sbt" % zioVersion % Test,
      ),
  )

lazy val sharedJVM = shared.jvm
lazy val sharedJS = shared.js

lazy val backend = project
  .in(file("modules/backend"))
  // `backend/stage` writes target/universal/stage/{bin,lib}; the Docker image copies that dir verbatim.
  .enablePlugins(JavaAppPackaging)
  .dependsOn(sharedJVM)
  .settings(commonSettings)
  .settings(
    name := "backend",
    libraryDependencies ++=
      Seq(
        "dev.zio" %% "zio" % zioVersion,
        "dev.zio" %% "zio-http" % zioHttpVersion,
        "dev.zio" %% "zio-config" % zioConfigVersion,
        "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
        "dev.zio" %% "zio-config-magnolia" % zioConfigVersion,
        "dev.zio" %% "zio-logging-slf4j2" % zioLoggingVersion,
        "ch.qos.logback" % "logback-classic" % logbackVersion,
        "io.getquill" %% "quill-jdbc-zio" % quillVersion,
        "org.postgresql" % "postgresql" % postgresqlVersion,
        "com.zaxxer" % "HikariCP" % hikariCpVersion,
        "org.flywaydb" % "flyway-core" % flywayVersion,
        "org.flywaydb" % "flyway-database-postgresql" % flywayVersion,
        "org.mindrot" % "jbcrypt" % jbcryptVersion,
        // Jakarta Mail implementation, used by SmtpEmailSender. Pulls jakarta.mail-api with it.
        "org.eclipse.angus" % "angus-mail" % angusMailVersion,
        "dev.zio" %% "zio-test" % zioVersion % Test,
        "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
        "dev.zio" %% "zio-http-testkit" % zioHttpVersion % Test,
        "org.xerial" % "sqlite-jdbc" % sqliteJdbcVersion % Test,
        "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test,
      ),
    Compile / mainClass := Some("gathedge.backend.Main"),
    // The message catalogs are one JSON file per language, canonically under `web/public/locales`,
    // where Vite serves them to the SPA in dev and nginx serves them out of the built image — both
    // with no configuration at all. The backend needs the same catalogs, because it renders the two
    // transactional emails, and it reads them off its classpath from *here* rather than from a copy:
    // a translator edits exactly one file per language, and there is no generated duplicate to go
    // stale. Landing them on the classpath root also puts them where `MessagesSpec` can check every
    // MessageKeys constant against both files.
    //
    // Docker is fine with the reach across module boundaries: the `base` stage does `COPY . .`, so
    // the whole repo is present when `backend/stage` runs.
    Compile / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "web" / "public" / "locales",
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
    reStart / baseDirectory := (ThisBuild / baseDirectory).value,
    // A real environment variable wins over the file, so `GOOGLE_CLIENT_ID=… sbt "~backend/reStart"`
    // still overrides one set in `.env` — the same precedence `docker compose` gives it.
    reStart / envVars := dotEnv((ThisBuild / baseDirectory).value) ++ sys.env,
  )

lazy val frontend = project
  .in(file("modules/frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(sharedJS)
  .settings(commonSettings)
  .settings(
    name := "frontend",
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.NoModule)),
    Test / jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(),
    libraryDependencies ++=
      Seq(
        "com.raquo" %%% "laminar" % laminarVersion,
        "com.raquo" %%% "waypoint" % waypointVersion,
        "dev.zio" %%% "zio-test" % zioVersion % Test,
        "dev.zio" %%% "zio-test-sbt" % zioVersion % Test,
      ),
  )

lazy val root = project
  .in(file("."))
  .aggregate(sharedJVM, sharedJS, backend, frontend)
  .settings(name := "gathedge", publish / skip := true)
