/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.watermedia.WaterMedia;
import org.watermedia.androidbridge.AndroidVlc;
import org.watermedia.androidbridge.BridgeConfig;
import org.watermedia.androidbridge.VlcDirectoryProvider;
import org.watermedia.androidbridge.VlcInstaller;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.loaders.ILoader;
import org.watermedia.videolan4j.VideoLan4J;
import org.watermedia.videolan4j.discovery.NativeDiscovery;
import org.watermedia.videolan4j.factory.MediaPlayerFactory;

/**
 * Desktop-side verification of the bridge's integration contract.  It runs on a
 * normal JVM (with WATERMeDIA on the classpath) and checks the two things that a
 * real Android device cannot be used to test cheaply:
 *
 * <ul>
 *   <li>{@code install}: the payload extraction produces exactly the directory
 *       layout videolan4j's LINUX discovery expects, and WATERMeDIA's
 *       {@code ConfigProvider} accepts the generated
 *       {@code custom_vlc_path.txt}.</li>
 *   <li>{@code discover}: with WATERMeDIA's own Windows VLC as the payload, the
 *       whole discovery chain really loads libvlc - proving the hook works, only
 *       the binary differs on Android.</li>
 * </ul>
 */
public final class BridgeHarness {
    private static int checks;
    private static int failures;

