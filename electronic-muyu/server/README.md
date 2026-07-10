# 电子木鱼 — WebSocket 中继服务

本地开发和短期公网联调两用的最小 WebSocket relay，用于在两台 Android 设备之间转发 tap 事件。

它不保存历史、不做离线补发，也不是聊天或账号后端。

运行时要求：Node.js 22 或更高版本，并优先使用仍处于 LTS 支持期的版本。

## 快速本地启动

```bash
cd server
npm ci
npm start
```

仓库已包含 `package-lock.json`，正常情况下必须使用 `npm ci`，不得用未锁定的依赖结果替代验收基线。

默认监听：

```text
ws://0.0.0.0:8443
```

健康检查：

```text
GET http://localhost:8443/health
```

预期响应：

```json
{"ok":true,"service":"electronic-muyu-relay"}
```

非 `/health` 的普通 HTTP 请求返回 `426 Upgrade Required`。

## 环境变量

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `PORT` | `8443` | 1～65535 范围内的监听端口 |
| `RELAY_TOKEN` | 空 | 可选共享 secret；设置后客户端必须提供相同 token |
| `MAX_TOTAL_CONNECTIONS` | `100` | relay 总连接数上限，范围 1～10000 |
| `MAX_MESSAGES_PER_WINDOW` | `60` | 每个连接在 10 秒窗口内允许的消息数，范围 1～1000 |
| `HEARTBEAT_INTERVAL_MS` | `30000` | ping/pong 心跳间隔，范围 100～300000 毫秒 |

当前 Android 端尚未提供安全 token 输入，因此 6B 初次公网联调不得设置 `RELAY_TOKEN`。

## npm 命令

```bash
npm start          # 启动 relay
npm run send       # 发送一次测试 tap
npm run client     # 启动交互式测试客户端
npm test           # 执行 relay 自动回归脚本
```

这些测试命令不会自动启动 relay。执行前应先在另一个终端运行 `npm start`。

## 连接方式

本地：

```text
ws://localhost:8443?room=test-room
```

同一局域网真机：

```text
ws://<电脑IP>:8443?room=test-room
```

短期公网联调：

```text
ws://<公网IP>:<端口>?room=约定房间名
```

长期使用必须通过反向代理和有效证书切换为：

```text
wss://<域名>?room=约定房间名
```

## Room 和连接限制

- `room` 必须提供。
- room 长度为 1～64 个字符，不能包含控制字符。
- 不同 room 完全隔离。
- 每个 room 最多 2 个在线连接。
- relay 总连接数上限为 100。
- 发送 socket 不会收到自己的 tap。
- 对方离线时 tap 直接丢弃。
- 服务端每 30 秒执行一次 ping/pong 心跳，失活连接会被清理，避免长期占用房间名额。

主要关闭码：

| code | 含义 | Android 行为 |
|---|---|---|
| `4000` | room 无效 | 停止 Service，不自动重连 |
| `4001` | token 错误 | 停止 Service，不自动重连 |
| `4002` | room 已有两台设备 | 停止 Service，不自动重连 |
| `4003` | relay 总连接数已满 | 停止 Service，不自动重连 |
| `4008` | 消息频率超限 | 停止 Service，不自动重连 |
| `1012` | relay 正在重启 | Android 按网络断开策略重连 |

## tap 消息要求

单条 WebSocket 消息最大 4 KB。

单个连接默认每 10 秒最多发送 60 条消息；可通过 `MAX_MESSAGES_PER_WINDOW` 调整，超限后以 `4008` 关闭。默认值允许正常快速连续敲击，同时仍限制异常脚本刷消息。

合法 tap：

```json
{
  "type": "tap",
  "pairId": "test-room",
  "deviceId": "device-id",
  "timestamp": 1234567890
}
```

校验规则：

- `type` 必须为 `tap`。
- `pairId` 必须与当前连接 room 完全一致。
- `deviceId` 长度为 1～128 个字符，不能包含控制字符。
- `timestamp` 必须是正的安全整数。
- 二进制消息、异常 JSON、未知类型和字段不合法的消息不会被转发。

