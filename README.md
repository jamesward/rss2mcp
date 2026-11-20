rss2mcp
-------

An MCP server that turns RSS and Atom feeds into [MCP Events](https://github.com/modelcontextprotocol/modelcontextprotocol/pull/3415) (`io.modelcontextprotocol/events`, SEP-3415, a draft). An agent subscribes to a feed, optionally with keywords, and gets an event for each new item. Built on [zio-http-mcp](https://github.com/jamesward/zio-http-mcp).

## Event type: `feed.item.published`

Subscription arguments (`inputSchema`):

| Field | Type | |
|---|---|---|
| `feedUrl` | string | The feed's `http` or `https` URL |
| `keywords` | string[], optional | Deliver only items whose title, summary, content or categories contain one of these, ignoring case |

Each event's `data` (`payloadSchema`) holds the item's `feedUrl`, `itemId`, `title`, `link`, `published`, `author`, `categories` and a plain-text `summary` shortened to 2,000 characters, so a delivery stays far below the 256 KiB limit. The event id is the item's id: its RSS `guid` or Atom `id`, otherwise its link.

Delivery is by poll (`events/poll`) and push (`events/stream`). The server reads a subscribed feed once a minute (`Main.PollInterval`), and subscriptions to the same feed share one fetch, so an event is at most about a minute behind the feed.

The cursor is the publish time, in epoch milliseconds, of the newest item a subscription has passed, matched or not. A client can persist it and resume from it after a restart. That has consequences:

- An item with neither a published nor an updated date is left out, because it can't be placed after a cursor.
- An item that shows up in the feed with a date older than the cursor is never delivered.
- `maxAgeMs` drops older items and sets `truncated`. A cursor this server didn't write restarts the subscription from now, also with `truncated`.

## Tool: `get_item`

`get_item` takes `feedUrl` and `itemId` from an event and answers the item's full text (summary and content, each up to 100,000 characters) as structured content. It reads the feed again, so it fails once the feed no longer lists the item.

## Untrusted input

- Feed text comes from whoever publishes the feed. It reaches the agent as data, and the event and tool descriptions say to treat it as data, but that doesn't stop a prompt injection. Agents should act on it with that in mind.
- HTML in feed text is reduced to plain text (jsoup). Rome refuses `DOCTYPE` declarations, which rules out XML external entities.
- The server only fetches `http` and `https` URLs without credentials. It refuses hosts that resolve to loopback, private, link-local (including cloud metadata endpoints), CGNAT or multicast addresses, and checks again on every redirect (at most 5). A DNS answer that changes between the check and the connection isn't caught.
- A feed body is limited to 5 MiB and a fetch to 15 seconds.

## Not implemented yet

- **Webhook delivery**, the only mode ChatGPT supports. zio-http-mcp serves poll and push but not webhooks. Webhooks also need persistent subscription storage and signed POSTs to callback URLs.
- **Claude Code channels** (`claude/channel`, `notifications/claude/channel`).
- **WebSub**, which would replace polling for feeds that support it.
- **Authorization.** Anyone who can reach the server can subscribe to any public feed.

## Running

```bash
./sbta run                 # serves http://localhost:8080/ (set PORT to change it)
./sbta "~runReload"        # restarts on source changes
```

See [AGENTS.md](AGENTS.md) for the build, tests and validation.
