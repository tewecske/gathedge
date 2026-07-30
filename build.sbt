import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import org.scalajs.linker.interface.ModuleKind

val scala3Version = "3.8.4"
val zioVersion = "2.1.26"
val zioHttpVersion = "3.11.3"
val zioJsonVersion = "0.9.1"
val zioConfigVersion = "4.0.8"
val laminarVersion = "17.2.1"
val waypointVersion = "10.0.0-M7"
val sqliteJdbcVersion = "3.53.2.0"
val jbcryptVersion = "0.4"
val logbackVersion = "1.5.38"

ThisBuild / scalaVersion := scala3Version
ThisBuild / organization := "com.example.webapp1"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val commonSettings = Seq(
  scalacOptions ++=
    Seq(
      "-encoding",
      "UTF-8", // source files are in UTF-8
      "-deprecation", // warn about use of deprecated APIs
      "-unchecked", // warn about unchecked type parameters
      "-feature", // warn about misused language features
      "-Xfatal-warnings",
      "-Yexplicit-nulls",
      "-noindent",
    )
)

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("modules/shared"))
  .settings(commonSettings)
  .settings(name := "shared", libraryDependencies += "dev.zio" %%% "zio-json" % zioJsonVersion)
  .jvmSettings(
    libraryDependencies ++=
      Seq(
        "org.xerial" % "sqlite-jdbc" % sqliteJdbcVersion,
        "dev.zio" %% "zio-config" % zioConfigVersion,
        "org.mindrot" % "jbcrypt" % jbcryptVersion,
      )
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
        "ch.qos.logback" % "logback-classic" % logbackVersion,
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
    libraryDependencies ++=
      Seq("com.raquo" %%% "laminar" % laminarVersion, "com.raquo" %%% "waypoint" % waypointVersion),
  )

lazy val root = project
  .in(file("."))
  .aggregate(sharedJVM, sharedJS, backend, frontend)
  .settings(name := "webapp1", publish / skip := true)
