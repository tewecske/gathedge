import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import org.scalajs.linker.interface.{ESVersion, ModuleKind}

val scala3Version = "3.8.4"
val zioVersion = "2.1.26"
val zioHttpVersion = "3.11.6"
val zioJsonVersion = "0.9.1"
// Matches what zio-http 3.11.6 pulls in; only the derivation module has to be requested explicitly.
val zioSchemaVersion = "1.8.7"
val zioConfigVersion = "4.0.8"
val zioLoggingVersion = "2.5.3"
val laminarVersion = "17.2.1"
val waypointVersion = "10.0.0-M7"
val jbcryptVersion = "0.4"
val angusMailVersion = "2.0.3"
val logbackVersion = "1.5.38"
val quillVersion = "4.8.6"
val postgresqlVersion = "42.7.13"
val hikariCpVersion = "7.1.0"
val flywayVersion = "13.9.0"
val zioTelemetryVersion = "3.1.19"
val otelSemconvVersion = "1.43.0"
// The OpenTelemetry Java agent, for `~backend/reStart` and `npm run dev`. Same jar the Dockerfile
// ADDs into the runtime image; keep the two versions in step. Resolved into its own ivy
// configuration (below) so it never reaches the compile/runtime classpath or `backend/stage/lib` —
// a `-javaagent` is loaded by the JVM itself, not imported.
val otelAgentVersion = "2.31.1"
val OtelAgent = config("otelAgent").hide
// Sentry: the SDK, its logback appender and profiler on the classpath, and Sentry's own build of
// the OpenTelemetry agent in an ivy configuration of its own, like the one above. `nix/agents.nix`
// fetches the same agent jar for the server; keep the two versions in step.
val sentryVersion = "8.60.0"
val SentryAgent = config("sentryAgent").hide
val testcontainersScalaVersion = "0.44.1"
// Transitive jars the Trivy scan flags (HIGH/CRITICAL) in `backend/stage/lib`. zio-http pulls
// BouncyCastle in through netty-pkitesting; Quill pulls jline in through scalafmt. Neither has a
// release with a fixed version yet, so the backend pins them. Drop a pin once its parent catches up.
val bouncyCastleVersion = "1.86"
val jlineVersion = "3.30.17"

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

/** Which Java agent `backend/reStart` runs with: `none`, `otel` or `sentry`.
  *
  * A session `set` of this key wins. That is how `npm run dev:otel` and `npm run dev:sentry` choose: they run under
  * `sbt --client`, whose server keeps the environment it booted with, so a variable on the client's shell would never
  * reach it. Left `None` (what `npm run dev` sets), TELEMETRY_AGENT in `.env` or the server's shell decides, and with
  * that unset the older OTEL_JAVAAGENT_ENABLED=true still means `otel`. The server makes the same choice in
  * `nix/module.nix` (`services.gathedge.telemetry.agent`).
  */
lazy val telemetryAgentChoice = settingKey[Option[String]]("Java agent for backend/reStart: none, otel or sentry")

// One alias per choice, so a script names the agent with no nested quoting:
// `sbt --client 'agentSentry; ~backend/reStart'`. The `set` lasts for the server's session.
addCommandAlias("agentFromEnv", "set backend/telemetryAgentChoice := None")
addCommandAlias("agentNone", "set backend/telemetryAgentChoice := Some(\"none\")")
addCommandAlias("agentOtel", "set backend/telemetryAgentChoice := Some(\"otel\")")
addCommandAlias("agentSentry", "set backend/telemetryAgentChoice := Some(\"sentry\")")

def telemetryAgent(choice: Option[String], baseDir: File): String = {
  val env = dotEnv(baseDir) ++ sys.env
  choice.orElse(env.get("TELEMETRY_AGENT")).map(_.trim.toLowerCase).filter(_.nonEmpty) match {
    case Some(agent @ ("none" | "otel" | "sentry")) => agent
    case Some(other) => sys.error(s"telemetry agent '$other': expected none, otel or sentry")
    case None =>
      if (env.get("OTEL_JAVAAGENT_ENABLED").exists(_.trim.equalsIgnoreCase("true"))) "otel" else "none"
  }
}

/** The variables the agent choice sets, over `.env` and the shell.
  *
  * Sentry's agent is an OpenTelemetry agent, so it needs OTEL_JAVAAGENT_ENABLED=true too, and its OTLP exporters off:
  * it sends to Sentry, not to Jaeger. SENTRY_ENABLED keeps the SDK and the logback appender silent under the other two
  * choices, even with a SENTRY_DSN in `.env`. `nix/module.nix` sets the same variables.
  */
