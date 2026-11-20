package rss2mcp

import com.jamesward.ziohttp.mcp.*
import zio.*
import zio.test.*

import Fixtures.*

object ItemEventsSpec extends ZIOSpecDefault:
  private val everything = FeedSubscription(feedUrl, None)
  private val items      = Chunk(item("c", 3, "ZIO 3"), item("b", 2, "Kotlin"), item("a", 1, "MCP news"))
  private def ids(items: String*) = items.map(id => Some(ItemId(id).eventId))
  private def cursor(minute: Int) = Some(McpEventCursor(at(minute).toEpochMilli.toString))

  private def since(
    subscription: FeedSubscription = everything,
    from: Option[McpEventCursor],
    maxAge: Option[Duration] = None,
    maxEvents: Int = 100,
    now: Int = 10,
  ) = ItemEvents.since(subscription, items, from, maxAge, maxEvents, at(now))

  def spec = suite("ItemEvents")(
    test("with no cursor, answers the newest item's position and no events"):
      val batch = since(from = None)
      assertTrue(batch.events.isEmpty, batch.cursor == cursor(3))
    ,
    test("with no cursor and an empty feed, answers now"):
      val batch = ItemEvents.since(everything, Chunk.empty, None, None, 100, at(10))
      assertTrue(batch.cursor == cursor(10))
    ,
    test("answers the items after the cursor, oldest first, each with its own position"):
      val batch = since(from = cursor(1))
      assertTrue(
        batch.events.map(_.eventId) == Chunk(ids("b", "c")*),
        batch.events.map(_.cursor) == Chunk(cursor(2), cursor(3)),
        batch.cursor == cursor(3),
        !batch.hasMore,
        !batch.truncated,
      )
    ,
    test("nothing new keeps the cursor"):
      val batch = since(from = cursor(3))
      assertTrue(batch.events.isEmpty, batch.cursor == cursor(3), !batch.truncated)
    ,
    test("keywords filter items, but the cursor moves past the ones left out"):
      val zio   = FeedSubscription(feedUrl, Some(Chunk(Keyword.parse("zio").toOption.get, Keyword.parse("mcp").toOption.get)))
      val batch = since(subscription = zio, from = cursor(0))
      assertTrue(batch.events.map(_.eventId) == Chunk(ids("a", "c")*), batch.cursor == cursor(3))
    ,
    test("maxEvents leaves the rest for the next check"):
      val batch = since(from = cursor(0), maxEvents = 2)
      assertTrue(batch.events.map(_.eventId) == Chunk(ids("a", "b")*), batch.hasMore, batch.cursor == cursor(2))
    ,
    test("maxAge skips older items and says so"):
      val batch = since(from = cursor(0), maxAge = Some(8.minutes))
      assertTrue(batch.events.map(_.eventId) == Chunk(ids("b", "c")*), batch.truncated, batch.cursor == cursor(3))
    ,
    test("a cursor this server didn't write restarts from now and says so"):
      val batch = since(from = Some(McpEventCursor("not-a-cursor")))
      assertTrue(batch.events.isEmpty, batch.truncated, batch.cursor == cursor(3))
    ,
    test("a new subscription to something that isn't a feed is InvalidParams; once subscribed it's Upstream"):
      for
        feeds   <- FakeFeeds.make(Map.empty)
        request  = (from: Option[McpEventCursor]) =>
                     McpEventRequest(everything, from, None, 100, McpRequestContext(ProtocolVersion.V2026_07_28))
        fresh   <- ItemEvents.check(request(None)).provideEnvironment(ZEnvironment[Feeds](feeds)).flip
        later   <- ItemEvents.check(request(cursor(1))).provideEnvironment(ZEnvironment[Feeds](feeds)).flip
      yield assertTrue(
        fresh == McpEventError.InvalidParams(s"${feedUrl.value} answered HTTP 404"),
        later == McpEventError.Upstream(s"${feedUrl.value} answered HTTP 404"),
      )
    ,
    test("blocked hosts are Forbidden, and unreachable ones and 5xx are Upstream even for a new subscription"):
      val fresh = ItemEvents.error(fresh = true)
      assertTrue(
        fresh(FeedError.Blocked(feedUrl, "private")) == McpEventError.Forbidden(s"Can't read ${feedUrl.value}: private"),
        fresh(FeedError.HttpStatus(feedUrl, 503)) == McpEventError.Upstream(s"${feedUrl.value} answered HTTP 503"),
        fresh(FeedError.Unreachable(feedUrl, "timeout")) == McpEventError.Upstream(s"Can't reach ${feedUrl.value}: timeout"),
        fresh(FeedError.Unparseable(feedUrl, "html")) == McpEventError.InvalidParams(s"${feedUrl.value} isn't an RSS or Atom feed: html"),
        fresh(FeedError.Undated(feedUrl, 3)) == McpEventError.InvalidParams(FeedError.Undated(feedUrl, 3).message),
        ItemEvents.error(fresh = false)(FeedError.Undated(feedUrl, 3)) == McpEventError.Upstream(FeedError.Undated(feedUrl, 3).message),
      )
    ,
    test("an event id is header-safe and stable, whatever the item id"):
      val id = ItemId("https://news.ycombinator.com/item?id=50583820")
      assertTrue(
        id.eventId.matches("item_[0-9a-f]{32}"),
        id.eventId == ItemId("https://news.ycombinator.com/item?id=50583820").eventId,
        id.eventId != ItemId("https://news.ycombinator.com/item?id=50583821").eventId,
      )
    ,
    test("an event's summary is shortened, so a delivery stays small"):
      val long = item("x", 1, summary = Some("a" * 10_000))
      assertTrue(ItemPublished(feedUrl, long).summary.exists(_.length == ItemPublished.MaxSummary))
    ,
  )
