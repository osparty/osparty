# OSParty architecture

OSParty is split into two layers that share one connection (see
[PROTOCOL.md](PROTOCOL.md) for the wire format) but own completely different things.

## The listing service (the board)

The listing service is a bulletin board: it advertises open parties and tracks **no
membership at all**. An [`Advertisement`](../src/main/java/net/osparty/model/Advertisement.java)
carries the activity, requirements, world, description, role composition, host's live-room
passphrase and a snapshot of the member list — enough to render a search card and to let a
client dial into the live room — but the board itself never admits, kicks or seats anyone. The
reference implementation is
[github.com/osparty/ospartyapi](https://github.com/osparty/ospartyapi) (Spring Boot + Redis).

An ad's lifetime is tied to its host's connection: the open WebSocket *is* the keep-alive (see
PROTOCOL.md's [Keep-alive, resume and reconnection](PROTOCOL.md#keep-alive-resume-and-reconnection)).
Board reads (`subscribe`/`snapshot`/`batch`) and board writes (`host`/`update`/`unhost`) are the
only things this layer does; Discord linking and device management ride the same channel because
they need the same per-account credential, not because they're conceptually part of the board.

## The live party (the room)

Once an ad is created or joined, the party itself runs as an **in-memory room**, owned by exactly
one node, keyed by the ad's passphrase
([`LiveParty`](../src/main/java/net/osparty/party/LiveParty.java)). Joining an ad means joining
the host's passphrase room on whichever pod the host's `host` frame landed on — the host stamps
that pod (`node`) onto its own advertisement so joiners connect directly via
`/n/{nodeId}/api/ws` rather than being redirected after the fact (see PROTOCOL.md's
[Node affinity](PROTOCOL.md#hosting-joining-and-node-affinity)).

The room holds:

- **The roster.** Who is seated, who is pending, and the room's capacity/lock state are the
  server's answer, delivered in `roster` frames — not a claim any client makes. `PENDING`
  members hold a connection and are visible to the host, but nothing they send fans out to
  anyone else, and they don't count against capacity checks the way an admitted member does.
- **Per-member live state.** Every member sends a `PlayerUpdate` (equipment, inventory, combat
  vitals, chosen role, learner/teacher mark) as only the parts that changed, and the owning node
  relays it to the rest of the room without reading or validating it. Receivers merge each
  update into the copy they hold — an omitted field means "unchanged", not "cleared" — and render
  it on the Party tab.
- **Host management.** Admit, kick, capacity, lock and the map-ping/ready-check/spec-drain
  broadcasts are all enforced by the node that owns the room.
- **The host-transfer handshake** and **friends-chat/notice-board/obelisk join prompts**, both
  targeted point-to-point deliveries between two members, routed by the server rather than
  broadcast to the room.

The server holds no *durable* live state across a reconnect: every (re)connect re-announces
(`hello` then `host`/`join`), and the room is rebuilt from what each currently-connected member
says about itself. That is also why a member re-sends `join` after 90 seconds of silence — long
enough that a plain reconnect wouldn't explain it, most commonly a logout the socket outlived.

## Trust model

The dividing line is exactly the board/room split above: the server is authoritative for
**membership**, everything else about a member is **relayed on trust**, and killcount is neither
— it's independently fetched by whoever is looking.

| Owned by the server | Self-asserted, relayed verbatim | Independently verified by each viewer |
|---|---|---|
| Roster membership (who is actually seated) | Equipment, inventory, rune pouch contents | Killcount / hard-mode killcount |
| Capacity | Combat vitals (HP, prayer, spec, run energy) | |
| Admission (`ADMIT`/`REJECT`) | Chosen role, learner/teacher marks | |
| Kicks (`KICK`) | Account type badge (`NORMAL`/`IRONMAN`/…) | |
| Lock state | Personal best time (`pbSeconds`) | |
| Host identity (post-transfer) | World, friends-chat membership | |

A modified client cannot forge its way into a party, kick another member, or claim capacity that
isn't there — those all require the owning node to agree. What a member reports about *itself* —
gear, stats, role, its own account type, its own PB — is taken on trust and relayed unmodified to
everyone else in the room, exactly as RuneLite's built-in Party plugin has always worked; there is
no server-side validation of any of it. `hideInventory`/`hideGear` withhold those fields at
the source (see `OSPartyConfig`), which is honoured the same way on both the sender's and
receiver's side of `LiveParty.applyPrivacy()`.

**Killcount is the one exception that looks like self-reporting but isn't.** A `PlayerUpdate`
does declare `killCount`/`hardModeKillCount` fields, but they're `transient` and unused —
[`PlayerUpdate.java:79-88`](../src/main/java/net/osparty/party/PlayerUpdate.java#L79-L88) —
because a client cannot read another account's boss KC locally at all. What actually populates
the "Req: 500 KC" checks and the roster's KC line is
[`KillcountService`](../src/main/java/net/osparty/service/KillcountService.java), which looks
each player up on the public OSRS hiscores independently, cached per (player, activity) for 30
minutes. So KC is neither server-owned nor self-asserted by the subject: every viewer fetches it
themselves from a third party, which is a stronger guarantee than anything else on this list —
and also means it can lag reality by up to the cache TTL, or read as unknown for a hiscores outage
or a private profile.

Membership favouring cooperation over enforcement is deliberate for everything below the
server-owned line: the backstop for bad actors is the RuneWatch/We Do Raids watchlist check and
the block list, not protocol-level verification.

## A member's `Advertisement` is a snapshot, not a live view

The copy of an `Advertisement` a searching client holds — the one behind a search card, and the
one a joining member takes with it when it applies — is exactly that: a copy, taken once. It is
**never re-fetched**. If the host edits the party afterwards (description, requirements, loot
rule, CM/HM toggle, CoX scale…), a member sitting on the old copy would keep showing what it saw
at join time for the rest of the party's life, because nothing about the board layer tells it
otherwise — board deltas only flow to clients actively subscribed to Search, and a member in the
party usually isn't.

This is what [`PartyMeta`](../src/main/java/net/osparty/model/PartyMeta.java) is for: a
host-authoritative subset of the ad's editable fields (description, capacity, world, KC
minimums, loot rule, private/ironman flags, invocation, hard mode, CoX scale, roles, learner/
teacher), published over the **live room** via the `setMeta` frame whenever the host changes it,
and re-published on every reconnect since the room holds no durable state either. Members apply
it with `PartyMeta.applyTo()` onto their held `Advertisement`. Deliberately excluded: the
activity and invite code (fixed at creation) and anything the live layer already owns outright
(roster, lock state, Discord URL) — those aren't duplicated onto the ad copy at all.

## Local storage

Everything the plugin persists to disk lives under `<runelite>/osparty/`, as one JSON file per
concern, each written through the same versioned, atomic writer:
[`JsonFile<T>`](../src/main/java/net/osparty/store/JsonFile.java). Every write goes to a sibling
`.tmp` file first, then an atomic rename (`ATOMIC_MOVE`, falling back to `REPLACE_EXISTING` on a
filesystem that can't do that atomically) — a crash, full disk or serialisation failure can never
leave a half-written file where good data used to be. Each file also carries a schema version; if
what's on disk is *newer* than this build knows (a downgrade), the plugin reads nothing from it
and writes nothing over it, rather than risk corrupting a file a newer OSParty will read later.

| File | Backed by | Contents |
|---|---|---|
| `history.json` | [`PartyHistoryService`](../src/main/java/net/osparty/service/PartyHistoryService.java) | Local, capped party history (newest first; capped at the *Party history size* setting, hard ceiling 500) |
| `flags.json` | [`JsonPartyStore`](../src/main/java/net/osparty/store/JsonPartyStore.java) | Favourite and block lists, keyed by `accountHash` where known, per-kind |
| `credentials.json` | [`CredentialStore`](../src/main/java/net/osparty/store/CredentialStore.java) | One OSParty auth token per character on this machine, keyed by `accountHash` |

None of these three ever leave the client except as the credential itself, presented on the
`X-OSParty-Auth` header.

### Why the credential isn't in RuneLite config

`CredentialStore` exists specifically so the long-lived, per-account auth token never touches
RuneLite's own config system. RuneLite config is **synchronised**: for a signed-in RuneLite user
the whole config group is PATCHed to `api.runelite.net` in plaintext. A token that authenticates
a player to OSParty has no business being copied to a server OSParty doesn't run, least of all
stored there as if it were an ordinary preference — see the rationale in
[`CredentialStore.java:15-19`](../src/main/java/net/osparty/store/CredentialStore.java#L15-L19).
Losing this file is a non-event: the next connection enrols a fresh credential.

This is narrower than "OSParty avoids RuneLite config" as a blanket rule — it doesn't. The
**host key** — the much shorter-lived, per-party secret described in PROTOCOL.md's
[Host authentication](PROTOCOL.md#host-authentication) — genuinely is stored via `ConfigManager`,
under the `osparty` group, keys `hostKey`/`hostKeyPartyId`
([`PartyState.java:97-131`](../src/main/java/net/osparty/ui/PartyState.java#L97-L131)), so a
restarted client can resume the ad it was hosting. The distinction is what's at stake: a host key
only ever authorises actions on one ad that will itself expire, while the credential authenticates
the account indefinitely, on every connection, for everything.

### The `osparty` RuneLite config group

Beyond the user-facing settings (`OSPartyConfig`), the `osparty` config group also holds several
things that are really plugin state rather than preferences, all read/written directly via
`ConfigManager.get/setConfiguration(OSPartyConfig.GROUP, …)` rather than through a `@ConfigItem`:

- **Search filters and panel state** — activities, roles, loot rule, ironman-only, the learner
  filter, max ping, sort order, and each collapsible section's expanded/collapsed state (keys
  `searchActivities`, `searchRoles`, `searchLoot`, `searchIronman`, `searchRegions`,
  `searchMaxPing`, `searchSort`, `searchLearnerFilter`, `searchHideIneligible`, and the matching
  `*Expanded` keys — see [`SearchPanel.java:1050-1064`](../src/main/java/net/osparty/ui/SearchPanel.java#L1050-L1064)).
- **Create-form presets**, including the implicit "last used" preset that pre-fills the form —
  saved under key `lastPreset`, and named presets as a JSON array under key **`favourites`**. The
  key name is legacy (presets were literally called favourites once); it has stayed `favourites`
  for backward compatibility, and now collides in name only with the unrelated starred-player
  favourites list, which lives in `flags.json` instead — see
  [`CreatePanel.java:70-72`](../src/main/java/net/osparty/ui/CreatePanel.java#L70-L72) and
  [`AdvertisementPreset`](../src/main/java/net/osparty/model/AdvertisementPreset.java).
- **The host key** (`hostKey`/`hostKeyPartyId`/`hostAdvertiseLayout`), described above.
- **The membership-resume marker** (`memberPartyId`, `memberPartyCode`, `memberPartyRole`,
  `memberPartyLearner`, `memberPartyAccount`, `memberPartySeenAt`) — an admitted member's party is
  remembered for 90 seconds so a relogin within that window seats them straight back in, without
  re-applying or waiting on the host; a pending applicant is deliberately not remembered this way.
  See [`PartyState.rememberMembership()`/`savedMembership()`](../src/main/java/net/osparty/ui/PartyState.java#L152-L208).

None of these are secrets on the order of the credential token, which is exactly why they're fine
to leave in the synced config: a leaked host key or search filter is not an account takeover.
