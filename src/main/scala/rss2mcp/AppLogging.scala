package rss2mcp

import zio.*
import zio.logging.{ConsoleLoggerConfig, LogFilter, LogFormat, consoleJsonLogger}
import zio.logging.slf4j.bridge.Slf4jBridge

/**
 * One JSON object per line on stdout, with Java libraries' SLF4J logs in the
 * same format. The floor is `LOG_LEVEL` (`INFO` when unset): `DEBUG` adds each
 * feed check and webhook watermark. Java libraries stay at `INFO` or above.
 */
object AppLogging:
  val fromEnv: ZLayer[Any, Nothing, Unit] =
    ZLayer.fromZIO(System.env("LOG_LEVEL").orDie).flatMap(name => json(levelNamed(name.get)))

  def json(level: LogLevel): ZLayer[Any, Nothing, Unit] =
    val config = ConsoleLoggerConfig(format, LogFilter.LogLevelByNameConfig(level, Map.empty))
    val libraries = if level.ordinal < LogLevel.Info.ordinal then LogLevel.Info else level
    Runtime.removeDefaultLoggers >>> consoleJsonLogger(config) >>> Slf4jBridge.init(LogFilter.logLevelByName[Any](libraries))

  /** The level `LOG_LEVEL` names, `INFO` if it names none. */
  def levelNamed(name: Option[String]): LogLevel =
    name.map(_.trim.toUpperCase).flatMap(n => LogLevel.levels.find(_.label == n)).getOrElse(LogLevel.Info)

  private lazy val format: LogFormat =
    import LogFormat.*
    label("timestamp", timestamp) |-|
      label("level", level) |-|
      label("thread", fiberId) |-|
      label("message", quoted(line)) +
      (space + label("cause", cause)).filter(LogFilter.causeNonEmpty) +
      annotations
