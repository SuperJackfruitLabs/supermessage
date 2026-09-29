# Fleet Live Activity and "since you last opened" widgets

Date: 2026-09-29. Operator-approved direction; this spec fixes the contract
between the hub (agentpod) and the app (supermessage).

## Why

TestFlight 34 on an iPhone 13 mini showed that Home Screen widgets cannot be
live. WidgetKit deferred a reload asked for by the NSE by five minutes, and it
budgets reloads (about 40 to 70 a day). The app's Agent Live Activity starts only
while the room is open in the foreground, and freezes once iOS suspends the app,
because it is local (`pushType: nil`).

The operator split the surfaces:

- **Lock Screen, as a Live Activity:** what needs me and what is happening now.
  Pushed by the hub, so it is live with the app closed. It is the one fleet card
  (no Dynamic Island on the mini, so the compact and minimal presentations are
  secondary).
- **Home Screen widgets:** what happened while I was away, since I last opened
  the app. They do not need to be live.

## Decisions (operator, 2026-09-29)

1. One Live Activity for the whole fleet, not one per turn or per decision.
2. It shows while any agent is **active**, using the roster's rule: a turn is
   running, a decision is pending, or the agent spoke within
   `ACTIVE_WITHIN_MS` (15 minutes). It ends when every agent has been quiet
   past that.
3. **Detailed content:** agent names, the current step's title, and the
   decision's question and options. This text reaches Apple in plaintext in
   the push. The operator accepted that trade, and it is a change to the
   gateway's "nothing a person wrote passes through Apple" stance.
   `AGENTS.md` / the gateway's doc comments must say so. The design must be
   deliberate and follow the design language.
4. The widget recap is **one row per agent that did something since the app
   was last opened**. Failed comes first, then finished, then said. Quiet
   agents are left out.
5. The hub drives the Live Activity (approach 1). Silent pushes waking the app
   were rejected (throttled, and dead after a force-quit). The NSE cannot touch
   ActivityKit.

## Part A — hub (agentpod)

### A1. Token registration endpoint

`POST /_supermessage/v1/live-activity/tokens` on the hub, the same origin as the
push gateway (`SMPushGatewayURL`).

- **Auth:** `Authorization: Bearer <the user's Matrix access token>`. The hub
  verifies it with the homeserver's `/_matrix/client/v3/account/whoami` and
  caches the result briefly. The user id it returns is the owner.
- **Body:**

```json
{
  "kind": "start" | "update",
  "token": "<hex APNs token>",
  "environment": "production" | "sandbox",
  "device_id": "<Matrix device id>",
  "activity_id": "<ActivityKit id>"
}
```

  `activity_id` is required when `kind` is `update`.
- **Delete:** `DELETE` on the same path, with `{kind, device_id, activity_id?}`.
  The app calls it when an activity ends locally and on sign-out.
- **Storage:** persisted in a DB table (a drizzle migration), keyed by
  `(user_id, device_id, kind)` for start tokens and by
  `(user_id, device_id, activity_id)` for update tokens. When APNs rejects a
  token (410 or `BadDeviceToken`), the hub deletes it.

### A2. Fleet state, per reader

The reader is the station owner (`readerForRoom`), as for the to-device live
events. The hub keeps, per reader, a `FleetState` built from what it already
has in `outbound.ts`, `permissions.ts` and `gates.ts`:

- **Per agent room:** name, state, step title, completed and total tools, when
  the turn started, last activity time, and last outcome. The state is one of
  `working`, `needs_you`, `active`, `done` or `failed`.
- **The top pending decision:** permission or gate, with its room, event id,
  agent, question and up to 2 inline options.

Rules:
- Hook points: turn start / `streamTool` / `streamLive` begin (working), permission
  asked/answered, gate pending/outcome, `recordTurn` (done/failed from counts).
- **Coalescing:** at most one update per reader every 3 s. The latest state
  wins. A decision arriving or clearing, or a turn finishing, flushes at once.
