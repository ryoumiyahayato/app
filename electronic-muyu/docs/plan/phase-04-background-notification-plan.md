# 阶段 4 — 后台通知方案确认文档

> 本文档为阶段 4 的设计方案，不含任何实现代码。
> 请在确认后再进入编码阶段。

---

## A. 当前 Android 后台提醒的现实边界

电子木鱼在阶段 3 验证了：
- **App 前台** ✅ 实时双向收发 tap，可靠
- **App 切后台（无 Foreground Service）** ❌ WSS 连接会在几秒到几分钟内被系统杀死
- **熄屏** ❌ 同上，连接很快断开
- **App 被划掉** ❌ 进程被杀死，连接终止

Android 系统的后台进程管理策略：

| Android 版本 | 后台网络限制 |
|-------------|-------------|
| 8.0 (API 26) | 后台执行限制，后台 App 网络访问受限 |
| 9.0 (API 28) | 后台 App 不可访问网络（除非特殊情况） |
| 10.0 (API 29) | 后台 App 网络访问进一步收紧 |
| 12+ (API 31+) | 省电模式更强，后台进程更易被杀 |

**结论**：没有 Foreground Service，WSS 连接在后台无法存活。这不是"可能不稳定"的问题，而是"几乎一定会断"。

---

## B. 是否必须使用 Foreground Service

**是，必须使用。**

理由：
1. WSS 长连接需要在后台持续运行，普通后台 Service 会被系统快速杀死。
2. Foreground Service 拥有用户可见的常驻通知，系统对 Foreground Service 的后台限制大幅降低。
3. 除 Foreground Service 外，Android 没有其他 API 能保证后台长期存活网络连接。
4. WorkManager 不适合长连接场景（周期性任务，非实时）。
5. FCM 不在第一版范围内。

**结论**：Foreground Service 是实现后台提醒的必选方案，无法绕过。

---

## C. Android 14+ foregroundServiceType 应该如何选择

Android 14（API 34）要求所有 Foreground Service 必须声明 `foregroundServiceType`。

电子木鱼的实际场景分析：

| 类型 | 说明 | 是否适用 |
|------|------|---------|
| `dataSync` | 数据同步、上传/下载 | ❌ 本项目的核心不是"同步数据"，是保持实时连接。Google Play 审查可能认为 dataSync 类型的常驻通知不符合预期用途 |
| `specialUse` | 特殊用途 App（需提交审核说明） | ⚠️ 可以，但有 Play 商店审核门槛 |
| `connectedDevice` | 蓝牙/NFC 外设连接 | ❌ 不相关 |
| `camera`/`microphone` | 摄像头/麦克风 | ❌ 不相关 |
| `phoneCall` | 通话相关 | ❌ 不相关 |

**推荐方案**：**`dataSync`**

理由：
1. WSS 连接本质是在"同步 tap 事件数据"——虽然只是简单转发，但归类为 dataSync 在技术上说得通。
2. 本项目不走 Google Play 发布（第一版是独立安装），所以 Play 审核问题不存在。
3. `dataSync` 不需要额外的审核或说明，声明即可使用。
4. 比 `specialUse` 实现成本低得多。

⚠️ 注意：国内手机厂商（华为、小米、OPPO、vivo 等）对 `dataSync` 类型的 Foreground Service 有额外的省电策略限制，即使正确实现，后台连接仍然可能被厂商系统杀死。这是第一版需要接受的事实。

---

## D. 为什么不能直接使用 dataSync 作为长期 WSS 常连接方案

**可以，但有局限性。**

`dataSync` 作为 `foregroundServiceType` 本身没问题，真正的限制不在 `dataSync`，而在以下因素：

1. **国内厂商系统**：华为、小米等对 Foreground Service 有白名单机制，非系统白名单 App 即使使用 Foreground Service，也可能在省电模式下被限制网络或杀掉。

2. **系统省电策略差异**：
   - 不同厂商、不同 Android 版本行为不一致
   - 部分系统在熄屏后仍然限制后台网络
   - 部分系统在省电模式下直接杀掉 Foreground Service

3. **用户感知**：
   - Foreground Service 必须有常驻通知（用户可见）
   - 部分用户可能反感常驻通知，关闭通知权限后会导致 Service 也被限制

4. **WSS 心跳问题**：
   - OkHttp 的 `pingInterval` 在后台可能被延迟
   - 长时间熄屏后 WSS 可能静默断开

