/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.watermedia.androidbridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.watermedia.WaterMedia;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.videolan4j.discovery.NativeDiscovery;

/**
 * The bridge itself: installs the bundled VLC-for-Android payload, points
 * WATERMeDIA at it and (optionally) re-registers WATERMeDIA's default VLC
 * factories with an Android friendly argument set.
 *
 * <p>Why this works without patching WATERMeDIA's bytecode:
 * <ul>
 *   <li>{@code videolan4j} discovers libvlc through a list of
 *       {@code IProvider}s, one of which
 *       ({@code PlayerAPI.ConfigProvider}) reads
 *       {@code config/watermedia/custom_vlc_path.txt}.  Writing that file is the
 *       supported way to tell WATERMeDIA where VLC lives - no mixin involved.</li>
 *   <li>This mod declares {@code ordering = "BEFORE"} on WATERMeDIA, so the
 *       payload and that configuration file are already in place when
 *       WATERMeDIA's {@code PlayerAPI} runs its own discovery.</li>
 *   <li>{@code NativeDiscovery.start()} and {@code PlayerAPI.registerFactory(id, args)}
 *       are public API, so the bridge can also repair a discovery that already
 *       failed and then select the Android audio output.</li>
 * </ul>
 */
public final class AndroidVlc {
    private static final int TICK_WINDOW = 1200; // 60 seconds at 20 tps
    private static final int RETRY_LIMIT = 3;

    private static boolean bootstrapped;
    private static BridgeConfig config;
    private static VlcPayload payload;
    private static Path gameDir;
    private static Path vlcDir;
    private static String abi;
    private static boolean installed;
    private static boolean discoveryOk;
    private static boolean argsApplied;
    private static int ticks;
    private static int discoveryRetries;

    private AndroidVlc() {}

    // ------------------------------------------------------------------ bootstrap

    public static synchronized void bootstrapEarly() {
        if (bootstrapped) return;
        bootstrapped = true;
        try {
            gameDir = resolveGameDir();
            config = BridgeConfig.load(gameDir.resolve("config"));

            BridgeLog.info("{} / {} - {}", BridgeLog.MOD_NAME, version(), AndroidEnv.describe());

            if (!config.enabled) {
                BridgeLog.info("disabled in {} - leaving WATERMeDIA untouched", config.file());
                return;
            }
            if (!AndroidEnv.isAndroid()) {
                BridgeLog.info("not running on Android, the bridge stays passive. "
                        + "Use -D{}=true to force it for testing.", AndroidEnv.PROP_FORCE_ANDROID);
                return;
            }

            abi = "auto".equalsIgnoreCase(config.abi) ? AndroidEnv.abi() : config.abi;
            if (abi == null || abi.isBlank()) {
                BridgeLog.error("cannot map os.arch '{}' to an Android ABI", System.getProperty("os.arch"));
                return;
            }

            final VlcInstaller.Result result = installPayload();
            vlcDir = result.directory;
            installed = result.ok;
            if (!installed) {
                BridgeLog.error("could not prepare the bundled VLC: {}", result.detail);
                return;
            }
            payload = VlcInstaller.payload();
            BridgeLog.info("bundled VLC {} ({}) for {}: {}", payload.vlcVersion, payload.vlcSource, abi, result.detail);
            BridgeLog.info("VLC payload directory: {}", vlcDir.toAbsolutePath());

            installJnaFallback();
            prepareWorkingDirectory();
            pointWaterMediaAtPayload();

            // Deliberately NOT calling NativeDiscovery.start() here when WATERMeDIA
            // has not booted yet.  videolan4j resolves its providers through
            // ServiceLoader, and PlayerAPI$ConfigProvider reads
            // WaterMedia.getConfigDir() in a static initialiser - which needs
            // WaterMedia.bootstrap, set by WaterMedia.prepare().  Touching the
            // ServiceLoader before that poisons the provider class for the whole
            // JVM (ExceptionInInitializerError is sticky), so the bridge only ever
            // triggers discovery once WATERMeDIA is prepared.
            if (waterMediaPrepared()) {
                discoveryOk = requestDiscovery();
            } else {
                BridgeLog.info("payload ready - WATERMeDIA will find it during its own boot");
            }
        } catch (final Throwable t) {
            BridgeLog.error("bootstrap failed", t);
        }
    }

    // ------------------------------------------------------------------ one-time steps