- **Priority:** routine step changes go at priority 5. A decision arriving and a
  turn finishing go at priority 10.
- **Quiet:** once every agent has been past `ACTIVE_WITHIN_MS` with nothing
  pending, the hub sends `end`, with `dismissal-date` two minutes on for a
  finished turn and now otherwise.
- **Restarts:** pending permissions are in memory, as today, so a restart loses
  them. Live state is rebuilt from new events; it is not persisted in this PR.

### A3. APNs Live Activity pushes

Extend `services/push/apns.ts` to carry a push type:

- `apns-push-type: liveactivity`
- `apns-topic: <APNS_TOPIC>.push-type.liveactivity`

Payload shapes, which must fit in 4 KB:

```json
// start (push-to-start, iOS 17.2+), to the reader's start tokens
{"aps":{"timestamp":<unix s>,"event":"start",
  "attributes-type":"FleetActivityAttributes",
  "attributes":{"readerId":"@owner:hs"},
  "content-state":<ContentState>,
  "alert":{"title":"<first active agent>","body":"<its step or question>"}}}

// update, to the update tokens of the reader's live activities
{"aps":{"timestamp":<unix s>,"event":"update","content-state":<ContentState>,
  "stale-date":<unix s + 15 min>, "alert"?: {...}}}   // alert only for needs_you

// end
{"aps":{"timestamp":<unix s>,"event":"end","content-state":<ContentState>,
  "dismissal-date":<unix s>}}
```

- **Start rule:** send `start` only when the reader has no registered update
  token for a live activity. If the start token arrives while the fleet is
  already active, start then.
- **Update tokens that come late:** after a start there is a gap until the app
  registers the new activity's update token. The hub holds the latest state
  and sends it as soon as that token arrives.

### A4. ContentState (the contract; JSON keys are camelCase, shared with Swift)

```json
{
  "agents": [                // at most 3, needs_you first, then working, then most recent
    {"roomId":"!r:hs","name":"Lyra","state":"working|needs_you|active|done|failed",
     "step":"Running the tests",          // ≤ 60 chars, optional
     "completed":3,"total":7,             // tool counts, optional
     "since":1790670000}                  // unix s: turn start, or last activity
  ],
  "more": 2,                 // active agents not listed
  "decision": {              // optional: the oldest pending one
    "roomId":"!r:hs","eventId":"$e","agent":"Ray","kind":"permission|gate",
    "question":"Run git push origin main?",   // ≤ 120 chars
    "options":[{"id":"Allow once","label":"Allow once","declines":false},
               {"id":"Reject","label":"Reject","declines":true}]   // ≤ 2, inline only
  },
  "needsYou": 1,             // pending decisions in all
  "working": 2,
  "updatedAt": 1790670123
}
```

Bounds are applied by the hub. The app decodes leniently: it ignores unknown
keys, and a missing optional key means none.

### A5. Turn outcome on the answer push (for the widget recap)

`recordTurn` counts come before the answer. The hub notes the answer's event id
in `hub-events.ts` with kind `answer` and `{total, failed}`. For that event, the
gateway adds this top-level key to the alert push payload:

```
"turn": {"total": 7, "failed": 1}
```

It holds only counts, never text. The contract schema in
`packages/contract/src/push.ts` gains this optional field.

### A6. Tests

- Unit tests:
  - FleetState transitions;
  - coalescing (3 s, flush on decision or finish);
  - quiet → end;
  - bounds;
  - the start-then-late-update-token race;
  - token endpoint auth (a whoami failure gives 401);
  - APNs headers and topic per push type;
  - the `turn` field on answer pushes.
- A contract test that a sample ContentState round-trips through the zod schema
  and matches the fixture the app decodes (`fleet-content-state.json`, copied
  into both repos).

## Part B — app (supermessage)

### B1. FleetActivityAttributes

