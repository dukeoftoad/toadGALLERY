# Phase 4 format-support audit

**Status:** closes the roadmap Phase 4 still-open item "Audit
support for HEIC/HEIF, AVIF, RAW, animated formats, and unusual video
formats; state actual device/library limitations" and delivers the
"Format-support matrix and tested sample corpus" listed under Phase 4
(`roadmap.md`, Phase 4 tasks/deliverables).\
**Code form:** `io.github.dukeoftoad.gallery.metadata.format.FormatSniffer`
(the magic-byte sniffer), `FormatSupportCatalogue` (this matrix as typed
Kotlin rows), `FormatSnifferTest` + `FormatSupportCatalogueTest` (the
tested sample corpus). No UI wiring this round --- see [Adoption](#adoption-phase-5).

**Verification snapshot.** Every platform/library claim below was checked
against the exact artifacts the app pins (`gradle/libs.versions.toml`):
`androidx.exifinterface` **1.4.1** (its sources jar), Coil **2.7.0** (its
`coil-gif` source), media3 **1.11.1** (its official supported-formats
page), and Android's official supported-formats / `ImageDecoder` /
`ExifInterface` reference pages. The current official `ExifInterface`
class reference states the read list with HEIC explicitly qualified
"(on API 28+)"; the 1.4.1 sources jar states the list without that
qualifier but gates HEIC in code at SDK 28 (both quotes below). Facts that
could NOT be re-verified against a primary source this round are flagged
in [Unverified register](#unverified-register); where an official source
exists, no claim below rests on memory alone.

---

## The four format truths (and which two this audit adds)

toadGALLERY already separates two format truths:

1. **App-policy truth** ---
   `metadata/format/MetadataFormatMatrix.kt` (+ `FormatCapability.kt`):
   what toadGALLERY's pinned parser set claims to read/write per MIME
   type this round. Declared, data-driven, JVM-tested
   (`MetadataFormatMatrixTest`).
2. **File truth** --- the per-file snapshot fields
   (`metadata/MediaMetadataSnapshot.kt`): "this photo has no GPS", not
   "GPS is unsupported".

This audit adds the two missing layers the roadmap item demands:

3. **Byte truth** --- `FormatSniffer`: what a file's OWN leading bytes
   prove it is, independent of the MediaStore MIME row (which comes from
   the file extension at scan time). Detects the format family from
   leading bytes; honest `UNKNOWN` for unrecognized/truncated input;
   never guesses, never defaults to JPEG.
4. **Device-and-library truth** --- `FormatSupportCatalogue`: what
   Android's platform decoders, `androidx.exifinterface` 1.4.1 and the
   app's media3 1.11.1 stack ACTUALLY do, per sniffed family, with every
   claim sourced (this document is the citation authority; the enum
   KDocs carry the same URLs).

The split exists so the future unsupported-format surface can say "THIS
device cannot decode HEIF" (layer 4) without either repeating the policy
matrix (layer 1) or inventing facts.

## What the app actually does today (decode-path evidence)

The app's decode paths, as the code branches today --- this is the
"ACTUAL decode path TODAY" column of the matrix, in repo-file terms:

| Path | What runs | Evidence |
| --- | --- | --- |
| Grid stills (all images) | Coil `AsyncImage` on the app-wide loader: default registry (`BitmapFactoryDecoder` + fetchers). GIF cells decode the static first frame through the same default registry. | `ToadGalleryApplication.kt` L179–182 (`newImageLoader`); class KDoc L172–177 ("GIF cells need no extra decoder --- `BitmapFactoryDecoder` already decodes a GIF's first frame") |
| Grid video thumbnails | Same loader + `VideoFrameDecoder.Factory` (`coil-video`): preview frame via `MediaMetadataRetriever`, purely on-device. | `ToadGalleryApplication.kt` L179–182; `libs.versions.toml` L81–82 (`coil-video` 2.7.0) |
| Viewer stills (non-GIF) | Same app-wide loader; no per-request decoder factory. | `ui/viewer/ViewerScreen.kt` L779–794 (the factory is attached only when `item.isGif`) |
| Viewer GIFs | Per-request `decoderFactory`: `ImageDecoderDecoder.Factory()` (wraps `android.graphics.ImageDecoder`) on `Build.VERSION.SDK_INT >= P` (API 28+), coil's software `GifDecoder.Factory()` on API 26/27. GIF detection is the MIME compare `mimeType == "image/gif"` (`MediaItem.isGif`, `data/MediaItem.kt` L81–82). | `ui/viewer/ViewerScreen.kt` L779–794 + the PhotoPage KDoc L720–730 |
| Viewer video | media3 `ExoPlayer` on `DefaultRenderersFactory(context).setEnableDecoderFallback(true)`, autoplaying the local content URI; a playback error flips an inline retry state (not the shared unsupported surface --- that is the Phase 5 adoption point). Decoder fallback exists because of the issue #65 device log: 10-bit HEVC (`hvc1.2.4.H123.90`) in MKV rejected by `c2.qti.hevc.decoder` (`NO_EXCEEDS_CAPABILITIES`), recovered by falling through to a capable init. | `ui/viewer/ViewerScreen.kt` L895–928 (VideoPage) + KDoc L863–878; `libs.versions.toml` L87–88 (`media3-exoplayer`/`media3-ui` 1.11.1) |
| AI / canonical decode pipeline | `AndroidBitmapDecoder`: `BitmapFactory.decodeByteArray` (sampled, ARGB_8888) + rotation + scale, on the pure `CanonicalDecodePlanner`'s plan. | `media/decode/CanonicalImageDecoder.kt` L92–127; `CanonicalDecodePlan.kt` |
| EXIF read | `AndroidExifReader`: `androidx.exifinterface.ExifInterface(ByteArrayInputStream(bytes))`, catches only `IOException`; the read list therefore follows the library's supported-format table (below). | `metadata/exif/ExifReader.kt` L403–422 |
| Video container metadata | `MediaMetadataRetrieverVideoMetadataSource`: rotation/duration/date through `MediaMetadataRetriever` over a descriptor-backed Uri (no `setDataSource(ByteArray)` overload exists --- no plaintext temp files). | `metadata/video/VideoMetadataSource.kt` L163–199 |

## The format-support matrix

One row per sniffed family. "Decode" = platform still-image decode across
the app's API 26–36 span (minSdk 26, targetSdk 36, `libs.versions.toml`
L48–50); "EXIF" = `androidx.exifinterface` 1.4.1 (the pinned version);
"Playback" = the app's media3 1.11.1 stack (platform decoders only --- no
software-decoder extensions are built, per `libs.versions.toml`).

