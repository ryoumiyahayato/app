# 电子木鱼 — Node.js 本地测试 WSS 中转服务

## ⚠️ 仅限本地开发测试

此服务用于阶段 3 的本地 WSS 联调测试，**不是正式后端**。

正式环境必须使用 `wss://`（Cloudflare Worker + Durable Object）。

## 启动方式

```bash
cd server
npm install
npm start
```

默认端口：`8443`

## 连接方式

### 真机测试（手机与电脑在同一局域网）

```
ws://<电脑IP>:8443?room=test-room
```

例如：`ws://192.168.1.23:8443?room=test-room`

### Android 模拟器测试

```
ws://10.0.2.2:8443?room=test-room
```

## 固定测试参数

- room: `test-room`（阶段 3 固定使用，不做配对）
- pairId: `test-room`（与 room 相同，阶段 3 固定）

## 功能限制

1. 不做配对系统
2. 不做 session/认证
3. 不做持久化
4. 不做离线缓存
5. 不做历史消息
6. 只转发 tap 事件
7. 对方离线时丢弃 tap

## 正式环境注意事项

本地测试使用 `ws://`，但正式环境必须使用 `wss://`。

不要在正式环境复用此服务。