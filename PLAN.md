# 电子木鱼 — 第一版方案确认文档

> 本文件用于确认第一版 MVP 方案，不包含任何实现代码。
> 请逐项确认后，我再进入阶段 1 项目初始化。

---

## A. 产品目标理解

电子木鱼是一个**双人极简提醒器**，不是聊天软件、不是社交软件。

核心交互：
- 点击木鱼 → 本机功德 +1 → 播放音效 + 震动 → 通过 WSS 通知对方
- 对方收到提醒 → 播放提示音 + 震动 + 计数 +1

本质定位：
- 两个人之间提前约定含义的物理提醒通道
- "我敲一下，就是提醒你一下"

第一版成功标准：
- 两台 Android 手机在 App 正常运行、服务正常连接、通知权限开启时，能稳定互收提醒

---

## B. 第一版最小功能范围

1. Android 原生 Kotlin App
2. 主界面：木鱼按钮 + 功德计数 + 收到提醒计数 + 连接状态
3. 本地点击功德 +1，播放木鱼声，震动
4. 点击后通过 WSS 向对方发送 tap
5. 前台实时收到对方 tap，播放提示音 + 震动 + 计数
6. 后台通过 Foreground Service 存活期间收到 tap → 通知栏提醒
7. 手动配对码配对（A 创建 → B 输入 → 绑定 pairId）
8. 配对状态持久化，重启可恢复
9. 解除配对
10. 断线自动重连（退避策略）
11. 连接状态显示（未配对/连接中/已连接/对方离线/未连接）
12. 设置页：声音开关、震动开关、通知开关、清空计数、关于
13. 基础权限：INTERNET、VIBRATE、POST_NOTIFICATIONS、FOREGROUND_SERVICE
14. 后端：Cloudflare Worker + Durable Object 做消息转发
15. 不保存历史记录，不做离线补发

---

## C. 明确不做的功能清单

| 类别 | 具体不做的功能 |
|------|---------------|
| 聊天 | 自由文本聊天、表情包、图片、语音、视频 |
| 社交 | 朋友圈、通讯录、好友搜索、陌生人匹配、公开房间 |
| 账号 | 手机号登录、邮箱登录、微信/QQ 接入 |
| 推送 | FCM、厂商推送（第一版不做） |
| 跨平台 | 不接小程序、不接 Web、不接 Flutter、不接 React Native |
| 数据 | 不保存历史消息、不做云端聊天记录、不做已读未读 |
| 后台 | 不承诺进程被杀后仍能收到、不承诺省电模式下必达 |
| 广告 | 无广告 SDK |
| 统计 | 无第三方统计 SDK、无埋点 |
| 安全 | 第一版不做端到端加密、不做二维码配对（后续考虑） |
| 扩展 | 不做多人群组、不做超过3台设备 |

---

## D. Android 技术选型

| 项目 | 选型 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 架构 | ViewModel + Kotlin Coroutines + StateFlow |
| 本地存储 | DataStore（普通配置/计数） |
| 敏感存储 | Android Keystore（session / key） |
| WSS 客户端 | OkHttp WebSocket |
| 音效播放 | SoundPool（短音效） |
| 震动 | Vibrator / VibratorManager |
| 通知 | NotificationCompat |
| 后台 | Foreground Service |
| Min SDK | 26（Android 8.0） |
| Target SDK | 35（Android 15） |
| Compile SDK | 35（Android 15） |
| 构建 | Gradle + Kotlin DSL |
| 包名 | `app.electronicmuyu.android` |
| 版本号 | `0.1.0` |

---

## E. 后端技术选型

| 项目 | 选型 |
|------|------|
| 运行时 | Cloudflare Workers |
| 状态管理 | Durable Objects（管理 pair room） |
| 实时通信 | WebSocket（WSS 443） |
| 配对 API | Worker HTTP endpoint |
| 数据存储 | 不做长期存储，DO 内存状态 |
| 日志 | 脱敏输出（短 hash，不出完整 deviceId） |

后端职责：
1. `POST /pair/create` → 创建配对码，返回 pairingCode + pairId
2. `POST /pair/join` → 输入配对码，加入 pair room，返回 pairId
3. `WSS /room/:pairId` → WebSocket 连接，收发 tap 事件
4. 收到 tap 后只转发给同 room 的另一台设备
5. 不保存 tap 历史
6. 对方离线时直接丢弃 tap

---

## F. 权限清单

**申请的必要权限（第一版确认）：**

