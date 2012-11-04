import scalariform.formatter.preferences._

name := "util"

organization := "io.wasted"

version := ("git describe --always"!!).trim

scalaVersion := "2.9.1"

crossScalaVersions := Seq("2.9.1", "2.9.2")

scalacOptions ++= Seq("-unchecked", "-deprecation")

scalariformSettings

ScalariformKeys.preferences := FormattingPreferences().setPreference(AlignParameters, true)

buildInfoSettings

sourceGenerators in Compile <+= buildInfo

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)

buildInfoPackage := "io.wasted.util.build"

resolvers ++= Seq(
  "Twitter's Repository" at "http://maven.twttr.com/",
  "Maven Repo" at "http://repo1.maven.org/maven2/",
  "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository",
  "Typesafe Ivy Repo" at "http://repo.typesafe.com/typesafe/ivy-releases",
  "Typesafe Maven Repo" at "http://repo.typesafe.com/typesafe/releases/",
  "Java.net Maven2 Repository" at "http://download.java.net/maven/2/"
)


libraryDependencies ++= Seq(
  "org.specs2" %% "specs2" % "1.12" % "test",
  "ch.qos.logback" % "logback-classic" % "1.0.6" % "compile",
  "com.typesafe" % "config" % "0.6.0",
  "io.netty" % "netty" % "4.0.0.Alpha6",
  "org.joda" % "joda-convert" % "1.2",
  "joda-time" % "joda-time" % "2.1"
)


