# 电子木鱼 6B 公网 relay 与回归验收清单

文档状态：待执行

本文件只定义测试步骤和记录格式，不代表任何测试已经通过。

## 1. 执行原则

- 先验证本地基线，再部署公网。
- 6B 初次联调不得设置 `RELAY_TOKEN`。
- 不得把 token、session、auth、api_key 等凭据写入 Android 服务器 URL。
- 公网 `ws://` 只用于短期测试，完成后立即关闭端口。
- Android 代码修改后必须重新编译和安装 APK。
- 没有两台真实设备结果时，不得填写“真机通过”。
- 每项必须记录日期、设备、网络和实际结果。

## 2. 基线信息

| 项目 | 待填写 |
|---|---|
| 测试分支 | `fix/z-audit-issues-20260710` |
| 测试 commit | 待填写 |
| Android versionName | `0.6.0` |
| Android versionCode | `1` |
| compileSdk / targetSdk | `36 / 36` |
| AGP / Gradle | `8.10.1 / 8.11.1` |
| Node 版本 | 待填写 |
| npm 版本 | 待填写 |
| 设备 A 型号 / Android / ROM | 待填写 |
| 设备 B 型号 / Android / ROM | 待填写 |
| 公网主机和平台 | 待填写 |
| 测试日期 | 待填写 |

## 3. 修改后静态与构建检查

- [ ] `git status --short` 已记录。
- [ ] 确认只有 `MuyuForegroundService` 实例化 `WebSocketClient`。
- [ ] Manifest 权限仍只有预期 5 项。
- [ ] Manifest 包含 `FOREGROUND_SERVICE_REMOTE_MESSAGING`。
- [ ] Manifest 不再包含 `FOREGROUND_SERVICE_DATA_SYNC`。
- [ ] Service 类型为 `remoteMessaging`。
- [ ] `applicationId` 仍为 `app.electronicmuyu.android`。
- [ ] AGP 为 `8.10.1`，Gradle Wrapper 为 `8.11.1`。
- [ ] Android 执行 `clean assembleDebug` 成功。
- [ ] 输出中出现 `BUILD SUCCESSFUL`。
- [ ] APK 路径存在：`app/build/outputs/apk/debug/app-debug.apk`。
- [ ] 构建产物、日志和 `node_modules` 未进入 Git 暂存区。

实际结果：

```text
待填写
```

## 4. relay 本地检查

启动：

```powershell
Set-Location .\server
npm.cmd ci
npm.cmd start
```

另一个终端执行：

```powershell
Invoke-WebRequest http://localhost:8443/health
npm.cmd test
npm.cmd run send
```

- [ ] `/health` 返回 200 和预期 JSON。
- [ ] 普通 HTTP 路径返回 426。
- [ ] `npm test` 的 6 个回归场景全部通过。
- [ ] `npm run send` 收到 `room_info` 后才发送。
- [ ] room 无效时测试工具非零退出。
- [ ] 同 room 第三连接收到 4002。
- [ ] relay 总容量拒绝使用 4003。
- [ ] 超过频率限制后使用 4008。
- [ ] 异常 JSON 不导致 server 退出。
- [ ] 二进制消息被拒绝。
- [ ] 心跳能清理断网后失活连接。
- [ ] 日志不显示完整 roomId、deviceId、客户端 IP 或 token。
- [ ] Ctrl+C 后 server 能正常退出，不残留 8443 端口。

实际结果：

```text
待填写
```

## 5. Android 单机回归

- [ ] 旧计数启动后正确恢复。
- [ ] App 刚启动立即点击不会把历史计数覆盖为 1。
- [ ] 快速连续点击时本机功德数不丢失。
- [ ] 第一次点击能正常播放声音。
- [ ] 声音开关有效，保存失败时 UI 不错误切换。
- [ ] 震动开关有效，保存失败时 UI 不错误切换。
- [ ] 清空计数同时清空本机功德、收到提醒次数和待显示提醒。
- [ ] deviceId 重启后保持一致。
- [ ] 设置页只显示 deviceId 前 8 位。
- [ ] 无效 serverUrl 不显示“配置已保存”。
- [ ] 配置成功提示只在 DataStore 事务完成后出现。
- [ ] URL 与 roomId 作为同一事务保存，不出现半保存状态。
- [ ] 超过 64 字符的 roomId 被 UI 拒绝。
- [ ] 超过 2048 字符的 serverUrl 被拒绝。
- [ ] URL 中用户名、密码和 fragment 被拒绝。
- [ ] URL 中 token/session/auth/api_key 等敏感 query 不会保存。
- [ ] 配置包含普通特殊字符时 room 参数被正确编码。
- [ ] serverUrl 已有非敏感 query 时不会生成第二个 `?`。
- [ ] 用户主动断开后不显示“自动重连中”。
- [ ] 打开系统通知设置并返回后，权限和渠道状态会刷新。