| 权限 | 用途 | 备注 |
|------|------|------|
| `INTERNET` | WSS 连接 | 普通权限，自动获得 |
| `VIBRATE` | 震动反馈 | 普通权限 |
| `POST_NOTIFICATIONS` | 后台通知 | Android 13+ 运行时申请 |
| `FOREGROUND_SERVICE` | 后台保持连接 | Manifest 声明 |
| `FOREGROUND_SERVICE_*` | Android 14+ 前台服务类型 | **待确认，见下方分析** |

**明确不申请（修正后）：**
- `RECEIVE_BOOT_COMPLETED` — 第一版不做开机自启
- 通讯录、短信、位置、相册、外部存储、麦克风、摄像头、通话记录、剪贴板读取、蓝牙、附近设备、日历、精确闹钟

**明确不申请的权限：**
通讯录、短信、位置、相册、外部存储、麦克风、摄像头、通话记录、剪贴板读取、蓝牙、附近设备、日历、精确闹钟

---

## G. 本地数据清单

| 数据项 | 存储方式 | 说明 |
|--------|----------|------|
| 本机功德数 | DataStore | Int，本地累计 |
| 收到提醒次数 | DataStore | Int，对方发来的累计 |
| 配对状态 | DataStore | Enum: UNPAIRED / PAIRING / PAIRED / FAILED |
| 本机 deviceId | DataStore | UUID，首次生成（日志脱敏，只显示前6位或短 hash）|
| pairId | DataStore | 配对成功后保存（日志脱敏）|
| 本地 session token | DataStore (Keystore 加密) | 鉴权用（禁止日志输出）|
| 连接配置（URL 等） | DataStore | 服务端地址 |
| 声音开关 | DataStore | Boolean |
| 震动开关 | DataStore | Boolean |
| 通知开关 | DataStore | Boolean |
| 最近一次提醒时间 | DataStore | Long?（可选） |

**明确不保存：**
聊天内容、历史消息列表、对方隐私信息、通讯录、位置、图片、语音、自由文本、手机号、邮箱、社交账号

---

## H. 网络流程

```
A 手机                    Cloudflare                    B 手机
  |                           |                           |
  |--- WSS connect /room ---->|                           |
  |                           |<--- WSS connect /room --- |
  |                           |                           |
  |--- tap event ------------>|                           |
  |                           |--- tap event ----------->|
  |                           |                           |
  |                           |                           |--- 播放提示音 + 震动
  |                           |                           |--- 收到计数 +1
  |                           |                           |--- 前台显示/后台通知
```

tap 事件格式：
```json
{
  "type": "tap",
  "pairId": "pair_xxx",
  "deviceId": "dev_xxx_hashed",
  "timestamp": 1234567890
}
```

关键设计：
- 仅 WSS 443，不走明文 HTTP
- 服务端收到 tap 后只转发给同 room 的另一方
- 对方离线时丢弃，不做离线消息队列
- 断线自动重连，指数退避（1s → 2s → 4s → ... → 上限 60s）

---

## I. 配对流程

```
A 手机                              B 手机
  |                                   |
  |--- POST /pair/create ------------>|
  |<--- { pairingCode, pairId } -----|
  |                                   |
  | 显示 pairingCode (6位字母数字)     |
  |                                   |--- POST /pair/join { code }
  |                                   |<--- { pairId, session }
  |                                   |
  |--- WSS /room/:pairId ------------>|
  |                                   |--- WSS /room/:pairId --->
  |                                   |
  | 配对完成，双方进入同一 room        |
  | 保存 pairId + session 到本地       |
```

设计要点：
- pairingCode 有效期：5 分钟
- 一次性使用，配对成功后立即失效
- 每对 pairId 只允许 2 台设备
- 配对码长度：6 位字母数字（排除易混淆字符 0/O/1/I/l）
- 配对页启用 `FLAG_SECURE` 防止截图录屏
- 任一方可解除配对 → 双方清除本地配对信息

---

## J. 分阶段开发计划

### 阶段 1：项目初始化（当前）
- 创建 Android 项目结构
- 配置 Gradle（Kotlin DSL）
- 引入必要依赖
- 确认项目能编译运行

### 阶段 2：Android 本地 MVP（不接后端）
- 主界面：木鱼按钮 + 功德计数 + 连接状态占位
- 本地点击功德 +1
- 播放木鱼声音（SoundPool）
- 震动（Vibrator）
- 按钮动画
- 设置页：声音/震动开关、清空计数
- DataStore 保存计数和设置
- 本阶段可独立运行测试

