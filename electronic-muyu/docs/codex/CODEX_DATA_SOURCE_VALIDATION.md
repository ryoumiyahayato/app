# Codex 数据源可行性验证（C0 → C1）

验证日期：2026-09-07（UTC）

项目分支：`feat/codex-usage-integration`

项目基线：`3536e62a6e656b0d7694f061b1365baf49859be6`

状态：**C1 未通过 Android 集成 Gate；未实施 Collector、Room 或 UI**

## 结论

```text
QUOTA SOURCE: PARTIAL
TOKEN SOURCE: PARTIAL
MODEL-FREE COLLECTION: PASS
READY FOR ANDROID INTEGRATION: NO
```

`MODEL-FREE COLLECTION: PASS` 只表示已确认的候选读取路径本身不需要创建 Codex
task、thread 或 turn，也不需要调用 Responses/Chat Completions API。它不把本次开发过程中使用
Codex 的行为算作成品运行时依赖。

当前不能进入 C2，原因不是缺少 Dashboard，而是本执行环境未能完成一次真实账户的
`account/rateLimits/read` / `account/usage/read` 往返，也没有可用于验证跨重启、重复通知和本地
rollout 增量扫描的桌面 Codex 会话样本。协议和实现证据证明数据通路存在，但尚不足以证明它在
目标电脑、目标账号和实际网络条件下“稳定、持续”。

此外，即使活体请求成功，当前协议仍不能用单一数据源完整提供“逐次 token + 原始发生时间 +
模型 + sessionId”。实现必须组合账户 quota、实时 thread 通知和本地 rollout，并保留未知值为
`null`。

## C0 — Repository audit

### Git 基线

- 从 `https://github.com/ryoumiyahayato/app.git` 全新克隆。
- 显式执行 `git fetch --prune origin` 后，`origin/main` 与本地 `main` 都是
  `3536e62a6e656b0d7694f061b1365baf49859be6`。
- 该提交为 `fix: hold transient taps until peer reconnects`，提交时间
  `2026-07-11T17:18:15Z`。
- 开始时工作树干净；远端只有 `main`，没有已有 Codex feature branch。
- 已从 `origin/main` 新建 `feat/codex-usage-integration`，不修改 `main`，不改历史。

### 当前 Android 实现

- Kotlin、Jetpack Compose、Material 3，`minSdk 26`、`compileSdk/targetSdk 36`，JDK 17。
- `MainActivity` 创建通知渠道、维护前后台状态，并在一个 Compose `NavHost` 中注册
  `main`、`settings`、`scanner`。
- `MainScreen` 保持木鱼按钮、功德/接收计数、连接/对端状态和设置入口的极简首页。
- `MainViewModel` 当前集中负责 DataStore 状态、配对流程、设置、计数和 Service 命令；文件已达
  574 行。Codex 后续不得继续塞入该 ViewModel。
- `LocalDataStore` 使用 Preferences DataStore 保存计数、设置、设备 ID、配对 metadata、加密
  secret blob、严格单调的发送/接收 counter 和 debug relay override。
- `MuyuForegroundService` 是唯一的长期连接所有者，实例化 `WebSocketClient`，处理重连、对端
  presence、10 秒 pending tap queue、加解密、重放检查、前台服务通知和后台提醒。
- `MuyuConnectionRepository` 是进程内 StateFlow 状态桥，不是历史数据库。
- `WebSocketClient` 使用 OkHttp WebSocket；只有严格校验 `auth_ok` 后才进入 connected，网络、
  server close 和 4408 会退避重连，认证失败/撤销/协议拒绝为终止性错误。
- `NotificationHelper` 当前只有 `merit_reminder` 渠道；Codex 通知后续需要独立渠道、ID 和状态。

### 当前 pairing / crypto / relay 安全边界

- secure pairing 协议版本为 1；QR 是一次性 120 秒邀请，双方核对 6 位 SAS 后才建立长期配对。
- P-256 ECDH + HKDF-SHA256 派生方向密钥；tap envelope 使用 AES-256-GCM，并把 version、
  pairId、sender、严格递增 counter 绑定为 AAD。
- P-256 私钥、设备 access token、send/receive key 被 Android Keystore 中非导出的 AES key 包装；
  DataStore 不保存明文秘密。
- Android 和 Worker 都检查 counter；Worker 只转发密文，不解析 tap 明文，不保存消息历史，也不
  提供离线队列。
