# 发布说明（Release Notes）

> `tools/upload-github.ps1 -ReleaseTag ...` 会把本文件内容作为 GitHub Release 的说明正文。
> 发新版本时先更新这里。

Android 上让 WATERMeDIA / WATERFrAMES 能用的补丁模组（内置 VLC for Android）。

**1.0.5 覆盖四个版本**：`1.21.1 Fabric` · `1.21.1 NeoForge` · `1.20.1 Fabric` · `1.20.1 Forge`
（四个 jar 的功能代码逐字节相同，按环境择一即可）。

## 适用环境

| 项目 | 要求 |
|---|---|
| Minecraft | **1.20.1** 或 **1.21.1**（客户端） |
| 加载器 | **Forge 47.x**、**Fabric**（loader 0.19.5+）或 **NeoForge 21.1.235+** |
| 必需前置 | WATERMeDIA **2.1.36 / 2.1.37** |
| 启动器 | PojavLauncher、FCL 等安卓 Java 版启动器 |
| 设备 | arm64-v8a / armeabi-v7a / x86_64（不含 32 位 x86） |

**按你的环境挑附件**（两个 jar 功能完全相同，只是 loader 不同）：

| 你的环境 | 用哪个 jar |
|---|---|
| 1.20.1 + Forge 47.x | `watermedia_android_bridge-1.0.5+mc1.20.1-forge.jar` |
| 1.20.1 + Fabric（loader 0.19.5+，需 Fabric API） | `watermedia_android_bridge-1.0.5+mc1.20.1-fabric.jar` |
| 1.21.1 + Fabric（loader 0.19.5+，需 Fabric API） | `watermedia_android_bridge-1.0.5+mc1.21.1-fabric.jar` |
| 1.21.1 + NeoForge 21.1.x | `watermedia_android_bridge-1.0.5+mc1.21.1-neoforge.jar` |

> 不适用于 WATERMeDIA 3.x（FFmpeg 后端，没有本模组依赖的 VLC 发现钩子）。

## 安装

1. 保持 `mods/` 里有 WATERMeDIA 2.1.36 或 2.1.37；
2. 把本页附件的 jar 放进同一个 `mods/` 目录；
3. 启动游戏。首次启动会把约 43 MiB 原生库解包到**应用内部存储**，之后直接复用。

`latest.log` 里出现下面两行即表示成功：

```
[VideoLan4J/NativeDiscovery/INFO] Successfully loaded VLC 3.0.23 Vetinari in '...' using 'WaterMedia Config Provider'
[watermedia/Bootstrap/INFO] Module PlayerAPI loaded successfully
```

**WATERFrAMES 无需任何额外补丁**——VLC 一旦可用，它的屏幕/投影仪/方块音乐就会自动恢复。

## 本版本变更（1.0.5）

### 新增：Minecraft 1.20.1 的 Forge 与 Fabric 支持

同一套补丁现在有四个版本（`+mc1.20.1-forge`、`+mc1.20.1-fabric`、`+mc1.21.1-fabric`、`+mc1.21.1-neoforge`），
**按你的环境择一**。1.20.1 版不是重写，而是把 loader 相关的那一层换掉：

* **共用全部功能源码**：内置 VLC、内部存储解包、`JNI_OnLoad` 预加载、`android.os.Environment`
  桩类、LWJGL 对齐分配兜底、GLES 安全的视频上传、视频链路埋点——这些只与 WATERMeDIA + LWJGL +
  VLC 打交道，与 MC/loader 版本无关，因此 13 个功能源文件**逐字节相同**；
* **只有入口与元数据不同**：Forge 用 `@Mod` + `TickEvent.ClientTickEvent`（`Dist.CLIENT`）+ `mods.toml`
  （`pack_format 15`、mixin 走 **jar 清单的 `MixinConfigs`**）；Fabric 用 `fabric.mod.json`
  （`preLaunch` + `client` 两个入口点、`mixins` 字段、`pack_format 15`，需 Fabric API）；
  NeoForge 保持原样（`neoforge.mods.toml` + `[[mixins]]` + `pack_format 34`）；
* **Fabric 为什么必须用 `preLaunch`**：Fabric 没有加载顺序声明，而 WATERMeDIA 是本模组的依赖 →
  它会**先**初始化；videolan4j 的 `NativeDiscovery.start()` 是**一次性**的（第一次失败后
  内部 `attempted` 标志会让后续调用直接返回 `false`，永不重试）。所以载荷必须在 WATERMeDIA
  初始化之前（`preLaunch`）就位，否则 Fabric 上 VLC 永远加载不上；
* **WATERMeDIA 依赖不变**：仍是 `[2.1.36,3.0.0)`。WATERMeDIA 2.1.37 的同一个 jar 同时面向
  1.16.5 / 1.18.2 / 1.19.2 / 1.20.1 / 1.21.1 / 1.21.5 与 fabric / forge / neoforge 发布
  （自带 `fabric.mod.json`、`mods.toml`、`neoforge.mods.toml`），内含 `ForgeLoader`、`NeoFLoader`、
  `FabricLoader` 三种实现，且 967 个 class 里**零个 `net/minecraft/` 引用**——所以三个 loader 版本
  用的是同一个前置；
* **游戏目录探测更稳**：依次尝试 Fabric 的 `FabricLoader.getGameDir()`、NeoForge/Forge 的
  `FMLLoader.getGamePath()` 与 `FMLPaths.GAMEDIR.get()`，都失败才退回工作目录并打印告警
  （`-Dwatermedia.androidbridge.gameDir=<路径>` 可强制指定）。

