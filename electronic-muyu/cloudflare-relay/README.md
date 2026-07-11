# 电子木鱼安全 Relay

Cloudflare Workers + Durable Objects 实现的协议 v1 relay。完整协议与威胁模型见 [SECURE_QR_PAIRING_DESIGN.md](../SECURE_QR_PAIRING_DESIGN.md)。

## 本地验证

需要 Node.js 22+：

```powershell
npm ci
npm test
npm run check
npm run test:integration
```

`npm run check` 包含协议单测和 `wrangler deploy --dry-run`；integration 启动本地 Miniflare，执行并发单次 join、双确认、认证、密文转发、重放、重连、撤销、取消与日志脱敏检查。

## API 和 Durable Objects

- `InvitationSession`：120 秒一次性邀请和双 SAS 确认。
- `SecurePair`：两个已注册设备、token hash、WebSocket 认证和密文 relay counter。
- `RequestRateLimiter`：按操作/网络隔离的限流桶。
- `PairRoom`：隔离的旧协议，仅显式 `ALLOW_LEGACY=true` 可用。

公开入口为 `/health`、`/v1/invites...`、`/v1/pairs...` 和 `/v1/socket?pair=...`。`/health` 不返回绑定、环境变量或秘密。

## 生产配置

`wrangler.jsonc` 已设置：

```json
"workers_dev": false,
"preview_urls": false,
"ALLOW_LEGACY": "false"
```

仓库不包含 Cloudflare API token、真实私有配置或生产域名。发布人员应在自己的 Cloudflare 账号中配置自定义域名/route，再人工执行部署；不要为方便测试重新打开 workers.dev、preview URL 或 legacy。

自定义域名健康检查：

```text
https://relay.example.com/health
```

正式 Android 构建配置使用 HTTPS base URL，App 自动转换为：

```text
wss://relay.example.com/v1/socket?pair=<opaquePairId>
```

域名不是密码。Cloudflare 仍可观察客户端 IP、连接时间、请求路径中的不透明 ID、密文长度和流量模式，但 Worker 不持有端到端消息密钥。

## 部署前检查

1. Node 22/24 LTS；`npm ci` 无漏洞错误。
2. `npm run check` 和 `npm run test:integration` 通过。
3. 确认 Durable Object `v2-secure-pairing` migration 不变且 bindings 完整。
4. 确认 `ALLOW_LEGACY=false`、workers.dev/preview 关闭。
5. 先部署 Worker 并检查 `/health`，再用同一 HTTPS 域名构建 Android。

本仓库任务不会自动执行生产部署或修改 Dashboard。
