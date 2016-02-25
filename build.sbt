import scalariform.formatter.preferences._

name := "wasted-util"

organization := "io.wasted"

version := scala.io.Source.fromFile("version").mkString.trim

scalaVersion := "2.11.7"

crossScalaVersions := Seq("2.10.6", "2.11.7")

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-language:postfixOps", "-language:implicitConversions")

libraryDependencies ++= Seq(
  "com.twitter" %% "util-core" % "6.30.0",
  "ch.qos.logback" % "logback-classic" % "1.1.2",
  "com.typesafe" % "config" % "1.2.1",
  "com.google.guava" % "guava" % "18.0",
  "io.netty" % "netty-all" % "4.0.34.Final",
  "org.javassist" % "javassist" % "3.18.2-GA",
  "com.google.code.findbugs" % "jsr305" % "1.3.+",
  "org.scala-lang" % "scala-reflect" % scalaVersion.value
)

// For testing
libraryDependencies ++= Seq(
 "org.scalatest" %% "scalatest" % "2.2.2" % "test"
)

publishTo := Some("wasted.io/repo" at "http://repo.wasted.io/mvn")

scalariformSettings

ScalariformKeys.preferences := FormattingPreferences().setPreference(AlignParameters, true)

sourceGenerators in Compile <+= buildInfo

buildInfoSettings

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)

buildInfoPackage := "io.wasted.util.build"

net.virtualvoid.sbt.graph.Plugin.graphSettings

site.settings

site.includeScaladoc()

ghpages.settings

git.remoteRepo := "git@github.com:wasted/scala-util.git"

resolvers ++= Seq(
  "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository",
  "wasted.io/repo" at "http://repo.wasted.io/mvn",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Maven Repo" at "http://repo1.maven.org/maven2/",
  "Typesafe Ivy Repo" at "http://repo.typesafe.com/typesafe/ivy-releases",
  "Typesafe Maven Repo" at "http://repo.typesafe.com/typesafe/releases/",
  "Java.net Maven2 Repository" at "http://download.java.net/maven/2/"
)

isSnapshot := true
