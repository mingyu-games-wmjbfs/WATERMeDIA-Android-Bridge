# Release notes

> `tools/upload-github.ps1 -ReleaseTag ...` uses this file together with
> [`RELEASE-NOTES.md`](RELEASE-NOTES.md) (Chinese) as the GitHub release body, so both languages ship with
> every release. Update both when publishing a new version.

A patch mod that makes WATERMeDIA / WATERFrAMES work on Android by bundling VLC for Android.

**1.0.5 covers four builds**: `1.21.1 Fabric` · `1.21.1 NeoForge` · `1.20.1 Fabric` · `1.20.1 Forge`
(the functional code is byte for byte identical in all four — pick the one matching your setup).

## Requirements

| Item | Requirement |
|---|---|
| Minecraft | **1.20.1** or **1.21.1** (client) |
| Loader | **Forge 47.x**, **Fabric (loader 0.19.5+, needs Fabric API)** or **NeoForge 21.1.235+** |
| Required dependency | WATERMeDIA **2.1.36 / 2.1.37** |
| Launchers | Android Java Edition launchers such as PojavLauncher and FCL |
| Architecture | arm64-v8a / armeabi-v7a / x86_64 (no 32-bit x86) |

**Pick the attachment that matches your setup** (all three jars are functionally identical, only the loader
differs):

| Your setup | Jar to use |
|---|---|
| 1.20.1 + Forge 47.x | `watermedia_android_bridge-1.0.5+mc1.20.1-forge.jar` |
| 1.20.1 + Fabric (loader 0.19.5+, needs Fabric API) | `watermedia_android_bridge-1.0.5+mc1.20.1-fabric.jar` |
| 1.21.1 + Fabric (loader 0.19.5+, needs Fabric API) | `watermedia_android_bridge-1.0.5+mc1.21.1-fabric.jar` |
| 1.21.1 + NeoForge 21.1.x | `watermedia_android_bridge-1.0.5+mc1.21.1-neoforge.jar` |

> Not for WATERMeDIA 3.x (FFmpeg backend, without the VLC discovery hook this mod relies on).

## Installation

1. Keep WATERMeDIA 2.1.36 or 2.1.37 in your `mods/` folder;
2. Put the jar attached below into the same `mods/` folder;
3. Start the game. The first launch extracts about 43 MiB of native libraries into **app-internal storage**;
   later launches reuse them.

These two lines in `latest.log` mean it worked:

```
[VideoLan4J/NativeDiscovery/INFO] Successfully loaded VLC 3.0.23 Vetinari in '...' using 'WaterMedia Config Provider'
[watermedia/Bootstrap/INFO] Module PlayerAPI loaded successfully
```

**WATERFrAMES needs no patch of its own** — as soon as VLC is available, its screens, projectors and block
music recover automatically.

## Changes in 1.0.5

### Added: Minecraft 1.20.1 support for Forge and Fabric

The same patch now ships as four builds (`+mc1.20.1-forge`, `+mc1.20.1-fabric`, `+mc1.21.1-fabric` and
`+mc1.21.1-neoforge`) — **pick the one matching your setup**. The 1.20.1 builds are not rewrites;
only the loader layer changes:

* **All functional sources are shared**: bundled VLC, app-internal extraction, the `JNI_OnLoad` preload,
  the `android.os.Environment` stub, the LWJGL aligned-allocation fallback, the GL ES safe video upload
  and the video chain logging only talk to WATERMeDIA + LWJGL + VLC, so they are byte-identical across
  both builds (12 files);
* **Only the entry point and metadata differ**: Forge uses `@Mod` plus `TickEvent.ClientTickEvent`
  (`Dist.CLIENT`) with `META-INF/mods.toml` + `pack_format 15` and registers its mixin config through
  the **jar manifest `MixinConfigs`**; Fabric uses `fabric.mod.json` with the **`preLaunch` and `client`
  entrypoints**, a `mixins` list and `pack_format 15`; NeoForge is unchanged (`neoforge.mods.toml` +
  `[[mixins]]` + `pack_format 34`);
* **The WATERMeDIA dependency is unchanged** (`[2.1.36,3.0.0)`): WATERMeDIA 2.1.37 is published for
  1.16.5 / 1.18.2 / 1.19.2 / 1.20.1 / 1.21.1 / 1.21.5 on fabric / forge / neoforge, contains
  `ForgeLoader`, `NeoFLoader` and `FabricLoader`, and has **zero `net/minecraft/` references** in its
  967 classes — so both builds use the very same prerequisite jar;
* **Why Fabric needs `preLaunch`**: Fabric has no load-order attribute and WATERMeDIA is a dependency,
  so it initialises **first**; videolan4j's `NativeDiscovery.start()` is **one-shot** (after its first
  failed attempt an internal `attempted` flag makes every later call return `false`).  The payload
  therefore has to be in place before WATERMeDIA initialises.
