addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
// `backend/stage` lays out a launcher script + lib/ for the Docker image (see Dockerfile).
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")

// JSEnv implementation (not an AutoPlugin) for running Scala.js tests against a DOM.
libraryDependencies += "org.scala-js" %% "scalajs-env-jsdom-nodejs" % "1.1.1"