**但这些问题不是选择其他 `foregroundServiceType` 能解决的。** 任何 Foreground Service 类型在面对国内厂商系统时都会遇到类似限制。

**结论**：`dataSync` 是第一版最合适的方案。局限性客观存在，需要在设置页明确提示用户。

---

## E. specialUse 是否适合本项目，风险是什么

**不适合第一版。**

`specialUse` 的适用场景：
- 系统级安全 App
- 设备管理 App
- 企业级控制 App
- 无障碍辅助功能 App

电子木鱼不适合 `specialUse`：
1. `specialUse` 需要 Google Play 提交审核说明，本项目不走 Play 商店。
2. `specialUse` 在部分厂商系统上反而可能触发更多限制。
3. 审核材料需要说明「为什么不能使用标准的 Foreground Service 类型」，电子木鱼没有充分的理由——`dataSync` 完全可以覆盖需求。
4. `specialUse` 在用户看来可能显得可疑。

**风险**：
- Play 审核失败或被拒绝
- 用户侧产生安全疑虑
- 实现复杂度高于 `dataSync`，不值得

**结论**：不使用 `specialUse`。

---

## F. 如果不使用 Foreground Service，后台提醒能力会降级到什么程度

| 场景 | 有 Foreground Service | 无 Foreground Service |
|------|----------------------|----------------------|
| App 前台 | ✅ 实时收到 | ✅ 实时收到 |
| App 切后台（< 1 分钟） | ✅ 仍在线 | ⚠️ 可能仍在线 |
| App 切后台（1-5 分钟） | ✅ 仍在线 | ❌ 大概率断连 |
| 熄屏（< 1 分钟） | ✅ 仍在线 | ⚠️ 可能仍在线 |
| 熄屏（> 5 分钟） | ✅ 仍在线（部分厂商可能断） | ❌ 必然断连 |
| 用户划掉 App | ❌ 收不到 | ❌ 收不到 |
| 系统强停 | ❌ 收不到 | ❌ 收不到 |

**无 Foreground Service 的实际情况**：
- App 切后台后，WSS 连接存活时间取决于：
  - Android 版本（越高越严格）
  - 厂商策略（小米/华为可能 30 秒内断）
  - 是否开启了省电模式
- 实际测试表明，多数 Android 12+ 设备上，后台 App 的 WSS 连接在 **30 秒到 2 分钟内**被断开
- 断连后虽然有自动重连，但后台 App 的网络权限被限制，重连会失败
- 因此实际上「无 Foreground Service = 后台基本收不到」

**结论**：不做 Foreground Service，后台提醒能力趋近于零。想要任何有意义的后台提醒能力，必须使用 Foreground Service。

---

## G. 第一版是否应该先做"App 前台 + 短期后台"提醒，而不是强后台常驻

**不建议。**

所谓的"短期后台"有两种理解：

### 方案 A：不做 Foreground Service，靠前台 Activity 的 onPause/onResume 生命周期
- `onPause` 时不主动断连，但系统会在几十秒内自动杀掉后台网络
- 用户切后台后，几乎无法收到提醒
- 这其实等于"只有前台能收到"，和阶段 3 没有区别

### 方案 B：使用 Bound Service + 前台可见时提权
- 这仍然无法解决后台存活问题
- 且 Bound Service 在有 Activity 绑定时才存活，切后台后 Activity 可能被销毁

### 方案 C：WorkManager 周期性检查
- 这不叫"后台提醒"，这叫"轮询"
- 最小周期 15 分钟（受系统限制），完全失去实时性
- 电子木鱼的核心价值是实时提醒，15 分钟延迟不可接受

**结论**：

1. "短期后台"本质上是不可行的——系统不会给你"短期"，而是直接杀死。
2. 如果接受"后台几乎收不到"，阶段 3 已经完成，不需要阶段 4。
3. 如果要做后台提醒，Foreground Service 是唯一可行的路径。
4. **第一版应该做 Foreground Service，但必须在设置页诚实显示限制。**

---

## H. Android 13+ POST_NOTIFICATIONS 权限申请流程

