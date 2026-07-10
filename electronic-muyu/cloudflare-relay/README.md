# 电子木鱼 Cloudflare Relay

正式公网中继实现，运行在 Cloudflare Workers + Durable Objects 上。

## 架构

```text
Android A ── wss:// ── Cloudflare Worker ── PairRoom Durable Object
Android B ── wss:// ── Cloudflare Worker ── PairRoom Durable Object
```

- 每个 `room` 映射到一个 Durable Object。
- 每个 room 最多允许两条在线 WebSocket 连接。
- 只转发合法 `tap`，发送方不会收到自己的消息。
- 不同 room 完全隔离。
- 不保存历史，不离线补发，不提供聊天或账号系统。
- 使用 Durable Objects WebSocket Hibernation API；空闲期间对象可以休眠，连接仍由 Cloudflare 保持。

## 协议和限制

连接地址：

```text
wss://<Worker 子域名>/?room=<房间ID>
```

Android 设置页只填写不含 room 的服务器地址：

```text
wss://electronic-muyu-relay.<账户子域>.workers.dev
```

App 会安全追加 room 参数。

当前限制：

- room：1～64 个字符，不能包含控制字符。
- 每个 room 最多两条连接。
- 单条消息最大 4096 bytes。
- 每个连接每 10 秒最多 60 条消息。
- 二进制消息关闭码 `1003`。
- 超大消息关闭码 `1009`。
- 无效 room 关闭码 `4000`。
- token 不匹配关闭码 `4001`。
- room 已满关闭码 `4002`。
- 频率超限关闭码 `4008`。

合法 tap：

```json
{
  "type": "tap",
  "pairId": "约定房间名",
  "deviceId": "设备ID",
  "timestamp": 1234567890
}
```

## Cloudflare 网页部署

不需要在本机安装 Wrangler 或执行命令行。

1. 登录 Cloudflare Dashboard。
2. 进入 `Workers & Pages`。
3. 选择创建 Worker，并选择从 GitHub 仓库导入。
4. 连接 GitHub 后选择私有仓库 `ryoumiyahayato/app`。
5. 使用以下配置：

| 字段 | 值 |
|---|---|
| Worker name | `electronic-muyu-relay` |
| Production branch | `fix/z-audit-issues-20260710` |
| Root directory | `electronic-muyu/cloudflare-relay` |
| Build command | `npm ci && npm test` |
| Deploy command | `npx wrangler deploy` |

6. 不添加 `PORT`。
7. 当前 Android 版本不要设置 `RELAY_TOKEN`。
8. 保存并部署。

Worker 名称必须与 `wrangler.jsonc` 中的 `name` 一致。

部署成功后，Cloudflare 会提供类似地址：

```text
https://electronic-muyu-relay.<账户子域>.workers.dev
```

健康检查：

```text
https://electronic-muyu-relay.<账户子域>.workers.dev/health
```

预期响应：

```json
{
  "ok": true,
  "service": "electronic-muyu-relay",
  "runtime": "cloudflare-workers-durable-objects"
}
```

手机服务器地址填写：

```text
wss://electronic-muyu-relay.<账户子域>.workers.dev
```

不要添加 `/health`，也不要添加端口。

## 当前安全边界

当前 Android 端还没有独立 token 输入和 Keystore 凭据存储，因此首次部署不要设置 `RELAY_TOKEN`。正式测试时，两台手机应使用同一个高随机度 room，建议至少 24 个随机字母和数字，例如：

```text
muyu-k7q4n9x2r8v5c3p6t1w0z4ab
```

不要使用 `test-room`、姓名、手机号、邮箱或其他可猜信息。

这种随机 room 是当前 MVP 的临时共享秘密，不等同于账号认证。后续配对系统应签发短期凭据，并由 Android Keystore 保存。

## 本地验证

需要 Node.js 22 或更高版本：

```bash
npm ci
npm run check
npm run test:integration
```

`npm run check` 会执行协议单元测试和 Wrangler dry-run；`npm run test:integration` 会启动本地 Workers/Miniflare，并实际验证健康检查、双向转发、房间隔离、连接容量和关闭码。

## 自定义域名

`workers.dev` 公网地址通过测试后，可以在 Worker 的设置中添加自有域名，并把 Android 地址改为：

```text
wss://relay.example.com
```

自定义域名不是首次公网验收的前置条件。
