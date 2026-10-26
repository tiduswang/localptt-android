# 更新日志（Changelog）

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/) 与 Keep a Changelog 风格。

## [1.3.0] - 2026-10-26

**语音对讲参考 Meshenger 的做法重做通话链路；微聊修好「照片发不出去」并补上缓存/去重。**
> 微聊照片需要新旧版本间同一协议，**两台手机都装 1.3.0** 才能互发照片。

### 修复（Fixed）
- **微聊照片过去完全不可用**：旧版只把本机 `content://` 索引塞进消息体，
  对端只显示「📷 [照片]」占位、点了没反应。现在发送前把图片压成 ≤1280px 的 JPEG（质量 80），
  落到本机分享目录，再由本机文件服务（8899）提供下载；接收方拿到后**直接显示缩略图**，
  点一下看大图。
- **Wi-Fi Direct 直连模式下语音/照片下载必然失败**：媒体 URL 过去优先用 `wlan` 地址拼，
  而 p2p 网段（192.168.49.x）的对端根本路由不到。新增 `localIpv4For(peerIp)`：
  优先挑「和当前对端同一 /24」的本机地址，直连用 p2p、局域网用 wlan。
- **媒体缓存与半截文件**：下载改为先写 `xxx.part` 再改名，同名文件已存在则直接复用，
  重复点同一条语音/照片不再重复下载，也不会把下了一半的文件当完整文件播放。
- **微聊消息重复/串页**：气泡适配器按消息 id 去重（`SharedFlow(replay=64)` 回放、
  重复进入页面都不再刷出重复消息）。
- **语音录制手势**：手指滑走（`ACTION_CANCEL`）改为取消录制，不再误发一条空语音。

### 变更（Changed）
- 微聊文件服务按扩展名返回正确的 `Content-Type`（jpg/png/m4a），照片与语音共用 8899。

### 语音对讲（参考 Meshenger / WebRTC 通话链路）
- **麦克风常开，PTT 只控制「发不发包」**：过去每次按下才 `startRecording`，
  启动延迟会把开头一两个字吞掉；现在录音一直在跑，按键即出声。
- **抢音频焦点**：`AUDIOFOCUS_GAIN_TRANSIENT` + `USAGE_VOICE_COMMUNICATION`
  （API 26+ 用 `AudioFocusRequest`，旧机走 legacy 接口），
  避免被音乐/其它 App 抢走焦点后直接没声；页面退出时归还。
- **挂载硬件音频效果**：`AcousticEchoCanceler`（消外放回声）、
  `NoiseSuppressor`（压环境噪声）、`AutomaticGainControl`（提人声），设备支持才挂。
- **低延迟播放通路**：AudioTrack 在 API 26+ 设 `PERFORMANCE_MODE_LOW_LATENCY`。
- 采集循环读失败时退避 20ms，不再空转烧 CPU/电量。

## [1.2.2] - 2026-10-26

**真机反馈的四个问题一起修**：语音听不到对方、视频只能看到自己、语音页不能静音、游戏又卡又慢。
> 注意：本轮改动包含媒体层协议，**两台手机都要装 1.2.2**，与旧版互通会出现「一边说、一边听不到」。

### 修复（Fixed）
- **视频「只能看到自己，看不到对面」的三处根因**：
  1. `RtpSocket` 过去用**两个 socket**：发送用系统随机临时端口的 `DatagramSocket()`，
     接收才绑定 5006。双方收到对端第一包后把「对端的临时发送端口」学成目标端口，
     之后视频流全发到那个**没人监听**的端口 → 双方都收不到后续帧，只剩本地预览。
     现在**收发共用同一个绑定端口的 socket**（源端口 == 监听端口），学习逻辑保留，
     即使 5006 被占用顺延也能自动对齐。
  2. **解码器异步模式违规**：解码器 `setCallback` 后仍调用 `dequeueInputBuffer`，
     会抛 `IllegalStateException` 把接收协程直接打死。改为「回调交出空闲输入缓冲 → 从队列灌数据」。
  3. **参数集（SPS/PPS）首包丢失后永远黑屏**：现在编码器把 csd 缓存下来，
     每 2 秒随关键帧请求一起重发；解码端改为「关键帧前先补喂参数集」，去重后不会重复堆积。
