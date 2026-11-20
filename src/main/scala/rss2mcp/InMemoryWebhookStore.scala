package rss2mcp

import com.jamesward.ziohttp.mcp.*
import zio.*

/**
 * Webhook subscriptions in a `Ref`: they are lost when the process ends. That
 * works only on one instance, and only because subscriptions are short-lived:
 * a client resubscribes with its cursor when its subscription would expire,
 * and the feed replays what it missed (see AGENTS.md).
 */
final case class InMemoryWebhookStore(
  subscriptions: Ref[Map[McpWebhookSubscriptionId, McpWebhookSubscription]],
  verifications: Ref[Map[(McpWebhookPrincipalId, McpWebhookUrl), McpWebhookVerification]],
) extends McpWebhookStore:
  val durable: Boolean = false

  def get(id: McpWebhookSubscriptionId): IO[McpWebhookStoreError, Option[McpWebhookSubscription]] =
    subscriptions.get.map(_.get(id))

  def all: IO[McpWebhookStoreError, Chunk[McpWebhookSubscription]] =
    subscriptions.get.map(m => Chunk.fromIterable(m.values))

  def put(subscription: McpWebhookSubscription): IO[McpWebhookStoreError, Unit] =
    subscriptions.update(_.updated(subscription.id, subscription))

  def update(id: McpWebhookSubscriptionId)(f: McpWebhookSubscription => McpWebhookSubscription)
    : IO[McpWebhookStoreError, Option[McpWebhookSubscription]] =
    subscriptions.modify: m =>
      m.get(id).map(f) match
        case Some(updated) => (Some(updated), m.updated(id, updated))
        case None          => (None, m)

  def delete(id: McpWebhookSubscriptionId): IO[McpWebhookStoreError, Unit] =
    subscriptions.update(_ - id)

  def verification(principal: McpWebhookPrincipalId, url: McpWebhookUrl): IO[McpWebhookStoreError, Option[McpWebhookVerification]] =
    verifications.get.map(_.get((principal, url)))

  def putVerification(verification: McpWebhookVerification): IO[McpWebhookStoreError, Unit] =
    verifications.update(_.updated((verification.principal, verification.url), verification))

object InMemoryWebhookStore:
  val layer: ULayer[McpWebhookStore] =
    ZLayer:
      for
        subscriptions <- Ref.make(Map.empty[McpWebhookSubscriptionId, McpWebhookSubscription])
        verifications <- Ref.make(Map.empty[(McpWebhookPrincipalId, McpWebhookUrl), McpWebhookVerification])
      yield InMemoryWebhookStore(subscriptions, verifications)
