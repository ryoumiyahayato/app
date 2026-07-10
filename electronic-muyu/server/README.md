# 电子木鱼 — WebSocket 中继服务

本地开发 / 短期公网联调两用的最小 WebSocket relay，用于在两台 Android 设备之间转发 tap 事件。

## 快速本地启动

```bash
cd server
npm install
npm start
```

默认监听 `ws://0.0.0.0:8443`。

浏览器访问 `http://localhost:8443/health` 验证服务是否运行，应返回：

```json
{"ok":true,"service":"electronic-muyu-relay"}
```

## 配置方式

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `PORT` | `8443` | 监听端口 |
| `RELAY_TOKEN` | （空） | 可选，设置后客户端必须提供相同 token |

当前 Android 端尚未提供 token 输入，因此 6B 初次公网联调不得设置 `RELAY_TOKEN`。

## 连接方式

### 本地测试

```
ws://localhost:8443?room=test-room
```

### 同一局域网真机测试

```
ws://<电脑IP>:8443?room=test-room
```

### 公网短期联调

```
ws://<公网IP或域名>:<端口>?room=约定房间名
```

长期使用应通过反向代理切换为：

```
wss://<域名>?room=约定房间名
```

## Room 规则

- `room` 参数必须提供且长度不超过 64 个字符，否则连接被拒绝（code 4000）。
- 不同 room 之间的消息完全隔离。
- 每个 room 最多允许 2 个在线连接；第 3 个连接被拒绝（code 4002）。
- 发送方不会收到自己的 tap。
- room 仅以短哈希形式写入服务端日志。

## 消息保护

- 单条 WebSocket 消息最大 4 KB。
- 单个连接每 10 秒最多发送 20 条消息，超限后断开（code 4008）。
- 仅接受合法 tap：
  - `type` 必须为 `tap`
  - `pairId` 必须与连接 room 一致
  - `deviceId` 必须是 1～128 个字符
  - `timestamp` 必须是正的安全整数
- 异常 JSON 和不合法消息会被忽略，不转发。

## 可选 RELAY_TOKEN

如果设置了 `RELAY_TOKEN=my-secret`，客户端连接时必须传递相同 token：

```
ws://host:8443?room=test-room&token=my-secret
```

本地开发默认不设置 token。公网无加密、无鉴权的 `ws://` 只能用于短期功能验证。

## 发送测试 tap

```bash
# 使用默认参数（ws://localhost:8443, room=test-room, device=test-client）
npm run send

# 或直接
node send-tap.js

# 指定房间
$env:ROOM_ID="test-room-2"; node send-tap.js

# 指定公网地址
$env:WS_URL="ws://203.0.113.10:8443"; $env:ROOM_ID="test-room"; node send-tap.js

# 未来使用 WSS
$env:WS_URL="wss://example.com"; $env:ROOM_ID="test-room"; node send-tap.js
```

`send-tap.js` 会保留 `WS_URL` 中已有的 query，安全写入 room 参数，并隐藏日志中的 roomId 和完整 deviceId。

## Android App 配置

在 Android App 设置页中填写：

| 字段 | 值 |
|---|---|
| 服务器地址 | `ws://你的IP或域名:端口` |
| 房间 ID | 与另一方约定的房间名 |

例如：

- 服务器地址：`ws://192.168.1.23:8443`
- 房间 ID：`my-room`

App 会安全追加并编码 room 参数；服务器地址已有其他 query 时不会生成第二个 `?`。

## 公网部署注意事项

1. 默认 `ws://` 是明文传输，只允许短期联调；长期使用必须切换到 `wss://`。
2. 服务不做持久化、不做离线补发、不保存 tap 历史。
3. 对方离线时 tap 直接丢弃。
4. 初次 6B 联调保持 `RELAY_TOKEN` 关闭；Android 增加 token 配置后再启用。
5. 测试结束后关闭临时公网端口。

## 不做什么

- 不做数据库
- 不做账号系统
- 不做历史消息
- 不做离线补发
- 不做二维码 / 扫码配对
- 不做多人管理 UI
