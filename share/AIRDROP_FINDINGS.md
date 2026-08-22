# AirDrop transport findings

Ground truth for what an AirDropping iPhone is actually reachable on **from an ordinary
third-party Android app**. Same role as `QUICK_SHARE_VERIFICATION.md`: claims here should be
backed by an observation, and anything unverified says so.

## The question

Quick Share on the Pixel 10 interoperates with AirDrop. If AirDrop's peer-to-peer link is a
standard one (Wi-Fi Direct or Wi-Fi Aware), an ordinary app can reach it — Android exposes
`WifiP2pManager` and `WifiAwareManager` publicly, and a `_airdrop._tcp` DNS-SD registration plus
an HTTPS server implementing `/Discover`, `/Ask` and `/Upload` would be a normal amount of work.
If it is AWDL, no app can reach it: there is no Android API for AWDL, and the open-source
equivalent needs 802.11 monitor mode and frame injection that the Wi-Fi HAL does not expose.

So the transport determines whether a receive path is buildable at all. It is worth measuring
rather than arguing about.

## Prior expectation (superseded by the APK analysis below)

Going in, the expectation was **AWDL**, on three grounds:

1. Whatever Google shipped had to interoperate with *unmodified* iPhones, so the transport must
   be something iOS already speaks. Google cannot change Apple's side.
2. Meta's internal Wi-Fi Aware working-group FAQ (June 2025) still describes AirDrop as
   AWDL-based: *"Historically, Apple has not adopted Wi-Fi Aware, instead relying on its
   proprietary solution, AWDL (Apple Wireless Direct Link), for applications like AirDrop."*
   Apple's DMA-driven iOS 26 change exposes **Wi-Fi Aware** (NAN) to third-party apps, which is
   a distinct technology from Wi-Fi Direct and is a *new third-party capability*, not a change
   to AirDrop's own transport.
3. Google's security blog restricts the feature to AirDrop's **"Everyone for 10 minutes"** mode.
   Contacts-only AirDrop validates identity with Apple-issued client certificates that Google
   cannot mint; "Everyone" accepts self-signed. That is the fingerprint of implementing the real
   AirDrop protocol rather than substituting a transport.

The widely-repeated explanation for *how* Google managed it — that the Pixel 10's Broadcom Wi-Fi
silicon has AWDL-capable firmware — is **not verified here** and should not be repeated as fact.

**Update:** the `com.google.android.mosey` APK analysis below resolves this. The transport is a
bespoke channel-hopping link on a `mosey0` interface, created by a Pixel-only privileged daemon —
not Wi-Fi Direct, and not Wi-Fi Aware. Read that section rather than reasoning from the three
points above.

## Running the probe

1. Build and install: `./gradlew :share:installDev`.
2. Grant the permissions the app asks for on launch. `BLUETOOTH_SCAN` and `NEARBY_WIFI_DEVICES`
   are the two the probe cannot run without.
3. Put both devices on the same Wi-Fi network. That is not how AirDrop works, but it is what
   makes the mDNS leg meaningful — an `_airdrop._tcp` record on the infrastructure network would
   settle the question immediately, so it is worth ruling out.
4. On the iPhone: Settings → General → AirDrop → **Everyone for 10 minutes**.
5. On the iPhone: share any file and leave the AirDrop picker open on screen. It stops beaconing
   when the sheet closes, and a negative result recorded after it closed is worthless.
6. In `:share`, open **AirDrop probe** from the top bar and press **Start probe**.

Logcat, if reading it off-device:

```
adb logcat -s AppleBeacon:V WifiDirectProbe:V AirDropProbe:V
```

### Reading the result

The BLE leg is the control. `AppleBeacon` logging `AIRDROP beacon from …` is proof the iPhone is
soliciting a transfer *at that moment*; without that line in the same window, nothing the other
two legs report means anything.

Three outcomes worth distinguishing:

| BLE `0x05` beacon | Wi-Fi Direct peer | `_airdrop._tcp` on wlan0 | Reading |
|---|---|---|---|
| yes | yes | either | AirDrop is reachable over Wi-Fi Direct — a receive path is buildable, and the prior expectation above is wrong |
| yes | no | yes | AirDrop is reachable over infrastructure mDNS — buildable |
| yes | no | no | Not reachable from this app on this device; consistent with AWDL |
| no | — | — | No result. The sheet was not open, or Bluetooth is off. Re-run |