```
App 启动
  └→ 检查 Build.VERSION.SDK_INT >= 33
       ├→ 否：无需处理（Android 12 及以下无运行时通知权限）
       └→ 是：
            ├→ 检查 checkSelfPermission(POST_NOTIFICATIONS) == GRANTED
            │    ├→ 已授权：正常使用通知
            │    └→ 未授权：
            │         ├→ shouldShowRequestPermissionRationale()
            │         │    ├→ true: 之前拒绝过但未勾选"不再询问"，可以解释后再次申请
            │         │    └→ false: 首次申请 或 勾选了"不再询问"
            │         ├→ requestPermissions(POST_NOTIFICATIONS, REQUEST_CODE)
            │         │    ├→ 用户允许：正常使用通知
            │         │    └→ 用户拒绝：
            │         │         ├→ 显示解释说明（但无法再次弹窗申请）
            │         │         └→ 设置页显示"通知未开启，请前往系统设置手动开启"
            │         └→ 如果勾选了"不再询问"：
            │              → 引导用户去系统设置手动开启通知权限
            └→ 设置页实时显示通知权限状态
```

**关键设计决策**：
1. 不是一启动就弹窗申请。触发时机：用户进入设置页，或者 App 首次需要通知能力时（例如后台 Service 启动前）。
2. 拒绝后不要反复弹窗，而是通过设置页的"通知未开启"提示 + 跳转系统设置引导。
3. 权限状态需动态检查，因为用户可能随时在系统设置中关闭通知。

---

## I. 后台收到 tap 后通知栏提醒流程

```
WSS 收到 tap 消息
  └→ 解析 JSON，验证 type == "tap"
       └→ 更新本地 receivedCount（DataStore）
            └→ 检查处于前台还是后台
                 ├→ 前台：
                 │    └→ 通过 StateFlow 通知 UI
                 │       ├→ 收到提醒次数 +1
                 │       ├→ 显示"对方敲了一下木鱼"
                 │       ├→ 播放提示音
                 │       └→ 震动
                 │
                 └→ 后台（Service 运行，无可见 Activity）：
                      └→ 创建通知
                         ├→ 标题："电子木鱼"
                         ├→ 正文："收到一次功德提醒" 或 "对方敲了一下木鱼"
                         ├→ 小图标：ic_launcher
                         ├→ 优先级：HIGH（弹出通知）
                         ├→ 震动：使用 NotificationCompat.DEFAULT_VIBRATE
                         ├→ 提示音：使用 NotificationCompat.DEFAULT_SOUND
                         │     （或系统默认通知音）
                         ├→ 点击行为：
                         │    └→ PendingIntent.getActivity() 回到 MainActivity
                         └→ notificationManager.notify(NOTIFICATION_ID, builder.build())
```

**通知 ID**：固定 ID（如 1001），同一通知不断更新，不会重复堆叠通知。

**震动提示音**：优先使用 `NotificationCompat.DEFAULT_VIBRATE | DEFAULT_SOUND`，跟随系统通知设置。也可以考虑自定义提示音（后续扩展）。

**Channel**：需要创建通知渠道（Android 8+ 必需）：
- Channel ID: `muyu_tap_channel`
- Channel 名称: "功德提醒"
- Channel 重要性: IMPORTANCE_HIGH（允许弹窗、震动、提示音）

---

## J. 点击通知回到 App 的流程

```
用户点击通知
  └→ PendingIntent.getActivity(context, requestCode, intent, flags)
       └→ Intent
            ├→ Action: Intent.ACTION_MAIN
            ├→ Category: Intent.CATEGORY_LAUNCHER
            ├→ Component: MainActivity
            ├→ Flags: FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP
            │    └→ 如果 App 已在后台：bring to front
            │    └→ 如果 App 已被杀死：重新启动并导航到 MainActivity
            └→ （可选）Extra: "from_notification" = true
                 └→ MainActivity 可通过 intent 判断是否由通知启动
```

PendingIntent 配置：
- `FLAG_UPDATE_CURRENT`：更新已存在的 PendingIntent
- `FLAG_IMMUTABLE`：Android 12+ 必须设置（除非使用 mutable）
- requestCode：0（单一通知）

**不需要特殊逻辑**：Activity 进入后，正常显示 MainScreen，状态通过 ViewModel/DataStore 恢复（功德数、收到次数等）。

---

## K. Manifest 需要新增哪些权限和声明

### 已有（阶段 3 已声明）：
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### 阶段 4 需要新增：

