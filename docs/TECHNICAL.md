# 技术分析：WATERMeDIA 的 VLC 加载机制与安卓补丁依据

本文记录编写 `WATERMeDIA: Android Bridge` 时对目标软件做的逆向结论，以及每个设计决定的证据来源。
所有引用行号来自本仓库 `vendor/` 下的反编译/源码产物：

| 产物 | 来源 |
|---|---|
| `vendor/downloads/watermedia-2.1.36.jar` | Modrinth 官方文件（sha512 `d5a2dd81…`） |
| `vendor/src/wm2136`, `vendor/src/wm2137` | 官方 `-sources.jar` |
| `vendor/decomp/src`, `vendor/decomp/src2` | Vineflower 1.10.1 反编译 `watermedia-2.1.36.jar` |
| `vendor/downloads/watermedia_binaries-3.0.0.6.jar` | 143 MB 的「WATERMeDIA: Binaries」模组 |
| `vendor/downloads/VLC-Android-3.7.1-<abi>.apk` | <https://get.videolan.org/vlc-android/3.7.1/> |
| `vendor/jna/libjnidispatch-<abi>.so` | PojavLauncher `v3_openjdk` 分支 |
| `vendor/downloads/watermedia-2.1.36-sources.jar` 等 | 编译期依赖 |

---

## 1. 启动链路

`org.watermedia.loaders.NeoFLoader` 本身就是 `@Mod("watermedia")`，**引导发生在 Mod 构造期**：

```java
// vendor/decomp/src2/loaders/NeoFLoader.java:14-34
@Mod("watermedia")
public class NeoFLoader implements ILoader {
   public NeoFLoader() {
      ...
      if (this.clientSide()) {
         WaterMedia.prepare(this).start();   // ← 构造期即启动全部模块
      } ...
   }
   @Override public Path processDir() { return FMLLoader.getGamePath(); } // 游戏目录
   @Override public Path tempDir() { return <java.io.tmpdir>/watermedia;  }
}
```

`WaterMedia.start()` 通过 `ServiceLoader.load(WaterMediaAPI.class)` 拉起模块（`vendor/src/wm2136/org/watermedia/WaterMedia.java:54-68`），
其中 `PlayerAPI` 的优先级是 `Priority.HIGH`。

结论：**VLC 的可用性必须在 Mod 构造阶段就绪**，这也是本模组声明 `ordering = "BEFORE"` 的原因。

## 2. PlayerAPI：唯一决定 VLC 能否加载的地方

```java
// vendor/src/wm2136/org/watermedia/api/player/PlayerAPI.java
101: private final boolean wrapped = Platform.isWindows() && Platform.is64Bit();
107: this.dir = bootstrap.tempDir().resolve("videolan");
109: this.zipInput = "videolan/" + zFilename;      // zFilename = "win-x64.zip"
112: if (this.wrapped) { ... } else { zipOutput = configOutput = null; }
162: LOGGER.warn(IT, "[NOT A BUG] {} doesn't contains VLC binaries for your OS and ARCH, ...");
187: String[] args = JarTool.readArray(Platform.isWindows()
         ? "videolan/arguments.json" : "videolan/arguments_linux.json");
202: private static final File customPathFile =
         WaterMedia.getConfigDir().resolve("custom_vlc_path.txt").toFile();
255: public boolean supported() { return Platform.isWindows() && Platform.is64Bit(); }  // Provider
```

要点：

1. 官方内置的 VLC 只有 `videolan/win-x64.zip`（**VLC 3.0.18**，见 jar 内 `videolan/version.cfg` = `3.0.18:2.1.9`），
   仅在 `Platform.isWindows() && is64Bit()` 时解包；**其余平台一律要求用户自装 VLC**。安卓因此必然失败。
2. 挂接点是现成的、公开的：`ConfigProvider` 读取
   `config/watermedia/custom_vlc_path.txt`（优先级 `OVERWRITE`），内容必须是**存在且为目录**的路径。

`2.1.37` 的源码与上表逐行一致（`vendor/src/wm2137/.../PlayerAPI.java` 同样的 101/187/202/255 行），
因此本补丁对 **2.1.36 与 2.1.37** 都成立；3.0.0.x 已改用 FFmpeg 后端，不在此补丁范围。

## 3. videolan4j 的发现算法（安卓走 LINUX 分支）

```java
// vendor/decomp/src/videolan4j/discovery/Environment.java:8-31
WINDOWS(patterns("libvlc\\.dll", "libvlccore\\.dll"), "plugins/", "vlc/plugins/"),
MACOS  (patterns("libvlc\\.dylib", "libvlccore\\.dylib"), "../plugins/"),
LINUX  (patterns("libvlc\\.so(?:\\.\\d)*", "libvlccore\\.so(?:\\.\\d)*"), "plugins/", "vlc/plugins/");
public static Environment get() {
   switch (Platform.getOSType()) { case 0: return MACOS; case 1: return LINUX; case 2: return WINDOWS; default: return null; }
}
```

* JNA 的 `Platform.getOSType()` 只看 `os.name`；**安卓（PojavLauncher 的 JVM）报告 `Linux`，因此天然落在 LINUX 分支**，
  文件名规则 `libvlc.so` / `libvlccore.so` 与安卓库一致。
* `NativeDiscovery.start()`（`vendor/decomp/src/videolan4j/discovery/NativeDiscovery.java:40-80`）遍历 provider，
  对每个候选目录执行 `start$searchPath`：
  只有当**两个 pattern 都在同一目录命中**时才返回该目录（`:104-112`），随后
  `setSearchPath` 注册 JNA 搜索路径并调用 `setPluginPath`（`:121-153`），
  后者要求 `<dir>/plugins/` 或 `<dir>/vlc/plugins/` **存在、可读、可执行**，然后
  `LibC.setenv("VLC_PLUGIN_PATH", …)`（`Environment.setVar` → `LibC.setEnv`）。
  最后 `testInstance()` 真正 `libvlc_new`，并用
  `LIBVLC_MIN_VERSION=3.0.0 / LIBVLC_MAX_VERSION=3.1.0`（`VideoLan4J.java:27-28`）校验版本。
* 已注册的 provider（jar 内 `META-INF/services/...IProvider`）：
  `PlayerAPI$ConfigProvider`(OVERWRITE)、`PlayerAPI$Provider`(HIGHEST, 仅 Windows)、
  `UserProvider`/`WinProvider`/`SystemProvider`/`MacProvider`/`LinuxProvider`/`JnaLibProvider`。

**设计结论**：只要给一个目录，里面同时有 `libvlc.so`、`libvlccore.so` 和一个 `plugins/` 子目录，
再把它写进 `custom_vlc_path.txt`，安卓就能走通整条发现链 —— 无需修改 WATERMeDIA 的字节码。

## 4. 内置 VLC 安卓库的构成（为什么上面那套能成立）

以 `VLC-Android-3.7.1-arm64-v8a.apk` 为例，`lib/arm64-v8a/` 只有 4 个 `.so`：

```
libc++_shared.so   1,253,544   ← libvlc 唯一需要随包的外部依赖
libmla.so          5.06 MB     ← MediaLibrary，播放不需要
libvlc.so         42,827,720   ← 单体构建（核心 + 全部模块）
libvlcjni.so       ~90 KB      ← 安卓 Java 层 JNI 胶水，JNA 路径不需要
```