`FleetActivityAttributes` in `SupermessageKit/Notifications/`, compiled into
both the app and the widget extension.
- Static field: `readerId`.
- `ContentState`: the A4 contract, with a Codable mirror and lenient decode.

It replaces `AgentActivityAttributes` and the local `LiveActivityController`
turn activity, which cannot stay live.

### B2. Tokens

On sign-in, and on each launch when Live Activities are enabled, the app sends
`Activity<FleetActivityAttributes>.pushToStartTokenUpdates` to A1 as `start`.
- For each activity in `Activity.activityUpdates` and each already running, it
  sends `pushTokenUpdates` as `update`.
- When an activity ends, or on sign-out, it sends a DELETE.
- The access token is the session's. The endpoint is the push gateway's origin.
- Failures are retried with backoff and never block the UI.

### B3. The Lock Screen card, designed deliberately

The layout is decided in previews first, following the design language
(`WidgetTheme` and the tokens) and AGENTS.md. Amber (`signal`) is only for an
owed decision.

- **Header:** "N working · M needs you" and the freshness ("updated 12s ago",
  from `updatedAt`, via `Text(date, style: .relative)`).
- **Decision:** when present, it sits on top as the one amber element. It shows
  the agent, the question (2 lines at most), and the inline buttons as
  `Button(intent: AnswerDecisionIntent)`, reusing `WidgetAnswering`.
- **Agents:** up to 3 rows. Each row has a state dot, the name, and the step or
  last line. For a working agent it adds `completed/total` and elapsed time
  (`Text(timerInterval:)`), which ticks without pushes.
- **Finished or failed:** a row says "Done · 7 steps" or "Failed at step 4 of 7".
- **Stale:** when `stale-date` passes, the card says so ("Waiting for updates")
  rather than showing a frozen "working".
- **Fitting:** must fit the Lock Screen presentation height at `.xLarge` text,
  with rows dropping through `ViewThatFits` as in the widgets. Previews: mini
  width, light and dark, one to three agents, with and without a decision,
  stale, and all done.
- **Dynamic Island** (compact, minimal and expanded): present but simple — the
  count and the top agent. It is not the priority.

### B4. Widget recap (core owns the rules)

- **`core::widget`:**
  - The snapshot gains `openedAtMs`, and per agent, since then:
    - `outcome` (`failed`, `finished` or `said`);
    - `outcomeLine`: "Failed at step 4 of 7", "Finished · 7 steps", or what it
      said;
    - `unread`, a count of pushes.
  - `apply_opened(stored, now)` sets `openedAtMs` and clears the per-agent
    "since" data. The app calls it on each foreground.
  - `apply_notification` reads `turn` (A5) from the push, carried in
    `NotificationDto` by the NSE from `userInfo`, to set finished or failed.
- **Recap order:** agents with activity since `openedAtMs`, failed first, then
  finished, then said, then by recency. Agents with none are omitted. When
  nothing has happened: "Nothing new since 10:42".
- **Needs you:** the Needs you widget stays as it is, with decisions and
  buttons.
- **Reload rule:** a decision arriving or leaving reloads at once. Any other
  change reloads when at least 15 minutes have passed since the last reload.
  A skipped reload is picked up by the timeline, which always asks again after
  15 minutes (`.after(15 min)`), since it cannot know about writes made after
  it ran. This replaces #107's reload-on-every-change and keeps within
  WidgetKit's daily budget.
- **Tests:** core tests for each rule, mutation-checked as in #106.

### B5. Out of scope

- The watchOS app, which is next.
- Persisting fleet state across hub restarts.
- Android.

## Verification

- CI green in both repos.
- On a device:
  - with the app force-quit, starting an agent task from another device shows
    the card within seconds;
  - steps advance;
  - a permission appears with buttons, and tapping Allow works;
  - the card ends about two minutes after the last turn finishes;
  - the widgets show the recap since the app was last opened.
