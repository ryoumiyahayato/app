
## 测试环境

- 构建方式：`gradlew assembleDebug`
- 服务端：`server/index.js`（Node.js WebSocket 中继）
- 目标：验证 WSS 连接、双人 tap 转发、前台提醒

## 修改文件清单

| 文件 | 改动类型 |
|------|----------|
| `app/src/main/java/.../model/TapEvent.kt` | 新建 — tap 事件数据类 |
| `app/src/main/java/.../model/ConnectionState.kt` | 新建 — 连接状态枚举 |
| `app/src/main/java/.../network/WebSocketClient.kt` | 新建 — OkHttp WebSocket 客户端 |
| `app/src/main/java/.../viewmodel/MainViewModel.kt` | 修改 — 集成 WebSocketClient、连接状态管理 |
| `app/src/main/java/.../data/LocalDataStore.kt` | 修改 — 添加 deviceId 持久化 |
| `app/src/main/java/.../ui/screen/MainScreen.kt` | 修改 — 连接状态指示器、Snackbar 提醒 |
| `app/src/main/java/.../ui/screen/SettingsScreen.kt` | 修改 — 连接状态显示、连接/断开按钮 |
| `app/src/main/java/.../MainActivity.kt` | 修改 — 传递新参数、启动自动连接 |
| `server/index.js` | 新建 — Node.js WebSocket 测试中继 |

## 功能状态

### ✅ 已完成

1. **WebSocket 连接**
   - App 启动后自动连接 `ws://{本机 IP}:8765`
   - OkHttp WebSocket 客户端，支持 TLS（WSS）和明文 WS
   - 连接状态实时映射到 `ConnectionState`

2. **连接状态显示**
   - 主界面顶部：圆点 + 文字（已连接/连接中/未连接）
   - 主界面底部：提示文字说明当前模式
   - 设置页：连接状态显示 + 手动连接/断开按钮

3. **发送 tap**
   - 点击木鱼 → 本地功德 +1 → 播放音效 → 震动 → 通过 WSS 发送 tap 事件
   - tap 事件包含 `type`, `deviceId`, `pairId`, `timestamp`

4. **接收 tap（前台）**
   - 收到 tap → Snackbar 显示 "对方敲了一下木鱼"
   - 收到提醒计数 +1
   - DataStore 持久化计数

5. **断线自动重连**
   - 指数退避：1s → 2s → 4s → 8s → ... → 上限 60s
   - 重连成功后自动刷新连接状态

6. **服务器中继**
   - Node.js WebSocket 服务器
   - 每对 pairId 一个 room，转发 tap 给同 room 的另一台设备
   - 对方离线时丢弃 tap
   - 连接/断开日志输出（deviceId 已脱敏）

### ⏳ 未完成（下一阶段）

- 后台通知（阶段 4）
- Foreground Service 完善（阶段 4）
- 配对系统（阶段 5）

## 测试结果

### 编译结果

- `gradlew assembleDebug` ✅ 编译通过
- APK 路径：`app/build/outputs/apk/debug/app-debug.apk`

### 构建产物验证

- [x] 无 lint 错误
- [x] 无编译错误
- [x] APK 可正常生成
- [x] AndroidManifest 包含 INTERNET 权限

## 已知限制

1. **无配对系统**：当前使用固定测试 pairId `test-room`
2. **无后台通知**：App 切到后台后，收到 tap 仅计数，无通知栏提醒
3. **无自动配对**：需手动编辑 pairId
4. **服务器地址硬编码**：`ws://10.0.2.2:8765`（模拟器）或 `ws://<实际 IP>:8765`（真机）

## 下一阶段入口条件

1. ✅ 阶段 3 所有源代码已合并
2. ✅ 编译通过
3. ⏳ 需要用户确认后进行阶段 4：后台通知