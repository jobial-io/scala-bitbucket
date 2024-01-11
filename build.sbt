/*
 * Copyright (c) 2020 Jobial OÜ. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance with
 * the License. A copy of the License is located at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
name := "scala-bitbucket"

ThisBuild / organization := "io.jobial"
ThisBuild / scalaVersion := "2.13.8"
ThisBuild / crossScalaVersions := Seq("2.12.15", "2.13.8")
ThisBuild / version := "0.6.0"
ThisBuild / scalacOptions += "-target:jvm-1.8"
ThisBuild / javacOptions ++= Seq("-source", "11", "-target", "11")
ThisBuild / Test / packageBin / publishArtifact := true
ThisBuild / Test / packageSrc / publishArtifact := true
ThisBuild / Test / packageDoc / publishArtifact := true
ThisBuild / resolvers += "Mulesoft" at "https://repository.mulesoft.org/nexus/content/repositories/public/"

import sbt.Keys.{description, libraryDependencies, publishConfiguration}
import sbt.addCompilerPlugin
import xerial.sbt.Sonatype._

lazy val commonSettings = Seq(
  publishConfiguration := publishConfiguration.value.withOverwrite(true),
  publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true),
  publishM2Configuration := publishM2Configuration.value.withOverwrite(true),
  publishTo := publishTo.value.orElse(sonatypePublishToBundle.value),
  sonatypeProjectHosting := Some(GitHubHosting("jobial-io", "scala-bitbucket", "orbang@jobial.io")),
  organizationName := "Jobial OÜ",
  licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
  description := "Simple Bitbucket Client in functional Scala",
  addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  scalacOptions ++= (if (scalaBinaryVersion.value != "2.13") Seq("-Ypartial-unification") else Seq())
)

lazy val CatsVersion = "2.6.1"
lazy val CatsEffectVersion = "2.5.3"
lazy val KittensVersion = "2.3.2"
lazy val CatsTestkitScalatestVersion = "2.1.5"
lazy val ScalaLoggingVersion = "3.9.2"
lazy val ScalatestVersion = "3.2.3"
lazy val ScalaXmlVersion = "1.3.0"
lazy val SoftwareConstructsVersion = "10.0.25"
lazy val SprintVersion = "0.3.2"
lazy val CirceVersion = "0.14.1"
lazy val CirceOpticsVersion = "0.14.1"
lazy val CirceYamlVersion = "0.14.1"
lazy val LogbackVersion = "1.2.3"
lazy val SttpVersion = "3.2.3"
lazy val JodaTimeVersion = "2.11.1"

lazy val root: Project = project
  .in(file("."))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % CatsVersion,
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "org.typelevel" %% "cats-testkit-scalatest" % CatsTestkitScalatestVersion % Test,
      "org.typelevel" %% "kittens" % KittensVersion % Test,
      "com.typesafe.scala-logging" %% "scala-logging" % ScalaLoggingVersion,
      "org.scalatest" %% "scalatest" % ScalatestVersion % Test,
      "ch.qos.logback" % "logback-classic" % LogbackVersion % Test,
      "com.github.sbt" % "junit-interface" % "0.13.2" % Test,
      "io.circe" %% "circe-parser" % CirceVersion,
      "io.circe" %% "circe-generic-extras" % CirceVersion,
      "io.circe" %% "circe-optics" % CirceOpticsVersion,
      "io.jobial" %% "sprint" % SprintVersion,
      "com.softwaremill.sttp.client3" %% "core" % SttpVersion,
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-cats" % SttpVersion,
      "org.scala-lang.modules" %% "scala-xml" % ScalaXmlVersion,
      "io.circe" %% "circe-yaml" % CirceYamlVersion,
      "joda-time" % "joda-time" % JodaTimeVersion
    )
  )
