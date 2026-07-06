# 电子木鱼 — WebSocket 中继服务

本地开发 / 公网测试两用的最小 WebSocket relay，用于在两台 Android 设备之间转发 tap 事件。

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
| `RELAY_TOKEN` | （空） | 可选，设置后客户端必须传递 `&token=xxx` |

## 连接方式

### 本地测试

```
ws://localhost:8443?room=test-room
```

### 同一局域网真机测试

```
ws://<电脑IP>:8443?room=test-room
```

### 公网部署

```
ws://<公网IP或域名>:<端口>?room=约定房间名
```

或使用 wss 反向代理（如 Nginx + Let's Encrypt）：

```
wss://<域名>?room=约定房间名
```

## Room 规则

- `room` 参数必须提供，否则连接将被拒绝（code 4000）。
- 不同 room 之间的消息完全隔离。
- 同一 room 内的所有客户端会收到彼此发送的 tap。
- 发送方不会收到自己的 tap。

## 可选 RELAY_TOKEN

如果设置了 `RELAY_TOKEN=my-secret` 环境变量，客户端连接时必须传递相同 token：

```
ws://host:8443?room=test-room&token=my-secret
```

本地开发默认不设置 token，方便调试。

## 发送测试 tap

```bash
# 使用默认参数（ws://localhost:8443, room=test-room, device=test-client）
npm run send

# 或直接
node send-tap.js

# 指定房间
$env:ROOM_ID="test-room-2"; node send-tap.js

# 指定公网地址
$env:WS_URL="wss://example.com"; $env:ROOM_ID="test-room"; node send-tap.js
```

## Android App 配置

在 Android App 设置页中填写：

| 字段 | 值 |
|---|---|
| 服务器地址 | `ws://你的IP或域名:端口` |
| 房间 ID | 与另一方约定的房间名 |

例如：
- 服务器地址：`ws://192.168.1.23:8443`
- 房间 ID：`my-room`

App 会自动拼接为 `ws://192.168.1.23:8443?room=my-room`。

## 公网部署注意事项

1. 默认使用 `ws://` 明文传输。公网建议用 Nginx 反向代理加 `wss://`（WebSocket over TLS）。
2. 此服务**不做持久化**，**不做离线补发**，**不做用户认证**（不含显式设置 RELAY_TOKEN 的 token 校验）。
3. 多人同时使用同一 room 会互相收到 tap，因此只推荐两人私下约定 room 使用。
4. 公网部署建议设置 `RELAY_TOKEN` 防止他人随意连入 room。

## 不做什么

- 不做数据库
- 不做账号系统
- 不做历史消息
- 不做离线补发
- 不做二维码 / 扫码配对
- 不做多人管理 UI
