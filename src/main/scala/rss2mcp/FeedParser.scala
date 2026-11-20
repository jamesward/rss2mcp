package rss2mcp

import com.rometools.rome.feed.synd.{SyndContent, SyndEntry}
import com.rometools.rome.io.{SyndFeedInput, XmlReader}
import org.jsoup.Jsoup
import zio.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import scala.util.Using

object FeedParser:

  /**
   * The feed's items that have an id and a date, newest first. An item with
   * neither a published nor an updated date can't be placed after a cursor, so
   * it is left out, and a feed whose items all lack one is [[FeedError.Undated]].
   */
  def parse(url: FeedUrl, bytes: Chunk[Byte]): Either[FeedError, Chunk[FeedItem]] =
    html(url, bytes).fold(xml(url, bytes))(Left(_))

  private def xml(url: FeedUrl, bytes: Chunk[Byte]): Either[FeedError, Chunk[FeedItem]] =
    Using(XmlReader(ByteArrayInputStream(bytes.toArray))): reader =>
      // A DOCTYPE is allowed because RSS 0.91 feeds declare one. Rome still
      // turns off external entities and external DTDs, resolves every entity
      // to nothing and doesn't expand entities, so an XXE reads nothing.
      val input = SyndFeedInput()
      input.setAllowDoctypes(true)
      Chunk.fromIterable(input.build(reader).nn.getEntries.nn.asScala)
    .toEither
    .left.map(e => FeedError.Unparseable(url, Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
    .flatMap: entries =>
      val items = entries.flatMap(item)
      if entries.nonEmpty && items.isEmpty then Left(FeedError.Undated(url, entries.size))
      else Right(items.sortBy(_.published).reverse)

  /**
   * An HTML page rather than a feed, as people often give a site's address:
   * the error names the feeds the page links to, if it does.
   */
  private def html(url: FeedUrl, bytes: Chunk[Byte]): Option[FeedError] =
    val head = String(bytes.take(4096).toArray, UTF_8).toLowerCase.nn
    val looksHtml = (head.contains("<html") || head.contains("<!doctype html")) &&
      !Chunk("<rss", "<feed", "<rdf:rdf").exists(head.contains)
    Option.when(looksHtml):
      val page = Jsoup.parse(String(bytes.toArray, UTF_8), url.value).nn
      val feeds = Chunk.fromIterable(page.select("link[rel~=(?i)alternate][type~=(?i)application/(rss|atom)\\+xml]").nn.asScala)
        .flatMap(link => nonBlank(link.absUrl("href")))
        .distinct
      val hint =
        if feeds.isEmpty then "it doesn't link to one"
        else s"it links to ${feeds.mkString(", ")}"
      FeedError.Unparseable(url, s"it's an HTML page, not a feed; $hint")

  private def item(entry: SyndEntry): Option[FeedItem] =
    val link = nonBlank(entry.getLink)
    for
      id        <- nonBlank(entry.getUri).orElse(link)
      published <- Option(entry.getPublishedDate).orElse(Option(entry.getUpdatedDate))
    yield FeedItem(
      id = ItemId(id),
      title = nonBlank(entry.getTitle).map(plain),
      link = link,
      published = published.toInstant.nn,
      author = nonBlank(entry.getAuthor),
      categories = Chunk.fromIterable(entry.getCategories.nn.asScala).flatMap(c => nonBlank(c.getName)),
      summary = Option(entry.getDescription).flatMap(text),
      content = Chunk.fromIterable(entry.getContents.nn.asScala).flatMap(text).reduceOption(_ + "\n\n" + _),
    )

  private def text(content: SyndContent): Option[String] =
    nonBlank(content.getValue).map(plain).flatMap(nonBlank)

  /** Feed text is often HTML; agents get it as plain text. */
  private def plain(html: String): String = Jsoup.parse(html).nn.text.nn

  private def nonBlank(value: String | Null): Option[String] =
    Option(value).map(_.trim.nn).filter(_.nonEmpty)
