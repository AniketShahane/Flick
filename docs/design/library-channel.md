# Flick library channel v1 — normative contract

Status: **approved 2026-11-19**. This is the binding contract for the TV library
feature: the TV shows the phone's video library, and a pick on the TV asks the phone to cast.
Both modules implement exactly this; the fixtures in §11 are byte-for-byte test inputs.

## 1. Scope and the one rule that shapes it

The library channel is a **second WebSocket**, separate from control v2. Control v2
(`docs/design/control-channel.md`) is not changed in any way: no frame, field, capability,
timeout or fixture moves. That separation is the point, not a convenience:

- Control v2's capability list is frozen inside the resume HMAC transcript. Growing it would
  break resume and re-pairing during version skew. A separate route does not touch it, so the
  two apps may be updated in **either order** and nobody re-pairs.
- Control v2 closes its socket on any unknown or malformed frame. A library bug carried on that
  socket could drop a film mid-play. On its own socket the worst case is a library that
  disappears until the next connect.
- Catalog and art traffic never queues ahead of a `pause` or `state` frame.

What the TV gains is display data and exactly **one** action: asking the phone to cast an item
it listed. The phone remains the only party that decides what is served, mints cast IDs and
tokens, and runs the cast through its existing control-v2 path. The TV never receives a file
path, content URI, media URL or token on this channel.

## 2. Transport

- Endpoint: `ws://<tv-host>:<control-port>/library` on the TV's existing control server.
- The TV applies the same gates as `/control` before reading a frame: binding published, exact
  `Host` pin, private-IPv4 peer. It has its own pre-auth permit pool (max 2 concurrent, 1 per
  peer); it never draws on `/control`'s.
- Frame size: at most 16 KiB (16384 bytes) per frame in both directions. This is the server's
  existing WebSocket `maxFrameSize`, which is engine-wide; it is not raised.
- Text frames: one strict UTF-8 JSON object, the same strict reader as control v2 (no duplicate
  keys, no non-finite numbers, exact types). Every frame has `"t"` and `"v":1`.
- Binary frames: only phone → TV, only after authentication, only for thumbnails (§6.4).
- An old TV has no `/library` route; the upgrade fails with HTTP 404. The phone then treats
  that TV as library-unsupported for the rest of the process and does not retry until the
  next control-v2 authentication after an app restart.

## 3. When the phone opens and closes it

- The phone opens `/library` only when all hold: control v2 is authenticated to that TV
  (so the phone knows the verified endpoint and its own `peerIp`), the phone setting **Show my
  library on my TV** is on, media access is granted, and that TV is not marked unsupported.