def telemetryEnv(agent: String): Map[String, String] = agent match {
  case "sentry" =>
    Map(
      "OTEL_JAVAAGENT_ENABLED" -> "true",
      "OTEL_TRACES_EXPORTER" -> "none",
      "OTEL_METRICS_EXPORTER" -> "none",
      "OTEL_LOGS_EXPORTER" -> "none",
      "SENTRY_ENABLED" -> "true",
    )
  case "otel" => Map("OTEL_JAVAAGENT_ENABLED" -> "true", "SENTRY_ENABLED" -> "false")
  case _      => Map("OTEL_JAVAAGENT_ENABLED" -> "false", "SENTRY_ENABLED" -> "false")
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
  .jsSettings(
    // `WordCell`'s `\p{L}` patterns need ES2018 regex (Unicode property escapes); the linker
    // default is older and fails these at runtime, not at compile time.
    scalaJSLinkerConfig ~= (_.withESFeatures(_.withESVersion(ESVersion.ES2018))),
  )
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
        // Manual span creation for the HTTP server span; per-SQL-statement spans, HikariCP and JVM
        // metrics come from the OpenTelemetry Java agent added to the runtime image (see Dockerfile).
        // `OpenTelemetry.global` reads the agent-registered SDK; with no agent it is a no-op tracer,
        // so `sbt test` and `npm run dev` need nothing extra.
        "dev.zio" %% "zio-opentelemetry" % zioTelemetryVersion,
        "io.opentelemetry.semconv" % "opentelemetry-semconv" % otelSemconvVersion,
        "ch.qos.logback" % "logback-classic" % logbackVersion,
        // Inert unless SENTRY_ENABLED=true and SENTRY_DSN are set, which only the `sentry` agent
        // choice does (see `telemetryAgent` below and `nix/module.nix`).
        "io.sentry" % "sentry" % sentryVersion,
        "io.sentry" % "sentry-logback" % sentryVersion,
        "io.sentry" % "sentry-async-profiler" % sentryVersion,
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
        "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test,
      ),
    dependencyOverrides ++=
      Seq(
        "org.bouncycastle" % "bcprov-jdk18on" % bouncyCastleVersion,
        "org.bouncycastle" % "bcpkix-jdk18on" % bouncyCastleVersion,
        "org.bouncycastle" % "bcutil-jdk18on" % bouncyCastleVersion,
        "org.jline" % "jline" % jlineVersion,
      ),
    Compile / mainClass := Some("gathedge.backend.Main"),
    // Every backend spec now opens its own Postgres schema and HikariCP pool against one shared
    // testcontainers container (see `TestDataSource`). sbt runs spec classes concurrently by default,
    // and enough of them at once exhausts the container's connection budget before any individual
    // pool gets a chance to fail fast and retry — capped here rather than in Postgres config, since
    // that budget is shared with whatever else is running against the same container.
    Test / concurrentRestrictions += Tags.limit(Tags.Test, 4),
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
    reStart / envVars := {
      val base = (ThisBuild / baseDirectory).value
      dotEnv(base) ++ sys.env ++ telemetryEnv(telemetryAgent(telemetryAgentChoice.value, base))
    },
    // The Java agent for the forked dev JVM. Downloaded like a dependency but kept off every
    // classpath — see `OtelAgent` and `SentryAgent` above.
    ivyConfigurations ++= Seq(OtelAgent, SentryAgent),
    libraryDependencies ++= Seq(
      "io.opentelemetry.javaagent" % "opentelemetry-javaagent" % otelAgentVersion % OtelAgent,
      "io.sentry" % "sentry-opentelemetry-agent" % sentryVersion % SentryAgent,
    ),
    // `-javaagent` follows `telemetryAgentChoice` (see `telemetryAgent` above). Both agents are
    // OpenTelemetry agents, so only one can load.
    telemetryAgentChoice := None,
    reStart / javaOptions ++= {
      // `update` is evaluated regardless of the choice (sbt runs task dependencies eagerly), so it
      // is looked up outside the `match` on purpose; resolving the configurations is cheap.
      val report = update.value
      def agentJar(cfg: Configuration): String =
        report.select(configurationFilter(cfg.name)).headOption match {
          case Some(jar) => s"-javaagent:${jar.getAbsolutePath}"
          case None      => sys.error(s"${cfg.name}: the agent jar did not resolve")
        }
      telemetryAgent(telemetryAgentChoice.value, (ThisBuild / baseDirectory).value) match {
        case "otel"   => Seq(agentJar(OtelAgent))
        case "sentry" => Seq(agentJar(SentryAgent))
        case _        => Seq.empty[String]
      }
    },
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
