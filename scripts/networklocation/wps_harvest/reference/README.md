# gs-loc protocol reference

These two files are recovered from commit `cb9f1c384^`, which is the last commit before
`:networklocation` dropped its online Apple WPS client. They are **reference only** — nothing
builds them. They are the specification the Rust client in `../src/gsloc.rs` implements.

| File | What it pins |
| --- | --- |
| `apple_wps.proto` | The `gs-loc` wire schema: `WifiPositioning`, `CellPositioning`, and the `BeaconLocation` payload, including the per-beacon `horizontal_accuracy` field. |
| `ApplePositioningService.kt.txt` | The endpoint, the ASCII request envelope, the 10-byte response header, and the `1e8` coordinate scale. |

Recover them again with:

```sh
git show cb9f1c384^:networklocation/src/main/proto/apple_wps.proto
git show cb9f1c384^:networklocation/src/main/java/com/vayunmathur/networklocation/apple/ApplePositioningService.kt
```

## The bits that matter

**Endpoint** `POST https://gs-loc.apple.com/clls/wloc`, `Content-Type:
application/x-www-form-urlencoded`, with a spoofed `locationd` User-Agent.

**Request envelope**, big-endian, wrapping the serialized protobuf:

```
u16 1                       version
u16 len, ascii locale       "en_US"
u16 len, ascii identifier   "com.apple.locationd"
u16 len, ascii version      "8.4.1.12H321"
u32 1
u32 0
u16 payload_len
payload
```

**Response**: a fixed 10-byte header, then the serialized protobuf.

**Coordinates** are `int64` degrees x 1e8. `horizontal_accuracy` is `int32` metres; a negative
value (and the `(-1, -1)` coordinate pair) means "location unknown for this beacon".

## Why this is the whole harvester

A request carries only beacon identifiers; the response echoes each identifier back with its
own location filled in. Apple does not return a device position. Critically, it returns *more*
beacons than were asked for — querying one BSSID yields a few hundred nearby ones — which is
what makes a snowball crawl possible: every response both answers the query and extends the
frontier.