- Cloudflare relay 使用 `InvitationSession`、`SecurePair`、`RequestRateLimiter` 和隔离的 legacy
  `PairRoom` Durable Objects；`ALLOW_LEGACY` 默认 `false`。
- 生产 relay URL 由 BuildConfig 注入，默认 `https://relay.invalid`；Release 不允许覆盖，Debug
  只允许 HTTPS 或受限 loopback HTTP。
- `allowBackup=false`，backup/data transfer 规则排除应用数据。

因此，后续 Codex 域必须拥有独立 pairing identity、密钥 alias、消息类型、counter/重放状态和
repository。不得把 Codex payload 放入 `encrypted_tap`，也不得把木鱼 partner identity 当作
Collector identity。

### 当前测试与 CI 基线

- Android JVM 测试源码共 34 个 `@Test`，覆盖 URL/legacy policy、QR、序列化、base64url、
  ECDH/HKDF/AES-GCM、replay、WebSocket policy、repository 和 pending queue。
- Cloudflare protocol 单元测试 9/9 通过；Wrangler `4.110.0` dry-run 通过，确认四个 Durable
  Object bindings 且 `ALLOW_LEGACY=false`。
- Cloudflare 本地 integration 测试在此环境启动期间被网络/本地绑定审批层取消，不能记为失败
  或通过。
- Android Gradle wrapper 没有 executable bit；改用 `bash gradlew` 后，wrapper 下载
  `gradle-8.11.1-bin.zip` 被当前网络策略拒绝。因此本次未得到新的 unit/lint/debug/release
  结果，不能沿用历史结果冒充本次通过。
- 两个 GitHub Actions 工作流分别验证 Android/legacy server 和 Cloudflare relay。仓库中的
  `VERIFICATION_STATUS.md` 明确说明当前 `main` 在后续合并后尚无一次新的完整 Runner 通过记录；
  真机、公网、Android 15/16 长运行和生产 alarm close frame 仍需人工验证。

## C1 — Codex Data Source Proof

### 执行环境和版本

| 项目 | 实际值 |
|---|---|
| Codex binary | `/opt/codex/bin/codex` |
| Codex version tested | `codex-cli 0.151.0-alpha.2` |
| Package target | `x86_64-unknown-linux-musl` |
| OS tested | Linux x86_64, kernel `6.18.35` |
| App-server transport inspected | JSON-RPC over `stdio://` |
| App-server protocol | unversioned initialize handshake + v2 account/thread methods |
| Exact official source tag commit | `7a85bd1bb4c61c211781c814596fcdeb311107fe` |
| Official repository HEAD observed | `4110342321bb19b0053190750a0a8b76427b13ad` |

版本和 schema 来自实际二进制：

```text
/opt/codex/bin/codex --version
/opt/codex/bin/codex app-server generate-json-schema --experimental --out <temporary-dir>
```

生成结果包含 `GetAccountRateLimitsResponse`、`AccountRateLimitsUpdatedNotification`、
`GetAccountTokenUsageResponse` 和 `ThreadTokenUsageUpdatedNotification`。随后以官方仓库精确 tag
的源码核对序列化、累加、持久化、恢复和测试语义。未把 `main` 上更新的行为倒灌为当前安装
版本的能力。

### A. Quota / rate limit

#### 官方接口

当前二进制正式暴露：

```json
{ "method": "account/rateLimits/read", "id": 7 }
```

响应包括：

- `rateLimits.primary` / `secondary`：`usedPercent`、可空的 `windowDurationMins`、可空的
  `resetsAt`（Unix 秒）。
- `rateLimitsByLimitId`：按 `limitId` 分桶的可空 map；不能假设永远只有一个 Codex bucket。
- `rateLimitReachedType`：后端分类的额度/工作区限制状态。
- 可空的 credits、spend control、plan type 等账户 metadata。
- 可空的 `rateLimitResetCredits`；其中 `availableCount` 是权威数量，`credits == null` 表示只知道
  数量，空数组表示已取详情但没有可用明细，详情数组还可能被后端截断。

`account/rateLimits/updated` 是**稀疏滚动更新**。客户端必须把已有字段合并进最近一次 read
snapshot，或重新 read；更新中的 `null` 不能一律解释成清除旧值。reset-credit 数据只存在于
read snapshot，不随 updated notification 推送。

