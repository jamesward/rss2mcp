rss2mcp
-------

An MCP server that turns RSS and Atom feeds into [MCP Events](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/seps/3415-events-extension.md) (`io.modelcontextprotocol/events`, SEP-3415). An agent subscribes to a feed, optionally with keywords, and gets an event for each new item. Built on [zio-http-mcp](https://github.com/jamesward/zio-http-mcp). The MCP endpoint is the server's root, `/`.

## Event type: `feed.item.published`

Subscription arguments (`inputSchema`):

| Field | Type | |
|---|---|---|
| `feedUrl` | string | The feed's `http` or `https` URL |
| `keywords` | string[], optional | Deliver only items whose title, summary, content or categories contain one of these, ignoring case |

Each event's `data` (`payloadSchema`) holds the item's `feedUrl`, `itemId`, `title`, `link`, `published`, `author`, `categories` and a plain-text `summary` shortened to 2,000 characters, so a delivery stays far below the 256 KiB limit. The item's id (`itemId`) is its RSS `guid` or Atom `id`, otherwise its link. The event id is derived from it, `item_` and 32 hex characters of its SHA-256, so it's stable and can be the `webhook-id` header, which ChatGPT wants equal to the event id.

Delivery is by poll (`events/poll`), push (`events/stream`) and webhook (`events/subscribe`). Webhook subscriptions are anonymous and last at most 15 minutes between refreshes. The server runs zio-http-mcp's `KnownClients` compatibility: ChatGPT gets the draft dialect it implements (`capabilities.events`, the events methods on a 2025 session, `-32015` for a callback error, webhook delivery alone in `events/list`), chosen by its `openai-mcp` client name, and clients that don't declare the extension get the event id as `webhook-id`; zio-http-mcp's README has the details. In ChatGPT, set up a watch in Dot: see [Using it from ChatGPT](#using-it-from-chatgpt). The server reads a subscribed feed once a minute (`Main.PollInterval`), and subscriptions to the same feed share one fetch, so an event is at most about a minute behind the feed.

The cursor is the publish time, in epoch milliseconds, of the newest item a subscription has passed, matched or not. A client can persist it and resume from it after a restart. That has consequences:

- An item with neither a published nor an updated date is left out, because it can't be placed after a cursor. A feed whose items all lack one can't be followed, so subscribing to it is refused (see below).
- An item that shows up in the feed with a date older than the cursor is never delivered.
- `maxAgeMs` drops older items and sets `truncated`. A cursor this server didn't write restarts the subscription from now, also with `truncated`.

## Subscribing to something that isn't a feed

The first check of a new subscription reads the feed, so a bad `feedUrl` is answered at once and nothing is subscribed:

| What's wrong | Error |
|---|---|
| Not an `http` or `https` URL, or it carries credentials | `-32602` |
| The host resolves to a loopback, private, link-local or other non-public address | `-32024` |
| An HTTP 4xx, not RSS or Atom, over 5 MiB, a redirect loop, or items without dates | `-32602`, with the reason |
| An HTML page | `-32602`, naming the feeds the page links to (`<link rel="alternate">`), if any |
| An unreachable host, a timeout or an HTTP 5xx, which may pass | `-32603` |

Once subscribed (a request with a cursor), a failure that may pass (an unreachable host, a timeout, an HTTP 5xx or 429) isn't an error: the check delivers nothing, keeps the cursor and is logged, and the next check that reads the feed delivers what was missed. So a client that resubscribes with its cursor, after the server restarted, is subscribed even while the feed is down. Any other failure is `-32603`: a push subscription reports it on the stream, and a webhook subscription logs it, and both keep checking.

## Fetching

Each feed is fetched at most once a minute, whatever its number of subscriptions, and only while it has one: a subscription that ends, or isn't refreshed, stops its checks. A fetch gives up after 20 seconds (16 to connect), and keeps its connection open for 90 seconds, so a feed polled every minute reuses it rather than connecting again. A feed that fails isn't fetched again for 30 seconds, doubled for each failure in a row up to 15 minutes, and a fetch that succeeds starts over; meanwhile its checks and new subscriptions get the last error. Items published while a feed was failing are delivered by the first fetch that succeeds, as long as the feed still lists them. A webhook subscription whose callback endpoint doesn't echo the verification challenge is refused with `-32027` (`-32015` for ChatGPT).

## Using it from ChatGPT

Add rss2mcp in ChatGPT's developer mode as a custom MCP server at `https://<host>/` with No Authentication, then ask for a watch, for example:

> Use rss2mcp to watch https://hnrss.org/newest. Each time items arrive, post a message here with each item's title and link.

**Ask in Dot.** Seen on 23 Nov 2026, on Plus and on Pro: a watch set up in Dot posts a message for each delivery, about a minute after the item is published. A watch set up from a Work chat doesn't:

- ChatGPT subscribes, the callback passes verification, and the Work chat shows a task (named, for example, "Monitoring RSS Feed Items and Posting Links").
- Each event is delivered and accepted (`200`), and the task's agent runs on it: it calls `get_item` 10 to 20 seconds later.
- No message appears in the chat, in the task, after a reload, or in the mobile app. Asked in that chat, the agent answers that it received the event and posted it there.
- The same happened with every server tried: rss2mcp, a minimal server written to OpenAI's [MCP Events guide](https://developers.openai.com/plugins/build/mcp-events), and Hookdeck's [mcp-events-outpost-demo](https://github.com/hookdeck/mcp-events-outpost-demo) deployed unchanged, which its README records posting in a Work chat on 13 Nov. So it's on ChatGPT's side, not the server's.
- Hookdeck showed [mcp-events-bridge](https://github.com/hookdeck/mcp-events-bridge) posting in a Work chat in ChatGPT's Android app the same day, under a task label ("Inbound email alerts >"), so it may depend on the account or on a rollout.

In the server's logs, Dot subscribes with the User-Agent `openai-mcp/1.0.0` and a Work chat's task with `openai-mcp/1.0.0 (Codex)`. Deleting a Work chat's task in Tasks unsubscribes it; until then it keeps receiving events.

## Tool: `get_item`

`get_item` takes `feedUrl` and `itemId` from an event and answers the item's full text (summary and content, each up to 100,000 characters) as structured content. It reads the feed again, so it fails once the feed no longer lists the item.

## Untrusted input

- Feed text comes from whoever publishes the feed. It reaches the agent as data, and the event and tool descriptions say to treat it as data, but that doesn't stop a prompt injection. Agents should act on it with that in mind.
- HTML in feed text is reduced to plain text (jsoup). A `DOCTYPE` is allowed, as RSS 0.91 feeds declare one, but Rome turns off external entities and external DTDs and expands no entity, so an XML external entity reads nothing.
- The server only fetches `http` and `https` URLs without credentials. It refuses hosts that resolve to loopback, private, link-local (including cloud metadata endpoints), CGNAT or multicast addresses, and checks again on every redirect (at most 5). A DNS answer that changes between the check and the connection isn't caught.
- A feed body is limited to 5 MiB and a fetch to 30 seconds. A feed's TLS certificate and host name are checked against the JDK's trust store (zio-http's default client accepts any certificate).

## Not implemented yet

- **Durable webhook subscriptions.** They are kept in memory, so a restart loses them until each client's next refresh (at most 15 minutes) resubscribes it from its cursor. Run one instance: a second would deliver a refreshed subscription twice.
- **Items without dates.** A feed whose items have no dates (RSS 0.91, and some RSS 2.0) can't be followed, because the cursor is a publish time.
- **Claude Code channels** (`claude/channel`, `notifications/claude/channel`).
- **WebSub**, which would replace polling for feeds that support it.
- **Authorization.** Anyone who can reach the server can subscribe to any public feed, by webhook too; anyone who knows a webhook subscription's callback URL, feed and keywords can unsubscribe it.

## Running

```bash
./sbta run                 # serves http://localhost:8080/ (set PORT to change it)
./sbta "~runReload"        # restarts on source changes
```

Logs are JSON, one object per line. `LOG_LEVEL=DEBUG` adds every feed check and each HTTP hop of a feed fetch; [AGENTS.md](AGENTS.md) lists what is logged.

See [AGENTS.md](AGENTS.md) for the build, tests and validation.
