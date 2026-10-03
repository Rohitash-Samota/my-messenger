# Messaging flow

Messages are stored synchronously in MySQL. The same transaction creates an
outbox event, so a committed message cannot be lost between MySQL and Kafka.
The outbox relay publishes at least once; consumers use `processed_events` for
idempotency.

## HTTP API

All routes require the existing bearer token.

- `GET /api/auth/me` returns the authenticated user's `id`, `email`, fallback
  `name`, `mobileNumber`, and `profilePhoto`.
- `GET /v1/api/users?q={email}&limit=20` searches active users for New Chat.
  Results contain `id`, `email`, fallback `name`, and `profilePhoto`; the
  authenticated user is excluded.
- `POST /v1/api/conversions` creates or reuses a direct conversation. Send
  `{"recipientEmail":"person@example.com"}`. A new conversation returns `201`;
  an existing one returns `200`. Conversation responses include a `peer`
  object with enough identity for display.
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

### Local message media

Only message attachments are stored on local disk. Calls remain peer-to-peer
and are never recorded by this service.

- `POST /v1/api/media` accepts `multipart/form-data` with `file`,
  `conversationId`, and `messageType` (`IMAGE`, `VIDEO`, or `AUDIO`). It returns
  an authenticated relative `url`; use that URL as `content` in the existing
  message-send API.
- `GET /v1/api/media/{mediaId}` returns the binary only when the bearer-token
  user is still an active conversation participant.

Files receive UUID-based server filenames. The upload service checks the
declared media type, file signature, and configured size limit. Metadata and
binaries live under `MEDIA_STORAGE_PATH` (default `./local-media`); mount that
directory on persistent local storage where needed. Defaults are 10 MiB for
images, 100 MiB for videos, and 25 MiB for audio, configurable through
`MEDIA_MAX_IMAGE_BYTES`, `MEDIA_MAX_VIDEO_BYTES`, and `MEDIA_MAX_AUDIO_BYTES`.
An `IMAGE`, `VIDEO`, or `AUDIO` message is accepted only when `content` is the
canonical local URL returned by this upload endpoint and its stored
conversation/type metadata matches the message. Arbitrary remote URLs are
rejected. `DOCUMENT` messages are currently rejected because document storage
is outside this local-media scope.

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

## WebRTC call signaling

One-to-one voice and video calls use STOMP over the native WebSocket endpoint
`/ws`. Media is never proxied or persisted by this service; browsers exchange
audio/video directly with WebRTC.

1. Connect to `ws://localhost:8080/ws` (use `wss://` in production).
2. Send `Authorization: Bearer <access-token>` in the STOMP `CONNECT` headers.
3. Subscribe to `/user/queue/calls`, `/user/queue/events`, and
   `/user/queue/errors` as needed.
4. Publish call events to `/app/calls.signal`.

Signal request example:

```json
{
  "callId": "5d4c7d20-a2e3-4ea1-b873-d4b201ccf55f",
  "conversationId": 42,
  "type": "INVITE",
  "mediaType": "VIDEO",
  "sdp": null,
  "candidate": null
}
```

Supported signal types are `INVITE`, `RINGING`, `ACCEPT`, `REJECT`, `BUSY`,
`OFFER`, `ANSWER`, `ICE_CANDIDATE`, and `HANGUP`. `OFFER`/`ANSWER` carry only
`sdp`; `ICE_CANDIDATE` carries only `candidate`. The server derives sender and
recipient identities, verifies the direct-conversation membership, binds the
first accepting recipient session, enforces legal lifecycle transitions, and
routes subsequent SDP/ICE only between the two bound browser sessions.

The built-in simple broker and active-call registry are single-instance. For a
multi-replica deployment, move broker routing and call state to shared
infrastructure. Production deployments also need HTTPS/WSS and short-lived TURN
credentials; a public STUN server alone is not sufficient on restrictive NATs.

## Realtime chat events

REST remains the authoritative write path. After the surrounding database
transaction commits, the service sends the same versioned event envelope to
each participant at `/user/queue/events`. Event types are:

- `CONVERSATION_CREATED`
- `MESSAGE_CREATED`
- `MESSAGE_STATE_CHANGED`

The sender receives events too, which lets multiple tabs/devices reconcile
with the committed IDs and timestamps. Clients should use `eventId` for
deduplication and refetch a conversation when they encounter a newer schema
version. STOMP `SEND` remains restricted to `/app/calls.signal`; chat writes
must use the authenticated REST endpoints.

## Deployment notes

Migration V10 converts the legacy ordinal conversion type and backfills
participants/receipts. Pause conversation and message writes while applying it.
Historical group receipts are necessarily approximate; new receipts are exact.

For production, set database, Mongo, Redis, Kafka, JWT, CORS/WebSocket origins,
media storage, topic replication, and
outbox instance environment variables. Provision Kafka with replication factor
3 and minimum ISR 2, disable application topic creation, and use TLS/SASL. The
single-broker plaintext Docker Compose configuration is for local development.
Monitor consumer lag, DLT depth, terminal `FAILED` outbox rows, and oldest
pending outbox age.