* **More robust game directory detection**: Fabric's `FabricLoader.getGameDir()`, NeoForge's and
  Forge's `FMLLoader.getGamePath()` and `FMLPaths.GAMEDIR.get()` are tried in turn, with a warning
  (and `-Dwatermedia.androidbridge.gameDir=<path>`) only when everything fails.

All four jars contain the **same functional code byte for byte**; only the loader entry point and
metadata differ.

**Fabric notes**: it additionally requires Fabric API, its WATERMeDIA range is widened to **2.1.24 -
2.1.37** (Forge/NeoForge stay on 2.1.36+), and it is compiled against 2.1.24 - the oldest supported version -
so an API that only exists in the newer generation cannot slip in.  WATERMeDIA 2.1.24 - 2.1.35 allocate with
`MemoryAllocator.malloc()` and throw `OutOfMemoryError` when it returns NULL (the "mod loading has failed"
crash fixed back in 1.0.3), so the bridge now covers that allocator generation too; the video upload injection
point has an identical descriptor in both generations. The bundled VLC and the payload version (`vlc3.0.23-android3.7.1-r2`) are unchanged, so upgrading
within this version does not re-extract anything.

### Fixed: white video screen (audio fine, picture frozen on the first frame)

Fixes **"audio plays fine but the video picture is stuck on the first frame (a plain white quad)"**.
Two independent causes are addressed, and every step of the video chain is now logged, so if it still
misbehaves the log itself says where it stops:

1. **The OpenGL pixel type (most likely culprit)** — WATERMeDIA uploads video frames with
   `GL_UNSIGNED_INT_8_8_8_8_REV` (0x8367), a **desktop-only** type. OpenGL ES rejects it with
   `GL_INVALID_ENUM`, so `glTexImage2D` never allocates texture storage, Minecraft samples an
   incomplete texture and the screen stays a flat white quad while the audio keeps playing. The bridge
   now takes over `RenderAPI.uploadBuffer` and uses `GL_UNSIGNED_BYTE`, which both desktop GL and GL ES
   accept: for `GL_RGBA` the memory layout is byte for byte identical, so the picture is unchanged. It
   adds GL error checking, re-allocation when the frame size changes, and an "allocate empty storage,
   then `glTexSubImage2D`" fallback. This path **never throws** — WATERMeDIA releases its frame semaphore
   only after `uploadBuffer` returns, and an exception there would wedge VLC's video output thread
   forever, which is exactly the "video frozen, audio fine" pattern.
2. **Video output module and decoder** — two Android-specific switches are added: `--vout=vmem` (force
   the callback (vmem) video output WATERMeDIA's video screens need, instead of the
   `android_display`/`android_window` paths that expect a Java `Surface`) and `--avcodec-hw=none` (the
   Android MediaCodec decoders want to decode into a `Surface`; without one they can open successfully
   and then never deliver a picture). Both can be changed in
   `config/watermedia_android_bridge.properties` (`videoOutput` / `hardwareDecoding`, e.g.
   `hardwareDecoding=any` to go back to hardware decoding and save battery).
3. **Diagnosability** — the log now records the video chain: `video player #1 created` →
   `first video frame from VLC: WxH` → `video texture upload works`, plus GL error details and a 30
   second heartbeat. When no frame arrives within 20 seconds of a player being created, the bridge says
   so explicitly ("libvlc delivered no picture").

**Unchanged in this release**: the bundled VLC is the same official VLC for Android 3.7.1 (libvlc
3.0.23) and the payload version `vlc3.0.23-android3.7.1-r2` is unchanged, so upgrading does not
re-extract anything (instant start). Licensing and dependency declarations are unchanged
(`GPL-3.0-or-later`; WATERMeDIA stays a required dependency and is not bundled).

### If the picture is still white, this is what to send

Just the `latest.log` — it names the failing step directly:

| What the log shows | Meaning |
|---|---|
| `video player #N created` but no `first video frame from VLC` | libvlc (video output/decoder) produced nothing; OpenGL is not the problem |
| both lines and `video texture upload works`, screen still white | frames and upload are fine; the problem is on the renderer/screen side |
| `GL error` / `could not upload a video frame` | the driver refused the upload; the error code and every fallback tried are in the same line |

## Older versions (1.0.4 / 1.0.3 and earlier)

* 1.0.4: licence compliance (`GPL-3.0-or-later`, all licence texts inside the jar, SPDX headers, the
  no-longer-needed JNA binary removed);
* 1.0.3 and earlier: made VLC actually load on Android — app-internal extraction (Android's linker
  namespace refuses emulated storage), the `JNI_OnLoad` preload plus the `android.os.Environment` stub
  (fixes the `s_jvm != NULL` assertion) and the LWJGL aligned-allocation fallback (fixes an
  `OutOfMemoryError` during startup).

## Checksums and support

* SHA-256 and detailed documentation are in the repository README;
* If you hit a problem, open an issue with your `latest.log` (keywords `Android Bridge`, `VideoLan4J`) and
  `logs/videolan-discovery.log` if it exists.
