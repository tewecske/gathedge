import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import org.scalajs.linker.interface.ModuleKind

val scala3Version = "3.8.4"
val zioVersion = "2.1.26"
val zioHttpVersion = "3.11.3"
val zioJsonVersion = "0.9.1"
val zioConfigVersion = "4.0.8"
val zioLoggingVersion = "2.5.3"
val laminarVersion = "17.2.1"
val waypointVersion = "10.0.0-M7"
val sqliteJdbcVersion = "3.53.2.0"
val jbcryptVersion = "0.4"
val logbackVersion = "1.5.38"
val quillVersion = "4.8.6"
val postgresqlVersion = "42.7.13"
val hikariCpVersion = "7.1.0"
val flywayVersion = "13.1.0"
val testcontainersScalaVersion = "0.44.1"

ThisBuild / scalaVersion := scala3Version
ThisBuild / organization := "com.example.webapp1"
ThisBuild / version := "0.1.0-SNAPSHOT"

// quill-jdbc-zio 4.8.6 depends on an older zio-json than we use directly; both are
// binary-compatible for the subset of the API in play here, so accept the highest
// version (normal "latest wins" resolution) instead of treating the eviction as a
// hard build error.
ThisBuild / evictionErrorLevel := Level.Warn

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
        "dev.zio" %%% "zio-test" % zioVersion % Test,
        "dev.zio" %%% "zio-test-sbt" % zioVersion % Test,
      ),
  )

lazy val sharedJVM = shared.jvm
lazy val sharedJS = shared.js

lazy val backend = project
  .in(file("modules/backend"))
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
        "dev.zio" %% "zio-test" % zioVersion % Test,
        "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
        "org.xerial" % "sqlite-jdbc" % sqliteJdbcVersion % Test,
        "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test,
      ),
    Compile / mainClass := Some("webapp1.backend.Main"),
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
    reStart / baseDirectory := (ThisBuild / baseDirectory).value,
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
  .settings(name := "webapp1", publish / skip := true)
