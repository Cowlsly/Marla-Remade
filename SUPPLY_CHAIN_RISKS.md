# Supply Chain Risks

This document records the current state of everything external that this repository depends on:
the code and artifacts that enter its builds and shipped APKs, and the network services its apps
contact at run time. Each entry states what the dependency is, the risk as it stands, and what
would mitigate it.

## Scope and limits

Covered:

- **The build and artifact supply chain** — dependency declaration and resolution, artifact
  integrity, the CI and release pipeline, scripts that fetch or compile remote code, vendored
  third-party source, and model-weight provenance.
- **The servers the apps talk to** — both the runtime hosts contacted from user devices (§11) and
  the build-time and data-prep hosts contacted from a developer machine or CI runner (§12). Several
  hosts appear in both roles; the two are kept separate throughout because the trust model and the
  blast radius differ.

Not covered: application logic, permissions posture, and data handling beyond where it bears
directly on a third-party dependency.

### Verification status

**Nothing in this document has been validated by a build.** No Gradle task ran, and no shell script
was syntax-checked. `cargo metadata --offline --no-deps` was run against the vendored fuzz manifests
(§4.1) and is the only build-tool invocation behind any claim here; there was no full `cargo build`.
Everything else is a static read of the tree.

Two specifics worth knowing before relying on a "clean" signal:

- Shell scripts cannot be checked in this environment. `bash -n` is unavailable, shellcheck is not
  installed, and no diagnostics provider is registered for `.sh` — so an empty diagnostics result on
  a shell file means *nothing was checked*, not *nothing is wrong*.
- Nothing JVM-side can be compiled locally. CI can compile it (§6.6), but no change described here
  has been through that path yet.

This matters most for the recently changed build inputs. The following are correct by inspection
and unverified by execution:

- Kotlin Gradle Plugin at 2.4.0, and the single `protobufVersion` key at 4.35.0 now backing
  `protobuf-java`, `protobuf-javalite` and `protoc`.
- The version-catalog accessors added for the Android Auto and `androidx.test` coordinates that are
  now declared through `libs.versions.toml`.
- The NDK version refactor onto `build-logic/src/main/kotlin/NdkVersion.kt`.
- Every shell script referenced below, including the pinning and checksum logic in
  `scripts/maps/extract_pmtiles.sh` and `scripts/download-ca-bundle.sh`.

Treat "the build tolerates this" anywhere in this document as an inference, not an observation.

### Limits of the audit

These bound what the rest of the document can claim.

- The Gradle dependency graph was not resolved. Everything here reflects *declared* coordinates.
  Transitive dependencies are not inventoried — notably, `io.grpc:grpc-okhttp` pulls okhttp and okio
  into `:appstore` transitively, and those versions are not established here.
- No vulnerability or advisory scanning result is reported here. Nothing below asserts that a
  dependency is or is not affected by a known CVE. `cargo-deny` runs in CI (§6.4) for the Rust side;
  there is no equivalent for the JVM side.
- Maintainer counts, release cadence, and abandonment status were not verified upstream. Where a
  library is described, the description is limited to what is observable in the tree.
- The `personal/*` modules are gitignored and absent from this checkout, so their dependencies and
  hosts are outside this inventory.
- The host inventory has its own, sharper limits — see §11.5.

## Summary

The build side is in reasonable shape and the runtime side is not.

The Rust/Cargo half of the build is well controlled: committed lockfiles with per-package checksums,
no git dependencies, no source replacement, locked builds, and `cargo-deny` on a schedule. The model
pipeline is comparably controlled — every weight pins an upstream revision and verifies a SHA-256.
CI pins every third-party action to a commit SHA, scopes signing secrets to the one step that needs
them, and gets the keystore off disk before any third-party code runs.

One build-integrity gap dominates: Gradle resolves every dependency with no artifact verification
whatsoever (§1.1).

The runtime picture is worse than the build picture. One app transports credentials over a socket
with no TLS, another accepts any certificate presented by an arbitrary mail server, live payment and
OAuth credentials are compiled into shipped APKs, and one developer-run generator downloads and
executes JavaScript from a mutable upstream branch. These are set out in full below.

---

## Highest-severity findings

The four items in this section outrank everything else in this document. Three are runtime; one is
in a build script.

**WhatsApp traffic runs over a plain TCP socket.** `WhatsAppSocket.kt` opens a bare
`java.net.Socket` to `g.whatsapp.net:443` and runs Noise_XX over it, with no TLS wrapper. Noise does
supply confidentiality for the payload, but the transport carries no system trust, no hostname
verification, and no certificate chain validation — server authentication rests entirely on the
custom verification inside the handshake. This is the sole transport for the primary WhatsApp
client, so there is no fallback path with better properties. The file's own header records that
whether the mobile edge expects raw Noise-over-TCP or a TLS wrap is an unresolved live-validation
question.

**The email client accepts any server certificate.**
`email/src/main/java/com/vayunmathur/email/network/imap/TrustAll.kt:25` implements
`checkServerTrusted` as an empty body, so every certificate validates. The user's IMAP/SMTP password
is sent immediately after the connection is established. Separately, `ImapClient.kt` swallows a
STARTTLS failure and continues to log in over the plaintext socket. Two of the six bundled provider
presets (`smtp-mail.outlook.com:587`, `smtp.mail.me.com:587`) start plaintext by design, so the
downgrade path is reachable with stock configuration.

**Live third-party credentials are compiled into shipped APKs.** A Stripe publishable *live* key is
a string literal at `fooddelivery/src/main/java/com/vayunmathur/fooddelivery/MainActivity.kt:82`. A
Lyft OAuth client secret is a string literal at
`taxi/src/main/java/com/vayunmathur/taxi/network/lyft/LyftAuth.kt:55`, and `:taxi` has
`BOOKING_LIVE = true`, so real charges are possible. A Tidal `client_id;client_secret` pair sits
base64-encoded at `musicbrainz/.../tidal/TidalAuth.kt:67-68`. Alongside these are a WhatsApp
`CLIENT_TOKEN` plus registration salt and public key, Google BotGuard api-key and request-key
constants in `:youpipe`, and two public OAuth client ids. A secret in an APK is a published secret:
these must be treated as disclosed and rotated, and the flows that need a confidential client moved
behind a service that can hold one.

**`generate_shields_lists.py` executes unpinned remote code.** `scripts/generate_shields_lists.py`
downloads uBlock Origin scriptlet sources from
`raw.githubusercontent.com/gorhill/uBlock/master/src` — a mutable branch, no commit pin, no digest —
writes them into a temporary directory alongside a generated `dump.mjs`, and runs
`node dump.mjs` over them with `check=True` (`:222-228`). Anything landing on uBO's `master`
executes on the developer machine on the next run of the generator. That machine is also where
release signing happens.

---

