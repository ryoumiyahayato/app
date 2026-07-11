# Android v0.7 安全配对使用与构建

## 用户流程

未配对时进入“设置 → 安全配对”：

- A 点“创建配对二维码”，B 点“扫描对方二维码”。
- B 的扫码页只识别 QR Code，不打开 URL、不复制内容；相机权限只在进入扫码页时请求。
- 两台设备显示 6 位安全码后，通过当面或语音逐位核对，各自点“代码一致”。远程截图/转发二维码不替代独立渠道的安全码核对。
- 双方确认后自动保存并连接。日常使用不再显示服务器地址、room 或 token。
- “解除配对”需要二次确认；服务器撤销成功后本机 Keystore 和配对材料被删除。

旧版配置只触发迁移告警，不会自动连接或转换。完成新配对后旧 URL/room 被原子删除。

## Relay BuildConfig

仓库默认占位值是 `https://relay.invalid`，不会意外连接真实服务。Release 必须由构建系统注入 HTTPS origin：

```powershell
.\gradlew.bat assembleRelease `
  -PELECTRONIC_MUYU_RELAY_BASE_URL=https://relay.example.com
```

也可放入不提交的 `local.properties`：

```properties
ELECTRONIC_MUYU_RELAY_BASE_URL=https://relay.example.com
```

不要把 Cloudflare API token、relay access token 或其他秘密写入 Gradle property；这里只配置公开域名。Release 总是使用 BuildConfig 值且隐藏覆盖 UI。Debug 的“开发者 / 高级设置”允许 HTTPS 覆盖，或本机/模拟器回环 `http://127.0.0.1`、`localhost`、`10.0.2.2`；二维码不能覆盖 relay。

## 本地构建

需要 JDK 17、Android SDK 36、Gradle wrapper 8.11.1：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 本机存储

- P-256 私钥为软件生成 PKCS#8，只在 Android Keystore 非导出 AES-256-GCM key 的包装下落盘。
- 同一加密 blob 内保存设备 access token 和两个方向消息密钥。
- DataStore 只保存 metadata、加密 blob 和单调 counter。
- alias 为 `electronic_muyu_pairing_wrap_v1`，协议版本进入名称。
- `allowBackup=false`，cloud backup、device transfer 和 full backup 全域排除。

卸载 App 会自然删除 Keystore 和应用数据，因此旧设备身份不可恢复。

## 已知行为

- Android 进程在配对确认的极小提交窗口被杀死时，需重新配对。
- 解除配对请求网络失败时，本机保留凭据并明确报错，以便稍后真正撤销服务器 token。
- 不支持离线提醒；对端离线时 Worker 不排队。
- 厂商省电、后台/锁屏通知和长连接行为需按真机清单验证。
