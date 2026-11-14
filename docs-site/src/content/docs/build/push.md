---
title: The push contract
description: The pusher SuperMessage registers, what a push gateway sends it on iPhone and Android, and the push rules it installs so quiet events stay quiet.
---

A Matrix client does not talk to Apple or Google. It asks the homeserver to keep a **pusher**
("send this account's notifications to that gateway, for this device"), the homeserver posts each
notification to the gateway, and the gateway forwards it to the phone. This page is the
SuperMessage half of that arrangement, for anyone running or writing the gateway.

The short version: a push names a room and an event, and the app fetches and decrypts the rest
on the device. A gateway never sees what anybody wrote.

## The pusher

SuperMessage registers one HTTP pusher per signed-in device, once it is signed in and has a
gateway to name:

| Field | iPhone | Android |
|---|---|---|
| `kind` | `http` | `http` |
| `data.format` | `event_id_only` | `event_id_only` |
| `data.url` | The build's `SMPushGatewayURL` | The UnifiedPush server's `/_matrix/push/v1/notify` |
| `app_id` | `dev.supermessage.ios`, or `dev.supermessage.ios.dev` for an APNs sandbox token | `dev.supermessage` |
| `pushkey` | The APNs device token, lowercase hex | The UnifiedPush endpoint |
| `app_display_name` | `supermessage` | `supermessage` |
| `device_display_name`, `lang` | The device's name and first preferred language | The same |

`event_id_only` is set in the shared core for every platform, so no host can make a push carry
message content. There is no `default_payload`.

A new token or endpoint replaces the device's previous pusher rather than adding a second one,
and signing out removes it.

## What the gateway must send

**iPhone.** The app reads the APNs payload's top level, beside `aps`:

| Key | Required | Used for |
|---|---|---|
| `room_id` | yes | Which room to fetch from |
| `event_id` | yes | Which event to fetch and decrypt |
| `unread_count` | no | The badge |
| `turn` | no | `{ "total": 7, "failed": 1 }` on an agent's answer: the counts the Agents widget shows |

The payload needs `mutable-content` so the Notification Service Extension runs. The extension
fetches the event, decrypts it on the device and writes the notification's text, actions,
category and thread itself, so whatever alert text the gateway sends is replaced. A push without
both a room and an event keeps the gateway's own text.

**Android.** The UnifiedPush distributor delivers the Matrix gateway body unchanged, and the app
reads `notification.room_id`, `notification.event_id` and `notification.counts.unread`. A push
posts only for the account that registered the pusher, and only while that account is signed in.

## Which gateway

**iPhone** uses the gateway named at build time by `SM_PUSH_GATEWAY_URL`. The TestFlight
workflow sets the AgentPod hub's, `https://hub.agentpod.dev/_matrix/push/v1/notify`. A build
generated without it, or with anything other than an absolute `https://` URL, registers no pusher
at all: a pusher pointing nowhere would have the homeserver post into a void and retry.

**Android** uses no fixed gateway. It asks the UnifiedPush endpoint's own server: a
`GET /_matrix/push/v1/notify` there must answer `{"unifiedpush":{"gateway":"matrix"}}`. ntfy.sh and
a self-hosted ntfy both do. If that server is not a Matrix gateway, there is no pusher, and
deliberately no fallback: a third party's gateway would see room ids, event ids and unread counts.

**The desktop apps** register no pusher. Their notifications come from the running app.

## The rules that keep quiet events quiet

Each time it registers a pusher, SuperMessage also makes sure the account has these override
rules, all don't-notify:

| Rule id | Matches |
|---|---|
| `dev.supermessage.quiet.reaction` | `m.reaction` |
| `dev.supermessage.quiet.agentpod_turn` | `dev.agentpod.turn.v1` |
| `dev.supermessage.quiet.agentpod_permission` | `dev.agentpod.permission.v1`, the separate event beside a prose message |
| `dev.supermessage.quiet.superpipeline_gate` | `dev.superpipeline.gate.v1`, the same |
| `dev.supermessage.quiet.agentpod_voice_reply` | A spoken reply, `dev.agentpod.voice_reply` at schema version 1 |
| `dev.supermessage.quiet.edit` | An edit, `m.relates_to.rel_type` of `m.replace` |

Installing them is idempotent, touches no rule outside the `dev.supermessage.quiet.` prefix, and
leaves a rule alone if somebody disabled it. A failure is logged, not fatal.

The homeserver can match these only in an unencrypted room: in an encrypted one every event is
`m.room.encrypted`. Those pushes still arrive, and the extension shows a short quiet line for them,
as [Notifications](/use/notifications/#quiet-events-never-interrupt) describes.

## The one exception

The fleet Live Activity is not an `event_id_only` push. The hub sends it to Apple with agent
names, steps and a pending decision's question in plaintext, and the app sends its Live Activity
tokens to `/_supermessage/v1/live-activity/tokens` on the push gateway's origin. See
[Widgets and the Lock Screen](/use/widgets/#the-fleet-live-activity).

## Next

- [Notifications](/use/notifications/): what the reader sees when a push lands
- [Installing it](/run/install/#background-notifications): turning push on for each platform