## 1. Build integrity (Gradle)

### 1.1 No artifact verification — High

There is no `gradle/verification-metadata.xml`, no dependency lockfiles, and no `dependencyLocking`
or `dependencyVerification` configuration anywhere in the build. The Gradle wrapper's
`distributionSha256Sum` is the only artifact integrity check in the entire build.

This is specifically *not* a version-drift risk. Every coordinate in the build is exactly pinned:
there are no dynamic versions, no version ranges, and no `-SNAPSHOT` dependencies. Pinning fixes
which artifact is requested; it does not constrain what bytes arrive. An artifact that is
substituted or republished at an already-pinned coordinate is consumed without complaint.

The exposure is not confined to library bytes sitting on a compile classpath. The build resolves
four *executables* from Maven and runs them during the build (§7.3), so the same missing check
applies to code the build actually executes.

Mitigation: generate `gradle/verification-metadata.xml` with checksum (ideally signature)
verification enabled. This is the single highest-value change available on the JVM side.

Generating it is not a mechanical step and should not be treated as one.
`--write-verification-metadata` records whatever is already in the local Gradle cache, so running it
on a warm cache pins the current bytes without ever establishing that those bytes are the right
ones. Doing this properly means resolving into a clean cache and reconciling the resulting digests
against upstream before committing them.

### 1.2 Wrapper distribution is verified — control in place

`gradle/wrapper/gradle-wrapper.properties` pins Gradle 9.6.1 from `services.gradle.org` with
`distributionSha256Sum` set and `validateDistributionUrl=true`.

The residual gap is narrow but worth stating precisely: `gradle-wrapper.jar` is committed to the
repository and is the one committed binary in the tree. The pinned checksum covers the distribution
zip that the jar downloads, not the jar itself.

Mitigation: keep the checksum pinned on every wrapper upgrade. Validate the committed jar against
the upstream release when it changes.

---

## 2. Repositories

`dependencyResolutionManagement` in `settings.gradle.kts` sets
`repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)`, so no module can introduce its own
repository — all resolution goes through the repositories declared centrally. `mavenLocal()` is not
present anywhere, so no artifact in a developer's local cache can outrank a declared remote.

Beyond the standard Google and Maven Central set, one custom repository is declared:
`https://build-artifacts.signal.org/libraries/maven/`, content-filtered to `includeGroup("org.signal")`.

### 2.1 Signal artifacts come from a vendor-run repository — Medium

`libsignal-android` 0.101.0 and `ringrtc-android` 2.71.0 are consumed by `:communicate` from
`build-artifacts.signal.org`. There is no Central mirror, so availability and integrity both rest
on a single vendor-operated host — and with no artifact verification configured (§1.1), nothing
validates what that host returns.

`libsignal` is deliberately held at 0.101.0 for MRENCLAVE reasons, so it cannot be upgraded freely
in response to an upstream advisory. That constraint is a property of the protocol, not an
oversight, but it does mean the usual "upgrade to fix" response is unavailable here.

Mitigation: cover these coordinates in verification metadata as part of §1.1. Mirror the artifacts
if build availability matters.

---

## 3. JVM / Android dependency surface

Dependencies are centralised in `gradle/libs.versions.toml`: 60 versions, 105 libraries, 7 plugins,
all exactly pinned. Every dependency coordinate in the tree takes its group, name and version from
the catalog. Two are still finished off as strings in module build files because they append a
classifier the catalog cannot express: the OS-classified `protoc` executable in `:communicate` and
`protoc-gen-grpc-kotlin`'s `:jdk8@jar` in `:appstore`.

### 3.1 Prerelease toolchain and libraries — Medium

Eighteen catalog version entries resolve to alpha or beta releases, among them the Android Gradle
Plugin at `9.4.0-alpha04` (with `lint` tracking it at `32.4.0-alpha04`), `material3`
`1.5.0-alpha27`, `camerax` `1.7.0-alpha02`, `credentials` `1.7.0-alpha02`, `biometric`
`1.4.0-alpha07`, `webkit` `1.17.0-alpha03`, and `media3` `1.11.0-beta01`. Prerelease artifacts can be
withdrawn or republished, receive no stability guarantee, and are generally not covered by upstream
security backports.

AGP is the one that matters most, because it is the build tool rather than a library: an alpha AGP
is the component with the widest reach over what gets produced.

Mitigation: move the toolchain, and AGP in particular, to stable releases before any release branch
is cut.

### 3.2 Deprecated annotation libraries — Low

`com.google.code.findbugs:jsr305` 3.0.2 is consumed by `:youpipe:extractor`, and
`javax.annotation:javax.annotation-api` 1.3.2 by `:appstore` as `compileOnly`. Both are long
deprecated. Both supply compile-time annotations only, so the practical exposure is small.

Neither can be removed independently. `javax.annotation-api` exists because grpc-java generated
stubs reference `@javax.annotation.Generated`. `jsr305` is required by the vendored NewPipe
Extractor source, so dropping it means editing vendored third-party code (§9).

Mitigation: revisit `jsr305` if and when the vendored extractor is refreshed.

### 3.3 Third-party libraries on sensitive paths — Medium

These are called out for where they sit rather than for any known defect. Descriptions are limited
to what is observable in the tree; publisher activity and maintenance status were not verified.

| Library | Version | Consumers | Why it is called out |
|---|---|---|---|
| `io.github.dokar3:quickjs-kt` | 1.0.5 | `:youpipe` | Published under a personal `io.github.dokar3` namespace, and evaluates JavaScript on the YouTube extraction path — a code-execution dependency from a single-account publisher. |
| `net.zetetic:sqlcipher-android` | 4.18.0 | `:library:room` → `:passwords`, `:notes` | Provides encrypted-database support. Declared `api(...)` at `library/room/build.gradle.kts:14`, so it propagates onto dependents' compile classpaths rather than staying an implementation detail. |
| `com.auroraoss:gplayapi` | 3.6.4 | `:appstore` | Small community organisation on the Play install path. Its host surface is in §11.1. |
| `org.brotli:dec` | 0.1.2 | 5 modules incl. `:library:network`, `:youpipe:extractor` | Decompression of network-sourced input, pinned at a 0.1.x version. |

Mitigation: cover all of these under artifact verification (§1.1).

On narrowing sqlcipher from `api` to `implementation`: the reason recorded next to the declaration
— that the inline `buildDatabase` body exposes a `net.zetetic` type — does not hold. `buildDatabase`
delegates to a non-inline overload and names no SQLCipher type; `SQLCipherDriver` appears only in
the private `openRoomDatabase` (`SqlCipher.kt:138`), and no public signature exposes a `net.zetetic`
type.