| Family (`SniffedFormat`) | Sniff signature | Still decode, API 26–36 | Animated | EXIF read (androidx 1.4.1) | EXIF write (androidx 1.4.1) | Playback (media3 1.11.1) |
| --- | --- | --- | --- | --- | --- | --- |
| JPEG | `FF D8 FF` | All releases (base + progressive) | Static only | Yes | Yes (JPEG) | n/a |
| PNG | `89 50 4E 47 0D 0A 1A 0A` | All releases | Static only (APNG undocumented by platform) | Yes | Yes (PNG) | n/a |
| GIF | `GIF87a` / `GIF89a` | All releases | **Wired in viewer** (26/27 software, 28+ ImageDecoder) | No standard carrier | No | n/a |
| WebP `VP8 ` | `RIFF….WEBP` + `VP8 ` chunk | All releases | Static only | Yes | Yes (WebP) | n/a |
| WebP `VP8L` | `RIFF….WEBP` + `VP8L` chunk | All releases (lossless decode since Android 4.2.1 — below minSdk) | Static only | Yes | Yes (WebP) | n/a |
| WebP `VP8X` | `RIFF….WEBP` + `VP8X` chunk | All releases | Has animated variant; **not wired** (factory attached only for `image/gif`) | Yes | Yes (WebP) | n/a |
| HEIF/HEIC | `ftyp` brands heic/heix/heim/heis/hevc/hevx/mif1/msf1 | **Device-optional from API 28**; nothing on 26/27 | Sequences exist; not wired | **API 28+** (empty below) | No | n/a (see hevc-brand caveat) |
| AVIF | `ftyp` brands avif/avis | **8-bit from API 31** (10-bit optional); nothing on 26–30 | `avis` sequences exist; platform animation undocumented | **API 31+** (empty below) | No | n/a |
| TIFF (plain) | `II* 00` / `MM 00*` | Not decoded by platform | Static only | Not in 1.4.1 read list | No | n/a |
| Canon CR2 | TIFF magic + bytes 8–11 `43 52 02 00` | Not decoded (no RAW develop) | Static only | Yes (CR2) | No | n/a |
| DNG | TIFF magic + DNGVersion tag `0xC612` in IFD0 | Not decoded by platform | Static only | Yes (DNG) | No | n/a |
| Nikon NEF/NRW (candidate) | TIFF magic + Make `NIKON…` | Not decoded (no RAW develop) | Static only | Yes (NEF, NRW) | No | n/a |
| Sony ARW (candidate) | TIFF magic + Make `SONY…` | Not decoded (no RAW develop) | Static only | Yes (ARW) | No | n/a |
| MP4 family | `ftyp` brands isom/iso2/mp41/mp42/avc1/dash/mp4*… | n/a (frames via MediaMetadataRetriever thumbnails) | n/a | No standard carrier (udta EXIF non-standard, policy UNSUPPORTED) | No | **Listed YES** by media3 (device codecs) |
| QuickTime MOV | `ftyp` major brand `qt  ` | n/a | n/a | No standard carrier | No | Demuxes in practice; **no dedicated media3 container row** |
| 3GPP/3GPP2 | `ftyp` brands `3gp*` / `3g2*` | n/a | n/a | No standard carrier | No | Demuxes in practice; no dedicated row; H.263 decoders optional since Android 7.0 |
| Matroska/WebM | EBML magic `1A 45 DF A3` | n/a | n/a | No standard carrier | No | **Listed YES** for both (family-level sniff; DocType not parsed) |
| AVI | `RIFF….AVI ` | Not decoded | n/a | No standard carrier | No | **No media3 extractor** --- recognized-but-unplayable |
| UNKNOWN | (none --- unrecognized/truncated) | No claim | No claim | No claim | No claim | No claim |

