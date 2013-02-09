import scalariform.formatter.preferences._

name := "wasted-util"

organization := "io.wasted"

version := scala.io.Source.fromFile("version").mkString.trim

scalaVersion := "2.10.0"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

resolvers ++= Seq(
  "wasted.io/repo" at "http://repo.wasted.io/mvn",
  "Twitter's Repository" at "http://maven.twttr.com/",
  "Maven Repo" at "http://repo1.maven.org/maven2/",
  "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository",
  "Typesafe Ivy Repo" at "http://repo.typesafe.com/typesafe/ivy-releases",
  "Typesafe Maven Repo" at "http://repo.typesafe.com/typesafe/releases/",
  "Java.net Maven2 Repository" at "http://download.java.net/maven/2/"
)

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.0.6" % "compile",
  "com.typesafe" % "config" % "0.6.0",
  "io.netty" % "netty" % "4.0.0.Beta1-SNAPSHOT",
  "org.joda" % "joda-convert" % "1.2",
  "joda-time" % "joda-time" % "2.1",
  "org.specs2" %% "specs2" % "1.13" % "test"
)

publishTo := Some("wasted.io/repo" at "http://repo.wasted.io/mvn")

scalariformSettings

ScalariformKeys.preferences := FormattingPreferences().setPreference(AlignParameters, true)

sourceGenerators in Compile <+= buildInfo

buildInfoSettings

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)

buildInfoPackage := "io.wasted.util.build"

site.settings

site.includeScaladoc()

ghpages.settings

git.remoteRepo := "git@github.com:wasted/scala-util.git"

