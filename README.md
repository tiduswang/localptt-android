# 小伙伴对讲机（LocalPTT）🐻🎤

> 纯本地、无外网、无需手动输入 IP 的 Android 局域网对讲应用：
> **实时语音 / 实时视频 / 微聊 / 联机小游戏**。
> 两台手机连**同一个路由器**能互通；**没有路由器**也能用 Wi-Fi Direct 直连。
> 专为小朋友设计 —— 糖果色大图标 + 10 个卡通头像 + 一键互联。

[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://www.android.com/)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-24_(Android_7.0)-orange.svg)](app/build.gradle.kts)
[![Build](https://github.com/tiduswang/localptt-android/actions/workflows/build.yml/badge.svg)](https://github.com/tiduswang/localptt-android/actions/workflows/build.yml)
[![Version](https://img.shields.io/badge/version-1.3.6-brightgreen.svg)](CHANGELOG.md)

## ✨ 功能特性

| 功能 | 说明 |
|---|---|
| 🎤 **实时语音对讲** | 半双工 PTT；免提（🔊大喇叭）/ 听筒（📞小听筒）一键切换；**🎧 听对方：开/关**随时静音；采集 MIC 优先 + 增益，播放放大 |
| 📹 **实时视频对讲** | Camera2 + MediaCodec H.264 → RTP/UDP；进页自动接通，优先前置摄像头，叠加免提语音 |
| 💬 **微聊** | 表情 / 文字 / 照片 / 语音消息，可靠送达；语音消息由发送方本机 HTTP 服务提供 |
| 🎮 **联机小游戏** | 猜拳、掷骰子比大小；**commit-reveal（SHA-256 承诺）防作弊**，双方各自独立裁定 |
| 🌐 **同一路由互联（LAN）** | 两台手机连同一个 Wi-Fi / 手机热点：UDP 广播 + mDNS 自动发现，点一下即进房间，无需输入 IP |
| 📡 **无路由直连（Wi-Fi Direct）** | 没有路由器也能连：开房方自动建 P2P 组（组长 `192.168.49.1`），对端在「找小伙伴」里点 `PTT-昵称` 加入 |
| 🔗 **零配置自动组网** | 「找小伙伴」同时合并三类来源：UDP 广播 / mDNS / Wi-Fi Direct 设备；连过的房间会自动回连 |
| 📶 **Wi-Fi 门禁** | 没开 Wi-Fi 会提示并引导打开（Android 9- 直接自动打开，Android 10+ 拉系统面板），开好后**自动继续**刚才那一步 |
| 👧 **儿童友好 UI** | 大 emoji 图标 + 糖果色卡片，功能一看就懂 |
| 🐼 **头像 + 昵称** | 10 个矢量卡通头像 + 自定义昵称，随在线名单同步 |
| 🔄 **功能同步（同进同出）** | 一台进入/退出某功能，房间内其他设备同步进入/退出 |

---

## 🚀 快速开始

### 1. 准备环境
- **Android Studio**（或仅用 Gradle 8.5 包装器）+ JDK 17
- Android SDK：`build-tools` 与 `platforms;android-34`
- 本机 `local.properties` 写入 `sdk.dir=/path/to/Android/Sdk`（该文件已被 `.gitignore`，不会提交）

### 2. 构建
```bash
./gradlew assembleDebug        # Linux / macOS
gradlew.bat assembleDebug      # Windows
```
> 仓库已自带 `gradlew` / `gradlew.bat` / `gradle/wrapper/`，首次运行会自动下载 Gradle 8.5，无需本地预装。
> 产物：`app/build/outputs/apk/debug/app-debug.apk`（约 10 MB，包名 `com.example.localptt`）。

不想自己编译？直接下载现成的安装包：
**[localptt-1.3.6-debug.apk](https://github.com/tiduswang/localptt-android/releases/download/v1.3.6/localptt-1.3.6-debug.apk)**（debug 签名，仅用于测试）。

### 3. 真机运行与验收
语音对讲 / 视频 / Wi-Fi Direct 需**真机**（模拟器不支持 P2P 与摄像头）。
建议准备 **2 台及以上** Android 7.0+ 手机。

**方式 A：同一个路由器 / 热点（最简单）**
1. 两台手机连到同一个 Wi-Fi；
2. 一台点「🏠 开个房间」，另一台点「🔍 找小伙伴」，房间里会出现「🏠 小伙伴 的房间」，点一下即进；
3. 状态卡下会出现 `🌐 同一个 WiFi` 小胶囊，表示走的是路由器链路。

**方式 B：没有路由器，两台手机直连**
1. 两台手机各自把 **Wi-Fi 开关打开**（不用连任何网络），并在首次使用时允许「附近的设备 / 定位」权限；
2. 一台点「🏠 开个房间」，App 会自动建 Wi-Fi Direct 组（状态提示会显示组长地址 `192.168.49.1`）；
3. 另一台点「🔍 找小伙伴」，在列表里点 `PTT-昵称` 的设备，等待 3~8 秒自动协商并进房；
4. 连上后状态卡下显示 `📡 WiFi 直连`。

> 少数机型要求「定位服务（GPS 总开关）」打开才能扫描到 Wi-Fi Direct 设备；
> 如果列表里一直看不到对端，先确认这一点。Android 13+ 用「附近的设备」权限，不再需要定位。

**验收清单**（详见 `CHANGELOG.md` 与 `设计与开发文档.md`）：

- 同房间 1 秒内两端均显示「2 人在线」；
- 语音页「🔊大喇叭」对方能清晰听到；
- 微聊表情 / 照片 / 语音消息对端能收到；
- 视频进页自动接通、双方画面出现；
- 游戏双方出招后显示最终胜负；
- 一台进入某功能、另一台同步进入；一台退出、另一台同步退出；
- 关掉路由器（或两台手机都不连 Wi-Fi）后按「方式 B」仍能互通。

---

## 🧭 架构总览（v1.1.0 起）

参考主流 Wi-Fi Direct / 局域网通信开源实现（分层 + 单向数据流）落地：

```
UI 层（Activity）        ui/MainActivity、Voice/Video/Chat/Game/Profile
    │  只订阅状态、只发指令 —— 不持有任何 socket
    ▼
房间状态机（进程级）      room/RoomManager  ← state / status / peers（StateFlow）
    │  互斥 + 硬超时 + generation 作废 + 资源 own/lifecycle
    ▼
传输与发现（net）         ControlServer/ControlClient  UdpBus  RtpSocket
                          HostBeacon/DiscoveryListener（UDP+mDNS）  WifiDirectManager
    ▼
业务模块                  audio/AudioEngine  video/VideoCallSession
                          chat/ChatManager  game/GameManager
```

**为什么关键的一步是加 `RoomManager`**：v1.0.x 的「忙碌」是 UI 里一个手写布尔量，
只有在网络回调里才会复位。一旦某回调不来（最常见的就是 TCP 建连无超时），
界面就永久停在「正在处理中」。现在是显式状态机，并配以下三条硬约束：

| 机制 | 作用 |
|---|---|
| `withTimeout`（开房 25s / 加入 15s / P2P 建组 14s） | 任何中间态**必定**在超时前结束，不存在永久等待 |
| `generation` 版本号 | 用户点「重新开始」后，所有在途回调自动作废，不会把界面改回旧状态 |
| 资源先局部变量、确认未被打断才挂到 `AppBus` | 被中断时不会留下没人能关的孤儿 socket/port |

配套约定：
- 信令分发协程属于**进程**（不是页面），旋转/回收 Activity 都不会丢消息；
- **有路由器局域网 → 走 LAN**（UDP 广播 + mDNS）；**没有局域网 → 自动 `createGroup` 建 Wi-Fi Direct 组**
  （建组前先 `removeGroup()` 清残留，否则系统会一直返回 `BUSY`）；
- 互联方式由 `RoomManager.ConnectMode` 统一表达（`NONE / LAN / DIRECT`），首页状态卡实时显示；
- 首页只在点「开个房间 / 找小伙伴」时申请**「附近的设备 / 定位」**权限（Wi-Fi Direct 必需），
  拒绝也不影响同一路由器那条路；麦克风与相机仍分别由语音页、视频页按需索取；
- 直连模式加入时 `joinRoom(viaP2p = true)`：收尾时**不会**拆掉刚协商好的 P2P 组。

> 🆘 界面万一还卡住：首页点「🚪 重新开始」即可强制作废全部在途操作回到可用状态。

---

## 📡 端口与协议约定

| 用途 | 协议 | 端口 |
|---|---|---|
| 信令（在线表 / 聊天 / 游戏 / 视频邀请 / 功能同步 / 心跳） | TCP + JSON | 8888 |
| 实时语音（PTT 广播） | UDP | 5004 |
| 实时视频（RTP，1 对 1，收发同端口） | UDP | 5006 |
| 语音消息文件下载（HTTP，主机与客户端各自起服务） | HTTP | 8899 |
| 自动发现 beacon | UDP 广播 | 8890 |

- 信令消息模型见 `app/src/main/java/com/example/localptt/data/Protocol.kt`
  （`Hello` / `Welcome` / `Ping` / `Pong` / `PeerList` / `ChatMsg` / `VoiceMeta` /
  `VideoCall` / `GameInvite` / `GameCommit` / `GameMove` / `GameResult` / `Bye` / `FeatureSync`）。
- 媒体面（语音 UDP、视频 RTP）不走 TCP 信令，由 `PeerList` 中的对端 IP 直接收发。

---

## 🗂️ 项目结构

```
Android对讲机应用/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/                      # 可选：NDK Opus 包装（默认关闭，AMR 兜底）
│       │   ├── CMakeLists.txt  fetch_opus.sh  opus_wrapper.c
│       ├── java/com/example/localptt/
│       │   ├── App.kt                # Application：全局异常兜底 + 初始化 myId
│       │   ├── AppBus.kt             # 进程内共享总线（含 featureEvents 功能同步）
│       │   ├── Profile.kt            # 昵称/头像持久化
│       │   ├── data/   Protocol.kt  Avatars.kt
│       │   ├── net/    ControlServer  ControlClient  UdpBus  RtpSocket  WifiGate
│       │   │          Discovery(HostBeacon/DiscoveryListener)  NetGuard  WifiP2pManager
│       │   ├── room/   RoomManager  RoomState      # 房间生命周期状态机（唯一权威）
│       │   ├── audio/  AudioEngine  AudioCodec  OpusCodec
│       │   ├── video/  VideoCallSession
│       │   ├── chat/   ChatManager  VoiceFileServer
│       │   ├── game/   GameManager
│       │   └── ui/     MainActivity  VoiceActivity  VideoActivity
│       │              ChatActivity  GameActivity  ProfileActivity  PeerAdapter
│       └── res/    # 糖果色 drawable（27 个 shape + 10 个头像 + ic_app）、布局、主题
├── gradle/wrapper/             # Gradle 包装器（已提交）
├── .github/workflows/build.yml # CI：自动构建 debug APK
├── README.md 设计与开发文档.md CHANGELOG.md CONTRIBUTING.md LICENSE
└── .gitignore
```

更完整的网络架构、模块划分、通信协议、关键代码索引与落地风险，见
[**设计与开发文档.md**](设计与开发文档.md)。

---

## 🔧 启用 Opus（可选，需 NDK）

默认语音走 **AMR-NB（MediaCodec 软件编解码）**，无需 NDK 即可运行。
如需更低延迟、更强抗丢包，可启用 Opus：

```bash
# 1. 安装 NDK 与 CMake
sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"

# 2. 拉取 libopus 源码
cd app/src/main/cpp && bash fetch_opus.sh

# 3. 在 app/build.gradle.kts 的 android{} 中加入：
#    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

# 4. 取消 OpusCodec.kt 中 System.loadLibrary("localptt_opus") 注释，
#    并把 AudioEngine(useOpus = true)
```

---

## 🛡️ 稳定性与已知限制

- 防闪退：端口真正释放、`NetGuard` 协程异常护栏、`WifiDirectManager` 空安全降级、
  `App.kt` 全局兜底、lateinit 改 Application 初始化。详见 `设计与开发文档.md` §稳定性。
- 已知限制：视频未做 RTP 分片（FU-A），单帧 > ~64KB 弱网易丢包 → 建议换 WebRTC；
  无 NAT / 公网穿透（设计即本地局域网）；NDK Opus 为可选增强。
- **Android 10（API 29）起系统禁止第三方 App 开关 Wi-Fi**：Android 9 及以下会自动打开，
  Android 10+ 只能拉系统 Wi-Fi 面板由用户点一下（打开后会自动继续刚才那一步）。
- **Wi-Fi Direct 相关限制**：
  - Android 13+ 需要「附近的设备（NEARBY_WIFI_DEVICES）」；Android 12 及以下需要「精确定位」，
    且部分机型必须打开系统**定位总开关**才能扫描到对端；
  - 两台手机都要打开 Wi-Fi 开关（不必连路由器），否则建组会失败；
  - 少数定制 ROM 会把 P2P 与热点互斥，开热点时请改用「同一个热点」方式互联；
  - 协商后如果自己成了组长而不是房间主机，App 会明确提示「直连没谈成」，不会白等超时。

### 排障速查

| 现象 | 先看这里 |
|---|---|
| 「找小伙伴」列表一直空 | 两台是否同一个 Wi-Fi（AP 隔离/访客网络会拦广播）；直连方式下对端是否已开房、权限与定位是否允许 |
| 点直连设备后提示「连不上」 | 对端是否仍在开房状态；先在对端点「🚪 重新开始」再点「🏠 开个房间」 |
| 连上了但听不到声音 | 状态卡下胶囊是否显示正确的互联方式；双方是否都允许了麦克风权限 |
| 频道的端口冲突 | 语音/视频/信令端口都会自动顺延，端口号随信令同步，无需手动处理 |

---

## 📄 文档导航

- [设计与开发文档.md](设计与开发文档.md) —— 技术选型、网络架构、协议、实现步骤、关键代码索引
- [CHANGELOG.md](CHANGELOG.md) —— 版本变更与 Bug 修复记录
- [CONTRIBUTING.md](CONTRIBUTING.md) —— 贡献与本地构建指南
- [LICENSE](LICENSE) —— MIT License

---

## 📜 许可证

[MIT](LICENSE) © 2026 tiduswang
