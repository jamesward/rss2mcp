package rss2mcp

import zio.*

import java.time.Instant

/**
 * How reading one feed has gone since the server started, for `GET /health`.
 *
 * @param requests            reads asked for (subscription checks and `get_item`), most of them answered from the cache
 * @param fetches             HTTP fetches of the feed
 * @param failures            fetches that failed
 * @param consecutiveFailures failures since the last fetch that succeeded
 * @param backingOffUntil     while the feed backs off after failures, when it may be fetched again
 * @param items               how many items the last successful fetch found
 * @param newestItemAt        the publish time of the newest of them
 */
final case class FeedStats(
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
):
  def requested(at: Instant): FeedStats = copy(requests = requests + 1, lastRequestAt = Some(at))

  def fetched(at: Instant, millis: Long, outcome: Either[FeedError, Chunk[FeedItem]]): FeedStats =
    val tried = copy(fetches = fetches + 1, lastFetchAt = Some(at), lastFetchMs = Some(millis))
    outcome match
      case Left(error)  => tried.copy(failures = failures + 1, lastError = Some(error.message))
      case Right(found) =>
        tried.copy(lastSuccessAt = Some(at), lastError = None, items = Some(found.size),
          newestItemAt = found.map(_.published).maxOption)

object FeedStats:
  val empty: FeedStats = FeedStats(0, 0, 0, 0, None, None, None, None, None, None, None, None)

  /** A feed no one has asked for in this long is dropped from the stats. */
  val Forget: Duration = 1.hour
