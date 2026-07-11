# 电子木鱼安全扫码配对设计（协议 v1）

## 目标与安全边界

v0.7 用一次性二维码、人工核对的 6 位安全码、设备独立凭据和端到端加密替代手工 `wsUrl + roomId`。普通用户不再输入或传递服务器地址、room 或 token。公网域名不是秘密；安全性来自一次性邀请、SAS 核对、设备认证和端到端密钥。

保护目标：

- 二维码最多有效 120 秒，成功 join、取消或过期后不能再次兑换。
- 只有两台完成 SAS 双确认的设备能成为正式成员。
- Worker 只持有 access token 的 SHA-256 摘要，只转发 AES-256-GCM 密文。
- 私钥、access token、方向消息密钥只以 Android Keystore AES-GCM 包装后的 blob 落盘。
- 重放或认证失败的消息不触发计数、声音、振动或通知。

不在保护范围内：已被完全控制的终端、用户忽略安全码不一致、操作系统/Cloudflare 可见的 IP、连接时间、密文长度和流量模式。端到端加密不等于元数据不可见。

## 组件与数据模型

Worker 使用四类 Durable Object：

- `InvitationSession`：邀请 secret hash、两端公钥、临时 session token hash、双确认状态和 120 秒 alarm。
- `SecurePair`：两个设备 ID、公钥、access token hash、状态和每发送者最后 relay counter。
- `RequestRateLimiter`：按操作和网络短哈希限流。
- `PairRoom`：仅供显式 `ALLOW_LEGACY=true` 的隔离旧协议；默认不可路由。

Android 普通 DataStore 只保存非敏感 `PairMetadata`、Keystore 加密 blob、发送/接收 counter 和偏好。加密 blob 内含软件生成 P-256 私钥、独立 access token、send key、receive key。

## 一次性 QR 格式

```json
{
  "v": 1,
  "type": "electronic-muyu-pair",
  "inviteId": "<16-byte base64url>",
  "inviteSecret": "<32-byte base64url>",
  "inviterPublicKey": "<P-256 SPKI base64url>",
  "expiresAt": 0
}
```

QR JSON 最大 2048 bytes，字段集合必须完全一致，base64url 必须无填充且规范化。QR 不包含 relay URL、长期 token、私钥或长期会话密钥；扫描器只启用 QR Code，不打开链接、不写剪贴板、不记录原文。ML Kit 模型随 APK 打包，首次扫描不依赖在线模型下载。

## 配对时序

1. A 生成 `inviteId`、`inviteSecret` 和临时 P-256 ECDH key pair；向 Worker 发送 secret hash。
2. Worker 返回只属于 A 的临时 owner session token 和服务端到期时间。
3. B 扫描 QR，生成自己的 P-256 key pair，用 invite secret 兑换一次。并发 join 通过 DO transaction 只允许一个成功。
4. Worker 生成 128-bit `pairId` 和 B 的临时 join session token，并向两端返回完整 transcript。
5. 两端验证 transcript 与本地角色、公钥、QR 邀请一致，计算并显示同一 6 位 SAS。
6. 两端各自生成不同的 256-bit access token，仅提交其 SHA-256 摘要并分别确认 SAS。
7. 只有两个确认都存在时，Invitation DO 初始化 SecurePair DO。两端随后把本地秘密写入 Keystore 包装 blob，并自动连接。
8. 任一方拒绝、A 取消或邀请超时，临时状态失效；不生成长期本机配对。

进程在“已向服务器确认、尚未本机保存”窗口被终止时，临时 token 可能无法恢复。此时不能绕过 SAS 或恢复明文凭据，用户需在邀请过期后重新配对。

## SAS 规范化

依次编码以下五个字段，每个字段前置 4-byte big-endian 长度：

1. UTF-8 `"1"`
2. 16-byte inviteId
3. 邀请方 91-byte P-256 SPKI
4. 加入方 91-byte P-256 SPKI
5. 16-byte pairId

对结果做 SHA-256，取前 20 bit，模 1,000,000 后显示为零填充 6 位十进制。Android 与 Worker 单测共享固定向量 `359919`。

## 密钥派生与消息加密

两端先做 P-256 ECDH，再使用 HKDF-SHA256：

```text
root = HKDF(sharedSecret, salt=pairIdBytes, info="electronic-muyu-root-v1")
slot0Key = HKDF(root, empty, "electronic-muyu-send-slot-0-v1")
slot1Key = HKDF(root, empty, "electronic-muyu-send-slot-1-v1")
```

