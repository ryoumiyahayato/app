# Codex 数据源可行性验证（C0 → C1 → C1-LIVE）

验证更新日期：2026-09-08（UTC）

项目分支：`feat/codex-usage-integration`

项目基线：`3536e62a6e656b0d7694f061b1365baf49859be6`

C1-LIVE 开始前远端 feature HEAD：`5ee858b73c8c740998accc5aacb8f5fca3d78188`

状态：**只完成数据源验证与隔离 Probe；未实施正式 Collector、Android Room、Dashboard、Navigation、通知或生产 relay 变更。**

## 当前结论

C0/C1 已确认的协议结论保持不变：

```text
QUOTA SOURCE: PARTIAL
TOKEN SOURCE: PARTIAL
MODEL-FREE COLLECTION PATH: PASS (source/protocol evidence)
READY FOR ANDROID INTEGRATION: NO
```

C1-LIVE 本轮将进入 C2 的能力拆成三个独立 Gate：

```text
G1 QUOTA READ
G2 TOKEN LEDGER
G3 RESET OBSERVATION
```

本轮执行环境无法访问用户实际运行 Codex 且已登录 ChatGPT 的目标电脑，因此不能把用户账户活测升级为 PASS。当前正确状态是：

```text
PROBE IMPLEMENTATION: PASS
LIVE USER ACCOUNT VALIDATION: PENDING
G1 QUOTA READ: PENDING LIVE USER VALIDATION
G2 TOKEN LEDGER: PARTIAL
G3 RESET OBSERVATION: PARTIAL
READY FOR C2 COLLECTOR MVP: NO
```

其中 G3 不再阻塞 C2。只有 G1、G2、MODEL-FREE MONITORING、SENSITIVE DATA LEAK CHECK，以及 JSONL/Zstd 和 replay/restart 去重测试全部 PASS，才允许后续进入 C2 Collector MVP。

## 远端基线复核

C1-LIVE 开始前重新读取远端状态：

- `main`：`3536e62a6e656b0d7694f061b1365baf49859be6`
- `feat/codex-usage-integration`：`5ee858b73c8c740998accc5aacb8f5fca3d78188`
- Draft PR #8 head：`5ee858b73c8c740998accc5aacb8f5fca3d78188`
- Draft PR #8 base：`main` / `3536e62a6e656b0d7694f061b1365baf49859be6`
- PR 状态：Open、Draft、未 merge

因此没有基线漂移；本轮继续在既有 feature branch 和 Draft PR #8 上工作。

## C0 — Repository audit 摘要

当前 Android 工程为 Kotlin + Jetpack Compose + Material 3，`minSdk 26`、`compileSdk/targetSdk 36`、JDK 17。现有木鱼配对、加密、relay、DataStore、前台服务和通知边界保持不动。

Codex 域后续必须保持独立身份、密钥、协议消息、counter/replay 状态和 repository，不能把 Codex payload 混入 `encrypted_tap`，也不能复用木鱼 partner identity。

本轮没有进入 Android UI / Room / Navigation / Dashboard / Notification 实现，也没有修改生产 relay。

## C1 — 已确认的数据源语义

### 1. Quota / rate limit

当前已确认 app-server 暴露：

```text
account/rateLimits/read
account/rateLimits/updated
```

`account/rateLimits/read` 可提供或可能提供：

- `rateLimits.primary` / `secondary`
- `rateLimitsByLimitId`
- `usedPercent`
- `windowDurationMins`
- `resetsAt`
- `rateLimitReachedType`
- 可空 reset-credit metadata

不得写死 `primary == 5-hour`、`secondary == weekly`。识别窗口优先使用 `limitId` 与 `windowDurationMins`：

```text
300 minutes   -> 5-hour candidate
10080 minutes -> weekly candidate
```

缺失字段必须保留 `null`，禁止推测。

`account/rateLimits/updated` 是稀疏更新，不能把 notification 中的 `null` 自动当作清空全部旧状态。

reset credit 当前只能按后端实际字段展示。没有稳定字段证明它等价于名为 `banked reset` 的产品概念时，不得擅自改名。

### 2. Token live notification

当前已确认：

```text
thread/tokenUsage/updated
```

通知包含：

```text
threadId
turnId
tokenUsage.last
tokenUsage.total
tokenUsage.modelContextWindow
```