`P2pObservations.unavailableReason` is reported separately from an empty peer list on purpose: a
probe that could not run and a probe that ran and saw nothing produce the same silence otherwise,
and only one of them is evidence.

Two limits to be honest about:

- **The BLE and Wi-Fi Direct sightings cannot be correlated by address.** Apple rotates a
  resolvable private address on BLE, and Wi-Fi Direct reports a different interface MAC. Any
  match between the two legs is temporal — "both were seen in the same window" — not
  identity-based. `candidateP2pPeers` therefore returns every peer rather than pretending to
  filter.
- A negative result is scoped to **this handset and this API surface**. It does not establish
  what Apple does internally, only what an app can reach here — which is the question that
  decides whether anything ships.

## Observations

### 2026-08-22 — Pixel 9 Pro XL (komodo), Android 17 / API 37

**Result: AirDrop was not reachable over Wi-Fi Direct or infrastructure mDNS while actively
beaconing. Consistent with AWDL.**

Probe run at 16:17:42–16:18:12 local, iPhone ~1 m away with the AirDrop picker open and AirDrop
set to Everyone. iOS version not recorded.

BLE control — the iPhone *was* soliciting a transfer throughout:

```
AIRDROP beacon 11:C0:39:D2:6A:D9 rssi=-46
  AirDrop(0x05)[18B]=000000000000000001ebd71809850e6f1500
  v1 contactHashes=[appleId=ebd7, phone=1809, 850e, 6f15]
```

Wi-Fi Direct — four discovery cycles over 30 s, **no Apple device among the peers**:

```
16:17:45  "DIRECT-98-HP Tango"        b2:0c:d1:0a:b8:98  AVAILABLE  primaryType=3-0050F204-1 (printer)
16:17:45  "Roku Ultra"                ca:3a:6b:1f:51:1a  AVAILABLE  primaryType=7-0050F204-1 (display)
16:17:45  "55\" Mini LED"              82:0d:3f:85:91:18  AVAILABLE  primaryType=7-0050F204-1 (display)
16:17:59  "[TV] Samsung 7 Series (65)" 56:3a:d6:4e:34:f2  AVAILABLE  primaryType=7-0050F204-1 (display)
DNS-SD over P2P: no services published by any peer
```

mDNS on `wlan0` — **no `_airdrop._tcp`, but the iPhone was plainly there**:

```
_companion-link._tcp  "vayun-mac"       -> 192.168.0.127:63038  attrs=[rpAD rpBA rpFl rpHA rpHI rpHN rpVr rpMac]
_rdlink._tcp          "Vayun's iPhone"  -> 192.168.0.209:49179  attrs=[rpAD rpBA rpVr]
_airdrop._tcp         (none)
```

### Why this is a real negative and not a broken probe

Every leg proved itself working in the same window, which is what makes the two absences
meaningful:

- mDNS resolved **the AirDropping iPhone itself** at `192.168.0.209` under `_rdlink._tcp`. So the
  browse worked, the phone was on the same LAN, and it *was* publishing Apple services there —
  just not `_airdrop._tcp`. The absence is specific to AirDrop, not a mDNS failure.
- Wi-Fi Direct discovery returned four peers including a printer and three displays, so P2P
  discovery was functioning; the iPhone simply was not advertising on it.
- The BLE beacon was present at −46 dBm throughout, so the sheet was genuinely open.

AirDrop's discovery is therefore on a link this device cannot see. That is what AWDL predicts.

### Caveats

- This is a **Pixel 9 Pro XL, not a Pixel 10**, so it has none of Google's AirDrop interop. That
  limits what the run can say about *how Google did it* — but not about the Wi-Fi Direct
  hypothesis, because Wi-Fi Direct is fully supported on this handset and the iPhone still did
  not appear on it.
- **Wi-Fi Aware (NAN) was not probed.** It is the other standards-based candidate and the one
  iOS 26 exposes to third-party apps. Untested here.
- The two captures disagree on the order of the last two hash slots — `…6f15850e00` on the first
  run, `…850e6f1500` on the second, same four values. So the `email` / `email2` labels in
  `AirDropBeacon` are **not** a stable field mapping; treat those two slots as an unordered pair.
  The 18-byte length, the eight leading zeros, the version byte at offset 8 and the trailing zero
  are all confirmed.

