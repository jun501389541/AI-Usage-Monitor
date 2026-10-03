# Phase 5 前置调研：Windows AI Usage Bridge 可行性验证

> 状态：**调研文档，不是实施计划**。按用户决策 D5（`docs/PHASE-4-PLAN.md:155`），Bridge 调研不并入 Phase 4 代码工作，单独成文。
> 本文只回答一个问题：**Spec 要求 Phase 5 读到的那三个数字，现在到底读不读得到、走哪条路读、边界在哪里。**
> 结论：**读得到，且不需要碰 Codex 的令牌。** 实测证据见 §2。
> 撰写日期：2026-10-02。基线提交：`95219d3`（Phase 4 收尾）。
> 本文所有"实测"都是本机真跑出来的命令与输出；所有"文档说法"都注明出处；拿不准的写在 §7，不粉饰。

---

## 1. Spec 已经承诺了什么（不是本文的发明）

| 出处 | 内容 | 对调研的约束 |
| --- | --- | --- |
| L2015-2035 `# Phase 5：Windows AI Usage Bridge MVP` | `先不要做二维码。` 链路 `Codex → Bridge → localhost API`，验收目标只有一个：**确认可以读取 `5H` / `Weekly` / `Reset`** | 本文的判定标准就是这三个字段，不是功能列表 |
| L711-729（§17） | 架构写死为 `Android → AI Usage Bridge → Codex App Server → account/rateLimits/read` | **接口名是 Spec 自己点名的**，调研要验证它当前存在 |
| L731-747 | 组件必须叫 `AI Usage Bridge`，不叫 `Codex Bridge`，因为将来还要接 Claude Code / Gemini CLI | 返回结构不能长成 Codex 专用形状 |
| L753-764 | Bridge 职责含「获取 Codex rate limits」「提供本地 HTTP API」「局域网自动发现」 | 协议形态 Spec 已定为 **本地 HTTP**（不是命名管道、不是共享文件） |
| L787-804 | `GET /v1/providers`、`GET /v1/accounts/{id}/usage`、`POST /v1/pair`，统一返回 `UsageResult` | 对外契约的形状 |
| L804-809 | **不得返回**：ChatGPT Access Token / Refresh Token / Cookie / `Codex auth.json` | 本文最重要的红线，直接否决了一条技术路线（§4 方案 B） |
| L1792-1806 | 缓存 < 5 分钟返回缓存；过期才请求 Codex；用户强制刷新忽略缓存；Bridge 回 `dataTimestamp` / `sourceTimestamp` | 与 Phase 4 的 `Freshness` / 「最后读取时间」同一套语义 |
| §53 L2255 规则 18 | `刷新失败不能清除最后一次成功数据。` | Bridge 读不到时必须回"旧数据 + 明确失败态"，与 Phase 4 保留策略同一条纪律 |
| §53 L2256 规则 19 | `Bridge 离线与 OAuth 失效必须区分。` | 方案 A 在这条上优于方案 B（§4） |
| §53 L2257 规则 20 | `Codex Direct 与 Bridge 必须共用 CodexProvider。` | Bridge 不是新 Provider，是同一 Provider 的另一个数据源（§45 L1688-1689 已给出两个 DataSource 的文件名） |
| §53 L2258 规则 21 | `任何内部 API 使用前必须明确其稳定性。` | **本节的实测就是这条规则的交付物**（§2、§7.1） |
| §54 L2295 | 禁止 `Codex Bridge 和 Codex Direct 做成两个Provider` | 与规则 20 从两面夹住同一个决定 |
| §54 L2325 | 禁止 `把IP地址当Bridge永久身份` | Phase 5 只做 localhost，但 `Account.bridgeId` 列已预留（§3） |
| §54 L2331 | 禁止 `把内部未公开API当稳定公开API设计核心架构` | 本文要量化的风险（§7.1） |
| L535 | `第一阶段禁止加入 Codex。` | 已由 Phase 0-2 遵守；Phase 5 才解禁 |