- **语音「听不到对方」的三处根因**：
  1. **编解码器两端不一致**：默认的 AMR-NB 依赖设备侧 MediaCodec，两端支持情况可能不同；
     逐帧 `dequeue` 只有 10ms 超时，经常拿到空帧 → 编码端静音、解码端解出空 PCM。
     现在统一走 **PCM 直通**（8kHz/16bit，约 128kbps，局域网/直连完全够用），
     任何机型行为一致。
  2. **音频路由混搭**：外放时 AudioTrack 用 `USAGE_MEDIA`，但 AudioManager 处于
     `MODE_IN_COMMUNICATION`，部分机型会直接把播放静音。现在音轨固定用
     `USAGE_VOICE_COMMUNICATION` + `MODE_IN_COMMUNICATION`，音量走通话流。
  3. **重复接收循环抢包**：建房间和每次进入语音页都会 `startReceiving()`，
     同一个 socket 上叠加多条 receive 循环持续抢包。现在幂等，只允许一条。
- **游戏「特别卡、延迟特别长」**：
  - 信令发送改为**后台单线程串行队列**：过去在 UI 线程直接 `println` 写 socket，
    对端接收慢 / TCP 窗口满时会把主线程卡住（表现为按钮点了半天没反应）。
  - 主机侧 accept 的连接补上 `tcpNoDelay`（关闭 Nagle）：信令都是几十字节的小包，
    Nagle + 延迟 ACK 会给每条消息叠加几十到上百毫秒。
  - `AppBus.peers` 换成 `CopyOnWriteArrayList`：名单由网络线程替换、UI 线程同时遍历，
    避免并发修改导致界面卡顿。

### 新增（Added）
- **语音页「🎧 听对方：开 / 🔇 听对方：关」按钮**：可随时手动打开/关闭对端声音
  （与已有的 🔊 大喇叭 / 📞 小听筒切换互不影响），状态在页面内保持。

### 变更（Changed）
- 语音发送目标接收缓冲加大到 256KB，密集小包场景下减少系统丢包。

## [1.2.1] - 2026-10-26

**真机反馈的两个问题一起修**：两种模式都「进不去房间」、以及「找小伙伴」列表里混进大量邻居设备。

### 修复（Fixed）
- **Wi-Fi 直连进不去房间（主因）**：`connect()` 的 `onSuccess` 只代表系统受理了请求，
  组还要 3~10 秒才成形；旧代码只 `delay(800)` 就查一次连接信息，查不到便拿
  `192.168.49.1` 去连 TCP —— 此时对端 `p2p0` 还没起来，必然失败。
  新增 `WifiDirectManager.awaitGroupFormed()`：轮询到「组已成形 + 拿到组长地址」再连，
  最多等 20 秒，超时给出可读提示；自己意外成为组长时立即提示并拆组。
- **TCP 建连增加重试**：`ControlClient.connect()` 改为最多 3 次、间隔 0.9 秒，
  每次换新 socket。覆盖「直连刚成组、对端监听还没就绪」这类瞬态失败。
- **连接过程可视化**：状态文案按阶段更新 ——
  `⏳ 正在连接 ip:port` → `📨 已经连上，正在等主机确认` → 成功/失败。
  卡在第一步说明网络层不通（多为路由器 AP 隔离），卡在第二步说明主机侧没回应。
- **失败提示给出下一步动作**：局域网失败会提示「可能路由器开了 AP 隔离，可改用 Wi-Fi 直连」；
  直连失败会提示「确认开房的那台手机还在房间里」。

### 变更（Changed）
- **「找小伙伴」只显示本应用设备**：Wi-Fi Direct 扫描只保留设备名为 `PTT-…` 的对端。
  旧版把附近**所有**支持直连的设备（邻居手机、电视、打印机）都列出来，
  点了只会白等几十秒，这也是「进不去房间」的观感来源之一。
- 列表项文案区分来源：`🌐 同一个 Wi-Fi · 点一下进房间` / `📡 Wi-Fi 直连 · 点一下跳过路由器`；
  空列表时直接给出两种接入步骤。

