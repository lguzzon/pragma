ThisBuild / scalaVersion := "2.13.1"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.heavenlyx"
ThisBuild / organizationName := "heavenlyx"

lazy val root = (project in file("."))
  .settings(
    name := "heavenly-x",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.8" % Test,
      "com.lihaoyi" %% "pprint" % "0.5.6"
    )
  )

libraryDependencies ++= Seq(
  "org.parboiled" %% "parboiled" % "2.1.8",
  "org.sangria-graphql" %% "sangria" % "2.0.0-M1",
  "com.github.nscala-time" %% "nscala-time" % "2.22.0",
  "io.spray" %% "spray-json" % "1.3.5",
  "com.pauldijou" %% "jwt-core" % "4.1.0",
  "com.typesafe.akka" %% "akka-stream" % "2.6.1"
)

scalacOptions ++= Seq("-feature", "-deprecation", "-Xlint:unused")