    /**
     * Picks an installation directory Android can actually {@code dlopen} from and
     * installs the payload there.
     *
     * <p>Android's linker refuses to load any library stored on emulated/external
     * storage:
     * <pre>dlopen failed: library ".../libvlc.so" ... is not accessible for the
     * namespace "clns-10"</pre>
     * Since almost every launcher keeps its instance directory on
     * {@code /storage/emulated/0}, the historical "extract next to the game"
     * layout cannot work.  File permission checks pass there, so the only
     * trustworthy test is to really load the payload: the bridge
     * {@code System.load}s {@code libc++_shared.so} from every candidate and only
     * hands a directory to WATERMeDIA once that succeeded.
     *
     * <p>Loading it is required on Android anyway - the linker resolves libvlc's
     * {@code DT_NEEDED} entry by soname across the process, not relative to the
     * directory being opened.
     */
    private static VlcInstaller.Result installPayload() {
        final List<VlcInstaller.Result> installed = new ArrayList<>();
        for (final Path candidate : installCandidates()) {
            final VlcInstaller.Result result = VlcInstaller.installAt(candidate, abi, config.forceInstall);
            if (!result.ok) {
                BridgeLog.warn("payload installation into {} failed: {}", candidate, result.detail);
                continue;
            }
            installed.add(result);

            if (!VlcInstaller.layoutUsable(candidate)) {
                BridgeLog.warn("{} has no readable/executable plugins folder, trying another location", candidate);
                continue;
            }

            final String refusal = probeSharedRuntime(candidate);
            if (refusal == null) {
                final String primeFailure = primeVlcRuntime(candidate);
                if (primeFailure == null) {
                    BridgeLog.info("using {} (payload and VLC's JNI_OnLoad initialised)", candidate);
                } else {
                    BridgeLog.warn("libvlc.so could not be preloaded through System.load ({}); VLC's JNI_OnLoad "
                            + "may not have run, which makes libvlc_new abort on 's_jvm != NULL'", primeFailure);
                    BridgeLog.info("using {} anyway", candidate);
                }
                cleanupStalePayloads(candidate);
                return result;
            }
            BridgeLog.warn("{} cannot be used: {}", candidate, refusal);
            if (!AndroidEnv.isAndroid()) {
                BridgeLog.info("not running on Android, keeping {} anyway", candidate);
                cleanupStalePayloads(candidate);
                return result;
            }
        }
        if (!installed.isEmpty()) {
            BridgeLog.warn("no candidate passed the loader check, using {} anyway - VLC is unlikely to load there",
                    installed.get(0).directory);
            return installed.get(0);
        }
        return new VlcInstaller.Result(false, false, VlcInstaller.vlcDirectory(gameDir, abi),
                "no usable installation location");
    }

    /**
     * Loads the bundled C++ runtime from the given directory, which doubles as the
     * test for "can Android {@code dlopen} from here at all".
     *
     * @return {@code null} when the directory is usable, otherwise a human readable
     *         reason why it is not
     */
    public static String probeSharedRuntime(final Path directory) {
        final Path runtime = directory.resolve("libc++_shared.so");
        if (!Files.isRegularFile(runtime)) return null; // nothing to preload
        try {
            System.load(runtime.toAbsolutePath().toString());
            return null;
        } catch (final UnsatisfiedLinkError error) {
            final String message = String.valueOf(error.getMessage());
            if (message.contains("already loaded")) return null;
            if (message.contains("not accessible for the namespace")) {
                return "Android's linker namespace refuses this location (external/emulated storage cannot be "
                        + "dlopen'ed); the payload must live in app internal storage";
            }
            return message;
        } catch (final Throwable t) {
            return t.toString();
        }
    }

    /**
     * Loads {@code libvlc.so} through {@code System.load}.
     *
     * <p>This is not redundant with JNA: the VLC for Android build only captures
     * the JavaVM in {@code JNI_OnLoad}, and HotSpot runs {@code JNI_OnLoad} solely
     * for libraries loaded through {@code System.load}/{@code System.loadLibrary}.
     * JNA opens libraries with a bare {@code dlopen}, so without this step
     * {@code s_jvm} stays null and {@code libvlc_new} aborts the process:</p>
     *
     * <pre>
     * src/android/specific.c:174: void system_Configure(...):
     *     assertion "s_jvm != NULL" failed
     * </pre>
     *
     * <p>Having the library resident also means JNA's later {@code dlopen} reuses
     * the very same image instead of loading a second copy.</p>
     *
     * @return {@code null} on success, otherwise a human readable reason
     */
    public static String primeVlcRuntime(final Path directory) {
        final Path library = directory.resolve("libvlc.so");
        if (!Files.isRegularFile(library)) return "libvlc.so is missing in " + directory;
        try {
            System.load(library.toAbsolutePath().toString());
            return null;
        } catch (final UnsatisfiedLinkError error) {
            final String message = String.valueOf(error.getMessage());
            if (message.contains("already loaded")) return null;
            return message;
        } catch (final Throwable t) {
            return t.toString();
        }
    }

