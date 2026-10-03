# Messaging flow

Messages are stored synchronously in MySQL. The same transaction creates an
outbox event, so a committed message cannot be lost between MySQL and Kafka.
The outbox relay publishes at least once; consumers use `processed_events` for
idempotency.

## HTTP API

All routes require the existing bearer token.

- `GET /v1/api/messages/{conversionId}?cursor={messageId}&limit=20`
  returns messages newest first. The next cursor is the last returned message
  ID.
- `POST /v1/api/messages/{conversionId}` sends a message. Example body:

  ```json
  {
    "clientMessageId": "018f6f52-2fd3-7c8a-bf31-6240dcddde91",
    "content": "Hello",
    "messageType": "TEXT",
    "parentMessageId": null
  }
  ```

  `clientMessageId` is the sender's idempotency key. Replaying the same key and
  body returns the existing message; reusing it for different content returns
  `409 Conflict`.
- `PATCH /v1/api/messages/{conversionId}/delivered` marks this user's incoming
  receipts delivered through `upToMessageId`.
- `PATCH /v1/api/messages/{conversionId}/read` marks them read through
  `upToMessageId`. Omitting the body advances through the latest message.
- `GET /v1/api/conversions?archived=false&limit=20` returns recent conversations
  with `unreadCount`, `lastMessageId`, and `lastActivityAt`.
- `GET /v1/api/notifications` and
  `PATCH /v1/api/notifications/{notificationId}/read` expose the durable in-app
  notification inbox.

`SENT -> DELIVERED -> READ` is monotonic. `READ` also implies delivered.
"Unseen" means that the authenticated user's receipt is not `READ`; it is not a
separate reversible message state. A group message has one receipt per active
recipient, snapshotted at send time. The compatibility status on `messages` is
`DELIVERED` or `READ` only when every snapshotted recipient has reached that
state.

## Kafka

- `conversation-events.v1`, keyed by conversion ID
- `notification-events.v1`, keyed by recipient user ID
- matching `.DLT` topics

Events use a versioned envelope and carry a unique event ID, correlation ID,
aggregate identity, occurrence time, and typed payload. Conversation ordering
is maintained by the conversion key. Notification fan-out uses the recipient
snapshot from `MESSAGE_CREATED`, not current group membership.

Producer acknowledgements or notification-provider acceptance do **not** mark
a chat message delivered. Only an authenticated recipient acknowledgement does.

## Deployment notes

Migration V10 converts the legacy ordinal conversion type and backfills
participants/receipts. Pause conversation and message writes while applying it.
Historical group receipts are necessarily approximate; new receipts are exact.

For production, set database, Mongo, Redis, Kafka, JWT, topic replication, and
outbox instance environment variables. Provision Kafka with replication factor
3 and minimum ISR 2, disable application topic creation, and use TLS/SASL. The
single-broker plaintext Docker Compose configuration is for local development.
Monitor consumer lag, DLT depth, terminal `FAILED` outbox rows, and oldest
pending outbox age.
