# v0.7 安全扫码配对验证状态

更新时间：2026-07-11

当前实现位于 `main`。安全扫码配对、设备认证、端到端加密、重放保护、在线状态、限流后自动恢复、通知送达诊断和 Foreground Service 生命周期修复已经合并。

## 已有自动化验证基线

在安全配对分支合并前，以下项目曾实际通过：

- Android `testDebugUnitTest`：覆盖 QR、严格 base64url、SAS 固定向量、P-256 ECDH、HKDF、AES-256-GCM/AAD、重放、序列化、URL 与 legacy 迁移策略。
- Android `lintDebug`。
- Android `assembleDebug`。
- Android `assembleRelease` + R8，使用 `https://relay.invalid` 安全占位地址生成未签名 Release。
- Cloudflare `npm ci`、协议单元测试和 Wrangler dry-run。
- 本地 Miniflare 集成：邀请、并发单次 join、双确认、两设备认证、重复设备拒绝、密文转发、重放、重连、撤销、取消和日志秘密检查。

合并冲突修复后又加入了安全在线状态、通知诊断、限流恢复、专用远端提示音/震动及 Service 生命周期处理。由于当前 GitHub Actions 包含额度已用尽，这一版 `main` 尚未获得新的完整 Runner 通过记录。两个工作流已支持 `workflow_dispatch`，额度恢复后应各手动运行一次。

## 当前代码已覆盖的需求

- 一次性二维码、120 秒有效期和双方 SAS 确认。
- 每设备独立 access token、Android Keystore 包装的本机秘密和 AES-256-GCM 加密提醒。
- 发送与接收 counter、Worker 和 Android 双侧重放保护。
- 认证完成后才进入已连接状态；未认证连接超时；重复设备连接拒绝。
- 仅已认证的对端参与在线状态，主界面和常驻通知区分对方在线/离线。
- 网络错误、服务重启和限流后的退避重连；认证失败、撤销和协议拒绝不循环重连。
- 前台提醒使用独立的远端提示音与震动模式；后台或锁屏按设置发送系统通知。
- 通知权限、App 通知总开关、通知渠道和测试通知诊断。
- Release 固定使用 HTTPS/WSS relay；只有 Debug 可覆盖到受限的本机 HTTP 地址。
- 旧 URL/room 配置不自动连接，完成安全配对后清理旧字段。

## 仍需人工或生产环境完成

- 两台真机摄像头扫码、SAS 双确认和解除配对全流程。
- 前台连续快速点击、专用远端提示音/震动、后台、锁屏和通知点击返回。
- Wi-Fi 与蜂窝切换、飞行模式恢复和公网自定义域名重连认证。
- Android 15/16 及厂商 ROM 至少 24 小时 `remoteMessaging` Foreground Service 运行。
- 生产 relay 地址注入、正式签名、全新安装、升级安装和 Play Console 声明。
- Cloudflare 生产环境 alarm 关闭帧、账户级 WAF/限流和配额策略。

以上项目必须按 [SECURE_PAIRING_MANUAL_TEST.md](SECURE_PAIRING_MANUAL_TEST.md) 执行。静态检查、旧版 CI 结果或本地 Miniflare 不能代替真机和生产验收。