## [1.2.0] - 2026-10-26

**两种互联方式全部打通**：同一路由器（LAN）与没有路由器（Wi-Fi Direct 直连）都能互联，
并补上 Wi-Fi Direct 一直缺失的运行时权限、组长地址公布与端口同步。

### 新增（Added）
- **`ConnectMode`（`NONE / LAN / DIRECT`）**：`RoomManager` 新增 `mode: StateFlow<ConnectMode>`，
  首页状态卡下方以 `🌐 同一个 WiFi` / `📡 WiFi 直连` 小胶囊展示本次是通过什么链路连上的。
- **Wi-Fi Direct 运行时权限**（`MainActivity`）：
  - Android 13+ 申请 `NEARBY_WIFI_DEVICES`，Android 12 及以下申请 `ACCESS_FINE_LOCATION`；
  - 使用 `ActivityResultContracts.RequestMultiplePermissions`；**只在点开房/找小伙伴时申请**；
  - 拒绝授权不阻塞局域网路径，只影响直连扫描。
- **开房自动建组**：没有路由器局域网（`lanIpv4() == null`）时，`doCreateRoom()` 自动
  `createGroup` 成为组长，并把真实组长地址公布给对端（`ControlServer.advertisedIp`）。
- **设备可见名**：Android 10+ 尝试用 `WifiP2pManager.setDeviceName("PTT-昵称")`（反射调用，
  老 SDK 上不可直接引用），支持的系统里对端在「Wi-Fi 直连」列表能直接认出是谁开的房间。
- **语音端口随信令同步**：`Hello` / `Peer` / `PeerList` 增加 `voicePort`，
  `UdpBus` 用整表替换维护「对端 IP + 实际语音端口」，修复 5004 被占用后对端听不到声音的问题。
- **`WifiGate.awaitWifiOn()`**：门禁改为只等「Wi-Fi 开关打开」，不再要求必须连上路由器。
- **`.github/workflows/build.yml`**：GitHub Actions 自动构建 debug APK（README 徽章对应）。

### 变更（Changed）
- **Wi-Fi 门禁语义**：`ensureWifi()` 由「必须有局域网」改为「只要有 Wi-Fi 开关」；
  `WifiGate.tryEnable()` 同步调整。旧版正是因为在 `NO_LAN` 就直接失败，导致直连永远走不到。
- **`WifiDirectManager`**：
  - `discoverPeers()` 改为**常驻接收器 + 持续刷新**，新增 `stopDiscovery()` 统一注销，不再泄漏 receiver；
  - `discoverAndConnect()` 统一使用兼容构造器（`deviceAddress` + `groupOwnerIntent = 0`，
    让开房方当组长）：新版 Builder 的「强制 2.4GHz」在部分 SDK 上无法直接引用，
    为保证能编译、且频段不是必需项，这里走全版本通用写法。
- **`AppBus.reset()` 不再拆 P2P 组**：改由 `RoomManager.cleanup(skipP2p)` 按模式决定，
  避免客户端刚连上组长就自己把组拆了。
- **超时调整**：开房 15s → **25s**（含建组），加入 15s，P2P 建组 **14s**。
- **首页文案与界面**：提示条区分「Wi-Fi 没打开」与「没连路由器（也能直连）」；
  底部小提示补充直连用法。

### 修复（Fixed）
- **Wi-Fi Direct 一直不可用的组合根因**：
  1. 从未在运行时申请权限 → `createGroup` / `discoverPeers` 直接 `SecurityException`；
  2. 无局域网被 `ensureWifiReady` 一票否决 → 建组路径根本不可达；
  3. 建组后仍对外公布 wlan/127.0.0.1 地址 → 对端 TCP 连不进来；
  4. 直连加入时 `cleanup()` 无条件 `removeGroup()` → 把刚协商好的组拆掉。
- **`IP` 判定拆分为 `lanIpv4()` / `p2pIpv4()` / `wifiIpv4()`**：
  组网模式、对外公布地址、语音文件 URL 各取所需，不再把 4G 地址误当局域网。
