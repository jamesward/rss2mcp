package rss2mcp

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.client.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.codec.JsonCodec
import zio.test.*

import Fixtures.*

object Rss2McpSpec extends ZIOSpecDefault:

  private given zio.json.JsonCodec[ItemPublished] = JsonCodec.jsonCodec(summon[zio.schema.Schema[ItemPublished]])

  // Webhooks to a receiver on localhost, over http: the production targets refuse both.
  private val localWebhooks = Rss2Mcp.webhooks.copy(targets = McpWebhookTargets.unrestricted)

  private def install(initial: Map[FeedUrl, Chunk[FeedItem]], extra: Routes[Any, Response] = Routes.empty) =
    for
      feeds  <- FakeFeeds.make(initial)
      server <- Rss2Mcp.server(100.millis, localWebhooks)
                  .provideSomeLayer[Scope](ZLayer.succeed[Feeds](feeds) ++ InMemoryWebhookStore.layer)
      port   <- Server.install(Rss2Mcp.routes(server) ++ extra)
                  .provideSomeLayer[Server & Scope](ZLayer.succeed[Feeds](feeds) ++ McpServer.State.default)
    yield (feeds, port, McpClientConfig(s"http://localhost:$port/"))

  /** A webhook endpoint that echoes verification challenges and keeps every POST body. */
  private def receiver(bodies: Queue[Json.Obj]): Routes[Any, Response] =
    Routes(Method.POST / "hook" -> handler: (request: Request) =>
      request.body.asString.orDie.map(_.fromJson[Json.Obj].toOption.get).flatMap: body =>
        bodies.offer(body).as:
          body.get("challenge").fold(Response.ok)(c => Response.json(Json.Obj("challenge" -> c).toJson)))

  private def subscription(keywords: String*): Json.Obj =
    FeedSubscription(feedUrl, Option.when(keywords.nonEmpty)(Chunk.fromIterable(keywords).map(Keyword.parse(_).toOption.get)))
      .toJsonAST(using JsonCodec.jsonEncoder(summon[zio.schema.Schema[FeedSubscription]])).toOption.get.asObject.get

  private def published(signal: McpEventSignal): Option[ItemPublished] = signal match
    case McpEventSignal.Event(occurrence) => occurrence.data.toJson.fromJson[ItemPublished].toOption
    case _                                => None

  private def receive(delivery: McpEventDelivery, keywords: String*) =
    ZIO.scoped:
      for
        (feeds, _, config) <- install(Map(feedUrl -> Chunk(item("old", 1))))
        client          <- McpEventsClient.connect(config)
        signals         <- client.subscribe(McpEventName(ItemEvents.Name), subscription(keywords*), delivery = Some(delivery))
                             .tap:
                               // Subscribed from now: publish once the subscription has its position.
                               case McpEventSignal.Active(_, _) | McpEventSignal.Heartbeat(_) =>
                                 feeds.publish(feedUrl, item("kotlin", 2, "Kotlin news")) *>
                                   feeds.publish(feedUrl, item("zio", 3, "ZIO news"))
                               case _ => ZIO.unit
                             .collect(Function.unlift(published))
                             .take(1).runCollect
      yield signals

  def spec = suite("rss2mcp")(
    test("events/list describes feed.item.published"):
      ZIO.scoped:
        for
          (_, _, config) <- install(Map.empty)
          client      <- McpEventsClient.connect(config)
          events      <- client.listAll
        yield
          val described = events.find(_.name == McpEventName(ItemEvents.Name))
          assertTrue(
            described.flatMap(_.inputSchema.get("properties")).flatMap(_.asObject).exists(_.get("feedUrl").isDefined),
            described.flatMap(_.payloadSchema.get("properties")).flatMap(_.asObject).exists(_.get("itemId").isDefined),
          )
    ,
    test("a push subscription gets the new items that match its keywords"):
      receive(McpEventDelivery.Push, "zio").map(items => assertTrue(items.map(_.itemId.value) == Chunk("zio")))
    ,
    test("a poll subscription gets the new items"):
      receive(McpEventDelivery.Poll).map(items => assertTrue(items.map(_.itemId.value) == Chunk("kotlin")))
    ,
    test("an anonymous webhook subscription, as ChatGPT makes one, is verified and gets new items"):
      ZIO.scoped:
        for
          bodies              <- Queue.unbounded[Json.Obj]
          (feeds, port, _)    <- install(Map(feedUrl -> Chunk(item("old", 1))), receiver(bodies))
          secret               = "whsec_" + java.util.Base64.getEncoder.encodeToString(Array.fill(32)(7.toByte))
          params               = Json.Obj(
                                   "name" -> Json.Str(ItemEvents.Name),
                                   "arguments" -> subscription("zio"),
                                   "delivery" -> Json.Obj("mode" -> Json.Str("webhook"),
                                     "url" -> Json.Str(s"http://localhost:$port/hook"), "secret" -> Json.Str(secret)),
                                 )
          meta                 = Json.Obj(
                                   "io.modelcontextprotocol/protocolVersion" -> Json.Str("2026-07-28"),
                                   "io.modelcontextprotocol/clientCapabilities" -> Json.Obj("extensions" -> Json.Obj()),
                                 )
          request              = Request.post(URL.decode(s"http://localhost:$port/").toOption.get,
                                   Body.fromString(Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> Json.Num(1),
                                     "method" -> Json.Str("events/subscribe"),
                                     "params" -> Json.Obj(params.fields :+ ("_meta" -> meta))).toJson))
                                   .addHeader(Header.ContentType(MediaType.application.json))
                                   .addHeader("accept", "application/json, text/event-stream")
                                   .addHeader("user-agent", "openai-mcp/1.0")
                                   .addHeader("mcp-protocol-version", "2026-07-28")
                                   .addHeader("mcp-method", "events/subscribe")
                                   .addHeader("mcp-name", ItemEvents.Name)
          result              <- ZClient.batched(request).flatMap(_.body.asString).map(_.fromJson[Json.Obj].toOption.get)
          verification        <- bodies.take
          _                   <- feeds.publish(feedUrl, item("kotlin", 2, "Kotlin news")) *>
                                   feeds.publish(feedUrl, item("zio", 3, "ZIO news"))
          delivery            <- bodies.take.timeoutFail(RuntimeException("no delivery"))(10.seconds)
        yield
          val data = delivery.get("data").flatMap(_.asObject)
          assertTrue(
            result.get("result").flatMap(_.asObject).flatMap(_.get("id")).isDefined,
            verification.get("type").contains(Json.Str("verification")),
            delivery.get("eventId").contains(Json.Str("zio")),
            data.flatMap(_.get("title")).contains(Json.Str("ZIO news")),
          )
    ,
    test("get_item answers an item's full text"):
      ZIO.scoped:
        for
          (_, _, config) <- install(Map(feedUrl -> Chunk(item("a", 1, summary = Some("The whole story")))))
          client      <- McpClient.connect(config)
          detail      <- client.callToolAs[GetItem, ItemDetail]("get_item", GetItem(feedUrl, ItemId("a")))
        yield assertTrue(detail.itemId == ItemId("a"), detail.summary.contains("The whole story"))
    ,
    test("get_item reports an item the feed no longer lists"):
      ZIO.scoped:
        for
          (_, _, config) <- install(Map(feedUrl -> Chunk.empty))
          client      <- McpClient.connect(config)
          result      <- client.callTool[GetItem]("get_item", GetItem(feedUrl, ItemId("gone")))
        yield assertTrue(result.isError.contains(true))
    ,
    test("LOG_LEVEL names the log floor, INFO when it names none"):
      assertTrue(
        AppLogging.levelNamed(Some("debug")).label == zio.LogLevel.Debug.label,
        AppLogging.levelNamed(Some(" WARN ")).label == zio.LogLevel.Warning.label,
        AppLogging.levelNamed(Some("chatty")).label == zio.LogLevel.Info.label,
        AppLogging.levelNamed(None).label == zio.LogLevel.Info.label,
      )
    ,
    test("log entries made while handling a request carry its X-Request-ID"):
      val routes = Routes(Method.GET / "hello" -> handler(ZIO.logInfo("handling").as(Response.ok))) @@ Rss2Mcp.requestId
      for
        _       <- ZIO.scoped(routes.runZIO(Request.get(URL.root / "hello").addHeader("X-Request-ID", "abc-123")))
        _       <- ZIO.scoped(routes.runZIO(Request.get(URL.root / "hello")))
        entries <- ZTestLogger.logOutput
      yield
        val handling = entries.filter(_.message() == "handling").map(_.annotations.get("request_id"))
        assertTrue(handling == Chunk(Some("abc-123"), None))
    ,
  ).provide(Server.defaultWithPort(0), Client.default) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
