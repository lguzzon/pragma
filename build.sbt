import Dependencies._

ThisBuild / scalaVersion := "2.12.10"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.heavenlyx"
ThisBuild / organizationName := "heavenlyx"

lazy val root = (project in file("."))
  .settings(
    name := "heavenly-x",
    libraryDependencies += scalaTest % Test
  )

libraryDependencies ++= Seq(
  "org.parboiled" %% "parboiled" % "2.1.8",
  "org.sangria-graphql" %% "sangria" % "1.4.2",
  "com.github.nscala-time" %% "nscala-time" % "2.22.0",
  "io.spray" %%  "spray-json" % "1.3.4",
  "com.pauldijou" %% "jwt-core" % "4.1.0"
)

scalacOptions ++= Seq("-feature", "-deprecation")

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
