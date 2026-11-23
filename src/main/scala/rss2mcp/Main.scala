package rss2mcp

import com.jamesward.ziohttp.mcp.{McpServer, McpWebhookStore}
import zio.*
import zio.http.*

object Main extends ZIOAppDefault:
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    AppLogging.fromEnv ++ Runtime.enableLoomBasedBlockingExecutor

  /** How often a subscription reads its feed. Feeds are pulled, so this bounds how fresh an event is. */
  val PollInterval: Duration = 1.minute

  def run =
    for
      port  <- System.envOrElse("PORT", "8080").map(_.toIntOption).someOrFail(IllegalArgumentException("PORT must be a number"))
      level <- System.env("LOG_LEVEL").map(AppLogging.levelNamed)
      _     <- ZIO.logAnnotate(
                 LogAnnotation("port", port.toString),
                 LogAnnotation("version", Rss2Mcp.Version),
                 LogAnnotation("poll_interval_ms", PollInterval.toMillis.toString),
                 LogAnnotation("log_level", level.label),
                 LogAnnotation("webhook_max_ttl_ms", Rss2Mcp.webhooks.maxTtl.toMillis.toString),
                 LogAnnotation("webhook_anonymous", Rss2Mcp.webhooks.anonymous.toString),
               )(ZIO.logInfo("rss2mcp starting"))
      _     <- ZIO.scoped(
                 for
                   server <- Rss2Mcp.server(PollInterval).mapError(e => IllegalStateException(e.toString))
                   _      <- Server.serve[Feeds & McpWebhookStore & McpServer.State](Rss2Mcp.routes(server))
                 yield ()
               ).provide(
                 Server.defaultWithPort(port),
                 Feeds.client,
                 Feeds.http(Feeds.Config(ttl = PollInterval), HostGuard.publicOnly),
                 McpServer.State.default,
                 InMemoryWebhookStore.layer,
               )
    yield ()
