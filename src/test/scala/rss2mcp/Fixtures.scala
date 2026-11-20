package rss2mcp

import zio.*

import java.time.Instant

object Fixtures:
  val feedUrl: FeedUrl = FeedUrl.parse("https://example.com/feed.xml").toOption.get

  def at(minute: Int): Instant = Instant.parse("2026-01-01T00:00:00Z").nn.plusSeconds(minute * 60L).nn

  def item(id: String, minute: Int, title: String = "A post", summary: Option[String] = None): FeedItem =
    FeedItem(ItemId(id), Some(title), Some(s"https://example.com/$id"), at(minute), None, Chunk.empty, summary, None)

  final case class FakeFeeds(feeds: Ref[Map[FeedUrl, Chunk[FeedItem]]]) extends Feeds:
    def items(url: FeedUrl): IO[FeedError, Chunk[FeedItem]] =
      feeds.get.flatMap(all => ZIO.fromOption(all.get(url)).orElseFail(FeedError.HttpStatus(url, 404)))
    def publish(url: FeedUrl, item: FeedItem): UIO[Unit] =
      feeds.update(all => all.updated(url, item +: all.getOrElse(url, Chunk.empty)))

  object FakeFeeds:
    def make(initial: Map[FeedUrl, Chunk[FeedItem]]): UIO[FakeFeeds] = Ref.make(initial).map(FakeFeeds(_))

  val rss: String =
    """<?xml version="1.0" encoding="UTF-8"?>
      |<rss version="2.0">
      |  <channel>
      |    <title>Example</title>
      |    <link>https://example.com</link>
      |    <description>Example feed</description>
      |    <item>
      |      <title>Older &amp; wiser</title>
      |      <link>https://example.com/older</link>
      |      <guid>older-guid</guid>
      |      <pubDate>Thu, 01 Jan 2026 00:00:00 GMT</pubDate>
      |      <description>&lt;p&gt;Hello &lt;b&gt;ZIO&lt;/b&gt;&lt;/p&gt;</description>
      |      <category>scala</category>
      |    </item>
      |    <item>
      |      <title>Newer</title>
      |      <link>https://example.com/newer</link>
      |      <pubDate>Fri, 02 Jan 2026 00:00:00 GMT</pubDate>
      |    </item>
      |    <item>
      |      <title>Undated</title>
      |      <guid>undated</guid>
      |    </item>
      |  </channel>
      |</rss>
      |""".stripMargin

  val atom: String =
    """<?xml version="1.0" encoding="utf-8"?>
      |<feed xmlns="http://www.w3.org/2005/Atom">
      |  <title>Example</title>
      |  <id>urn:example</id>
      |  <updated>2026-01-03T00:00:00Z</updated>
      |  <entry>
      |    <title>An Atom entry</title>
      |    <id>urn:example:1</id>
      |    <link href="https://example.com/atom-1"/>
      |    <updated>2026-01-03T00:00:00Z</updated>
      |    <author><name>James</name></author>
      |    <content type="html">&lt;p&gt;Full &lt;i&gt;text&lt;/i&gt;&lt;/p&gt;</content>
      |  </entry>
      |</feed>
      |""".stripMargin