对 `libvlc.so` 的分析（脚本化，见下）：

* 版本：`3.0.23 Vetinari`（`3.0.23-2-228-gb0e7b54f24`）→ 落在 WATERMeDIA 允许的 `[3.0.0, 3.1.0)`；
* 符号表里存在 **`vlc_static_modules`**、`module_LoadPlugins`、`libvlc_new`、`libvlc_get_version`
  → 确认是**静态模块单体构建**，APK 内也确实没有 `plugins/` 目录；
* 字符串表里出现模块短名 `vmem`（"Video memory output"）、`opensles`、`audiotrack`、
  `android_display`、`android_window`、`avcodec`、`filesystem`
  → WATERMeDIA 用的**回调视频输出（`libvlc_video_set_callbacks` → `vmem`）可用**，
  音频可选 `opensles`（纯原生）或 `audiotrack`（需 VLC 安卓 Java 层）；
* `DT_NEEDED`（自写 ELF 解析）：`libEGL.so, libGLESv2.so, libm.so, liblog.so, libc.so, libdl.so, libc++_shared.so`
  → 只有 `libc++_shared.so` 需要随包，其余是安卓系统库。`SONAME = libvlc.so`。

各 ABI 实测大小（`tools/pack-payload.ps1` 输出，deflate 后约为原始的 45%）：

| ABI | libvlc.so | libc++_shared.so |
|---|---|---|
| `arm64-v8a` | 42,827,720 | 1,253,544 |
| `armeabi-v7a` | 38,402,400 | 554,808 |
| `x86_64` | 48,973,736 | 1,229,808 |

**两个由此推出的实现决定**：

1. `libvlccore.so` 必须存在（LINUX pattern 要求），但单体库没有这个文件，而 Linux 分支上
   `LIBVLCCORE_NAME` 只被用于 `NativeLibrary.addSearchPath`（`NativeDiscovery.java:123`），
   **从不 dlopen**（只有 MACOS 分支会 `NativeLibrary.getInstance("vlccore")`）。
   → 硬链接/符号链接/复制/占位文件即可，`VlcInstaller.ensureCoreAlias` 按此顺序降级。
2. `DT_NEEDED` 的解析不发生在 dlopen 所在目录（安卓链接器按 soname 在全进程已加载库里匹配），
   → 必须先用 `System.load(<绝对路径>/libc++_shared.so)` 把它载入进程，再让 JNA 加载 `libvlc.so`。

## 5. 加载顺序陷阱（本模组的核心防御）

`ordering = "BEFORE"` 让本模组先于 WATERMeDIA 构造，此时 `WaterMedia.bootstrap == null`。
而 `PlayerAPI$ConfigProvider` 的**静态初始化**就会调用 `WaterMedia.getConfigDir()`：

```java
// vendor/src/wm2136/org/watermedia/api/player/PlayerAPI.java:202
private static final File customPathFile = WaterMedia.getConfigDir().resolve("custom_vlc_path.txt").toFile();
// WaterMedia.getConfigDir() → bootstrap.processDir().resolve("config/watermedia")
```

若在 `WaterMedia.prepare()` 之前调用 `NativeDiscovery.start()`，`ServiceLoader` 会实例化该 provider →
`NullPointerException` → `ExceptionInInitializerError`。JVM 对失败的类初始化是**粘性**的：
此后任何一次 `new ConfigProvider()` 都会立刻再抛 `NoClassDefFoundError`，**整局游戏再也无法发现 VLC**。

这个坑在桌面 harness 上被真实复现过（第一版实现触发 `ServiceConfigurationError`），修正后
`AndroidVlc.bootstrapEarly()` 只在 `WaterMedia.bootstrap != null` 时才主动触发发现，其余交给
WATERMeDIA 自身的启动流程；另有客户端 tick 兜底处理「本模组后构造」的反向顺序。

## 6. 验证方法

`tools/itest/BridgeHarness.java` 用普通 JVM + WATERMeDIA 官方 jar 复现整条链路，分三种运行：

| 模式 | 验证内容 |
|---|---|
| `install`（编译产物） | 解包、目录契约、缓存命中、`start$searchPath(LINUX, dir)` 命中、`PlayerAPI.ConfigProvider` 契约、`VlcDirectoryProvider` |
| `install`（打包 jar） | 同上，但类与载荷都来自 `dist/*.jar`，证明交付物自洽 |
| `discover` | 用 WATERMeDIA 自己 jar 里的 **Windows VLC** 当载荷，跑真实 `NativeDiscovery.start()` → 真的加载出 `3.0.18`，再用模组相同调用注册 Android 参数工厂 |

`discover` 模式是这套设计的最强证据：**除了 `.so` 文件本身，安卓上走的每一步都与它相同**。

## 7. 未在设备上验证的部分

| 项 | 现状 / 影响 |
|---|---|
| JNA 原生库版本 | ~~PojavLauncher 内嵌 6.1.6 而 classpath 是 5.14.0~~ **已由实机日志排除**：FCL 自带 `jna/5.14.0/libjnidispatch.so`，与 NeoForge 1.21.1 匹配，本模组的兜底正确地放弃覆盖（见 §8） |
| 位置探测的成功分支 | 桌面 harness 跑的是异构架构载荷，`System.load` 必然失败，只有"失败→换位置/退化"这条分支被覆盖；"在应用内部目录加载成功"需真机确认 |
| 音频输出 | 模块表已确认含 `opensles`/`audiotrack`，实际出声需设备确认；可用配置项调整 |
| `--file-logging` | WATERMeDIA 的发现测试会写 `logs/videolan-discovery.log`（相对工作目录）；本模组预先创建 `logs/` 目录 |

复现本文所有结论的脚本：`tools/download-deps.ps1`、`tools/download-vlc-apk.ps1`、`tools/fetch-jna.ps1`、
`tools/pack-payload.ps1`（内含 ELF/字符串探针）、`tools/build.ps1`、`tools/itest.ps1`。

## 8. 实机日志分析（FCL / Android 16 / aarch64 / NeoForge 21.1.248）

第一版（1.0.0）在 FCL 上的运行日志推翻了一个假设、确认了另一个，并暴露了本模组的一个真实缺陷。

### 8.1 发现链完全命中（证明钩子设计正确）

```
[Android Bridge]: bundled VLC 3.0.23 ... for arm64-v8a: extracted 2 native file(s) ... 83 MiB
[Android Bridge]: pointed WATERMeDIA at the bundled VLC through .../config/watermedia/custom_vlc_path.txt
[watermedia/PlayerAPI]: [NOT A BUG] WATERMeDIA doesn't contains VLC binaries for your OS and ARCH   ← 原本的行为
[VideoLan4J/NativeDiscovery]: Searching using 'WaterMedia Config Provider'                          ← 我们写的配置文件生效
[VideoLan4J/NativeDiscovery]: Setting plugins path to '.../vlc/arm64-v8a/plugins'                   ← 目录契约满足
[VideoLan4J/NativeDiscovery]: Founded VLC binaries in '...' using 'WaterMedia Config Provider', running test...
```

即：`custom_vlc_path.txt` → `ConfigProvider` → `start$searchPath`（两个 pattern 命中）→ `setPluginPath`
全部按预期工作；`ordering = "BEFORE"` 也生效（我们的日志时间戳早于 watermedia 的模块启动）。
另外 `83 MiB` 说明外置存储（FUSE）上硬链接与符号链接都被拒绝，`libvlccore.so` 退化成了整份 42 MB 复制。

