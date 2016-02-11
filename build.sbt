import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import sbt.Keys._

import scalariform.formatter.preferences._

organization := "ru.unicorndev"

name := "images"

scalaVersion := "2.11.7"

val akkaV = "2.4.1"

val akkaHttpV = "2.0.2"

val gitHeadCommitSha = settingKey[String]("current git commit SHA")

val commonScalariform = scalariformSettings :+ (ScalariformKeys.preferences := ScalariformKeys.preferences.value
  .setPreference(AlignParameters, true)
  .setPreference(AlignSingleLineCaseStatements, true)
  .setPreference(DoubleIndentClassDeclaration, true)
  .setPreference(PreserveSpaceBeforeArguments, true)
  .setPreference(RewriteArrowSymbols, true))

val commons = Seq(
  scalaVersion := "2.11.7",
  resolvers ++= Seq(
    "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
    Resolver.sonatypeRepo("snapshots"),
    Resolver.sonatypeRepo("releases"),
    Resolver.jcenterRepo,
    Resolver.bintrayRepo("alari", "generic")
  ),
  gitHeadCommitSha in ThisBuild := Process("git rev-parse --short HEAD").lines.head,
  licenses +=("MIT", url("http://opensource.org/licenses/MIT")),
  bintrayPackageLabels := Seq("scala", "akka-http", "images", "api"),
  bintrayRepository := "generic",
  version := "0.1." + gitHeadCommitSha.value
) ++ commonScalariform

commons

lazy val `images` = (project in file(".")).settings(commons: _*).settings(
  name := "images",
  organization := "ru.unicorndev",
  libraryDependencies ++= Seq(
    "com.sksamuel.scrimage" %% "scrimage-core" % "2.1.3",
    "com.sksamuel.scrimage" %% "scrimage-io-extra" % "2.1.3",
    "com.sksamuel.scrimage" %% "scrimage-filters" % "2.1.3",
    "com.ibm.icu" % "icu4j" % "56.1",
    "de.heikoseeberger" %% "akka-http-play-json" % "1.4.2",
    "com.typesafe.play" %% "play-json" % "2.4.6",
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "com.typesafe.akka" %% "akka-stream-experimental" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-experimental" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-testkit-experimental" % akkaHttpV % Test,
    "org.scalatest" %% "scalatest" % "2.2.5" % Test,
    "junit" % "junit" % "4.12" % Test
  )
)

//testOptions in Test += Tests.Argument("junitxml")

parallelExecution in Test := false

fork in Test := true

ivyScala := ivyScala.value map {
  _.copy(overrideScalaVersion = true)
}