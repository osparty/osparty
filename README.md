# OSParty

A RuneLite side-panel plugin to **search**, **advertise** and **join** parties
for group activities (raids, bosses, minigames), with raid **role matchmaking**,
**learner/teacher** raids, in-game map pings and a special-attack defence tracker.

The plugin uses a **two-layer split**:

- **Advertising API** — a small *bulletin board* HTTP backend that lists open
  parties and accepts new ads. It tracks **no membership**; an ad just carries
  the activity, requirements, world, description, role composition and the host's
  **party passphrase**. The reference server lives in its own repository:
  **[github.com/iodrareg/ospartyapi](https://github.com/iodrareg/ospartyapi)**
  (Spring Boot + Redis).
- **Live party** is RuneLite's built-in **peer-to-peer** party network
  (`PartyService`/`WSClient`). Joining an ad means joining the host's passphrase
  room; the real roster, plus each member's live gear / inventory / combat stats
  and chosen role, is exchanged P2P. On top of it the plugin adds a
  **host-authoritative** management layer (admit / decline / kick / capacity) —
  see [Live party](#live-party-peer-to-peer).

## Side panel

The panel has up to three tabs:

- **Search** — pick the activities you're interested in, hit *Refresh*, and apply
  to an open party. Collapsible **Roles** (tick the raid roles you'll fill) and
  **Search** (free text + **loot rule** + **ironman-only**) sections keep it
  compact; mark yourself **"I'm a learner"** to be flagged as one to hosts. You
  can also **Join a private party by code**. Full parties and your own aren't
  shown/appliable.
- **Create** — host a new party: activity, party size, **loot rule** (FFA /
  Split), min KC (and a separate CM/HM/Expert KC), optional world and
  description, **Private** / **Ironmen only** toggles, raid **roles**
  composition, and **Learner/Teacher** tagging, then *Create party* to enter it
  as host. The form stays usable logged-out so you can build and save
  favourites; only *Create party* needs you logged in.
- **Current** — only shown while you're in a party. Shows the party type tags,
  the still-needed roles, and the roster (click a member to expand a **Skills /
  Gear / Inventory** inspection view, with each member's role and learner mark),
  and offers host controls (admit / decline, kick, disband) or leave.

You can be in **only one party at a time** (hosting or joined). Applying to a
party leaves your current one first; you must leave/disband before creating.

Filters on the Search tab (activities, roles, loot, ironman, the learner mark and
the collapse state) are **remembered across sessions**.

## Roles (raids)

Theatre of Blood and Chambers of Xeric advertise a **role composition**. Each of
the four difficulty modes has its own role set so a pick in one can't be matched
against a party of another:

| Mode | Roles |
|------|-------|
| **ToB** | Melee · Ranged · North freeze · South freeze |
| **HMT** (ToB hard mode) | Melee · Ranged · North freeze · South freeze |
| **CoX** | Melee · Mage · Runner · Fill |
| **CM** (CoX challenge mode) | Veng · Ancient · Normal spells · Fill |

- **Hosting** — pick your own role and the team composition. ToB's composition is
  fixed by party size; CoX/HMT use a count per role (CoX's *Fill* absorbs the
  remainder). The composition must fill the party and include the host's role.
- **Searching** — the **Roles** filter has a tab per mode; tick the roles you're
  willing to fill (*Fill / Any* means "any role"). A party matches if it still
  needs one of your ticked roles. Applying prompts you to commit to one of its
  open roles.
- The host keeps the **still-open roles** up to date as members join/leave (sent
  on the heartbeat), so search cards and the apply prompt always show what's left.

## Learner / teacher raids

When creating a raid you can tag it **Learner** or **Teacher** (mutually
exclusive — either one marks it a *learner raid*; pick neither for a normal
raid). Searchers can also flag **"I'm a learner"**, which travels with their
application so the host sees it on applicants and in the roster.

In-game, party members tagged as a learner or teacher get a small **icon by their
name** and a **coloured tile marker** (untagged members get nothing). The icon
and tile marker are independent toggles, and the tile colour is configurable per
role — see **Learner/teacher name icons** / **tile markers** / **Teacher
colour** / **Learner colour** in settings.

## Party types

- **Private** — hidden from public search; the host shares a short **invite
  code** (shown on the Current tab) and others join via *Join private party by
  code* on the Search tab.
- **Loot rule** — `FFA` / `Split` / `Unspecified`, set by the host and shown on
  search cards, with a Search-tab filter.
- **Ironman-only** — restricts the party to ironman accounts. The plugin reads
  your account type, blocks mains from applying (button shows *Iron only*),
  broadcasts each member's account type so the roster shows an **iron tag**
  (`[IM]` / `[HCIM]` / `[UIM]` / `[GIM]` / `[HCGIM]`), and warns the host of a
  *Not an ironman* applicant. Self-reported (cooperative trust, like KC/gear);
  RuneWatch remains the backstop for bad actors.

## RuneWatch warnings

The plugin checks every roster member and applicant against the public
**RuneWatch** / **We Do Raids** scammer watchlist (the same combined
`mixedlist.json` feed the official RuneWatch plugin uses). A flagged player gets
a red **⚠ RuneWatch: &lt;reason&gt;** badge under their name in the Current tab —
so a host sees the warning *before* admitting an applicant. The watchlist is
downloaded on start-up and refreshed every 15 minutes; names are matched locally
(no name ever leaves the client). Toggle it with **RuneWatch warnings** in
settings.

## In-game extras

- **Map pings** — hold the **Ping hotkey** (default `` ` ``) and left-click a
  tile to ping it for the whole party; incoming pings animate on the scene in the
  sender's colour. Toggle / recolour in settings.
- **Defence tracker** — while the party drains a boss's defence with special
  attacks, shows its live defence next to the overhead HP bar and/or as an info
  box. Reads RuneLite's **Special Attack Counter** plugin events (enabled
  automatically), with a configurable colour ramp. See the **Defence tracker**
  settings section.
- **Ready checks** — anyone in the party can start one from the Current tab;
  optional sounds via the **Meme mode** section.
- **Friends-chat requests** — a host can ask you (via an on-screen popup) to join
  their friends chat; the plugin only surfaces the request, it never joins or
  hops for you. Toggle with **Friends-chat join requests**.

## Running against the API

The plugin talks to the advertising API at `https://api.osparty.net` by default.
There's **no in-settings URL field**; for local development point it at your own
[ospartyapi](https://github.com/iodrareg/ospartyapi) instance with a JVM system
property:

```
-Dosparty.apiUrl=http://localhost:8080
```

The store starts empty — Search returns parties once people advertise them.

## Advertising API contract

The API is a **bulletin board only** — it advertises open parties and tracks no
membership (membership lives in the P2P room). Reference implementation:
**[github.com/iodrareg/ospartyapi](https://github.com/iodrareg/ospartyapi)**.

All endpoints are under the versioned base path **`/api/v1`**.

| Method | Path                             | Body / Query                   | Auth      | Returns   |
|--------|----------------------------------|--------------------------------|-----------|-----------|
| GET    | `/api/v1/parties`                | `?activity={id}&player={name}` | —         | `Party[]` |
| GET    | `/api/v1/parties/by-code/{code}` | —                              | —         | `Party`   |
| POST   | `/api/v1/parties`                | `PartyRequest`                 | host key  | `Party`   |
| PUT    | `/api/v1/parties/{id}`           | `PartyUpdate` (partial)        | host key  | `Party`   |
| PUT    | `/api/v1/parties/{id}/heartbeat` | `?size&world&layout&roles`     | host key  | `Party`   |
| DELETE | `/api/v1/parties/{id}`           | —                              | host key  | `Party`   |
| WS     | `/api/v1/ws/parties`             | subscribe + host/update/unhost | host key¹ | `Party[]` |

¹ Over the socket the connection is the host's identity; the key is the
reclaim credential carried on `host`/`resume` (and accepted on writes).

The API replaces any previous ad from the same host and rate-limits `POST` to
1/5s per IP.

### Live socket

The plugin keeps **one WebSocket** to **`/api/v1/ws/parties`** open for the whole
session (`PartySocket`), used for both reading and hosting:

- **Search read** — while the Search tab is visible the plugin `subscribe`s and
  renders the `snapshot` + `created`/`updated`/`removed` deltas, so list load is
  driven by *changes*, not by user count. Off the tab it `unsubscribe`s; the
  connection stays up.
- **Host write** — creating a party sends `host` (the `hosted` ack carries the
  server id); field changes send `update`; disband sends `unhost`. **The open
  socket is the keep-alive** — there's no periodic heartbeat. A brief disconnect
  keeps the ad alive for a grace window and the plugin `resume`s it (same id) on
  reconnect.

If the socket can't connect (older server / WS blocked) everything **falls back to
REST** — `POST` to create, `PUT …/{id}/heartbeat` to keep alive, `DELETE` to
disband — so the plugin works against either. See the API repo for the schema.

### Host authentication

So a hand-rolled REST client can't hijack or close someone else's ad, host-only
mutations are gated by a per-party secret. On create the plugin mints a random
UUID and sends it in the **`X-OSParty-Host-Key`** header; the server stores it in
the party's session (never returned in any response) and requires the same header
on that party's `heartbeat` and `DELETE` — wrong/missing key → `403`. The plugin
persists the key locally so it can keep managing the ad after a client restart.
(Ads created without a key stay open, for backward compatibility with older
clients.)

> The plugin's `PartyService` interface still declares older
> `apply`/`cancel`/`accept`/`kick` membership methods, but the UI no longer calls
> them — those actions are handled P2P now. They remain only so a future backend
> could re-introduce a server-side accept-gate (e.g. withholding the passphrase
> until the host admits).

`PartyRequest`:

```json
{ "activity": "tob", "host": "Zezima", "description": "...", "capacity": 3,
  "world": "420", "minKillCount": 500, "minHardModeKillCount": 50,
  "passphrase": "wine-of-zamorak-…",
  "privateParty": false, "lootRule": "SPLIT", "ironmanOnly": false,
  "hostAccountType": "NORMAL", "hardMode": false, "invocation": 0,
  "requiredRoles": ["tobmelee", "tobranged", "tobnfrz"], "hostRole": "tobmelee",
  "learner": false, "teacher": false }
```

`Party` (adds a server-generated `inviteCode`, live `layout`/`neededRoles`):

```json
{ "id": "abc", "activity": "tob", "host": "Zezima", "description": "...",
  "size": 1, "capacity": 3, "world": "420", "layout": null, "createdAt": 0,
  "hardMode": false, "invocation": 0,
  "minKillCount": 500, "minHardModeKillCount": 50,
  "passphrase": "wine-of-zamorak-…", "members": ["Zezima"],
  "privateParty": false, "inviteCode": "Y2Y3D9", "lootRule": "SPLIT",
  "ironmanOnly": false, "hostAccountType": "NORMAL",
  "requiredRoles": ["tobmelee", "tobranged", "tobnfrz"], "hostRole": "tobmelee",
  "neededRoles": ["tobranged", "tobnfrz"], "learner": false, "teacher": false }
```

Activity ids are the `id` values in `Activity.java` (`cox`, `tob`, `toa`, …).
`minHardModeKillCount` is only meaningful for activities with a harder variant
(`hardModeLabel` in `Activity.java`: CoX → CM, ToB → HM, ToA → Expert). The host
key never appears in a `Party` response.

## Live party (peer-to-peer)

Once you create or join an ad, the actual party runs over RuneLite's party
network, keyed by the ad's **passphrase**:

- **Roster & live data** — every member broadcasts a `PlayerUpdate`
  (`equipment`, `inventory`, combat stats, chosen role, learner mark) which
  everyone renders in the Current tab. The host broadcasts a `PartyStateMessage`
  (the authoritative admitted roster + rules).
- **Host management** — applicants who join the room are **pending** until the
  host **Admits** them (the host sees their real gear/stats/role before
  deciding); **Decline**/**Kick** send a `MemberCommand` the target honours by
  leaving; capacity is enforced by the host. Admitting/declining from the side
  panel also dismisses the in-game chatbox prompt for that applicant.
- **Trust** — enforcement is cooperative, not server-enforced; a modified client
  could ignore it. Same trust model as the rest of RuneLite's party network.

### Applying / joining

- A player is in **at most one party at a time**; applying elsewhere leaves the
  current one first.
- **Leave** starts a 30s **cooldown** on that party before re-applying
  (client-side, anti-spam).
- After joining you are **pending** until the host admits you; you appear in the
  host's Current tab with your live gear/stats/role for them to vet.
- While in a party, a banner shows whether you're **in the host's friends chat**
  and **on the host's world** — both derived from read-only client state,
  refreshed each second. The plugin never joins the FC or hops for you (that
  would be disallowed automation); it only reports status.

### Hosting a party

Creating an ad opens the live room and switches the Current tab to a **manage
view**: the roster (host + admitted), **Pending applicants** with **Admit** /
**Decline**, per-member **Kick**, and **Disband**. When an applicant joins, the
host gets a chatbox ping and an in-game overlay of their combat stats and role;
admitting adds them to the roster (capacity-enforced). The host can share the
room **passphrase** shown in the panel to invite directly.

## Build

Run a dev client with the plugin loaded for local testing (point it at a local
API with `-ea -Dosparty.apiUrl=…` if desired) — `OSPartyPluginTest.main` launches it:

> Requires JDK 11 to match the RuneLite client. The Gradle wrapper resolves the
> `latest.release` RuneLite client from `https://repo.runelite.net`.
