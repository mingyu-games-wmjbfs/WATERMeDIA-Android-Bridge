# WATERMeDIA: Android Bridge

[![License](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20%7C%201.21.1-3fb950.svg)](#适用范围)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.5-dbb69c.svg)](#适用范围)
[![Forge](https://img.shields.io/badge/Forge-47.x-e8942a.svg)](#适用范围)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.235%2B-e8942a.svg)](#适用范围)
[![WATERMeDIA](https://img.shields.io/badge/WATERMeDIA-2.1.36%20~%202.1.37-8b5cf6.svg)](#适用范围)
[![Version](https://img.shields.io/badge/version-1.0.5%20%C2%B7%201.21.1%20Fabric%20%2F%201.21.1%20NeoForge%20%2F%201.20.1%20Fabric%20%2F%201.20.1%20Forge-lightgrey.svg)](https://github.com/mingyu-games-wmjbfs/WATERMeDIA-Android-Bridge/releases)

**1.0.5 覆盖四个版本**：`1.21.1 Fabric` · `1.21.1 NeoForge` · `1.20.1 Fabric` · `1.20.1 Forge` —— 四个 jar 的功能代码完全相同，按环境择一。

[English](README.en.md) · **简体中文**

给 **WATERMeDIA**（以及基于它的 **WATERFrAMES** 等模组）补上 Android 端缺失的 VLC，
让视频屏幕、方块音乐在 PojavLauncher / FCL 等安卓 Java 版启动器上真正能播。

> **English TL;DR** — WATERMeDIA plays media through a system VLC install; Android has none, so its player
> module fails to load and the game may even crash. This client-side add-on bundles the official VLC for
> Android binaries (libvlc 3.0.23, arm64-v8a / armeabi-v7a / x86_64), extracts them into app-internal
> storage and wires them into WATERMeDIA's own native-discovery hook. No WATERMeDIA file is modified.
> Requires Minecraft 1.20.1 + Forge 47.x **or** Minecraft 1.21.1 + NeoForge 21.1.235+, plus WATERMeDIA
> 2.1.36/2.1.37. Licensed GPL-3.0-or-later.

---

## 它解决什么问题

WATERMeDIA 2.1.x 用 VLC 解码音视频，但它的原生库分发**只覆盖桌面平台**：

```java
// WATERMeDIA 2.1.36 / 2.1.37  PlayerAPI.java
private final boolean wrapped = Platform.isWindows() && Platform.is64Bit();
...
} else {
    LOGGER.warn(IT, "[NOT A BUG] {} doesn't contains VLC binaries for your OS and ARCH, "
        + "you had to download it manually from 'https://www.videolan.org/vlc/'", WaterMedia.NAME);
}
```

只有 Windows x64 会解包内置 VLC，安卓（`os.name = Linux`）被当成普通 Linux，只能去系统里找 VLC。
安卓既没有系统 VLC，也不存在可供加载的 `libvlc.so`，于是 `NativeDiscovery` 失败、
`PlayerAPI.isReady()` 恒为 `false`——视频屏幕黑屏、音乐不出声，严重时整个游戏加载失败。

本模组把 **官方 VLC for Android 的原生库**打包进来，并接到 WATERMeDIA 官方的原生库发现链上。

---

## 适用范围

| 项目 | 要求 |
|---|---|
| Minecraft | **1.20.1** 或 **1.21.1**（客户端） |
| 模组加载器 | **Forge 47.x**、**Fabric**（loader 0.19.5+，需 Fabric API）或 **NeoForge 21.1.235+**；Fabric 同时提供 1.20.1 与 1.21.1 两个版本 |
| 必需前置 | **WATERMeDIA 2.1.36 / 2.1.37**（Forge、NeoForge）；**2.1.24 – 2.1.37**（Fabric，见下）——都是 VLC / videolan4j 世代 |
| 适用场景 | 安卓 Java 版启动器：PojavLauncher、FCL（Fold Craft Launcher）及其分支 |
| 设备架构 | `arm64-v8a`（绝大多数设备）、`armeabi-v7a`（32 位 JVM）、`x86_64`（模拟器）；不含 `x86` |
| 服务端 | **不需要**（纯客户端；VLC 播放只发生在客户端） |

> ⚠️ **不适用于 WATERMeDIA 3.x**。3.0.0 起改用 FFmpeg 后端，既没有 `videolan4j` 也没有本模组依赖的
> 发现钩子；本模组的依赖版本区间已锁在 `[2.1.36, 3.0.0)`，届时加载器会直接提示不满足依赖。
>
> 💡 **两个 jar 功能完全相同**，只是 loader 不同；`watermedia` 前置也是同一个 jar——WATERMeDIA 2.1.37
> 本身就同时支持 1.16.5 / 1.18.2 / 1.19.2 / 1.20.1 / 1.21.1 与 fabric / forge / neoforge。

---

## 安装与使用

1. 确认 `mods/` 里已有 **WATERMeDIA** 的 jar（客户端版本）：Forge / NeoForge 用 2.1.36 或 2.1.37；
   **Fabric 版支持 2.1.24 – 2.1.37**（老版本走的是另一条原生分配路径，桥接已为两代都做了兜底）；
2. 从 [Releases](https://github.com/mingyu-games-wmjbfs/WATERMeDIA-Android-Bridge/releases) 下载**对应你环境的那一个** jar
   （还没发布 Release 时，可按下文「从源码构建」自行打包）：

   | 你的环境 | 下载 |
   |---|---|
   | MC **1.20.1** + Forge 47.x | `watermedia_android_bridge-1.0.5+mc1.20.1-forge.jar` |
   | MC **1.20.1** + Fabric 0.19.5+ | `watermedia_android_bridge-1.0.5+mc1.20.1-fabric.jar`（需要 Fabric API；WATERMeDIA 支持 2.1.24 – 2.1.37） |
| MC **1.21.1** + Fabric 0.19.5+ | `watermedia_android_bridge-1.0.5+mc1.21.1-fabric.jar`（需要 Fabric API；WATERMeDIA 支持 2.1.24 – 2.1.37） |
   | MC **1.21.1** + Fabric 0.19.5+ | `watermedia_android_bridge-1.0.5+mc1.21.1-fabric.jar`（需要 Fabric API） |
   | MC **1.21.1** + NeoForge 21.1.x | `watermedia_android_bridge-1.0.5+mc1.21.1-neoforge.jar` |

   放进同一个 `mods/` 目录（**多个都放没有意义，放错那个会被加载器拒绝并提示依赖不满足**）；
3. 启动游戏。**首次启动**会自动把约 43 MiB（每个 ABI）的原生库解包到**应用内部存储**——
   日志里会打印确切路径；之后启动直接复用（有版本标记，不会重复解包）；
4. 进游戏后用 WATERFrAMES 的屏幕/投影仪等播放任意视频即可。

**WATERFrAMES 无需任何额外补丁**——它通过 WATERMeDIA 的 `PlayerAPI` 取播放器，VLC 一旦可用就自动恢复。

### 怎么确认它工作正常

启动后在 `latest.log` 里应当出现（关键字：`Android Bridge`、`VideoLan4J`）：

```
[WATERMeDIA: Android Bridge/INFO] using /data/user/0/<包名>/files/watermedia_android_bridge/arm64-v8a (payload and VLC's JNI_OnLoad initialised)
[WATERMeDIA: Android Bridge/INFO] pointed WATERMeDIA at the bundled VLC through .../config/watermedia/custom_vlc_path.txt
[VideoLan4J/NativeDiscovery/INFO] Successfully loaded VLC 3.0.23 Vetinari in '...' using 'WaterMedia Config Provider'
[watermedia/Bootstrap/INFO] Module PlayerAPI loaded successfully
[WATERMeDIA: Android Bridge/INFO] registered Android VLC factories: ... --aout=opensles,audiotrack,any
```

看到 `Successfully loaded VLC 3.0.23 Vetinari` 与 `Module PlayerAPI loaded successfully` 就说明 VLC 已就绪。

---

## 配置

首次启动会在 `config/watermedia_android_bridge.properties` 生成配置：

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 关掉则完全不干预，回到 WATERMeDIA 原行为 |
| `abi` | `auto` | 强制使用某个 ABI 载荷（`arm64-v8a` / `armeabi-v7a` / `x86_64`） |
| `installLocation` | `auto` | 解包基目录。`auto` 会挑一个**系统加载器真能加载**的目录；也可填绝对路径 |
| `forceInstall` | `false` | 每次启动都重新解包（排错用） |
| `overrideFactory` | `true` | 是否重注册默认 VLC 工厂（安卓音频输出依赖它） |
| `jnaFallback` | `true` | 允许在启动器未提供 JNA 原生库时使用载荷内的同版本库（默认不随包，见许可章节） |
| `audioOutput` | `opensles,audiotrack,any` | VLC 音频输出模块，按顺序尝试；**无声时可改成 `audiotrack,opensles,any` 或 `any`** |
| `videoOutput` | `vmem` | VLC 视频输出模块。`vmem` 是 WATERMeDIA 回调视频（屏幕里放视频）必需的输出；安卓自带的 `android_display`/`android_window` 需要一个这里并不存在的 Surface，会导致**白屏**。留空表示交给 libvlc 自己决定 |
| `hardwareDecoding` | `none` | VLC 硬解模块列表。默认 `none` 是为了避开安卓 MediaCodec——它要往 Surface 送帧，这里没有 Surface，可能"打开成功却永远不出图"。设备性能强、想省电可改回 `any` |
| `extraVlcArguments` | 空 | 追加的 libvlc 参数，逗号或空格分隔 |

调试用系统属性：`-Dwatermedia.androidbridge.forceAndroid=true`（在非安卓环境强制走安卓流程）、
`-Dwatermedia.androidbridge.gameDir=<路径>`（覆盖游戏目录探测）。

---

## 常见问题

**先看日志**：`latest.log` 搜 `WATERMeDIA: Android Bridge` 与 `VideoLan4J`；
WATERMeDIA 在发现失败时还会打印 VLC 自述日志 `logs/videolan-discovery.log`，那是最有用的一份。

| 症状 | 原因 | 处理 |
|---|---|---|
| `is not accessible for the namespace "clns-NN"` | 载荷位于模拟/外置存储（`/storage/emulated/0`），被安卓链接器命名空间拒绝 | 1.0.1 起自动改用应用内部存储；若仍出现，把 `installLocation` 指向 `/data/user/0/<包名>/files/` 并设 `forceInstall=true` |
| `assertion "s_jvm != NULL" failed` + `OpenJDK exited with code : 6` | VLC 安卓版的 `JNI_OnLoad` 没有被执行（JNA 的裸 `dlopen` 不触发它） | 1.0.2 起已修（主动 `System.load` + 内置 `android.os.Environment` 桩类）。若复现请附完整日志 |
| `watermedia` 加载失败 `java.lang.OutOfMemoryError`（栈里是 `RenderAPI.createByteBuffer` / `memAlignedAlloc`） | LWJGL 的**对齐分配**在该设备上不可用；异常抛在 Mod 构造期，`NeoFLoader` 抓不到 `Error` 就判定加载失败 | 1.0.3 起自动回退到 direct buffer，无需操作。若日志出现 `the direct buffer fallback ... failed too`，说明进程真的缺内存，请调小 `-Xms/-Xmx` |
| 启动时 `SIGSEGV` in `libc.so` (`strtol`)、`Could not find any graphics adapters` | **与本模组无关**：启动器的自定义渲染器插件（例如 `libltw_turbo.so`，报 `undefined symbol`）在 GL 初始化阶段崩溃 | 把启动器渲染器从「自定义/Custom」改回内置渲染器，或更新/移除该渲染器插件 |
| `bilibili_media` 加载失败 `NoClassDefFoundError: me/shedaniel/autoconfig/ConfigData` | 该第三方模组缺 **Cloth Config** 前置 | 安装 Cloth Config 或移除该模组 |
| 有画面没声音 | 设备的 `opensles` 不可用 | 把 `audioOutput` 改为 `audiotrack,opensles,any`，或直接用 `any` |
| 有声音但画面永远是一块白色（视频不动） | 1.0.4 及更早：WATERMeDIA 用桌面专用的 `GL_UNSIGNED_INT_8_8_8_8_REV` 上传视频帧，OpenGL ES 拒绝该类型，纹理始终没有数据 | 1.0.5 起改为通用的 `GL_UNSIGNED_BYTE`（字节序等价）并加兜底路径，同时强制 `--vout=vmem`、默认关掉 MediaCodec 硬解。若仍白屏，日志会指明是 libvlc 没出帧（只有 `video player #N created`）还是渲染侧问题（有 `video texture upload works`） |
| 1.20.1 Forge 上加载器提示缺少依赖 / 模组不加载 | 装错了 jar（loader 不匹配） | MC 1.20.1 + Forge 47.x 用 `+mc1.20.1-forge`，MC 1.20.1 + Fabric 用 `+mc1.20.1-fabric`，MC 1.21.1 + NeoForge 用 `+mc1.21.1-neoforge`；同时放多个没有意义 |
| 启动即崩：`Error while resolving modules` + `ResolutionException: Modules rinku and mcef export package org.cef.misc to module watermedia_android_bridge` | **与本模组无关**：MCEF 与 Rinku 两个 jar 都是带 `module-info` 的显式 JPMS 模块且都导出 `org.cef.misc`，Java 模块解析直接失败。报错里第三个模块名（这里恰好是本模组）只是「读取方」，不是元凶 | `mods/` 里只保留一份 Rinku（MCEF 自带内嵌的 `de.keksuccino.rinku-…-mod.jar`，删掉独立的那份），或移除 MCEF。验证：临时移走本模组的 jar，报错只会换成别的模块名，游戏依旧起不来 |
| Fabric 上提示缺少 `fabric-api`，或启动后视频仍然不可用 | Fabric 版依赖 **Fabric API**（`fabric-api` 为必需），并且用 `preLaunch` 入口点在 WATERMeDIA 之前解包 VLC | 装 Fabric API 0.92.x；若 VLC 没被加载，看日志里是否有 `WATERMeDIA: Android Bridge … using /data/user/0/…` 与 `Successfully loaded VLC` |
| 首次启动很慢 | 正在解包约 43 MiB 原生库 | 正常，仅首次；之后命中版本标记直接复用 |

---

## 工作原理（简述）

不改 WATERMeDIA 的任何文件，只用它自己的公开扩展点：

1. **内置并解包**：把官方 VLC for Android 的 `libvlc.so`（单体构建，libvlc **3.0.23 Vetinari**）与它唯一的
   外部依赖 `libc++_shared.so` 解包到应用内部目录，并补齐 videolan4j 要求的目录契约
   （`libvlc.so` + `libvlccore.so` 别名 + 可读的 `plugins/`，单体构建不从这里加载模块）。
2. **位置探测**：逐个候选目录真实执行 `System.load`，只有系统加载器接受的位置才会被采用
   （安卓禁止从外置存储加载原生库，光看文件权限是看不出来的）。
3. **接通发现链**：写入 WATERMeDIA 官方读取的 `config/watermedia/custom_vlc_path.txt`，
   另外注册一个同优先级的 `IProvider` 作为兜底。
4. **触发 `JNI_OnLoad`**：主动 `System.load(libvlc.so)`。VLC 安卓版只在 `JNI_OnLoad` 里记录 JavaVM，
   而 JNA 的 `dlopen` 不会触发它——少了这一步 `libvlc_new` 会直接 `abort` 整个 JVM。
5. **替换默认参数**：用 `PlayerAPI.registerFactory` 把音频输出固定为 `--aout=opensles,audiotrack,any`
   （`audiotrack` 需要 VLC 安卓 Java 层，`opensles` 是纯原生，优先它并以 `any` 收尾）。
6. **可选兜底（Mixin）**：拦截 `RenderAPI.createByteBuffer` 里那次 LWJGL 对齐分配，失败时回退到
   WATERMeDIA 自带的 direct buffer 分支，避免启动期因一个 156 KB 的分配失败而整个 Mod 加载失败。

完整的源码级分析、逐版本的真机排障记录与验证清单见 [`docs/TECHNICAL.md`](docs/TECHNICAL.md)。

---

## 已知限制与验证状态

* **已在真机验证**（FCL / Android 16 / aarch64 / NeoForge 21.1.248）：VLC 3.0.23 加载成功、
  `PlayerAPI` 模块加载成功、Android 音频参数生效、游戏完整启动并进入主世界。
* **仍需你在自己的设备上确认播放效果**：画面是否正常出画、声音是否正常（不同设备/启动器的
  音频后端差异较大）。有问题请附 `latest.log` 提 Issue。
* 安卓上走的是 VLC 的软件解码，4K/高码率的表现取决于设备性能。
* 仅支持 WATERMeDIA **2.1.x**（VLC 世代）；`x86`（32 位 x86）没有内置载荷。
* 本模组与 WATERMeDIA 官方无关，属第三方兼容补丁。

---

## 从源码构建

需要 JDK 17+（打包不需要 Gradle，也不需要联网构建；首次准备依赖需要网络）。

```powershell
# 1) 下载 WATERMeDIA 源码/二进制、VLC 安卓 APK、许可全文（需要网络）
tools\download-deps.ps1
tools\download-vlc-apk.ps1
tools\download-wm37.ps1
tools\fetch-license-texts.ps1

# 2) 从官方 APK 抽出原生库到 src/main/resources，并生成载荷清单
tools\pack-payload.ps1

# 3) 用 javac + jar 编译打包
#    -Target forge1201（默认）= MC 1.20.1 / Forge 47.4.10
#    -Target fabric1201       = MC 1.20.1 / Fabric（loader 0.19.5 + Fabric API 0.92.x）
#    -Target fabric1211       = MC 1.21.1 / Fabric（loader 0.19.5 + Fabric API 0.116.x）
#    -Target neoforge1211     = MC 1.21.1 / NeoForge 21.1.x
#    -Target all              = 四个都打，并生成源码包
#    构建前会校验每个源文件的 SPDX 许可头
tools\build.ps1 -Target all

# 4) 集成检查（四个 target 各跑一遍编译产物与打包 jar，外加真实发现链）
tools\itest.ps1
```

* `tools/build.ps1` 默认从本机 PCL2 的库目录读取 Fabric / Forge / NeoForge / Mixin / JNA / Log4j 等
  编译期依赖，换机器改脚本顶部的 `$mc` 即可；Forge 47.4.10 的三个 jar（`forge-…-universal`、
  `javafmllanguage`、`mergetool`）体积很小，放在 `vendor/downloads/`（`tools/download-deps.ps1` 会取）。
* **Fabric 版刻意用最老的受支持版本编译**（`vendor/downloads/watermedia-2.1.24.jar`），这样任何只在 2.1.36+ 才有的
  API 都会在编译期暴露；集成检查里还会用 2.1.24 跑一遍运行期校验。
* **Fabric target 需要两样额外东西**：`fabric-loader-0.19.5.jar`（本机库目录里有）与 Fabric API 的
  **嵌套模块 jar** —— Fabric API 的发行包把模块藏在 `META-INF/jars/` 里，javac 看不见，所以
  `tools/download-deps.ps1` 会把它们解到 `vendor/downloads/fabric-api-modules/`（1.21.1 用 `fabric-api-modules-1.21.1/`）；另外
  `tools/fabric-stubs/` 里有一个**只用于编译**的中介名桩类（`net.minecraft.class_310`，即客户端类），
  它只会进编译 classpath，**不会**被打进 jar。
* `src/main/resources/watermedia_android/natives/**`（约 127 MiB 的 `.so`）由 `tools/pack-payload.ps1`
  从官方 APK 生成，**不需要提交进版本库**（三个 target 共用同一份载荷）。
* 源码布局：`src/main/java`（12 个与 loader 无关的类，含 `android/os/Environment` 桩、两个 mixin、
  `VideoUpload`/`VideoDiagnostics`）
  + `src/loader/forge` 与 `src/loader/neoforge`（各自的入口类与 loader 元数据/`pack.mcmeta`）
  + `src/main/resources`（mixin 配置、服务声明、`META-INF/licenses/`、载荷清单）。

---

## 下载与校验

从 [Releases](https://github.com/mingyu-games-wmjbfs/WATERMeDIA-Android-Bridge/releases) 下载：

| 文件 | 大小 | SHA-256 |
|---|---|---|
| `watermedia_android_bridge-1.0.5+mc1.20.1-forge.jar` | 59.17 MiB | `EBD3DED9EB421285A618A04FDC2958E86FFC599177E69465CF1DD8E815E7A9B8` |
| `watermedia_android_bridge-1.0.5+mc1.20.1-fabric.jar` | 59.17 MiB | `8D5F9DB8D83CE79B248611FF7555F6C1A18BE0195E4606AC53B58AD4675AAEBD` |
| `watermedia_android_bridge-1.0.5+mc1.21.1-fabric.jar` | 59.17 MiB | `CC3B5083AEB88A2A3027CCEEAA21D79B44EA0B295F5C6C785A9CEABBAA0FFFD7` |
| `watermedia_android_bridge-1.0.5+mc1.21.1-neoforge.jar` | 59.17 MiB | `9C15DEC8BE6A35881CB6F02294337182CEA63C6D49F84E2B12B1ABE0E55C3516` |

四个 jar 的功能代码完全相同，区别只在 loader 入口与元数据：Forge 用 `mods.toml`（`pack_format 15`）+ 清单里的 `MixinConfigs`；
Fabric 用 `fabric.mod.json`（`preLaunch` + `client` 入口点、`mixins` 字段），1.20.1 与 1.21.1 各一份，只差 `minecraft` 依赖与 `pack_format`（15 / 34）；
NeoForge 用 `neoforge.mods.toml` + `[[mixins]]`（`pack_format 34`）。

源码就在本仓库中（`src/`、`tools/`），不再另外附带源码包；按「从源码构建」一节即可自行打包。

---

## 许可与致谢

**本模组采用 [`GPL-3.0-or-later`](LICENSE)。** 这不是偏好，而是「随包分发 VLC 安卓单体构建」直接决定的：
桌面版 VLC 用动态插件把 LGPL 核心与 GPL 模块分开，而安卓版是单体静态链接，因此整包分发落入 GPL 范围。
（上游 `vlc-android` README 原话：*"VLC for Android is licensed under GPLv2 (or later). Android libraries
make this, de facto, a GPLv3 application."*）

| 随包内容 | 许可 | 许可文本 |
|---|---|---|
| `libvlc.so`（VLC for Android 3.7.1，libvlc 3.0.23） | GPL-2.0-or-later（组合作品按 GPL-3.0+ 分发） | `META-INF/licenses/GPL-3.0.txt`、`GPL-2.0.txt` |
| LibVLC 引擎 | LGPL-2.1-or-later | `META-INF/licenses/LGPL-2.1.txt` |
| `libc++_shared.so`（LLVM libc++） | Apache-2.0 with LLVM exception | `META-INF/licenses/Apache-2.0.txt`、`LLVM-exception.txt` |

完整声明见 [`META-INF/licenses/THIRD-PARTY-NOTICES.txt`](src/main/resources/META-INF/licenses/THIRD-PARTY-NOTICES.txt)。

**致谢**

* **VideoLAN 团队** —— libVLC 与 VLC for Android（本模组随包的即其官方二进制）；
* **SrRapero720 / Goedix** —— [WATERMeDIA](https://github.com/WaterMediaTeam/watermedia) 与
  [WATERFrAMES](https://github.com/SrRapero720/WATERFrAMES)（本模组不含其代码，仅运行期依赖）；
* **PojavLauncher / FCL 团队** —— 安卓 Java 版运行环境与 JNA 原生支持；
* 以及把真机日志反馈回来的测试者。

**免责声明**：本模组为第三方非官方补丁，与 Mojang、Microsoft、VideoLAN、WATERMeDIA 作者均无隶属关系；
不含 Minecraft 任何代码或素材；Minecraft 为 Mojang Studios 的商标。

> 本仓库的许可是工程实践层面的梳理，不构成法律意见。
