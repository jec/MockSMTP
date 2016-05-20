name := "MockSMTP"

organization := "net.jcain"

version := "0.1.0-SNAPSHOT"

scalaVersion := "2.11.8"

libraryDependencies ++= {
  val akkaVersion       = "2.4.6"
  Seq(
    "ch.qos.logback"    % "logback-classic" % "1.1.7",
    "com.typesafe.akka" %% "akka-actor"     % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j"     % akkaVersion,
    "com.typesafe.akka" %% "akka-testkit"   % akkaVersion   % "test",
    "org.scalatest"     %% "scalatest"      % "2.2.6"       % "test"
  )
}

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  //"-Xfatal-warnings",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  //"-Ywarn-value-discard",
  "-Xfuture",
  "-Ywarn-unused-import"
)