实际结果：

```text
待填写
```

## 6. 两台设备本地或局域网联调

- [ ] 两台设备相同 room 可以连接。
- [ ] A 点击后 A 本机功德 +1。
- [ ] A 点击后 B 收到提醒次数 +1。
- [ ] B 收到远端 tap 时本机功德不增加。
- [ ] 前台收到 tap：App 内提示、声音、震动正常，不弹普通系统通知。
- [ ] Activity 重建窗口收到前台 tap 后，10 秒内仍能显示一次且不重复播放。
- [ ] 超过 10 秒的旧前台 UI 事件不会重新播放。
- [ ] 后台收到 tap：普通系统通知正常。
- [ ] 锁屏收到 tap：普通系统通知正常。
- [ ] 点击普通通知返回 App。
- [ ] 两台设备使用不同 room 时不互通。
- [ ] 第三客户端不能加入已有两台设备的 room。
- [ ] 修改连接配置并重新连接后，旧 socket 不再接收消息。
- [ ] 快速切换配置不会出现双连接或双计数。
- [ ] 用户断开后常驻通知消失。

实际结果：

```text
待填写
```

## 7. 自动重连与终止性错误

- [ ] relay 临时停止后 Android 进入指数退避重连。
- [ ] 退避顺序约为 1、2、4、8、16、32、60 秒。
- [ ] 同一时刻只有一个重连任务。
- [ ] relay 恢复后连接自动恢复。
- [ ] Wi-Fi 切移动数据后可恢复。
- [ ] 移动数据切 Wi-Fi 后可恢复。
- [ ] 用户主动断开后不再重连。
- [ ] 4000 room 无效后 Service 自动停止。
- [ ] 4001 token 错误后 Service 自动停止。
- [ ] 4002 room 已满后 Service 自动停止。
- [ ] 4003 relay 已满后 Service 自动停止。
- [ ] 4008 限流后 Service 自动停止。
- [ ] 1012 server restart 后 Android 继续重连。
- [ ] 终止性错误不会留下“未连接但常驻通知仍存在”的假运行状态。

实际结果：

```text
待填写
```

## 8. 6B 公网部署

- [ ] 只部署 `server/` 必要文件。
- [ ] 使用 `npm ci` 安装依赖。
- [ ] 设置正确 `PORT`。
- [ ] 未设置 `RELAY_TOKEN`。
- [ ] 云防火墙临时放行指定 TCP 端口。
- [ ] 公网 `/health` 返回预期 JSON。
- [ ] 两台不在同一局域网的手机可以连接。
- [ ] 异地前台互收通过。
- [ ] 异地后台通知通过。
- [ ] 异地锁屏通知通过。
- [ ] 异地不同 room 隔离通过。
- [ ] 异地网络切换重连通过。
- [ ] 对方离线时 tap 被丢弃且不补发。
- [ ] 测试结束后关闭公网端口。

实际结果：

```text
待填写
```

## 9. 长时间与系统边界

- [ ] 息屏 5 分钟后仍能接收。
- [ ] 息屏 30 分钟后结果已记录。
- [ ] 连续运行超过 6 小时的结果已记录。
- [ ] Android 15/16 上未出现 dataSync 配额超时，因为 Service 已使用 `remoteMessaging`。
- [ ] 省电模式结果已记录。
- [ ] 通知权限关闭时 App 状态提示正确。
- [ ] 功德提醒渠道关闭时 App 状态提示正确。
- [ ] App 被系统回收后的实际行为已记录。
- [ ] 用户强制停止后明确不保证接收。
- [ ] 意外 Service timeout 回调后的清理结果已记录。

## 10. 发布与 Play Console 边界

- [ ] 正式发布前已切换到 `wss://`。
- [ ] 正式发布前已关闭 `usesCleartextTraffic`。
- [ ] 已在 Play Console 声明 `remoteMessaging` 前台服务类型及使用场景。
- [ ] 已确认商店审核材料与 App 实际行为一致。
- [ ] Release 构建、混淆、签名和安装已验证。

## 11. 最终结论模板

```text
测试 commit：
Android 构建：通过 / 失败
relay 本地回归：通过 / 失败
局域网双机：通过 / 失败
公网异地双机：通过 / 失败
后台通知：通过 / 失败
锁屏通知：通过 / 失败
重连：通过 / 失败
长时间 remoteMessaging Service：通过 / 失败
仍存在的问题：
是否允许进入 6C WSS：是 / 否
```
