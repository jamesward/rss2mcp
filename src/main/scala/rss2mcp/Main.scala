package rss2mcp

import com.jamesward.ziohttp.mcp.McpServer
import zio.*
import zio.http.*

object Main extends ZIOAppDefault:
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    AppLogging.fromEnv ++ Runtime.enableLoomBasedBlockingExecutor

  /** How often a subscription reads its feed. Feeds are pulled, so this bounds how fresh an event is. */
  val PollInterval: Duration = 1.minute

  def run =
    for
      port   <- System.envOrElse("PORT", "8080").map(_.toIntOption).someOrFail(IllegalArgumentException("PORT must be a number"))
      server <- ZIO.fromEither(Rss2Mcp.server(PollInterval)).mapError(e => IllegalStateException(e.toString))
      level  <- System.env("LOG_LEVEL").map(AppLogging.levelNamed)
      _      <- ZIO.logAnnotate(
                  LogAnnotation("port", port.toString),
                  LogAnnotation("version", Rss2Mcp.Version),
                  LogAnnotation("poll_interval_ms", PollInterval.toMillis.toString),
                  LogAnnotation("log_level", level.label),
                )(ZIO.logInfo("rss2mcp starting"))
      _      <- Server.serve(Rss2Mcp.routes(server)).provide(
                  Server.defaultWithPort(port),
                  Feeds.client,
                  Feeds.http(Feeds.Config(ttl = PollInterval), HostGuard.publicOnly),
                  McpServer.State.default,
                )
    yield ()
