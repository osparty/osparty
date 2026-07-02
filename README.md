# OSParty

A RuneLite side-panel plugin for finding a group. Search open parties, advertise
your own, and join for raids, bosses and minigames, with raid role matchmaking,
learner/teacher raids, in-game map pings and a special-attack defence tracker.

The plugin is split into two layers:

- A **listing service** — a small bulletin board that keeps a live list of open
  parties. It tracks no membership; an ad just carries the activity,
  requirements, world, description, role composition and the host's party
  passphrase. The reference server lives in its own repo,
  [github.com/iodrareg/ospartyapi](https://github.com/iodrareg/ospartyapi)
  (Spring Boot + Redis).
- The **live party** — RuneLite's built-in peer-to-peer party network
  (`PartyService`/`WSClient`). Joining an ad means joining the host's passphrase
  room. The roster, each member's live gear/inventory/combat stats and their
  chosen role are all exchanged P2P. On top of that the plugin adds a
  host-authoritative management layer (admit, decline, kick, capacity) — see
  [Live party](#live-party-peer-to-peer).

Discovery and hosting run entirely over a single WebSocket to the listing
service; there is no REST API.

## Side panel

The panel has up to three tabs.

**Search** — pick the activities you're interested in, hit Refresh, and apply to
an open party. Collapsible Roles and Search sections keep it compact: tick the
raid roles you'll fill under Roles, and use the Search section for free text, a
loot rule and an ironman-only filter. Mark yourself "I'm a learner" to be flagged
as one to hosts. You can also join a private party by code. Full parties and your
own ad are hidden.

**Create** — host a new party: activity, party size, loot rule (FFA or Split),
min KC (with a separate CM/HM/Expert KC), optional description, Private and
Ironmen-only toggles, raid role composition, and Learner/Teacher tagging. Hit
Create party to enter it as host. The form stays usable while logged out so you
can build and save favourites; only Create party needs you logged in.

**Current** — shown only while you're in a party. It lists the party type tags,
the still-needed roles, and the roster. Click a member to expand a Skills / Gear
/ Inventory view showing their role and learner mark. As host you also get the
management controls (admit/decline, kick, disband, edit); as a member you get
Leave.

You can be in only one party at a time, whether hosting or joined. Applying to a
party leaves your current one first, and you must leave or disband before
creating a new one.

Your Search-tab filters — activities, roles, loot, ironman, the learner mark and
the collapse state — are remembered across sessions.

## Roles (raids)

Theatre of Blood and Chambers of Xeric advertise a role composition. Each of the
four difficulty modes has its own role set, so a pick in one mode can't be
matched against a party in another:

| Mode | Roles |
|------|-------|
| **ToB** | Melee · Ranged · North freeze · South freeze |
| **HMT** (ToB hard mode) | Melee · Ranged · North freeze · South freeze |
| **CoX** | Melee · Mage · Runner · Fill |
| **CM** (CoX challenge mode) | Veng · Ancient · Normal spells · Fill |

- Hosting: pick your own role and the team composition. ToB's composition is
  fixed by party size; CoX and HMT use a count per role, with CoX's Fill
  absorbing the remainder. The composition has to fill the party and include the
  host's role.
- Searching: the Roles filter has a tab per mode. Tick the roles you're willing
  to fill (Fill / Any means "any role"). A party matches if it still needs one of
  your ticked roles, and applying prompts you to commit to one of its open roles.
- The host keeps the still-open roles up to date as members join and leave, so
  search cards and the apply prompt always show what's left.

## Learner / teacher raids

When creating a raid you can tag it Learner or Teacher. The two are mutually
exclusive — either one marks it a learner raid, and picking neither makes it a
normal raid. Searchers can also flag "I'm a learner", which travels with their
application so the host sees it on applicants and in the roster.

In-game, party members tagged as a learner or teacher get a small icon by their
name and a coloured tile marker; untagged members get neither. The icon and tile
marker are independent toggles, and the tile colour is configurable per role. See
the Learner/teacher name icons, tile markers, Teacher colour and Learner colour
settings.

## Party types

- **Private** — hidden from public search. The host shares a short invite code
  (shown on the Current tab) and others join via Join private party by code on
  the Search tab.
- **Loot rule** — FFA, Split or Unspecified, set by the host and shown on search
  cards, with a matching Search-tab filter.
- **Ironman-only** — restricts the party to ironman accounts. The plugin reads
  your account type, blocks mains from applying (the button reads Iron only),
  broadcasts each member's account type so the roster shows an iron tag (`[IM]`,
  `[HCIM]`, `[UIM]`, `[GIM]`, `[HCGIM]`), and warns the host about a non-ironman
  applicant. This is self-reported, on the same cooperative-trust basis as KC and
  gear; RuneWatch remains the backstop for bad actors.

## RuneWatch warnings

The plugin checks every roster member and applicant against the public RuneWatch
/ We Do Raids scammer watchlist — the same combined `mixedlist.json` feed the
official RuneWatch plugin uses. A flagged player gets a red ⚠ RuneWatch: &lt;reason&gt;
badge under their name on the Current tab, so a host sees the warning before
admitting an applicant. The watchlist is downloaded on start-up and refreshed
every 15 minutes, and names are matched locally, so no name ever leaves the
client. Toggle it with RuneWatch warnings in settings.

## In-game extras

- **Map pings** — hold the Ping hotkey (default `` ` ``) and left-click a tile to
  ping it for the whole party. Incoming pings animate on the scene in the
  sender's colour. Toggle and recolour in settings.
- **Defence tracker** — while the party drains a boss's defence with special
  attacks, this shows its live defence next to the overhead HP bar and/or as an
  info box. It reads RuneLite's Special Attack Counter plugin events (enabled
  automatically) and uses a configurable colour ramp. See the Defence tracker
  settings section.
- **Ready checks** — anyone in the party can start one from the Current tab, with
  optional sounds via the Meme mode section.
- **Friends-chat requests** — a host can ask you (via an on-screen popup) to join
  their friends chat. The plugin only surfaces the request; it never joins or
  hops for you. Toggle with Friends-chat join requests.

## Running against the listing service

The plugin talks to the listing service at `https://api.osparty.net` by default.
There's no in-settings URL field. For local development, point it at your own
[ospartyapi](https://github.com/iodrareg/ospartyapi) instance with a JVM system
property:

```
-Dosparty.apiUrl=http://localhost:8080
```

The list starts empty — Search shows parties once people advertise them.

## Listing protocol

The listing service is a bulletin board: it advertises open parties and tracks no
membership (that lives in the P2P room). The reference implementation is
[github.com/iodrareg/ospartyapi](https://github.com/iodrareg/ospartyapi).

The plugin keeps one WebSocket open to `/api/v1/ws/parties` for the whole session
(`PartySocket`) and uses it for both reading and hosting. The open connection is
itself the ad's keep-alive: the server refreshes the ad's TTL while the client is
connected, so there is no periodic heartbeat. On a brief drop the ad survives a
grace window, and the plugin resumes it by id on reconnect. If the grace window
lapses first, the server reports the ad `gone`. Reconnects use jittered backoff.

Messages are JSON frames with a `type`. Client-to-server frames:

| Frame | Payload | Purpose |
|-------|---------|---------|
| `subscribe` | `activity?` | Start receiving the live list, optionally scoped to one activity id |
| `unsubscribe` | — | Stop receiving the list (the socket stays up for hosting) |
| `host` | `request`, `key` | Advertise a new party |
| `update` | `id`, `key`, `patch` | Change fields on the hosted ad (live occupancy, or a host edit) |
| `unhost` | `id`, `key` | Close the ad |
| `resume` | `id`, `key` | Reclaim the ad by id+key after a reconnect |
| `getByCode` | `code` | Look up one party by invite code |
| `getByHost` | `host` | Look up the ad a player is hosting (used to rejoin after a restart) |

Server-to-client frames:

| Frame | Payload | Meaning |
|-------|---------|---------|
| `snapshot` | `parties[]` | The full current list, sent on (re)subscribe |
| `created` / `updated` | `party` | A single party appeared or changed |
| `removed` | `id` | A party dropped off the list |
| `batch` | `created[]`, `updated[]`, `removed[]` | One tick's worth of changes applied together |
| `hosted` | `party` | Ack of `host`, carrying the server-assigned id |
| `gone` | `id` | The ad's grace window lapsed before a resume |
| `error` | `detail` | A host action was rejected |
| `byCode` / `byHost` | `id`, `party` | Reply to a one-shot lookup |

While the Search tab is visible the plugin subscribes and renders the snapshot
plus deltas, so list load is driven by changes rather than by user count.
Subscribing with an activity scopes the server's fan-out to just the matching
ads. In a `batch`, `created` entries carry whole parties while `updated` entries
are `PartyDelta`s — only the fields that changed, plus the id — merged onto the
party the client already holds (see [`PartyDelta`](src/main/java/net/osparty/model/PartyDelta.java)).
Off the tab the plugin unsubscribes but the connection stays up.

### Host authentication

So that another client can't hijack or close someone else's ad, host-only changes
are gated by a per-party secret. On create the plugin mints a random UUID and
sends it as the `key` on the `host` frame. The server stores it in the party's
session — it's never returned in any response — and requires the same key on that
party's `update`, `unhost` and `resume`. A wrong or missing key is rejected. The
plugin persists the key locally so it can keep managing the ad after a client
restart.

`PartyRequest` (the `host` frame's `request`):

```json
{ "activity": "tob", "host": "Zezima", "description": "...", "capacity": 3,
  "world": "420", "minKillCount": 500, "minHardModeKillCount": 50,
  "passphrase": "wine-of-zamorak-…",
  "privateParty": false, "lootRule": "SPLIT", "ironmanOnly": false,
  "hostAccountType": "NORMAL", "hardMode": false, "invocation": 0,
  "requiredRoles": ["tobmelee", "tobranged", "tobnfrz"], "hostRole": "tobmelee",
  "learner": false, "teacher": false }
```

`Party` (adds a server-generated `inviteCode` and the live `layout`/`neededRoles`):

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

Two kinds of `update` patch travel over the socket:

- The **keep-alive patch** carries only live fields (size, world, layout, needed
  roles) and is deduped, so it's sent only when one of them actually changes.
- A **host edit** ([`PartyEditRequest`](src/main/java/net/osparty/model/PartyEditRequest.java))
  carries every editable field, so the host can both set and clear values (an
  empty description, a zeroed minimum KC). This is what the Edit party button
  sends.

Activity ids are the `id` values in `Activity.java` (`cox`, `tob`, `toa`, and so
on). `minHardModeKillCount` only means something for activities with a harder
variant (`hardModeLabel` in `Activity.java`: CoX → CM, ToB → HM, ToA → Expert).
The host key never appears in a `Party`.

## Live party (peer-to-peer)

Once you create or join an ad, the actual party runs over RuneLite's party
network, keyed by the ad's passphrase.

- **Roster and live data** — every member broadcasts a `PlayerUpdate` (equipment,
  inventory, combat stats, chosen role, learner mark) that everyone renders on the
  Current tab. The host broadcasts a `PartyStateMessage`: the authoritative
  admitted roster plus the rules.
- **Host management** — applicants who join the room are pending until the host
  admits them, so the host sees their real gear, stats and role before deciding.
  Decline and Kick send a `MemberCommand` the target honours by leaving, and
  capacity is enforced by the host. Admitting or declining from the side panel
  also dismisses the in-game chatbox prompt for that applicant.
- **Trust** — enforcement is cooperative, not server-enforced; a modified client
  could ignore it. This is the same trust model as the rest of RuneLite's party
  network.

### Applying and joining

- You're in at most one party at a time; applying elsewhere leaves the current
  one first.
- Leaving starts a 30-second cooldown on that party before you can re-apply
  (client-side, to curb spam).
- After joining you're pending until the host admits you. You show up on the
  host's Current tab with your live gear, stats and role for them to vet.
- While in a party, a banner shows whether you're in the host's friends chat and
  on the host's world, both derived from read-only client state and refreshed each
  second. The plugin never joins the FC or hops for you (that would be disallowed
  automation); it only reports the status.

### Hosting a party

Creating an ad opens the live room and switches the Current tab to a manage view:
the roster (host plus admitted members), Pending applicants with Admit and
Decline, per-member Kick, and Disband. When an applicant joins, the host gets a
chatbox ping and an in-game overlay of their combat stats and role; admitting adds
them to the roster, subject to capacity. The host can share the room passphrase
shown in the panel to invite people directly.

Edit party opens the Create form pre-filled with the ad's current settings.
Everything except the activity can be changed (the activity keys the live room and
the role/difficulty model, so it stays locked), and capacity can't be dropped
below the number of people already in the party. Saving pushes the edit over the
socket; the refreshed ad comes back on the live list, and joined members get an
updated roster broadcast.

## Build

`OSPartyPluginTest.main` launches a dev client with the plugin loaded for local
testing. Add `-ea -Dosparty.apiUrl=…` to point it at a local listing service.

> Requires JDK 11 to match the RuneLite client. The Gradle wrapper resolves the
> `latest.release` RuneLite client from `https://repo.runelite.net`.