官方 app-server 实现直接通过已登录的 ChatGPT auth client 读取后端 `/api/codex/usage`，并在
可用时读取 `/api/codex/rate-limit-reset-credits`。API-key 登录不能读取此 ChatGPT quota；没有
ChatGPT auth 时返回明确 JSON-RPC 错误。该路径不创建模型请求。

#### bucket 识别

不得写死 `primary == 5-hour`、`secondary == weekly`。内部规范化必须优先使用 `limitId` 和
`windowDurationMins`：

- 300 分钟可标记为 5-hour window。
- 10,080 分钟可标记为 weekly window。
- duration 缺失或未知时保留原始 bucket，UI 不猜名称。

#### Reset / reset credit

- `resetsAt` 是后端给出的下一次 reset Unix 秒，不是客户端接收时间。
- 协议没有独立的“reset happened”事件。`CodexResetEvent` 只能由连续 snapshots 观测推导，
  reason 必须是 `observed` / `unknown`，除非消费 reset credit 的本地操作有明确结果。
- reset-credit 官方枚举为 `codexRateLimits` / `unknown`；当前没有名为 `bankedReset` 的稳定字段。
  UI 可显示“reset credits”，不得把未知 credits 擅自改名为 banked resets。
- 本阶段只读验证没有调用 `account/rateLimitResetCredit/consume`。

#### 活体请求结果

向实际 app-server 发送 initialize + `account/rateLimits/read` 的只读探针时，进程的外部网络访问
被当前 Work 执行环境的审批层在返回前取消。没有收到 Codex JSON-RPC success/error payload，
所以不能确认当前账号实际提供 5h、weekly、reset credits，也不能测试限额耗尽后仍能读取。

结论：**QUOTA SOURCE: PARTIAL**。

### B. Token usage

#### 1. `thread/tokenUsage/updated`

通知结构为：

```text
threadId
turnId
tokenUsage.total
tokenUsage.last
tokenUsage.modelContextWindow
```

`total` 和 `last` 都包含：

- `inputTokens`
- `cachedInputTokens`
- `cacheWriteInputTokens`
- `outputTokens`
- `reasoningOutputTokens`
- `totalTokens`

源码语义：

- `last` 是最近一次上游 response completion 的新增 usage。
- `total` 是该 thread 已累加的 usage。
- 每次上游 completion 可能发生在同一个 turn 内的多轮工具调用之间，因此一个 turn 可有多个
  token update。
- `cachedInputTokens` 是 input 的子集，`reasoningOutputTokens` 是 output 的组成/分类；聚合时不得
  再把它们加到 `totalTokens` 上。
- resume/fork 可以重放已持久化的最后 snapshot。重放不是新使用，不能把 `last` 再累计一次。
- rate-limit 更新、context 重新估计等路径也可能触发 TokenCount；官方说明该通知可能是
  accumulated/estimated/persisted/replayed，不应把每个通知都当作一条唯一账单事件。
- 通知没有 timestamp、model 或 sessionId。

#### 2. Rollout/session JSONL

当前官方实现把 `EventMsg::TokenCount` 列为可持久化事件。每个 rollout JSONL envelope 由 writer
增加：

- `timestamp`：写入时的 UTC RFC3339，毫秒精度。
- 可空 `ordinal`。
- `type: event_msg` + `payload.type: token_count`。

rollout 的 timestamp 是**本地持久化时间**，不是后端声明的 token 发生时间。它适合做历史排序
和近似发生时间，但内部 schema 应同时保留 `occurredAt` 可空、`observedAt`/`persistedAt` 明确
标记来源。

当前 Codex 还会把冷 rollout 压缩为 `.jsonl.zst`。Collector 若只 watch `.jsonl` 会静默漏掉
历史；必须支持 plain/compressed sibling 选择和文件替换/截断恢复。

rollout 还包含 `turn_started`（可带 Unix 秒 `started_at`）和持久化的 thread settings（含配置
model），可用于关联；但 token_count payload 本身没有 model。发生 model reroute、缺失 settings
或关联含糊时，model 必须为 `null`。

#### 3. `account/usage/read`

当前二进制还提供账户级只读接口：

```json
{ "method": "account/usage/read", "id": 8 }
{ "method": "account/usage/read", "id": 9, "params": { "threadId": "..." } }
```

账户结果的 daily buckets 只有 `startDate` 和 `tokens`，可提供官方账户每日总量，但不能还原
input/cache/output/reasoning 或逐次时间。thread 查询可返回按 model/reasoning effort/speed 分组的
估算 usage/credits，字段仍可能为 `null`，且不是逐事件 ledger。

