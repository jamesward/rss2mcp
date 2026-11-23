package rss2mcp

import zio.*
import zio.http.*
import zio.test.*

import java.net.InetAddress

object FeedsSpec extends ZIOSpecDefault:

  private val routes = Routes(
    Method.GET / "feed.xml" -> handler(Response.text(Fixtures.rss).contentType(MediaType.application.`rss+xml`)),
    Method.GET / "moved"    -> handler(Response.redirect(URL.root / "feed.xml")),
    Method.GET / "loop"     -> handler(Response.redirect(URL.root / "loop")),
    Method.GET / "huge"     -> handler(Response.text("x" * 2000)),
    Method.GET / "page"     -> handler(Response.html("<html><body>not a feed</body></html>")),
  )

  private def url(port: Int, path: String): FeedUrl = FeedUrl.parse(s"http://localhost:$port/$path").toOption.get

  private def withFeeds[E, A](config: Feeds.Config, guard: HostGuard, extra: Routes[Any, Response] = Routes.empty)(
    f: (Int, Feeds) => ZIO[Any, E, A]) =
    ZIO.scoped:
      for
        port  <- Server.install(routes ++ extra)
        feeds <- (Client.default >>> Feeds.http(config, guard)).build.map(_.get)
        a     <- f(port, feeds)
      yield a

  private def failure(config: Feeds.Config = Feeds.Config(), guard: HostGuard = HostGuard.allowAll)(path: String) =
    withFeeds(config, guard): (port, feeds) =>
      val target = url(port, path)
      feeds.items(target).flip.map(target -> _)

  def spec = suite("Feeds.http")(
    test("fetches and parses a feed"):
      withFeeds(Feeds.Config(), HostGuard.allowAll): (port, feeds) =>
        feeds.items(url(port, "feed.xml")).map(items => assertTrue(items.size == 2))
    ,
    test("subscriptions to the same feed share one fetch while it's cached"):
      for
        fetches <- Ref.make(0)
        counted  = Routes(Method.GET / "counted.xml" -> handler(
                     fetches.update(_ + 1).as(Response.text(Fixtures.rss)).delay(200.millis)))
        sizes   <- withFeeds(Feeds.Config(), HostGuard.allowAll, counted): (port, feeds) =>
                     ZIO.foreachPar(1 to 10)(_ => feeds.items(url(port, "counted.xml"))).map(_.map(_.size)) <*>
                       feeds.items(url(port, "counted.xml")).map(_.size)
        count   <- fetches.get
      yield assertTrue(sizes._1.forall(_ == 2), sizes._2 == 2, count == 1)
    ,
    test("a caller that gives up doesn't cancel a fetch another caller is waiting for"):
      for
        fetches <- Ref.make(0)
        slow     = Routes(Method.GET / "slow.xml" -> handler(
                     fetches.update(_ + 1).as(Response.text(Fixtures.rss)).delay(500.millis)))
        size    <- withFeeds(Feeds.Config(), HostGuard.allowAll, slow): (port, feeds) =>
                     for
                       quitter <- feeds.items(url(port, "slow.xml")).fork
                       waiter  <- feeds.items(url(port, "slow.xml")).fork.delay(50.millis)
                       _       <- quitter.interrupt.delay(100.millis)
                       items   <- waiter.join
                     yield items.size
        count   <- fetches.get
      yield assertTrue(size == 2, count == 1)
    ,
    test("a feed that keeps failing is fetched less often, and a success starts over"):
      for
        fetches <- Ref.make(0)
        healthy <- Ref.make(false)
        flaky    = Routes(Method.GET / "flaky.xml" -> handler(fetches.update(_ + 1) *> healthy.get.map: ok =>
                     if ok then Response.text(Fixtures.rss) else Response.status(Status.ServiceUnavailable)))
        config   = Feeds.Config(ttl = 1.milli, backoff = 300.millis, maxBackoff = 1.second)
        result  <- withFeeds(config, HostGuard.allowAll, flaky): (port, feeds) =>
                     val get = feeds.items(url(port, "flaky.xml")).either
                     for
                       first   <- get                    // fails: backs off 300 ms
                       backing <- get                    // within the backoff: no fetch
                       _       <- ZIO.sleep(400.millis)
                       second  <- get                    // fetched, fails again: backs off 600 ms
                       _       <- ZIO.sleep(400.millis)
                       still   <- get                    // still backing off
                       count1  <- fetches.get
                       _       <- healthy.set(true)
                       _       <- ZIO.sleep(300.millis)
                       ok      <- get                    // fetched, and succeeds
                       again   <- get.delay(10.millis)   // no backoff after a success
                       count2  <- fetches.get
                     yield (first.isLeft && backing.isLeft && second.isLeft && still.isLeft, count1, ok.isRight && again.isRight, count2)
      yield assertTrue(result._1, result._2 == 2, result._3, result._4 == 4)
    ,
    test("the backoff doubles up to its maximum"):
      val config = Feeds.Config(backoff = 30.seconds, maxBackoff = 15.minutes)
      assertTrue(
        config.backoffAfter(1).toMillis == 30_000L,
        config.backoffAfter(2).toMillis == 60_000L,
        config.backoffAfter(5).toMillis == 480_000L,
        config.backoffAfter(6).toMillis == 900_000L,
        config.backoffAfter(100).toMillis == 900_000L,
      )
    ,
    test("stats count each feed's requests, fetches and failures, and show a backoff"):
      for
        healthy <- Ref.make(true)
        toggled  = Routes(Method.GET / "toggled.xml" -> handler(healthy.get.map: ok =>
                     if ok then Response.text(Fixtures.rss) else Response.status(Status.ServiceUnavailable)))
        config   = Feeds.Config(ttl = 1.milli, backoff = 1.minute)
        stats   <- withFeeds(config, HostGuard.allowAll, toggled): (port, feeds) =>
                     val target = url(port, "toggled.xml")
                     for
                       _     <- feeds.items(target)
                       _     <- healthy.set(false).delay(10.millis)
                       _     <- feeds.items(target).either
                       _     <- feeds.items(target).either   // backing off: no fetch
                       stats <- feeds.stats
                     yield stats.get(target)
      yield assertTrue(
        stats.exists(s => s.requests == 3 && s.fetches == 2 && s.failures == 1 && s.consecutiveFailures == 1),
        stats.exists(s => s.backingOffUntil.isDefined && s.items.contains(2) && s.lastError.isDefined),
        stats.exists(s => s.lastSuccessAt.isDefined && s.lastFetchMs.isDefined && s.newestItemAt.isDefined),
      )
    ,
    test("a feed served with a certificate the JDK doesn't trust isn't read"):
      ZIO.scoped:
        for
          ssl    <- ZIO.succeed(SSLConfig.fromJavaxNetSslKeyStoreResource("self-signed.p12", zio.Config.Secret("changeit")))
          tls    <- Server.defaultWith(_.onAnyOpenPort.ssl(ssl)).build
          port   <- Server.install(routes).provideEnvironment(tls)
          feeds  <- (Feeds.client >>> Feeds.http(Feeds.Config(), HostGuard.allowAll)).build.map(_.get)
          target  = FeedUrl.parse(s"https://localhost:$port/feed.xml").toOption.get
          error  <- feeds.items(target).flip
        yield assertTrue(error match
          case FeedError.Unreachable(url, _) => url == target
          case _                             => false)
    ,
    test("follows a redirect"):
      withFeeds(Feeds.Config(), HostGuard.allowAll): (port, feeds) =>
        feeds.items(url(port, "moved")).map(items => assertTrue(items.size == 2))
    ,
    test("gives up on a redirect loop"):
      failure()("loop").map((u, e) => assertTrue(e == FeedError.TooManyRedirects(u)))
    ,
    test("refuses a body over the size limit"):
      failure(Feeds.Config(maxBytes = 1000))("huge").map((u, e) => assertTrue(e == FeedError.TooLarge(u, 1000)))
    ,
    test("reports an HTTP error status"):
      failure()("missing").map((u, e) => assertTrue(e == FeedError.HttpStatus(u, 404)))
    ,
    test("reports a page that isn't a feed"):
      failure()("page").map((_, e) => assertTrue(e.message.contains("isn't an RSS or Atom feed")))
    ,
    test("the public-only guard refuses localhost"):
      failure(guard = HostGuard.publicOnly)("feed.xml").map((_, e) => assertTrue(e.message.contains("non-public address")))
    ,
    test("the public-only guard knows the internal address ranges"):
      def internal(address: String) = HostGuard.isInternal(InetAddress.getByName(address).nn)
      assertTrue(
        internal("127.0.0.1"), internal("10.1.2.3"), internal("172.16.0.1"), internal("192.168.1.1"),
        internal("169.254.169.254"), internal("100.64.0.1"), internal("0.0.0.0"),
        internal("::1"), internal("fd00::1"), internal("fe80::1"),
        !internal("93.184.215.14"), !internal("2606:4700::1111"),
      )
    ,
    test("a feed URL must be http or https with a host and no credentials"):
      assertTrue(
        FeedUrl.parse("https://example.com/feed").isRight,
        FeedUrl.parse("file:///etc/passwd").isLeft,
        FeedUrl.parse("ftp://example.com/feed").isLeft,
        FeedUrl.parse("https://user:pass@example.com/feed").isLeft,
        FeedUrl.parse("not a url").isLeft,
      )
    ,
  ).provide(Server.defaultWithPort(0)) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
