# v0.7 安全扫码配对验证状态

更新时间：2026-07-11

分支：`feat/secure-qr-pairing-20260710`。`main` 未修改、未合并；未执行 Cloudflare 生产部署或 Dashboard 修改。

## 本地已实际通过

- Android `testDebugUnitTest`：通过。覆盖 QR、严格 base64url、跨 Worker SAS 固定向量、P-256 ECDH、RFC 5869 HKDF、AES-256-GCM/AAD、重放、序列化、URL 与 legacy 迁移策略，以及原有计数/服务回归。
- Android `lintDebug`：通过；为 AGP 8.10.1/Kotlin metadata 兼容仅禁用会崩溃的 `StateFlowValueCalledInComposition` 检测器，其余 lint 开启。
- Android `assembleDebug`：通过，生成 `app/build/outputs/apk/debug/app-debug.apk`。
- Android `assembleRelease` + R8：使用 `https://relay.invalid` 安全占位构建通过，生成未签名 Release；不代表生产配置或签名验收。
- Cloudflare `npm ci`：通过，0 vulnerabilities。
- Cloudflare `npm test`：9/9 通过。
- Wrangler `deploy --dry-run`：通过，识别 Invitation、SecurePair、PairRoom 和 RequestRateLimiter bindings；没有实际部署。
- 本地 Miniflare 集成：通过完整邀请、并发单次 join、双确认、两设备认证、重复/第三设备拒绝、密文转发、重放、重连、撤销、取消与日志秘密检查。

## 本轮仍未实际验证

- 两台真机摄像头扫码与 SAS UI 流程。
- 公网自定义域名、跨 Wi-Fi/蜂窝、网络切换后的重连认证。
- Android 15/16 和厂商 ROM 长时间 `remoteMessaging` FGS。
- 后台、锁屏通知、通知点击返回和 Activity 重建去重的 v0.7 双机回归。
- 生产 relay BuildConfig、正式签名、安装/升级与 Play Console 声明审核。
- Cloudflare 生产 alarm close frame 的客户端交付（Miniflare Worker 侧执行 4410，但 Node 客户端事件交付不稳定）。

这些项目必须按 [SECURE_PAIRING_MANUAL_TEST.md](SECURE_PAIRING_MANUAL_TEST.md) 人工验收，不能由静态审计或本地构建代替。