```xml
<!-- 1. Foreground Service 权限 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

<!-- 2. Android 13+ 通知权限（运行时权限，仅声明） -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- 3. Android 14+ Foreground Service 类型声明（仅在 service 标签上） -->

<!-- 4. Service 声明 -->
<service
    android:name=".service.MuyuForegroundService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

### 变更清单：
| 项目 | 新增 |
|------|------|
| `FOREGROUND_SERVICE` 权限 | ✅ 新增 |
| `POST_NOTIFICATIONS` 权限 | ✅ 新增（仅 Android 13+ 运行时申请） |
| `MuyuForegroundService` service 声明 | ✅ 新增 |
| `foregroundServiceType="dataSync"` | ✅ 新增（Android 14+ 必备） |
| 通知渠道声明 | 代码中创建，Manifest 不需要 |

### 仍然不申请：
- `RECEIVE_BOOT_COMPLETED` — 第一版不做开机自启
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — 第一版不申请电池白名单
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` — 不需要

---

## L. 禁止内容清单

阶段 4 必须严格遵守以下禁止项：

| 类别 | 禁止 |
|------|------|
| 聊天 | 不新增任何文本输入、消息列表、会话 UI |
| 自由文本 | 通知内容固定为"收到一次功德提醒"，不从任何地方读取或展示用户文本 |
| 历史消息 | 不保存 tap 到本地列表/数据库，不存储历史记录 |
| 离线补发 | 不缓存离线期间的 tap，不补发 |
| 配对系统 | 不新增配对 UI、配对 API、pairId 管理 |
| FCM | 不接 Firebase Cloud Messaging |
| 厂商推送 | 不接华为/小米/OPPO/vivo 推送 SDK |
| 账号系统 | 不新增登录、注册、session 管理 |
| SDK | 不新增第三方 SDK（广告、统计、推送等） |
| 权限 | 不申请额外非必要权限 |
| 音效文件 | 通知音效使用系统默认，不新增自定义音效文件 |

**通知正文**必须使用固定字符串，不可拼接、不可用户自定义、不可从网络获取。

---

## M. 阶段 4 最小实现范围

### 必须实现

1. **Foreground Service** (`MuyuForegroundService.kt`)
   - 持有 WSS 连接引用（或重新建立连接）
   - 常驻通知："电子木鱼正在运行"
   - 收到 tap 后创建通知栏提醒
   - Start/Stop 方法（由 Activity 控制生命周期）

2. **NotificationHelper** (`NotificationHelper.kt`)
   - 创建通知渠道
   - 创建 Foreground Service 常驻通知
   - 创建 tap 提醒通知
   - POST_NOTIFICATIONS 权限检查

3. **MainActivity 生命周期管理**
   - `onStart`/`onResume` → 启动 Foreground Service
   - `onStop` → 不停止 Service（Service 继续在后台运行）
   - 启动时检查 POST_NOTIFICATIONS 权限

4. **设置页通知权限状态显示**
   - 显示通知是否开启
   - 显示后台连接状态
   - 跳转系统通知设置入口

5. **AndroidManifest 更新**
   - 新增 `FOREGROUND_SERVICE` 权限
   - 新增 `POST_NOTIFICATIONS` 权限
   - 新增 `MuyuForegroundService` 声明（带 `foregroundServiceType`）

### 明确不实现

- ❌ 开机自启（`RECEIVE_BOOT_COMPLETED`）
- ❌ 电池优化白名单申请（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）
- ❌ 自定义通知提示音
- ❌ 通知分组/折叠
- ❌ 通知按钮（快速回复、已读等）
- ❌ 通知渠道设置页面
- ❌ 后台连接断开后自动重启 Service
- ❌ 进程被杀后系统重启 Service

---

## N. 阶段 4 测试清单

