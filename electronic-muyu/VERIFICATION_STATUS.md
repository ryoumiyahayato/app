# 电子木鱼验证状态

更新时间：2026-07-10

## 分支边界

- 工作分支：`fix/z-audit-issues-20260710`
- 草稿 PR：`#1 Verify and harden electronic-muyu relay MVP`
- `main` 尚未合并本轮修复。
- 正式部署前仍需在 Cloudflare Dashboard 完成账号侧授权和首次部署。

## 已实际通过

### Android 与原 Node relay

- Windows GitHub Actions 已完成确定性验证。
- Node.js 依赖安装、生产依赖审计、无鉴权/测试 token relay 回归通过。
- Android `lintDebug`、14 个 JVM 单元测试、debug/release 编译与 R8 通过。
- debug APK、未签名 release APK 和 mapping 已生成并校验。
- debug 允许本地明文连接，release 禁止明文连接。
- 两台真实 Android 设备在同一局域网完成连接和双向 tap 转发。
- 修改 room 后按“保存、断开、重新连接”流程，房间隔离实际通过。

### Cloudflare Workers + Durable Objects relay

当前分支包含正式公网 relay：`electronic-muyu/cloudflare-relay`。

已在 Node.js 22 环境实际完成：

- 5 个协议单元测试通过。
- Wrangler dry-run 打包通过。
- Durable Object 绑定 `ROOMS -> PairRoom` 被正确识别。
- 本地 Wrangler/Miniflare 集成测试通过。
- 实际验证 `/health`、同 room 转发、不同 room 隔离、第三连接 `4002`、无效 room `4000` 和二进制消息 `1003`。
- 锁定依赖可通过 `npm ci` 安装，生产依赖审计为 0 漏洞。

Cloudflare relay 使用 Durable Objects WebSocket Hibernation API，每个 room 对应一个 `PairRoom`，最多两条在线连接，不保存历史或离线消息。

## 可重复验证入口

### Android、Node relay 与构建产物

```powershell
Set-Location .\electronic-muyu
.\verify-local.ps1
```

### Cloudflare relay

```bash
cd electronic-muyu/cloudflare-relay
npm ci
npm run check
npm run test:integration
```

`.github/workflows/verify-cloudflare-relay.yml` 会在 GitHub Actions 中执行 Cloudflare relay 的锁定依赖安装、协议测试、Wrangler dry-run 和本地 Durable Object WebSocket 集成测试。

## Cloudflare 首次部署边界

首次部署通过 Cloudflare Dashboard 的 GitHub 集成完成：

- 仓库：`ryoumiyahayato/app`
- 分支：`fix/z-audit-issues-20260710`
- 根目录：`electronic-muyu/cloudflare-relay`
- Worker name：`electronic-muyu-relay`
- Build command：`npm ci && npm test`
- Deploy command：`npx wrangler deploy`

当前 Android 版本尚未支持安全 token 输入，因此首次部署不要设置 `RELAY_TOKEN`。公网测试必须使用高随机度 room，不使用 `test-room` 或个人信息。

## 仍需外部执行

以下项目不能由无设备 CI 或 GitHub 仓库写入替代：

- Cloudflare 账号授权、首次实际部署和 `workers.dev` 地址生成。
- 两台手机分别使用 Wi-Fi 与移动数据进行公网双向测试。
- 公网后台、锁屏、通知点击返回和网络切换重连。
- 厂商省电和长时间息屏稳定性。
- 后续配对系统、短期凭据和 Android Keystore 凭据存储。
- 可选自定义域名。
- Play Console 的 `remoteMessaging` 前台服务用途声明。
- 正式签名、安装和发布验收。
