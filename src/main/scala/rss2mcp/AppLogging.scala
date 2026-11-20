package rss2mcp

import zio.*
import zio.logging.{ConsoleLoggerConfig, LogFilter, LogFormat, consoleJsonLogger}
import zio.logging.slf4j.bridge.Slf4jBridge

/** One JSON object per line on stdout, with Java libraries' SLF4J logs in the same format, at an INFO floor. */
object AppLogging:
  val json: ZLayer[Any, Nothing, Unit] =
    val config = ConsoleLoggerConfig(format, LogFilter.LogLevelByNameConfig(LogLevel.Info, Map.empty))
    Runtime.removeDefaultLoggers >>> consoleJsonLogger(config) >>> Slf4jBridge.init(LogFilter.logLevelByName[Any](LogLevel.Info))

  private lazy val format: LogFormat =
    import LogFormat.*
    label("timestamp", timestamp) |-|
      label("level", level) |-|
      label("thread", fiberId) |-|
      label("message", quoted(line)) +
      (space + label("cause", cause)).filter(LogFilter.causeNonEmpty) +
      annotations