| 测试场景 | 预期 | 测试方法 |
|---------|------|---------|
| App 前台，正常连接 | 实时收到 tap，提醒次数 +1，显示提示 | `node send-tap.js` |
| App 切后台（< 1 分钟） | 收到后台通知，震动，提示音 | `node send-tap.js` 后立刻切后台 |
| App 切后台（> 5 分钟） | 大概率收到（受厂商限制） | 切后台后等待 5 分钟再发 tap |
| 熄屏 | 收到通知（厂商差异大） | 熄屏后 `node send-tap.js` |
| 通知权限关闭 | 不显示通知，但 App 内计数可能更新（如有前台） | 系统设置关闭通知权限 |
| 通知权限未授予（首启） | App 正常启动，设置页显示通知未开启 | 新安装或清除数据 |
| 用户拒绝通知权限 | 不再弹窗申请，设置页引导手动开启 | 弹窗时拒绝 |
| 点击通知回到 App | App 切换到前台（已打开）或重新启动（已杀死） | 发送 tap → 点击通知 |
| Node 中继断开 | 连接状态变为"未连接"，WSS 重连 | 停止 index.js |
| Node 中继恢复 | 自动重连，连接状态恢复 | 重新启动 index.js |
| 用户划掉 App | 进程被杀死，无法收到提醒 | 多任务划掉 App → send-tap |
| 用户强停 App | 无法收到提醒 | 系统设置强行停止 → send-tap |
| App 重建（进程被杀后重开） | Foreground Service 正常启动，连接恢复 | 划掉 → 重新打开 App |
| 多种系统版本（26/33/34） | 正常编译运行 | 真机测试 |

---

## O. 阶段 4 风险和建议

### 风险

| 风险 | 等级 | 说明 | 应对 |
|------|------|------|------|
| 厂商系统杀掉 Foreground Service | 🔴 高 | 华为/小米等可能杀死非白名单 App 的 Foreground Service | 设置页明确提示，不承诺必达 |
| 用户反感常驻通知 | 🟡 中 | 部分用户可能认为常驻通知是骚扰 | 常驻通知提供简洁文案，优先级设为 LOW |
| 通知权限被用户关闭 | 🟡 中 | 关闭后 Foreground Service 可能仍存活但无提醒 | 设置页显示通知状态，引导开启 |
| Android 14+ 行为变化 | 🟡 中 | 未来 Android 版本可能进一步限制 Foreground Service | 跟踪 Android 版本更新，及时调整 |
| WSS 在后台静默断开 | 🟡 中 | 长期熄屏后 WSS 可能断开且重连失败 | 重连退避策略，App 回到前台时强制检查 |
| 电池续航影响 | 🟢 低 | WSS 长连接 + 常驻通知会略微增加耗电 | Foreground Service 本身无需额外电量优化，保持心跳间隔合理 |

### 建议

1. **常驻通知优先级设为 LOW**（`NotificationCompat.PRIORITY_LOW`），不在顶部持续弹出，减少用户视觉干扰。

2. **常驻通知文案精简**：
   - 标题："电子木鱼"
   - 内容："已连接"
   - 或者隐藏式折叠，用户展开才能看到。

3. **常驻通知不宜可滑动清除**（`setOngoing(true)`），防止用户误删导致 Service 被系统杀死。

4. **设置页提示**：
   - "后台提醒需要保持 App 在后台运行"
   - "部分手机系统的省电模式可能会限制后台提醒"
   - "如长时间收不到提醒，请在系统设置中检查：通知权限、省电策略"

5. **不承诺任何可靠性**：
   - 第一版文档/设置页中明确："后台提醒受系统省电策略影响，不保证必达"
   - 用户手册中明确列出已知限制

6. **阶段 4 不需要处理"App 被杀死后重启 Service"**：
   - `START_STICKY` 在某些系统上不可靠
   - 第一版不做被杀后重建

7. **测试顺序建议**：
   1. 前台收到 tap → ✅
   2. 前台切后台 30 秒内发 tap → ✅
   3. 前台切后台 5 分钟后发 tap → ⚠️ 记录结果
   4. 熄屏后发 tap → ⚠️ 记录结果
   5. 通知权限关闭 → 验证提示文字
   6. 用户划掉 App → 验证收不到（告知用户这是预期行为）

---

## 确认清单

请在确认后回复以下内容：

1. 是否接受 `foregroundServiceType="dataSync"` 方案
2. 是否接受 Foreground Service 作为阶段 4 的必选方案
3. 常驻通知优先级是否接受 `PRIORITY_LOW`
4. 常驻通知文案：标题 "电子木鱼"，内容 "已连接"
5. Tap 提醒通知内容："收到一次功德提醒"
6. 通知渠道名称："功德提醒"，重要性 `IMPORTANCE_HIGH`
7. 是否接受"不承诺后台必达"的定位
8. 是否同意阶段 4 不处理"进程被杀后重建"
9. 是否同意开始编码实现阶段 4