### 8.2 真正的失败：Android 链接器命名空间

```
java.lang.UnsatisfiedLinkError: Unable to load library 'vlc':
dlopen failed: library ".../config/watermedia_android_bridge/vlc/arm64-v8a/libvlc.so"
  needed or dlopened by "/data/data/com.tungsten.fcl/app_runtime/jna/5.14.0/libjnidispatch.so"
  is not accessible for the namespace "clns-10"
```

同一次运行里，**本模组自己的预加载也被同一条规则拒绝**（`clns-10` 由 `libjvm.so` 加载）：

```
[DEBUG] libc++_shared.so is already resident (... dlopen failed: library
  "/storage/emulated/0/.../libc++_shared.so" needed or dlopened by
  ".../jre21/lib/server/libjvm.so" is not accessible for the namespace "clns-10")
```

结论：**命名空间（classloader namespace）只允许 dlopen 它自己可访问的路径**，而 FCL 的实例目录位于
`/storage/emulated/0`（模拟/外置存储，API 24 起禁止从中加载可执行代码）。这不是权限问题——
`File.canRead()/canExecute()` 在该目录上全部返回 `true`，所以 1.0.0 里基于权限的 `layoutUsable()`
兜底**永远不会触发**。同时这条 debug 日志也暴露了 1.0.0 的日志分类缺陷：
`System.load` 抛的 `UnsatisfiedLinkError` 被当成"库已加载"处理（真实原因被藏进 DEBUG）。

### 8.3 1.0.1 的设计改动

1. 候选目录按"链接器可达性"排序：`installLocation`（显式）→ `<app>/files/...` → JNA 原生目录 →
   `java.io.tmpdir` → 游戏目录。应用私有根目录由启动器给出的路径推导
   （`/data/user/<id>/<pkg>/...` 与 `/data/data/<pkg>/...` 两种形态，`AndroidVlc.androidAppDataRoot`）。
2. **判据换成真实的 `System.load`**（`AndroidVlc.probeSharedRuntime`）：加载 `libc++_shared.so` 成功才算可用，
   失败时给出可读原因（识别 `not accessible for the namespace` 与 `already loaded` 两种情形）并尝试下一个候选；
   全部失败则退化为第一个已成功解包的目录，同时打印警告。
3. 选中位置后清理游戏目录里的旧副本（回收 83 MB）。
4. 新增 `installLocation` 配置项，便于手工指定。

### 8.4 同一份日志里与本模组无关的两个崩溃源

**（a）WATERMeDIA 2.1.37 自身在启动期原生内存 OOM：**

```
[watermedia/Bootstrap]: Runtime memory Usage: 1239MB/2286MB
[watermedia/Bootstrap]: Starting ImageAPI
java.lang.OutOfMemoryError: null
  at org.lwjgl.system.MemoryUtil.nmemAlignedAllocChecked(MemoryUtil.java:675)
  at org.watermedia.api.render.RenderAPI.createByteBuffer(RenderAPI.java:41)
  at ...ImageRenderer.<init>(ImageRenderer.java:67)
  at ...ImageAPI.start(ImageAPI.java:249)      → WaterMedia.start(WaterMedia.java:66) → NeoFLoader.<init>
```

量化：`ImageAPI.start(249)` 加载内置 `pictures/loading.gif`，实测该文件 **200×200、40 帧**，
`getImageBuffer` 每帧申请 `w*h*4 = 160,000` 字节，总量仅约 **6 MiB**。
（**注意**：本节当时把它归因于"进程原生内存已经耗尽"，这个判断已被 §10 的真机实测**推翻**——
真正原因是 LWJGL 的对齐分配路径在该设备上不可用，与内存水位无关。）
异常发生在 `NeoFLoader` 构造期，而它只 `catch (Exception)`，
抓不到 `Error`，于是整个 watermedia 被 FML 标记为加载失败 → 游戏崩溃。
**这与 VLC 是否可用无关：即使 VLC 修好，这次启动仍会崩。**

**（b）`bilibili_media` 2.3 缺少 `me.shedaniel.autoconfig.ConfigData`**（Cloth Config / AutoConfig 前置未安装），
且该模组还通过 mixin 挂在 `WaterMedia.start` 上（`IHateWaterMediaMixin`）；排查 (a) 时建议先移除它。

### 8.5 1.0.1 的真机复测结果

```
[Android Bridge]: using /data/user/0/com.tungsten.fcl/files/watermedia_android_bridge/arm64-v8a (payload loaded by the system loader)
[Android Bridge]: bundled VLC 3.0.23 ... already extracted
[watermedia/Bootstrap]: Runtime memory Usage: 351MB/2688MB
```

* 位置探测的**成功分支**在真机成立：应用内部目录可被链接器加载，`System.load(libc++_shared.so)` 通过；
* 命名空间问题消失，第二次启动命中版本标记（不重复解包）；
* 8.4(a) 的 ImageAPI OOM 不再出现（内存参数已放宽到 2688 MB 上限），8.4(b) 的 `bilibili_media` 也正常启动。

## 9. 第二个真机阻断点：VLC 安卓版要求 `JNI_OnLoad` 必须被执行

1.0.1 之后库能加载了，但进程被 VLC 自己的断言 `abort`：

```
[VideoLan4J]: Founded VLC binaries in '/data/user/0/com.tungsten.fcl/files/watermedia_android_bridge/arm64-v8a' ... running test...
../../src/android/specific.c:174: void system_Configure(libvlc_int_t *, int, const char *const *): assertion "s_jvm != NULL" failed
OpenJDK exited with code : 6        ← SIGABRT：不是崩溃报告，是 JVM 进程被杀
```

### 9.1 源码依据（VLC 3.0.23 `src/android/specific.c`，已存 `vendor/vlc/specific-3.0.23.c`）

```c
36:  static JavaVM *s_jvm = NULL;
97:  /* This function is called when the libvlcore dynamic library is loaded via the
98:   * java.lang.System.loadLibrary method. Therefore, s_jvm will be already set
99:   * when libvlc_InternalInit is called. */
100: jint JNI_OnLoad(JavaVM *vm, void *reserved) {
103:     s_jvm = vm;
106:     if (GetEnv(...) != JNI_OK) return -1;
109:     jclass clazz = FindClass(env, "android/os/Environment");
110:     if (ExceptionCheck(env)) return -1;              // ← s_jvm 已赋值，但版本返回 -1
113-128: 读取 DIRECTORY_DOWNLOADS/DOCUMENTS/MUSIC/PICTURES/MOVIES
131:     fields.Environment.getExternalStoragePublicDirectory =
             GetStaticMethodID(clazz, "getExternalStoragePublicDirectory",
                               "(Ljava/lang/String;)Ljava/io/File;");
139-153: 缓存 java/io/File.getAbsolutePath() 与 java/lang/System.getProperty(String)
156:     return JNI_VERSION_1_2;
170: void system_Configure(libvlc_int_t *p_libvlc, ...) {
174:     assert(s_jvm != NULL);                          // ← 断点
175:     var_Create(p_libvlc, "android-jvm", VLC_VAR_ADDRESS);
176:     var_SetAddress(p_libvlc, "android-jvm", s_jvm);
177: }
```

