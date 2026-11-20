# rss2mcp

An MCP server, built on [zio-http-mcp](https://github.com/jamesward/zio-http-mcp), that serves new RSS and Atom feed items as MCP Events (`feed.item.published`, SEP-3415) plus a `get_item` tool. [README.md](README.md) describes the event type, the cursor and the security limits.

## Skills

- Follow the `zen-of-projects` Skill. This file records only project facts and exceptions.
- For Scala 3 and ZIO code (writing or reviewing it), follow `zen-of-scala`.
- For design, domain modeling, effects and testing strategy, follow `zen-of-james`.

Extract them with `./sbta extractSkillsJars` into the gitignored `.kiro/skills/`, and read the relevant ones before working.

## MCP

The MCP server is `sbta` (this build's `./sbta --mcp`), configured in `.kiro/settings/mcp.json` and `.mcp.json` and approved in `.claude/settings.json`. Its tools are deferred in Claude Code: load them with ToolSearch (search `sbta`). Logs: `project/target/sbta/mcp.log` and `project/target/sbta/boot.log`.

## Build, Test & Dev Workflow

- Use the MCP server `sbta` (this build's `./sbta --mcp`) for all sbt work: build, test and run
  with `compile`, `test` (`tests` for specific classes, `full` for all), `run`, `clean` and
  `publish-local`; inspect the build with `projects`, `test-names`, `main-classes`, `dependencies` and
  `show`. Use `sbt-task` for any other command (e.g. `reload`) and `list-tasks` to find tasks. Don't run
  `sbt` or `./sbta` in a shell while the MCP tools are available; if the server is unavailable, say so and
  fall back to `./sbta <command>`.
- After editing Scala 3 sources, check them with `check` (pass the edited files: a fast type check
  without compiling; `"scope":"module"` after changing an API), then run `compile` and `test` before
  declaring the work done.
- For Scala symbols use `glob-search` (find or list), `inspect` (members and signatures) and
  `symbol-location` (source location) rather than text search or guessing APIs. JavaDoc/ScalaDoc
  lookups are the proxied javadocs.dev tools of the same server.
- Full validation: `./sbta "Test / compile; testFull; stage"`. For the startup smoke test, run
  `PORT=18080 target/out/jvm/scala-3.10.1/rss2mcp/universal/stage/bin/rss2mcp` and POST an
  `initialize` to `http://localhost:18080/`.
- Dev: `./sbta run` serves `http://localhost:8080/` (`PORT` overrides it); `./sbta "~runReload"` restarts on changes.
  The server has no service dependencies, so there is no `AppTest`.
- Tests need no Docker and no network: `FeedsSpec` serves feeds from an in-process zio-http server
  (with `HostGuard.allowAll`, since the production guard refuses localhost), and `Rss2McpSpec` runs the
  MCP server over loopback against an in-memory `Feeds`.
- To work on zio-http-mcp together with this project, check it out at `../zio-http-mcp` and run
  `./sbta -Dlocal ...`, which builds against that checkout instead of the published artifact.

## Production

- Not deployed yet: there is no Heroku app.
- Logs are one JSON object per line on stdout (`zio-logging` console JSON logger with the SLF4J bridge,
  `AppLogging`), with `request_id` from `X-Request-ID` on every entry made during a request. `LOG_LEVEL`
  sets the floor (`INFO` when unset; Java libraries stay at `INFO` or above). At `INFO` the logs show each
  request's MCP headers (`Request received`), each feed fetch, feed checks that find items, `get_item`, and,
  from zio-http-mcp, every events method and webhook POST. `DEBUG` adds every feed check, each HTTP hop of a
  fetch, and webhook watermarks. Request bodies, tokens and webhook secrets are never logged; a session id is
  logged as its first 8 characters.
- Runtime metrics (`log-runtime-metrics`) are not on, because there is no app yet.

## Maintenance routine

`.factory/MAINTENANCE.md`, weekly, following the `zen-of-projects` Skill.

## Exceptions

None.
