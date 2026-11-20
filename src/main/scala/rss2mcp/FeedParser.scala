package rss2mcp

import com.rometools.rome.feed.synd.{SyndContent, SyndEntry}
import com.rometools.rome.io.{SyndFeedInput, XmlReader}
import org.jsoup.Jsoup
import zio.*

import java.io.ByteArrayInputStream
import scala.jdk.CollectionConverters.*
import scala.util.Using

object FeedParser:

  /**
   * The feed's items that have an id and a date, newest first. An item with
   * neither a published nor an updated date can't be placed after a cursor, so
   * it is left out.
   */
  def parse(url: FeedUrl, bytes: Chunk[Byte]): Either[FeedError, Chunk[FeedItem]] =
    Using(XmlReader(ByteArrayInputStream(bytes.toArray))): reader =>
      // Rome refuses DOCTYPE declarations by default, which rules out XXE.
      val feed = SyndFeedInput().build(reader).nn
      Chunk.fromIterable(feed.getEntries.nn.asScala).flatMap(item)
    .toEither
    .left.map(e => FeedError.Unparseable(url, Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
    .map(_.sortBy(_.published).reverse)

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