## 日志边界

- 不打印完整 roomId，只打印 SHA-256 短哈希。
- 不打印完整 deviceId，只保留前后少量字符。
- 不打印完整客户端 IP，只打印短哈希。
- 消息类型和关闭原因会移除控制字符并限制长度。
- 不打印 `RELAY_TOKEN`。

## 可选 RELAY_TOKEN

设置服务端：

```bash
RELAY_TOKEN=my-secret npm start
```

测试客户端连接示例：

```text
wss://example.com?room=test-room&token=my-secret
```

当前 token 位于 URL query 中，可能出现在反向代理访问日志里。正式启用前必须确认代理不会记录完整 query，并为 Android 增加 Keystore 支持的独立凭据配置。当前 Android 普通连接配置会拒绝把 token、session、auth、api_key 等敏感 query 保存到 DataStore。

## 测试工具

### 发送一次 tap

```powershell
npm run send

$env:ROOM_ID="test-room-2"
npm run send

$env:WS_URL="ws://203.0.113.10:8443"
$env:ROOM_ID="test-room"
npm run send
```

`send-tap.js` 必须先收到匹配的 `room_info` 才发送 tap。room 无效、room 已满或 relay 拒绝连接时会以非零状态退出，避免误报成功。

### 交互式客户端

```powershell
npm run client -- test-room
```

relay 接受连接后，按 Enter 发送 tap，输入 `q` 后回车退出。

### 自动回归

```powershell
npm test
```

当前脚本定义以下场景：

1. `/health` 返回 200，普通 HTTP 路径返回 426。
2. 达到 relay 总连接上限后新连接被 `4003` 拒绝。
3. 两台同房间客户端连接，双向 tap 转发。
4. 发送方不回显，不同 room 不串消息。
5. 第三台设备被 `4002` 拒绝。
6. 错误 pairId、未知消息和异常 JSON 不会转发。
7. 空 room 被 `4000` 拒绝。
8. 设置 `RELAY_TOKEN` 运行测试时，错误 token 被 `4001` 拒绝。
9. 二进制和超过 4 KB 的消息分别以 `1003`、`1009` 关闭。
10. 断开后房间名额可重用并继续转发。
11. 超过配置的消息速率后以 `4008` 关闭。

回归脚本需要与 relay 使用相同的 `RELAY_TOKEN`、`MAX_TOTAL_CONNECTIONS` 和
`MAX_MESSAGES_PER_WINDOW`、`HEARTBEAT_INTERVAL_MS` 环境变量。为了缩短本地容量和
心跳测试，可在两个终端都设置：

```powershell
$env:MAX_TOTAL_CONNECTIONS="12"
$env:HEARTBEAT_INTERVAL_MS="200"
```

当心跳间隔不超过 2 秒时，自动回归会额外验证不回应 ping 的连接被终止；默认 30 秒
运行时跳过该耗时场景。

## Android App 配置

设置页填写：

| 字段 | 示例 |
|---|---|
| 服务器地址 | `ws://192.168.1.23:8443` |
| 房间 ID | `test-room` |

App 会安全追加并编码 room 参数；服务器地址已有普通非敏感 query 时不会生成第二个 `?`。

`ws://` 只用于本地或短期公网联调。长期公网地址必须使用 `wss://`。

## 6B 短期公网联调边界

1. 不设置 `RELAY_TOKEN`，因为当前 Android 尚未支持安全 token 存储。
2. 只临时放行 relay 端口。
3. 先验证 `/health`，再连接手机。
4. 验证相同 room 互通、不同 room 隔离、后台和锁屏通知、网络切换重连。
5. 测试结束后关闭公网端口。
6. 不把明文、无鉴权的 `ws://` relay 长期暴露到公网。

## 明确不做

- 数据库
- 账号系统
- 历史消息
- 离线补发
- 聊天
- 二维码或扫码配对
- 多人房间管理 UI