The actual blocker is one consumer. `health/src/main/java/com/vayunmathur/health/util/FoodDatabase.kt:19`
imports `net.zetetic.database.sqlcipher.SQLiteDatabase` while `health/build.gradle.kts` declares no
sqlcipher dependency of its own — it rides the `api` leak. The other 21 `:library:room` consumers do
not. So the narrowing is a two-line change: add `implementation(libs.sqlcipher.android)` to `:health`
and drop `api` to `implementation` in `:library:room`. It needs a build to confirm, and none can be
run locally (see Verification status).

---

## 4. Native code (Rust / Cargo)

This is the strongest-controlled area of the build.

- The root `Cargo.lock` is committed and covers 440 packages. All 398 external packages carry a
  registry source and a SHA-256 checksum.
- There are no git dependencies and no `[patch]` or `[replace]` sections, so no crate is resolved
  from an arbitrary remote or silently swapped.
- There is no `.cargo/config.toml`, so no registry redirection is configured.
- `rust-toolchain.toml` pins the toolchain to 1.97.0, and CI reads its channel from that file.
- Builds run with `--locked`, so a build fails rather than silently updating the lockfile.
- Nine independent workspace roots each have a committed lockfile, including
  `third_party/betocore/np_adv/fuzz`, whose manifest spells out the `[workspace.dependencies]` keys
  that upstream's unvendored `nearby/Cargo.toml` would otherwise supply.

### 4.1 Four vendored fuzz harnesses do not parse — Low

`third_party/betocore/*/fuzz`, other than `np_adv/fuzz`, still inherit `arbitrary`,
`derive_fuzztest` and `libfuzzer-sys` via `workspace = true` against a root
`[workspace.dependencies]` that does not define them, so `cargo metadata` fails on each. Their
committed lockfiles predate the `derive_fuzztest` dependency and have no entry for it. They are
fuzzing harnesses, ship in no APK, and are excluded from both Dependabot and `cargo-deny` for that
reason.

Mitigation: repair the manifests the way `np_adv/fuzz` was, or delete the harnesses if they are not
run.

---

## 5. NDK and cross-compilation

The build does not use `cargo-ndk`. `build-logic/src/main/kotlin/RustNative.kt` implements Android
cross-compilation directly. The NDK version is single-sourced as `NDK_VERSION` in
`build-logic/src/main/kotlin/NdkVersion.kt` and consumed by AGP's `ndkVersion` in the app and wear
convention plugins and by the Rust toolchain paths, so the version string exists in one place.

The residual risk is that the constant names an NDK that must actually be installed on the build
host; a mismatch surfaces as a link failure rather than a version error. This is unverified by any
build (see Verification status).

---

## 6. CI and release pipeline

Four workflows: `android.yml` (release), `pr-compile-check.yml`, `cargo-deny.yml`, and
`issue_labeler.yml`.

### 6.1 Third-party action pinning — control in place

Every third-party action across all four workflows is pinned to a full commit SHA with the tag in a
trailing comment: `actions/checkout`, `actions/setup-java`, `actions/github-script`,
`softprops/action-gh-release`, and `taiki-e/install-action`. A repointed upstream tag therefore
cannot change what runs.

Mitigation: keep the SHA form on every bump. The `github-actions` Dependabot ecosystem (§6.4)
proposes them.

### 6.2 Signing key handling — control in place

In `android.yml`, workflow-level `env:` holds only the non-secret keystore filename. `KS_STORE_PASS`,
`KS_ALIAS` and `KS_ALIAS_PASS` are step-level `env:` on the single "Build and Sign APKs" step, so
they are not in the environment of any other step. That step deletes the decoded keystore
immediately after collecting the APKs — before `softprops/action-gh-release` runs — and an
`if: always()` cleanup step is a backstop for a build that fails earlier.

