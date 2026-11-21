package rss2mcp

import zio.*
import zio.http.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8

object DebugFeedSpec extends ZIOSpecDefault:
  private def post(debug: DebugFeed, token: Option[String], title: Option[String]) =
    val url = title.fold(URL.root / "debug" / "items")(t => (URL.root / "debug" / "items").addQueryParam("title", t))
    val request = token.fold(Request.post(url, Body.empty))(t =>
      Request.post(url, Body.empty).addHeader(Header.Authorization.Bearer(t)))
    ZIO.scoped(debug.routes.runZIO(request))

  def spec = suite("DebugFeed")(
    test("an item posted with the token shows up in the feed, newest first; without it, 401"):
      for
        items   <- Ref.make(Chunk.empty[DebugFeed.Item])
        debug    = DebugFeed("secret", items)
        refused <- post(debug, None, Some("nope"))
        wrong   <- post(debug, Some("guess"), Some("nope"))
        first   <- post(debug, Some("secret"), Some("First \"test\" & more"))
        _       <- TestClock.adjust(1.second)
        second  <- post(debug, Some("secret"), None)
        feed    <- ZIO.scoped(debug.routes.runZIO(Request.get(URL.root / "debug" / "feed.xml"))).flatMap(_.body.asString)
      yield
        val parsed = FeedParser.parse(Fixtures.feedUrl, Chunk.fromArray(feed.getBytes(UTF_8))).toOption.get
        assertTrue(
          refused.status.code == 401,
          wrong.status.code == 401,
          first.status.code == 200,
          second.status.code == 200,
          parsed.flatMap(_.title) == Chunk("rss2mcp debug item 2", "First \"test\" & more"),
        )
    ,
  )