### 阶段 3：WSS 连接 MVP
- 使用固定测试 pairId
- 建立 WSS 连接（OkHttp WebSocket）
- 显示连接状态
- 发送 tap
- 接收 tap → 前台提示 + 计数
- 断线自动重连 + 退避
- Foreground Service 基础实现

### 阶段 4：后台通知
- Foreground Service 完善
- Android 13+ POST_NOTIFICATIONS 权限申请
- 后台收到 tap → 通知栏提醒
- 通知震动 + 提示音
- 点击通知回到 App
- 设置页显示通知权限状态
- 通知关闭时给出提示

### 阶段 5：配对系统
- 后端 Worker + Durable Object 实现
- 创建配对码 API
- 加入配对码 API
- Android 端配对 UI
- 本地持久化配对状态
- 重启自动恢复连接
- 解除配对

### 阶段 6：安全与加固
- 敏感日志脱敏
- Android Keystore 保存 session
- 禁用 Auto Backup
- 检查 Manifest 权限
- Release 构建配置
- FLAG_SECURE 配对页
- 测试清单输出

---

## K. 阶段 1 项目结构

```
electronic-muyu/
├── app/
│   ├── build.gradle.kts
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/example/electronicmuyu/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainScreen.kt
│   │   │   │   │   ├── SettingsScreen.kt
│   │   │   │   │   └── PairScreen.kt
│   │   │   │   ├── viewmodel/
│   │   │   │   │   └── MainViewModel.kt
│   │   │   │   ├── data/
│   │   │   │   │   ├── LocalDataStore.kt
│   │   │   │   │   └── SecureStorage.kt
│   │   │   │   ├── network/
│   │   │   │   │   ├── WebSocketClient.kt
│   │   │   │   │   └── PairApi.kt
│   │   │   │   ├── service/
│   │   │   │   │   └── MuyuForegroundService.kt
│   │   │   │   ├── audio/
│   │   │   │   │   └── SoundManager.kt
│   │   │   │   ├── vibration/
│   │   │   │   │   └── VibrationManager.kt
│   │   │   │   ├── notification/
│   │   │   │   │   └── NotificationHelper.kt
│   │   │   │   └── model/
│   │   │   │       └── TapEvent.kt
│   │   │   ├── res/
│   │   │   │   ├── raw/ (木鱼音效文件)
│   │   │   │   ├── drawable/
│   │   │   │   ├── values/
│   │   │   │   └── ...
│   │   │   └── ...
│   │   ├── test/
│   │   └── androidTest/
├── build.gradle.kts (project level)
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml (version catalog)
└── worker/ (Cloudflare Worker 后端)
    ├── src/
    │   ├── index.ts
    │   ├── do.ts (Durable Object)
    │   └── types.ts
    ├── wrangler.toml
    ├── package.json
    └── tsconfig.json
```

---

## L. 后台提醒的现实边界和限制

### 能收到提醒的前提条件

1. App 仍在运行（未被系统杀死）
2. Foreground Service 正在运行（常驻通知可见）
3. WSS 连接存活
4. 用户未关闭通知权限
5. 系统/厂商省电策略未强行杀掉进程
6. 用户未手动强行停止 App

### 可能收不到提醒的场景

| 场景 | 能否收到 | 说明 |
|------|----------|------|
| App 前台 | ✅ 实时收到 | 最佳状态 |
| App 后台，Foreground Service 运行 | ✅ 尽量实时 | 受系统省电影响 |
| 手机熄屏，服务运行 | ✅ 可能延迟 | 部分系统限制网络 |
| 系统省电模式 | ⚠️ 可能延迟或收不到 | 进程可能被冻结 |
| 用户手动杀掉 App | ❌ 收不到 | 进程被杀死 |
| 用户强行停止 App | ❌ 收不到 | 系统设置中强行停止 |
| 通知权限关闭 | ❌ 无通知提醒 | App 内可能仍计数 |
| 无网络 | ❌ 收不到 | 重连后也不补发 |
| 进程被系统回收 | ❌ 收不到 | 系统内存不足时 |

### 第一版表述

> 前台实时收到；后台在 Foreground Service 存活时尽量实时收到；进程被系统杀死、用户强停、设备深度省电、通知权限关闭时，不保证收到。

### 设置页提示文案

- 通知权限关闭时："通知未开启，无法在后台接收提醒"
- 系统可能限制后台运行时："后台实时提醒可能受系统省电策略影响"

---

## M. 需要您确认的问题

### 项目配置

1. **包名**：`com.example.electronicmuyu` 是否合适？还是您有其他偏好？
2. **Min SDK**：设 26 (Android 8.0) 是否接受？
3. **Target SDK**：设为 34（Android 14）是否可以？
4. **项目目录名**：项目文件夹命名为 `electronic-muyu` 还是其他名称？

