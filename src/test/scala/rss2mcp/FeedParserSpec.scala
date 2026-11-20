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
    test("reads an RSS 0.91 feed that declares the Netscape DTD"):
      val rss091 =
        """<?xml version="1.0"?>
          |<!DOCTYPE rss PUBLIC "-//Netscape Communications//DTD RSS 0.91//EN" "http://my.netscape.com/publish/formats/rss-0.91.dtd">
          |<rss version="0.91"><channel><title>Old</title><link>https://example.com</link><description>d</description>
          |<item><title>Hello</title><link>https://example.com/hello</link><description>hi</description></item>
          |</channel></rss>""".stripMargin
      // RSS 0.91 items have no dates: the feed parses, but can't be followed.
      assertTrue(parse(rss091) == Left(FeedError.Undated(Fixtures.feedUrl, 1)))
    ,
    test("a feed with no items isn't undated: there's just nothing yet"):
      val empty = """<rss version="2.0"><channel><title>t</title><link>https://example.com</link><description>d</description></channel></rss>"""
      assertTrue(parse(empty).map(_.size) == Right(0))
    ,
    test("an external entity is never read"):
      val xxe =
        """<?xml version="1.0"?>
          |<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
          |<rss version="2.0"><channel><title>t</title><link>https://example.com</link><description>d</description>
          |<item><title>&xxe;</title><guid>x</guid><pubDate>Thu, 01 Jan 2026 00:00:00 GMT</pubDate></item>
          |</channel></rss>""".stripMargin
      val text = parse(xxe).toOption.toList.flatMap(_.toList).flatMap(i => i.title.toList ++ i.summary).mkString
      assertTrue(parse(xxe).isRight, !text.contains("root:"))
    ,
    test("an HTML page is reported as one, with the feeds it links to"):
      val page =
        """<!doctype html><html><head><title>Blog</title>
          |<link rel="alternate" type="application/rss+xml" href="/feed.xml">
          |<link rel="alternate" type="application/atom+xml" href="https://example.com/atom.xml">
          |</head><body>hi</body></html>""".stripMargin
      val plain = "<html><body>nothing here</body></html>"
      assertTrue(
        parse(page).left.toOption.map(_.message).contains(
          s"${Fixtures.feedUrl.value} isn't an RSS or Atom feed: it's an HTML page, not a feed; it links to https://example.com/feed.xml, https://example.com/atom.xml"),
        parse(plain).left.toOption.exists(_.message.endsWith("it doesn't link to one")),
      )
    ,
  )