- It dials exactly the endpoint control v2 last verified. It never uses an NSD candidate here.
- **Connect on open.** Each time the phone's activity comes to the foreground, the phone runs the
  silent control-v2 resume (the knock's, §13.3, with no knock host) to the last TV when all
  hold: the setting is on, a verified pairing exists (the last TV, not `needsRepair`), media
  access is granted, control v2 is disconnected (not connected and not dialing), no cast is live,
  queued, starting or being reattached, no dial, resume or block wait is running, no pair sheet or
  scanned-code card is open, and no such resume started in the last 15 s (`elapsedRealtime`).
  When foregrounding finds a held control line dead and retires it, the same check runs again.
  Like a knock's resume it never marks the pairing for repair, never moves the screen and never
  shows an error; a dial the person starts supersedes it, and so does tapping a TV that is not
  yet paired (its code sheet would otherwise sit on that resume's `CONNECTING`). The Connect
  screen's confirm haptic follows how the connection was made: when each dial begins, the phone
  records whether it is a silent resume (a knock's, connect on open's or the prompt's) or a name
  refresh, and reaching CONNECTED buzzes only when it is neither. No silent resume buzzes, and a
  dismissed sheet or a cancelled pairing cannot make a later one buzz. Once control authenticates, the
  library opens by the first rule above. So a TV's idle text "open Flick on your phone" is true.
- It closes `/library` whenever control v2 loses authentication, the setting is turned off,
  media access is revoked, or the TV is forgotten; it reopens after the next control-v2
  authentication. The phone therefore needs no liveness probe of its own on the library socket;
  its Ktor client sets no ping interval. The TV does: it pings each authenticated `/library`
  session every 60 s and closes it when a pong is 20 s late, so a phone gone without a close
  (a Wi-Fi drop, a killed process) stops counting as linked and holding knocks off (§13.4). The
  phone's Ktor client answers those pings itself. Every close above is a Close frame from the
  phone, which the TV reads as deliberate: the link was **closed**, not **lost**, and it owes no
  knock for it (§13.4). Only a ping timeout or a socket that ends without a Close frame is a lost
  link.
- **Opting out is said, not implied.** Before closing because the setting was turned off or
  media access was revoked, the phone sends an empty full catalog
  (`catBegin count:0 parts:0`, `catEnd`). A TV holding an empty catalog shows no library, so
  the library leaves the TV at once instead of lingering as a lost link.
- Reconnect backoff after a failed library dial while control is up: 2 s, 5 s, 15 s, then 60 s
  steady. It resets only after a socket has stayed authenticated for 30 s, so a TV that accepts
  and then drops the socket cannot pull the phone into a tight redial loop.
- The phone bounds its outgoing queue; on overflow it closes the library socket (the TV does the
  same at 512 queued frames).

## 4. Handshake (mutual proof, six-second deadline)

The pairing key authenticates both ends. The transcript is domain-separated from control v2, so
a library proof can never be replayed as a control proof or the reverse.

```json
{"t":"libHello","v":1,"tvId":"<22>","keyId":"<22>","clientNonce":"<22>"}
{"t":"libChallenge","v":1,"tvId":"<22>","keyId":"<22>","clientNonce":"<echo>","serverNonce":"<22>","peerIp":"<phone-ipv4>","serverHost":"<tv-ipv4>","serverPort":42421}
{"t":"libProof","v":1,"clientNonce":"<same>","serverNonce":"<same>","proof":"<43>"}
{"t":"libReady","v":1,"serverNonce":"<same>","proof":"<43>"}
{"t":"libDenied","v":1}
```

1. Phone sends `libHello` with a fresh 128-bit nonce.
2. TV looks up exactly `(tvId,keyId)` in its pairing store. Unknown → `libDenied`, close 1008.
   Otherwise it answers `libChallenge` with a fresh server nonce and the observed `peerIp` and
   bound `serverHost`/`serverPort`.
3. Phone verifies the echoes, that `serverHost`/`serverPort` equal the endpoint it dialed, and
   that `peerIp` equals the `peerIp` of its authenticated control session. Any mismatch: close,
   send nothing else. Otherwise it sends `libProof` with the client proof.
4. TV consumes the challenge (one proof per challenge, consumed before validation) and verifies
   in constant time. Failure → `libDenied`, close 1008. Success → `libReady` with the server
   proof. The phone verifies the server proof in constant time before sending any catalog byte.

HMAC-SHA256 over UTF-8, each field as a four-byte unsigned big-endian length then its bytes:

```text
Flick-Library-V1
client            (server proof: "server")
1
tvId
keyId
clientNonce
serverNonce
peerIp
serverHost
serverPort        (canonical decimal)
```

A library denial never marks a pairing `needsRepair`, never deletes or changes a key, and
never touches control v2 state. **Forget all phones** / forgetting one phone on the TV closes
that phone's library socket together with its control lease.

## 5. The catalog

### 5.1 Item

```json
{"id":"nTbULXb50u0","name":"The.Lantern.Coast.S02E04.2160p.mkv","title":"The Lantern Coast S02E04",
 "sortKey":"the lantern coast s02e04","find":"thelanterncoasts02e042160pmkv",
 "kind":"episode","show":"The Lantern Coast","showKey":"tv:91234","s":2,"e":4,
 "year":2022,"durMs":3130000,"added":1795000000,"folder":"Movies/Shows/Lantern Coast/",
 "w":3840,"h":2160,"hdr":"dv","resumeMs":1260000,"playedAt":1795100000,
 "art":{"tmdb":91234,"tv":true,"poster":"/a.jpg","backdrop":"/b.jpg","logo":"/c.png"}}
```

| Field | Rule |
|---|---|
| `id` | 11 base64url chars: first 8 bytes of SHA-256(`"Flick-Library-Item-V1\|" + PlaybackMediaFingerprint`). Stable while the file is unchanged. Unique within a catalog. |
| `name` | the file name on disk, ≤ 255 code points, display-normalized. Search needs it. |
| `title` | the tile's title exactly as the phone shows it (`VideoNames.parse(name).displayName`), ≤ 200. |
| `sortKey` | `librarySortTitle(name)` from the phone (folded). The TV compares it with the ported `LibraryNameOrder`. |
| `find` | the phone's search entry text for this row (`LibrarySearchPolicy` entry: folded raw + displayed, spaces removed). |
| `kind` | `film` (TMDB movie match), `episode` (season and episode parsed), `video` (anything else). |
| `show`, `showKey` | episodes only. `showKey` is `tv:<tmdbId>` when matched, else `name:<folded show title>`. Episodes group by `showKey`. |
| `s`, `e` | episodes only, integers 0..999 and 0..9999. |
| `year` | optional, 1870..2100. |
| `durMs` | 0..604800000; 0 means unknown, as on the phone. |
| `added` | MediaStore date added, epoch seconds; 0 means unknown. Drives Recently added. |
| `folder` | optional `relativePath`, ≤ 512, as MediaStore reports it. Drives the folder tree. |
| `w`, `h` | pixels, 0 when unknown. |
| `hdr` | optional `dv` \| `hdr10` \| `sdr`. Omitted means not probed or not known (a failed probe included), never "SDR". |
| `resumeMs` | optional; present only when the phone's `PlaybackResumePolicy` would resume. |
| `playedAt` | optional; when the checkpoint behind `resumeMs` was written, epoch seconds, integer 1..4102444800. Present only with `resumeMs`. |
| `watched` | optional `true` when the phone counts the item watched (§9). |
| `watchedAt` | optional; when it became watched, epoch seconds, integer 1..4102444800. Present only with `watched`. |
| `blocked` | optional `unplayable` \| `silent`, the phone's existing marks. |
| `art` | optional object: `tmdb` (optional positive id), `tv` (optional boolean, the TMDB catalogue), and `poster`, `backdrop`, `logo`, each optional but at least one present. TMDB paths only, each matching `^/[A-Za-z0-9_-]+\.(jpg\|jpeg\|png\|webp)$` (the phone's own `FilmPosterPolicy` rule). The TV builds `https://image.tmdb.org/t/p/<size><path>` itself and never fetches a URL it was sent. |

Fields appear in the table's order, and an absent optional field is omitted rather than sent
empty. `playedAt` and `watchedAt` were added within v1: a phone that predates them sends neither,
and the TV falls back to ordering by `(s,e)` (§10). Unknown fields inside an item are ignored, so
a later phone can add display fields without a coordinated release. Missing required fields or a wrong type reject the whole part.

### 5.2 Delivery

Phone → TV:

```json
{"t":"catBegin","v":1,"rev":"<22>","count":1077,"parts":24}
{"t":"catPart","v":1,"rev":"<22>","index":0,"items":[ … ]}
{"t":"catEnd","v":1,"rev":"<22>"}
{"t":"catPatch","v":1,"base":"<22>","rev":"<22>","upsert":[ … ],"remove":["<id>"]}
```

TV → phone:

```json
{"t":"catWant","v":1,"have":"<22>"}
{"t":"catWant","v":1}
```

- `rev` is the first 16 bytes of SHA-256 over the UTF-8 bytes of `[` + the items' canonical
  JSON joined by `,` + `]`, base64url. Canonical JSON is fields in the order of the §5.1 table,
  absent optionals omitted, no whitespace, strings escaping only `"`, `\\` and control
  characters (`\n`, `\r`, `\t`, otherwise `\u00XX`).
- A `catPatch` result keeps the TV's order rule: upserted ids not already held go first in
  upsert order, then the held items in their old order. The phone sends a patch only when that
  rule reproduces its own order exactly; otherwise it sends the full catalog.
- After `libReady` the TV sends `catWant` with the rev it still holds in memory, if any. The
  phone answers nothing if it matches, a `catPatch` if it can build one from that base with at
  most 64 changed items, otherwise a full `catBegin` … `catEnd`.
- The phone also pushes on its own whenever the library, a resume point, a watched mark, a
  blocked mark, a probed HDR value or TMDB art changes, debounced by 2 s. **While a cast is
  live it neither rebuilds nor pushes** (the progress store writes every few seconds during a
  film); it remembers that something changed and pushes once after the cast ends. A change that
  arrives while a push is being built or sent is not lost: the phone checks again after each
  delivery.
- The phone packs items greedily into `catPart` frames that serialize to ≤ 16 KiB. Parts are
  sent in index order. The TV assembles into a staging list and swaps it in atomically only on
  a `catEnd` whose `rev` and part count match `catBegin`. A new `catBegin` discards staging.
- A `catPatch` applies only when `base` equals the TV's current rev; otherwise the TV sends
  `catWant` with no `have` and the phone sends a full catalog.
- Limits: `count` ≤ 20000, `parts` ≤ 4000. Beyond either the TV closes the library socket.

The TV keeps the catalog **in memory only**, keyed by the phone's `keyId`. It is dropped when
that phone is forgotten or when the TV process ends. It is kept across a library reconnect, so
a Wi-Fi blip does not blank the screen. What the TV *shows* is narrower than what it keeps:

- a phone whose catalog is empty is not shown (§3, opting out);
- a phone whose socket has been lost for more than 10 minutes is hidden, while its catalog and
  `rev` stay in memory as the patch base for its next `catWant`; a reconnect shows it again;
- with two phones connected, the TV shows the one holding the control lease, else the most
  recently connected, and never switches while an Up next card is up or a `play` is waiting.

## 6. Requests

### 6.1 Play — the one action

```json
{"t":"play","v":1,"req":"<22>","id":"nTbULXb50u0","from":"resume"}
{"t":"playAck","v":1,"req":"<22>","ok":true}
{"t":"playAck","v":1,"req":"<22>","ok":false,"code":"missing"}
```

- `from` is exactly `resume` or `start`. `resume` with no resume point plays from the start.
- The phone resolves `id` against its **current** catalog only. It then runs the same entry a
  tap on the phone runs (`FlickController.flickToTv` for `resume`, `startOver` for `start`),
  so resume position, remembered subtitle, audio delay, link repairs and the startup retry are
  identical to a phone-started cast. Ownership, busy and every control-v2 rule apply unchanged.
- `playAck ok:true` means the phone accepted and has begun that path; success is still the
  control-v2 `loadMedia` → `loadReady` the TV already handles. `ok:false` codes: `missing`
  (not in the current catalog), `disabled` (setting off), `busy` (a phone-side cast start is
  already in flight), `unavailable` (the file can no longer be read), `background` (Android
  would refuse the cast's foreground service: Flick is not visible and not exempt from battery
  optimisation, see §8). A code the TV does not know reads as `unavailable`.
- The phone answers within 2 s. The TV shows "Asking your phone…" on the button and gives up
  after 8 s with no `playAck`, or 30 s after `ok:true` with no `loadMedia`; nothing is half
  started because the TV started nothing.
- At most one outstanding `play` per socket; a second while one is outstanding is ignored. The
  TV shows its "Asking your phone…" status on every library page while one is outstanding, so a
  second press visibly waits. A `playAck` counts only from the phone the `play` was sent to.

### 6.2 Detail

```json
{"t":"detailWant","v":1,"id":"<id>"}
{"t":"detail","v":1,"id":"<id>","overview":"…","tagline":"…","hdr":"hdr10","subtitle":"English"}
```

From the phone's existing `FilmDetailsStore` (films and shows, by TMDB id) and `MediaProbe`.
Every field but `id` is optional; `overview` ≤ 1200, `tagline` ≤ 200, `subtitle` (the label of
the subtitle the phone will attach) ≤ 200. Sent at most once per `detailWant`.

The phone works on at most 4 `detailWant` and 4 `seasonWant` at a time (more wait for a slot)
and accepts at most 20 of each per 10 s; a request beyond that rate is dropped with no reply.
The TV therefore never waits on an answer: a request unanswered after 30 s may be sent again
when the screen that needs it is shown again, and every request a showing screen needs is sent
again after each new authentication.

### 6.3 Season

```json
{"t":"seasonWant","v":1,"showKey":"tv:91234","s":2}
{"t":"season","v":1,"showKey":"tv:91234","s":2,"part":0,"parts":1,"episodes":[{"e":4,"name":"The Quiet Tide","overview":"…","still":"/d.jpg"}]}
```

Only for `tv:` show keys. From TMDB `/tv/{id}/season/{n}`, looked up and cached by the phone
for 7 days, at most 50 seasons (same key handling and network rules as `FilmPosters`).
`overview` ≤ 400 code points; parts split so each frame stays ≤ 16 KiB; `parts` ≤ 4000, and the
TV keeps at most 4000 episodes per season. No TMDB data → `episodes` is empty and the TV falls
back to file titles and phone thumbnails.

### 6.4 Thumbnail

```json
{"t":"thumbWant","v":1,"id":"<id>"}
{"t":"thumbMiss","v":1,"id":"<id>"}
```

Answer is one **binary** frame: ASCII `FLT1`, the 11-character `id`, then a baseline JPEG of
the phone's existing still for that file (the same `VideoStills` pipeline and one-colour refusal
the phone tiles use), scaled to 384×216 crop and re-encoded until the whole frame ≤ 16 KiB. The
TV requests thumbnails only for tiles on screen that have no TMDB art, keeps at most 6
outstanding and sends at most 60 `thumbWant` per 10 s, and caches the newest 120 in memory. A
request with no answer in time goes back in the queue (at most 3 attempts per ask), a late
frame for an id still wanted is accepted, and a tile whose thumbnail is missing asks again. The phone
serves at most 6 at a time and drops `thumbWant` beyond 60 per 10 s.

### 6.5 Refresh and browsing

```json
{"t":"refresh","v":1}
{"t":"browsing","v":1}
```

`refresh` = the phone's pull-to-refresh (`refreshMediaLibrary`); at most once per 10 s.
`browsing` restarts the phone's ready window (§8). The TV sends it once when a library screen
appears and then only on a remote key press while one is showing, at most once per 60 s. It is
never sent on a timer: a library left open on an idle TV must not hold the phone's foreground
service open for ever.

### 6.6 Casting

```json
{"t":"casting","v":1,"castId":"<22>","id":"<11>"}
```

Sent by the phone whenever it sends a control-v2 `loadMedia` for a file that is in its current
catalog, whoever started the cast. The TV keeps the last few `castId` → `id` pairs, so the Up
next card (§10) knows which item a cast is playing even when the phone started it. Frames on
the two sockets are not ordered against each other; the TV matches by `castId`. When a library
socket opens while a cast is live, the phone sends that cast's `casting` again after its first
catalog delivery. A `casting` that arrives after the TV has already seen that cast end still
runs the Up next decision.

### 6.7 Doorbell address

```json
{"t":"knockAt","v":1,"port":47670}
```

Phone → TV. Where the phone's doorbell (§13) listens now: `port` is an integer 1..65535, on
the address the TV sees this library socket come from. The phone sends it right after
`libReady` whenever its listener is bound, and again on the same socket whenever the bound port
changes; it sends nothing when the listener stops. A TV from before the doorbell ignores it as
an unknown type (§7), so the two apps may still be updated in either order. A `knockAt` with a
missing or out-of-range `port`, or a wrong `v`, is malformed (§7).

## 7. Errors and unknowns

- A malformed frame of a known type, an oversize frame, or a binary frame from the TV closes
  the **library** socket with 1008. Control v2 is unaffected.
- Unknown fields in a known frame are ignored, as inside items.
- An unknown `t` is ignored (and logged by type only). This is a deliberate difference from
  control v2: the library is a display surface whose only action, `play`, is validated
  strictly, so ignoring an unknown display frame lets later versions add one without a
  coordinated release.
- A known `t` with `v` other than `1` closes the library socket; an unknown `t` is ignored
  whatever its `v`.
- Before authentication the TV closes the socket on the third ignored or unknown frame, as
  `/control` does.

## 8. The phone's ready window

Android 14+ freezes a cached app ten seconds after it becomes cached and terminates its TCP
sockets. Without a foreground service the phone would vanish from the TV seconds after a film
stops while it is in a pocket — the moment the library is most needed.

- When a cast ends or stops, or the app goes to the background, while control v2 to a TV is
  authenticated and the library socket is open, the phone starts `TvReadyService`, a
  `connectedDevice` foreground service with a low-importance notification ("Ready for
  <TV name>", action Stop). Its runtime prerequisite is met by the `CHANGE_WIFI_STATE`
  permission the phone already declares. A refused start
  (`ForegroundServiceStartNotAllowedException`) is logged and the window simply does not open.
- It holds **no wake lock and no Wi-Fi lock**. It ends 30 minutes after the last of: the cast
  ending, the app leaving the foreground, a `browsing` or any request frame from the TV. It
  also ends when control v2 is lost and not regained within 2 minutes, on Stop, or at once when
  the setting is turned off (whatever the state of control v2). A cast starting supersedes it
  (the cast service takes over). When a cast stops, the phone decides on the cast itself being
  over, not on the media server's status, and starts the window while the cast service is still
  in the foreground.
- Android lets an app start a foreground service from the background only under listed
  exemptions (developer.android.com, "Restrictions on starting a foreground service from the
  background"). The documented one that applies is "the user turns off battery optimizations
  for your app", which Flick's hardening layer already asks for. The docs do not list "a
  foreground service is already running"; AOSP does allow a process already in the
  foreground-service state to start another (`REASON_PROC_STATE_FGS`), and Flick's existing
  cast re-target path relies on that through `foregroundStartAllowed`. So the phone starts
  `TvReadyService`, and a TV-requested `CastServerService`, when any of these holds: its
  activity is visible, `PowerManager.isIgnoringBatteryOptimizations` is true, the SDK is below
  31, or `foregroundStartAllowed(sdk, processImportance)` holds. Otherwise the window does not
  open, and a `play` is answered `ok:false` `background` so the TV can say what to change. A
  start Android refuses anyway (`ForegroundServiceStartNotAllowedException`) is caught where it
  happens and leaves the phone as it was before the request.

### 8.1 Ready mode

When the window would only lapse because nobody is watching, the phone keeps it — and with it
the doorbell (§13) — for as long as all of these hold:

- **Show my library on my TV** is on;
- a verified pairing exists (the last TV, not marked `needsRepair`);
- media access is granted (checked against the platform's grant, not against whether the app
  has read the library yet in this process);
- the app is exempt from battery optimisation (`PowerManager.isIgnoringBatteryOptimizations`);
- the person has not pressed **Stop** on the notification since Flick was last opened.

Without the exemption the phone keeps the 30-minute window above exactly: a background start
would be refused anyway. In ready mode:

- `TvReadyService` runs with no time limit, on its own channel (`IMPORTANCE_LOW`, no badge):
  "Ready for <TV name>" / "Your TV can wake Flick", action Stop. LOW is as quiet as a
  foreground service's notification gets: for one posted on a channel at `IMPORTANCE_MIN` or
  `NONE` the system raises the channel to LOW unless the person set its importance themselves
  (AOSP `NotificationManagerService`). So it shows as a silent notification, and because the
  channel has its own id the person can long-press it to minimise or hide just this one in
  system settings without touching the 30-minute window's channel. It is `START_STICKY`; a restart with no
  intent starts it again when ready mode still holds and stops it otherwise. It opens without a
  control or library socket, on the same triggers as the window (the app leaving the foreground,
  a cast ending), and also on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`; each only when ready
  mode holds. A cast still supersedes it while the cast's own service runs.
- Losing control v2 does not end it.
- **Idle hang-up.** With no cast, the activity not visible, and no TV request, `browsing`, knock
  or cast end for 30 minutes (the window's own clock), the phone closes the library socket and
  then the control-v2 socket quietly: no screen, no haptic, no redial. Control's 15-s pings stop;
  the service and the doorbell stay, and nothing is scheduled again until the next activity. A
  cast, a connection attempt actually running (a dial, a resume including a knock's, or a block
  wait) or a visible activity at that moment restarts the clock instead; an open pair sheet or
  scanned-code card does not.
- Pressing Stop ends the service and holds ready mode back until Flick is next opened, across
  reboots. A setting turned off, a pairing forgotten or marked for repair, media access revoked
  or the exemption withdrawn drops it back to the 30-minute window rules. The exemption has no
  callback, so it is re-read at each trigger (the start triggers above) and whenever a pending
  window deadline falls due. After an idle hang-up no deadline is pending, so the next re-read
  waits for the next trigger, or for the deadline a later knock or TV request schedules.
- The service holds no wake lock, Wi-Fi lock or multicast lock while idle, and no timer runs
  beyond the window's own deadline. The only wake lock is the knock's (§13).

## 9. Watched ledger (phone)

The phone records an item as watched when its cast reaches `ENDED`, or stops inside the
**finish zone**: with a known duration, no more than 30 s (`PlaybackResumePolicy.END_WINDOW_MS`)
or 5 % of it left, whichever is longer (`PlaybackResumePolicy.inFinishZone`). The same zone
offers no resume and clears the checkpoint, so stopping during the end credits finishes an
episode. Stored by fingerprint with the time, newest 5000 kept, excluded from backup like the
progress store; that time is `watchedAt`.

A checkpoint written before the zone widened can sit inside it. When the ledger has no record,
such a checkpoint makes the item `watched` with `watchedAt` set to the checkpoint's time, derived
at each catalog build. When a cast of that item reaches its first frame, the phone writes that
record to the ledger once, before the replay can clear the checkpoint, so the mark survives a
rewatch. The ledger feeds `watched` and the
TV's Next episodes row only; it changes nothing on the phone's own screens.

## 10. TV behaviour that depends on the contract

- **When an episode was last touched:** `watchedAt` if it is watched, and `playedAt` if it has a
  `resumeMs` of at least 2 minutes; the later of the two, or none. A play under 2 minutes never
  moves it. A show's **anchor** is its episode touched last (a tie goes to the later episode by
  `(s,e)`). A show in which no episode has a time — a phone that predates the fields — is in
  **legacy** mode: its anchor is the last watched episode by `(s,e)`, and every rule below behaves
  as it did before the fields existed.
- **Next episodes:** a show has none when it has no anchor or the anchor has a `resumeMs` (it is
  in Continue watching). Otherwise the candidate is the first episode after the anchor whose
  `(s,e)` differs and that is not watched, so a rewatch skips episodes already seen and a finished
  series stays finished. If that candidate has a `resumeMs` the show has none (a later episode is
  never offered in its place). The row is ordered by the anchor's time, newest first; shows
  without one follow in the previous order.
- **Play on a show:** the anchor if it has a `resumeMs`; else the candidate above (resumed if it
  has a `resumeMs`); else the first unwatched episode; else the first. Legacy shows keep the
  previous rule.
- **Continue watching** holds items with `resumeMs`, newest `playedAt` first, items without one
  after in the previous order. An episode appears only if it is its show's anchor or the episode
  Play on the show would resume, so a half-watched episode of a show the viewer has moved past is
  hidden. Legacy shows keep the previous rule.
- **Up next:** when a cast whose `casting` frame (§6.6) names an episode, with a next one in the
  same `showKey`, reaches `ended`, the TV shows the Up next card over the full-size film; its
  `play` goes to the phone that sent that `casting`. It also offers the card earlier, at the
  credits: when the remaining time, playing or paused, is no more than the show's **credits
  lead**. The learned lead is the median of the show's last 3 samples of "time left when the
  viewer left the episode early", clamped to 20 s..3 min, or 30 s with no samples. The effective
  lead is capped to the phone's finish zone (§9): min(learned lead, max(20 s, zone − 3 s)), with
  zone = max(30 s, 5 % of the duration), so a skip from the card always lands inside the zone and
  the phone counts the episode watched. When none of the casting phone's items carries
  `playedAt` or `watchedAt` (a phone from before those fields, whose zone is the last 30 s only),
  the cap is 27 s. Only a viewer exit with no credits card showing is a sample: a `stop` (from the
  phone or the TV remote), a replacement by a new cast, or Play now, before `ended`; the
  remaining time is kept only when 5 s..3 min was left and at least 85 % had played. An exit
  while the credits card is up says nothing new about the credits and is not a sample; nor are
  errors, link loss, teardown, backgrounding, an autoplay or a natural `ended`. (A phone-side
  fault that makes the phone send `stop` reads as a stop.) Samples are kept on
  the TV under a hash of the `showKey` (no title stored), newest 200 shows, not backed up. During
  the credits the card offers only Play now (no Back to library) and leaves Left/Right to
  seeking: OK plays now, Back hides it. It clears if the viewer seeks back past the lead plus
  10 s, or past the cap if that is sooner (unless its `play` is already outstanding); once hidden the credits do not offer it again
  for that cast, but `ended` still does, with the card as before. With autoplay on (TV setting, default on)
  it sends `play` with `from:"start"` after 10 s. The card shows the play's progress and the
  specific reason if the phone refuses, and Play now retries. After three consecutive autoplays
  with no remote key press it shows "Still watching?" instead. The film is never scaled.
- Sort, folder scope and search run on the TV over `sortKey`, `added`, `durMs`, `folder` and
  `find`, with the phone's exact semantics (ported `LibraryNameOrder`, stable sort over the
  phone's newest-first order, unknown duration last in Shortest, every query word a substring
  of `find` after the ported `FoldedText.fold` with spaces removed).

## 11. Fixtures

Key `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8`, tvId `EREREREREREREREREREREQ`,
keyId `IiIiIiIiIiIiIiIiIiIiIg`, clientNonce `MzMzMzMzMzMzMzMzMzMzMw`, serverNonce
`RERERERERERERERERERERA`, peerIp `192.168.1.20`, serverHost `192.168.1.30`, serverPort `42421`:

- client proof `G9mhvmEtExjWSAoOuss3RU3YbAMLeM4-qrYvtUjebGg`
- server proof `Dl-vmcgpfnTbgSgDKv6NNRa-OvjJKl4JWCh7CUg8j1Q`

Item id for fingerprint `AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA`: `nTbULXb50u0`.

A doorbell transcript (§13) for the same key, tvId and keyId, phoneNonce `MzMzMzMzMzMzMzMzMzMzMw`,
tvNonce `RERERERERERERERERERERA`, phoneHost `192.168.1.20`, phonePort `47670`:

- knock proof `DdZzClsQ82tZwVfbbFKIaLEbTklR8zZpwnZFxMX4_JA`

## 12. Privacy and logging

Titles, file names, folder names, TMDB paths, item ids and thumbnails never enter logs,
exceptions, notifications, diagnostics or the incident journal on either device. Logs carry
frame types, counts, byte sizes and timings only. Nothing in this channel is backed up.

The TV's learned credits lengths (§10) are stored in `flick_credits` keyed by a hash of the
`showKey`, never the key or a title, and that file is excluded from backup and device transfer.

The doorbell (§13) logs and journals outcomes only. The phone writes exactly one journal line per
valid knock, `knock outcome=<resume|resuming|linked|library|busy|cast_live|no_pairing|superseded|asked|declined>`
(`superseded`: a later knock arrived while this one's liveness check was still out; `asked`: the
prompt was posted; `declined`: the TV was told `declined`, §13.5), and one per silent resume
however it ends: `knock_resume`, `open_resume` (connect on open, §3) or `prompt_resume` (the
prompt's Connect), each `ok=<bool> reason=<ok|failed|superseded|commit> dials=<n>`
(`superseded`: a dial the person started, or another attempt, took over; `failed`: every
candidate refused or went unanswered). The prompt adds `prompt choice=<connect|later>`, with
`skipped=<no_pairing|cast_live|setting|linked|other_tv|busy|resuming>` when Connect started no
dial of its own (`resuming`: a silent resume to that TV was already dialing and took the
remembered host instead), and `prompt_unavailable` when a resume knock that would have asked
connected instead because the prompt could not be shown: the reply was `ask` and the post failed
or notifications went away, or the reply was plain in ask mode, with the activity hidden, because
notifications were unavailable already. The TV
journals one `knock_round trigger=<start|key|rebind> phones=<n> outcome=<answered|asked|declined|unreachable|no_hello|silent|no_key|no_lan|not_started> sweep=<none|missed|found>`
per round, with the best outcome across its phones (`sweep`: `none` when no port sweep ran,
`found` when the outcome came from a swept port, `missed` when a sweep ran and found nothing
better), and `knock_skip reason=<linked|no_target|no_lan|not_ready|gap>`
when a start round was owed but did not run, at most once per reason per STARTED period. The
TV's log line for the end of an authenticated `/library` session is
`close reason=<peer_closed|ping_timeout|dropped|cancelled|violation|superseded|forgotten|outbox_full|tv_closed>`
(receiver `LibraryClose.of`): `peer_closed` only for a Close frame from the phone,
`ping_timeout` when the 60-s ping went unanswered or the session settled close code 1011 (§3),
`dropped` for a socket that ended without a Close frame, `outbox_full` when the phone stopped
reading. A timeout is caught inside the session, never escaping as a handler failure. Refused and invalid knocks reach the log ring by
reason only (`rate`, `proof`, dropped connections); each of those three is logged at most once
a minute, the next such line carrying how many went unlogged, so one noisy device cannot flush
the 200-line ring. No peer or
local address, tvId, keyId, nonce or proof enters a log or the journal, on either device.

What each side stores for it:

- **Phone:** the **When your TV opens Flick** choice (§13.5), and the prompt's body-tap token in
  `flick_prompt_token`: 32 random bytes from `SecureRandom`, base64url, made once per install,
  excluded from backup and device transfer, never logged (§13.5). The knocking
  peer's address is held only in memory for the one resume it hints (§13.3), or while that knock's
  prompt is up (§13.5); "Not now" is held only in memory. A resume that succeeds updates the pairing's stored endpoint exactly as any
  resume does; a knock never writes to the pairing store itself.
- **TV:** per paired phone, the private IPv4 its last authenticated control-v2 or library
  connection came from and the port of its last `knockAt` (§13.4), in `flick_doorbell`, excluded from backup and
  device transfer, deleted when that phone is forgotten and pruned for phones no longer paired.

## 13. The doorbell (TV wakes the phone)

Only the phone can start a connection: it dials control v2, then `/library`. After the ready
window a pocketed phone is frozen and the TV can only say "open Flick on your phone". The
doorbell lets the TV ask instead: the phone in ready mode (§8.1) keeps a quiet listener in
`TvReadyService`, the TV knocks with a proof made from the pairing key, and the phone then runs
its ordinary, non-user control-v2 resume to the TV it already holds. Nothing in control v2 or in
the library handshake changes; the knock only decides *when* the phone dials.

### 13.1 Listener

- TCP, IPv4. The phone binds port **47670**, falling back through **47671..47679**, then an
  ephemeral port, on a Wi-Fi interface's private IPv4 address only (a `NetworkCallback` on
  Wi-Fi, without requiring internet). Each candidate socket sets `SO_REUSEADDR` before binding
  (the socket of a knock just answered sits in `TIME_WAIT` on the phone's side, and on Linux the
  option still never lets two listeners share a port); one that fails to bind is closed.
- **Where it binds.** Only on a Wi-Fi network whose own IPv4 subnet (address and prefix length
  from its `LinkProperties`) contains the stored host of at least one verified pairing (not
  `needsRepair`). This is a heuristic, not proof of being at home: any network that reuses the
  same private subnet (common router defaults such as 192.168.0.0/24 or 192.168.1.0/24)
  qualifies too, and there the listener greets private peers, rate-limited as below; a network
  on a different subnet never hears a greeting. The stored host may equal the phone's own
  address (DHCP swapped the two), and that still qualifies: it is the case the knock-host
  fallback (§13.3) exists for. The rule is asked again whenever a Wi-Fi network or its
  addresses change and whenever the pairings change (a pairing made, an endpoint committed, a
  pairing forgotten or marked for repair), so a TV newly paired on this network gets a listener
  and its `knockAt` without waiting for a Wi-Fi change. The phone tracks every Wi-Fi network that is up: the bound one stays while it
  still qualifies (rebinding if its address changed); when it is lost or stops qualifying, the
  listener moves to the first other network that does, so a make-before-break switch (Android
  12+) or a second Wi-Fi network never leaves nothing listening. With none, it unbinds. A
  failed bind is retried on the next network change. It reports the bound port in `knockAt`
  (§6.7). No discovery, no mDNS, no multicast lock: with the screen off the phone's APF drops
  multicast, so the TV wakes it by unicast to the address it last saw.
- A failed `accept` (descriptor exhaustion, an aborted connection) does not stop the listener:
  only a closed listening socket ends the loop; otherwise it waits 100 ms, doubling to at most
  1 s, and accepts again.
- Accepted only from a private IPv4 peer (10/8, 172.16/12, 192.168/16) on a private local
  address; anything else is closed at once.
- One handshake at a time; a **2-second** total deadline from accept, however the bytes dribble.
- Frames are one strict JSON object per line (the §2 reader: no duplicate keys, exact types),
  UTF-8, at most **512 bytes** before the terminating LF. Anything longer, not UTF-8, or not a
  valid frame closes the connection with nothing sent.
- **Admission:** per peer address, at most one handshake per 5 s and 6 per minute; across all
  peers, at most 30 per minute, all in real time (`SystemClock.elapsedRealtime`, which keeps
  counting through deep sleep). Counted when the phone greets a connection; connections over
  any limit are closed unanswered and count against nothing, so one busy device on the LAN
  cannot keep a paired TV out. A TV retry is therefore useful after a failed connect (never
  admitted), not after a refused proof.

### 13.2 Exchange

```json
{"t":"knockHello","v":1,"phoneNonce":"<22>"}
{"t":"knock","v":1,"tvId":"<22>","keyId":"<22>","phoneNonce":"<echo>","tvNonce":"<22>","proof":"<43>"}
{"t":"knockOk","v":1}
```

`knockOk` may carry one of two optional booleans (§13.5); `v` stays 1 and a receiver ignores
fields it does not know, so a TV from before them reads every form as the plain one, and a phone
from before them never sends them:

```json
{"t":"knockOk","v":1,"ask":true}
{"t":"knockOk","v":1,"declined":true}
```

The phone never sends both. A TV that reads both treats the reply as `declined`; a field that is
missing or not a boolean counts as false.

1. On accept the phone sends `knockHello` with a fresh 128-bit nonce (base64url, 22 chars).
2. The TV answers `knock`: its tvId, the keyId of this phone's pairing, the echoed nonce, its own
   fresh 128-bit nonce, and the proof.
3. The phone looks up exactly `(tvId,keyId)` in its pairing store (a pairing marked `needsRepair`
   does not count), checks the echo and that `tvNonce` differs from `phoneNonce`, and verifies
   the proof in constant time. Success: `knockOk`, then close. **Any failure: close, send
   nothing.** A failed knock never marks a pairing `needsRepair` and never changes a key.

The proof is HMAC-SHA256 with the pairing key over, each field as a four-byte unsigned
big-endian length then its UTF-8 bytes:

```text
Flick-Knock-V1
tv
1
tvId
keyId
phoneNonce
tvNonce
phoneHost         (the phone's local IPv4 of the accepted socket, i.e. the address the TV dialed)
phonePort         (canonical decimal)
```

The domain string separates it from control v2 and from the library handshake, so no proof
moves between the three. Binding `phoneHost`/`phonePort` stops a knock captured at one address
being replayed at another; the phone's fresh nonce stops it being replayed at the same one.
Fixture: §11.

### 13.3 What a valid knock does

The phone acts on the pairing. The knocking peer's address is used only as a hint behind the
stored endpoint (step 3), never written to the pairing store by the knock:

1. It takes a partial wake lock for at most **20 s** (a later knock restarts the 20 s). It is
   released when the TV is first sent a catalog on the library socket that follows (so the
   MediaStore read and the catalog build of a phone woken from sleep run under it); at once when
   the answer is `cast_live`, `busy`, `no_pairing`, `declined` or `asked` (§13.5), when the knock
   resume fails, or when the
   library is refused outright (setting off, no media access, a TV without the library); and
   otherwise by the 20-s cap. A library dial that keeps failing after control came up relies on
   the cap.
2. If nothing in this process has evaluated media access yet (only the activity does), it does
   so first, so the library can load.
3. A knock the phone replied `declined` to (§13.5) does nothing more (outcome `declined`).
   Otherwise: a TV knocks only when it holds no library socket from this phone, so a control line the phone
   believes up may be one a powered-off TV left behind (control's 15-s ping does not run while
   the phone sleeps). Whenever control v2 is authenticated, to any TV, and no cast is live, the
   phone first asks that line with an authenticated `ping` round trip — unless an earlier
   knock's resume is still dialing, or the activity is visible with control to another TV,
   where the answer below does not depend on it. Then, in order:
   - a live cast → nothing (outcome `cast_live`);
   - a silent resume still dialing the same TV (a knock's, connect on open's or the prompt's) →
     left to answer this knock too, keeping the wake lock (`resuming`). An open or prompt resume
     also takes this knock's address and dials it once, at the stored port, after its own
     candidates, unless it has dialed it already. While such a resume is running the phone
     replies plain, never `ask` or `declined` (§13.5). Dialing a different TV → `busy`;
   - control to that TV answered and the library socket looks open → that socket is dead, so it
     is closed and the library dialled again (`linked`);
   - control to that TV answered without a library socket → the library is dialled now,
     skipping the backoff (`library`);
   - a dial, pairing or block wait the person started → left alone (`busy`);
   - control to **another** paired TV answered, or the activity is visible with control to
     another TV whatever the ping said → that session is in use and is not taken over (`busy`;
     one lost pong must not switch the person's TV under them, so no ping is sent then);
   - otherwise, including a control line that did not answer, the non-user resume (`resume`):
     a control-v2 resume to the **stored, verified endpoint**, then, if that fails, to the
     knocking peer's address at the stored port (`ResumeCandidates`: a different host never
     goes ahead of the verified endpoint). A valid proof shows the knocker holds the pairing
     key, and control v2 authenticates the TV again, so this finds a TV whose DHCP address
     changed without trusting the knock. Failures are silent, never mark the pairing for repair
     and never move the screen; a resume that succeeds updates the stored endpoint as any
     resume does. On success the library opens through §3 as after any authentication. When the
     phone replied `ask`, this step asks its owner first instead (§13.5).
4. The visible screen never changes because of a knock.

Every valid knock also restarts the ready window's clock (§8.1).

Known limitation: `cast_live` and `busy` are decided after the phone has sent `knockOk`, so a TV
knocking a phone that is merely busy with another TV (or casting) sees no link within its grace
and may show its "couldn't wake" hint, though the phone is awake and well.

### 13.4 The TV's side

**What it remembers.** Per paired phone (by keyId), in SharedPreferences `flick_doorbell` as
`1|host|port[|proven]` (either address half empty while unknown): `host` is the private IPv4 of
that phone's last authenticated control-v2 or `/library` connection (the phone's doorbell listens
on the host its control comes from, so a DHCP move is learned without first needing a knock to
reach the old address), `port` its last `knockAt` (§6.7), and `proven` a recency stamp from one
sequence shared by the whole book, raised when either connection authenticates from that host
and the entry is not already the newest (absent means 0, so older
3-field values still load). An address belongs to the last pairing proven from it:
authenticating from a host deletes every other entry at that host. Excluded from backup and
device transfer; entries for phones no longer paired are pruned at start-up, and Forget / Forget
all delete them. Addresses, keyIds and titles are never logged.

**When it knocks.** Only while the TV app is STARTED, its control server is listening on the LAN,
no phone's `/library` socket is authenticated (the TV holds one controller at a time, so waking a
second phone would displace the first), and a phone has both halves of a stored address. A start
owed while a phone is linked is dropped (`knock_skip reason=linked`), unless the last link is
**lost** in the same STARTED period with no round run since: then that start is owed again, still
within the gaps. Lost means the 60-s `/library` ping went unanswered for 20 s (§3; a close code
of 1011 counts as that), the socket ended without a Close frame, or the TV's outgoing queue
overflowed because the phone stopped reading (`ping_timeout`, `dropped`, `outbox_full` in the
§12 log line). A link the phone **closed** on purpose (a
Close frame: the setting turned off, media access revoked, the ready-mode idle hang-up of §8.1,
control v2 lost) owes nothing, since knocking then would show a false "Couldn't wake your phone"
or undo the hang-up; nor does a link the TV closes itself (superseded by a newer one from the same
phone, or its phone forgotten). A phone gone without a close counts as linked until that ping
timeout.

**Which phone.** Targets are paired, unlinked phones with a full stored address, one keyId per
host (the most recently proven at that host). A round knocks them one at a time, most recently
proven first (ties and 3-field entries by keyId), and moves to the next only when a phone never
answered `knockOk` (unreachable, no hello, silent after the proof, or no key), after that phone's
retries, or answered `declined` (its owner said "Not now"; another phone may still answer). It
ends at the first plain or `ask` `knockOk`, at no LAN address (a start stays owed), at a stop, or
when a link comes up; a phone that answered but did not link within the grace never hands the
round to another phone. The round's outcome ranks `answered` over `asked` over `declined` over
the failures.

- **Start knock:** once per STARTED period, when the app is STARTED and the control server is
  listening (in either order). A new control-server bind or relisten while still STARTED makes a
  start knock due again; if a round is out, it is made when that round ends. That round is
  journaled `trigger=rebind` only when a start round already ran in this STARTED period,
  otherwise `trigger=start`. One owed while "Check your phone to connect" shows is kept and runs
  when that window ends.
- **Key knock:** a remote key on the idle screen while no phone's socket is authenticated, at
  most once per 10 s since the last round began. A key round counts as the owed start knock,
  unless it found no LAN address.
- Never while stopped, with no LAN address, or with the server unbound. No LAN address means
  "not ready yet": the knock stays owed and is retried on the next bind-loop pass, never counted
  as a failure.

**Client.** Connect within 2.5 s (bound to the TV's LAN address, as the control server is),
`knockHello` within 1.5 s, `knockOk` within 2 s; lines of at most 512 bytes, strict UTF-8 (a
malformed one is a protocol failure). Up to 2 attempts per phone, 1.5 s apart, only when the
connect failed or timed out, or there was no LAN address. Once a connection is made nothing is
retried: the phone counts its admission budget when it greets and closes over-limit connections
before greeting (§13.1), so a second try 1.5 s later would be refused. Any failure after the
connect and before the proof is sent counts as "no hello"; "silent" is no reply after the proof.
A missing or undecodable key, or a phone forgotten mid-round, ends that phone's knock with no
hint.

**Port sweep.** The phone's listener may re-bind to another of 47670..47679 when its process
restarts, and the TV learns the port only from a `knockAt` over a live link. So when a phone's
stored port stays unreachable after its retries, the TV tries that host's other listener ports
in the phone's bind order, one connect each, 4 s in all. It goes on past a port that connects but
sends no hello (another app's listener, or a phone process still starting) and stops at the first
port that sends a hello and then answers any `knockOk` or goes silent after the proof, or when the
4 s run out. A port whose knock the phone accepts (any `knockOk`) becomes the stored port.

**Gaps and grace.** Start rounds are at least 6 s apart; a start inside the gap is postponed to
the gap's end. Because the phone's per-peer gate counts 5 s from accepting a connection, each
phone's first knock in a round also waits until 6 s after the last successful connect to that
phone. Key rounds are 10 s apart. After `knockOk` the TV allows 8 s for the phone's link;
returning to the app inside those 8 s shows "Waking your phone…" for the rest of that window
instead of knocking again, unless the control server re-bound while the app was STARTED (the
TV's address may have changed, so it knocks, still within the gaps). A link coming up ends the round. These timings are held for the life
of the TV process (leaving and reopening the app keeps them) and are not persisted.

**Idle screen.** "Linked" means any phone's library socket is authenticated, even one sharing an
empty library.

- "Waking your phone…": while a knock is out, or for 8 s after an answer.
- Today's text plus "Couldn't wake your phone. Check it's on this Wi-Fi and Flick's battery is
  set to Unrestricted.": a phone was unreachable, sent no hello, went silent after the proof, or
  answered but no link came within 8 s. (A phone that is away or has stopped Flick reads the
  same; a phone busy with another TV can too, §13.3.)
- "Check your phone to connect": a round ended with a phone answering `ask` and none answering
  plainly, until a phone links or 60 s pass; then today's quiet text, never the "Couldn't wake"
  hint (the phone did hear the TV). While it shows, no automatic round starts; a key on the idle
  screen still may under the key rules (the phone's prompt does not alert twice), and a rebind
  round owed meanwhile runs when the window ends.
- A round that ended `declined` (some phone declined and none answered plainly or `ask`): today's
  quiet text, and no more automatic rounds for the rest of that STARTED period.
- Today's quiet text otherwise: no round yet, no LAN, no stored address or key, after a link
  comes up, and after that link is later closed by the phone. When the last link is lost instead,
  a start dropped for "linked" may be owed again (above), and the face then follows that round.

### 13.5 The phone's reply and the "Connect?" prompt

The phone decides which `knockOk` to send on the listener thread, after the proof verifies and
before anything acts on the knock, so it reads only state that is safe there (a StateFlow's value,
a volatile flag, a synchronized map):

1. a silent resume to this TV (§13.3) is already dialing → plain: it answers this knock, even
   within a "Not now";
2. the owner chose "Not now" for this TV within the last **10 minutes** → `declined`;
3. **When your TV opens Flick** is "Automatic" → plain (connect);
4. the activity is visible → plain;
5. notifications are unavailable (`areNotificationsEnabled()` false, or the prompt's channel set
   to importance NONE) → plain, and the knock journals `prompt_unavailable` when, on main, it
   resumes in ask mode with the activity hidden and the prompt still unavailable;
6. otherwise → `ask`.

The setting lives in `flick_tv_library` as `connect_mode` (`ask`, the default, or `auto`) and is
shown under **Show my library on my TV**, only while that is on. "Not now" is held in memory per
tvId on `elapsedRealtime`; process death forgets it, which only means the next knock asks again.
Starting a connect on open or prompt resume to that TV clears it: the person acted.

On main the knock then runs §13.3 with fresh state; minor races are accepted. A `declined` knock
does nothing more. If the action computed in §13.3 is anything but the resume, it is carried out
exactly as there, whatever the reply was. For the resume:

- plain → the knock's resume;
- `ask`, with the activity visible by now → the knock's resume;
- `ask` otherwise → the phone posts the prompt, remembers the knock's host for it (memory only,
  a hint like the knock's own), releases the wake lock and journals `knock outcome=asked`. If the
  post fails or notifications became unavailable, it resumes instead and journals
  `prompt_unavailable`.

**The prompt.** A heads-up notification on its own channel `tv_connect_requests` ("TV connection
requests", `IMPORTANCE_HIGH`), one per TV: "Connect to <TV name>?" / "Flick is open on your
TV." (always true: the TV knocks only while Flick is open on it, though a knock after a lost link
may come long after it opened), public on the lock screen (it names only the TV), auto-cancelled, alerting once, and
timing out after 60 s. Not a full-screen intent: Android 14+ limits `USE_FULL_SCREEN_INTENT` to
calling and alarm apps (source.android.com, "Full-screen intent limits"), and a heads-up on a HIGH
channel is the documented surface for a time-sensitive ask. Its body opens the app through an
explicit, immutable activity intent carrying the tvId and this install's body-tap token, and the
activity runs **Connect** below for it, with the remembered host. The activity is exported, so any
app could send it a tvId: an intent whose token does not match (constant-time compare) has its
extras dropped and does nothing. When the tap starts the activity, Connect runs in `onCreate`,
before its own connect on open (§3), which then finds that dial running. When the activity was
stopped in the background, Android calls `onRestart`, then `onStart` (connect on open dials the
last TV), and only then `onNewIntent`, so Connect meets that dial: it joins it for the same TV and
replaces it for another (below). The tap is handled once (the extras are removed, and a recreation
from saved state never repeats it). Connect on open's 15-s throttle therefore cannot swallow the
tap, and the knock's address is never lost on the way. Its actions go to a non-exported receiver through immutable
PendingIntents (request codes distinct per TV and action, the body's included); a tap counts as
the person acting on the app's UI, which exempts starting work from the background:

- **Connect** → cancels the prompt; if the pairing is still verified, no cast is live, the setting
  is on and control is not already up: when a silent resume to that TV is already dialing, it
  takes the knock's wake lock and hands that resume the remembered host
  (`prompt choice=connect skipped=resuming`); when connect on open is dialing a **different**
  TV and nothing else owns the line, it cancels that resume (journaled `open_resume ...
  reason=superseded`; no reattach is abandoned and no block wait ended) and goes on as below; when
  any other dial, pairing, block wait or resume owns the line, it starts nothing (`skipped=busy`);
  otherwise it takes the
  knock's 20-s wake lock and runs the silent resume with the remembered host as its hint
  (`prompt choice=connect`, then `prompt_resume`). The wake lock is released as for a knock: the
  first catalog, or the resume's failure. Otherwise it journals
  `prompt choice=connect skipped=<reason>`.
- **Not now** → cancels the prompt and snoozes that TV for 10 minutes (`prompt choice=later`);
  knocks in that time are answered `declined`.

The prompt is also cancelled when control authenticates to that TV by any path, when the setting
is turned off, and when a knock finds its pairing gone.

