rss2mcp
-------

An MCP server that turns RSS and Atom feeds into [MCP Events](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/seps/3415-events-extension.md) (`io.modelcontextprotocol/events`, SEP-3415). An agent subscribes to a feed, optionally with keywords, and gets an event for each new item. Built on [zio-http-mcp](https://github.com/jamesward/zio-http-mcp). The MCP endpoint is the server's root, `/`.

## Event type: `feed.item.published`

Subscription arguments (`inputSchema`):

| Field | Type | |
|---|---|---|
| `feedUrl` | string | The feed's `http` or `https` URL |
| `keywords` | string[], optional | Deliver only items whose title, summary, content or categories contain one of these, ignoring case |

Each event's `data` (`payloadSchema`) holds the item's `feedUrl`, `itemId`, `title`, `link`, `published`, `author`, `categories` and a plain-text `summary` shortened to 2,000 characters, so a delivery stays far below the 256 KiB limit. The event id is the item's id: its RSS `guid` or Atom `id`, otherwise its link.

Delivery is by poll (`events/poll`), push (`events/stream`) and webhook (`events/subscribe`). Webhook subscriptions are anonymous and last at most 15 minutes between refreshes. ChatGPT gets the draft dialect it implements (`capabilities.events`, the events methods on a 2025 session, `-32015` for a callback error, the event id as `webhook-id`), chosen by its `openai-mcp` client name; zio-http-mcp's README has the details. The server reads a subscribed feed once a minute (`Main.PollInterval`), and subscriptions to the same feed share one fetch, so an event is at most about a minute behind the feed.

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

Once subscribed (a request with a cursor), every failure to read the feed is `-32603`: a push subscription reports it on the stream, and a webhook subscription logs it, and both keep checking. A webhook subscription whose callback endpoint doesn't echo the verification challenge is refused with `-32027` (`-32015` for ChatGPT).

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