### 后端选择

5. **Cloudflare Worker + Durable Object** 方案是否确认？您是否有 Cloudflare 账号可以部署，还是需要我提供完整的本地可模拟方案？
6. **Cloudflare Worker 部署**：您希望我提供完整的 Worker 代码和 wrangler 配置，由您自行部署，还是需要其他安排？

### 音效资源

7. **木鱼音效文件**：您需要我提供一个简单的合成音效（编程生成），还是您自己有音效文件要使用？
8. **提示音效**：对方收到提醒时的提示音，是和木鱼声一样，还是用不同的短促提示音？

### 开发环境

9. **开发机**：您当前设备是否已安装 Android Studio？是否需要我帮助搭建开发环境？
10. **测试设备**：您是否有两台 Android 真机用于测试？还是使用模拟器？

### 功能细节

11. **配对码长度**：6 位字母数字（排除易混淆字符）是否合适？
12. **配对码有效期**：5 分钟是否合适？还是需要调整？
13. **清空计数**：设置页的"清空本机计数"，是同时清空功德数和收到提醒数，还是分开清空？
14. **关于页面**：是否需要显示版本号、开发者信息等内容？

### 其他

15. **版本号**：第一版版本号定为 `1.0.0` 是否可以？
16. **调试日志**：开发阶段是否允许打印完整 deviceId 日志（release 时必须脱敏）？

---

> 以下为修正后的确认方案（2026-07-01 更新）。
> 
> **Foreground Service 类型问题尚未最终确认，见下方补充分析。**

---

## 补充分析：Foreground Service 类型选择（Android 14+）

### 背景

Android 14（API 34）开始，Foreground Service 必须在 Manifest 中声明 `foregroundServiceType`。
Android 15（API 35）对部分类型（如 `dataSync`）增加了连续运行 6 小时的时间限制。

### 候选类型分析

| 类型 | 适用场景 | 时间限制 | 评估 |
|------|----------|----------|------|
| `dataSync` | 网络数据传输 | Android 15 起 **6 小时限制** | ⚠️ 不适合长期 WSS 常连接 |
| `remoteMessaging` | 实时通信/消息 | 无明确限制 | ❌ 需额外权限声明，且本 App 不是消息应用 |
| `connectedDevice` | 蓝牙/NFC 等外设通信 | 无 | ❌ 不适用 |
| `specialUse` | 不适用于以上类别的特殊用途 | 无限制 | ✅ 适合本场景，需提供文字说明 |

### 推荐方案

**使用 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`**

理由：
1. WSS 常连接不属于大规模 `dataSync`，也不属于 `remoteMessaging`
2. `specialUse` 在 Android 15+ **没有运行时限制**
3. 系统会向用户展示用途说明，符合透明原则

需要在 `res/xml/foreground_service_description.xml` 提供说明文字，例如：
> "维持 WebSocket 长连接，用于接收提醒消息"

### 风险说明

- `specialUse` 在 Google Play 发布时需要人工审核说明文字
- 本 App 第一版不计划上架 Play Store（未要求），可先使用此类型
- 如果后续上架 Play Store 时审核不通过，可在那时调整为其他类型

### 请确认

**是否同意第一版使用 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`？**
- 同意 → 阶段 1 将在 Manifest 中声明此类型，并创建说明资源文件
- 不同意或希望改为仅前台可用 → 第一版可降级：App 退出前台后不保证连接，不做后台通知，简化权限声明

---

## 其他修正要点（已按反馈完成）

1. **包名**：`app.electronicmuyu.android` ✓
2. **SDK**：`minSdk = 26`, `targetSdk = 35`, `compileSdk = 35` ✓
3. **权限**：移除 `RECEIVE_BOOT_COMPLETED` ✓
4. **音效**：阶段 2 先使用同一个木鱼声作为占位（`res/raw/muyu.mp3`）✓
5. **清空计数**：两个独立按钮（清空功德数 / 清空收到提醒数）✓
6. **关于页**：仅显示名称 + 版本 `0.1.0` + 简短说明 ✓
7. **日志**：从开发阶段即做脱敏处理 ✓
8. **版本号**：第一版开发阶段使用 `0.1.0` ✓
9. **后端**：保留 `worker/` 目录规划，阶段 1 不写后端代码 ✓
10. **收到提醒音效**：第一版复用木鱼声，不做额外提示音 ✓
11. **调试日志**：deviceId 只显示前 6 位 / 短 hash，session 和 pairingCode 不打印 ✓