#### 4. 不采用的源

- `rawResponse/completed` 虽可带上游精确 usage，但官方标记为 internal-only，需要 thread 的
  experimental raw events，并且不持久化、不重放；不能作为稳定 Collector API。
- 不读取 Usage HTML，不 OCR，不模拟点击，不截屏。
- 不读取、复制或上传 `auth.json`、session cookie、access/refresh token 或 API key。
- 不解析 prompt、源码、assistant content 或完整 conversation；rollout parser 必须只投影允许的
  metadata 字段。

#### 活体结果

本 Work 环境的 `/root/.codex/sessions` 没有普通桌面 Codex rollout JSONL；当前会话由 Work 的
其他状态存储管理。运行中的 app-server control socket也不可由本执行进程访问。因而本次未能对
真实样本验证：同一 turn 多 completion、Codex 重启、Collector 重启、压缩切换、重复 replay、
截断文件和损坏尾行。

结论：**TOKEN SOURCE: PARTIAL**。

## 事件与去重语义（C2 的必备约束，不代表已实现）

### 建议规范化模型

```text
CodexUsageEvent
  id                       stable local identity
  occurredAt               nullable; source-declared time only
  observedAt               collector clock
  persistedAt              nullable rollout envelope time
  threadId                 nullable only for account aggregates
  turnId                   nullable
  sessionId                nullable
  model                    nullable
  inputTokens              nullable
  cachedInputTokens        nullable
  cacheWriteInputTokens    nullable
  outputTokens             nullable
  reasoningTokens          nullable
  totalTokens              nullable
  semantics                incremental | cumulative_snapshot | estimated_snapshot
  source                   app_server_live | rollout_jsonl | account_usage
  sourceVersion
```

quota 和 token 必须存为不同实体。不得从 token 推导 quota 百分比，也不得把 observed correlation
称作 OpenAI 官方换算率。

### 去重

- rollout 首选 ID：`SHA-256(sourceVersion + canonicalRolloutIdentity + ordinal + projectedPayload)`；
  ordinal 缺失时加入 envelope timestamp、文件偏移和 payload hash。
- 文件 checkpoint 至少保存 canonical path、文件 identity、offset、最后完整行 hash；遇到 shrink、
  inode/file-id 更换或 plain → zstd 转换时重新核对而非盲目续读。
- live 通知可能与稍后扫描到的 rollout 是同一 snapshot。跨源合并要比较
  `(threadId, turnId, total breakdown fingerprint)`，live event 先暂存，rollout 到达后补充
  persisted timestamp/source identity。
- 绝不对每个 `total` 求和；优先接受可信的 `last`，同时用前后 `total` 的分项差校验。
- replayed `last` 不代表新增。相同 cumulative fingerprint 不新增 usage。
- total 回退可能来自 fork/revert、rollout lineage 或账号切换，不能当负 token；开启新 lineage 或
  标记 discontinuity。

## Reset 检测

只有满足以下条件时才生成“观测到 reset”的候选事件：

- 同一 account scope、limitId、window duration；
- snapshot 顺序可证明；
- `usedPercent` 明显下降，或旧 `resetsAt` 已到且新 `resetsAt` 前移到未来；
- 排除陈旧 notification、账号切换、bucket 变化和 Collector 时钟倒退。

无法区分自然 reset、后端修正和其他原因时，`reason = null/observed`。消费 reset credit 的成功
响应也必须随后 refetch snapshot，不能自己伪造新的百分比。

## 失败、离线和生命周期语义

| 场景 | 预期行为 | 本次验证 |
|---|---|---|
| Collector 离线 | Android 保留最后 snapshot，明确 stale/offline；只本地算 countdown | 设计确认，未实现 |
| 网络离线 | quota read 失败；已落盘 rollout 仍可扫描 | 源码确认，未活测 |
| rate-limit exhausted | 显示 backend reached type 和最后 reset；是否仍可 read 需活测 | 未验证 |
| Collector restart | 从持久化 checkpoint 恢复并重扫校验，不重复累计 | 未验证 |
| Codex restart | 发现新/续写 rollout，重新订阅；不得假在线 | 未验证 |
| duplicate notification | cumulative fingerprint 去重，resume replay 不计新增 | 源码确认，未活测 |
| malformed JSONL | 忽略未完成尾行，隔离永久坏行并记录 gap | 未验证 |
| account switch | 分区历史并停止跨账号 delta；不向手机同步账号凭据 | 未验证 |

