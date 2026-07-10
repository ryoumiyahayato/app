# 电子木鱼验证状态

更新时间：2026-07-10

## 分支边界

- 工作分支：`fix/z-audit-issues-20260710`
- `main` 尚未合并本轮修复。
- 本文件区分“已经实际执行的结果”和“代码存在但仍需重新执行的结果”。

## 中断前已经实际执行的结果

以下结果来自中断前的本地执行记录：

- Node.js 24 / npm 11 下完成 `npm ci`。
- npm 生产依赖审计为 0 漏洞。
- relay 无鉴权和带测试 token 的协议回归曾实际通过。
- Android Kotlin 源码编译、lint、JVM 单元测试、debug APK 和 release R8 打包曾实际通过。
- release 输出为未签名 APK。
- relay 停止后测试端口曾确认释放。

这些结果对应中断前的代码状态。此后又修改了 Service 可见性分流、前台启动失败回滚、测试客户端 token 支持和一键验收脚本，因此必须在当前分支最新提交上重新执行，不能沿用旧结果作为最终验收。

## 当前可重复验证入口

Windows PowerShell：

```powershell
Set-Location .\electronic-muyu
.\verify-local.ps1
```

脚本会执行：

1. 要求 Git 工作区干净。
2. 校验 Node.js 版本不低于 22。
3. 执行 `npm ci` 和生产依赖审计。
4. 启动无鉴权 relay，执行协议回归和 send smoke。
5. 启动带本地测试 token 的 relay，执行 4001 等鉴权回归和 send smoke。
6. 确认两个 relay 端口均已释放。
7. 停止 Gradle daemon 后执行 Android `clean`、`lintDebug`、JVM 测试、debug/release 打包。
8. 读取 JVM XML 报告并确认没有 failure/error。
9. 确认 debug 允许明文、release 禁止明文。
10. 确认 debug APK、未签名 release APK 和 R8 mapping 为本轮新产物并输出 SHA-256。
11. 使用 `apksigner` 确认 release APK没有被本地密钥静默签名。
12. 再次确认 Git 工作区干净。

## 远端验证

`.github/workflows/verify-electronic-muyu.yml` 会在 Windows GitHub Actions 上执行相同的一键脚本，并上传 Android 报告、测试结果、APK 和 mapping 文件。

远端工作流实际完成前，不得将其记为“CI 通过”。

## 仍需外部执行

以下项目不能由无设备 CI 替代：

- 两台真实 Android 设备前台互收。
- 后台、锁屏和通知点击返回。
- Wi-Fi 与移动数据切换后的重连。
- 厂商省电和长时间息屏。
- 6B 公网异地 relay。
- 6C 域名、证书和 WSS。
- Play Console 的 `remoteMessaging` 前台服务用途声明。
- 正式签名、安装和发布验收。