    /**
     * Candidate directories, best first.  On Android only app internal storage is
     * reachable by the linker, so the game directory is kept strictly as a last
     * resort (and for desktop harnesses).
     */
    private static List<Path> installCandidates() {
        final List<Path> candidates = new ArrayList<>();
        final boolean android = AndroidEnv.isAndroid();

        if (config.installLocation != null && !config.installLocation.isBlank()
                && !"auto".equalsIgnoreCase(config.installLocation)) {
            addCandidate(candidates, Paths.get(config.installLocation).resolve(BridgeLog.MOD_ID).resolve(abi));
        }

        if (!android) {
            addCandidate(candidates, VlcInstaller.vlcDirectory(gameDir, abi));
            return candidates;
        }

        final Path appRoot = appPrivateRoot();
        if (appRoot != null) {
            addCandidate(candidates, appRoot.resolve("files").resolve(BridgeLog.MOD_ID).resolve(abi));
        }
        addCandidate(candidates, jnaDirectory());
        final String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isBlank()) {
            addCandidate(candidates, Paths.get(tmp).resolve(BridgeLog.MOD_ID).resolve(abi));
        }
        addCandidate(candidates, VlcInstaller.vlcDirectory(gameDir, abi));
        return candidates;
    }

    private static void addCandidate(final List<Path> candidates, final Path path) {
        if (path == null) return;
        final Path normalized = path.toAbsolutePath().normalize();
        if (!candidates.contains(normalized)) candidates.add(normalized);
    }

    /** {@code /data/data/<pkg>} or {@code /data/user/<n>/<pkg>}, derived from a known app path. */
    private static Path appPrivateRoot() {
        for (final String hint : new String[] { System.getProperty("java.io.tmpdir"),
                System.getProperty("jna.boot.library.path") }) {
            final Path root = androidAppDataRoot(hint);
            if (root != null) return root;
        }
        return null;
    }

    public static Path androidAppDataRoot(final String pathHint) {
        if (pathHint == null || pathHint.isBlank()) return null;
        try {
            final Path path = Paths.get(pathHint).toAbsolutePath().normalize();
            if (path.getNameCount() >= 3 && "data".equals(path.getName(0).toString())
                    && "data".equals(path.getName(1).toString())) {
                return path.getRoot().resolve(path.subpath(0, 3));
            }
            if (path.getNameCount() >= 4 && "data".equals(path.getName(0).toString())
                    && "user".equals(path.getName(1).toString())
                    && path.getName(2).toString().chars().allMatch(Character::isDigit)) {
                return path.getRoot().resolve(path.subpath(0, 4));
            }
        } catch (final Throwable t) {
            BridgeLog.debug("could not derive the app data root from '{}': {}", pathHint, t.toString());
        }
        return null;
    }

    /**
     * The folder the launcher loads its JNA dispatch library from.  Whatever that
     * folder is, the current linker namespace has already proven it can dlopen
     * from there, which makes it a dependable fallback.
     */
    private static Path jnaDirectory() {
        final String bootPath = System.getProperty("jna.boot.library.path");
        if (bootPath != null && !bootPath.isBlank()) {
            try {
                return Paths.get(bootPath).resolve(BridgeLog.MOD_ID).resolve(abi);
            } catch (final Throwable t) {
                BridgeLog.debug("unusable jna.boot.library.path '{}': {}", bootPath, t.toString());
            }
        }
        try {
            final Class<?> nativeLibrary = Class.forName("com.sun.jna.NativeLibrary", false,
                    AndroidVlc.class.getClassLoader());
            final Object instance = nativeLibrary.getMethod("getInstance", String.class).invoke(null, "jnidispatch");
            final Object file = nativeLibrary.getMethod("getFile").invoke(instance);
            if (file instanceof java.io.File loaded && loaded.getParentFile() != null) {
                return loaded.getParentFile().toPath().resolve(BridgeLog.MOD_ID).resolve(abi);
            }
        } catch (final Throwable t) {
            BridgeLog.debug("could not locate JNA's native directory: {}", t.toString());
        }
        return null;
    }

    /** Removes the payload copy left behind at the game directory by an older run. */
    private static void cleanupStalePayloads(final Path chosen) {
        try {
            final Path legacyRoot = VlcInstaller.vlcRoot(gameDir).toAbsolutePath().normalize();
            final Path normalized = chosen.toAbsolutePath().normalize();
            if (!Files.isDirectory(legacyRoot) || normalized.startsWith(legacyRoot)) return;
            VlcInstaller.deleteRecursively(legacyRoot);
            BridgeLog.info("removed the previous payload copy at {} to free up space", legacyRoot);
        } catch (final Throwable t) {
            BridgeLog.debug("could not clean up an older payload copy: {}", t.toString());
        }
    }

    /**
     * PojavLauncher ships an Android build of JNA's dispatch library inside its
     * APK, so JNA normally just works.  When it does not, point JNA at the copy
     * bundled with this mod - but only when the version matches the JNA build on
     * the classpath, otherwise a working setup could be broken.
     */
    private static void installJnaFallback() {
        if (!config.jnaFallback) return;
        final Path jnaDir = vlcDir.resolve("jna");
        if (!Files.isDirectory(jnaDir)) return;

        try {
            System.loadLibrary("jnidispatch");
            BridgeLog.info("JNA native dispatch is provided by the platform, no fallback needed");
            return;
        } catch (final Throwable t) {
            BridgeLog.debug("System.loadLibrary(\"jnidispatch\") failed: {}", t.toString());
        }

        final String runtimeVersion = jnaVersion();
        final String bundledVersion = payload == null ? null : payload.jnaVersion;
        if (!jnaCompatible(runtimeVersion, bundledVersion)) {
            BridgeLog.warn("not overriding jna.boot.library.path: classpath JNA is {} but the bundled dispatch library is {}",
                    runtimeVersion, bundledVersion);
            return;
        }
        System.setProperty("jna.boot.library.path", jnaDir.toAbsolutePath().toString());
        BridgeLog.info("JNA native dispatch redirected to {}", jnaDir.toAbsolutePath());
    }

    /**
     * Mirrors JNA's own compatibility rule ({@code Native.isCompatibleVersion}):
     * a native dispatch library may only be used by a JNA of the same major and
     * minor version.  Unknown versions are accepted, because in that situation
     * JNA was already unable to load anything on its own.
     */
    static boolean jnaCompatible(final String runtimeVersion, final String bundledVersion) {
        if (runtimeVersion == null || bundledVersion == null) return true;
        final String[] runtime = runtimeVersion.split("\\.");
        final String[] bundled = bundledVersion.split("\\.");
        if (runtime.length < 2 || bundled.length < 2) return true;
        return runtime[0].equals(bundled[0]) && runtime[1].equals(bundled[1]);
    }

    private static String jnaVersion() {
        try {
            final Class<?> nativeClass = Class.forName("com.sun.jna.Native", false, AndroidVlc.class.getClassLoader());
            final Package pkg = nativeClass.getPackage();
            return pkg == null ? null : pkg.getImplementationVersion();
        } catch (final Throwable ignored) {
            return null;
        }
    }

    /**
     * videolan4j's discovery test creates a libvlc instance with
     * {@code --file-logging --logfile=logs/videolan-discovery.log}, resolved
     * against the process working directory.  Making sure that folder exists
     * keeps the diagnosis log (the very thing WATERMeDIA prints on failure)
     * available on Android.
     */
    private static void prepareWorkingDirectory() {
        try {
            final Path logs = Paths.get("logs");
            if (!Files.isDirectory(logs)) Files.createDirectories(logs);
        } catch (final Throwable t) {
            BridgeLog.debug("could not create the VLC log directory: {}", t.toString());
        }
    }

    /** WATERMeDIA's supported extension point: {@code config/watermedia/custom_vlc_path.txt}. */
    private static void pointWaterMediaAtPayload() throws Exception {
        final Path configDir = gameDir.resolve("config").resolve("watermedia");
        final Path file = configDir.resolve("custom_vlc_path.txt");
        final String desired = vlcDir.toAbsolutePath().toString();

        String current = null;
        try {
            if (Files.isRegularFile(file)) current = Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (final Throwable t) {
            BridgeLog.debug("could not read {}: {}", file, t.toString());
        }
        if (desired.equals(current)) return;
        if (current != null && !current.isEmpty()) {
            // Never fight a user who configured their own VLC installation.
            final Path configured = Paths.get(current);
            if (Files.isDirectory(configured) && looksLikeVlc(configured)) {
                BridgeLog.info("{} already points at a usable VLC ({}), keeping it", file.getFileName(), current);
                return;
            }
        }
        Files.createDirectories(configDir);
        Files.write(file, desired.getBytes(StandardCharsets.UTF_8));
        BridgeLog.info("pointed WATERMeDIA at the bundled VLC through {}", file);
    }

    private static boolean looksLikeVlc(final Path dir) {
        return Files.exists(dir.resolve("libvlc.so")) || Files.exists(dir.resolve("libvlc.dll"))
                || Files.exists(dir.resolve("libvlc.dylib"));
    }

    // ------------------------------------------------------------------ discovery & factories

    /**
     * Runs {@code videolan4j}'s discovery.  This is idempotent: once a library was
     * accepted the result is cached inside WATERMeDIA.
     */
    static synchronized boolean requestDiscovery() {
        if (!installed) return false;
        try {
            if (NativeDiscovery.discovered()) {
                discoveryOk = true;
                return true;
            }
            resetAttemptFlag();
            final boolean ok = NativeDiscovery.start();
            discoveryOk = ok;
            if (ok) {
                BridgeLog.info("WATERMeDIA loaded VLC from '{}'", NativeDiscovery.discoveryPath());
            } else {
                BridgeLog.warn("WATERMeDIA refused the VLC at '{}' - see the 'VideoLan4J' log lines above for VLC's own report",
                        vlcDir);
            }
            return ok;
        } catch (final Throwable t) {
            BridgeLog.error("native discovery crashed", t);
            return false;
        }
    }

    /**
     * {@code NativeDiscovery} caches a failed attempt in a private static flag and
     * then refuses to ever try again.  Clearing it is what makes a retry (after
     * WATERMeDIA already gave up) possible; if the JVM refuses the reflective
     * write we simply keep the single attempt WATERMeDIA allows.
     */
    private static void resetAttemptFlag() {
        try {
            final Field field = NativeDiscovery.class.getDeclaredField("attempted");
            field.setAccessible(true);
            field.setBoolean(null, false);
        } catch (final Throwable t) {
            BridgeLog.debug("could not clear NativeDiscovery's attempt flag: {}", t.toString());
        }
    }

    /**
     * True once {@code WaterMedia.prepare(loader)} ran, i.e. {@code WaterMedia.bootstrap}
     * is set.  Only then is it safe to let videolan4j instantiate its ServiceLoader
     * providers from this mod.
     */
    private static boolean waterMediaPrepared() {
        try {
            final Class<?> waterMedia = Class.forName("org.watermedia.WaterMedia", false,
                    AndroidVlc.class.getClassLoader());
            final Field bootstrap = waterMedia.getDeclaredField("bootstrap");
            bootstrap.setAccessible(true);
            return bootstrap.get(null) != null;
        } catch (final Throwable t) {
            BridgeLog.debug("could not inspect WaterMedia.bootstrap: {}", t.toString());
            return false;
        }
    }

    /**
     * Replaces WATERMeDIA's default factories with Android tuned arguments.
     * WATERMeDIA exposes this through {@code PlayerAPI.registerFactory}.
     */
    static synchronized boolean applyAndroidArguments() {
        if (argsApplied) return true;
        if (!installed || config == null || !config.overrideFactory) return false;
        try {
            if (!PlayerAPI.isReady()) return false;
            final String[] args = config.vlcArguments();
            PlayerAPI.registerFactory(WaterMedia.asResource("default"), args);
            PlayerAPI.registerFactory(WaterMedia.asResource("sound_only"), withVideoDisabled(args));
            argsApplied = true;
            BridgeLog.info("registered Android VLC factories: {}", String.join(" ", args));
            return true;
        } catch (final Throwable t) {
            BridgeLog.error("could not register the Android VLC factories", t);
            return false;
        }
    }

    private static String[] withVideoDisabled(final String[] args) {
        // drop any --vout=... first, so the result does not depend on libvlc letting the
        // last occurrence of a repeated option win
        final List<String> result = new ArrayList<>(args.length + 1);
        for (final String arg : args) {
            if (!arg.startsWith("--vout=")) result.add(arg);
        }
        result.add("--vout=none");
        return result.toArray(new String[0]);
    }

    /**
     * Called from the client tick hook.  Covers the load orders where the bridge
     * was constructed after WATERMeDIA already tried (and failed) its discovery.
     */
    public static void clientTick() {
        // the video watchdog runs for the whole session, not only until the factories are set
        VideoDiagnostics.tick();
        if (!installed || argsApplied) return;
        if (ticks > TICK_WINDOW) return;
        ticks++;
        if (ticks % 20 != 0) return;

        try {
            if (!PlayerAPI.isReady()) {
                if (ticks <= 200 && discoveryRetries < RETRY_LIMIT) {
                    discoveryRetries++;
                    requestDiscovery();
                }
                return;
            }
            applyAndroidArguments();
        } catch (final Throwable t) {
            BridgeLog.error("client tick hook failed", t);
        }
    }

    // ------------------------------------------------------------------ accessors

    public static boolean isInstalled() {
        return installed && vlcDir != null;
    }

    public static Path vlcDirectory() {
        return vlcDir;
    }

    public static Path gameDirectory() {
        return gameDir;
    }

    public static boolean isDiscoveryOk() {
        return discoveryOk;
    }

    public static boolean isActive() {
        return installed;
    }

    public static String version() {
        return VlcInstaller.class.getPackage() == null || VlcInstaller.class.getPackage().getImplementationVersion() == null
                ? "dev"
                : VlcInstaller.class.getPackage().getImplementationVersion();
    }

    /**
     * Resolves the Minecraft instance directory.  The loader is accessed reflectively so
     * that this class also loads in plain JVM harnesses, and every supported loader is
     * tried in turn: Fabric through {@code FabricLoader.getGameDir()}, NeoForge and Forge
     * through {@code FMLLoader.getGamePath()}, while {@code FMLPaths.GAMEDIR.get()} is the
     * older (and in some Forge versions the only) entry point.
     */
    private static Path resolveGameDir() {
        final String override = System.getProperty(AndroidEnv.PROP_GAME_DIR);
        if (override != null && !override.isEmpty()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        // Fabric first (a single well known accessor), then NeoForge, then Forge
        try {
            final Class<?> fabric = Class.forName("net.fabricmc.loader.api.FabricLoader");
            final Object instance = fabric.getMethod("getInstance").invoke(null);
            final Object value = fabric.getMethod("getGameDir").invoke(instance);
            if (value instanceof Path path) {
                BridgeLog.debug("game directory from FabricLoader.getGameDir()");
                return path.toAbsolutePath().normalize();
            }
        } catch (final Throwable t) {
            BridgeLog.debug("FabricLoader.getGameDir() unavailable ({})", t.toString());
        }
        // NeoForge and Forge both ship FMLLoader.getGamePath()
        for (final String loaderName : new String[] {
                "net.neoforged.fml.loading.FMLLoader",
                "net.minecraftforge.fml.loading.FMLLoader"}) {
            try {
                final Class<?> loader = Class.forName(loaderName);
                final Method getGamePath = loader.getMethod("getGamePath");
                final Object value = getGamePath.invoke(null);
                if (value instanceof Path path) {
                    BridgeLog.debug("game directory from {}.getGamePath()", loaderName);
                    return path.toAbsolutePath().normalize();
                }
            } catch (final Throwable t) {
                BridgeLog.debug("{}.getGamePath() unavailable ({})", loaderName, t.toString());
            }
        }
        for (final String pathsName : new String[] {
                "net.neoforged.fml.loading.FMLPaths",
                "net.minecraftforge.fml.loading.FMLPaths"}) {
            try {
                final Class<?> paths = Class.forName(pathsName);
                final Object gameDir = paths.getField("GAMEDIR").get(null);
                final Method get = gameDir.getClass().getMethod("get");
                final Object value = get.invoke(gameDir);
                if (value instanceof Path path) {
                    BridgeLog.debug("game directory from {}.GAMEDIR.get()", pathsName);
                    return path.toAbsolutePath().normalize();
                }
            } catch (final Throwable t) {
                BridgeLog.debug("{}.GAMEDIR.get() unavailable ({})", pathsName, t.toString());
            }
        }
        BridgeLog.warn("could not ask the loader for the game directory, falling back to the "
                + "process working directory {} - set -D{}=<path> if that is wrong",
                Paths.get("").toAbsolutePath(), AndroidEnv.PROP_GAME_DIR);
        return Paths.get("").toAbsolutePath().normalize();
    }
}