---

## 2. 本机实测证据

调研环境：Windows 10.0.26220 / x64，`%USERPROFILE%\.codex` 存在且已登录（`auth.json` 有内容）。**这台机器上就有一个真的 Codex，所以不需要靠文档推断。**

探针脚本放在仓库外的临时目录（`%TEMP%\codex-probe\`），**故意不入库**：它读的是本机登录态、一次性的，进了仓库就会变成一条将来会自己烂掉的"验收脚本"。复现命令写在 §8。

### 2.1 版本与命令面

```text
$ codex --version
codex-cli 0.121.0

$ codex app-server --help
[experimental] Run the app server or related tooling
Usage: codex app-server [OPTIONS] [COMMAND]
Commands:  generate-ts | generate-json-schema | help
  --listen <URL>   Supported: `stdio://` (default), `ws://IP:PORT`, `off`  [default: stdio://]
  --ws-auth <MODE> Websocket auth mode for non-loopback listeners
                   [possible values: capability-token, signed-bearer-token]
  --ws-token-file / --ws-token-sha256 / --ws-shared-secret-file / --ws-issuer
  --analytics-default-enabled   （默认关闭，需显式开启）
```

- 命令存在，且 Spec 点名的 **App Server 就是它**。
- 整个命令面自描述为 `[experimental]`（每个子命令单独也带这个标记）。
- transport 有三种，`ws://IP:PORT` 的非 loopback 监听**必须**带鉴权模式（capability-token 或 signed-bearer-token）。Spec Phase 6 的「IP / Port / Temporary Token」和 Phase 7 的一次性配对 Token，在 Codex 这一侧已有现成机制可类比。

### 2.2 协议方法，按"谁发给谁"分（本机 0.121.0 自己的 schema）

用 `codex app-server generate-json-schema --out <dir>` 生成 0.121.0 的 schema，再从 `v2/ClientRequest.json`、`v2/ServerRequest.json`、`v2/ClientNotification.json`、`v2/ServerNotification.json` 四个文件分别取 `method` 枚举——**方向很重要，所以分开列**：

```text
Bridge 能请求的（ClientRequest）
  initialize
  account/read
  account/rateLimits/read          ← Spec 点名的那一个，确实存在
  account/login/start
  account/login/cancel
  account/logout

Bridge 发的通知（ClientNotification）
  initialized                      （initialize 之后的固定第二步）

Codex 反向请求 Bridge 的（ServerRequest）        ← 容易漏掉的一条
  account/chatgptAuthTokens/refresh

Codex 推给 Bridge 的通知（ServerNotification）
  account/rateLimits/updated
  account/updated
  account/login/completed
```

**`account/usage/read` 在 0.121.0 里不存在**（全目录 grep `account/usage` 无命中）。新版官方文档写了这个方法和 `dailyUsageBuckets`（**二手**：经检索得到，本文未能一手抓取该页面确认，见 §7.2），说明它比本机版本新。**结论：Bridge 不能把任何方法名当成"Codex 一直都有"，必须先做版本/方法探测**（§7.1）。

`ServerRequest` 那一条要单独记一笔：App Server **会反过来要求客户端提供刷新后的 ChatGPT 令牌**（这个设计是给 VS Code 扩展那类"自己管登录"的客户端用的）。Bridge 按方案 A 不持有令牌，所以**必须显式实现这个反向请求的失败分支**——不能假装没收到。它什么时候会被触发、触发后 `account/rateLimits/read` 是否会失败，属于 §7.3 的未测项。

### 2.3 `account/rateLimits/read` 的字段形状（来自本机生成的 schema）

```text
GetAccountRateLimitsResponse
  rateLimits            : RateLimitSnapshot            # 向后兼容的单桶视图
  rateLimitsByLimitId   : map<limit_id, RateLimitSnapshot> | null   # 多桶，键例如 "codex"

RateLimitSnapshot   { credits?, limitId?, limitName?, planType?, primary?, secondary? }
RateLimitWindow     { usedPercent (int32, 必填), windowDurationMins? (int64), resetsAt? (int64) }
CreditsSnapshot     { hasCredits, unlimited, balance }
```

只有 `usedPercent` 是必填，其余全部可空——**Bridge 的解析器必须容忍 null**（实测确实见到 null，见 §2.5）。

### 2.4 真跑一次：三个数字全都拿到了

`initialize` → `initialized` → `account/rateLimits/read`，transport 用默认的 `stdio://`（**没有开任何端口**）。原样输出（无敏感字段，令牌一个都没出现）：

```json
{
 "rateLimits": {
  "limitId": "codex",
  "limitName": null,
  "primary":   { "usedPercent": 100, "windowDurationMins": 300,   "resetsAt": 1790936545 },
  "secondary": { "usedPercent": 40,  "windowDurationMins": 10080, "resetsAt": 1791419454 },
  "credits":   { "hasCredits": false, "unlimited": false, "balance": "0" },
  "planType":  "plus"
 },
 "rateLimitsByLimitId": { "codex": { ... 同上 ... } }
}
```

对照 Spec Phase 5 的验收目标：

| Spec 要确认 | 实测值 | 判定 |
| --- | --- | --- |
| `5H` | `primary.windowDurationMins = 300`（= 5 小时），`usedPercent = 100` | **拿到** |
| `Weekly` | `secondary.windowDurationMins = 10080`（= 7 天），`usedPercent = 40` | **拿到** |
| `Reset` | `primary.resetsAt = 1790936545` → 本机 `2026-10-02 18:22`；`secondary.resetsAt = 1791419454` → `2026-10-08 08:30` | **拿到** |

`initialize` 的返回还顺带给了 `codexHome`（`%USERPROFILE%\.codex`）和 `userAgent`（内含版本号），可用于 Bridge 的自检。

**Phase 5 的核心不确定性就此消除：可行。** 两个窗口 + 重置时间一次请求全回，无需解析日志、无需提取令牌。

两点要留意：
1. `resetsAt` 是 **epoch 秒**，而本项目 `QuotaWindow.getResetAt()` 约定是 **epoch 毫秒**——不换算就会显示成 1970 年。
2. `credits.balance` 是**字符串** `"0"`，不是数字。

### 2.5 离线降级路径能拿到什么（实测，结果不如预期）

Spec §53 规则 21（`任何内部 API 使用前必须明确其稳定性`，L2258）要求先把未公开接口的稳定性写清楚，所以这里确认了纯本地、零网络的一条退路：解析 `~\.codex\sessions\YYYY\MM\DD\rollout-*.jsonl`。

本机最新一个会话文件（20 MB / 5493 行）里：

```text
事件类型统计（节选）：
  event_msg/token_count     648
  token_usage_record        633
  turn_context / response_item/* ...

token_usage_record.payload:
  thread_id / turn_id / session_id / root_turn_id / response_id
  usage              {input_tokens, cached_input_tokens, cache_write_input_tokens,
                      output_tokens, reasoning_output_tokens, total_tokens}
  turn_token_usage   { 同上 }
  thread_token_usage { 同上 }

event_msg/token_count.payload.info:
  total_token_usage / last_token_usage / model_context_window(258400)

event_msg/token_count.payload.rate_limits:
  { "limit_id": "premium", "plan_type": "plus", "credits": {...},
    "primary": null, "secondary": null }      ← 关键
```

**实测结论：离线文件能可靠给出 token 计数，但给不出额度百分比**——本机样本里 `primary` / `secondary` 就是 `null`。所以离线路径只能作为「今日消耗了多少 token」的补充，**不能**当作 `5H / Weekly / Reset` 的备份来源。
另外一个坑：同一份数据在 rollout 文件里是 **snake_case**（`used_percent` / `limit_id` / `plan_type`），走 JSON-RPC 是 **camelCase**；而且会话文件可能被 zstd 压成 `.jsonl.zst`。写解析器前要先确认这两件事。

### 2.6 凭据边界（实测本机形状，未读取任何值）

`auth.json` 的**键路径**（值一律没打印）：

```text
auth_mode          : string(len=7)
OPENAI_API_KEY     : null
tokens.id_token     : string(len=1904)
tokens.access_token : string(len=1870)
tokens.refresh_token: string(len=196)
tokens.account_id   : string(len=36)
last_refresh        : string(len=30)
```

- 这台机器上确实是**明文 JSON 落盘**（默认 File 存储模式；`config.toml` 没有 `cli_auth_credentials_store = "keyring"`）。官方文档对它的定性是「像密码一样对待」。
- **但方案 A 根本不需要读它**：`codex app-server` 子进程自己会用登录态去换数据，Bridge 只见结果不见令牌。这让 Spec L804-809 那条「不得返回 Access/Refresh Token、Cookie、auth.json」的红线**天然满足**，而不是靠"我们小心地不打印"来满足。

---

## 3. 数据落到本项目哪里（映射表）

Bridge 的产物必须长成现有 `UsageResult` 的形状，`ui/` 与 `widget/` 才能零改动复用（Spec §45/§53 分层红线）。已有的挂钩点**已经预留好了**，不需要新造概念：

| Codex 字段 | 本项目落点 | 现成度 |
| --- | --- | --- |
| — | `model/UsageResult.Source.BRIDGE` | **已存在**（`UsageResult.java:36`） |
| — | `UsageError.BRIDGE_OFFLINE` / `BRIDGE_UNAUTHORIZED` | **已存在**（`UsageError.java:24-25`） |
| — | `AuthType.BRIDGE_TOKEN`、`AuthContext.KEY_DEVICE_TOKEN` | **已存在** |
| — | `Account.bridgeId` / `Database` 的 `bridge_id` 列 | **已存在** |
| `planType` | `UsageResult` 无对应字段，建议进 `Metric` 或 ProviderCapabilities | 需决定（§9 D2） |
| `primary{usedPercent,windowDurationMins,resetsAt}` | `QuotaWindow(id, label, usedPercent, remainingPercent, windowMinutes, resetAt)` | **形状一一对应**，仅需 `remaining = 100 - used` 与 秒→毫秒 |
| `secondary{...}` | 同上，**但 id/label 不能硬写"每周"**：`QuotaWindow` 注释（`QuotaWindow.java:6-8`）已明确要求不得假定 primary=5H、secondary=周；应直接用 `limitId` + `windowDurationMins` 生成 label | 现成约束，照办即可 |
| `credits.balance`（字符串） | `util/Money` 只管 CNY 格式化，额度百分比不该走 Money | 需决定放 Metric 还是 Balance |
| `rateLimitsByLimitId` 多桶 | `UsageResult.quotaWindows(List)` 是列表，天然放得下 | 已支持 |
| token 计数（§2.5） | `WidgetMetricId` 目前只有 `balance` / `today_usage` | **需要新指标 id**，属 Phase 5 之外（§9 D3） |

`ProviderCapabilities.reportsQuotaWindows()`（`ProviderCapabilities.java:53`）已经存在，正是 Codex 这类"没有余额、只有额度窗口"的提供方该返回 true 的那个开关。Phase 3 重定基线时留的这个口子，现在用得上。

---

## 4. 三条候选路线与取舍

| | A. 子进程跑 App Server（**推荐**） | B. 自己带令牌打 HTTP | C. 只读本地会话文件 |
| --- | --- | --- | --- |
| 做法 | Bridge 用 `stdin/stdout` 拉起 `codex app-server`，调 `account/rateLimits/read` | 读 `auth.json` 取 access token，自己 `GET https://chatgpt.com/backend-api/wham/usage` + `ChatGPT-Account-Id` 头 | 解析 `sessions/**/rollout-*.jsonl` |
| 实测可行性 | **已验证通过**（§2.4） | 路径与字段在 `openai/codex` 仓库 `codex-rs/backend-client` 源码里可确认（高置信），但**本文没有真跑过** | 部分可用：token 数有（§2.5），额度百分比是 `null` |
| 令牌边界 | **Bridge 从不接触令牌**，红线天然满足 | **直接违反 Spec L804-809**，且要自行处理刷新与失效 | 不接触令牌 |
| 稳定性 | 方法是本地生成 schema 的一部分，但仍标 experimental | 未公开接口，Spec §53 规则 21 要求先定性其稳定性、§54 L2331 禁止当稳定架构依赖 | 文件形状随版本变、可能被 zstd 压缩 |
| 时效 | 每次现取，可缓存 5 分钟（Spec L1792） | 同 A，但多一层鉴权失败态 | 只到"上次会话结束"，必然陈旧 |
| 判定 | **采用** | **否决**，写进代码注释防止后人"优化"成这条 | 仅作**补充**（今日 token 用量），不作额度来源 |

选 A 的附带好处：Codex 不在线 / 未登录时，子进程会给出可区分的失败，正好对上 Spec §53 规则 19「Bridge 离线 ≠ OAuth 过期」——B 路线则会把两者混成一个 401。

**代价**（要如实说）：Bridge 必须在用户机器上找得到**原生** `codex` 二进制。本机 `where codex` 只返回 npm 的两个 shim（`%APPDATA%\Roaming\npm\codex` 与 `codex.cmd`），原生 exe 在包目录三层之下（`...\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\codex\codex.exe`，Phase 5 步骤 1 实测确认）。另外还要为每次查询负责一个子进程的生命周期与超时。

---

## 5. 安全结论（本文的边界，不是实现细节）

1. **令牌不进 Bridge**：方案 A 下 Bridge 的输出里没有令牌，也就不可能泄漏。`docs/HANDOFF.md` 里「DB 永不出现明文 `sk-`」的同一条纪律，扩展为「Bridge 响应体永不出现 Codex 令牌」。
2. **不要把 Codex 的额度数据透传给手机之外的任何地方**。Spec L886 已把「手机↔Bridge」与「Bridge↔OpenAI」定性成两套独立认证系统，L859-869 定义了 Pair Token → Device Token 的降级路径。
3. **`ws://` 非 loopback 监听必须带鉴权**（§2.1 实测的 `--ws-auth`）。反过来也提醒我们自己的 localhost API：**只绑 `127.0.0.1`**，绑 `0.0.0.0` 就等于把额度（以及将来的配对入口）开放给整个局域网。
4. **日志与截图纪律**沿用 Phase 0-4 的做法：本次探针输出在进入本文之前已经人工核对过无令牌；脚本里的 redact 规则（JWT / `sk-` 前缀）保留在 §8 的复现命令里。
5. 本文期间**没有**读取、复制或修改 `auth.json` 的内容（只取了键名与长度）；**没有**登出、**没有**改动 Codex 配置；除一次只读查询外**没有**其他外部调用。

---

## 6. 与 Spec 现有约定的冲突检查

| Spec 约束 | 方案 A 是否冲突 |
| --- | --- |
| L804-809 不得返回令牌 / Cookie / auth.json | 不冲突（结构上不接触） |
| L2295 / §53 L2257 规则 20：Bridge 与 Direct 不做成两个 Provider | 需在计划里落实：**一个 `CodexProvider`，两个 DataSource**（§45 L1688-1689 已给出 `BridgeCodexDataSource` / `DirectChatGPTDataSource` 的文件名，L1744 明确第一版不拆 Gradle Module） |
| L2331 不把未公开 API 当稳定公开 API | 方案 A 用的是 App Server 公开方法面（本地生成 schema 可见），风险低于方案 B；仍要 §7.1 的版本下限保护 |
| L2325 不把 IP 当 Bridge 永久身份 | Phase 5 只做 localhost，不涉及；`Account.bridgeId` 列已存在，Phase 6/7 用得上 |
| §54 L2313 不为实时 Widget 每分钟后台轮询 | Bridge 缓存 5 分钟（L1792）+ 本项目现有闹钟节奏即可满足 |
| §53 规则 8/10 Widget 不直连 provider | 不变：Bridge 结果走的仍是 `UsageProvider.fetchUsage` → `UsageRepository` → `WidgetUpdateManager` |

---

## 7. 风险与**尚未知**的事（不粉饰）

### 7.1 版本漂移（最大的风险）
本机 0.121.0，官方文档描述的功能面更新（多了 `account/usage/read`）。**没有证据表明 `account/rateLimits/read` 的形状在 0.121 → 更新版本间保持稳定**，也没有任何"该方法受兼容性承诺保护"的书面依据。上游 issue 记录显示这一带一直在动（以下四条本次已逐个 `curl` 确认存在且标题相符）：

| 上游 | 说的什么 |
| --- | --- |
| [#14235](https://github.com/openai/codex/issues/14235) | 状态行显示的额度限制"不准 / 会消失" |
| [#15281](https://github.com/openai/codex/issues/15281) | 请求机器可读的额度数据（说明当时还没有稳定出口） |
| [#37934](https://github.com/openai/codex/issues/37934) | `/backend-api/wham/rate-limit-reset-credits` 返回 429（网页端与桌面端同时） |
| [#28823](https://github.com/openai/codex/issues/28823) | 5 小时计量不准 |

**是否已修复：未核对。** 本文只确认这些 issue 存在、标题与所指的接口/行为对得上，不替它们下结论。
→ 缓解：Bridge 侧做「Codex 版本 + 方法可用性」探测；探测失败时报 `BRIDGE_OFFLINE` 之外的明确"不受支持"态，**不得**因为读不到就把旧数据清空（§53 L2255 规则 18「刷新失败不能清除最后一次成功数据」，§54 另有一条同义禁止项）。

### 7.2 experimental 定性
`codex app-server` 自述 `[experimental] Run the app server...`，官方文档另有「experimental / 不支持生产负载」的表述（**该页面本次抓取失败，文档说法未经一手确认**）。

### 7.3 未测的失败形状
**没有实测**：未登录、令牌过期、`usedPercent` 缺失、Pro 计划无 5 小时窗口（**二手**：新版文档称 Pro 目前没有 5 小时窗口，本文未一手确认）时各返回什么；也没实测 Codex 反向发出 `account/chatgptAuthTokens/refresh` 时会发生什么（§2.2）。本文不能替它们定形状，Phase 5 计划里必须留出探测步骤，且探测**不能**通过登出用户来制造。

### 7.4 多桶 / 多账户
`rateLimitsByLimitId` 允许按 `limit_id` 分桶（本机只见到 `codex` 一个；离线样本里出现的是 `premium`）。**同一台机器多账户怎么区分**，未验证。

### 7.5 条款（ToS）
没能取到 OpenAI 条款正文（抓取 403），因此**无法给出"第三方工具读取用户自己本机登录态做个人监控"是否合规的一手结论**。方案 A 之所以安全，一半原因正是它压根不读凭据。唯一看到的相关书面限制是官方文档里那句「App-server authentication has never been permitted for commercial or hosted services」——**个人本机使用与"hosted service"不是一回事，但这条边界应当由用户自己确认，不要由本文代答**。

### 7.6 本机环境的特殊性
这台机器的 `~\.codex` 同时被其他 agent 工具使用（sessions 由外部工具产生，`latest` 会话文件 20 MB）。实测数字（`primary.usedPercent = 100`）反映的是**这台机器登录的那个账户当下的状态**，不代表将来验收时的状态。写验收脚本时要断言"字段存在且类型正确"，**不要**断言具体百分比。

---

## 8. 复现命令（Windows / PowerShell）

```powershell
# 1) 命令面与 transport
codex --version
codex app-server --help

# 2) 本机协议方法清单（不联网、不需要登录）
$P = "$env:TEMP\codex-probe"
codex app-server generate-json-schema --out "$P\schema"
Select-String -Path "$P\schema\codex_app_server_protocol.v2.schemas.json" -Pattern 'account/[A-Za-z/]+' -AllMatches |
  ForEach-Object { $_.Matches.Value } | Sort-Object -Unique

# 3) 真的读一次额度（stdio，不开端口；令牌不出现）
#    stdin 依次写入三行 JSON：initialize (id=1) / initialized / account/rateLimits/read (id=2)
#    发送前先对输出做 redact：'eyJ…' 与 'sk-…' 一律截断
node "$P\probe-rate-limits.js"

# 4) 离线文件形状（只读，不打印会话正文）
node "$P\inspect-local-shapes.js"
```

第 3 步的最小请求体：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientInfo":{"name":"ai-usage-feasibility-probe","version":"0.0.1"},"capabilities":{"experimentalApi":false}}}
{"jsonrpc":"2.0","method":"initialized"}
{"jsonrpc":"2.0","id":2,"method":"account/rateLimits/read","params":{}}
```

---

## 9. 需要用户拍板的点（**这些定下来才写 Phase 5 实施计划**）

| # | 决策 | 选项 | 我的倾向 |
| --- | --- | --- | --- |
| D1 | Bridge 用什么运行时 | .NET 8 单文件 / Node（本机已有 npm 版 Codex，路径发现现成）/ Go | **Go**：单 exe、无运行期依赖、跨"零第三方依赖"哲学最接近；Node 次之（本机确实现成） |
| D2 | Codex 没有余额、只有额度窗口，UI 上放什么 | ①`planType`+百分比作 Metric；②给 `UsageResult` 加 `plan` 概念；③Phase 5 只落库不上屏 | **③**：Phase 5 验收目标是"读得到 + 存得下"，上屏牵出 Widget 指标选择（Phase 3 遗留 ①），单独做 |
| D3 | 是否现在就加 `quota_5h` / `quota_weekly` 两个 `WidgetMetricId` | 加 / 不加 | **不加**，等 D2 上屏需求真出现（同 R17「没有消费者就不加」） |
| D4 | Phase 5 是否包含对 Android 的可调用 HTTP 面 | ①只 localhost、无配对；②按计划做 `/v1/*` + 手动填 IP/Port/Token（Phase 6 内容） | **①**：Spec Phase 5 只画到 `localhost API`；接入是 Phase 6 |
| D5 | Codex 版本下限怎么定 | ①钉死 0.121.0；②按"方法存在与否"探测、不钉版本 | **②**：探测不钉版本，钉版本会在用户下次 `npm update` 时自己炸 |
| D6 | 离线 token 用量（§2.5）要不要纳入 Phase 5 | 纳入 / 留后 | **留后**：它需要 zstd、snake_case 两套解析，和"确认 5H/Weekly/Reset"不是一个体量 |

---

## 10. 一句话总结

**Spec 的 Phase 5 是可做的，而且比预想的干净**：`codex app-server` 的 `account/rateLimits/read` 在本机一次调用就同时给出 5 小时窗口、周窗口和两者的重置时间，全程不需要接触 Codex 的令牌，因此 Spec 的凭据红线是"结构上满足"而非"操作上小心"。剩下的不确定性集中在**版本漂移**和**失败形状未测**两点上，都可以用启动探测和"失败不清空旧数据"来兜住。

**下一步**：按 §9 的六个决策点取得用户答复 → 出 `docs/PHASE-5-PLAN.md` → 交用户审批 → 才动代码。在此之前不新增任何 Bridge 代码。