`last` 表示最近一次上游 completion 新增 usage；`total` 表示 thread 累计 usage。

必须保留以下关键语义：

```text
1 user turn != 1 token event
```

一个用户 turn 可以发生：

```text
model -> tool -> model -> tool -> model
```

因此内部应使用 `usage event` / `completion usage` / `token delta`，不能把每个 `last` 自动命名为“一次 Codex 使用”。

live token notification 本身没有可靠：

```text
timestamp
model
sessionId
```

所以：

```text
occurredAt = null
model = null
```

除非某个具体数据源对该 token event 明确声明并可靠归因。

不得把当前 Codex 默认模型、thread 初始模型或 UI 当前选择直接复制到 token event。必须考虑 thread 内模型切换、reroute、fallback、resume、fork。

### 3. Token breakdown invariant

`last` / `total` 的 token breakdown 包括：

```text
inputTokens
cachedInputTokens
cacheWriteInputTokens
outputTokens
reasoningOutputTokens
totalTokens
```

必须满足：

```text
cachedInputTokens ⊆ inputTokens
reasoningOutputTokens ⊆ outputTokens
```

因此总 token 绝不能计算为：

```text
input + cached + output + reasoning
```

否则会重复统计 cache 和 reasoning。

### 4. Rollout

rollout JSONL 中可持久化 `token_count`。envelope 的 timestamp 是本地持久化时间，只能记作：

```text
persistedAt
```

不得冒充：

```text
occurredAt
```

冷 rollout 可能为：

```text
*.jsonl
*.jsonl.zst
```

因此简单 tail `.jsonl` 会漏历史，不可接受。

rollout 中包含高度敏感内容。验证工具只允许：

```text
read
-> parse
-> allow-list projection
-> discard original content
```

不得输出或保存完整 rollout 行。

### 5. `account/usage/read`

当前已确认：

```text
account/usage/read
```

它用于账户级 aggregate / reconciliation，不是逐事件 ledger。

允许记录官方 daily bucket 等汇总数据用于诊断，但不能用它静默重写本地事件，也不能从 daily aggregate 反推 input/cache/output/reasoning 分项或精确时间。

## C1-LIVE — Probe Harness

新增严格隔离的验证目录：

```text
electronic-muyu/tools/codex-live-probe/
```

明确没有创建：

```text
electronic-muyu/codex-collector/
```

因为正式 Collector Gate 尚未通过。

### Probe 允许的 app-server 方法

运行时硬 allow-list：

```text
initialize
initialized
account/rateLimits/read
account/usage/read
```

Probe 不会发送：

```text
thread/start
turn/start
account/rateLimitResetCredit/consume
```

也会拒绝其他非 allow-list RPC。

Probe 不读取或请求：

```text
OpenAI password
cookie
access token
refresh token
auth.json
API key
production relay secret
```

### 数据保留白名单

报告与 checkpoint 只允许：

```text
token metadata
quota metadata
opaque SHA-256 truncated IDs
timestamp metadata
ordinal
safe explicit model metadata
source/probe version
```

完整以下信息不得进入报告、checkpoint 或 fixture：

```text
prompt
assistant response
source code
tool output
conversation text
auth token
refresh token
access token
cookie
API key
email
full filesystem path
full threadId
full turnId
account identifier
```

thread / turn / source identity 使用 SHA-256 截断显示 hash。完整 path 仅在进程内瞬时用于打开文件和计算 opaque source hash，不写入结果。

server RPC error 也不保存原始 message/data，只保留方法和数值错误码，避免错误消息夹带路径或账号信息。外部字符串（Codex version、model、timestamp、枚举）使用严格格式白名单；若最终泄漏检测仍发现异常，报告会 fail-closed，只写状态和泄漏类别，不写可疑原值。

### 模型字段策略

live notification 不填 model。

rollout parser 只在 `token_count` 事件本身显式声明 model 时才把该值作为 per-event model 保存。仅在 turn-context 看到 model 不足以证明具体 completion 没有 reroute，因此默认仍保留 `model = null`。

## G2 ledger 算法 invariant

核心 invariant：

```text
同一个 cumulative snapshot 永远不能产生第二份新增 usage。
```

以及：

```text
totalTokens 不允许被连续 total snapshot 相加。
```