- **`UdpBus` 目标表改为 `IP → 端口` 映射**：成员离开/端口变化时整表替换，不再残留幽灵目标。
- **自己意外成为组长时立即提示**：不再拿着 `192.168.49.1` 去连对端并白等超时。
- **构建链修复**：仓库内的 `gradle-wrapper.jar` 不完整（缺 `IDownload` 等类），
  `./gradlew` 直接抛 `NoClassDefFoundError`；已替换为 Gradle 8.5 官方 wrapper，
  CI（`.github/workflows/build.yml`）可正常 `assembleDebug` 并产出 APK。
- **两处 P2P API 编译错误**：`setDeviceName` 与 `WifiP2pConfig.Builder#setGroupOwnerIntent`
  在部分 SDK 上不可直接引用，前者改反射调用、后者改全版本通用的 `WifiP2pConfig()` 写法。

---

## [1.1.1] - 2026-10-26

**忘记开 Wi-Fi 不再只是「转圈后失败」**：新增 Wi-Fi 门禁，没开先提示、能开就自动开。

### 架构（Added）
- **新增 `net/WifiGate`**：Wi-Fi 状态的唯一入口，三态枚举
  `OK / WIFI_OFF / NO_LAN`（区分「开关关着」和「开了但还没拿到 IP」，提示语因此不会牛头不对马嘴）。
  - `tryEnable()`：Android 9（API 28）及以下用 `WifiManager.setWifiEnabled(true)`
    **直接替用户打开**（真正意义上的自动）；
  - `openWifiUi()`：Android 10（API 29）起系统**禁止第三方开关 Wi-Fi**，改为拉
    `Settings.Panel.ACTION_WIFI` 悬浮面板（不用离开本 App 的完整设置页），
    不支持再降级 `ACTION_WIFI_SETTINGS`；
  - `awaitReady()`：限时轮询等待 IP 落地（始终有上限，不会无限等待）。
- **首页橙色 Wi-Fi 提示条**（`activity_main.xml` 的 `wifiTip`）：Wi-Fi 不可用时自动出现，
  带「去打开」按钮；监听 `WIFI_STATE_CHANGED_ACTION` 实时刷新（动态注册、`RECEIVER_NOT_EXPORTED`）。

### 修复（Fixed）
- **没开 Wi-Fi 时开房间/找小伙伴只会白等**：过去直接开跑 TCP/UDP，一路走到 15 秒超时才失败，
  用户完全不知道是没开 Wi-Fi。现在出门就得过门禁：
  - 首页「🏠 开个房间」「🔍 找小伙伴」先走 `ensureWifi{}`；可用性不满足时弹童趣提示，
    用户从系统面板回来后**自动继续执行刚才那一步**（不必重新点一次）；
  - `RoomManager.doCreateRoom()/doJoinRoom()` 增加 `ensureWifiReady()` 前置校验：
    即使绕过 UI 直接调用，也会先尝试自动打开，**打不开就立刻失败并明确说「Wi-Fi 还没打开」**，
    不再把 15 秒超时浪费在注定失败的连接上；
  - `RoomManager.hint()`：在房间里把 Wi-Fi 关掉时给出明确告警，而不是默默掉线。
- **刚进 App 若能自动开就先开好**：`autoEnableWifiOnLaunch()` 在启动页静默尝试，
  成功后 Toast「📶 已经帮你打开 Wi-Fi 啦」（旧机型有效，Android 10+ 由提示条引导）。

### 变更（Changed）
- `MainActivity`：`onStart/onStop` 注册/注销 Wi-Fi 广播；`maybeAutoRestore()` 增加 Wi-Fi 就绪判断，
  避免没网时也去自动回房。

---

## [1.1.0] - 2026-10-26

引入 **RoomManager 房间状态机**，重写组网生命周期。直接诱因是三星 S23U 上
「点了开房间后一直显示正在处理中」。

### 架构（Added）
- **新增 `room/RoomManager`**：房间生命周期的唯一权威（进程级单例）。
  对外暴露 `state: StateFlow<RoomState>`（`IDLE / CREATING / HOSTING / JOINING / JOINED / CLOSING`）、
  `status`、`peers`，UI 变为 `UI = f(state)` 的纯渲染层。
