package rss2mcp

import com.jamesward.ziohttp.mcp.*
import zio.*
import zio.http.*
import zio.schema.*
import zio.schema.codec.JsonCodec

import java.time.Instant

/**
 * `GET /health`: always `200`, for heroku-ops's probe, with each feed the server
 * reads and how reading it has gone. It's public, so it names a feed by its URL
 * without the query (a private feed's URL can carry a token there), and it never
 * shows a subscription's callback URL or arguments.
 */
object Health:
  /** One feed: its webhook subscriptions, then its `FeedStats`. */
  final case class Feed(
    feed: String,
    webhookSubscriptions: Int,
    requests: Long,
    fetches: Long,
    failures: Long,
    consecutiveFailures: Int,
    lastRequestAt: Option[Instant],
    lastFetchAt: Option[Instant],
    lastFetchMs: Option[Long],
    lastSuccessAt: Option[Instant],
    lastError: Option[String],
    backingOffUntil: Option[Instant],
    items: Option[Int],
    newestItemAt: Option[Instant],
  ) derives Schema

  final case class Report(status: String, version: String, webhookSubscriptions: Int, feeds: Chunk[Feed]) derives Schema

  private val encoder = JsonCodec.jsonEncoder(summon[Schema[Report]])

  /** The feed's URL as shown: scheme, host, port and path, with `?…` for a query. */
  def shown(url: FeedUrl): String =
    val uri   = url.uri
    val port  = if uri.getPort == -1 then "" else s":${uri.getPort}"
    val query = Option(uri.getRawQuery).fold("")(_ => "?…")
    s"${uri.getScheme}://${uri.getHost}$port${Option(uri.getRawPath).getOrElse("")}$query"

  /** The live webhook subscriptions to each feed. */
  private def subscribers: URIO[McpWebhookStore, Map[FeedUrl, Int]] =
    (for
      all <- ZIO.serviceWithZIO[McpWebhookStore](_.all)
      now <- Clock.instant
    yield all.filter(s => s.key.name.value == ItemEvents.Name && !s.isExpired(now))
      .flatMap(s => s.key.arguments.get("feedUrl").flatMap(_.asString).flatMap(FeedUrl.parse(_).toOption))
      .groupBy(identity).map((url, subs) => url -> subs.size))
      .catchAllCause(c => ZIO.logWarningCause("Health: couldn't read the webhook subscriptions", c).as(Map.empty))

  def report: URIO[Feeds & McpWebhookStore, Report] =
    for
      stats <- ZIO.serviceWithZIO[Feeds](_.stats)
      subs  <- subscribers
    yield
      val feeds = (stats.keySet ++ subs.keySet).toList.map: url =>
        val s = stats.getOrElse(url, FeedStats.empty)
        Feed(shown(url), subs.getOrElse(url, 0), s.requests, s.fetches, s.failures, s.consecutiveFailures,
          s.lastRequestAt, s.lastFetchAt, s.lastFetchMs, s.lastSuccessAt, s.lastError, s.backingOffUntil,
          s.items, s.newestItemAt)
      Report("ok", Rss2Mcp.Version, subs.values.sum, Chunk.fromIterable(feeds.sortBy(_.feed)))

  val routes: Routes[Feeds & McpWebhookStore, Response] =
    Routes(Method.GET / "health" -> handler(report.map(r => Response.json(encoder.encodeJson(r).toString))))