    public static void main(final String[] args) throws Exception {
        final String mode = args.length > 0 ? args[0] : "install";
        final Path gameDir = Paths.get(args.length > 1 ? args[1] : "build/itest-game").toAbsolutePath().normalize();
        System.out.println("== BridgeHarness mode=" + mode + " gameDir=" + gameDir);
        switch (mode) {
            case "install" -> installMode(gameDir);
            case "discover" -> discoverMode(gameDir, Paths.get(args[2]));
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
        System.out.println("== checks=" + checks + " failures=" + failures);
        if (failures > 0) System.exit(1);
    }

    // --------------------------------------------------------------- install mode

    private static void installMode(final Path gameDir) throws Exception {
        deleteRecursively(gameDir.resolve("config"));
        // Force the Android code path so the harness can exercise it on Windows.
        System.setProperty("watermedia.androidbridge.forceAndroid", "true");
        System.setProperty("watermedia.androidbridge.gameDir", gameDir.toString());

        // This is the real load order on Android: the bridge is constructed before
        // WATERMeDIA, so WaterMedia.bootstrap is still null while the payload is
        // installed.  WaterMedia must survive that untouched (its ServiceLoader
        // providers may only be initialised after prepare()).
        AndroidVlc.bootstrapEarly();

        final Path vlcDir = AndroidVlc.vlcDirectory();
        check("bootstrap reports an installed payload", AndroidVlc.isInstalled());
        if (vlcDir == null) return;
        check("libvlc.so extracted", Files.isRegularFile(vlcDir.resolve("libvlc.so")));
        check("libc++_shared.so extracted", Files.isRegularFile(vlcDir.resolve("libc++_shared.so")));
        check("plugins/ directory created", Files.isDirectory(vlcDir.resolve("plugins")));
        check("libvlccore.so alias created", Files.exists(vlcDir.resolve("libvlccore.so")));
        check("version marker written", Files.isRegularFile(vlcDir.resolve("bridge-payload.cfg")));
        check("no third party JNA binary is bundled any more", !Files.exists(vlcDir.resolve("jna")));
        check("config/watermedia/custom_vlc_path.txt points at the payload",
                expectedConfigFile(gameDir).equals(vlcDir.toAbsolutePath().toString()));

        // idempotency: an install of the very same ABI and directory must be a cache hit
        final String installedAbi = vlcDir.getFileName().toString();
        final VlcInstaller.Result second = VlcInstaller.installAt(vlcDir, installedAbi, false);
        check("re-install of " + installedAbi + " is a cache hit", second.ok && !second.extracted);

        // The loader check (System.load of the bundled C++ runtime) is what decides
        // whether a directory is usable on Android.  On this host the payload is a
        // foreign architecture, so a refusal with a reason is the expected outcome -
        // and the bridge must still come up with a usable installation.
        final String refusal = AndroidVlc.probeSharedRuntime(vlcDir);
        System.out.println("  (loader check on this host: " + (refusal == null ? "accepted" : refusal) + ')');
        check("payload directory passes the layout contract", VlcInstaller.layoutUsable(vlcDir));
        check("bridge survives a refused loader check", AndroidVlc.isInstalled());

        // VLC's JNI_OnLoad contract (src/android/specific.c:100-163): the framework class
        // it resolves with FindClass, the five directory constants it reads and the static
        // method it caches.  A missing member makes JNI_OnLoad return -1, and the JavaVM it
        // captured never reaches system_Configure - which then aborts on "s_jvm != NULL".
        try {
            final Class<?> environment = Class.forName("android.os.Environment");
            final Method getDir = environment.getMethod("getExternalStoragePublicDirectory", String.class);
            check("android.os.Environment.getExternalStoragePublicDirectory(String) -> File is present for JNI_OnLoad",
                    getDir.getReturnType() == java.io.File.class
                            && java.lang.reflect.Modifier.isStatic(getDir.getModifiers()));
            int constants = 0;
            for (final String name : new String[] { "DIRECTORY_DOWNLOADS", "DIRECTORY_DOCUMENTS",
                    "DIRECTORY_MUSIC", "DIRECTORY_PICTURES", "DIRECTORY_MOVIES" }) {
                final java.lang.reflect.Field field = environment.getField(name);
                if (field.getType() == String.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && field.get(null) != null) {
                    constants++;
                }
            }
            check("all five android.os.Environment directory constants are static Strings", constants == 5);
            check("java.io.File.getAbsolutePath()/java.lang.System.getProperty(String) are present for JNI_OnLoad",
                    java.io.File.class.getMethod("getAbsolutePath").getReturnType() == String.class
                            && System.class.getMethod("getProperty", String.class).getReturnType() == String.class);
        } catch (final Throwable t) {
            check("VLC's JNI_OnLoad contract is satisfied by the bundled stub (" + t + ')', false);
        }

        // The mixin rescue for WATERMeDIA's aligned native allocations has to point at the
        // exact call site the shipped WATERMeDIA bytecode contains; otherwise the redirect
        // silently does nothing (the config is deliberately not "required").
        try {
            final byte[] renderApi = readFromCodeSource(Class.forName("org.watermedia.api.render.RenderAPI"),
                    "org/watermedia/api/render/RenderAPI.class");
            check("WATERMeDIA's RenderAPI bytecode declares createByteBuffer",
                    containsAscii(renderApi, "createByteBuffer"));
            check("WATERMeDIA's RenderAPI allocates natively through LWJGL (either generation)",
                    containsAscii(renderApi, "org/lwjgl/system/MemoryUtil")
                            && (containsAscii(renderApi, "memAlignedAlloc")      // 2.1.36+
                                    || containsAscii(renderApi, "getAllocator"))); // 2.1.24 - 2.1.35

            final byte[] mixinClass = readFromCodeSource(AndroidVlc.class,
                    "org/watermedia/androidbridge/mixin/RenderAPIMixin.class");
            check("the mixin class is packaged", mixinClass != null);
            check("the mixin targets that same call site",
                    containsAscii(mixinClass, "org/lwjgl/system/MemoryUtil")
                            && containsAscii(mixinClass, "memAlignedAlloc")
                            && containsAscii(mixinClass, "createByteBuffer(II)Ljava/nio/ByteBuffer;"));
            // WATERMeDIA 2.1.24-2.1.35 have no two argument createByteBuffer: they allocate with
            // MemoryAllocator.malloc and throw OutOfMemoryError on NULL, so the bridge needs the
            // single argument injection as well or those versions crash during mod loading.
            check("the allocation mixin also covers the WATERMeDIA 2.1.24-2.1.35 allocator",
                    containsAscii(mixinClass, "createByteBuffer(I)Ljava/nio/ByteBuffer;")
                            && containsAscii(mixinClass, "getAllocator")
                            && containsAscii(mixinClass, "memByteBuffer"));

            final byte[] mixinConfig = readFromCodeSource(AndroidVlc.class, "watermedia_android_bridge.mixins.json");
            check("the mixin config is packaged, non-required and lists RenderAPIMixin",
                    containsAscii(mixinConfig, "org.watermedia.androidbridge.mixin")
                            && containsAscii(mixinConfig, "RenderAPIMixin")
                            && containsAscii(mixinConfig, "\"required\": false"));
        } catch (final Throwable t) {
            check("mixin wiring could be inspected (" + t + ')', false);
        }

        // ---- loader metadata: the same sources ship as Fabric, Forge and NeoForge jars --
        // Only the entry point, the pack format and the mixin announcement differ; the
        // wrong combination silently produces a jar the loader refuses to start, so every
        // piece is checked against the metadata that is actually packaged.
        try {
            final byte[] forgeToml = readFromCodeSource(AndroidVlc.class, "META-INF/mods.toml");
            final byte[] neoForgeToml = readFromCodeSource(AndroidVlc.class, "META-INF/neoforge.mods.toml");
            final byte[] fabricJson = readFromCodeSource(AndroidVlc.class, "fabric.mod.json");
            final int metadataFiles = (forgeToml == null ? 0 : 1) + (neoForgeToml == null ? 0 : 1)
                    + (fabricJson == null ? 0 : 1);
            check("exactly one loader metadata file is packaged (mods.toml XOR neoforge.mods.toml XOR fabric.mod.json)",
                    metadataFiles == 1);
            final String loader = forgeToml != null ? "forge" : neoForgeToml != null ? "neoforge" : "fabric";
            final boolean modsTomlSyntax = "forge".equals(loader);
            System.out.println("  (loader metadata: " + loader + ')');

            final byte[] metadata = "forge".equals(loader) ? forgeToml
                    : "neoforge".equals(loader) ? neoForgeToml : fabricJson;
            check("the metadata declares the mod id watermedia_android_bridge",
                    containsAscii(metadata, "watermedia_android_bridge"));
            check("the metadata declares GPL-3.0-or-later",
                    containsAscii(metadata, "GPL-3.0-or-later"));
            // The Fabric build supports the wider 2.1.24-2.1.37 range (its allocation paths are
            // covered for both generations); Forge and NeoForge stay on 2.1.36+.
            final boolean waterMediaRangeOk = "fabric".equals(loader)
                    ? containsAscii(metadata, ">=2.1.24 <2.1.38")
                    : containsAscii(metadata, "2.1.36");
            check("the metadata requires a supported WATERMeDIA range ("
                            + ("fabric".equals(loader) ? "2.1.24 - 2.1.37" : "2.1.36+") + ')',
                    waterMediaRangeOk && containsAscii(metadata, "watermedia"));

            final byte[] packMeta = readFromCodeSource(AndroidVlc.class, "pack.mcmeta");
            // The pack format follows the declared Minecraft version, not the loader: the bridge
            // now ships four jars (1.20.1 Forge/Fabric and 1.21.1 Fabric/NeoForge).
            final boolean pack15 = containsAscii(metadata, "1.20.1");
            check("pack.mcmeta uses the pack format of its Minecraft version ("
                    + (pack15 ? "1.20.1 = 15" : "1.21.1 = 34") + ')',
                    pack15 ? containsAscii(packMeta, "\"pack_format\": 15")
                            : containsAscii(packMeta, "\"pack_format\": 34"));

            final byte[] entry = readFromCodeSource(AndroidVlc.class,
                    "org/watermedia/androidbridge/AndroidBridge.class");
            check("the entry point class is packaged", entry != null);
            final boolean entryOk = switch (loader) {
                case "fabric" -> containsAscii(entry, "net/fabricmc/api/ClientModInitializer")
                        && containsAscii(entry, "net/fabricmc/loader/api/entrypoint/PreLaunchEntrypoint")
                        && !containsAscii(entry, "net/neoforged/fml/common/Mod")
                        && !containsAscii(entry, "net/minecraftforge/fml/common/Mod");
                case "forge" -> containsAscii(entry, "net/minecraftforge/fml/common/Mod")
                        && !containsAscii(entry, "net/neoforged/fml/common/Mod")
                        && !containsAscii(entry, "net/fabricmc/api/ClientModInitializer");
                default -> containsAscii(entry, "net/neoforged/fml/common/Mod")
                        && !containsAscii(entry, "net/minecraftforge/fml/common/Mod")
                        && !containsAscii(entry, "net/fabricmc/api/ClientModInitializer");
            };
            check("the entry point belongs to this loader (" + loader + ')', entryOk);

            if ("forge".equals(loader)) {
                check("Forge metadata targets loader 47+ (Minecraft 1.20.1)",
                        containsAscii(metadata, "loaderVersion=\"[47,)\""));
                check("Forge metadata declares the ordering=BEFORE WaterMedia dependency",
                        containsAscii(metadata, "ordering=\"BEFORE\"") && containsAscii(metadata, "mandatory=true"));
            } else if ("neoforge".equals(loader)) {
                check("NeoForge metadata targets loader 3+ (Minecraft 1.21.1)",
                        containsAscii(metadata, "loaderVersion = \"[3,)\""));
                check("NeoForge metadata declares the type=required ordering=BEFORE WaterMedia dependency",
                        containsAscii(metadata, "ordering = \"BEFORE\"") && containsAscii(metadata, "type = \"required\""));
                check("NeoForge registers the mixin config through [[mixins]]",
                        containsAscii(metadata, "[[mixins]]")
                                && containsAscii(metadata, "watermedia_android_bridge.mixins.json"));
            } else {
                // Fabric reads everything from fabric.mod.json, including load order workarounds:
                // preLaunch must run the payload before WATERMeDIA initialises, because
                // videolan4j's NativeDiscovery.start() is one-shot (an "attempted" flag makes
                // every later call return false without retrying).
                check("Fabric metadata is client only", containsAscii(metadata, "\"environment\": \"client\""));
                check("Fabric metadata runs the payload from preLaunch",
                        containsAscii(metadata, "\"preLaunch\"")
                                && containsAscii(metadata, "org.watermedia.androidbridge.AndroidBridge"));
                check("Fabric metadata registers the client entrypoint",
                        containsAscii(metadata, "\"client\""));
                check("Fabric metadata registers the mixin config",
                        containsAscii(metadata, "\"mixins\"")
                                && containsAscii(metadata, "watermedia_android_bridge.mixins.json"));
                check("Fabric metadata depends on the loader, the API and this Minecraft version",
                        containsAscii(metadata, "fabricloader") && containsAscii(metadata, "fabric-api")
                                && containsAscii(metadata, pack15 ? "~1.20.1" : "~1.21.1"));
            }

            // The manifest only exists inside a jar; a classes directory has none, so the
            // mixin announcement is verified on the packaged artifact runs.
            final byte[] manifest = readFromCodeSource(AndroidVlc.class, "META-INF/MANIFEST.MF");
            if (manifest == null) {
                System.out.println("  (classes directory: the jar manifest is only checked on the packaged jar)");
            } else if ("forge".equals(loader)) {
                check("Forge announces the mixin config through the jar manifest MixinConfigs",
                        containsAscii(manifest, "MixinConfigs")
                                && containsAscii(manifest, "watermedia_android_bridge.mixins.json"));
            } else {
                check("NeoForge needs no manifest entry because [[mixins]] is registered in the toml",
                        !containsAscii(manifest, "MixinConfigs"));
            }
        } catch (final Throwable t) {
            check("the loader metadata could be inspected (" + t + ')', false);
        }

        // ---- video path: the Android GL upload and the frame diagnostics ----------
        // WATERMeDIA uploads video frames with GL_UNSIGNED_INT_8_8_8_8_REV (0x8367), a
        // desktop-only pixel type that OpenGL ES rejects; the texture then keeps no
        // storage and the screen stays white while the audio plays.  The bridge replaces
        // that call, so the injection has to name the exact descriptor or it silently
        // does nothing, and the replacement must never throw: WATERMeDIA releases its
        // frame semaphore only after uploadBuffer returns.
        try {
            final byte[] uploadMixin = readFromCodeSource(AndroidVlc.class,
                    "org/watermedia/androidbridge/mixin/RenderAPIMixin.class");
            check("the upload mixin is packaged", uploadMixin != null);
            check("the upload mixin targets uploadBuffer(ByteBuffer,int,int,int,int,boolean)",
                    containsAscii(uploadMixin, "uploadBuffer(Ljava/nio/ByteBuffer;IIIIZ)V"));
            check("the upload mixin routes frames through VideoUpload",
                    containsAscii(uploadMixin, "org/watermedia/androidbridge/VideoUpload")
                            && containsAscii(uploadMixin, "upload"));

            final byte[] playerMixin = readFromCodeSource(AndroidVlc.class,
                    "org/watermedia/androidbridge/mixin/VideoPlayerMixin.class");
            check("the video player mixin is packaged", playerMixin != null);
            check("the video player mixin targets VideoPlayer.display and its constructor",
                    containsAscii(playerMixin, "display(Lorg/watermedia/videolan4j/player/base/MediaPlayer;"
                                    + "[Ljava/nio/ByteBuffer;Lorg/watermedia/videolan4j/player/embedded/"
                                    + "videosurface/callback/BufferFormat;)V")
                            && containsAscii(playerMixin, "<init>(Lorg/watermedia/videolan4j/factory/"
                                    + "MediaPlayerFactory;Ljava/util/concurrent/Executor;)V"));
            check("the video player mixin reports through VideoDiagnostics",
                    containsAscii(playerMixin, "org/watermedia/androidbridge/VideoDiagnostics"));

            final byte[] mixinConfigVideo = readFromCodeSource(AndroidVlc.class, "watermedia_android_bridge.mixins.json");
            check("the mixin config lists both mixins",
                    containsAscii(mixinConfigVideo, "VideoPlayerMixin")
                            && containsAscii(mixinConfigVideo, "RenderAPIMixin"));

            final byte[] uploadClass = readFromCodeSource(AndroidVlc.class,
                    "org/watermedia/androidbridge/VideoUpload.class");
            check("VideoUpload uses GL_UNSIGNED_BYTE and a two step fallback",
                    containsAscii(uploadClass, "glTexImage2D")
                            && containsAscii(uploadClass, "glTexSubImage2D")
                            && containsAscii(uploadClass, "GL_UNSIGNED_BYTE")
                            && containsAscii(uploadClass, "glGetError"));

            // the contract that keeps VLC's video output thread alive: no exception may
            // escape, even when there is no GL context at all (which is the case here)
            boolean threw = false;
            try {
                org.watermedia.androidbridge.VideoUpload.upload(
                        java.nio.ByteBuffer.allocateDirect(16), 42, 6408, 2, 2, true);
            } catch (final Throwable t) {
                threw = true;
                System.out.println("  (VideoUpload.upload threw: " + t + ')');
            }
            check("VideoUpload.upload never throws without a GL context (keeps the frame semaphore balanced)",
                    !threw);
            check("the GL error names are decoded for the log",
                    org.watermedia.androidbridge.VideoDiagnostics.describeGlError(0x0500).contains("GL_INVALID_ENUM")
                            && org.watermedia.androidbridge.VideoDiagnostics.describeGlError(0x0501).contains("GL_INVALID_VALUE")
                            && org.watermedia.androidbridge.VideoDiagnostics.describeGlError(0x0505).contains("OUT_OF_MEMORY"));
            boolean tickThrew = false;
            try {
                org.watermedia.androidbridge.VideoDiagnostics.tick();
            } catch (final Throwable t) {
                tickThrew = true;
            }
            check("the video watchdog tick never throws", !tickThrew);
        } catch (final Throwable t) {
            check("the video upload path could be inspected (" + t + ')', false);
        }

        // ---- Android VLC arguments -------------------------------------------------
        try {
            final Path configDir = gameDir.resolve("config").resolve("watermedia_android_bridge-probe");
            Files.createDirectories(configDir);
            Files.deleteIfExists(configDir.resolve("watermedia_android_bridge.properties"));
            final BridgeConfig defaults = BridgeConfig.load(configDir);
            final String joined = String.join(" ", defaults.vlcArguments());
            check("the default arguments force the callback (vmem) video output", joined.contains("--vout=vmem"));
            check("the default arguments keep the Android MediaCodec decoders out of the way",
                    joined.contains("--avcodec-hw=none"));
            check("the default arguments still request the OpenSL ES audio output",
                    joined.contains("--aout=opensles,audiotrack,any"));
            check("the default arguments keep every switch WATERMeDIA's Linux list uses",
                    joined.contains("--no-quiet") && joined.contains("--network-synchronisation")
                            && joined.contains("--network-caching=1000") && joined.contains("--live-caching=1000")
                            && joined.contains("--file-caching=1000") && joined.contains("--no-file-logging")
                            && joined.contains("--http-reconnect") && joined.contains("--no-metadata-network-access"));

            Files.writeString(configDir.resolve("watermedia_android_bridge.properties"),
                    "videoOutput=\nhardwareDecoding=any\nextraVlcArguments=--no-drop-late-frames,--drop-late-frames\n",
                    StandardCharsets.UTF_8);
            final BridgeConfig custom = BridgeConfig.load(configDir);
            final String customJoined = String.join(" ", custom.vlcArguments());
            check("an empty videoOutput lets libvlc choose the video output",
                    !customJoined.contains("--vout="));
            check("hardwareDecoding=any re-enables hardware decoding",
                    customJoined.contains("--avcodec-hw=any"));
            check("extra arguments are appended last",
                    customJoined.endsWith("--no-drop-late-frames --drop-late-frames"));
            deleteRecursively(configDir);
        } catch (final Throwable t) {
            check("the Android VLC argument set could be inspected (" + t + ')', false);
        }

        // Licence compliance: whatever the jar redistributes must come with its full
        // licence text, and the mod's own licence has to be declared.
        try {
            final String[] texts = { "GPL-3.0.txt", "GPL-2.0.txt", "LGPL-2.1.txt",
                    "Apache-2.0.txt", "LLVM-exception.txt" };
            int shipped = 0;
            for (final String text : texts) {
                final byte[] bytes = readFromCodeSource(AndroidVlc.class, "META-INF/licenses/" + text);
                if (bytes != null && bytes.length > 2000) shipped++;
            }
            check("all five licence texts for the redistributed binaries ship in the jar", shipped == texts.length);

            final byte[] notice = readFromCodeSource(AndroidVlc.class, "META-INF/licenses/THIRD-PARTY-NOTICES.txt");
            check("the third party notice ships and explains the GPLv3 combination",
                    containsAscii(notice, "GPLv3") && containsAscii(notice, "PolyForm")
                            && containsAscii(notice, "LGPL-2.1"));

            byte[] tomlLicence = readFromCodeSource(AndroidVlc.class, "META-INF/neoforge.mods.toml");
            String licenceSyntax = "license = \"GPL-3.0-or-later\"";   // neoforge.mods.toml
            if (tomlLicence == null) {
                tomlLicence = readFromCodeSource(AndroidVlc.class, "META-INF/mods.toml");
                licenceSyntax = "license=\"GPL-3.0-or-later\"";        // mods.toml
            }
            if (tomlLicence == null) {
                tomlLicence = readFromCodeSource(AndroidVlc.class, "fabric.mod.json");
                licenceSyntax = "\"license\": \"GPL-3.0-or-later\"";   // fabric.mod.json
            }
            check("the loader metadata declares the mod licence as GPL-3.0-or-later",
                    containsAscii(tomlLicence, licenceSyntax));
        } catch (final Throwable t) {
            check("licence compliance could be inspected (" + t + ')', false);
        }

        // priming libvlc.so must report an outcome and never throw
        final String prime = AndroidVlc.primeVlcRuntime(vlcDir);
        System.out.println("  (libvlc.so preload on this host: " + (prime == null ? "accepted" : prime) + ')');

        // Android app-private directories are derived from paths the launcher hands
        // us; this is the logic that has to find an internal location on device.
        final Path userRoot = AndroidVlc.androidAppDataRoot("/data/user/0/com.tungsten.fcl/cache/fclauncher");
        check("app data root derived from /data/user/<id>/<pkg>/...",
                userRoot != null && userRoot.getNameCount() == 4
                        && "data".equals(userRoot.getName(0).toString())
                        && "user".equals(userRoot.getName(1).toString())
                        && "com.tungsten.fcl".equals(userRoot.getName(3).toString()));
        final Path dataRoot = AndroidVlc.androidAppDataRoot("/data/data/com.tungsten.fcl/app_runtime/jna/5.14.0");
        check("app data root derived from /data/data/<pkg>/...",
                dataRoot != null && dataRoot.getNameCount() == 3
                        && "data".equals(dataRoot.getName(0).toString())
                        && "data".equals(dataRoot.getName(1).toString())
                        && "com.tungsten.fcl".equals(dataRoot.getName(2).toString()));
        check("desktop temp directory is not mistaken for an Android app directory",
                AndroidVlc.androidAppDataRoot(System.getProperty("java.io.tmpdir")) == null);

        // the ABI that matters on Android, extracted explicitly
        final VlcInstaller.Result arm = VlcInstaller.install(gameDir, "arm64-v8a", false);
        check("arm64-v8a payload extracts", arm.ok && Files.isRegularFile(arm.directory.resolve("libvlc.so")));
        check("arm64-v8a re-install is a cache hit",
                VlcInstaller.install(gameDir, "arm64-v8a", false).extracted == false);

        // the LINUX file patterns + plugin path videolan4j uses on Android
        final Object linux = Class.forName("org.watermedia.videolan4j.discovery.Environment")
                .getField("LINUX").get(null);
        check("videolan4j LINUX search finds the payload directory",
                vlcDir.toAbsolutePath().toString().equals(searchPath(linux, vlcDir.toAbsolutePath().toString())));
        check("videolan4j LINUX search finds the arm64-v8a payload directory",
                arm.directory.toAbsolutePath().toString()
                        .equals(searchPath(linux, arm.directory.toAbsolutePath().toString())));

        // WATERMeDIA's own provider must accept the generated configuration file.
        // This is also the regression test for the load-order trap: instantiating
        // ConfigProvider before WaterMedia.prepare() poisons its static initialiser
        // for the whole JVM, so it must still work here.
        WaterMedia.prepare(loaderFor(gameDir));
        try {
            final PlayerAPI.ConfigProvider provider = new PlayerAPI.ConfigProvider();
            check("WATERMeDIA ConfigProvider instantiates and accepts the payload directory", provider.supported());
            if (provider.supported()) {
                final String[] dirs = provider.directories();
                check("ConfigProvider hands out the payload directory",
                        dirs.length > 0 && vlcDir.toAbsolutePath().toString().equals(dirs[0]));
            }
        } catch (final Throwable t) {
            check("WATERMeDIA ConfigProvider instantiates (poisoned static init? " + t + ')', false);
        }

        // the ServiceLoader based provider must agree
        final VlcDirectoryProvider bridgeProvider = new VlcDirectoryProvider();
        check("VlcDirectoryProvider supported()", bridgeProvider.supported());
        check("VlcDirectoryProvider directory matches",
                bridgeProvider.directories().length > 0
                        && vlcDir.toAbsolutePath().toString().equals(bridgeProvider.directories()[0]));

        // Real discovery cannot succeed here: these are ARM shared objects and the
        // harness runs on Windows.  It must fail cleanly instead of throwing.
        System.out.println("  (discovery on this host returned " + AndroidVlc.isDiscoveryOk()
                + ", which is expected for foreign-architecture binaries)");
    }

    private static String expectedConfigFile(final Path gameDir) throws IOException {
        return Files.readString(gameDir.resolve("config/watermedia/custom_vlc_path.txt"), StandardCharsets.UTF_8).trim();
    }

    private static String searchPath(final Object environment, final String directory) throws Exception {
        final Class<?> envClass = Class.forName("org.watermedia.videolan4j.discovery.Environment");
        final Method method = NativeDiscovery.class.getDeclaredMethod("start$searchPath", envClass, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, environment, directory);
    }

    /** Reads a class/resource next to the given class (works for a directory or a jar). */
    private static byte[] readFromCodeSource(final Class<?> anchor, final String entry) throws Exception {
        final java.security.CodeSource source = anchor.getProtectionDomain().getCodeSource();
        if (source == null) return null;
        final Path base = Paths.get(source.getLocation().toURI());
        if (Files.isDirectory(base)) {
            final Path file = base.resolve(entry);
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
        try (ZipFile zip = new ZipFile(base.toFile())) {
            final ZipEntry zipEntry = zip.getEntry(entry);
            if (zipEntry == null) return null;
            try (InputStream in = zip.getInputStream(zipEntry)) {
                return in.readAllBytes();
            }
        }
    }

    /** Class files are not UTF-8, but constant pool strings are plain ASCII for our needles. */
    private static boolean containsAscii(final byte[] bytes, final String needle) {
        return bytes != null && new String(bytes, StandardCharsets.ISO_8859_1).contains(needle);
    }

    // -------------------------------------------------------------- discover mode

    private static void discoverMode(final Path gameDir, final Path waterMediaJar) throws Exception {
        final Path vlcDir = gameDir.resolve("vlc-windows");
        deleteRecursively(vlcDir);
        extractWindowsVlc(waterMediaJar, vlcDir);
        check("windows VLC payload extracted from WATERMeDIA's own jar",
                Files.isRegularFile(vlcDir.resolve("libvlc.dll")));

        final Path configDir = gameDir.resolve("config/watermedia");
        Files.createDirectories(configDir);
        Files.write(configDir.resolve("custom_vlc_path.txt"),
                vlcDir.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));

        // Make sure WATERMeDIA's own Windows provider (the temp "videolan" folder)
        // cannot win, so discovery has to go through the configuration file.
        final Path tempVideolan = Paths.get(System.getProperty("java.io.tmpdir"), "watermedia", "videolan");
        System.out.println("  (WATERMeDIA temp provider dir: " + tempVideolan + ", exists=" + Files.exists(tempVideolan) + ')');

        WaterMedia.prepare(loaderFor(gameDir));
        check("ConfigProvider active in discover mode", new PlayerAPI.ConfigProvider().supported());

        final boolean discovered = NativeDiscovery.start();
        check("NativeDiscovery.start() loaded libvlc through the configuration file", discovered);
        if (discovered) {
            System.out.println("  discovered: " + NativeDiscovery.discoveryPath());
            System.out.println("  libvlc version: " + VideoLan4J.getLibVersion());
            check("libvlc version inside WATERMeDIA's supported range", VideoLan4J.isLibSupported());

            // The second half of what the bridge does: re-register WATERMeDIA's
            // default factories with the Android argument vector.
            final BridgeConfig bridgeConfig = BridgeConfig.load(gameDir.resolve("config"));
            final String[] args = bridgeConfig.vlcArguments();
            System.out.println("  argument vector: " + String.join(" ", args));
            check("argument vector carries the Android audio output",
                    String.join(" ", args).contains("--aout=opensles,audiotrack,any"));
            final MediaPlayerFactory defaultFactory =
                    PlayerAPI.registerFactory(WaterMedia.asResource("default"), args);
            check("PlayerAPI.registerFactory accepted the Android argument vector", defaultFactory != null);
            check("PlayerAPI.getFactory() hands out the registered factory", PlayerAPI.getFactory() != null);
            check("sound-only factory arguments append --vout=none",
                    String.join(" ", withVideoDisabled(args)).contains("--vout=none"));
        }
    }

    /** mirrors AndroidVlc#withVideoDisabled so the harness checks the same shape */
    private static String[] withVideoDisabled(final String[] args) {
        final String[] result = new String[args.length + 1];
        System.arraycopy(args, 0, result, 0, args.length);
        result[args.length] = "--vout=none";
        return result;
    }

    private static void extractWindowsVlc(final Path waterMediaJar, final Path target) throws IOException {
        Files.createDirectories(target);
        try (ZipFile jar = new ZipFile(waterMediaJar.toFile())) {
            final ZipEntry nested = jar.getEntry("videolan/win-x64.zip");
            if (nested == null) throw new IOException("watermedia jar has no videolan/win-x64.zip");
            final Path nestedZip = target.resolveSibling("win-x64.zip");
            try (InputStream in = jar.getInputStream(nested); OutputStream out = Files.newOutputStream(nestedZip)) {
                in.transferTo(out);
            }
            try (ZipFile inner = new ZipFile(nestedZip.toFile())) {
                final Enumeration<? extends ZipEntry> entries = inner.entries();
                while (entries.hasMoreElements()) {
                    final ZipEntry entry = entries.nextElement();
                    final Path out = target.resolve(entry.getName()).normalize();
                    if (!out.startsWith(target)) throw new IOException("zip slip: " + entry.getName());
                    if (entry.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        try (InputStream in = inner.getInputStream(entry); OutputStream os = Files.newOutputStream(out)) {
                            in.transferTo(os);
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static ILoader loaderFor(final Path gameDir) {
        return new ILoader() {
            @Override public String name() { return "BridgeHarness"; }
            @Override public Path tempDir() { return gameDir.resolve("tmp"); }
            @Override public Path processDir() { return gameDir; }
            @Override public boolean tlcheck() { return false; }
            @Override public boolean clientSide() { return true; }
            @Override public boolean developerMode() { return true; }
        };
    }

    private static void check(final String what, final boolean ok) {
        checks++;
        if (ok) {
            System.out.println("  [PASS] " + what);
        } else {
            failures++;
            System.out.println("  [FAIL] " + what);
        }
    }

    private static void deleteRecursively(final Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            final List<Path> paths = new ArrayList<>(walk.sorted(java.util.Comparator.reverseOrder()).toList());
            for (final Path path : paths) Files.deleteIfExists(path);
        }
    }

    private BridgeHarness() {}
}