- **每个耗时操作都经 `runExclusive()`**：互斥 + **硬超时（开房 15s / 加入 15s / P2P 8s）**
  + `generation` 作废在途回调 + `finally` 兜底。由此保证**任何中间态最多 15 秒必出结果**，
  不可能再出现永久「处理中」。
- **信令分发移到进程级协程**：过去挂在 MainActivity 的 `lifecycleScope`，
  页面旋转/被回收时消息会断；现在跟随进程，Activity 销毁不影响房间。
- **`MainActivity.onDestroy()` 不再 `AppBus.reset()`**：房间生命周期不再绑在页面实例上。
- **首页新增「🚪 重新开始」**：强制作废在途操作、清理资源、回到可用状态，用户自己就能救回卡住的界面。

### 修复（Fixed）
- **三星 S23U「一直显示正在处理中」**（多点问题，逐项修）：
  - 忙碌态过去是 MainActivity 里一个手写布尔量 `working`，**只在网络回调里**复位；
  - `Socket(hostIp, port)` **没有连接超时**，目标不可达时阻塞数十秒到数分钟，期间所有点击
    都只得到一句「正在处理中，请稍候」（首成大 blocking 根因）→ 改为 `Socket()+connect(addr, 3000ms)`；
  - `ControlServer` 在**主线程**构造并 bind `ServerSocket` → 全部网络创建移到 `Dispatchers.IO`；
  - 已有可用 Wi-Fi 局域网时仍强行调用 Wi-Fi Direct `createGroup`，在部分三星/OneUI 上会触发
    系统重置 Wi-Fi → **改为按需**：只有检测不到 Wi-Fi 局域网（`wifiIpv4()`）时才走 P2P 直连。
- **Wi-Fi Direct 建组卡住**：
  - `createGroup` 前先 `removeGroup()` 并等待 600ms：残留旧 P2P 组会让 `createGroup` 一直返回
    `BUSY`，回调可能永远不来（同类开源实现里最常见的卡住根因）；
  - `createGroup` 的 `onSuccess` 后改为**轮询** `requestConnectionInfo`（组通常要几百毫秒才成形），
    避免把「还没成形」误判成失败。
- **首页权限门槛过高**：开房/找小伙伴原本一次性索要麦克风 + 相机 + 定位，任一项被拒就走不到建组。
  改为**按需索取**：组网与微聊不需要任何运行时权限，麦克风交给语音页、相机交给视频页。
- **功能页重复堆叠**：四个功能 Activity 加 `launchMode="singleTop"`，
  对端重复发「一起进来」时不会叠出一串相同页面。

### 变更（Changed）
- `MainActivity` 瘦身为纯 UI 层（约 600 行 → 约 420 行），不再持有任何网络资源。
- `ProfileActivity` 保存名片后改走 `RoomManager.announceProfile()`，不再直接碰 `server/client`。

---

## [1.0.1] - 2026-10-25

修复 4 个实测 Bug，核心根因是**消息总线用错并发原语**。

### 修复（Fixed）
- **微聊对方收不到**：
  - `ChatManager.inbound` 由单消费者 `Channel` 改为 `MutableSharedFlow(replay)`。
    Channel + `receiveAsFlow()` 会把每条消息只交给**其中一个**收集者；微聊页被重复打开时，
    消息会被已经看不见的那个页面截走。SharedFlow 广播给所有收集者。
  - 聊天消息改为广播 `to="all"`：过去按缓存 peerId 定向发送，名单过期时主机查不到该 id
    就把消息静默丢弃。
- **游戏结果不同步显示**：`GameManager.results` / `invites` 同样改用 `MutableSharedFlow`，
  并补上一局结束后重开新局的揭晓/裁定复位，保证多局连续可玩且两端都显示胜负。
- **只有「游戏」会同步进入**：`AppBus.activeFeature` 由 MainActivity 在 startActivity 之前预置，
  页面没真正起来时残留的标记会让 `openRemotely()` 误判「已经在这个功能里」而直接 return。
  现在该标记由**功能页自己**在 onCreate/onDestroy 里维护；并新增一次 1.2s 延迟补发 enter
  （重复到达会被去重逻辑挡住），避免首包丢失导致同步失败。
