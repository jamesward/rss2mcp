package rss2mcp

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant

/**
 * A feed for testing subscriptions end to end: `GET /debug/feed.xml` is an
 * Atom feed of the items added with `POST /debug/items` (`Authorization:
 * Bearer <DEBUG_TOKEN>`, optional `title` query parameter). Subscribing to
 * this server's own `/debug/feed.xml` gives an event within a feed check or
 * two of each POST. Served only when `DEBUG_TOKEN` is set.
 */
final case class DebugFeed(token: String, items: Ref[Chunk[DebugFeed.Item]]):
  import DebugFeed.*

  val routes: Routes[Any, Response] = Routes(
    Method.GET / "debug" / "feed.xml" -> handler:
      items.get.map(all => Response(
        headers = Headers(Header.ContentType(MediaType.application.`atom+xml`)),
        body = Body.fromString(atom(all)),
      ))
    ,
    Method.POST / "debug" / "items" -> handler: (request: Request) =>
      if !authorized(request) then
        ZIO.logWarning("Debug item refused: no valid bearer token").as(Response.status(Status.Unauthorized))
      else
        for
          // Whole seconds: Rome reads a nanosecond fraction as milliseconds.
          now   <- Clock.instant.map(_.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
          count <- items.modify(all => (all.size + 1, all))
          title  = request.queryParam("title").filter(_.trim.nonEmpty).getOrElse(s"rss2mcp debug item $count")
          item   = Item(s"urn:rss2mcp:debug:${now.toEpochMilli}:$count", Text.shorten(title, 500), now)
          _     <- items.update(all => (item +: all).take(MaxItems))
          _     <- ZIO.logAnnotate(LogAnnotation("item_id", item.id), LogAnnotation("title", item.title))(
                     ZIO.logInfo("Debug item added"))
        yield Response.json(Json.Obj("id" -> Json.Str(item.id), "title" -> Json.Str(item.title),
          "published" -> Json.Str(item.published.toString)).toJson)
  )

  private def authorized(request: Request): Boolean =
    request.rawHeader("authorization").flatMap(h => Option.when(h.startsWith("Bearer "))(h.drop(7)))
      .exists(sent => MessageDigest.isEqual(sent.getBytes(UTF_8), token.getBytes(UTF_8)))

object DebugFeed:
  final case class Item(id: String, title: String, published: Instant)

  val MaxItems: Int = 50

  /** The debug feed, when `DEBUG_TOKEN` is set. */
  val fromEnv: UIO[Option[DebugFeed]] =
    System.env("DEBUG_TOKEN").orDie.flatMap: token =>
      ZIO.foreach(token.filter(_.trim.nonEmpty))(t => Ref.make(Chunk.empty[Item]).map(DebugFeed(t, _)))

  def atom(items: Chunk[Item]): String =
    val updated = items.headOption.fold(Instant.EPOCH)(_.published)
    val entries = items.map: item =>
      s"""  <entry>
         |    <id>${escape(item.id)}</id>
         |    <title>${escape(item.title)}</title>
         |    <updated>${item.published}</updated>
         |    <summary>A test item posted to rss2mcp's debug feed at ${item.published}.</summary>
         |  </entry>""".stripMargin
    s"""<?xml version="1.0" encoding="utf-8"?>
       |<feed xmlns="http://www.w3.org/2005/Atom">
       |  <id>urn:rss2mcp:debug</id>
       |  <title>rss2mcp debug feed</title>
       |  <updated>$updated</updated>
       |${entries.mkString("\n")}
       |</feed>
       |""".stripMargin

  private def escape(text: String): String =
    text.flatMap:
      case '&'  => "&amp;"
      case '<'  => "&lt;"
      case '>'  => "&gt;"
      case '"'  => "&quot;"
      case '\'' => "&apos;"
      case c    => c.toString