## 数据丢失与稳定性风险

1. App-server v2 方法存在，但 Codex CLI 的 app-server 仍标注 experimental 命令；私有实现字段可能
   在升级时变化。Collector 必须做版本/schema capability negotiation，未知版本 fail closed。
2. 一个独立启动的 app-server 不会自动收到另一个 Codex 进程的所有 thread live notifications。
   全局历史仍需 rollout 扫描或未来正式的账户 ledger。
3. `account/usage/read` 的 daily buckets 只有总 token，不能补齐逐事件 breakdown。
4. token notification 无时间和 model；rollout 时间是落盘时间，model 需要关联且可能不可靠。
5. rollout 是本地内部格式并包含高度敏感的 prompt/源码内容。Collector 必须流式投影白名单字段，
   禁止复制整行到日志、数据库、relay 或测试 fixture。
6. `.jsonl.zst`、partial tail、rotation、revert/fork lineage 和 schema upgrade 都会造成 naive tailer
   漏数或重复。
7. reset notification 不是独立事件；snapshot 差分只能得到 observed reset。
8. reset credits 详情可缺失，且明细条数可能小于 availableCount。
9. 当前版本实测版本是 alpha build；不能承诺跨版本稳定。

## Unsupported / nullable fields

以下字段在统一 schema 中必须允许 `null`，不得用 0、空字符串或推测值代替：

- token event 的 `occurredAt`、`sessionId`、`model`。
- 任一缺失的 token breakdown 分项。
- quota 的 `windowDurationMins`、`resetsAt`、secondary window、limit name。
- credits balance、monthly limit、plan type、rate-limit reached type。
- reset-credit 明细、标题、描述、过期时间。
- reset 原因。

## 下一步 Gate

C2 之前必须在用户实际运行 Codex 的目标电脑上，用同一 Codex 版本或明确记录的新版本完成一个
不含敏感内容的验证夹具：

1. 只读调用 `account/rateLimits/read`，记录脱敏 schema、5h/weekly duration、reset timestamp 和
   reset-credit 可用性。
2. 在一次已由用户正常发起的 Codex turn 中观察 live token notifications；Collector 不创建 turn。
3. 仅投影 rollout 的 timestamp/ordinal/token_count/turn boundary/settings metadata，验证 `last`
   与 `total`。
4. 覆盖同 turn 多 completion、resume replay、Collector restart、Codex restart、坏尾行、
   `.jsonl.zst` 和 account switch。
5. 在接近或达到额度时确认 quota read 是否继续可用。
6. 连续运行至少一个自然 quota reset 周期，确认 snapshot/updated 合并和 reset 检测。
7. 保存的 fixture 必须人工检查不含 prompt、源码、路径、邮箱、token、cookie、key 或完整 IDs。

只有上述验证使 quota/token 都达到 PASS 后，才能进入 C2。当前按 Gate 要求停止，不创建
`codex-collector/`，不添加 Android Room、Navigation、Dashboard 或通知代码。

## 可复核的官方来源

- [OpenAI Codex exact tested source tag](https://github.com/openai/codex/tree/7a85bd1bb4c61c211781c814596fcdeb311107fe)
- [App-server protocol implementation](https://github.com/openai/codex/blob/7a85bd1bb4c61c211781c814596fcdeb311107fe/codex-rs/app-server-protocol/src/protocol/common.rs)
- [Account protocol types](https://github.com/openai/codex/blob/7a85bd1bb4c61c211781c814596fcdeb311107fe/codex-rs/app-server-protocol/src/protocol/v2/account.rs)
- [Thread token protocol types](https://github.com/openai/codex/blob/7a85bd1bb4c61c211781c814596fcdeb311107fe/codex-rs/app-server-protocol/src/protocol/v2/thread.rs)
- [App-server account processor](https://github.com/openai/codex/blob/7a85bd1bb4c61c211781c814596fcdeb311107fe/codex-rs/app-server/src/request_processors/account_processor.rs)
- [Rollout persistence policy](https://github.com/openai/codex/blob/7a85bd1bb4c61c211781c814596fcdeb311107fe/codex-rs/rollout/src/policy.rs)
- [Rollout timestamp writer](https://github.com/openai/codex/blob/7a85bd1bb4c61c211781c814596fcdeb311107fe/codex-rs/rollout/src/recorder.rs)
