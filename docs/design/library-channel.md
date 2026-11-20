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
- It closes `/library` whenever control v2 loses authentication, the setting is turned off,
  media access is revoked, or the TV is forgotten; it reopens after the next control-v2
  authentication. The library socket therefore needs no liveness probe of its own; the Ktor
  client sets no ping interval on it.
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
  the phone counts the episode watched. Only a viewer exit is a sample: a `stop` (from the phone
  or the TV remote), a replacement by a new cast, or Play now, before `ended`; the remaining time
  is kept only when 5 s..3 min was left and at least 85 % had played. Errors, link loss,
  teardown, backgrounding, an autoplay and a natural `ended` are not samples. Samples are kept on
  the TV under a hash of the `showKey` (no title stored), newest 200 shows, not backed up. During
  the credits the card offers only Play now (no Back to library) and leaves Left/Right to
  seeking: OK plays now, Back hides it. It clears if the viewer seeks back past the lead plus
  10 s (unless its `play` is already outstanding); once hidden the credits do not offer it again
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

## 12. Privacy and logging

Titles, file names, folder names, TMDB paths, item ids and thumbnails never enter logs,
exceptions, notifications, diagnostics or the incident journal on either device. Logs carry
frame types, counts, byte sizes and timings only. Nothing in this channel is backed up.

The TV's learned credits lengths (§10) are stored in `flick_credits` keyed by a hash of the
`showKey`, never the key or a title, and that file is excluded from backup and device transfer.