四个 jar 的功能代码**逐字节相同**，区别只在 loader 入口与元数据。
内置 VLC 与 payload 版本（`vlc3.0.23-android3.7.1-r2`）不变，同一版本号内升级**不会重新解包**。

**Fabric 版特别说明**（1.20.1 与 1.21.1 各一份，功能代码相同）：

* 额外要求 Fabric API（`fabric-api`）；两个版本用同一份 `fabric.mod.json` 模板，只差 `minecraft` 依赖与 `pack_format`（15 / 34）；
* **前置 WATERMeDIA 放宽到 2.1.24 – 2.1.37**（Forge / NeoForge 仍是 2.1.36 / 2.1.37）。2.1.24 – 2.1.35
  的原生分配走的是 `MemoryAllocator.malloc()` + 失败即抛 `OutOfMemoryError`（正是 1.0.3 修过的"模组加载失败"形态），
  桥接为此补了一条只在老版本生效的分配兜底：先按原逻辑 malloc，返回 NULL 才退回 direct buffer；
  视频上传的注入点（`uploadBuffer`）在两代里描述符完全一致，白屏修复同样有效。
* Fabric 版**用 2.1.24（最老受支持版本）编译**，确保不会误用新版本才有的 API。

### 修复：视频白屏（音频正常、画面停在第一帧）

修复**「音频正常、视频画面永远停在第一帧（一块纯白）」**。按两条互不相关的原因分别下药，
并把整条视频链路的关键节点写进日志，这样万一还没好，日志本身就能指明卡在哪一步：

1. **OpenGL 像素类型（最可能的元凶）** —— WATERMeDIA 用桌面专用的
   `GL_UNSIGNED_INT_8_8_8_8_REV`(0x8367) 上传视频帧，**OpenGL ES 不接受这个类型**
   （只回一个 `GL_INVALID_ENUM`），于是 `glTexImage2D` 被拒绝、纹理自始至终没有存储，
   Minecraft 采样不完整纹理，画面就是一块纯白而音频照常。桥接现在接管
   `RenderAPI.uploadBuffer`，改用桌面与 GLES 通用的 `GL_UNSIGNED_BYTE`：对 `GL_RGBA`
   而言两者内存字节序完全相同，画面不受影响；另外加入 GL 错误自检、尺寸变化时重新分配、
   以及「先分配空存储再 `glTexSubImage2D`」的兜底路径。这条路径**绝不抛异常**——
   WATERMeDIA 只有在 `uploadBuffer` 正常返回后才释放帧信号量，异常会让 VLC 的视频输出
   线程永久卡死（正是「视频冻住、音频没事」的形态）。
2. **视频输出模块与解码器** —— 新增两个 Android 专属参数：
   `--vout=vmem`（强制 WATERMeDIA 回调视频所需的 vmem 输出，绕开需要 Java Surface 的
   `android_display`/`android_window`）与 `--avcodec-hw=none`（Android 的 MediaCodec
   硬解要往 Surface 送帧，这里没有 Surface，可能"打开成功却永远不出图"）。
   两者都可以在 `config/watermedia_android_bridge.properties` 里改
   （`videoOutput` / `hardwareDecoding`，例如 `hardwareDecoding=any` 可换回硬解省电）。
3. **可诊断性** —— 日志新增视频链路节点：`video player #1 created` →
   `first video frame from VLC: WxH` → `video texture upload works`，附 GL 错误明细与
   每 30 秒一次的心跳统计；玩家创建后 20 秒内一个帧都没到，会明确警告
   「libvlc 没有产出画面」。

**本版没有改动**：内置 VLC 仍是同一份官方 VLC-Android 3.7.1（libvlc 3.0.23），
payload 版本 `vlc3.0.23-android3.7.1-r2` 不变，因此升级不会重新解包（秒开）；
许可与依赖声明不变（`GPL-3.0-or-later`；WATERMeDIA 仍是必需依赖、不打包进本模组）。

### 如果画面还是白的，请这样反馈

把 `latest.log` 发来即可，日志会直接指明位置：

| 日志里看到 | 说明 |
|---|---|
| 只有 `video player #N created`，没有 `first video frame from VLC` | libvlc 侧（视频输出/解码器）没出帧，问题不在 OpenGL |
| 两条都有，还有 `video texture upload works`，屏幕仍白 | 帧和上传都正常，问题在渲染侧（WATERFrAMES/屏幕本身） |
| 出现 `GL error` / `could not upload a video frame` | 上传被驱动拒绝，错误码和尝试过的回退路径都写在同一条日志里 |

## 历史版本（1.0.4 / 1.0.3 及更早）

* 1.0.4：许可合规（`GPL-3.0-or-later`、随包许可全文、SPDX 头、移除不再需要的 JNA 二进制）；
* 1.0.3 及更早：让 VLC 在安卓上真正加载起来——内部存储解包（绕开链接器命名空间）、
  `JNI_OnLoad` 预加载 + `android.os.Environment` 桩类（修 `s_jvm != NULL` 断言）、
  LWJGL 对齐分配兜底（修启动期 `OutOfMemoryError`）。

## 校验与支持

* 附件 SHA-256 与详细说明见仓库 README；
* 遇到问题请在 Issue 里附上 `latest.log`（关键字 `Android Bridge`、`VideoLan4J`），
  以及 `logs/videolan-discovery.log`（如果存在）。