`system_Configure` 本身只做一件事：把 JavaVM 发布到 libvlc 的 `"android-jvm"` 变量（供 `audiotrack`
音频输出回调 Java 用）。**创建实例不再需要别的 Java 类**，但断言是无条件的。

### 9.2 为什么 JNA 的加载方式必然踩坑

JNI 规范与 HotSpot 的实现只在库经 `System.load`/`System.loadLibrary`（→ `ClassLoader$NativeLibrary.load`
→ `JVM_LoadLibrary`）加载时查找并调用 `JNI_OnLoad`；**JNA 的 `Native.open` 是裸 `dlopen`，不会触发它**。
所以 `s_jvm` 永远是 `NULL`，只要 `libvlc_new` 一执行就 `abort`。
这也解释了为什么 WATERMeDIA 官方从未在安卓上跑通：即便有人手动塞进 VLC 库，也会撞在同一处。

已核实 `libvlc.so`（APK 内 arm64-v8a）的 `.dynsym` 里 **`JNI_OnLoad` / `JNI_OnUnload` 是 `STB_GLOBAL`
导出符号**，因此 `System.load` 能调用到它们。

### 9.3 1.0.2 的修复

1. **主动预加载**：`AndroidVlc.primeVlcRuntime(dir)` 在解包/探测阶段执行
   `System.load(<dir>/libvlc.so)`。即使 `JNI_OnLoad` 返回 -1（下述风险），`s_jvm` 也在其第 103 行已赋值；
   顺带让 soname 在进程内注册，之后 JNA 的 `dlopen` 复用同一份镜像而不是再加载一份。
2. **补齐 `JNI_OnLoad` 需要的形状**：随包提供最小 `android.os.Environment` 桩类
   （5 个静态 `String` 常量 + `static File getExternalStoragePublicDirectory(String)`，
   名字/修饰符/描述符与 `GetStaticFieldID`/`GetStaticMethodID` 的要求完全一致）。
   这样 `FindClass` 能成功，`JNI_OnLoad` 返回 `JNI_VERSION_1_2`，库不会被 JVM 因"非法 JNI 版本"卸载，
   `s_jvm` 稳定有效。桩类由"调用 `System.load` 的那个类"的类加载器可见——按 JNI 规范，
   `JNI_OnLoad` 里的 `FindClass` 正是用这个类加载器，因此桩类必须放在模组 jar 内。
3. 失败不更糟：预加载失败会打印可读原因，行为退回到 1.0.1。

集成 harness 新增 3 项**结构性核对**，逐条对照 `JNI_OnLoad` 实际会做的查找
（类、5 个常量、方法签名、`java.io.File`/`java.lang.System` 的方法），并分别在编译产物与打包 jar 上验证，
确保桩类被打进交付物。

### 9.4 尚存的未知

`JNI_OnLoad` 里的 `FindClass` 用的是"加载该库的类的类加载器"。在真机上这个加载器是 NeoForge 的
`TransformingClassLoader`（模块类加载器），它应当能从本模组 jar 解析 `android.os.Environment`；
这一点只有真机日志能最终确认。若下一个日志仍出现同一断言，则说明该加载器没有把桩类交给 JNI，
届时的后备方案是：从 `AndroidVlc.class.getProtectionDomain().getCodeSource().getLocation()` 取出模组 jar 路径，
用一个子 `URLClassLoader` 加载一个只负责调用 `System.load` 的辅助类，使 `JNI_OnLoad` 的 `FindClass`
落在确定能看到桩类的加载器上。

### 9.5 真机结果（1.0.2）：已确认

```
[Android Bridge]: using /data/user/0/com.tungsten.fcl/files/watermedia_android_bridge/arm64-v8a (payload and VLC's JNI_OnLoad initialised)
[VideoLan4J]: VLC test instance created successfully
[VideoLan4J]: Successfully loaded VLC 3.0.23 Vetinari in '...' using 'WaterMedia Config Provider'
[watermedia/Bootstrap]: Module PlayerAPI loaded successfully
```

即：`System.load` 确实触发了 `JNI_OnLoad`，桩类确实被 `FindClass` 解析到，`s_jvm` 断言通过，
**VLC 在安卓上首次加载成功**（§9.4 的未知项已消除，后备方案不需要了）。

## 10. 最后一个阻断点：WATERMeDIA 自身的启动期原生内存 OOM（与 VLC 无关）

1.0.2 让 VLC 可用之后，`WaterMedia.start()` 继续执行到 `ImageAPI`，随即失败：

```
[watermedia/Bootstrap]: Runtime memory Usage: 239MB/2152MB      ← 堆仅用 239MB，远未占满
[watermedia/Bootstrap]: Starting ImageAPI
java.lang.OutOfMemoryError: null
  at org.lwjgl.system.MemoryUtil.nmemAlignedAllocChecked(MemoryUtil.java:675)
  at org.lwjgl.system.MemoryUtil.memAlignedAlloc(MemoryUtil.java:690)
  at org.watermedia.api.render.RenderAPI.createByteBuffer(RenderAPI.java:41)
  at RenderAPI.getImageBuffer(RenderAPI.java:114) ← ImageRenderer.<init>(:67) ← ImageAPI.start(:249)
  at org.watermedia.WaterMedia.start(WaterMedia.java:66) ← NeoFLoader.<init>(NeoFLoader.java:25)
```

崩溃报告同时给出 `Memory: 1199 MiB / 2048 MiB up to 2152 MiB` 与 `JVM Flags: ... -Xmx2152m -Xms2048m`。

**判定依据：**

1. 失败的是一次 **156 KB 的原生分配**（`200×200×4`，即内置 `pictures/loading.gif` 的一帧），
   而 Java 堆当时只用了 239 MiB / 2152 MiB —— 与堆无关；
2. **1.0.0 的日志里，在 libvlc 完全没被加载（dlopen 被命名空间拒绝）的情况下，同一位置、同样大小的分配也失败了**
   —— 所以这个 OOM 不依赖 VLC，也不依赖本模组的任何行为；
3. `-Xms2048m` 让 JVM 启动即提交 2 GiB，加上 GL 驱动、LWJGL、MC 本体与映射进来的 libvlc，
   进程原生内存触顶，`malloc` 返回 `NULL`（LWJGL 的 `nmemAlignedAllocChecked` 于是抛 `OutOfMemoryError`）；
4. 异常发生在 `NeoFLoader` 构造期，而它 `catch (Exception)` 抓不到 `Error` → FML 判定 watermedia 加载失败。

**1.0.3 的处理与真机实测结果：**

| 手段 | 位置 | 性质 |
|---|---|---|
| Mixin 兜底：`RenderAPI.createByteBuffer` 失败时退到 `ByteBuffer.allocateDirect` | 本模组 | **实测证明这就是正解**（见下） |
| 改启动器内存参数：`-Xms2048m` → `-Xms512m` | 用户侧配置 | 可选的内存优化，不是该崩溃的前提 |

1.0.3 的真机日志（NeoForge 21.1.248 / FCL / aarch64）：

```
Starting ImageAPI
WARN  LWJGL's aligned native allocation of 160000 bytes failed (java.lang.OutOfMemoryError);
      retrying with a plain direct buffer ...（共 8 次，全部回退成功）
...
Hmjmfabc加入了游戏
```

