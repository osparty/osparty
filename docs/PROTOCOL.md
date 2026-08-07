# OSParty wire protocol

Everything the plugin does against the backend — search, hosting, the live party, Discord
linking, device management — runs over a **single WebSocket**. There is no REST API.

This document describes the wire format as implemented in
[`OSPartySocket`](../src/main/java/net/osparty/api/OSPartySocket.java) (the board channel) and
[`LiveParty`](../src/main/java/net/osparty/party/LiveParty.java) /
[`LivePartyChannel`](../src/main/java/net/osparty/party/LivePartyChannel.java) (the live channel).
For what the two channels are *for* — the listing service vs. the live party room, node
affinity, the trust model — see [ARCHITECTURE.md](ARCHITECTURE.md). The reference server
implementation lives in its own repo,
[github.com/osparty/ospartyapi](https://github.com/osparty/ospartyapi).

## Connecting

The plugin opens one connection for the whole session and keeps it open:

```
GET /api/ws
GET /n/{nodeId}/api/ws
```

The base URL defaults to `https://api.osparty.net` (overridden with `-Dosparty.apiUrl`; see
[DEVELOPING.md](DEVELOPING.md)), which OkHttp upgrades to a WebSocket. The `/n/{nodeId}/…` form
pins the connection to the pod that owns the live party the client is in or joining — see
[`OSPartySocket.currentUrl()`](../src/main/java/net/osparty/api/OSPartySocket.java#L218-L235).
Without a party in progress the gateway round-robins.

Three headers ride the WebSocket upgrade request itself
([`OSPartySocket.connect()`](../src/main/java/net/osparty/api/OSPartySocket.java#L299-L326)):

| Header | Carries | Notes |
|---|---|---|
| `X-OSParty-Auth` | The stored credential for the logged-in character, if this machine has enrolled one | Absent on a fresh install/character; see [Host authentication](#host-authentication) below and the credential system in ARCHITECTURE.md |
| `X-OSParty-Client` | The plugin version string | So the service can see what's actually deployed rather than guess |
| `X-OSParty-Device` | A best-effort device label: the `osparty.deviceLabel` system property if set, otherwise the resolved local hostname | Omitted when neither resolves. Only used as a fresh credential's starting display label; never used to decide anything |

Authentication is entirely out of band from the frames: identity is settled at the handshake,
not by a login frame.

## Framing

Every frame, in both directions, is a **binary WebSocket message**: one tag byte, then UTF-8 JSON.
There is no `type`-tagged plain-JSON envelope at the transport level — `type` is a field *inside*
the JSON payload, used for dispatch once the tag has routed the frame to the right channel.

```
byte 0       tag: Mux.BOARD (1) or Mux.LIVE (2)
bytes 1..N   UTF-8 JSON (the frame)
```

See [`Mux`](../src/main/java/net/osparty/api/Mux.java) and
[`OSPartySocket.sendTagged()`](../src/main/java/net/osparty/api/OSPartySocket.java#L1656-L1672).

Board `snapshot` and `batch` frames — the only frames big enough to be worth it, and the only
ones the server shares between every connected client rather than building per-recipient — are
additionally **gzipped**. There is no separate flag for this: the receiver detects it off the
gzip magic bytes (`0x1f 0x8b`) at the start of the payload, after stripping the tag byte
([`OSPartySocket.onMessage(WebSocket, ByteString)`](../src/main/java/net/osparty/api/OSPartySocket.java#L818-L865)).
The client advertises that it can read compressed frames via `compress: true` on `subscribe`
(see below).

## The BOARD channel (`Mux.BOARD = 1`)

The board is the bulletin-board list of open parties: search reads, hosting writes, Discord
linking and device management all travel here.

### Subscribing

While the Search tab is visible the plugin sends `subscribe` and receives a `snapshot` followed by
incremental `created`/`updated`/`removed`/`batch` frames — the list is change-driven, not polled.
Unsubscribing (`unsubscribe`) stops the feed but leaves the connection up for hosting.

`snapshot` and `batch` both carry a `seq` — a monotonically increasing board revision. The client
remembers the highest one it has applied (`boardSeq`) and offers it back as `since` on the next
`subscribe`, so a reconnect costs only the ads that changed while it was away rather than the
whole board — which matters most during a deploy, when every client reconnects at once. If the
server can no longer answer the difference (the gap is too old) it replies with a fresh
`snapshot` instead of a `batch`.

### Client → server frames

| Frame | Payload | Purpose |
|---|---|---|
| `subscribe` | `activity?`, `compress: true`, `since?` | Start (or restart) the live list feed, optionally scoped to one activity id, advertising gzip support and a resume cursor |
| `unsubscribe` | (none) | Stop the feed; the socket stays up for hosting |
| `host` | `request` (`AdvertisementRequest`), `key` | Advertise a new party; `key` is a client-minted host secret (see below) |
| `update` | `id`, `key`, `patch` | Live keep-alive patch on the hosted ad (occupancy, world, layout, needed roles) — deduped, sent only when the patch actually differs from the last one sent |
| `unhost` | `id`, `key` | Close the ad |
| `resume` | `id`, `key` | Reclaim the ad by id + key after a reconnect |
| `getByCode` | `code` | Look up one ad by invite code |
| `getByHost` | `host` | Look up the ad a player is hosting (used to rejoin after a restart) |
| `createVoiceChannel` | `id`, `key` | Host: provision a Discord voice channel for the party |
| `kickVoiceMember` | `id`, `key`, `accountHash` | Host: disconnect a member from the voice channel |
| `requestVoiceAccess` | `id`, `accountHash` | Member: request per-user access to the voice channel |
| `transferHost` | `id`, `key`, `host`, `hostAccountType`, `newKey` | Host: reassign the ad to a new host, re-keying it to `newKey`. **Requires the current host key** |
| `startDiscordLink` | `accountHash` | Begin an OAuth2 Discord account link |
| `getDiscordLink` | `accountHash` | Look up the account's Discord link status |
| `unlinkDiscord` | `accountHash` | Remove the account's Discord binding |
| `setBadgeVisibility` | `accountHash`, `visible` | Show or hide the caller's own Discord-role badges |
| `report` | `id` | Report an advertisement for moderator review. Deliberately never acknowledged (see `error`/no reply) — the server rate-limits reports silently so a reply can't reveal which ones got through |
| `identify` | `accountHash`, `name` | Register this connection's OSRS identity, so the server can route invites to it. Re-sent on every reconnect |
| `invite` | `id`, `name`, `accountHash`, `target` | Invite an online friend (`target`, a player name) to the ad `id` we're in |
| `listDevices` | (none) | List the credentials/machines entitled to speak for this account |
| `revokeDevice` | `deviceId` | Withdraw one device by the id `devices` reported for it |
| `renameDevice` | `deviceId`, `label` | Cosmetic rename of one device |
| `requestCouplingCode` | `accountHash` | Mint a six-digit code and show it on the account's other signed-in devices. **The only thing that mints one** — a failed sign-in no longer does, or naming an account hash would be enough to put a dialog in front of whoever really owns it |
| `couplingConfirm` | `accountHash`, `code` | Confirm the six-digit coupling code shown on an incumbent device |
| `retryAuth` | `accountHash` | Ask the server to attempt sign-in again. `identify` is answered **once per connection per account**, so this is the only way past that — deliberately a user action, since the answer only changes when they do something (typically starting OSParty on the device that holds the account) |
| `recoveryConfirm` | `accountHash`, `code` | Spend a one-time recovery code to sign this device in. The only route needing neither a second device nor a browser |
| `issueRecoveryCodes` | (none) | Mint a fresh set of codes, retiring any unspent ones. Signed-in sessions only, and only for their own account |
| `recoveryStatus` | (none) | How many codes are left. Never returns the codes themselves |
| `startDiscordRecovery` | `accountHash` | Begin Discord-based recovery; answered with `discordRecoveryUrl` |
| `discordRecoveryPoll` | `ticket` | Has the browser half finished? Polled because the OAuth callback lands on whichever replica the browser reached, which cannot reach this socket |

Frame shapes: [`OSPartySocket`](../src/main/java/net/osparty/api/OSPartySocket.java#L1765-L2036)
(`HostFrame`, `UpdateFrame`, `MutateFrame`, `LookupFrame`, `VoiceFrame`, `TransferFrame`,
`AccountHashFrame`, `BadgeVisibilityFrame`, `KickVoiceFrame`, `ReportFrame`, `VoiceAccessFrame`,
`IdentifyFrame`, `InviteFrame`, `TypedFrame`, `RevokeDeviceFrame`, `RenameDeviceFrame`,
`CouplingConfirmFrame`, `AccountFrame`, `CodeFrame`, `TicketFrame`). Gson omits null fields, so a patch
or request only carries what's set.

### Server → client frames

| Frame | Payload | Meaning |
|---|---|---|
| `snapshot` | `ads[]`, `seq` | The full current list, sent on (re)subscribe when a resume isn't possible |
| `created` | `ad` (`Advertisement`) | A single ad appeared |
| `updated` | `ad` (`Advertisement`) | A single ad changed — the **whole** ad, not a delta (deltas only appear inside `batch`) |
| `removed` | `id` | An ad dropped off the list |
| `batch` | `created[]` (`Advertisement[]`), `updated[]` (`AdvertisementDelta[]`), `removed[]` (`id[]`), `seq` | One tick's worth of changes, applied together; `updated` entries here are partial — only the fields that changed, plus `id` |
| `hosted` | `ad` | Ack of `host`, carrying the server-assigned id |
| `gone` | `id` | The ad's grace window lapsed before a `resume` (or it was purged) |
| `error` | `id?`, `detail` | Rejects a host action, or a pending voice/access/transfer/link request keyed by `id`. `detail == "gone"` on the caller's own hosted ad clears local hosting state, same as a `gone` frame |
| `byCode` | `id` (the echoed invite code), `ad` | Reply to `getByCode` |
| `byHost` | `id` (the echoed host name), `ad` | Reply to `getByHost` |
| `voiceChannel` | `id`, `url` | The provisioned voice channel's invite URL |
| `discordLinkUrl` | `url` | The OAuth authorise URL to open in a browser |
| `discordLink` | `accountHash`, `id` (Discord user id), `username`, `badgesVisible?` | The account's Discord link and badge-visibility state |
| `voiceAccess` | `id` | Reply to `requestVoiceAccess` |
| `transferred` | `id` | Ack of `transferHost` |
| `presence` | `online` | The current count of connected plugin clients |
| `invited` | `ad`, `from` | Push: someone invited us to their party |
| `inviteAck` | `id` (the echoed target name), `delivered` | Whether an outbound `invite` reached the target's client |
| `authIssued` | `token`, `playerId`, `firstDevice`, `codes[]?` | A fresh credential minted for this login; stored once (server keeps only a digest). `playerId` — the account's public, non-reversible id — is carried but currently unread by the client. `codes` rides only the account's **first** credential, is plaintext, and is never obtainable again. **Named `codes`, not `recoveryCodes`:** the plugin reads every frame into one flat class where `recoveryCodes` is already `authFailed`'s boolean, and a list under that name made Gson throw and silently drop the whole frame |
| `authFailed` | `accountHash`, `reason`, `coupling`, `recoveryCodes`, `discord` | This device could not be signed in, and which ways back in are open. Every flag is server-confirmed, so a route offered here will answer. Sent **at most once per connection per account** — see `retryAuth`. Carries no code and no Discord identity: it goes to a connection that has proved nothing. `coupling` means *a device is online somewhere*, **not** that a code is waiting — nothing is minted until `requestCouplingCode` |
| `couplingCode` | `accountHash`, `code` | The code to display, sent to an already-enrolled (incumbent) device — on any replica, not just this one |
| `couplingCodeSent` | `accountHash`, `reached` | Ack of `requestCouplingCode`: how many of the account's devices were shown it. `0` is a real answer, not an error — presence was checked before the request and the last device can go offline in between |
| `couplingResult` | `accountHash`, `success` | Outcome of a coupling attempt |
| `couplingAccepted` | `accountHash` | Notice to an incumbent device that another machine has just joined the account |
| `recoveryCodes` | `codes[]?`, `remaining` | Reply to `issueRecoveryCodes` (with `codes`) or `recoveryStatus` (count only) |
| `recoveryResult` | `success`, `pending`, `detail?` | Outcome of `recoveryConfirm` or `discordRecoveryPoll`. `pending` is what almost every poll sees and is **not** a failure — without it a client cannot tell "not finished" from "refused" |
| `discordRecoveryUrl` | `url`, `ticket` | Where to send the browser, and the secret this connection polls with. The ticket never travels in the URL, so seeing the URL does not let anyone claim the enrolment |
| `devices` | `devices[]` (`id`, `label`, `issuedAt`, `lastSeenAt`) | Reply to `listDevices` |
| `deviceRevoked` | `deviceId`, `success` | Ack of `revokeDevice` |
| `deviceRenamed` | `deviceId`, `success` | Ack of `renameDevice` |

Frame shape: [`OSPartySocket.Frame`](../src/main/java/net/osparty/api/OSPartySocket.java#L1724-L1763)
(a single class; only the fields relevant to a given `type` are populated). Dispatch:
[`OSPartySocket.onMessage(WebSocket, String)`](../src/main/java/net/osparty/api/OSPartySocket.java#L887-L1014).

### JSON shapes

Field names are the wire — they must match the server's model classes exactly, since neither
side annotates and both serialise by field name
([`Advertisement`](../src/main/java/net/osparty/model/Advertisement.java) javadoc). All examples
below were checked field-for-field against the source.

**`AdvertisementRequest`** — the `host` frame's `request`
([`model/AdvertisementRequest.java`](../src/main/java/net/osparty/model/AdvertisementRequest.java)):

```json
{ "activity": "tob", "host": "Zezima", "hostAccountHash": 123456789012345,
  "description": "Learners welcome, ~30 min", "capacity": 3, "world": "420",
  "minKillCount": 500, "minHardModeKillCount": 50,
  "passphrase": "wine-of-zamorak-widow",
  "privateAd": false, "lootRule": "SPLIT", "ironmanOnly": false,
  "hostAccountType": "NORMAL", "hardMode": false, "invocation": 0, "coxScale": "",
  "requiredRoles": ["tobmelee", "tobranged", "tobnfrz"], "hostRole": "tobmelee",
  "learner": false, "teacher": false }
```

**`Advertisement`** — what the board holds and broadcasts
([`model/Advertisement.java`](../src/main/java/net/osparty/model/Advertisement.java)); adds a
server-assigned `id`/`inviteCode`, the live `size`/`layout`/`neededRoles`, and the node hint:

```json
{ "id": "abc123", "activity": "tob", "host": "Zezima", "hostAccountHash": 123456789012345,
  "description": "Learners welcome, ~30 min", "size": 1, "capacity": 3, "world": "420",
  "layout": null, "hardMode": false, "invocation": 0, "coxScale": "",
  "createdAt": 1732000000000,
  "passphrase": "wine-of-zamorak-widow",
  "members": [
    { "name": "Zezima", "accountHash": 123456789012345, "badges": ["developer"], "playerId": "pl_9f2ac3" }
  ],
  "minKillCount": 500, "minHardModeKillCount": 50, "privateAd": false,
  "node": "pod-3", "inviteCode": "Y2Y3D9", "lootRule": "SPLIT", "ironmanOnly": false,
  "hostAccountType": "NORMAL",
  "requiredRoles": ["tobmelee", "tobranged", "tobnfrz"], "hostRole": "tobmelee",
  "neededRoles": ["tobranged", "tobnfrz"], "learner": false, "teacher": false,
  "discordChannelId": null, "discordInviteUrl": null }
```

Note `privateAd`, not `privateParty`, and that `members` is a list of **objects**
(`name`, `accountHash`, `badges?`, `playerId?`), not bare strings. A bare-string member (e.g.
`"Zezima"`) is accepted on read as a legacy fallback — it deserialises to a hash-less member —
but the plugin never writes that shape; see
[`Member.MemberAdapter`](../src/main/java/net/osparty/model/Member.java#L52-L136).
`hostAccountHash` is `0` from a server old enough to predate the field; callers should read it
through `Advertisement.getHostAccountHash()`, which falls back to `members.get(0)` for that case
(wrong after a host transfer, which is exactly why the server now sends the hash directly). The
host key itself never appears in an `Advertisement` — see [Host authentication](#host-authentication).

**`AdvertisementDelta`** — the partial form used inside a `batch`'s `updated[]`
([`model/AdvertisementDelta.java`](../src/main/java/net/osparty/model/AdvertisementDelta.java)):
every field is boxed/nullable, and only the changed fields (plus `id`) are ever populated; absent
fields are left untouched by `applyTo()`.

**`AdvertisementEditRequest`** — the `update` frame's `patch` for a full host edit
([`model/AdvertisementEditRequest.java`](../src/main/java/net/osparty/model/AdvertisementEditRequest.java)),
as opposed to the smaller keep-alive patch:

```json
{ "description": "", "capacity": 4, "world": "420", "minKillCount": 0,
  "minHardModeKillCount": 0, "lootRule": "SPLIT", "privateAd": false, "ironmanOnly": false,
  "invocation": 0, "hardMode": false, "coxScale": "",
  "requiredRoles": ["tobmelee", "tobranged", "tobnfrz"], "hostRole": "tobmelee",
  "learner": false, "teacher": false }
```

Unlike the keep-alive patch, every field here is sent (no dedup) so the host can both set and
**clear** values — an empty description, a zeroed minimum KC. `requiredRoles`/`hostRole` are
`null` (and then omitted by Gson) for activities without roles.

Two different `update` patches travel over the same frame type, distinguished only by shape:

- The **keep-alive patch** ([`OSPartySocket.update()`](../src/main/java/net/osparty/api/OSPartySocket.java#L531-L545)) carries only live fields (occupancy, world, layout, needed roles) and is deduped against the last patch actually sent.
- The **host edit** (`AdvertisementEditRequest`, sent via [`OSPartySocket.edit()`](../src/main/java/net/osparty/api/OSPartySocket.java#L551-L560)) always sends and resets the dedup baseline, so the next keep-alive re-sends live fields against the new state.

Activity ids are the `id` values in [`Activity.java`](../src/main/java/net/osparty/model/Activity.java)
(`cox`, `tob`, `toa`, `ba`, and so on). `minHardModeKillCount` only means something for activities
with a harder variant (`hardModeLabel` in `Activity.java`: CoX → CM, ToB → HM, ToA → Expert).

### Host authentication

So another client can't hijack or close someone else's ad, host-only board frames are gated by a
**per-party secret** distinct from the per-account credential in the `X-OSParty-Auth` header.

On `host`, the plugin mints a random UUID client-side
([`CreatePanel.java:1268`](../src/main/java/net/osparty/ui/CreatePanel.java#L1268)) and sends it
as `key`. The server stores it in the ad's session — it is never returned in any response — and
requires the same key on that ad's `update`, `unhost`, `resume`, `createVoiceChannel`,
`kickVoiceMember` **and `transferHost`**. A wrong or missing key is rejected. The plugin persists
the key locally (RuneLite config, `osparty` group, key `hostKey`, alongside the ad id it belongs
to) so it can keep managing the ad after a client restart; see
[`PartyState`](../src/main/java/net/osparty/ui/PartyState.java#L97-L131).

`transferHost` carries **both** the outgoing host's current key (`key`, to authorise the change)
and a `newKey` the incoming host will use afterwards — the ad is re-keyed atomically, so the old
key stops working the moment the transfer lands. See [Host transfer](#host-transfer-live-channel)
below for how this fits into the full handshake, which starts on the LIVE channel.

Note this `passphrase`/`key` pair is easy to conflate: `passphrase` (also sent on `host`) is the
**live room's** key — it is what `hello`/`join` use to reach the LIVE-channel room — while `key`
here is the **board's** host-only-mutation secret. They are different values with different
lifetimes; only `passphrase` is ever shown to other players (as the invite code/passphrase on the
Party tab).

### Signing in, and getting back in

`X-OSParty-Auth` is presented at the handshake, but a machine that has never enrolled has nothing to
present. That case is settled by the first `identify` on the connection: the server either mints a
credential (`authIssued`) or explains why it cannot (`authFailed`).

Enrolment is trust-on-first-use, so an account's *first* device enrols with no questions asked and
every later one has to prove itself. There are three ways it can, and `authFailed` says which are
actually open right now rather than assuming:

| Route | Frame | Needs | Covers |
|---|---|---|---|
| Coupling | `requestCouplingCode` → `couplingConfirm` | A signed-in device **online now** to display a six-digit code | "Both my computers are here" |
| Recovery code | `recoveryConfirm` | One of the ten codes issued with the account's first credential | "The old PC is gone" |
| Discord | `startDiscordRecovery` → `discordRecoveryPoll` | A Discord link made *from a signed-in session* | "The old PC is gone and I saved nothing" |

Three properties are load-bearing and easy to break by accident:

- **A code is minted only on request, and reaches every replica.** Two devices belonging to one person land
  on the same pod about a third of the time (3 replicas, no session affinity), so the code delivery and the
  "is a device online" check both go through `CouplingBus` over Redis pub/sub rather than sweeping the local
  connection map. And nothing is minted by a failed sign-in — otherwise naming an account hash was enough to
  put a code dialog in front of whoever really owned it, repeatedly.

- **`identify` is answered once per connection per account.** It is re-sent on every reconnect, and a
  device that cannot enrol reconnects on every world hop and every network blip. Answering each one
  put a modal prompt on the user's screen and a fresh code on their other machine's, repeatedly, for
  a situation that had not changed. `retryAuth` is the deliberate way past it.
- **Only a Discord link made by an authenticated session counts for recovery**
  (`discord_link.verified`). Linking used to accept any account hash a session merely named, so
  without this an attacker could bind their own Discord account to someone else's hash and then
  "recover" it. `startDiscordLink` and `unlinkDiscord` therefore both require a signed-in session
  once the account has a credential.

Recovery codes are Crockford base32 (no I/L/O/U), sixteen characters shown as `XXXX-XXXX-XXXX-XXXX`.
The server stores only their SHA-256, they are single-use, and they never expire — the day a machine
dies is not a day anyone schedules. Typed-in codes are normalised before comparison, so `O` for zero
and `l` for one are accepted.

### Keep-alive, resume and reconnection

The open board connection **is** the hosted ad's keep-alive — there is no separate heartbeat
frame on this channel. While connected, the server refreshes the ad's TTL; on a drop the ad
survives a grace window, and `onOpen()` immediately re-sends `resume` with the remembered
`(id, key)` so the ad picks back up where it left off
([`OSPartySocket.onOpen()`](../src/main/java/net/osparty/api/OSPartySocket.java#L779-L804)). If
the grace window lapses before a resume arrives, the server answers with `gone` (or a `gone`
`detail` on the next `error`), and the plugin clears its local hosting state.

Reconnects use **jittered exponential backoff**: `min(30s, 1s << min(attempt, 5))` plus up to a
further second of jitter
([`OSPartySocket.scheduleReconnect()`](../src/main/java/net/osparty/api/OSPartySocket.java#L1584-L1601)).
A manual "Reconnect" action resets the attempt counter and retries immediately. Every reconnect
re-subscribes (with `since` set to the last known `boardSeq`), re-resumes any hosted ad,
re-identifies, and — if a live party is attached — re-announces on the LIVE channel too, since
the server holds no durable live state across a reconnect.

## The LIVE channel (`Mux.LIVE = 2`)

The live channel carries the party room itself: the server-authoritative roster, per-member live
state, map pings, ready checks, spec-drain broadcasts, join prompts and the host-transfer
handshake. It rides the same connection as the board, tagged with `Mux.LIVE` instead of
`Mux.BOARD`, and is attached/detached as a unit by
[`LivePartyChannel`](../src/main/java/net/osparty/party/LivePartyChannel.java) whenever the
client hosts, joins, or leaves a party — there is no live-channel traffic for a client that isn't
in one.

Frame shapes on this channel use a **short field key for `type`**: every outbound frame class in
[`LiveFrames`](../src/main/java/net/osparty/party/LiveFrames.java) declares
`@SerializedName("t") final String type = "…"`, so on the wire the discriminator is `"t"`, not
`"type"` (unlike the board channel, which uses `"type"` in full). Several other fields are
similarly shortened for size — a live update's vitals go out as `hp`/`pr`/`sp`/`re`, for
example — via [`LiveStateCodec`](../src/main/java/net/osparty/party/LiveStateCodec.java); this
document spells out the long/short names per frame below but doesn't exhaustively cover the
per-field codec.

### Hosting, joining and node affinity

Hosting sends `host` (or joining sends `join`) naming the room by `room` — the same value as the
ad's `passphrase`. The server answers `welcome` with the caller's assigned `memberId` and,
for the host, the pod (`nodeId`) the room actually landed on; the host then stamps that node onto
its own `Advertisement` (an ordinary board `update` patch with a `node` field) so joiners connect
directly to the right pod via `/n/{nodeId}/api/ws`, rather than being redirected after the fact —
see [`LiveParty.hintLiveNode()`](../src/main/java/net/osparty/party/LiveParty.java#L230-L234) and
its callers in `PartyCardPanel`/`OSPartyPanel`, which call it with `ad.getNode()` before joining.

Three frames handle a room being mid-move, and are intercepted by `LivePartyChannel` itself —
they never reach the party's own frame listener:

- **`redirect`** (`nodeId`) — the room lives on a different pod; the connection moves there.
- **`ownerChanged`** (no payload) — the owning node is draining or already lost the room; the
  client drops its node hint and reconnects unhinted.
- **`ownerPending`** (`retryAfterMs?`) — the room exists but has no owner yet (its host is
  mid-reclaim after the old node drained); the client waits and re-announces, up to 20 retries.

On every (re)connect the client re-sends `hello` and then `host`/`join` as appropriate — the
server holds no durable live state, so the room is rebuilt from what each connected member
re-announces. A member additionally re-sends `join` any time it has gone 90 seconds without
sending anything (`SWEPT_AFTER_MS` in
[`LiveParty.java`](../src/main/java/net/osparty/party/LiveParty.java#L74-L82)) — long enough that
the server's own idle timeout would already have dropped the seat, most commonly after a logout
the connection outlived.

### Client → server frames

| Frame (`t`) | Payload | Purpose |
|---|---|---|
| `hello` | `accountHash`, `name` | Announce/re-announce identity |
| `host` | `room`, `hostName`, `activityId`, `capacity`, `locked`, `role`, `learner`, `teacher`, `accountHash` | Open (or re-announce) a room as its host |
| `join` | `room`, `activityId`, `role`, `learner`, `teacher`, `invited`, `name`, `accountHash` | Join (or rejoin) a room as a member; `invited` claims prior admission (client-asserted) so a handover doesn't dump an admitted member back into the applicant queue |
| `heartbeat` | (none) | Proof of life, sent only when nothing else went out within 5 s |
| `update` | `s` (state — partial `PlayerUpdate`, short-keyed), `g?` (urgent) | Report changed vitals/items/profile fields; `g: true` asks the owner node to relay without waiting out its idle window (only a vital moving *down* sets it) |
| `ping` | `x`, `y`, `plane`, `color`, `name` | Drop a map ping |
| `command` | `action` (`ADMIT` / `KICK` / `REJECT`), `target` (memberId), `name` | Host roster action |
| `setCapacity` | `capacity` | Host changes party size |
| `setLocked` | `locked` | Host locks/unlocks the room |
| `setMeta` | `meta` (`PartyMeta`) | Host (re)publishes the ad's settings for members to track |
| `setDiscord` | `url` | Host sets/clears the voice-channel invite URL |
| `leave` | (none) | Leave the room |
| `readyStart` | `checkId`, `starter` | Start a ready check (any member may) |
| `ready` | `checkId` | Mark ready for the named check |
| `specDrain` | `npcIndex`, `weapon`, `hit`, `world` | Broadcast a defence-draining special attack |
| `fcRequest` | `target` (memberId), `kind`, `friendsChat` | Host → one member: how to actually get into the raid (`kind`: `FC` / `NOTICE_BOARD` / `OBELISK`) |
| `transferHost` | `kind` (`OFFER`/`ACCEPT`/`COMMIT`/`ABORT`), `target` (memberId), `newHostKey?`, `newHostName?`, `hostStays` | One step of the host-transfer handshake — see below |

Frame shapes: [`LiveFrames`](../src/main/java/net/osparty/party/LiveFrames.java).

### Server → client frames

| Frame (`t`) | Payload | Meaning |
|---|---|---|
| `welcome` | `m` (memberId), `status`, `nodeId?` | Seated: our own status, and (host only) the pod the room landed on |
| `roster` | `members[]` (`m`, `name`, `accountHash`, `playerId`, `status`, `role`, `learner`, `teacher`, `offline`), `host`, `capacity`, `locked`, `discordUrl`, `closed?` | The authoritative roster and room settings. `closed: true` ends the party for everyone |
| `mu` | `u[]` of (`m`, `s`) | One aggregation window's worth of every other member's state changes |
| `resync` | (none) | We were just seated with no picture of the room (the owner keeps no live state); treat everything we hold as stale and resend it all |
| `alive` | `m` | A peer's heartbeat, relayed — presence only, no state change |
| `meta` | `meta` (`PartyMeta`) | The host republished the ad's settings |
| `memberLeft` | `m` | A member's seat was dropped |
| `ping` | `x`, `y`, `plane`, `color`, `name`, `m` | A peer's map ping |
| `readyStart` | `checkId`, `starter`, `m` | Someone started a ready check |
| `ready` | `checkId`, `m` | A member marked ready |
| `specDrain` | `m`, `npcIndex`, `weapon`, `hit`, `world` | A relayed defence-draining special |
| `fcRequest` | `host`, `kind`, `friendsChat`, `m` | Targeted join prompt (delivered only to its target) |
| `transferHost` | `kind`, `m`, `newHostKey?`, `newHostName?`, `hostStays` | Targeted host-transfer step (delivered only to its target) |
| `kicked` | (none) | The host removed us; delivered only to the removed member |
| `error` | `detail` | A live-channel error |
| `redirect` | `nodeId` | Intercepted by `LivePartyChannel` — see [Node affinity](#hosting-joining-and-node-affinity) |
| `ownerChanged` | (none) | Intercepted by `LivePartyChannel` |
| `ownerPending` | `retryAfterMs?` | Intercepted by `LivePartyChannel` |

Frame shape: [`LivePartyChannel.Frame`](../src/main/java/net/osparty/party/LivePartyChannel.java#L234-L275)
(one class covering every incoming type). Dispatch:
[`LiveParty.onFrame()`](../src/main/java/net/osparty/party/LiveParty.java#L372-L461).

### Host transfer (LIVE channel)

Handing the party to another member without destroying it is a four-step, targeted handshake over
the LIVE channel, driven by
[`HostTransferHandler`](../src/main/java/net/osparty/ui/HostTransferHandler.java) and
[`HostTransferEvent`](../src/main/java/net/osparty/party/HostTransferEvent.java), with a
12-second timeout on each side of the handoff:

1. **OFFER** (old host → target): "will you take over?" — carries a fresh `newHostKey` the new
   host will use to own the board ad, and `hostStays` (whether the old host remains a member
   afterwards or leaves).
2. **ACCEPT** (target → old host): confirms liveness. This is the trigger for the old host to
   send the **board-channel** `transferHost` frame — the actual, irreversible ad re-key — using
   its own current host key plus the `newKey` from step 1.
3. **COMMIT** (old host → target), sent only once the board's `transferred` ack comes back: the ad
   is now re-keyed to the new host, which promotes itself locally and adopts the ad
   (`boardService.adoptHostedAd`).
4. **ABORT** (old host → target): sent if the ACCEPT never arrives (timeout) or the board re-key
   fails; the target stays a member and the old host keeps hosting.

The old host keeps full authority — and keeps owning the board ad — until COMMIT actually lands,
so a dropped or ignored message at any step never orphans the party; the failure mode is always
"nothing happened", never a party with no host. The recipient of an OFFER is **not prompted** —
`HostTransferHandler.onOffer()` auto-accepts on the target's behalf
([`ui/HostTransferHandler.java:124-142`](../src/main/java/net/osparty/ui/HostTransferHandler.java#L124-L142)).
Feedback for both sides is posted to the in-game chatbox, not the side panel.
