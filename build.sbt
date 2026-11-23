name := "rss2mcp"

scalaVersion := "3.10.1"

scalacOptions ++= Seq(
  "-language:strictEquality",
  "-deprecation",
  "-Werror",
)

// ./sbta -Dlocal ...  builds against ../zio-http-mcp instead of the published artifact.
// ZIO_HTTP_MCP_REF=<commit> builds against that commit of zio-http-mcp on GitHub, to try an
// unreleased change on Heroku (heroku config:set ZIO_HTTP_MCP_REF=...); unset it to go back.
val useLocal = sys.props.contains("local")
val localZioHttpMcp = file("../zio-http-mcp")
val gitRef = sys.env.get("ZIO_HTTP_MCP_REF").map(_.trim).filter(_.nonEmpty)

val zioHttpMcpSource: Option[ProjectReference] =
  if useLocal then Some(RootProject(localZioHttpMcp))
  else gitRef.map(ref => RootProject(uri(s"https://github.com/jamesward/zio-http-mcp.git#$ref")))

lazy val root = (project in file(".")).dependsOn(
  zioHttpMcpSource.map(p => p: ClasspathDep[ProjectReference]).toSeq *
)

libraryDependencies ++= Option.when(zioHttpMcpSource.isEmpty)("com.jamesward" %% "zio-http-mcp" % "0.15.0").toSeq

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
