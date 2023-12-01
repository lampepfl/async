ThisBuild / scalaVersion := "3.3.1"

lazy val root = project
  .in(file("."))
  .settings(
    name := "Gears",
    organization := "ch.epfl.lamp",
    version := "0.1.0-SNAPSHOT",
    libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test
  )