The release commit stages explicit paths (`settings.gradle.kts`, `build.gradle.kts`, and each
detected module's `build.gradle.kts` and generated listing directory) rather than `git add .`, so
the decoded keystore cannot be captured into a commit that the pushed tag is reachable from.

The job holds `contents: write`. `pr-compile-check.yml` and `cargo-deny.yml` are `contents: read`;
`issue_labeler.yml` is `issues: write`.

### 6.3 Rust version check — control in place

The "Install Rust Toolchain" step greps the repository-root `rust-toolchain.toml` for its channel
and exits non-zero if the value is empty, so CI tracks the real pin instead of falling back to a
hardcoded version.

### 6.4 Dependency monitoring — control in place, JVM half missing

`.github/dependabot.yml` covers three ecosystems: `gradle` (root plus the `build-logic` included
build), `cargo` (nine workspace roots), and `github-actions`. All are monthly with minor/patch bumps
grouped into one PR per ecosystem and major bumps left ungrouped. Dependabot security alerts are
independent of that schedule.

`.github/workflows/cargo-deny.yml` runs `cargo deny check advisories bans sources` and
`check licenses` against `deny.toml`, per workspace root in a matrix, on pull request, on push to
`main`, on a Monday cron, and on dispatch. The cron matters: an advisory can land against an
unchanged lockfile, which no `pull_request` run would ever see.

The gap is that this is Rust-only. Nothing scans the JVM dependency surface for advisories —
Dependabot proposes JVM upgrades but no equivalent of `cargo-deny` reports on what is committed
today.

Mitigation: add a JVM advisory scan (OWASP dependency-check or equivalent) to CI.

### 6.5 Release APK upload uses a shell script with an ambient token — Low

`release.sh:165-168` uploads every built APK and a generated `index.json` with
`gh release create ... --draft`, using whatever credential the local `gh` CLI holds. This is the
manual counterpart to the `android.yml` release job and runs on a developer machine with a personal
token rather than a scoped `GITHUB_TOKEN`.

Mitigation: prefer the workflow path for real releases; keep `release.sh` for dry runs.

### 6.6 The PR check does not exercise the release toolchain — Medium

`pr-compile-check.yml` is the only workflow that compiles application code on a pull request, and it
diverges from the release job in two ways that matter.

**It runs a different JDK.** `pr-compile-check.yml:20-24` sets up JDK 17; `android.yml:26-30` sets up
JDK 21. For routine code changes the gap is harmless. For a toolchain change — an AGP major, a
Kotlin realignment — the JDK is precisely the variable that bites, because toolchain resolution and
Kotlin `jvmTarget` behaviour differ across the two. A green PR check on that class of diff does not
establish that the release job still builds.

**It compiles and nothing else.** The PR check runs `./gradlew :compileDevKotlin` only, and the
release job runs `assembleRelease` with `-x lint -x test`. So neither workflow ever runs `lint` or
the test suite: the repo's own lint rules (including the build-failing ones) and every unit test are
verified only by whoever remembers to run them locally.

This bounds what CI can be relied on to catch, including for the unverified changes listed under
Verification status.

Mitigation: align the PR check's JDK with the release job's, and run `lint` and `test` somewhere in
CI rather than excluding them from the one job that could.

---

## 7. Build-time and script downloads

The hosts these scripts contact are inventoried in §12.

### 7.1 `go-pmtiles` — control in place, with a stated caveat

`scripts/maps/extract_pmtiles.sh` pins `go-pmtiles` to v1.31.2, installs it project-local under the
gitignored `tmp/tools` (no `sudo`, no write to a system path), and verifies a per-platform SHA-256
before extracting.

The script is explicit that the digests are weaker than they look, and that statement should be
preserved rather than rounded up: upstream publishes no `checksums.txt` for v1.31.2, so the four
digests were computed from downloaded artifacts. They establish **immutability** (a later silent
re-upload or a MITM is caught) and not **authenticity** (if an artifact was already bad when
measured, the bad one is now pinned).

Two residual gaps: an OS/arch pair not in the `case` list falls through to an empty
`PMTILES_SHA256` and installs with only a printed warning, and the macOS path extracts with
`unzip -o` without a traversal guard.

Mitigation: fail rather than warn when no digest is available for the host platform, and re-derive
the digests from an upstream-published manifest if protomaps ever ships one.

### 7.2 TLS trust anchors are pin-gated — control in place, with residual gaps

`scripts/download-ca-bundle.sh` produces the 20 DER trust anchors under
`library/network/src/main/assets/ca/`, which are what the apps pin against at run time (§11.4). It
fetches the Mozilla bundle from `curl.se/ca/cacert.pem`, falls back to direct vendor URLs
(`letsencrypt.org`, `pki.goog`, `cacerts.digicert.com`, `www.amazontrust.com`) for roots the bundle
drops, converts everything inside a temporary directory, and moves a `.der` into the asset directory
**only** when its SHA-256 matches a `PINS` entry. An unpinned label is never written. `--clean`
pruning is skipped entirely if the run had any failure or rejection, and the script exits non-zero
if either counter is non-zero — so a partial run cannot silently ship a short trust set.

Three residual gaps:

- The pins are digests of the `.der` files **as committed**. They prove the shipped anchor set
  cannot change without a reviewable edit to both the asset and its pin in the same diff; they do
  not independently establish that a given anchor is the authentic upstream root. The
  authenticity question is answered by review of that diff, not by the script.
- `godaddy-root-g2.der` is committed PEM-encoded despite its extension, so a freshly converted DER
  can never match its pin and that anchor will always be rejected until the asset is re-encoded.
- The fallback URLs are fetched over TLS validated by the *host* trust store, which is the ordinary
  bootstrap circularity for this kind of script rather than a defect in it.

Mitigation: re-encode `godaddy-root-g2.der`. Continue reviewing any change to a `.der` or its pin as
a security-relevant diff.

### 7.3 Four build-time executables resolved from Maven and run unverified — High

The build downloads four executable artifacts from Maven and runs them during compilation:

| Artifact | Version | Resolved by |
|---|---|---|
| `com.google.protobuf:protoc` (OS-classified `@exe`) | 4.35.0 | `:communicate`, via a custom `protocBinary` configuration |
| `com.google.protobuf:protoc` | 4.35.0 | `:appstore`, `:youpipe:extractor` |
| `io.grpc:protoc-gen-grpc-java` | 1.69.0 | `:appstore` |
| `io.grpc:protoc-gen-grpc-kotlin` (`:jdk8@jar`) | 1.4.1 | `:appstore` |

The `:communicate` case additionally marks the download executable with `setExecutable(true)` before
invoking it. All four resolve from Maven Central, all are exactly pinned, and — because there is no
verification metadata and no lockfile (§1.1) — nothing checks their bytes before they execute.

This is rated above the other consequences of §1.1 because a substituted library must still be
called by application code to matter, whereas a substituted compiler plugin runs on the build
machine by construction, with that machine's credentials.

Mitigation: prioritise these four coordinates when adding verification metadata under §1.1.

### 7.4 Remote source compiled from commit-pinned revisions — control in place

`scripts/geocoder_gen.sh` compiles simdjson and zstd from single-header sources fetched from
`raw.githubusercontent.com`, pinned to commit SHAs (`SIMDJSON_REV` b4242d3b / v3.11.0, `ZSTD_REV`
63779c79 / v1.5.5). The header records how to bump them, including that `git ls-remote ... '^{}'` is
required to peel zstd's annotated tags to a commit — a bare tag ref returns the tag object, which is
not a content ref.

A `raw.githubusercontent.com` URL carrying a commit SHA is content-addressed, so no separate digest
is needed. The residual trust is in GitHub serving the correct object for that SHA, plus TLS.

### 7.5 Launcher icon fetch — Low integrity risk, Medium availability risk

`LauncherIconGen.kt` fetches Material Symbols sources from `raw.githubusercontent.com`, pinned to
the full commit SHA `819d78680a849ceef4c78f863d8753e3160b7c89`, which is also the SHA used by the
second, separate fetch of the same upstream in `android.yml:174` for Play/F-Droid listing icons.
Results are cached under `$GRADLE_USER_HOME/material-symbols-cache/<ref>/`, and a cache hit skips
the download.

On integrity the pin is doing real work: a full commit SHA makes the request content-addressed, so
the risk is low. The response carries no digest of its own, leaving trust resting on the commit SHA
plus TLS.

Availability is the sharper edge. The fetch is reached from 72 `launcherIcon` blocks across the app
modules, and a failure throws `GradleException` — so on a cold cache without GitHub reachability the
build fails outright rather than degrading. The `android.yml` copy is softer: it warns and continues
without an icon.

Mitigation: vendor the icon sources to remove the build-time network dependency entirely, or record
a digest per fetched file and pre-seed the cache in CI.

---

## 8. Model weights

Eleven model weights are fetched by the scripts under `scripts/ml/`, and **all eleven pin an
upstream revision and verify a SHA-256**. This is the strongest provenance control in the
repository.

Six are fetched directly by `fetch_and_convert.sh`, each from a `huggingface.co/.../resolve/<commit>/`
URL with a pinned file digest, then converted to `.maml`:

| Asset | Model |
|---|---|
| `camera/src/main/assets/selfie_segmentation.maml` | MediaPipe Selfie Segmentation |
| `photos/src/main/assets/u2netp.maml` | U²-Net portable (saliency) |
| `photos/src/main/assets/scrfd_500m.maml` | SCRFD 500M (face detection) |
| `photos/src/main/assets/w600k_mbf.maml` | MobileFaceNet (face embedding) |
| `library/ocr/src/main/assets/ppocr_det.maml` | PP-OCRv5 mobile (text detection) |
| `library/ocr/src/main/assets/ppocr_rec.maml` | PP-OCRv5 mobile latin (text recognition) |

Five more come through `huggingface_hub.snapshot_download`, each with an explicit `revision=` and a
per-file digest table: `fetch_maia.py` (`:games:chess`), `fetch_nllb600.py` (`:translate`),
`fetch_supertonic.py` and `fetch_whisper.py` (`:speech`), and `fetch_tinyclip.py` (`:photos`).

`fetch_and_convert.sh` downloads into a `mktemp -d` scratch directory that is removed on exit, and
on a digest mismatch it reports and skips the model without invoking the converter — so a failed
verification cannot overwrite a committed asset.

Beyond the digests, `maml_convert.py` pins a digest over the whole ordered layer table, so an
upstream re-export that reorders or re-pads a layer fails at conversion rather than shipping a net
that infers nonsense. That digest covers *structure*, not weight values; `scripts/ml/onnx_parity.py`
is the numeric check.

### 8.1 Two model provenance gaps — Medium

- The SMaLL-100 provenance scripts are deleted but still referenced.
- `music_detector.sound_model` has its provenance recorded nowhere — no fetch script, no upstream
  revision, no digest.

Mitigation: record provenance for `music_detector.sound_model` in the form the other eleven use, and
either restore or remove the SMaLL-100 references.

### 8.2 `HF_TOKEN` is sent implicitly — Low

`huggingface_hub` attaches `HF_TOKEN` from the environment when it is set, so the five
`snapshot_download` scripts authenticate to `huggingface.co` whenever a developer happens to have a
token exported, whether or not the repository requires one.

Mitigation: pass `token=False` where anonymous access suffices.

---

## 9. Vendored third-party source

| Vendored source | Upstream revision recorded |
|---|---|
| `third_party/betocore` | Yes — upstream commit recorded |
| `youpipe/extractor` (NewPipe Extractor) | No |
| `third_party/om-file-format-sys` | No |

### 9.1 Two vendored trees have no recorded upstream revision — Medium

Without a recorded upstream commit there is no way to diff the vendored copy against upstream, apply
an upstream security fix with confidence, or establish what local modifications exist. This matters
most for `youpipe/extractor`, which parses untrusted remote input from YouTube (§11.1) and is also
what forces `jsr305` to stay in the catalog (§3.2).

Mitigation: record the upstream commit for both, in the form `third_party/betocore` already uses.

---

## 10. Release signing key

### 10.1 `release_keystore.jks` is committed to the repository — High, pending confirmation

A 2500-byte `release_keystore.jks` is tracked in git at the repository root, under the exact
filename the release workflow signs with. It is the only keystore in the tree.

CI overwrites this file from a secret before signing, which strongly suggests the committed file is
a development key rather than the production one. That has not been confirmed, and the distinction
is the entire risk: if the committed key is the production signing key, it is disclosed to everyone
with repository access and every APK signed with it is compromised.

Mitigation: confirm what the committed keystore actually is. If it is not a throwaway development
key, treat it as disclosed — rotate it and follow key-compromise procedure. Either way, remove it
from version control and have CI supply the keystore entirely from secrets, so no file at that path
is ever tracked.

---

## 11. Runtime servers the apps depend on

Hosts contacted from a user's device. Build-time hosts are in §12; several organisations appear in
both, and the two roles are not interchangeable.

First-party infrastructure (`vayunmathur.com`, `findfamily.cc`) is out of scope for this inventory
and is omitted throughout.

`UNCERTAIN` markings below are load-bearing. They mean the host was inferred from a string, a
comment, or a single capture rather than confirmed from a request site, and they should not be
promoted to fact without re-checking.

### 11.1 Hosts by app

**`:appstore`**

| Host | Purpose | Notes |
|---|---|---|
| `f-droid.org/repo` | Signed index, APKs, icons | HTTPS; fails closed on signature failure; cache survives; toggleable; cert pinned |
| `verification.f-droid.org/verified.json` | Reproducible-build badge | Best-effort, cosmetic |
| `apps.grapheneos.org` | Sandboxed Play index + APKs | signify ed25519 pin. **No mirror, not toggleable** |
| `repo.accrescent.app` | Signed allowlist / trust anchor | Pinned key. **No fallback, fails closed** |
| `appstore-api.accrescent.app:443` (gRPC) | Listings, download URLs | TLS **deliberately unpinned**; browse soft-fails; sends device attributes |
| Accrescent split CDN | APK bytes | Host supplied by the server — **not statically known** |
| `auroraoss.com/api/auth` | Anonymous Play accounts | **Sends a full device fingerprint and receives a Google authToken**; spoofs the Aurora UA to evade Cloudflare. `FALLBACK_DISPENSERS` has exactly one entry — the same URL — so it is a **SPOF** |
| Play API hosts (`android.clients.google.com` and siblings) | Play protocol via the `gplayapi` AAR | authToken + `X-DFE-*`. **UNCERTAIN** — read from strings inside the AAR |
| Play APK CDN | APK bytes | **Does not use the pinned SSL factory** that its sibling downloaders use. Range-resume |
| `play-lh.googleusercontent.com` | Listing imagery | **UNCERTAIN / dynamic** |

`play.google.com` and `f-droid.org` also appear as inbound deep links in the manifest, and
`github.com/vayun-mathur/Modern-Apps` as a dead cache key and displayed link. Neither is a
dependency.

**`:maps`**

| Host | Purpose | Notes |
|---|---|---|
| `api.transitous.org/api/v1/stoptimes` | Transit departures | **Volunteer-run.** Good fallback behaviour, but the UA `Modern-Apps-Maps/1.0` is not contactable, and `OfflineRouter.kt:562-569` fires one call per journey stop, concurrently |
| `www.google.com/search?tbm=map` | POI discovery and search | Keyless, ToS-hostile scrape. A spoofed Chrome UA, Referer and warmed cookies **are** the credential. 15 category terms per viewport; search is online-only |
| `www.google.com/maps/vt/pb=` | Traffic tiles | **UNCERTAIN** — the file states it is a no-op on phone |
| `maps.googleapis.com/.../SingleImageSearch` | Street View lookup | Fired on **every** place selection |
| `www.google.com/maps/photometa/v1`, `streetviewpixels-pa.googleapis.com/v1/tile` | Street View metadata and tiles | Full tile grid fetched concurrently |
| `www.google.com/maps?cid=` | Reviews | Hidden WebView DOM scrape, 10–45 s per place |
| `*.googleusercontent.com` | Photos and avatars | |
| `http://localhost:<ephemeral>/traffic/{z}/{x}/{y}` | Local traffic tile server | **PLAINTEXT**, unauthenticated, `Access-Control-Allow-Origin: *`. Loopback-bound, which is itself a deliberate narrowing of an earlier bind |

`openstreetmap.org` and `github.com/protomaps/basemaps` are attribution only. `maps.app.goo.gl` /
`goo.gl` are deep links, and are a real dependency only if the app resolves shorteners itself —
**UNCERTAIN**. The manifest accepts `scheme="http"`.

**`:musicbrainz`**

| Host | Purpose | Notes |
|---|---|---|
| `musicbrainz.org/ws/2` | Metadata | Non-profit. Correctly rate-gated at 1100 ms with an identifying UA. Hard-fails the screen |
| `coverartarchive.org` | Cover art | Non-profit. **Not rate-gated and no identifying UA** |
| `archive.org`, `ia*.us.archive.org` | Cover art redirect targets | |
| `api.tidal.com/v1` | Catalogue and playback | Bearer OAuth |
| `auth.tidal.com/v1/oauth2` | Tidal auth | **Hardcoded shared `client_id;client_secret`**, base64-encoded at `TidalAuth.kt:67-68` |
| `sp-*-{cf,fa}.audio.tidal.com` | Audio CDN | The reason `TrustBundle.MUSICBRAINZ` exists |
| `lrclib.net` | Lyrics | **Volunteer-run**, ungated, two calls per track |

**`:youpipe`**

| Host | Purpose | Notes |
|---|---|---|
| `www.youtube.com/youtubei/v1/*` | InnerTube API | No fallback host |
| `www.youtube.com/sw.js`, `/results` | Client version discovery | Real three-tier fallback |
| `youtubei.googleapis.com` | InnerTube alternate | **UNCERTAIN** |
| `music.youtube.com` | Music surface | |
| `suggestqueries.google.com` | Search suggestions | **Partial query sent to Google per keystroke** |
| `api.pipepipe.dev/decoder/*` | Stream-URL deobfuscation | **Third-party community service that sees every stream URL the user plays**, and so learns what each user watches. The local fallback is null by default. The highest-risk runtime third party in the tree |
| `jnn-pa.googleapis.com` | BotGuard / PoToken | **Hardcoded api-key and request-key** at `LocalDomPoTokenGenerator.kt:287-288` |
| BotGuard interpreter URL | Attestation | **Host chosen by YouTube**, and the JS is eval'd in a WebView |
| `*.googlevideo.com` | Media | Dynamic |
| `/feeds/videos.xml` | Channel feeds | **UNCERTAIN** |

The 30-host Invidious/Piped list is string-only recognition and is never contacted.
`dearrow-thumb.ajay.app` appears in a comment only.

**`:communicate`** — the largest runtime surface.

*Signal.* `wss://chat.signal.org:443/v1/websocket/` (two sockets) is a **SPOF**. `/v2/directory/auth`
resolves to `cdsi.signal.org`, whose host lives inside libsignal (**UNCERTAIN**).
`/v4/attachments/form/upload` returns a **server-supplied CDN3 URL that is used without scheme or
host validation** (`SignalAttachmentUpload.kt:104`). `/v2/calling/relays` returns
**server-supplied `turn:`/`stun:` hosts, including bare IPs**. The bundled Signal roots are a
**union with the system CAs — additive, not pinning**.

*WhatsApp.* `g.whatsapp.net:443` over a raw socket with no TLS — see Highest-severity findings; sole
transport and a **SPOF**. `v.whatsapp.net/v2/*` carries the OTP, 2FA PIN, key bundle and device
fingerprint, impersonates the official client, and uses a **hardcoded salt and public key**
(`WhatsAppRegistrationConstants.kt:26-27,54-55`). `graph.whatsapp.com/graphql` uses a **hardcoded
`CLIENT_TOKEN`**. The media host is **server-supplied** via `media_conn`, and its **scheme is
unverified** (§11.5).

*Google Voice.* `clients6.google.com/voice/v1/voiceclient/` uses **no OAuth — it replays the browser
cookie jar plus a SAPISIDHASH**, is a **SPOF**, and **logs every response body, including the SIP
password**. `voice.google.com` is loaded in a WebView that monkey-patches `fetch`/`XHR`, uses
`addJavascriptInterface`, and has an unscoped fallback injection.
`wss://web.voice.telephony.goog/websocket` uses RFC 2069 MD5 digest auth and logs frames.
`web.c.pbx.voice.sip.google.com` is derived from a **single HAR capture**.
`stun:stun.l.google.com:19302` is **plaintext UDP and the only ICE server — no TURN**.

*MMS.* The download URL is taken **unvalidated from an inbound WAP-push PDU**
(`MmsDeliverReceiver.kt:63`), and real MMSCs are usually `http://`.

Verified absent from `:communicate`: RCS, all push (60 s Google Voice polling instead), any bridge,
any community endpoint.

**`:email`**

Six provider preset pairs, two of which start plaintext (`smtp-mail.outlook.com:587`,
`smtp.mail.me.com:587`). `login.microsoftonline.com/.../{authorize,token}` uses a hardcoded PKCE
`client_id`, **hard-fails — a SPOF for every Outlook account** — and **logs access and refresh
tokens**. The arbitrary user-supplied IMAP/SMTP host is reached through the empty
`checkServerTrusted` described in Highest-severity findings. There is **no autoconfig or ISPDB
lookup at all**; the in-app help links are display-only.

**`:everysync`**

`accounts.google.com/o/oauth2/v2/auth` (hardcoded `client_id`), `oauth2.googleapis.com/token`
(**SPOF**), `www.googleapis.com/oauth2/v3/userinfo`, Google CardDAV/CalDAV
(`apidata.googleusercontent.com/caldav/v2/...`), `health.googleapis.com/v4/...`, and
`caldav.icloud.com` + `contacts.icloud.com` over Basic auth, plus `.well-known/{caldav,carddav}`
discovery. `DavLoginScreen.kt:61-66` performs **no scheme validation**, so an `http://` server URL
sends the password in the clear. A stale comment references a Withings/Samsung proxy that does not
exist.

**`:euicc`**

A user-supplied SM-DP+ host and `/gsma/rsp2/es9plus/*`. `es9p.rs:154` **accepts `http://` verbatim
from a scanned QR code or a typed activation code**. There is **no SM-DS discovery**.
`rsp.truphone.com` is a test fixture, not a dependency.

**`:weather`**

`api.open-meteo.com/v1/forecast`, `geocoding-api.open-meteo.com/v1/search`,
`air-quality-api.open-meteo.com/v1/air-quality`, and `map-tiles.open-meteo.com` (both `latest.json`
and 64 KB HTTP Range reads of `*.om` from Rust). All keyless HTTPS. **Open-Meteo is free and
donation-funded, and is a single organisational SPOF for the entire app.** `api.weather.gov` is
verified absent.

**`:networklocation`**

**No third-party positioning host.** Beacon resolution is fully on-device: the `BeaconCache`
(in-memory + Room) first, then the offline WPSDB stores, and nothing beyond that. Beacons
absent from both are dropped from the solve. MLS, beacondb, OpenCelliD, WiGLE, Apple `gs-loc`
and Google geolocation are all absent from the tree.

The app still holds `INTERNET`, used solely by `:library:downloadservice` to fetch the offline
databases. `wifi.wpsdb`, `cells.wpsdb` and `geocoder.geodb` are **downloaded on demand, not
bundled** (they are far too large to ship in the APK). Until they arrive every lookup misses,
and with no online fallback the provider now reports **no position at all** rather than
degrading to a remote service — a deliberate trade of availability for not talking to anyone.
The stores are built by an out-of-repo `wtfps-experiment/store.py`, so their **provenance is
unresolved — UNCERTAIN**, and that is now the app's only meaningful supply-chain exposure.

**`:passwords`**

`wss://cable.ua5v.com/...` is the **Google-operated** FIDO caBLE tunnel. It sees ciphertext only,
which is the point of the design, but `DEFAULT_ID = 0` is hardcoded, making it a **hard-fail SPOF
for cross-device passkeys** with Apple's `cable.auth.com` sitting unused one array index away. The
`cable.<base32>.*` entries are self-declared **UNVERIFIED** and dormant. Disproven: no HIBP, no
favicon fetch, no remote KDBX sync (the vault is a local SAF URI).

**`:taxi`**

`api.lyft.com` with **`BOOKING_LIVE = true`, so real charges are possible**, a **hardcoded OAuth
client id and secret**, a pinned app-version string that is a time bomb, and **SPOF** status.
`api.stripe.com` needs an explicit system-CA factory. `payments.braintree-api.com/graphql` is
reached at a **URL read out of the Lyft-supplied client token**. `cn-geo1.uber.com` is
non-functional by the authors' own admission. `m.uber.com` runs through a JS bridge and **logs
GraphQL variables and 4000 characters of each response to logcat**; Uber quotes are unimplemented
and the store listing overstates what works.

**`:fooddelivery`**

`api.deliverycollective.com` (~50 endpoints) is a real third-party aggregator and a **SPOF for the
whole app**. `api.stripe.com` is reached with the **hardcoded `pk_live_` key** described in
Highest-severity findings.

**`:education`**

`www.youtube.com/watch?v=` via the vendored NewPipe extractor, plus `*.googlevideo.com`. A
chronic-breakage SPOF with graceful fallback. **ExoPlayer fetches media outside `NetworkClient`, so
it bypasses the pinned trust bundle.** The curriculum is bundled JSON; there is no content API.

**`:backup`**

An **unvalidated `http://` WebDAV URL** (`BackendFactory.kt:16-20`) sends **HTTP Basic credentials
in the clear**, and the password is also **stored unencrypted** (`BackupConfig.kt:66`). A local SAF
folder is an equally supported alternative.

**`:web`**

Seven user-selectable search engines (DuckDuckGo default), Safe Browsing opted in (host
**UNCERTAIN**), `<origin>/favicon.ico` as a PWA fallback, and `market://`. Global
`cleartextTrafficPermitted="true"` plus user CAs is defensible for a browser and is **well mitigated
in-app**: `ShieldsRequestFilter.kt:54-62` and `LanPolicy.kt:48-63` fail **closed** on DNS failure and
require *all* resolved addresses to be private (a DNS-rebinding defence), and HTTPS upgrade is
applied. Verified absent: DoH, a suggestions endpoint, a runtime filter updater, any third-party
favicon service, and hardcoded DNS IPs.

**`:vpn`**

**Zero hardcoded hosts, zero DNS IPs, zero default endpoints.** Everything comes from a
user-imported `.conf`. Network security config cleartext is false. The only literals are RFC1918
fallbacks, MTU 1280, and port 51820.

### 11.2 Apps with no third-party runtime host

- `:updater` — HTTPS *enforced* by a hard `HttpsURLConnection` cast and no redirects.
- `:translate` — first-run 617 MB NLLB download, SHA-256 pinned, no Hugging Face fallback.
- `:openassistant` — on-device, 24 local IPC tools.
- `:calculator` — a currency tab is the sole reason it holds `INTERNET`.
- `:travel` — one proxy fronting Duffel; every call currently throws, which makes it a **SPOF for a
  money-handling app**.
- `:office`, `:games:voxels` — one E2EE relay each, no STUN/TURN.
- `:cast` / `:cast:tv` — LAN only, ephemeral TLS pinned by fingerprint, no STUN/TURN. The "100%
  offline" claim is accurate.
- `:share` — LAN TCP plus mDNS/BLE.
- `:photos` — holds `INTERNET` but has no host of its own; models ship in the APK.
- `:music` — **no `INTERNET` permission**; no radio, lyrics or artwork APIs.
- `:nowplaying` — `SongMatcher` is a stub; no AcoustID, no Shazam.
- `:speech` — models bundled.
- Fully offline: astronomy, clock, code, contacts, calendar, health, keyboard, launcher, logviewer,
  measure, things, tuner, setupwizard, camera (which *removes* `INTERNET`), and every game except
  voxels.

### 11.3 Cross-cutting patterns

**Plaintext or unauthenticated transport**, worst first: the WhatsApp raw socket; the empty
`checkServerTrusted` and STARTTLS downgrade in `:email`; `stun.l.google.com` over plaintext UDP; the
unvalidated `http://` WebDAV URL in `:backup`; the unvalidated DAV URL in `:everysync`; the
`http://` activation code accepted by `:euicc`; the MMS content-location taken from an inbound PDU;
the loopback traffic tile server in `:maps`; and `:web`'s global cleartext allowance, which is
deliberate and mitigated.

**Volunteer or community-run services carrying production traffic**: `api.transitous.org` (hit at
both runtime *and* as a 10 GB build-time mirror, §12), `api.pipepipe.dev`, `lrclib.net`,
`coverartarchive.org`, Open-Meteo, and — build-side — gorhill/uBlock and the Brave filter lists.
None of these has a commercial support relationship, and several are hit without rate-gating or a
contactable UA.

**Single points of failure**: `chat.signal.org`, `g.whatsapp.net`, `v.whatsapp.net`,
`clients6.google.com`, `stun.l.google.com`, `api.pipepipe.dev`, Open-Meteo, `api.lyft.com`,
`api.deliverycollective.com`, `cable.ua5v.com`, `auroraoss.com`,
`login.microsoftonline.com`, `oauth2.googleapis.com`, `apps.grapheneos.org`, `repo.accrescent.app`.

**Hardcoded credentials shipped in APKs**: Lyft client id and secret; a Stripe `pk_live_` key; a
Tidal `client_id;client_secret`; a WhatsApp client token plus registration salt and public key;
Google BotGuard api-key and request-key; and two public OAuth client ids. See Highest-severity
findings.

### 11.4 Shared network-library controls

- `:library:network`'s manifest sets `cleartextTrafficPermitted="false"` for **every consumer**.
  Only `:web` and `:email` override it (`:vpn`'s override is a no-op).
- There is **no `<pin-set>` anywhere**. Pinning is code-side, via `TrustBundle` over the 20 DER roots
  under `library/network/src/main/assets/ca/`, which are pin-gated at generation time (§7.2).
- **Manifest-merge `INTERNET` leak**: `:notes`, `:flashcards`, `:files`, `:pdf` and `:translate`
  ship with `INTERNET` without declaring it, which makes several `metadata_data/*.md` claims wrong.
- `InitialDownloader.kt:323` uses a raw `HttpURLConnection`, **bypassing the CA bundle**.
- Six modules consume `:library:map`: maps, findfamily, weather, photos, taxi, fooddelivery.
  `:library:image` has no fixed host.
- CARTO is confirmed removed — `library/map/build.gradle.kts` drops `:library:image` because there
  is no raster path, and the "bundled CARTO map" is a `screenshotTest` PNG.

### 11.5 Limits of the host inventory

These bound §11 and §12 specifically, on top of the audit limits in the Scope section.

1. **The WhatsApp media scheme is unverified.** `WhatsAppClient.kt:1100-3639` was not read, so
   whether the `media_conn`-supplied media host is contacted over TLS is not established.
2. **The `NetworkClient.init(` consumer table is incomplete.** Only `:findfamily` and `:passwords`
   are verified; the rest is comment-sourced. Two traps: `:library` is a dependency of every app,
   and `initWithFactory` (`NetworkClient.kt:163`) is a second entry point that a search for `init(`
   misses.
3. **The inventory is closed over *hardcoded* hosts only.** Hosts that are real but not statically
   enumerable are named by role, not by name: `:library:image` targets, the Play and Accrescent
   CDNs, `*.googlevideo.com`, WhatsApp media, Signal CDN3 and TURN, Duffel logos, the Hugging Face
   LFS CDN, and the two unbounded build-time registry host sets (§12).
4. **Not read line by line**: roughly 12 `:vpn` packet and UI files, the `scripts/maps` crate `.rs`
   bodies, `library/ui/odf/`, and the `library/ml` examples.
5. The inventory was built by directory-walking and targeted reads across roughly 15 parallel
   searches, without a grep tool. Coverage is high but is not a mechanical sweep.

---

## 12. Build-time and data-prep hosts

Contacted from a developer machine or a CI runner, never from a user device. Where a risk is written
up above, this section only names the host.

**Toolchain and dependency resolution.** `services.gradle.org` (SHA-256 verified, §1.2);
`dl.google.com`, `repo.maven.apache.org` and `plugins.gradle.org` (no verification metadata, §1.1);
`build-artifacts.signal.org` (§2.1); `crates.io` (`--locked`, §4); `static.rust-lang.org`;
`proxy.golang.org`, `sum.golang.org` and `github.com` for the unblockjam Go tools (hash-pinned);
`cdn.azul.com` for the JDK; Ubuntu apt mirrors; `github.com` for Actions; and `api.github.com`,
including the `gh release create` upload in `release.sh` (§6.5).

**`raw.githubusercontent.com` — five consumers.**

| Consumer | Pinning |
|---|---|
| Material Symbols (`LauncherIconGen.kt`, plus the second site at `android.yml:174`) | Full commit SHA (§7.5) |
| simdjson and zstd (`scripts/geocoder_gen.sh`) | Full commit SHAs (§7.4) |
| brave/adblock-resources | **`master` — mutable, unpinned** |
| gorhill/uBlock | **`master` — mutable, unpinned, and executed** (Highest-severity findings) |
| unicode-org/cldr | **`main` — mutable, unpinned** |

**TLS trust anchors.** `curl.se/ca/cacert.pem`, with `letsencrypt.org`, `pki.goog`,
`cacerts.digicert.com` and `www.amazontrust.com` as per-root fallbacks. Pin-gated (§7.2).

**Model weights.** `huggingface.co` — six direct fetches plus five `snapshot_download` repos, all
revision-pinned and digest-verified (§8). The LFS CDN behind it is dynamic.

**Map and geospatial data.**

- `download.geofabrik.de` — OSM extracts.
- `demo-bucket.protomaps.com` — the original upstream basemap; a size mismatch is a warning only,
  and upstream is reported dead.
- `github.com` and `api.github.com` — go-pmtiles, pinned and checksummed (§7.1).
- `naciscdn.org` — **the only source for two shipped layers**, extracted with `unzip -o` and no
  traversal guard. A **SPOF** for those layers.
- `api.transitous.org/gtfs/` — roughly 2250 zip files, about 10 GB, soft-fail, **zero checksums**.
  Same volunteer-run organisation the runtime hits (§11.1).
- `codeload.github.com` at `refs/heads/main` — mutable.
- **An unbounded set of GTFS agency hosts.** Third-party API keys transit these, and an `http://`
  entry in the agency list would be fetched in plaintext.
- Cloudflare R2 upload, using `R2_*` / `AWS_*` credentials from a sourced `.env`.

**Other data prep.**

- `static.openfoodfacts.org` — roughly 1.2 GB into a `:health` asset, **no checksum**, redirecting
  to an unnamed S3 bucket.
- `unicode.org` — emoji data.
- The Brave filter-list catalog — **a second unbounded host set**, since the list of lists is read
  from upstream rather than hardcoded.
- `calendar.google.com` — `tools/holidaygen/HolidayGen.kt` issues roughly **13,700 sequential,
  unthrottled GETs** with a spoofed `Mozilla/5.0 (holidaygen)` UA. The build file's "Thunderbird"
  comment describes a different source and is wrong.

**Cleared.** `wikidesc` reads a local dump and deliberately avoids the API.
`scripts/networklocation` is an OSM geocoder, not a Wi-Fi harvester. Also clear: the other three
maps crates, the chess/voxels/pipes/wordmaker generators, the install scripts, both `third_party`
`build.rs` files, and the remaining build-logic. The only plaintext URL anywhere in build scope is
`http://tdc-www.harvard.edu/...` in an inert docstring.

---

## 13. Absent risks worth recording

These were checked and are not present, or are present by decision. They are listed so future audits
need not re-derive them.

- No git hooks.
- No git submodules.
- No Gradle init scripts.
- No `curl | sh` style pipe-to-shell installers anywhere in the tree.
- No dynamic, range, or `-SNAPSHOT` dependency versions.
- No `mavenLocal()`, and no per-module repositories (`FAIL_ON_PROJECT_REPOS`).
- One committed binary only: `gradle/wrapper/gradle-wrapper.jar`.
- CI installs build prerequisites with `apt-get` and no version constraints. This is deliberate:
  pinning apt versions breaks the build on every GitHub runner-image roll, which costs more than the
  reproducibility it buys.