**这条结果推翻了 §10 早先的"原生内存耗尽"假设**：同一个 160,000 字节，`memAlignedAlloc` 返回 `NULL`
之后紧接着的 `ByteBuffer.allocateDirect`（底层同样是 `malloc`）**立即成功**，而且重复 8 次都成功。
所以失败是**特定于 LWJGL 的对齐分配路径**的（bionic 的 `posix_memalign`/`aligned_alloc` 对
`alignment = 1` 的处理，或 FCL 那个 `lwjgl-3.3.3-snapshot` 构建的实现），与堆大小、`-Xms2048m`
都没有因果关系。崩溃报告里的 `Memory: 1199 MiB / 2048 MiB` 只是启动期的正常水位。

Mixin 的注入点已对着 WATERMeDIA 的真实字节码核对（`javap -c`，2.1.36 与 2.1.37 完全一致）：

```
public static java.nio.ByteBuffer createByteBuffer(int, int);
   2: invokestatic  // Method org/lwjgl/system/MemoryUtil.memAlignedAlloc:(II)Ljava/nio/ByteBuffer;
```

因此 `@Redirect` 写作
`method = "createByteBuffer(II)Ljava/nio/ByteBuffer;"` +
`at = @At(value = "INVOKE", target = "Lorg/lwjgl/system/MemoryUtil;memAlignedAlloc(II)Ljava/nio/ByteBuffer;")`，
`remap = false`、`require = 0`，配置 `"required": false`：

* 目标方法或调用点变化时**只丢兜底**，不会让游戏崩；
* `ByteBuffer.allocateDirect` 同样是 direct buffer，后续 `MemoryUtil.memAddress` / `glTexImage2D` 上传照常工作；
* 两级失败时抛出最初的 `OutOfMemoryError`，并打印明确的处置建议。

harness 为此增加 6 项**结构性核对**（分别在编译产物与打包 jar 上运行）：`RenderAPI.class` 里存在
`createByteBuffer` 与 `MemoryUtil.memAlignedAlloc` 调用、混入类引用的方法名/描述符与之一致、
mixin 配置随包且 `"required": false`、`mods.toml` 里有对应的 `[[mixins]]` 条目。

## 11. 许可选择的技术依据（1.0.4）

许可不是随意挑的，而是由「随包分发哪个二进制」反推出来的。已核实的上游原文：

| 组件 | 许可 | 依据 |
|---|---|---|
| VLC for Android 的 `libvlc.so`（APK 内的**单体**构建） | GPLv2-or-later；上游自述事实上为 GPLv3 | `vlc-android` README *License* 节：*"VLC for Android is licensed under GPLv2 (or later). Android libraries make this, de facto, a GPLv3 application."* |
| LibVLC 引擎 | LGPL-2.1-or-later | 同上：*"VLC engine (LibVLC) for Android is licensed under LGPLv2."*；`libvlc-all` 的 POM 亦标注 LGPL 2.1 |
| `libc++_shared.so` | Apache-2.0 with LLVM exception | LLVM `LICENSE.TXT` |
| PojavLauncher 的 `libjnidispatch.so`（1.0.4 起**不再随包**） | LGPL-3.0 | 其仓库根 `LICENSE` 全文即 LGPLv3 |
| WATERMeDIA（运行期依赖，不随包） | PolyForm Strict 1.0.0（非开源） | 其 jar 内 `neoforge.mods.toml` |

关键点：桌面版 VLC 用动态插件把 LGPL 核心与 GPL 模块**分离**，而安卓版是**单体静态链接**；
把该 `libvlc.so` 打进 jar，整包分发就落入 GPL 范围（GPLv2+ 可升 v3；LGPL-2.1 §3 与 LGPL-3 §2
都允许把组合作品按 GPLv3 发布）→ 本模组采用 **GPL-3.0-or-later**。

1.0.4 据此落实：仓库根 `LICENSE`（GPL-3.0 全文）、jar 内 `META-INF/licenses/` 五份许可全文
（GPL-3.0 / GPL-2.0 / LGPL-2.1 / Apache-2.0 / LLVM-exception）、重写的第三方声明、全部源文件的
SPDX 头（`tools/build.ps1` 会在编译前强制校验），并移除 `libjnidispatch.so`——它在 FCL/PojavLauncher 上
因版本不匹配（内嵌 6.1.6 ↔ classpath 5.14.0）基本永不启用，删掉即少分发一个第三方二进制
（需要时 `tools/pack-payload.ps1 -IncludeJna` 可加回，同时必须补其许可声明）。

若要把本模组代码改为 MIT/Apache-2.0，唯一干净做法是**不把 GPL 载荷打进同一个 jar**（分离分发或首次运行获取）。

## 12. 视频链路：为什么是白屏，「卡在第一帧」的两种成因（1.0.5）

设备实测：**音频正常、WATERFrAMES 屏幕是一块纯白**（有黑色屏幕边框，FPS 6）。要定位必须先把
WATERMeDIA 的取帧路径读到底。

### 12.1 链路

```
libvlc（解码器 → vout=vmem）
   └─ format 回调  → VideoPlayer.getBufferFormat(w,h)  → new BufferFormat(Chroma.RGBA,w,h)
                     → NativeBuffers.allocate()        → Buffers.alloc(pitch*lines)
                                                       → VideoLan4J.bufferAllocator
                                                       = RenderAPI::createByteBuffer（本模组已接管）
   └─ lock/unlock/display 回调（JNA，VLC 自己的 vout 线程）
        lock()   : semaphore.acquire() → 写 planes 指针 → semaphore.release()
        unlock() : 空实现
        display(): renderExecutor.execute( → bindTexture → tryAcquire(1s) → uploadBuffer → release() )
```

`display` 与 `lock` 靠 `VideoPlayer.semaphore`（1 个许可）互斥：`lock` 等上传结束才把缓冲区交给
VLC。**注意崩溃点**：`semaphore.release()` 在 `uploadBuffer` 之后，`bindTexture` 之前没有 try/finally——
所以一旦 `uploadBuffer` 抛异常，许可永久泄漏，下一次 `lock()` 无限阻塞，表现就是**视频永久冻住、
音频照常**（音频是完全独立的管线）。这条路径必须「绝不抛异常」。

### 12.2 成因 A：OpenGL ES 不接受 `GL_UNSIGNED_INT_8_8_8_8_REV`

`RenderAPI.uploadBuffer`（2.1.36/2.1.37 相同）：

```java
GL11.glTexImage2D (GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, format, 0x8367, buffer); // first
GL11.glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, format, 0x8367, buffer);
```

`0x8367 = GL_UNSIGNED_INT_8_8_8_8_REV`，是**桌面 GL 专有**的像素类型：GL ES 3.x 的合法
format/type 组合表里只有 `GL_UNSIGNED_BYTE`、`GL_UNSIGNED_SHORT_5_6_5`、`_4_4_4_4`、`_5_5_5_1`、
`GL_UNSIGNED_INT_2_10_10_10_REV`、`GL_HALF_FLOAT`、`GL_FLOAT` 等，**没有 8_8_8_8_REV**。安卓上
Minecraft 跑在 GL ES 之上（FCL 的 GL 翻译层），调用被拒 → 纹理对象始终没有存储 → 采样不完整纹理，
屏幕就是一片纯白/空白（同时完全不抛 Java 异常，因此日志里"什么都看不到"）。