Probe checkpoint 保存已见 cumulative fingerprint；因此即使重启、文件从头 replay、plain/zstd 重扫，也不会再次累计同一 snapshot。

### Case A

```text
last = 100
total = 100
=> added = 100
```

### Case B

```text
previous total = 100
last = 40
total = 140
=> added = 40
```

### Case C — replay

```text
last = 40
total = 140
same cumulative fingerprint already seen
=> added = 0
```

### Case D — total-only

```text
previous total = 140
total = 200
last absent
=> inferred delta = 60
```

只在线性 lineage、分项不回退时产生 inferred delta，并明确标记 semantics：

```text
inferred_delta
```

### Case E — total regression

```text
200 -> 120
```

不得产生 `-80`。Probe 标记：

```text
lineage_discontinuity
```

并且不生成负 token event。

## Cross-source live ↔ rollout

live 与 rollout 只用安全投影后进行匹配：

```text
threadHash + cumulative token-breakdown fingerprint
```

如果实际收到 live notification，并且 rollout 中出现相同 cumulative snapshot，可判定 live ↔ rollout match。

仅扫描到 rollout 不能替代 live notification PASS。

特别是：独立启动的只读 app-server 通常不会自动收到另一个 Codex 进程产生的 thread token notification。因此本轮 Work 环境不会把 rollout 结果冒充 `LIVE TOKEN EVENT`。

## JSONL / Zstd / lifecycle tests

本轮 synthetic tests 不包含用户内容，覆盖：

```text
plain JSONL
Zstd JSONL
plain/Zstd projection equivalence
truncated final JSON line
corrupt compressed file
file replacement
file shrink
duplicate replay
file-offset restart
ledger restart
last replay
Case A-E
cachedInputTokens subset invariant
reasoningOutputTokens subset invariant
ID hashing
path/content projection leak rejection
```

本轮验证环境：

```text
21 tests run
21 passed
JSONL synthetic parser: PASS
JSONL.ZST synthetic parser: PASS
replay dedup synthetic: PASS
restart dedup synthetic: PASS
sensitive projection tests: PASS
```

Zstd 测试使用可用的标准/模块/`zstd` backend；本轮执行环境存在 `zstd` CLI，因此 synthetic compressed fixture 得到真实压缩/解压验证。

这些 synthetic PASS 证明算法与 parser harness，不等价于用户实际 Codex rollout 已经活测通过。

## File checkpoint / restart

plain JSONL checkpoint 保存：

```text
opaque source hash
opaque file identity hash
offset
size
mtime
```

不保存完整 path。

遇到：

```text
file identity replacement
offset > current size
```

分别标记 replacement / shrink，从安全位置重新扫描，并依靠 cumulative fingerprint 防止旧 snapshot 重复累计。

`.jsonl.zst` 作为冷压缩文件允许 replay-scan；去重仍以 cumulative fingerprint 为最终防线。

## Model-free monitoring 运行时证据

Probe 对一次 quota/usage read 周期记录：

1. 实际 outbound method 列表；
2. 运行前后 rollout 文件 metadata snapshot；
3. 是否出现 rollout 改动；
4. quota/usage RPC 是否成功。

只有在目标电脑实际运行时满足：

```text
rateLimits/read = PASS
usage/read = PASS
outbound RPC 全部在 read-only allow-list
monitoring window 内没有 Probe 可归因的 rollout side effect
```

才把：

```text
MODEL-FREE MONITORING = PASS
LLM CALLS CAUSED BY MONITORING = 0
```

本 Work 环境没有用户的实际 Codex 登录运行环境，因此本轮不能仅凭代码阅读把该 C1-LIVE runtime gate 升级为 PASS。

## G1 — Quota Read Gate

目标电脑需要真实验证：

```text
initialize
account/rateLimits/read
account/usage/read
```

至少记录允许字段：

```text
primary/secondary or rateLimitsByLimitId
usedPercent
windowDurationMins
resetsAt
rateLimitReachedType
reset credit availability when present
```

字段不存在就保留 `null`。

如果当前用户恰好已达到 rate limit，则额外观察 reached 状态下 `account/rateLimits/read` 是否仍可用。

如果距离额度很远，不允许为测试故意消耗到 100%。此项可保持：

```text
RATE-LIMIT-EXHAUSTED READ: NOT OBSERVED
```

且不阻塞 C2。

## G3 — Reset Observation Gate