The same matrix in typed, test-pinned form is
`FormatSupportCatalogue` (`FormatSupportCatalogueTest` fails if a family
gains or loses a row or a verdict silently).

Two classification caveats the sniffer states up front and the wiring
round must remember:

- **`hevc`/`hevx` are HEIF *sequence* brands**, and some HEVC camcorder
  files carry them: such a file sniffs as HEIF (the image reading) even
  though it is really video. The Phase 5 wiring should treat a HEIF
  verdict on a MediaStore *video* row with suspicion --- the two truth
  layers (bytes vs MIME row) disagree, and that disagreement is itself
  the signal.
- **NEF and NRW cannot be separated by these bytes** (both are Nikon
  TIFF-magic RAWs carrying Make "NIKON"), so the Nikon candidate row
  covers both; the row's name is the naming convention, not a claim the
  bytes distinguish the two.

## EXIF read truth (androidx.exifinterface 1.4.1)

Verified against BOTH the official class reference
(<https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface>)
and the pinned 1.4.1 sources jar
(<https://dl.google.com/android/maven2/androidx/exifinterface/exifinterface/1.4.1/exifinterface-1.4.1-sources.jar>,
`ExifInterface.java` class KDoc):

> "Supported for reading: JPEG, PNG, WebP, HEIC, DNG, CR2, NEF, NRW, ARW,
> RW2, ORF, PEF, SRW, RAF, AVIF (on API 31+). Supported for writing:
> JPEG, PNG, WebP."