### Bugs this run surfaced (both fixed)

1. **mDNS never ran on the first attempt.** All four browses failed instantly with
   `FAILURE_INTERNAL_ERROR` (0). Cause: `NsdManager.discoverServices` binds to the *default*
   network, which was cellular (`rmnet16`) even though Wi-Fi was associated as `mathurs-5` at
   192.168.0.76. Fixed by passing a `NetworkRequest` for `TRANSPORT_WIFI`. **The same latent bug
   exists in `NsdDiscoveryManager`**, which uses the plain three-argument call — meaning Quick
   Share discovery and advertising also silently fail whenever cellular is the default network.
   That is worth fixing separately; `WIFI_LAN` is the only medium `:share` can accept a transfer
   on.
2. **`discoverPeers()` was called once.** A single call covers one scan cycle and then lapses, so
   a short window could have produced a false negative. Now re-issued every 10 s — which is how
   the fourth peer at 16:17:59 was caught at all.

## APK analysis: `com.google.android.mosey` ("Quick Share extension")

Static analysis of `com.google.android.mosey` 1.0.962636193 (versionCode 39073, `min_api 36`,
arm64-v8a), the package that implements Quick Share ↔ AirDrop interop. Strings extracted from
`classes*.dex` and the binary `AndroidManifest.xml` string pool. **This supersedes guesswork
about the transport.**

### It is not Wi-Fi Direct and not Wi-Fi Aware

Searched for the whole Wi-Fi Direct and Wi-Fi Aware discovery surface. **Not one of these
appears anywhere in the DEX:**

```
discoverPeers   discoverServices   WifiP2pConfig   createGroup   addLocalService
PublishConfig   SubscribeConfig    PeerHandle      WifiAwareNetworkSpecifier
WifiAwareSession  PublishDiscoverySession  SubscribeDiscoverySession
```

What *is* referenced from those packages is only `WifiP2pManager$Channel`, `WifiP2pGroup`,
`GroupInfoListener`, `requestGroupInfo`, `removeGroup`, and a bare `WifiAwareManager` class
reference, all inside one class `com.google.android.mosey.network.WifiP2pAware`. Querying group
info and *removing* groups, with no config, no `createGroup` and no `connect`, is coexistence
management — P2P, NAN and this feature all contend for the same radio, so it tears down or avoids
conflicting sessions. It is not how a peer is discovered.

So AirDrop is not discovered over Wi-Fi Direct. That matches the 2026-08-22 device run exactly:
the beaconing iPhone never appeared among four Wi-Fi Direct peers.

### What it actually does: a bespoke `mosey0` link, registered as a network

```
mosey0
Network interface 'mosey0' not found.
Failed to get network interface 'mosey0'
MoseyController got interface %s
===== MoseyController start: channels: %s
Channel hopping is supported, notify daemon with necessary events
notifyDaemonOfStaChannelUpdate: %s
notifyDaemonOfStartTransfer: %s   notifyDaemonOfEndTransfer: %s
com/google/android/mosey/network/MoseyNetworkAgent      android/net/NetworkAgent
com/google/android/mosey/network/MoseyNetworkProvider   android/net/NetworkProvider
com/google/android/mosey/network/StaNetwork
```

Google created **its own L2 link on a virtual interface `mosey0`**, driven by a native **daemon**
it notifies of channel-hopping events, of the current **STA channel**, and of transfer start/end.
It then registers `mosey0` with ConnectivityManager via `NetworkAgent` / `NetworkProvider` so
ordinary IP sockets work over it. Regulatory-domain code is extensive (`countryCode` from
telephony, WifiManager and location) because it picks the channels it hops on.

A channel-hopping link whose availability windows are aligned to the infrastructure STA channel,
addressed over IPv6 link-local:

```
Link-local IPv6 found on %s
Failed to bind attemptSocket to local IPv6 address: %s
Explicitly bound attemptSocket to local IPv6 link-local address: %s
```

is a functional description of **AWDL**. The literal string `awdl` does not appear in this APK —
but it would not: the link implementation is not here. It lives behind

```
com.google.pixel.moseyservice.IMoseyService
com.google.android.moseyservice.IMoseyService
Lcom/google/android/moseylib/MoseyManager;
Error linking against Mosey Lib.   Got null mosey binder.
binder is null, unable to update country code or stop mosey.
```