**等价替换**：对 `format = GL_RGBA`，`GL_UNSIGNED_INT_8_8_8_8_REV` 与 `GL_UNSIGNED_BYTE` 的内存
字节序**完全相同**（前者是"32 位整数按 R,G,B,A 从最低字节开始"，在小端机上就是 R,G,B,A，与后者
逐字节一致）。因此换成 `GL_UNSIGNED_BYTE` 画面不变，且桌面/GLES 通吃。

`VideoUpload` 在此基础上加了：unpack 状态归零（VLC 给的是紧密打包缓冲）、尺寸变化时重新
`glTexImage2D`（否则子图会越界被拒）、`glGetError()` 自检、失败时回退到
「先 `glTexImage2D(..., null)` 分配空存储再 `glTexSubImage2D`」，并且**所有异常都吞掉**（见 12.1）。
`RenderAPIMixin` 用 `@Inject(at = HEAD, cancellable = true)` 接管
`uploadBuffer(Ljava/nio/ByteBuffer;IIIIZ)V`——描述符必须逐字对上，否则 `require = 0` 会静默失效，
harness 因此专门核对这个字符串。

### 12.3 成因 B：vout / 解码器在安卓上不出帧

同一份 `libvlc.so` 的字符串扫描（`vendor/vlc/raw-arm64/.../libvlc.so`，42.8 MB）：

| 符号 | 次数 | 含义 |
|---|---|---|
| `vmem` / `vmem-lock` / `vmem-unlock` / `vmem-display` / `vmem-data` | 13 / 1 / 1 / 1 / 1 | 回调视频所需的 vmem 输出与它的 var 接口**都在**，回调路径可用 |
| `android_display` / `android_window` | 1 / 1 | 安卓原生 vout 也在包里，它们需要 Java 侧 `Surface`；这里没有 → 必须显式钉住 vmem |
| `mediacodec` / `mediacodec_ndk` | 11 / 2 | MediaCodec 硬解在包里，且安卓默认会先试它——而它要往 Surface 送帧 |
| `avcodec` | 284 | 软件解码器在，硬解失败时可回退 |
| `RV32` | 23 | vmem 需要的 RGBA 色度也在 |

因此新增 `--vout=vmem`（把选择钉死在回调输出，绕开要 Surface 的 android_* ）与
`--avcodec-hw=none`（避开 MediaCodec 的"打开成功但永远不出图"），两者都可在
`config/watermedia_android_bridge.properties` 里改回。

### 12.4 让日志自己说话（可诊断性设计）

三种失败形态从外部看起来都是"白屏"，所以 1.0.5 把链路节点写进日志：

| 日志 | 证明的事情 |
|---|---|
| `video player #1 created` | `VideoPlayer` 构造成功（`VideoPlayerMixin` 注入 2 参构造的 RETURN，1 参构造会委托给它） |
| `first video frame from VLC: WxH, N plane(s), chroma …` | 解码器 + vout 都出图了（`display` 回调被调用），问题只可能在 GL 侧 |
| `video texture upload works: texture T <- WxH (… bytes, …)` | 帧真的进了纹理（此时若屏幕还是白，问题在渲染/屏幕侧） |
| `… failed for WxH with GL_INVALID_*` / `could not upload a video frame` | 上传被驱动拒绝，错误码 + 已尝试的回退路径在同一条里 |
| 20 秒无人出帧 → `no video frame from VLC in the N s …` | 由 `VideoDiagnostics.tick()`（挂在客户端 tick 上）判定：libvlc 侧没出帧 |

这些埋点只读计数，不改变播放行为；`VideoDiagnostics` 每个入口都自带 try/catch，`VideoUpload` 更是
「绝不抛」，避免埋点本身把 12.1 的信号量弄坏。harness 复核了：混入类随包、注入描述符正确、
mixin 配置列出两个混入、`VideoUpload.class` 里确实是 `GL_UNSIGNED_BYTE` + 两步回退、
以及**在没有 GL 上下文时调用 `VideoUpload.upload` 不抛异常**（正是信号量保护契约）。

## 13. 多 loader 移植：1.20.1（Forge / Fabric）与 1.21.1（NeoForge）（1.0.5）

### 13.1 为什么这不是重写

补丁的全部功能面只与 **WATERMeDIA + LWJGL + libVLC + JNA** 打交道，不碰 Minecraft 类：

| 功能 | 依赖的东西 | 与 MC/loader 版本有关吗 |
|---|---|---|
| 解包内置 VLC、位置探测、`JNI_OnLoad` 预加载 | `libvlc.so`、`System.load`、`android.os.Environment` 桩 | 无关 |
| 接通发现链（`custom_vlc_path.txt` + `IProvider`） | `org.watermedia.videolan4j.discovery` | 无关（WATERMeDIA 自己的 API） |
| LWJGL 对齐分配兜底、GLES 安全上传、视频埋点 | `MemoryUtil` / `GL11` / `VideoPlayer` | 无关（LWJGL 3.3.x 两版都有这些符号） |
| 注册 VLC 工厂（`--aout` / `--vout` / `--avcodec-hw`） | `PlayerAPI.registerFactory` | 无关 |

因此 **13 个功能源文件在三个构建里逐字节相同**（`src/main/java`），
差异被压缩到两处：入口类与 loader 元数据。

### 13.2 上游证据：WATERMeDIA 2.1.37 是同一份 jar

* Modrinth 上 `watermedia-2.1.37.jar` 声明支持 `1.16.5, 1.18.2, 1.19.2, 1.20.1, 1.21.1, 1.21.5`
  × `fabric, forge, neoforge`，文件大小 **37,046,674 B**，与本仓库 `vendor/downloads/watermedia-2.1.37.jar`
  **完全一致**；
* 该 jar 内 967 个 class 中 `net/minecraft/...` 引用数 = **0**（用 Latin-1 扫常量池验证）；
* jar 内同时含 `META-INF/mods.toml`（Forge，`loaderVersion="[36,)"`）与
  `META-INF/neoforge.mods.toml`（NeoForge，`[3,)`），并含 `ForgeLoader` / `NeoFLoader` / `FabricLoader`
  与对 `net/minecraftforge/fml`、`net/neoforged/fml`、`net/fabricmc/api` 的引用——即运行时按 loader 自选实现。

结论：依赖区间 `[2.1.36,3.0.0)` 在两个目标上指的都是同一个前置 jar，不需要 1.20.1 专用的 WATERMeDIA。

### 13.3 每个 loader 到底差在哪

| 维度 | 1.20.1 / Forge 47.x | 1.21.1 / NeoForge 21.1.x |
|---|---|---|
| 入口类 | `src/loader/forge/.../AndroidBridge.java` | `src/loader/neoforge/.../AndroidBridge.java` |
| 注解 | `@Mod(MOD_ID)`（**Forge 的 `@Mod` 没有 `dist` 成员**）+ `@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = FORGE)` + `TickEvent.ClientTickEvent`（判 `Phase.END`） | `@Mod(value = MOD_ID, dist = Dist.CLIENT)` + `NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, …)` |
| 元数据文件 | `META-INF/mods.toml` | `META-INF/neoforge.mods.toml` |
| loader 版本 | `loaderVersion="[47,)"` | `loaderVersion = "[3,)"` |
| 依赖语法 | `mandatory=true` + `side="CLIENT"` | `type = "required"` + `side = "CLIENT"` |
| mixin 注册 | **jar 清单 `MixinConfigs: watermedia_android_bridge.mixins.json`**（Forge 的 `mods.toml` 没有 `[[mixins]]`） | `[[mixins]] config = "…"` |
| `pack.mcmeta` | `pack_format 15` | `pack_format 34` |
| 编译依赖 | `forge-1.20.1-47.4.10-universal` + `javafmllanguage-1.20.1-47.4.10`（`net.minecraftforge.fml.common.Mod` 在这里）+ `mergetool-1.1.5-api`（`net.minecraftforge.api.distmarker.Dist` 在这里）+ `fmlcore` + `eventbus-6.0.5` + `sponge-mixin-0.12.5` + LWJGL 3.3.1 | `neoforge-21.1.235-universal/client` + `loader-4.0.44` + `bus-8.0.5` + `mergetool-2.0.0-api` + `sponge-mixin-0.15.2` + LWJGL 3.3.3 |