slot 0 的 send key 是 slot0Key、receive key 是 slot1Key；slot 1 相反。原始 ECDH secret 不直接作为 AES key。

消息 envelope：

```json
{
  "type": "encrypted_tap",
  "version": 1,
  "pairId": "...",
  "sender": "...",
  "counter": 1,
  "iv": "<12-byte base64url>",
  "ciphertext": "<AES-256-GCM ciphertext+tag>"
}
```

明文只有 `{"type":"tap","timestamp":...}`。AAD 对 version、pairId、sender 和 8-byte big-endian counter 分别做 4-byte 长度前缀编码。IV 为 `HMAC-SHA256(sendKey, AAD)` 前 12 bytes；方向密钥和持久化单调 counter 保证同一密钥下不重复。接收端先验证 IV 与 AES-GCM tag，再原子接受严格递增 counter，避免伪造高 counter 推进重放窗口。

Worker 不解析 ciphertext、不保存消息历史、不提供离线队列。Worker 自己也持久化每发送者 relay counter，重复或回退消息以 4414 关闭。

## WebSocket 设备认证

地址为 `WSS /v1/socket?pair=<opaquePairId>`。token 只放第一条业务 JSON，不进入 URL：

```json
{"type":"auth","version":1,"pairId":"...","deviceId":"...","token":"..."}
```

成功返回：

```json
{"type":"auth_ok","version":1,"slot":0,"timestamp":0}
```

Android 只有收到并严格校验 `auth_ok` 后才进入“已连接”。未认证 socket 最多保留 5 秒、不占两个正式槽位、不能收发 tap；同 deviceId 的第二条正式连接被拒绝。网络切换使用带 25% 随机抖动的指数退避，并在每次重连后重新认证。

关闭码：

| Code | 含义 |
|---:|---|
| 4400 | 非法请求、非法 JSON 或非密文业务消息 |
| 4401 | 设备或 token 认证失败 |
| 4403 | pair 不存在、不可用或已撤销 |
| 4408 | pending/auth/message 频率或容量限制 |
| 4409 | 相同 deviceId 已在线 |
| 4410 | 5 秒认证超时 |
| 4414 | counter 重放/回退 |
| 1003 | 只允许文本消息 |
| 1009 | 消息超过上限 |

## HTTP API

- `POST /v1/invites`
- `POST /v1/invites/{inviteId}/join`
- `POST /v1/invites/{inviteId}/confirm`
- `POST /v1/invites/{inviteId}/cancel`
- `DELETE /v1/pairs/{pairId}/devices/{deviceId}`
- `GET /health`
- `WSS /v1/socket?pair=<pairId>`

JSON 接口执行方法、Content-Type、16 KiB body、严格字段、长度和 base64url 校验。响应设置 `no-store`、`nosniff`、`no-referrer`；外部响应不返回内部异常。创建、join、confirm、cancel、revoke 和 socket auth 分别限流。

## 撤销与迁移

任一已配对设备可用自己的 body token 调用 DELETE；Pair DO 原子设为 disabled、清空两个 token hash 并以 4403 关闭在线 socket。Android 仅在服务器确认撤销（或明确 404/410）后删除 DataStore 配对、Keystore alias 和内存密钥，避免网络失败造成无法撤销的孤立凭据。

首次启动 v0.7 会检测旧 `ws_url/room_id`，不自动连接、不转换为 pairId/token，并提示“旧连接方式不再安全”。含敏感 query 的旧 URL 会立即从普通 DataStore 清除；新配对保存时原子删除所有旧字段。Worker 旧协议只有 `ALLOW_LEGACY=true` 才路由，生产必须保持 false。

## 日志与隐私

应用日志只记录操作、状态、关闭码、非敏感计数和 ID 的前 8 个十六进制短哈希。不记录 QR、invite secret、session/access token、token hash 全值、私钥、shared/root/message keys、完整 pair/device ID 或解密结果。Wrangler observability 默认关闭；平台仍可观察网络元数据。

## 已知限制

- 无离线投递、账号恢复、密钥轮换、多设备组或历史同步。
- 6 位 SAS 依赖用户通过独立渠道认真核对。
- 被完全控制的终端可读取显示内容或发送合法加密消息。
- ROM 省电策略、Android 15/16 长时间 `remoteMessaging`、跨 Wi-Fi/蜂窝和锁屏通知必须真机验证。
- Miniflare 会在 Worker 侧执行 alarm 关闭，但本地 Node WebSocket 客户端可能不及时收到 alarm 发出的 close frame；生产 close frame 仍需部署后验证。
