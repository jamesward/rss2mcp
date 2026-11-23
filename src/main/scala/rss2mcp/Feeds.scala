package rss2mcp

import zio.*
import zio.cache.{Cache, Lookup}
import zio.http.*
import zio.http.netty.NettyConfig

import java.net.{Inet6Address, InetAddress}

/** Reads a feed's items, newest first. */
trait Feeds:
  def items(url: FeedUrl): IO[FeedError, Chunk[FeedItem]]

  /** How reading each feed asked for in the last hour has gone. */
  def stats: UIO[Map[FeedUrl, FeedStats]] = ZIO.succeed(Map.empty)

object Feeds:
  /**
   * An HTTP client that checks a feed's TLS certificate and host name against
   * the JDK's trust store. zio-http's `Client.default` accepts any certificate,
   * which would let anyone on the network path rewrite a feed.
   *
   * A connection stays open for `IdleTimeout` between fetches, longer than the
   * one-minute poll, so a feed's next fetch reuses it rather than connecting
   * again. The pool is dynamic and starts empty: zio-http's default, a fixed
   * pool of 10, opens all 10 connections to a host on its first request (11 s
   * to an address that doesn't answer, with a 1 s connect timeout), and one of
   * them that failed in the background fails a later fetch at once. A busy feed host can drop new connections (hnrss.org loses some of
   * their SYNs, so a connect takes 1, 3 or 7 seconds, or fails); a reused one
   * skips that. A new connection gives up after `ConnectTimeout`, inside a
   * fetch's `timeout`, so the client fails it rather than the fetch's timeout
   * interrupting it.
   */
  val client: ZLayer[Any, Throwable, Client] =
    (ZLayer.succeed(ZClient.Config.default.ssl(ClientSSLConfig.FromSystemTrustStore)
      .connectionTimeout(ConnectTimeout).idleTimeout(IdleTimeout)
      .dynamicConnectionPool(minimum = 0, maximum = 10, ttl = IdleTimeout)) ++
      ZLayer.succeed(NettyConfig.defaultWithFastShutdown) ++ DnsResolver.default) >>> Client.live

  /** Covers a SYN retransmitted at 15 seconds (after 1, 2 and 4 seconds lost), and leaves room in a 20-second fetch. */
  val ConnectTimeout: Duration = 16.seconds

  /** Longer than `Main.PollInterval`, so a feed polled once a minute keeps its connection. zio-http's default is 50 s. */
  val IdleTimeout: Duration = 90.seconds

  final case class Config(
    maxBytes: Long = 5L * 1024 * 1024,
    maxRedirects: Int = 5,
    // Some busy feeds (hnrss.org) take several seconds to answer. Under Heroku's 30-second router
    // timeout, so a subscribe to a feed that doesn't answer still gets its error back.
    timeout: Duration = 20.seconds,
    /** How long a fetched feed is reused, so subscriptions to one feed share a fetch. */
    ttl: Duration = 1.minute,
    capacity: Int = 10_000,
    /**
     * A feed that fails is not fetched again for `backoff`, doubled for each failure in a row, up to
     * `maxBackoff`; a fetch that succeeds starts over. Meanwhile its subscribers get the last error.
     */
    backoff: Duration = 30.seconds,
    maxBackoff: Duration = 15.minutes,
  ):
    /** How long to wait after `failures` failed fetches in a row. */
    def backoffAfter(failures: Int): Duration =
      val doubled = backoff.toMillis * (1L << (failures - 1).min(30))
      Duration.fromMillis(doubled.min(maxBackoff.toMillis))

  /** A feed whose last `failures` fetches failed: it isn't fetched again before `retryAt`. */
  final case class Failing(failures: Int, error: FeedError, retryAt: java.time.Instant)

  /** Fetches feeds over HTTP, after `guard` allows each hop, and caches them for `config.ttl`. */
  def http(config: Config, guard: HostGuard): ZLayer[Client, Nothing, Feeds] =
    ZLayer.scoped:
      for
        client  <- ZIO.service[Client]
        scope   <- ZIO.scope
        failing <- Ref.make(Map.empty[FeedUrl, Failing])
        counts  <- Ref.make(Map.empty[FeedUrl, FeedStats])
        fetch    = HttpFetch(client, config, guard)
        cache  <- Cache.makeWith(config.capacity, Lookup(recorded(counts, fetch.uninterrupted))):
                    case Exit.Success(_) => config.ttl
                    // A failure is shared by the callers waiting for it, then not kept: the backoff decides
                    // when the feed is fetched again.
                    case Exit.Failure(_) => Duration.Zero
      yield new Feeds:
        // On a fiber of its own: when the caller that started a fetch is interrupted (a client that hangs
        // up, a router's timeout), zio-cache interrupts every caller waiting for that fetch too, and an
        // interrupted request ends without an answer. Interrupting a caller only stops its own join.
        def items(url: FeedUrl): IO[FeedError, Chunk[FeedItem]] =
          Clock.instant.flatMap: now =>
            counts.update(all => all.updated(url, all.getOrElse(url, FeedStats.empty).requested(now))) *>
            failing.get.map(_.get(url).filter(_.retryAt.isAfter(now))).flatMap:
              case Some(f) =>
                ZIO.logAnnotate(LogAnnotation("feed", url.value), LogAnnotation("failures", f.failures.toString),
                  LogAnnotation("retry_at", f.retryAt.toString))(ZIO.logDebug("Feed backing off")) *> ZIO.fail(f.error)
              case None =>
                cache.get(url).forkIn(scope).flatMap(_.join).tapBoth(failed(url, _), _ => failing.update(_ - url))

        override def stats: UIO[Map[FeedUrl, FeedStats]] =
          Clock.instant.flatMap: now =>
            val since = now.minus(FeedStats.Forget)
            counts.updateAndGet(_.filter((_, s) => s.lastRequestAt.exists(_.isAfter(since)))).zipWith(failing.get):
              (all, backingOff) => all.map: (url, s) =>
                url -> backingOff.get(url).fold(s)(f =>
                  s.copy(consecutiveFailures = f.failures, backingOffUntil = Option.when(f.retryAt.isAfter(now))(f.retryAt)))

        // Callers that shared one failed fetch count it once: the first sets a retryAt in the future.
        private def failed(url: FeedUrl, error: FeedError): UIO[Unit] =
          Clock.instant.flatMap: now =>
            failing.modify: all =>
              all.get(url) match
                case Some(f) if f.retryAt.isAfter(now) => (None, all)
                case previous =>
                  val failures = previous.fold(1)(_.failures + 1)
                  val next     = Failing(failures, error, now.plus(config.backoffAfter(failures)))
                  (Some(next), all.updated(url, next))
            .flatMap: recorded =>
              ZIO.foreachDiscard(recorded): f =>
                ZIO.logAnnotate(LogAnnotation("feed", url.value), LogAnnotation("failures", f.failures.toString),
                  LogAnnotation("retry_at", f.retryAt.toString))(ZIO.logInfo("Feed backing off after a failed fetch"))

  /** `fetch`, with each fetch's time and outcome kept in `counts`. */
  private def recorded(counts: Ref[Map[FeedUrl, FeedStats]], fetch: FeedUrl => IO[FeedError, Chunk[FeedItem]])(
    url: FeedUrl): IO[FeedError, Chunk[FeedItem]] =
    for
      started <- Clock.instant
      timed   <- fetch(url).either.timed
      (took, outcome) = timed
      _       <- counts.update(all =>
                   all.updated(url, all.getOrElse(url, FeedStats.empty).fetched(started, took.toMillis, outcome)))
      items   <- ZIO.fromEither(outcome)
    yield items

  private final case class HttpFetch(client: Client, config: Config, guard: HostGuard):
    /**
     * `items`, with an interruption from inside it (the HTTP client) as an
     * `Unreachable`, so callers get an error rather than ending without an answer.
     */
    def uninterrupted(url: FeedUrl): IO[FeedError, Chunk[FeedItem]] =
      items(url).catchAllCause: cause =>
        if cause.isInterruptedOnly then
          ZIO.logAnnotate(LogAnnotation("feed", url.value))(ZIO.logWarningCause("Feed fetch interrupted", cause)) *>
            ZIO.fail(FeedError.Unreachable(url, "the fetch was interrupted"))
        else ZIO.refailCause(cause)

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