两个坑值得记下来：`@Mod` 注解**不在** Forge 的 universal jar 里（在 `javafmllanguage`），
`Dist` 也不在（在 `mergetool-api`）——直接把 `universal` 丢进 classpath 会得到 "找不到符号"。

### 13.4 构建与校验

`tools/build.ps1 -Target forge1201|neoforge1211|all`：同一批 `src/main/java`，
加上目标对应的 `src/loader/<loader>/java` 与 `src/loader/<loader>/resources`（含各自的 `pack.mcmeta`
与元数据），classpath 与清单属性按目标注入，产物为
`watermedia_android_bridge-1.0.5+mc1.20.1-forge.jar` / `…+mc1.21.1-neoforge.jar`（载荷共用一份，
payload 版本仍为 `vlc3.0.23-android3.7.1-r2`，因此从 1.0.5 升级不重新解包）。

`tools/itest.ps1` 现在对**每个 target** 分别跑"编译产物"和"打包 jar"两轮：
`checks = 60 / 61（forge）/ 61 / 62（neoforge）/ 8（真实发现链），failures = 0`。新增的结构性核对包括：
`mods.toml` 与 `neoforge.mods.toml` 只出现一个、`pack_format` 与 MC 版本匹配、入口类引用的 loader
注解与目标一致（Forge jar 里不能出现 `net/neoforged/fml/common/Mod`，反之亦然）、
Forge 的 `MixinConfigs` 清单项、NeoForge 的 `[[mixins]]`，以及依赖语法（`mandatory=true` ↔ `type="required"`）。

离线无法验证的部分只有一件：真正的 Forge 启动（需要一台装了 Forge 47.4.10 的实例）。
因此这次移植的真机复测重点是"1.20.1 上能否加载并把 VLC 接上"，判定方式仍是 §12.4 的三行日志。

### 13.5 游戏目录探测（三个 loader 统一）

`AndroidVlc.resolveGameDir()` 现在按顺序尝试：系统属性覆盖 → Fabric 的 `FabricLoader.getInstance().getGameDir()` →
`net.neoforged.fml.loading.FMLLoader.getGamePath()` → `net.minecraftforge.fml.loading.FMLLoader.getGamePath()`
→ `net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()` → `net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()`
→ 工作目录（并打印告警，提示用 `-Dwatermedia.androidbridge.gameDir=<路径>` 指定）。
两边都是反射调用，因此这个类在纯 JVM harness 里也能加载。

### 13.6 Fabric：为什么必须用 `preLaunch`，以及中介名桩

Fabric **没有加载顺序声明**，而 WATERMeDIA 是本模组的依赖 → Fabric 按依赖顺序初始化，WATERMeDIA
会**先**跑。这在别处只是"晚一点生效"，在这里却是**致命**的，因为 videolan4j 的发现是一次性的：

```java
// 反编译自 videolan4j NativeDiscovery
public static synchronized boolean start() {
    if (discovered) return true;
    else if (attempted) return false;   // ← 一旦试过，后续调用不会再试
    ...
    attempted = true;                   // ← 找不到/失败时置位
}
```

所以第一次（WATERMeDIA 启动时）载荷必须已经就位，否则 Fabric 上 VLC 永远加载不上——这也解释了为什么
现有的 tick 兜底只对"重注册参数"有效，对"重新发现"无效。Fabric 的解法是 **`PreLaunchEntrypoint`**：
它在所有模组的 `main`/`client` 初始化器之前、游戏启动之前运行，正好用来 `bootstrapEarly()`。

| Fabric 关注点 | 做法 |
|---|---|
| 入口点 | `fabric.mod.json` 里 `preLaunch`（装载荷）+ `client`（tick 兜底，`ClientTickEvents.END_CLIENT_TICK`）指向同一个类；`AndroidVlc.bootstrapEarly()` 幂等，调用两次无副作用 |
| mixin 注册 | `fabric.mod.json` 的 `"mixins": [...]`（Fabric 不看 jar 清单的 `MixinConfigs`） |
| 元数据 | `pack.mcmeta` 用 `pack_format 15`；`depends` 声明 `fabricloader / fabric-api / minecraft ~1.20.1 / java >=17 / watermedia >=2.1.24 <2.1.38` |
| 编译期中介名 | Fabric 运行时把游戏映射成 intermediary，`ClientTickEvents.EndTick` 的参数类型叫 `net.minecraft.class_310`。本机没有 Loom 生成的 intermediary MC jar，于是加了 `tools/fabric-stubs/net/minecraft/class_310.java`（空类），只编进 `build/stubs/<target>` 并挂上 classpath，**不会**进 jar |
| Fabric API 的模块 | 发行包把模块藏在 `META-INF/jars/`（javac 看不见），`tools/download-deps.ps1` 解出 53 个嵌套模块到 `vendor/downloads/fabric-api-modules/`，构建时用通配符整体挂上 |
| tick 兜底 | 在 `client` 入口点注册；`try/catch` 保护，API 缺失时只告警、不影响其它功能 |

harness 对 Fabric 目标的核对（`checks = 64 / 65`）：`fabric.mod.json` 的 `environment`、
`preLaunch`/`client` 入口点、`mixins` 字段、依赖声明，以及入口类**只**引用 Fabric 类型
（不得出现 `net/neoforged`、`net/minecraftforge` 的 `@Mod`）。

### 13.7 Fabric 目标的前置下界：WATERMeDIA 2.1.24

Fabric 版把依赖放宽到 **2.1.24 – 2.1.37**（Forge / NeoForge 保持 2.1.36+）。这不是随手填的区间，
而是逐项核对过的：

| 检查项 | 2.1.24 | 结论 |
|---|---|---|
| `fabric.mod.json`（Fabric 模组身份） | 有（version 2.1.24，entrypoint `FavricLoader`） | ✓ 同一个 jar 就是 Fabric 模组 |
| `RenderAPI.uploadBuffer(ByteBuffer;IIIIZ)V` | 描述符与 2.1.36 逐字相同 | ✓ 白屏修复的注入点有效 |
| `VideoPlayer.display(MediaPlayer;[Ljava/nio/ByteBuffer;BufferFormat)V`、`<init>(MediaPlayerFactory;Executor)V` | 逐字相同 | ✓ 视频链路埋点有效 |
| videolan4j 遮罩包名 | 同为 `org.watermedia.videolan4j`（487 个 class） | ✓ |
| 发现链（`IProvider`、`PlayerAPI$ConfigProvider`、`NativeDiscovery.start()`、`registerFactory`、`WaterMedia.asResource`） | 全部存在 | ✓ 载荷挂接方式不变 |
| **原生分配** | **只有 `createByteBuffer(int)`，走 `MemoryUtil.getAllocator(false).malloc(size)`，返回 0 就抛 `OutOfMemoryError`** | ✗ 需要新的兜底 |