API gates, verified in the 1.4.1 source itself:

- **HEIC below SDK 28**: `getHeifAttributes` throws
  `UnsupportedOperationException("Reading EXIF from HEIC files is
  supported from SDK 28 and above")`; the constructor's `loadAttributes`
  catches `IOException | UnsupportedOperationException` and IGNORES it
  ("Ignore exceptions in order to keep the compatibility with the old
  versions of ExifInterface"). So on API 26/27 a HEIC read yields EMPTY
  attributes --- no data and no crash. The same source comment states the
  mechanism: `MediaMetadataRetriever` `METADATA_KEY_EXIF_OFFSET`/`LENGTH`
  "was added in SDK 28 for HEIC and in SDK 31 for AVIF".
- **AVIF below SDK 31**: same path, same swallow --- empty attributes.
- **Consequence for the app**: `AndroidExifReader` returns `null`-ish
  results (no tags) for HEIC on API 26/27 and AVIF on API 26–30 even when
  the files carry EXIF; the Details view already renders that honestly as
  "no EXIF" via the availability states. The app-policy matrix
  (`MetadataFormatMatrix`) keeps `image/heic`/`image/heif` EXIF PRESENT
  and `image/avif` UNSUPPORTED --- accurate for the 28+ devices that
  dominate the install base, and the catalogue rows now state the 26/27
  leg explicitly.

## EXIF write truth (and R15's dependency on it)

`ExifInterface.saveAttributes`, 1.4.1 source (verified):

- Only **JPEG, PNG, WebP** are writable: other formats fail with
  `IOException("ExifInterface only supports saving attributes for JPEG,
  PNG, and WebP formats.")`.
- The instance must be **file-backed** (file path or seekable descriptor):
  stream-constructed instances (what `AndroidExifReader` builds today)
  fail with `IOException("ExifInterface does not support saving
  attributes for the current input.")`.
- The save is a **whole-file rewrite staged through the library's own
  temp file** (source: `tempFile` copy + restore-on-failure + delete in
  `finally`); the reference page's own words: "This is expensive because
  it involves copying all the data from one file to another and deleting
  the old file and renaming the other."

R15's metadata-write round depends on all three facts: its write path
must open files by descriptor (not stream), accept the temp-file staging
inside the vault/media boundary, and restrict the editable surface to
JPEG/PNG/WebP --- anything broader needs a different writer, which is a
new ADR, not a silent stretch.

## Animated formats

- **GIF**: animated in the viewer TODAY ---
  `ImageDecoderDecoder.Factory()` on API 28+ / coil's software
  `GifDecoder.Factory()` on 26/27, attached per-request only when
  `item.isGif` (`ui/viewer/ViewerScreen.kt` L779–794). The grid renders
  static first frames by design (issues #33/#34 pinned behavior;
  `ToadGalleryApplication` KDoc).
- **Animated WebP** (`VP8X` + ANIM subchunk): the platform can animate it
  --- `ImageDecoder`'s reference: "If the encoded image is an animated GIF
  or WEBP, `decodeDrawable` will return an `AnimatedImageDrawable`"
  (<https://developer.android.com/reference/android/graphics/ImageDecoder>)
  --- and coil-gif 2.7.0's `ImageDecoderDecoder` is documented for "GIFs,
  animated WebPs, and animated HEIFs"
  (<https://github.com/coil-kt/coil/blob/2.7.0/coil-gif/src/main/java/coil/decode/ImageDecoderDecoder.kt>).
  But the app attaches that factory only for the `image/gif` MIME type,
  so an animated WebP renders its static first frame today. Detecting the
  ANIM subchunk is deeper parsing this round's sniffer does not do; the
  `VP8X` verdict says "could be animated", which is exactly what the
  leading bytes prove.
- **HEIF sequences** (`msf1`/`hevc`/`hevx` brands): classified HEIF (the
  image reading). Platform documentation names animated GIF and WebP only
  for `AnimatedImageDrawable`; coil 2.7.0's own KDoc documents animated
  HEIF decode "only supported on API 30 and above" --- a library claim,
  not a platform promise, and not wired.
- **Animated AVIF** (`avis`): not documented as platform-animatable
  anywhere official; treat as static until the on-device gate proves
  otherwise.
- **APNG**: not documented by the platform (same ImageDecoder reference
  names GIF/WebP only) --- renders as a still; PNG row says so.

## Video containers and codecs

Container truth from media3's official supported-formats page
(<https://developer.android.com/media/media3/exoplayer/supported-formats>):

- Container table lists **MP4 YES, WebM YES, Matroska YES** (also M4A,
  FMP4, MP3, Ogg, WAV, MPEG-TS, MPEG-PS, FLV, ADTS, FLAC --- the audio
  and stream containers are out of this sniff's scope, honestly UNKNOWN).
- **No dedicated row for MOV, 3GP/3G2 or AVI.** MOV and 3GP are
  ISO-BMFF-family containers that the MP4 extractor demuxes in practice,
  but this audit does not turn "in practice" into a promise: the rows say
  demuxes-in-practice-not-listed, and on-device verification belongs to
  the release gate. AVI has no media3 extractor listed --- the honest
  verdict is recognized-but-unplayable, surfaced as unsupported rather
  than a frozen black frame.
- **Codecs are the DEVICE's, not media3's**: "By default ExoPlayer uses
  Android's platform decoders. Hence the supported sample formats depend
  on the underlying platform rather than on ExoPlayer … Note that
  individual devices may support additional formats beyond those listed."
  The repo's standing evidence is issue #65 (10-bit HEVC in MKV rejected
  by a Qualcomm hardware decoder, recovered by the viewer's
  `setEnableDecoderFallback(true)`). Software-decoder extensions exist
  ("must be manually built and included") and this app builds none
  (`libs.versions.toml` carries only `media3-exoplayer` + `media3-ui`).
- **H.263 caveat for 3GP**: the platform supported-formats video table
  states "Support for H.263 is optional in Android 7.0+" --- an
  H.263-only 3GP may have no decoder on a modern device
  (<https://developer.android.com/media/platform/supported-formats>).

## Per-API-level span (26 → 36)

What changes across the app's supported span, for the families above:

| API level | What changes |
| --- | --- |
| 26–27 (8.0/8.1) | No `ImageDecoder` (added 28): stills decode via BitmapFactory/Coil defaults (JPEG/PNG/GIF/WebP); GIF animation is coil's software `GifDecoder`; HEIF/AVIF: no platform decode, no androidx EXIF (both gates are 28/31); `isMimeTypeSupported` unavailable. |
| 28 (9, P) | HEIF platform decode added --- **device optional** ("Android 9.0+ Device optional. Check ImageDecoder.isMimeTypeSupported("image/heif") for 8-bit support. 10-bit decoding requires optional HEVC Main10 profile" --- supported-formats page); androidx reads HEIC EXIF (MMR EXIF keys, SDK 28); viewer GIF animation switches to `ImageDecoderDecoder`. |
| 29 (10, Q) | `ImageDecoder.isMimeTypeSupported` added (API 29, per the reference page) --- the runtime check the supported-formats page recommends becomes callable; ACCESS_MEDIA_LOCATION redaction of EXIF location begins (handled by the r14 round, out of scope here). |
| 30 (11, R) | No still-decode gate changes for these families; coil 2.7.0 documents animated-HEIF decode from API 30 (library claim, unwired). |
| 31 (12, S) | AVIF **8-bit** platform decode added ("Android 12+ … 10-bit decoding is optional; check ImageDecoder.isMimeTypeSupported("image/avif") for 10-bit support (even if false, 8-bit still works)" --- supported-formats page); androidx reads AVIF EXIF (MMR EXIF keys, SDK 31). |
| 32–36 (12L–16) | No further still-image decode gates for these families between API 31 and 36 in the official tables: HEIF remains device-optional-from-28, AVIF 8-bit-from-31 with 10-bit optional. (AVIF *encoding* arrives via MediaCodec on 14; the app encodes nothing.) |

Device-dependence doctrine: HEIF/AVIF decode support is a
`MediaCodec`/OEM property the official pages themselves gate with runtime
checks. This audit therefore states support LEVELS with their check
mechanism, and the on-device release gate (below) runs those checks on
the actual device matrix rather than the doc asserting every OEM build.

## Honest device/library limitations (the register)

1. **No RAW develop.** CR2/DNG/NEF/NRW/ARW rows: androidx 1.4.1 reads
   their EXIF; nothing in the platform's supported image formats (nor in
   this app) decodes the sensor image. OEM-added RAW decoders are
   device-dependent and unpromised. A RAW file shows metadata + the
   unsupported-decode surface.
2. **No EXIF write outside JPEG/PNG/WebP** (1.4.1 `saveAttributes`
   rejects everything else); writes are file-backed and temp-file-staged.
3. **HEIC EXIF is empty on API 26/27; AVIF everything is empty below
   API 31** --- library-gated, silently (swallowed exceptions), so the UI
   must never read "no tags" as "no EXIF exists" for those families.
4. **HEIF decode is device-optional from API 28; AVIF 10-bit is optional
   from API 31.** No promise is made per device; the release gate checks.
5. **Video codecs are the device's MediaCodec set** (media3's own
   doctrine quote); 10-bit HEVC needed decoder fallback on a real device
   (issue #65); H.263 is optional since Android 7.0.
6. **AVI is recognized but unplayable** in this stack (no media3
   extractor, no platform row).
7. **Animated WebP/HEIF-sequence/AVIF-seq are not wired**; only GIF
   animates, and only in the viewer (grid is static by design).
8. **Sniffer gaps, deliberate and honest** (all land in UNKNOWN, never a
   guess): WAV/M4A audio containers, BMP, MPEG-TS (its 0x47 sync byte
   repeats throughout the stream --- leading bytes cannot prove it), FLV,
   and the non-TIFF RAW magics ORF (`IIRO`), RW2 (`IIU\0`), RAF
   (`FUJIFILMCCD-RAW`). Matroska-vs-WebM separation needs the EBML
   DocType element, not parsed this round (family-level verdict).
9. **Proof levels are stated per family**: magic-level (JPEG/PNG/GIF/
   EBML), structure-level (the `ftyp` brand scan; the TIFF IFD0 walk for
   DNGVersion/Make), convention-level (NEF/ARW: the Make tag proves the
   MAKER; the NEF/ARW identification is the naming convention for that
   maker's TIFF-magic RAWs, and NEF cannot be separated from NRW by
   these bytes at all).

## The tested sample corpus

`FormatSnifferTest` (35 JVM tests, hex-literal byte fixtures) plus
`FormatSupportCatalogueTest` (13 JVM tests pinning every catalogue row)
are the roadmap's "tested sample corpus", at the level the JVM can
honestly test: **signature-level**. The corpus pins every family's valid
magic, the subtype disambiguations (WebP VP8/VP8L/VP8X; ftyp brand
priority including AVIF-over-HEIF), the ambiguous cases (mif1+heic, DNG
converted from NEF --- DNGVersion outranks Make), and the honest
negatives (truncations, invalid GIF versions, unlisted ftyp brands,
out-of-bounds TIFF structures, and the deliberately-unsniffed formats).

**Decode-level validation is the on-device release gate** --- the repo's
established doctrine (ADR-0016: CI compiles and runs the JVM suite;
instrumented checks run on physical devices). The gate this audit hands
to that process, per device:

- `ImageDecoder.isMimeTypeSupported("image/heif")` /
  `("image/avif")` for 8-bit support, and the AVIF 10-bit nuance
  (API 29+ only --- on API 28 the check does not exist and HEIF support
  must be probed by decoding a sample);
- play the corpus videos (MP4/WebM/MKV listed; MOV/3GP in-practice; AVI
  expected to fail honestly) and record which containers the device's
  MediaCodec set actually renders;
- open a HEIC on an API 26/27 device and confirm the EXIF read is empty
  rather than crashed (the swallowed-exception path).

## Adoption (Phase 5)

Deliberately NOT wired this round (R15/R17 own the UI files; this round
is pure Kotlin + docs):

- **The unsupported-format surface** ---
  `ui/media/UnsupportedFileState.kt` (defined in issue #13's frozen
  state-system round, still without call sites): the viewer/editor should
  render it for sniffed families whose catalogue row says the platform
  cannot decode (AVI, UNKNOWN bytes, RAW stills), with the file's name
  and the honest capability text from the catalogue row.
- **Details view rows** --- `ui/details/DetailsPresentation.kt` already
  renders the app-policy matrix (`capabilitiesFor`, L267); the sniffed
  family + its device/library row extend the same section (e.g. "bytes
  say WebP, MediaStore said JPEG").
- No string resources were added this round; the adoption round owns the
  user-facing copy.

## Unverified register

Citation discipline cuts both ways; these specific constants are pinned
by the corpus but were NOT re-verified against a primary spec this round
(no public Canon spec exists; the Adobe DNG/TIFF specification PDFs were
not parsed in this sandbox):

- the Canon **CR2 marker** `43 52 02 00` at bytes 8–11 (industry-wide
  convention; no primary source available);
- the **DNGVersion tag `0xC612` being IFD0-required** by Adobe's DNG
  specification (spec home:
  <https://helpx.adobe.com/camera-raw/digital-negative.html>);
- the **TIFF 6.0 magic** `II*\0`/`MM\0*` byte order marks (spec home:
  Adobe TIFF 6.0, <https://download.osgeo.org/libtiff/doc/TIFF6.pdf>).

All three are long-stable, cross-implementation constants; if any drifts,
the corpus fails loudly rather than the app guessing.

## Sources

1. `androidx.exifinterface.media.ExifInterface` class reference ---
   <https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface>
2. `androidx.exifinterface:exifinterface:1.4.1` sources jar (the pinned
   version's own source) ---
   <https://dl.google.com/android/maven2/androidx/exifinterface/exifinterface/1.4.1/exifinterface-1.4.1-sources.jar>
3. Android supported media formats (image + video tables, H.263 note) ---
   <https://developer.android.com/media/platform/supported-formats>
4. `android.graphics.ImageDecoder` reference (AnimatedImageDrawable for
   animated GIF/WEBP; `isMimeTypeSupported` "Added in API level 29") ---
   <https://developer.android.com/reference/android/graphics/ImageDecoder>
5. media3 / ExoPlayer supported formats (container table; platform-decoder
   doctrine; software-extension note) ---
   <https://developer.android.com/media/media3/exoplayer/supported-formats>
6. Coil 2.7.0 `coil-gif` `ImageDecoderDecoder` (GIFs, animated WebPs and
   HEIFs; animated HEIF API 30+) ---
   <https://github.com/coil-kt/coil/blob/2.7.0/coil-gif/src/main/java/coil/decode/ImageDecoderDecoder.kt>
7. Repo evidence: `ToadGalleryApplication.kt`, `ui/viewer/ViewerScreen.kt`,
   `data/MediaItem.kt`, `metadata/exif/ExifReader.kt`,
   `metadata/video/VideoMetadataSource.kt`,
   `metadata/format/MetadataFormatMatrix.kt`, `ui/details/DetailsPresentation.kt`,
   `ui/media/UnsupportedFileState.kt`, `media/decode/CanonicalImageDecoder.kt`,
   `gradle/libs.versions.toml` (file/line references throughout the
   tables above).
