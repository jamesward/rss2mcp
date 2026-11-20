name := "rss2mcp"

scalaVersion := "3.10.1"

scalacOptions ++= Seq(
  "-language:strictEquality",
  "-deprecation",
  "-Werror",
)

// ./sbta -Dlocal ...  builds against ../zio-http-mcp instead of the published artifact
val useLocal = sys.props.contains("local")
val localZioHttpMcp = file("../zio-http-mcp")

lazy val root = (project in file(".")).dependsOn(
  Option.when(useLocal)(RootProject(localZioHttpMcp): ClasspathDep[ProjectReference]).toSeq *
)

libraryDependencies ++= Option.unless(useLocal)("com.jamesward" %% "zio-http-mcp" % "0.14.0").toSeq

val zioVersion = "2.1.27"

libraryDependencies ++= Seq(
  "com.rometools" % "rome"                      % "2.1.0",
  "org.jsoup"     % "jsoup"                     % "1.24.1",
  "dev.zio"      %% "zio-cache"                 % "0.4.1",
  "dev.zio"      %% "zio-logging"               % "2.5.5",
  "dev.zio"      %% "zio-logging-slf4j2-bridge" % "2.5.5",
  "dev.zio" %% "zio-test"     % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
)

enablePlugins(JavaAppPackaging)

fork := true

Compile / mainClass := Some("rss2mcp.Main")

// Scala's LazyVals use sun.misc.Unsafe and Netty loads its native transport. Without these the JVM
// prints multi-line warnings, which break the one-JSON-object-per-line logs.
val jvmFlags = Seq(
  "--sun-misc-unsafe-memory-access=allow",
  "--enable-native-access=ALL-UNNAMED",
)

javaOptions ++= jvmFlags

Universal / javaOptions ++= jvmFlags.map("-J" + _)

skillsJarsOutputDir := Some(file(".kiro/skills"))

libraryDependencies += "com.jamesward" % "skills" % "0.0.27" % Skills