Probe checkpoint 保留脱敏 quota baseline，可以跨运行比较同一 bucket 的：

```text
usedPercent
windowDurationMins
resetsAt
```

只有在同 bucket、同 duration、snapshot 顺序可解释，且 `resetsAt` 向后推进并伴随旧边界已到或明显 usage drop 时，才记录观察到 reset。

如果本轮没有自然 reset：

```text
G3 RESET OBSERVATION: PARTIAL
```

这不阻塞 C2。

G3 PASS 前不得宣称：

```text
reset event detection complete
reset notification correctness complete
natural vs reset-credit classification complete
```

weekly natural reset 也不再阻塞基础 Collector MVP。

## C2 进入标准

最低标准：

```text
G1 QUOTA READ = PASS
G2 TOKEN LEDGER = PASS
MODEL-FREE MONITORING = PASS
SENSITIVE DATA LEAK CHECK = PASS
JSONL = PASS
JSONL.ZST = PASS
REPLAY DEDUP = PASS
RESTART DEDUP = PASS
```

允许仍为：

```text
G3 RESET OBSERVATION = PARTIAL
RATE-LIMIT-EXHAUSTED READ = NOT OBSERVED
weekly natural reset = NOT OBSERVED
reset credit = NOT PRESENT
```

这些不阻塞 C2，但对应产品能力不能提前宣称完成。

## 当前 C1-LIVE 结果

由于本执行环境不能访问用户实际 Codex 登录目标电脑，本轮最终状态必须保持：

```text
CODEX VERSION:
TARGET USER VERSION NOT OBSERVED

G1 QUOTA READ:
PENDING LIVE USER VALIDATION

RATE LIMIT READ:
PENDING

RATE LIMIT UPDATED:
NOT OBSERVED

ACCOUNT USAGE:
PENDING

5H WINDOW:
NOT OBSERVED ON TARGET USER ACCOUNT

WEEKLY WINDOW:
NOT OBSERVED ON TARGET USER ACCOUNT

RESET CREDITS:
NOT OBSERVED ON TARGET USER ACCOUNT

G2 TOKEN LEDGER:
PARTIAL

LIVE TOKEN EVENT:
NOT OBSERVED ON TARGET USER ACCOUNT

ROLLOUT TOKEN EVENT:
NOT OBSERVED ON TARGET USER ACCOUNT

LIVE ↔ ROLLOUT MATCH:
NOT OBSERVED

REPLAY DEDUP:
PASS (synthetic)

RESTART DEDUP:
PASS (synthetic)

JSONL:
PASS (synthetic)

JSONL.ZST:
PASS (synthetic)

SENSITIVE DATA LEAK CHECK:
PASS (synthetic/report schema)

MODEL-FREE MONITORING:
PENDING TARGET RUNTIME EVIDENCE

G3 RESET OBSERVATION:
PARTIAL

READY FOR C2 COLLECTOR MVP:
NO
```

## 目标电脑唯一执行入口

Windows，从 `electronic-muyu/` 目录执行：

```powershell
powershell ./tools/codex-live-probe/run.ps1
```

预期只在本机生成：

```text
tools/codex-live-probe/C1_LIVE_RESULT.json
tools/codex-live-probe/C1_LIVE_RESULT.md
```

结果文件和 checkpoint 已加入工具目录 `.gitignore`，不得提交真实用户结果。

不需要、也不得提供：

```text
OpenAI password
cookie
access token
refresh token
auth.json
API key
```

Probe 使用目标电脑 Codex 已有的合法登录环境。

## 已确认的官方来源

前一轮 C1 使用的精确 tested source tag：

- `openai/codex@7a85bd1bb4c61c211781c814596fcdeb311107fe`

相关协议/实现位置：

- `codex-rs/app-server-protocol/src/protocol/common.rs`
- `codex-rs/app-server-protocol/src/protocol/v2/account.rs`
- `codex-rs/app-server-protocol/src/protocol/v2/thread.rs`
- `codex-rs/app-server/src/request_processors/account_processor.rs`
- `codex-rs/rollout/src/policy.rs`
- `codex-rs/rollout/src/recorder.rs`

C1-LIVE 实现时再次核对官方 SDK 当前初始化握手仍使用：

```text
initialize(clientInfo, capabilities)
initialized
```

Probe 不依赖读取或导出任何 OpenAI credential。
