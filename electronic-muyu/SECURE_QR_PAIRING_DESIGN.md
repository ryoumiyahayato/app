# 电子木鱼安全扫码配对设计

更新时间：2026-07-10

## 目标

下一版不再要求普通用户手工填写或转发服务器地址、room ID 或长期凭据。

用户流程改为：

1. 设备 A 点击“创建配对二维码”。
2. 设备 B 点击“扫描二维码”。
3. 两台设备建立一次性配对会话。
4. 两台设备显示同一组 6 位安全码；用户当面或通过语音核对。
5. 双方确认后完成配对，此后自动连接。

公网域名不是秘密。安全性不得依赖隐藏域名或隐藏 Worker 地址，而应依赖一次性邀请、独立设备凭据和端到端加密。

## 威胁模型

必须防止：

- 第三方猜测房间名后加入连接。
- 第三方抢占两个连接名额。
- 第三方伪造 tap。
- 配对二维码被截图、转发或由消息平台读取后形成长期凭据泄漏。
- Cloudflare relay 或日志读取 tap 明文。
- 重放过去的 tap 消息。
- 长期 token 出现在 URL query、截图或普通日志。

暂不承诺防止：

- 已解锁且被完全控制的 Android 设备读取正在使用的会话数据。
- 用户忽略安全码不一致警告后主动确认错误设备。
- 操作系统、输入法或恶意无障碍服务截取界面。

## 配对二维码

二维码只包含短期邀请数据，不包含长期设备 token，也不包含端到端私钥。

建议格式：

```json
{
  "v": 1,
  "type": "electronic-muyu-pair",
  "inviteId": "128-bit random base64url",
  "inviteSecret": "256-bit random base64url",
  "inviterPublicKey": "P-256 public key SPKI base64url",
  "expiresAt": 0,
  "relay": "https://relay.example.com"
}
```

约束：

- 有效期 2 分钟。
- 只能成功兑换一次。
- 成功或超时后立即失效。
- `inviteSecret` 只用于兑换邀请，不能作为长期连接凭据。
- 即使二维码经过消息平台转发，也必须在配对后核对安全码。

## 设备密钥和本地存储

- 每台设备生成独立 P-256 ECDH 密钥对。
- 为兼容 minSdk 26，软件生成的私钥使用 Android Keystore 中不可导出的 AES-GCM 包装密钥加密后保存。
- Keystore 包装密钥不得备份或导出。
- DataStore 只保存非秘密元数据和密文：pairId、peer public key、加密后的设备 token、计数器。
- 卸载应用或执行“解除配对”时删除包装密钥和全部配对材料。

## 安全码

双方拿到完整握手 transcript 后计算：

```text
SAS = first_20_bits(SHA-256(
  protocolVersion || inviteId || inviterPublicKey || joinerPublicKey || pairId
))
```

显示为零填充的 6 位十进制数字。

双方必须看到相同安全码并分别点击“代码一致”。任何一方拒绝或超时，配对状态必须删除。

## 端到端密钥

两台设备使用 ECDH 计算共享秘密，再使用 HKDF-SHA256 派生：

```text
rootKey    = HKDF(sharedSecret, salt=pairId, info="muyu-root-v1")
messageKey = HKDF(rootKey, info="muyu-message-v1")
```

Worker 不获得 `sharedSecret`、`rootKey` 或 `messageKey`。

## 设备认证

每台设备拥有不同的 256-bit 随机 access token：

- token 不放入二维码。
- token 不放入 WebSocket URL。
- token 通过 TLS 配对接口下发或由设备生成后注册。
- Worker 只保存 token 的 SHA-256 哈希。
- WebSocket 建立后，第一条业务消息必须是 `auth`。
- 未认证连接不得占用正式设备槽位、发送消息或接收消息。

认证消息：

```json
{
  "type": "auth",
  "pairId": "opaque pair id",
  "deviceId": "opaque device id",
  "token": "base64url token"
}
```

认证成功后 Worker 回复：

```json
{
  "type": "auth_ok",
  "slot": 0,
  "timestamp": 0
}
```

## 加密 tap

Worker 只转发密文包：

```json
{
  "type": "encrypted_tap",
  "pairId": "opaque pair id",
  "sender": "opaque device id",
  "counter": 1,
  "iv": "12-byte base64url",
  "ciphertext": "AES-256-GCM ciphertext plus tag"
}
```

明文 tap：

```json
{
  "type": "tap",
  "timestamp": 0
}
```

AES-GCM AAD 固定包含：

```text
protocolVersion || pairId || sender || counter
```

每个发送方使用单调递增 64-bit counter。接收方拒绝不大于已接受 counter 的消息，阻止重放。

## Worker 数据模型

### Invitation Durable Object

保存最多 2 分钟：

- inviteId
- inviteSecretHash
- inviter public key
- inviter pending connection
- expiry
- redeemed flag

### Pair Durable Object

持久保存：

- pairId
- 两个 deviceId
- 两个 access token hash
- 两个 public key
- paired/disabled 状态

不保存：

- ECDH 私钥
- rootKey/messageKey
- tap 明文
- 通知内容
- 消息历史

## 路由

```text
POST /v1/invites
POST /v1/invites/{inviteId}/join
POST /v1/invites/{inviteId}/confirm
DELETE /v1/pairs/{pairId}/devices/{deviceId}
GET /health
WSS /v1/socket?pair=<opaquePairId>
```

`pair` 不是秘密，但应为至少 128-bit 随机值。长期 token 始终通过首条认证消息发送，不进入 query。

## Android 界面

普通设置页移除：

- 服务器地址输入框。
- room ID 输入框。
- 手工连接配置。

新增：

- “创建配对二维码”。
- “扫描对方二维码”。
- “等待对方确认”。
- 6 位安全码确认页。
- 已配对设备状态。
- “解除配对”。
- “重新配对”。

自托管服务器地址只保留在开发者/高级设置中，默认折叠且明确标记为高级功能。

## 二维码扫描实现

优先使用 ML Kit bundled barcode scanner，只启用 QR_CODE 格式：

- 模型随 APK 提供，首次扫描不依赖动态下载。
- 扫描在设备本地完成。
- 扫描结果只交给配对解析器，不自动打开 URL。
- 相机权限仅在用户进入扫描页时请求。

## 迁移

现有手工 `wsUrl + roomId` 配置标记为 legacy：

1. 新版本首次启动不自动连接 legacy room。
2. 显示“旧连接方式不再安全，请重新扫码配对”。
3. 用户完成新配对后删除旧 room 配置。
4. Worker 停止接受未认证的旧版 tap 前，保留一个短迁移窗口。
5. 迁移结束后删除旧协议。

## 验收标准

- 二维码过期后无法兑换。
- 同一二维码不能被第二次兑换。
- 未认证 WebSocket 不能占用正式槽位。
- 错误 token 不能加入或接收消息。
- Worker 日志不出现 token、二维码内容、私钥、共享密钥或 tap 明文。
- 两台设备核对安全码后可自动重连。
- Worker 只能观察密文长度、pairId、设备槽位和时间。
- 重放同一 counter 的密文被接收端拒绝。
- 解除配对后旧 token、旧二维码和旧密文全部失效。
- 一台设备 Wi-Fi、另一台移动数据时可正常互联。