a **Pixel-specific privileged system service** plus a `uses-library` shared library
`com.google.android.moseylib`, neither of which ships in this APK.

### On top of the link: stock AirDrop application layer

```
_airdrop._tcp                                  AirDrop/1.0
com/google/android/mosey/compat/NsdManagerCompat   android/net/nsd/DiscoveryRequest$Builder
NsdManagerCompat acquired multicast lock %d
/Discover   /Ask   /Upload
Pinging /Discover for target %s     Socket timed out sending ASK request
bplist   <plist version="1.0">   <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" ...>
Failed to parse discover response plist
SenderID   ReceiverComputerName
application/x-cpio
okhttp/4.12.0 + javax.net.ssl.SSLContext / X509Certificate / SSLSockets
```

Plain DNS-SD `_airdrop._tcp` over `NsdManager` (network-bound via API 36's `DiscoveryRequest`,
with a multicast lock), then HTTPS with `/Discover`, `/Ask`, `/Upload`, Apple binary plists, and
a cpio archive body. BLE scan and advertise are present via `BluetoothLeScannerCompat` /
`BluetoothLeAdvertiserCompat` with `setManufacturerData` for the beacon bootstrap.

This is why the device run saw no `_airdrop._tcp` on `wlan0` while resolving the same iPhone
under `_rdlink._tcp`: AirDrop's DNS-SD record is published on the peer-to-peer link, not on the
infrastructure network. Mosey reaches it by *being on that link*.

### Why `:share` cannot do this

The permissions that bring up the link are all `signature|privileged`:

```
android.permission.MANAGE_WIFI_INTERFACES              android.permission.NETWORK_FACTORY
android.permission.CREATE_APP_SPECIFIC_NETWORK         android.permission.LOCATION_HARDWARE
android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS
android.permission.MANAGE_WIFI_NETWORK_SELECTION       android.permission.BLUETOOTH_PRIVILEGED
```

A third-party app signed with its own key cannot hold any of them. On top of that it would need
to bind `com.google.pixel.moseyservice.IMoseyService` and link `com.google.android.moseylib`.
Neither the package nor the library is present on the Pixel 9 Pro XL used here
(`pm list packages`/`cmd package list libraries` both return nothing for `mosey`) — it is
Pixel 10 only.

**Conclusion: AirDrop receive is not implementable in `:share`.** Not for want of protocol
knowledge — the application layer above is entirely reproducible — but because the link it runs
on can only be created by privileged, Pixel-specific platform code. This is a hard platform
boundary, not a gap in effort.

### What remains unproven

Whether `mosey0` is AWDL on the wire, or an AWDL-compatible variant, cannot be settled from this
APK; the frame format is in the daemon and `moseylib`. The evidence here establishes the *shape*
(channel-hopping, STA-aligned, IPv6 link-local, bespoke interface) and, decisively for this
repo, the *reachability* — which is the only part that affects what can ship.

## What is decoded so far

`AppleBeaconScanner` decodes Apple's manufacturer-specific data (company ID `0x004C`) as a bare
sequence of `type, length, value` records, and AirDrop's `0x05` record — always 18 bytes — as:

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | zeros |
| 8 | 1 | version |
| 9 | 2 | Apple ID hash prefix |
| 11 | 2 | phone number hash prefix |
| 13 | 2 | email hash prefix |
| 15 | 2 | second email hash prefix |
| 17 | 1 | zero terminator |

The four prefixes are the leading two bytes of `SHA-256` over the sender's contact identifiers,
which a contacts-only receiver matches against its own address book. Two bytes identify nobody
on their own; they are decoded only so sightings can be correlated across adverts. The 18-byte
length, the eight leading zeros, the version byte and the trailing zero are **confirmed** against
the 2026-08-22 capture; the split of the last two slots into `email` and `email2` is **not** —
see the caveat in that run's notes.

## If the transport turns out to be reachable

**It is not** — see the APK analysis. Kept only to record what the shape would have been: the
seam is `ShareReceiveController`, which hard-wires exactly one `NsdDiscoveryManager` and one
`TcpTransport`; an AirDrop path would be a sibling of each, and `NearbyDevice` would need a
transport discriminator beyond `DiscoverySource`. The application layer is the larger half and is
fully reproducible: DNS-SD registration, TLS, Apple binary plists for `/Discover` and `/Ask`, and
a gzipped cpio body on `/Upload`. Only the link is out of reach.