最后一行是关键差异：2.1.36+ 的分配在 `createByteBuffer(int,int)` 里调用 `MemoryUtil.memAlignedAlloc`，
而 2.1.24 – 2.1.35 直接 `malloc` 并在失败时抛 `OutOfMemoryError`（正是 1.0.3 修掉的
“mod loading has failed”形态）。所以 `RenderAPIMixin` 现在有两条注入：

1. `@Redirect` 在 `createByteBuffer(II)` 的 `memAlignedAlloc` 调用点（2.1.36+：失败时退回 direct buffer，成功则保留对齐）；
2. `@Inject(HEAD, cancellable)` 在 `createByteBuffer(I)`，**运行时先判断 2 参重载是否存在**，存在就直接放行
   （不干扰新版本的对齐路径），不存在才按老逻辑 `malloc`、返回 0 时退回 `ByteBuffer.allocateDirect`。

验证是两级证据：

* **编译期**：Fabric target 的 classpath 指向 `vendor/downloads/watermedia-2.1.24.jar` —— 任何只在 2.1.36+
  才有的 API 都会直接编译失败；
* **运行期**：`tools/itest.ps1` 额外跑一轮 `mode=install (fabric classes with WATERMeDIA 2.1.24)`，
  用最老版本把桥接自身的代码路径（配置、载荷、发现钩子、`ConfigProvider`）走一遍。

两轮都通过后才写下 `">=2.1.24 <2.1.38"`。

### 13.8 同一个 loader 的两个 Minecraft 版本（1.20.1 / 1.21.1 Fabric）

Fabric 版现在同时提供给 1.20.1 与 1.21.1。**入口类与 mixin 完全复用**（它们不碰 Minecraft 类），
差异只有两处：`fabric.mod.json` 的 `minecraft` 依赖与 `pack.mcmeta` 的 `pack_format`（1.20.1 = 15，1.21.1 = 34）。
因此资源文件里写占位符，由构建脚本按目标展开：

```
fabric.mod.json   "version": "${modVersion}",  "minecraft": "~${mcVersion}"
pack.mcmeta       "pack_format": ${packFormat}
```

（`tools/../build.ps1` 里的 `Copy-Resources` 只对文本资源展开 `${...}`，二进制载荷原样复制；若展开后仍残留占位符会直接构建失败。）
编译桩 `net.minecraft.class_310` 两个版本都能用 —— intermediary 名对已有类是稳定的（已用 `intermediary-1.21.1.jar`
的 `mappings.tiny` 核对），而且 Fabric API 的 `ClientTickEvents.EndTick.onEndTick` 在 1.21.1 里参数类型仍是 `class_310`。

四个目标的校验（`tools/itest.ps1`，共 10 轮）：

| 轮次 | 结果 |
|---|---|
| forge1201 编译产物 / 打包 jar | 61 / 62，0 失败 |
| fabric1201 编译产物 / 打包 jar | 64 / 65，0 失败 |
| fabric1211 编译产物 / 打包 jar | 64 / 65，0 失败 |
| neoforge1211 编译产物 / 打包 jar | 62 / 63，0 失败 |
| 真实发现链（discover） | 8，0 失败 |
| fabric 编译产物 + WATERMeDIA 2.1.24（运行期下界） | 64，0 失败 |

## 14. 真机复测记录：视频修复生效 + 一个会「点名」本模组的第三方 JPMS 冲突

### 14.1 1.0.5 的视频链路在真机上成立

FCL / Android 16 / aarch64 / NeoForge 21.1.248 / WATERMeDIA 2.1.37，装 `+mc1.21.1-neoforge`：

```
18:17:42  [WATERMeDIA: Android Bridge/] WATERMeDIA: Android Bridge / 1.0.5 - Linux/aarch64
18:17:42  [WATERMeDIA: Android Bridge/] using /data/user/0/com.tungsten.fcl/files/watermedia_android_bridge/arm64-v8a
18:17:42  [mixin/] Mixing RenderAPIMixin from watermedia_android_bridge.mixins.json into org.watermedia.api.render.RenderAPI
18:17:42  [mixin/] …RenderAPIMixin…->@Inject::bridge$uploadBuffer(Ljava/nio/ByteBuffer;IIIIZ…)V does use it's CallbackInfo
18:17:42  [VideoLan4J/NativeDiscovery] Successfully loaded VLC 3.0.23 Vetinari in '…/arm64-v8a'
18:18:21  [WATERMeDIA: Android Bridge/] video player #1 created; waiting for the first decoded frame
18:18:21  [WATERMeDIA: Android Bridge/] first video frame from VLC: 1920x1090, 1 plane(s), chroma RGBA
18:18:21  [WATERMeDIA: Android Bridge/] video texture upload works: texture 59 <- 1920x1090
          (8371200 bytes, fresh storage, pixel type GL_UNSIGNED_BYTE …)
18:18:51  [WATERMeDIA: Android Bridge/] video: 632 frame(s), 632 texture upload(s), 0 GL error(s), 0 upload failure(s)
18:19:21  [WATERMeDIA: Android Bridge/] video: 1642 frame(s), 1641 texture upload(s), 0 GL error(s), 0 upload failure(s)
```

一分钟内 1642 帧、1641 次上传、**0 个 GL 错误**：`GL_UNSIGNED_BYTE` 替换 + vmem/MediaCodec 参数确实修掉了白屏。
（帧数比上传数多 1 是首帧回调早于 `buffers` 赋值时 WATERMeDIA 自己跳过的，属预期。）

### 14.2 崩溃「点名」本模组，但根因在第三方模组

另一次启动在模组构造之前就死了：

```
[main/ERROR]: Error while resolving modules.
java.lang.module.ResolutionException: Modules rinku and mcef export package org.cef.misc to module watermedia_android_bridge
```

读法是关键：**消息里第三个模块是「读取方」，不是元凶**。JDK 的
`jdk.internal.module.Resolver#checkExportSuppliers`（用 `javap -c -p java.lang.module.Resolver` 核对过字节码：
先 `ModuleDescriptor.isAutomatic()` 跳过自动模块作为提供方，再遍历 `exports()` / `isQualified()` / `targets()`，
最后抛 `Modules %s and %s export package %s to module %s`）要求「同一个包只能有一个提供方」。
`rinku` 与 `mcef` 两个 jar 都带 `module-info`（显式模块）且都导出 `org.cef.misc`；本模组是无 `module-info`
的**自动模块**（自动读取全部模块），于是成为解析器遍历到的第一个「同时读这两个模块」的受害者，被写进报错。

与 14.1 的对照就是证据：同一次会话（Rinku 尚未进入 mod 集合）能正常启动，说明冲突来自后来加入的
Rinku/MCEF 组合；把本模组的 jar 移走只会让报错换一个模块名，游戏仍然起不来。

**处理**（都在用户侧）：`mods/` 里只保留一份 Rinku —— MCEF 自带内嵌的
`de.keksuccino.rinku-…-mod.jar`（父 jar `rinku_neoforge_…jar`），删掉重复的那份即可；或者移除 MCEF。
真机验证方法：临时移走本模组的 jar，看报错里的模块名是否换成了别的模组。
