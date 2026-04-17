import sbt.Keys._

ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.2.2"
ThisBuild / organization := "netgamesim"

ThisBuild / resolvers += "akka-secure-mvn" at "https://repo.akka.io/2XPQeahGudQMVvzi8bKQrrNTUqdZ-M1wJUr_veNtmeC6Wf7y/secure"
ThisBuild / resolvers += Resolver.url("akka-secure-ivy", url("https://repo.akka.io/2XPQeahGudQMVvzi8bKQrrNTUqdZ-M1wJUr_veNtmeC6Wf7y/secure"))(Resolver.ivyStylePatterns)

val circeVersion = "0.14.1"

lazy val `sim-core` = (project in file("sim-core"))
  .settings(
    name := "sim-core",
    libraryDependencies ++= Seq(
      "com.typesafe" % "config" % "1.4.3",
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.scalatest" %% "scalatest" % "3.2.18" % Test
    ),
    scalacOptions ++= Seq("-deprecation", "-feature")
  )

lazy val `sim-runtime-akka` = (project in file("sim-runtime-akka"))
  .dependsOn(`sim-core`, `sim-algorithms`)
  .settings(
    name := "sim-runtime-akka",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.6.20",
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "com.typesafe.akka" %% "akka-testkit" % "2.6.20" % Test,
      "org.scalatest" %% "scalatest" % "3.2.18" % Test
    ),
    scalacOptions ++= Seq("-deprecation", "-feature")
  )

lazy val `sim-algorithms` = (project in file("sim-algorithms"))
  .dependsOn(`sim-core`)
  .settings(
    name := "sim-algorithms",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.18" % Test
    ),
    scalacOptions ++= Seq("-deprecation", "-feature")
  )

lazy val `sim-cli` = (project in file("sim-cli"))
  .dependsOn(`sim-core`, `sim-algorithms`, `sim-runtime-akka`)
  .settings(
    name := "sim-cli",
    libraryDependencies ++= Seq(
      "com.typesafe" % "config" % "1.4.3",
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.scalatest" %% "scalatest" % "3.2.18" % Test
    )
  )

lazy val root = (project in file("."))
  .aggregate(`sim-core`, `sim-runtime-akka`, `sim-algorithms`, `sim-cli`)
  .settings(
    name := "netgamesim-akka-sim",
    publish / skip := true
  )