- **华为 P40（Android 10）无画面**：`VideoCallSession` 编码改用 **CBR + Baseline profile/level**
  （麒麟编码器在 VBR / High profile 下常不出帧或解不动）；分辨率与帧率按
  `640x480@20 → 640x480@15 → 320x240@15` 逐级降级；双 Surface 预览不被支持时降级为只输出编码器；
  帧率区间按摄像头能力挑选（写死会让部分机型拒绝预览请求）；周期性请求关键帧帮助对端恢复。
  `RtpSocket` 新增**从收到的包学习对端真实源端口**，彻底消除端口错配导致的黑屏。

## [1.0.0] - 2026-10-25

首个可发布版本。在「可落地代码框架」基础上补全全部功能，并修复 5 个实测 Bug，
通过 `assembleDebug` 编译校验（0 error）。应用标签：小伙伴对讲机；包名 `com.example.localptt`。

### 新增（Added）
- **儿童友好 UI**：大 emoji 图标 + 糖果色卡片，功能页全部可视化、一看就懂。
- **卡通头像与昵称**：10 个矢量卡通头像（🐻🐰🐱🐶🦊🐼🐯🐸🐨🦄）+ 自定义昵称，
  通过 `SharedPreferences` 持久化，随 `Hello` / `PeerList` 同步给房间内成员。
- **互联状态一致**：主机收不到自己名单、客户端「假成功」、IP 硬编码、无心跳四类根因已修；
  两端由同一份 `PeerList`（含主机自己）渲染，状态天然一致。新增 `Welcome` / `Ping` / `Pong`
  信令与 2.5s 心跳（9s 超时剔除僵尸 + 自动重连）。
- **功能同步（同进同出）**：新增 `FeatureSync` 信令 + `AppBus.featureEvents`
  （`MutableSharedFlow` 广播），一台进入/退出某功能页，房间内其他设备同步进入/退出。
- **微聊语音消息**：客户端也起 `VoiceFileServer`，语音文件 URL 指向发送方本机 IP，
  解决「主机收不到客户端语音」问题。
- **视频通话自动接通**：进页自动 invite，收到 invite 自动 accept，叠加免提全双工语音。

### 修复（Fixed）
- **语音太轻 / 不走扬声器**：`AudioEngine` 切 `MODE_IN_COMMUNICATION` + 扬声器开；
  采集源改 MIC 优先 + AGC + 数字增益 `recGain=2.0`；播放端 `playGain=3.0`（带削幅）；
  新增「🔊大喇叭 / 📞小听筒」开关。
- **微聊对方收不到**：主机 `route()` 补「目标是主机自己→投递本机」；`ChatManager` 改为进程内单例；
  语音 URL 指向发送方 IP。
- **视频不启用摄像头/麦克风**：`RtpSocket` 收发同端口（修复端口错配 5006/5007）；
  Camera2 两个 Surface 就绪才建会话；优先前置摄像头；进页即自动 invite/accept。
- **游戏不显示结果**：主机 `GameManager.send()` 改走 `AppBus.post`；`maybeJudge()` 改为
  双方揭晓齐备才裁定；收新承诺重置状态可连玩。
- **防闪退系统性加固**：端口真正 release、协程异常护栏 `NetGuard`、`WifiDirectManager` 空安全降级、
  `App.kt` 全局兜底、lateinit 改 Application 初始化。

### 已知限制（Known Limitations）
- 视频未做 RTP 分片（FU-A），单帧 > ~64KB 时弱网易丢包，生产环境建议换 WebRTC 媒体栈。
- 无 NAT / 公网穿透（设计目标即本地局域网，不需要 STUN/TURN）。
- NDK Opus 为可选增强：代码与 CMake 已备好，默认关闭，AMR-NB（MediaCodec）兜底功能等价。

## [0.1.0] - 2026-10-23

初始框架版本：组网发现、语音/视频/微聊/小游戏四大模块的「可落地代码框架」与通信协议设计。
