package rss2mcp

import zio.*
import zio.cache.{Cache, Lookup}
import zio.http.*
import zio.http.netty.NettyConfig

import java.net.{Inet6Address, InetAddress}

/** Reads a feed's items, newest first. */
trait Feeds:
  def items(url: FeedUrl): IO[FeedError, Chunk[FeedItem]]

object Feeds:
  /**
   * An HTTP client that checks a feed's TLS certificate and host name against
   * the JDK's trust store. zio-http's `Client.default` accepts any certificate,
   * which would let anyone on the network path rewrite a feed.
   */
  val client: ZLayer[Any, Throwable, Client] =
    (ZLayer.succeed(ZClient.Config.default.ssl(ClientSSLConfig.FromSystemTrustStore)) ++
      ZLayer.succeed(NettyConfig.defaultWithFastShutdown) ++ DnsResolver.default) >>> Client.live

  final case class Config(
    maxBytes: Long = 5L * 1024 * 1024,
    maxRedirects: Int = 5,
    // Some busy feeds (hnrss.org) take several seconds to answer.
    timeout: Duration = 30.seconds,
    /** How long a fetched feed is reused, so subscriptions to one feed share a fetch. */
    ttl: Duration = 1.minute,
    capacity: Int = 10_000,
  )

  /** Fetches feeds over HTTP, after `guard` allows each hop, and caches them for `config.ttl`. */
  def http(config: Config, guard: HostGuard): ZLayer[Client, Nothing, Feeds] =
    ZLayer:
      for
        client <- ZIO.service[Client]
        fetch   = HttpFetch(client, config, guard)
        cache  <- Cache.makeWith(config.capacity, Lookup(fetch.items)):
                    case Exit.Success(_) => config.ttl
                    case Exit.Failure(_) => 10.seconds
      yield new Feeds:
        def items(url: FeedUrl): IO[FeedError, Chunk[FeedItem]] = cache.get(url)

  private final case class HttpFetch(client: Client, config: Config, guard: HostGuard):
    /** A cache miss: fetch and parse the feed, and log how it went. */
    def items(url: FeedUrl): IO[FeedError, Chunk[FeedItem]] =
      ZIO.logAnnotate(LogAnnotation("feed", url.value)):
        for
          started <- Clock.nanoTime
          bytes   <- fetch(url, config.maxRedirects)
          items   <- ZIO.fromEither(FeedParser.parse(url, bytes))
          ended   <- Clock.nanoTime
          _       <- ZIO.logAnnotate(
                       LogAnnotation("bytes", bytes.size.toString),
                       LogAnnotation("items", items.size.toString),
                       LogAnnotation("newest", items.headOption.fold("-")(_.published.toString)),
                       LogAnnotation("duration_ms", ((ended - started) / 1000000).toString),
                     )(ZIO.logInfo("Feed fetched"))
        yield items
      .tapError(e => ZIO.logAnnotate(LogAnnotation("feed", url.value), LogAnnotation("error", e.message))(
        ZIO.logWarning("Feed fetch failed")))

    private def fetch(url: FeedUrl, redirectsLeft: Int): IO[FeedError, Chunk[Byte]] =
      for
        _        <- guard.check(url)
        target   <- ZIO.fromEither(URL.decode(url.value)).orElseFail(FeedError.Unreachable(url, "invalid URL"))
        request   = Request.get(target).addHeader("User-Agent", s"rss2mcp/${Rss2Mcp.Version}").addHeaders(Headers(
                      Header.Accept(MediaType.application.`rss+xml`, MediaType.application.`atom+xml`,
                        MediaType.application.xml, MediaType.text.xml, MediaType.any),
                    ))
        bytes    <- ZIO.scoped(ZClient.streaming(request).provideSomeLayer[Scope](ZLayer.succeed(client)).mapError(e => FeedError.Unreachable(url, e.getMessage)).flatMap(response => read(url, response, redirectsLeft)))
                      .timeoutFail(FeedError.Unreachable(url, s"no answer within ${config.timeout.render}"))(config.timeout)
      yield bytes

    private def read(url: FeedUrl, response: Response, redirectsLeft: Int): ZIO[Scope, FeedError, Chunk[Byte]] =
      ZIO.logAnnotate(
        LogAnnotation("hop", url.value),
        LogAnnotation("status", response.status.code.toString),
        LogAnnotation("content_type", response.rawHeader("content-type").getOrElse("-")),
        LogAnnotation("location", response.rawHeader("location").getOrElse("-")),
      )(ZIO.logDebug("Feed answered")) *>
      (response.status match
        case status if status.isRedirection =>
          if redirectsLeft <= 0 then ZIO.fail(FeedError.TooManyRedirects(url))
          else
            ZIO.fromOption(response.header(Header.Location))
              .orElseFail(FeedError.Unreachable(url, s"HTTP ${status.code} without a Location"))
              .flatMap(location => ZIO.fromEither(FeedUrl.parse(url.uri.resolve(location.url.encode).toString))
                .mapError(reason => FeedError.Blocked(url, s"it redirected to $reason")))
              .flatMap(next => fetch(next, redirectsLeft - 1))
        case status if status.isSuccess =>
          response.body.asStream.take(config.maxBytes + 1).runCollect
            .mapError(e => FeedError.Unreachable(url, e.getMessage))
            .filterOrFail(_.size <= config.maxBytes)(FeedError.TooLarge(url, config.maxBytes))
        case status => ZIO.fail(FeedError.HttpStatus(url, status.code)))
    .catchAllDefect(e => ZIO.fail(FeedError.Unreachable(url, e.getMessage)))

/** Decides whether the server may connect to a feed's host. */
trait HostGuard:
  def check(url: FeedUrl): IO[FeedError, Unit]

object HostGuard:
  /**
   * Refuses hosts that resolve to loopback, private, link-local (including cloud
   * metadata endpoints) or multicast addresses, so a subscription can't make
   * the server read its own network. The connection resolves the name again,
   * so a DNS answer that changes between the two lookups isn't caught.
   */
  val publicOnly: HostGuard = new HostGuard:
    def check(url: FeedUrl): IO[FeedError, Unit] =
      ZIO.attemptBlocking(InetAddress.getAllByName(url.host).nn.toList.map(_.nn))
        .mapError(e => FeedError.Unreachable(url, s"can't resolve ${url.host}: ${e.getMessage}"))
        .flatMap: addresses =>
          ZIO.foreachDiscard(addresses.find(isInternal)): address =>
            ZIO.fail(FeedError.Blocked(url, s"${url.host} resolves to the non-public address ${address.getHostAddress}"))

  /** Allows every host: for tests against a local server. */
  val allowAll: HostGuard = new HostGuard:
    def check(url: FeedUrl): IO[FeedError, Unit] = ZIO.unit

  def isInternal(address: InetAddress): Boolean =
    address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
      address.isSiteLocalAddress || address.isMulticastAddress || isUniqueLocal(address) || isSharedAddressSpace(address)

  /** IPv6 unique local addresses, fc00::/7. */
  private def isUniqueLocal(address: InetAddress): Boolean = address match
    case v6: Inet6Address => (v6.getAddress.nn(0) & 0xfe) == 0xfc
    case _                => false

  /** Carrier-grade NAT, 100.64.0.0/10. */
  private def isSharedAddressSpace(address: InetAddress): Boolean =
    val bytes = address.getAddress.nn
    bytes.length == 4 && (bytes(0) & 0xff) == 100 && (bytes(1) & 0xc0) == 64
