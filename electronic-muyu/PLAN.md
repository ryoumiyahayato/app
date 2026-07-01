# 电子木鱼 — 方案确认文档

> 当前阶段：阶段 1 项目初始化
> 版本：0.1.0
> 包名：app.electronicmuyu.android

---

## 项目定位

电子木鱼是一个**双人极简提醒器**，不是聊天软件，不是社交软件。

核心交互：
- 点击木鱼 → 本机功德 +1 → 播放音效 + 震动 → 通过 WSS 通知对方
- 对方收到提醒 → 播放提示音 + 震动 + 计数 +1

本质定位：
- 两个人之间提前约定含义的物理提醒通道

第一版成功标准：
- 两台 Android 手机在 App 正常运行、服务正常连接、通知权限开启时，能稳定互收提醒

---

## SDK 配置

| 项目 | 值 |
|------|-----|
| compileSdk | 35 |
| minSdk | 26 |
| targetSdk | 35 |

---

## 包名

| 位置 | 值 |
|------|-----|
| namespace | `app.electronicmuyu.android` |
| applicationId | `app.electronicmuyu.android` |
| Kotlin package | `app.electronicmuyu.android` |
| 源码目录 | `kotlin/app/electronicmuyu/android/` |

---

## 第一版最小功能范围

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

## 明确不做的功能清单

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

## Android 技术选型（第一版）

| 项目 | 选型 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 架构 | ViewModel + Kotlin Coroutines + StateFlow |
| 本地存储 | DataStore（普通配置/计数） |
| 敏感存储 | Android Keystore（session / key，阶段 6） |
| WSS 客户端 | OkHttp WebSocket（阶段 3+） |
| 音效播放 | SoundPool（阶段 2+） |
| 震动 | Vibrator / VibratorManager（阶段 2+） |
| 通知 | NotificationCompat（阶段 4+） |
| 后台 | Foreground Service（阶段 4+） |
| Min SDK | 26 |
| Target SDK | 35 |
| 构建 | Gradle + Kotlin DSL |

---

## 后端技术选型

| 项目 | 选型 |
|------|------|
| 运行时 | Cloudflare Workers |
| 状态管理 | Durable Objects（管理 pair room） |
| 实时通信 | WebSocket（WSS 443） |
| 配对 API | Worker HTTP endpoint |
| 数据存储 | 不做长期存储，DO 内存状态 |
| 日志 | 脱敏输出（短 hash，不出完整 deviceId） |

后端职责：
1. `POST /pair/create` → 创建配对码
2. `POST /pair/join` → 输入配对码，加入 pair room
3. `WSS /room/:pairId` → WebSocket 连接，收发 tap 事件
4. 收到 tap 后只转发给同 room 的另一台设备
5. 不保存 tap 历史
6. 对方离线时直接丢弃 tap

> 注意：阶段 1 不做 Worker 后端实现，留待阶段 5 实现。

---

## 权限清单（完整第一版）

| 权限 | 用途 | 阶段 |
|------|------|------|
| `INTERNET` | WSS 连接 | 阶段 1 声明，阶段 3 使用 |
| `VIBRATE` | 震动反馈 | 阶段 2+ |
| `POST_NOTIFICATIONS` | 后台通知（Android 13+） | 阶段 4+ |
| `FOREGROUND_SERVICE` | 后台保持连接 | 阶段 4+ |

> 阶段 1 Manifest 仅声明当前阶段必需的权限。
> 
> Android 14+ 所需 Foreground Service 类型：阶段 4 前重新评估，当前不确认具体类型。
> - 不使用 `dataSync`（Android 15+ 有运行时长限制）
> - 不使用 `specialUse`（涉及用途说明和审核风险）
> - 不使用 `remoteMessaging`
> - 阶段 4 前单独评估后再确认具体类型

---

## 本地数据清单

| 数据项 | 存储方式 | 阶段 |
|--------|----------|------|
| 本机功德数 | DataStore | 阶段 2+ |
| 收到提醒次数 | DataStore | 阶段 2+ |
| 配对状态 | DataStore | 阶段 5+ |
| 本机 deviceId | DataStore | 阶段 3+ |
| pairId | DataStore | 阶段 5+ |
| 本地 session token | DataStore → Keystore（阶段 6） | 阶段 5+ |
| 声音/震动/通知开关 | DataStore | 阶段 2+ |
| 最近一次提醒时间 | DataStore（可选） | 阶段 3+ |

---

## 分阶段开发计划

### 阶段 1：项目初始化（当前）
- 创建 Android 项目结构
- 配置 Gradle（Kotlin DSL，compileSdk 35, minSdk 26, targetSdk 35）
- 引入必要依赖
- 最简 Compose 主界面（显示"阶段 1 项目初始化完成"）
- 不实现 Foreground Service
- 不实现后台通知
- 不实现 WSS
- 不实现 Worker 后端
- 不实现音效/震动
- 不实现 DataStore 业务逻辑
- 项目能编译运行

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
- 阶段 3 不做 Foreground Service

### 阶段 4：后台通知
- 评估 Foreground Service 必要性
- 如需实现，评估并确认 Android 14+ 具体 foregroundServiceType
- 实现 Foreground Service
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

## 阶段 1 项目结构

```
electronic-muyu/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/app/electronicmuyu/android/
│   │   │   ├── MainActivity.kt
│   │   │   └── ui/
│   │   │       └── theme/
│   │   │           └── ElectronicMuyuTheme.kt
│   │   └── res/
│   │       ├── values/strings.xml
│   │       ├── values/themes.xml
│   │       └── mipmap-anydpi-v26/ic_launcher.xml
├── build.gradle.kts (project level)
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
└── worker/ (阶段 5 实现，当前仅占位 README)
```

---

## 后台提醒的现实边界

> 阶段 1 不实现后台提醒。此节留作阶段 4 前置参考。

后台提醒能收到需满足的前提条件：
1. App 仍在运行（未被系统杀死）
2. Foreground Service 正在运行（常驻通知可见）
3. WSS 连接存活
4. 用户未关闭通知权限
5. 系统/厂商省电策略未强行杀掉进程
6. 用户未手动强行停止 App

第一版表述：
> 前台实时收到；后台在 Foreground Service 存活时尽量实时收到；进程被系统杀死、用户强停、设备深度省电、通知权限关闭时，不保证收到。

> 后台提醒方案放到阶段 4 前重新评估。