package rss2mcp

import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8

object FeedParserSpec extends ZIOSpecDefault:
  private def parse(xml: String) = FeedParser.parse(Fixtures.feedUrl, Chunk.fromArray(xml.getBytes(UTF_8).nn))

  def spec = suite("FeedParser")(
    test("reads RSS items newest first, as plain text, and leaves out items without a date"):
      val items = parse(Fixtures.rss).toOption.get
      assertTrue(
        items.map(_.id.value) == Chunk("https://example.com/newer", "older-guid"),
        items(1).title.contains("Older & wiser"),
        items(1).summary.contains("Hello ZIO"),
        items(1).categories == Chunk("scala"),
        items(0).published.toEpochMilli == java.time.Instant.parse("2026-01-02T00:00:00Z").nn.toEpochMilli,
      )
    ,
    test("reads Atom entries, with their content"):
      val items = parse(Fixtures.atom).toOption.get
      assertTrue(
        items.map(_.id.value) == Chunk("urn:example:1"),
        items.head.content.contains("Full text"),
        items.head.author.contains("James"),
        items.head.link.contains("https://example.com/atom-1"),
      )
    ,
    test("fails on a document that isn't a feed"):
      assertTrue(parse("<html><body>nope</body></html>").isLeft)
    ,
    test("refuses a DOCTYPE, so an external entity is never read"):
      val xxe =
        """<?xml version="1.0"?>
          |<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
          |<rss version="2.0"><channel><title>&xxe;</title></channel></rss>""".stripMargin
      assertTrue(parse(xxe).isLeft)
    ,
  )
