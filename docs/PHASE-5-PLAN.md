# Phase 5 实施计划 — Windows AI Usage Bridge MVP

> 依据：Spec `AI-Usage-Monitor-Development-Plan.md` Phase 5 章（L2015-2035：链路 `Codex → Bridge → localhost API`，验收目标只有 `5H` / `Weekly` / `Reset`）、§17（L711-729，点名 `account/rateLimits/read`）、§18（L752-764 Bridge 职责）、L804-809（不得返回 Access/Refresh Token、Cookie、`auth.json`）、L1792-1806（缓存 < 5 分钟回缓存，回 `dataTimestamp` / `sourceTimestamp`）、§53 规则 18/19/20/21（L2255-2258）、§54（L2295 / L2313 / L2325 / L2331）。
> 可行性已由本机实测确认，全部证据在 `docs/PHASE-5-BRIDGE-FEASIBILITY.md`（本文不重复其数据，只引用小节号）。
> 状态：**待用户审批。批准前不写任何代码。**

---

## 0. 前置事实（本计划撰写时实测，不是估算）

| 事实 | 实测值 | 来源 |
| --- | --- | --- |
| 本机 Codex | `codex-cli 0.121.0`，已登录 | `codex --version` |
| App Server | `codex app-server` 存在，自述 `[experimental]`；transport `stdio://`（默认）/ `ws://IP:PORT` / `off` | `codex app-server --help` |
| 额度接口 | `account/rateLimits/read` 在 `ClientRequest` 枚举里；一次调用返回 primary 300 分钟、secondary 10080 分钟、两者 `resetsAt`、`credits`、`planType` | 调研文档 §2.2、§2.4 |
| 本机 `codex` 的 PATH 解析 | 只有 npm 的两个 shim：`%APPDATA%\Roaming\npm\codex` 与 `codex.cmd`，**没有原生 exe**；真正的二进制在 `...\node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\codex\codex.exe` | `where codex` 输出两条 shim + 目录列举 |
| `account/usage/read` | **0.121.0 里不存在**（新版文档才有） | 调研文档 §2.2 |
| 反向请求 | `ServerRequest` 里有 `account/chatgptAuthTokens/refresh`（Codex 可能反过来问客户端要令牌） | 调研文档 §2.2 |
| 离线兜底 | 会话文件里 `rate_limits.primary/secondary` 实测为 `null`，**不能**当额度来源 | 调研文档 §2.5 |
| **Go 工具链** | **本机未安装**（`go` 不在 PATH，常见目录无） | 本次检查 |
| 其他可用工具链 | `.NET SDK 8.0.420` 与 `10.0.400` 已装；Node `v24.15.0` 已装；`winget` 与 `choco` 均可用 | `dotnet --list-sdks` |

> **步骤 0 需要用户单独同意**：装了 Go 才有后续。若不想装 Go，替代路径是改用已装的 .NET 8（用户此前选了 Go，所以本文默认仍走 Go，只在 §9 R1 记一笔风险）。

---

## 1. 目标与范围

**目标一句话**：Windows 上有一个能独立运行的小程序，它用自己的方式问到 Codex 的额度，缓存在本地，并通过 **只监听 127.0.0.1** 的 HTTP 接口把统一形状的数据交出去。

| 做 | 不做（属于后续阶段） |
| --- | --- |
| 找到本机 Codex 并探测其能力 | 二维码配对（Phase 7）、mDNS 自动发现（Phase 8） |
| `codex app-server` stdio 客户端 + `account/rateLimits/read` | 手机侧任何改动、手机手动填 IP/Port/Token（Phase 6） |
| 5H / Weekly / Reset 解析成统一形状 | Codex Direct（直连 OpenAI 的另一条数据源） |
| 5 分钟缓存 + `dataTimestamp` / `sourceTimestamp` | AUTO 路由（Phase 10）、图表、Claude Code / Gemini CLI |
| 失败时保留旧数据并分类错误 | 系统托盘、开机自启（§18 列了，但不是"确认读得到"的前置，留后） |
| `GET /v1/providers`、`GET /v1/accounts/{id}/usage` | `POST /v1/pair`（配对属于 Phase 7） |
| 响应脱敏，绝不携带令牌 | 把数据写进 Android 的 SQLite（手机根本不读） |

**"只落库不上屏"（用户答复 D2）在 Phase 5 的准确含义**：手机不参与，所以落库发生在 **Bridge 自己的本地文件**；Android 侧一行的代码和一张表都不改。上屏到 `UsageResult` / `QuotaWindow` 的口子留在 Phase 6。

---

## 2. 关键设计决定（含取舍）

| # | 决定 | 取舍 |
| --- | --- | --- |
| A1 | **进程模型：每次未命中缓存的查询现起一个 `codex app-server` 子进程，问完即关**（不常驻） | 优点：无生命周期管理、崩了不连坐、验收可重复；代价：每次多几百毫秒启动。因缓存 5 分钟（Spec L1792），实际开销可忽略。常驻连接留给真出现性能问题时再说 |
| A2 | **不读 `auth.json`，一个字节都不读** | 调研 §5 的红线从"小心不打印"升级为"结构上不接触"；顺带免疫令牌格式变更 |
| A3 | **反向请求必须显式实现失败分支**：收到 `account/chatgptAuthTokens/refresh` 就回 JSON-RPC error，并把本次查询归类为 `CODEX_AUTH_NEEDS_CLIENT` | 不实现的话，令牌到期时表现成"莫名其妙读不到"。这是 §53 规则 19「Bridge 离线 ≠ OAuth 失效」唯一能落地的地方 |
| A4 | **错误分类固定四种**：`CODEX_NOT_FOUND` / `CODEX_METHOD_UNAVAILABLE` / `CODEX_AUTH_REQUIRED` / `CODEX_TIMEOUT`，每种都携带"上次成功读数 + 其时间戳" | 直接对应用户能看懂的四句话；也是 §54「刷新失败以后清空旧数据」的反面保险 |
| A5 | **本地存储用单个 JSON 文件**（`%LOCALAPPDATA%\AIUsageBridge\state.json`），不引 SQLite | 就一个账户一组窗口 + 时间戳，SQLite 是过度设计；Go 标准库无 SQLite，引第三方就违反本项目"零第三方依赖"哲学 |
| A6 | **HTTP 只用 Go 标准库 `net/http`，硬编码只绑 `127.0.0.1:38411`**（端口可用 `--port` 改，**绑非 loopback 直接拒绝启动**） | Spec 只说"localhost API"；拒绝 `0.0.0.0` 是把调研 §5.3 的教训写进代码，而不是写进祈祷文 |
| A7 | **版本策略按 D5：探测不钉版本**——解析 `initialize` 返回的 `userAgent` 里的版本号，并用"方法是否存在"决定能力 | 钉死 0.121.0 会在用户下次 `npm update` 时静默失效；探测失败明确报 `CODEX_METHOD_UNAVAILABLE` |
| A8 | **仓库布局**：Go 代码放 `bridge/`，自带 `go.mod`，与 `app/` 平级；本阶段引入**第二套门禁** `go build ./... && go vet ./... && go test ./...` | Android 门禁不受影响（本阶段零改动），但每步仍要跑一次以证明没被牵连 |

---

## 3. 逐项设计

### 3.1 Codex 发现（步骤 1）

按顺序找，第一个能跑 `--version` 的为准：`--codex` 显式路径 → `CODEX_HOME`/PATH 里的 `codex` → `%APPDATA%\Roaming\npm\codex.cmd` → 常见安装目录。找到就记下路径与版本号；找不到直接 `CODEX_NOT_FOUND`。

**为什么必须列多个位置**：本机 `where codex` 只给到 npm 的 shim，给不到可直接或经子进程安全启动的原生二进制（§0）。步骤 1 实测确认：只按 PATH 找的实现在这台机器上会撞上 `.cmd`，列多个位置才找得到 `codex.exe`。

### 3.2 JSON-RPC 客户端（步骤 1-2）

`os/exec` 起子进程 + `bufio.Scanner` 逐行读 + `encoding/json`。发三帧：`initialize`（带 `clientInfo`）→ `initialized` → `account/rateLimits/read`。整段对话有超时（默认 8 秒），超时杀子进程并归 `CODEX_TIMEOUT`。

字段映射（对齐现有 Android 模型，为 Phase 6 预留）：

| Codex | Bridge 内部 | 陷阱（实测确认） |
| --- | --- | --- |
| `primary.windowDurationMins` | `QuotaWindow.windowMinutes` | 单位就是分钟，别当秒 |
| `primary.resetsAt` | `QuotaWindow.resetAt` | **epoch 秒**，Android 侧约定是毫秒，出站时乘 1000 |
| `usedPercent` | `usedPercent` / `remainingPercent = 100 - used` | 只有它是必填，其余可空（调研 §2.3） |
| `limitId` + 窗口时长 | 窗口 id / label | **不得**硬写"5 小时 / 每周"，见 `QuotaWindow.java:6-8` 注释 |
| `credits.balance` | `credits` | 是**字符串** `"0"`，不是数字 |
| `planType` | `planType` | 目前不进 UI（D2） |

### 3.3 缓存与新鲜度（步骤 3）

`state.json` 存 `{windows, planType, fetchedAt, sourceFetchedAt, lastError}`。命中条件：年龄 < 5 分钟且无 `--refresh`。返回体始终带 `dataTimestamp`（我这份数据多老）和 `sourceTimestamp`（Codex 那侧多新），让调用方自己判过期——Spec L1792-1806 原话。

失败时**不清空**：新错误写 `lastError`，`windows` 保持上一次成功的值（§53 规则 18 / §54 禁止项）。

### 3.4 localhost HTTP API（步骤 4）

```text
GET /v1/health      → {ok, codexVersion, capabilities}
GET /v1/providers   → [{id:"codex", name:"OpenAI Codex", authType:"bridge"}]
GET /v1/accounts/{id}/usage → UsageResult 形状的统一载荷
```

`?refresh=1` 忽略缓存。响应里**没有** `raw` 字段——不把上游 JSON 整段透传，这是脱敏最省事也最可靠的做法（`/v1/*` 的响应体一律由我们自己组装）。

### 3.5 脱敏（步骤 5）

出站前对**所有**字符串过一遍 redact（`eyJ…` JWT 形态、`sk-…`、`Bearer …`），并有一条测试专门喂假令牌证明它真的会被打码。日志同样走这个函数。

---

## 4. 分层与红线

1. 不修改 `app/` 下任何文件，因此 Android 的 §45/§53 分层红线本阶段不适用也不触碰；`AuthType.BRIDGE_TOKEN`、`Account.bridgeId`、`UsageResult.Source.BRIDGE` 等既有挂钩点**保持原样**（本阶段不消费它们，Phase 6 才消费——这与 R17「没有消费者就不加」一致：不加新钩子，也不删旧钩子）。
2. Bridge 不读取、不复制、不转发任何 Codex 凭据（A2）。
3. Bridge 不绑非 loopback（A6）。
4. Bridge 不向手机以外的网络暴露任何东西；本阶段没有任何外部 HTTP 出口（唯一出网的是 Codex 子进程自己的行为）。
5. 任何 `tools/**/*.ps1` 保存为 UTF-8 with BOM（沿用 HANDOFF §8）。

---

## 5. 测试与验收

### 5.1 Go 单元测试（不需要登录、不联网）

核心是 **fake app-server**：一个测试内的小 Go 程序（`testdata/fakecmd`），说同样的 JSON-RPC，可按脚本返回"正常 / 方法不存在 / 反向请求 refresh / 慢 / 带假令牌"。所有分支都能被测，**而且不用碰真实账户**。

计划用例（≥ 20 例）：Codex 发现顺序、握手缺 `initialized`、`usedPercent` 缺失、`resetsAt` 秒→毫秒、`credits.balance` 字符串、多 `limitId` 分桶、缓存命中/过期/强制刷新、四种错误分类各自保留旧数据、反向 refresh 请求被拒、redact 命中 JWT 与 `sk-`、非 loopback 绑定被拒。

### 5.2 本机验收矩阵（`tools/smoke/assert-bridge.ps1`）

| # | 断言 | 怎么证明它**会**失败（变异验证） |
| --- | --- | --- |
| B1 | 真跑 Bridge，`/v1/accounts/codex/usage` 里 5H / Weekly / **Reset** 三个字段存在且类型正确 | 把解析字段名改错一个 → 变红。**不断言具体百分比**（调研 §7.6：本机当下 `usedPercent=100` 只是巧合） |
| B2 | `--codex` 指向不存在的文件 → `CODEX_NOT_FOUND`，且旧读数仍在 | 让"找不到"回退到 PATH → 变红 |
| B3 | 5 分钟内第二次请求**不再起子进程**（用子进程计数证明） | 把缓存判断取反 → 变红 |
| B4 | `?refresh=1` 忽略缓存，子进程计数 +1 | 忽略 query 参数 → 变红 |
| B5 | 只监听 127.0.0.1：连本机局域网 IP 应连不上 | 把绑定改成 `0.0.0.0` → 变红（这条正是防"看起来过了其实裸奔"） |
| B6 | 响应体与日志里不出现 `eyJ` / `sk-` / `Bearer` | 用 fake app-server 返回一个含假 JWT 的字段 → 变红 |
| B7 | 版本探测：fake app-server 声称无 `account/rateLimits/read` → `CODEX_METHOD_UNAVAILABLE`，旧数据仍在（A7/D5） | 把探测改成"假定存在" → 变红 |
| B8 | 收到 `account/chatgptAuthTokens/refresh` → 明确拒绝并归类，不静默 | 删掉该分支 → 变红 |
| B9 | `dataTimestamp` / `sourceTimestamp` 随缓存年龄增长而变（不是固定值） | 写死时间戳 → 变红 |

**红线 grep 沿用现有做法**：脚本里加一条 `assert "auth.json" not read`——静态检查 Bridge 源码不含打开 `auth.json` 的语句（这是本项目 §53 红线 grep 的同族）。

### 5.3 门禁

- 每步固定：`cd bridge && go build ./... && go vet ./... && go test ./...`。
- 每步收尾仍跑 Android 门禁（`. .\tools\env.ps1; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --rerun-tasks`）并**记录真实数字**，以证明 Android 侧没被动。当前基线：**26 suites / 349 tests / 0 failures / 0 errors，Lint 0 error / 30 warning**（`95219d3` 上刚复跑过）。

---

## 6. 交付物

1. `bridge/`（Go module：`cmd/aiusage-bridge`、`internal/codex`、`internal/store`、`internal/server`、`internal/redact`）+ 单测 + `testdata/fakecmd`。
2. 可执行文件 `aiusage-bridge.exe`（本地构建产物，不入库）；`bridge/README.md` 写清启动方式与四个接口。
3. `tools/smoke/assert-bridge.ps1`（B1-B9，UTF-8 BOM）。
4. 文档：`README.md`（Phase 5 一行）、`docs/HANDOFF.md`（§2/§4/§5/§6/§9）、本文补 §10 实施记录。

---

## 7. 提交序列（每步：改 → 测 → 提交）

| 步 | 内容 | 完成判据 |
| --- | --- | --- |
| 0 | **装 Go**（`winget install GoLang.Go`，需用户单独同意）+ `bridge/go.mod` + `.gitignore` 增量 | `go version` 有输出；`go build` 空模块通过 |
| 1 | Codex 发现 + JSON-RPC 客户端骨架 + fake app-server 测试台 | 发现顺序、握手三帧、超时有单测 |
| 2 | `account/rateLimits/read` 解析 → 内部模型 + 秒→毫秒 + 可空字段 | B1 的解析部分单测绿；golden fixture 用调研 §2.4 的真实返回 |
| 3 | 缓存 5 分钟 + 四分类错误 + 失败保留旧数据 + `state.json` | B2/B3/B4 + 四分类单测绿 |
| 4 | `net/http` 只绑 loopback + `/v1/*` 三接口 | B5 绿；`/v1/health` 手测通 |
| 5 | 出站/日志脱敏 + 反向 refresh 请求分支 | B6/B8 绿 |
| 6 | 版本与方法探测 | B7 绿 |
| 7 | `assert-bridge.ps1` 跑通 B1-B9 + 文档（本文 §10） | 全绿 + Android 门禁数字不变 |

---

## 8. 决策记录

**用户已于 2026-10-02 答复（调研文档 §9）**：

| # | 决策 | 用户选择 |
| --- | --- | --- |
| D1 | Bridge 运行时 | **Go 单文件 exe**（已知代价：本机需装 Go 工具链） |
| D2 | 无余额、只有额度百分比的账户怎么显示 | **先只落库，不上屏** |
| D4 | Phase 5 是否含手机可访问的 HTTP 面 | **只到 localhost，不碰手机** |
| D5 | Codex 版本要求 | **方法探测，不钉版本** |

**由上述四条推出的、本文直接照办的**：D3（不新增 `quota_5h` / `quota_weekly` 指标 id）随 D2；D6（离线 token 用量）不做，随调研 §2.5 实测结论——它给不出百分比，价值有限。

**本文引入、需一并确认的默认值**（不同意就说改哪个）：

| # | 默认 | 备注 |
| --- | --- | --- |
| N1 | 端口固定 `38411`，只允许 `--port` 改，拒绝非 loopback | A6 |
| N2 | 状态文件 `%LOCALAPPDATA%\AIUsageBridge\state.json` | A5 |
| N3 | 子进程超时 8 秒 | A1 |
| N4 | 系统托盘 / 开机自启**不在本阶段** | §1 的"不做"表 |

---

## 9. 风险清单

| # | 风险 | 处置 |
| --- | --- | --- |
| R1 | Go 未安装；装工具链是对本机的改动 | 步骤 0 单独取得同意；失败则整体退回 .NET 8（本机已装），本文其余设计不变，只有 A8 目录形态要改 |
| R2 | `codex app-server` 自述 experimental，上游可能改方法名或形状 | A7 探测 + `CODEX_METHOD_UNAVAILABLE` + 保留旧数据；**不**因为读不到就清空（规则 18） |
| R3 | 未实测的失败形状（未登录 / 令牌过期 / refresh 反向请求） | 步骤 3/5 用 fake app-server 造这些形状测；**不通过登出真实账户来制造**（同 Phase 3/4 的设备纪律） |
| R4 | 版本漂移：本机 0.121.0 与新版文档能力不一致 | 已确认 `account/usage/read` 在本机不存在（调研 §2.2），所以本阶段不依赖它 |
| R5 | 多账户 / 多 `limit_id` 语义未验证（调研 §7.4） | 本阶段只处理 `rateLimits` 单桶 + `rateLimitsByLimitId` 原样带出，不做账户合并 |
| R6 | 本机 `~\.codex` 与其他 agent 工具共用，验收数字会变 | B1 只断言字段存在与类型，不断言百分比 |
| R7 | 验收脚本写出"永不会失败"的断言（Phase 3/4 两轮审查都栽过） | §5.2 每行都配一条变异验证，跑不红的断言不进脚本 |


---

## 10. 实施记录（2026-10-02）

### 提交

| 步 | 提交 | 内容 | 与计划的差异 |
| --- | --- | --- | --- |
| 0 | `3e140f0` | Go 工具链安装 + `bridge/` 模块骨架 | 安装走 `winget --source winget`（默认源会先试 msstore 并 0x80072efd 失败）；装到 `C:\Program Files\Go`，**不进当前会话 PATH** |
| 0b | `cf0977c` | 忽略 `bridge/*.exe` | `go build ./...` 会把 exe 拉到 go.mod 旁边，步骤 0 的提交把它带进了仓库（2.4 MB）。已 `git rm --cached`，产物留在 `3e140f0` 历史里未改写 |
| 1 | `5c9019c` | Codex 发现 + JSON-RPC 客户端 | 计划 §5.1 写的 fake 是 `testdata/fakecmd` 独立二进制；实作为**测试二进制自执行**（`AIUSAGE_FAKE_APPSERVER=<场景>`），同样走真实 `os/exec`+管道，但少一次构建步骤 |
| 2 | `9ba17d5` | `rateLimits` 解析 + 秒→毫秒 | 无 |
| 3 | `f8f2d57` | 缓存 / 四类错误 / 失败不清空 / `state.json` | 多了**第五类 `CODEX_UNKNOWN`**：无法归名的错误不该冒充能归名的（A4 原文只列四类） |
| 4 | `9cf3125` | 只绑 127.0.0.1 的 `/v1/*` + `main()` | 无 |
| 5 | `c72a2ac` | 出站脱敏 | 无 |
| 6 | `2a295ca` | 版本与方法探测 | 无 |
| 7 | 本次 | `tools/smoke/assert-bridge.ps1` + 文档 | B7/B8 不脚本化（见下） |

§0 前置事实里「`which codex` 失败、不在 PATH」一句**当时写错了**：`where codex` 能解析出来，只是解析到 npm 的 `codex` / `codex.cmd` 两个 shim，没有原生 exe。已改。

### 门禁

- Go：`gofmt` 无输出、`go vet ./...` 通过、**55 单测 0 失败**（逐包实测：discover 5 / codex 17 / bridge 16 / redact 5 / server 12）。
- Android（每步收尾复跑，证明未被牵连）：**26 suites / 349 tests / 0 failures / 0 errors**，`assembleDebug` 通过，Lint **0 error / 30 warning**。数字取自 JUnit / lint 的 XML（`gradlew -q` 不打印计数）。
- 变异验证 **20 次**，每次只红它该红的那条：
  - 步骤 1：显式路径允许回退 / 把 `.cmd` shim 当成可用发现 / 不回应 Codex 的反向请求
  - 步骤 2：去掉秒→毫秒 / 保留没有百分比的窗口 / 丢掉单桶视图回退 / 不排序（两次运行顺序即不同）
  - 步骤 3：失败时清空旧窗口 / 缓存永不过期且忽略 force / 把损坏状态当空的 / 去掉「登录失效」优先于「离线」
  - 步骤 4：接受任意绑定地址 / 忽略 `?refresh` / 复用上游字段名（透传的起点）
  - 步骤 5：失败消息不脱敏 / `limitName` 不脱敏 / 删掉 JWT 正则
  - 步骤 6：版本解析恒返回空 / 失败路径忘记版本号 / 去掉 `-32601` 能力分支（退化成 `CODEX_UNKNOWN`）
  其中步骤 5 的前两条第一次跑成了**编译失败**（被删掉的正是该包唯一的调用），补上占位后重跑才拿到该红的测试——「构建失败」不算变异验证的证据。

### 设备/本机验收（`tools/smoke/assert-bridge.ps1` → **27 checks / 0 failed**）

| # | 项 | 结果 | 证据 |
| --- | --- | --- | --- |
| B1 | 5H / Weekly / Reset 可读 | **通过** | 真读：`5 小时=91% reset=10-02 23:40`、`7 天=54% reset=10-08 08:30`；断言字段存在、类型正确、`used+remaining=100`、reset 是 epoch **毫秒**。**不断言百分比**（同日实测 100→31→82→86→91） |
| B2 | 找不到 Codex 且无旧数据 | **通过** | `--codex` 指向不存在的路径 → 503 + `CODEX_NOT_FOUND` |
| B3 | 5 分钟缓存命中 | **通过** | 第二次请求 `fromCache=true` 且 `dataTimestamp` 不变 |
| B4 | 强制刷新忽略缓存 | **通过** | `?refresh=1` → `fromCache=false` |
| B5 | 只绑 loopback | **通过** | `--host 0.0.0.0` 拒绝启动并退出非 0；本机 LAN 地址 `192.168.1.170` 连该端口**连不上** |
| B6 | 响应与状态文件不含凭据 | **通过** | 对 usage（冷/热）、providers、`state.json` 四处扫 JWT / `sk-` 形态，命中 0 |
| B7 | 方法不可用 | **不脚本化**（有意） | 要造出一个「没有该方法」的 Codex 只能登出或替换真实安装；改由 `TestMethodUnavailableKeepsEarlierWindows` + `IsMethodNotFound` 的变异覆盖 |
| B8 | 反向 refresh 请求 | **不脚本化**（有意） | 同上，真实触发需要令牌过期；由 `TestReverseRequestIsRefusedAndReadContinues`（真子进程走真实管道）与规则 19 分类测试覆盖 |
| B9 | 时间戳随新读数前进 | **通过** | 用 `--ttl 2s` 起一个实例，两次相隔 4s 的读数都 fresh 且 `dataTimestamp` 递增 |
| §54 | Bridge 不读凭据 | **通过** | grep `bridge/**/*.go` 无 `auth.json` 引用 |

「失败不清空」这一条在脚本里差点成为**永不会失败**的断言：种子实例的 `state.json` 是几秒前写的，默认 5 分钟 TTL 下它直接走缓存、根本没去碰坏掉的 `--codex`，于是 `degraded` 为空、检查失败——**修的是脚本，不是把断言删掉**：加 `--ttl 1s` 逼出真正的失败路径后才通过。同类问题在 Phase 3/4 审查里出现过两次，这次是自己先撞上的。

### 已知限制

1. 只服务一个 provider（`codex`），`/v1/accounts/{id}/usage` 对别的 id 一律 404——这是 Phase 5 的范围，不是 bug。
2. 无配对、无发现、无手机接入（Phase 6-8）；Bridge 也不做托盘/开机自启（§1 N4）。
3. `dataTimestamp` 与 `sourceTimestamp` 同值：Codex 的 `rateLimits` 载荷里**没有**源端时间戳（实测字段仅 usedPercent / windowDurationMins / resetsAt / credits / planType），所以「源时间」只能是「我们问到的时间」。Spec L1792-1806 要的两个字段都在，但语义上它们目前无法分开。
4. B7/B8 只能单测覆盖（见上表），真实形状仍未观测。
5. 未处理进程优雅退出：Bridge 靠外部杀掉；`state.json` 是临时文件+改名写入，所以被杀也不会写出半个文件。

---

## 11. Phase 5 复审处置（2026-10-03）

复审文档：`docs/PHASE-5-REVIEW.md`（另一个 Agent 只读审查所写，随本仓库提交时**原文未改**，以保持其结论可追溯）。本节记录四项的复现、修复与实测。

### 复现（改代码之前，本机跑同一条探针）

`cd bridge && go run ./bin/phase5-review-probe`：

```text
cold_error status=503 raw_secret_present=true
cold_notfound error_is_unknown=true
concurrent_success has_data=true err=<nil>
after_late_failure has_data=false err=<nil>
```

§1/§2/§3 当场复现。§4 是控制流事实（`CodexFetcher.Fetch` 只在成功分支挂 `RefusedRequests()`，三条失败返回都没有），复审也已说明未对真实账户制造退出登录——本项同样不制造。

### 提交

| 复审项 | 提交 | 内容 | 门禁 |
| --- | --- | --- | --- |
| §1 + §2 | `0eb16e2` | 冷失败的 `FailureError`（结构化类型 + 已脱敏消息即落盘那一份）；HTTP 出口对所有错误消息统一过掩码 | Go 55→**59**；探针 `raw_secret_present=false`、`error_is_unknown=false` |
| §3 | `568de0f` | `Service.tx` 把 load→fetch→save 整段串行化；`Store.Save` 改唯一临时名且失败必删 | Go 59→**63** |
| §4 | `0c21ba7` | `CodexFetcher.Fetch` 改具名返回 + 一个 `defer`，拒绝记录随每条路径带出；测试用**测试二进制自己冒充 app server**（真子进程、真 stdio JSON-RPC） | Go 63→**66 PASS 行**（含子测试） |
| §6.4 验收 | `8bb8f50` | `assert-bridge.ps1`：B2 改解析 JSON 断言 `error` 字段；新增两个真子进程场景（拒绝→换令牌类失败且文本带合成凭据；无拒绝→未归类文本带合成凭据）断言响应与 `state.json` 都不含凭据形状、并区分「替换上游文本」与「过掩码」两种正确行为；新增并发两条 `?refresh=1` 的进程级检查 | 见下 |

变异验证 **11 次尝试：10 次被指定测试命中，1 次无法表达**（每次只红它该红的那条）：

- §1/§2 批次 5 次。①冷路径返回原始上游串 → `TestColdFailureReturnsTheMaskedFailureItStored`；②出口不过掩码 → 直接删调用会让 server.go 不再引用 `redact` 而**编译失败**（编译失败不是证据），改写成 `redact.Text("") + detail` 后由 `TestStateErrorsAreMaskedAtTheOutlet` 命中；③类型退回 `CODEX_UNKNOWN` → `TestEachFailureClassArrivesOnAColdFailure` 与 `TestFailureWithoutDataIsServiceUnavailable`；④分类时忽略拒绝记录 → 前者与 `TestRefusedRefreshIsAuthRequiredAndKeepsWindows`；⑤落盘不脱敏 → **无法表达**（那正是 service.go 中 `redact` 的唯一调用），而「发出去的与存下来的分叉」由 ①对应的 served-vs-stored 比对从另一侧覆盖。
- §3 批次 4 次，全部命中：去掉事务锁 → 两条闩锁测试各命中（重复跑两轮稳定）；临时名改回固定 → `TestConcurrentSavesNeverMixAState`；重命名失败不清理临时文件 → 同一条；吞掉保存失败照样报成功 → `TestUnwritableStateFileIsReported`。
- §4 批次 2 次：删掉那个 `defer` → `TestRefusalTravelsWithEveryFailurePath` 的两个子测试；把「拒绝」改成回传一个带 accessToken 的结果 → 新加的线上证据断言 `refusal-code=-32601 refusal-payload-bytes=0` 与 codex 包原有的 `TestReverseRequestIsRefusedAndReadContinues` 同时命中。

### §3 的两个额外事实

1. **Windows 上并发 rename 覆盖同一目标是会被拒绝的**（`… state.json: Access is denied`）。所以并发保存的正确断言不是「每次都成功」，而是「落盘的那一份必须是完整自洽的一份、且不许留下临时文件」；测试按此写。单进程内由于事务锁已串行化，只有第二个进程共用 `--data-dir` 时才会遇到。
2. 复审自带的探针第三段现在**自己死锁退出**（rc=1）：它的闩锁是「先让 B 跑完再放行 A」，而事务锁让 B 根本无法在 A 之前进入——它要复现的交错已不可达。§3 改由确定性 Go 测试证明，进程级另由验收脚本的并发段覆盖（两条强刷都要有答案，且状态文件仍是完整读数）。

### 门禁（复跑）

- Go：`gofmt -l ./cmd ./internal` 无输出（复审探针文件在忽略目录 `bin/` 下、非 gofmt 格式，不计入），`go vet ./cmd/... ./internal/...` 通过，`go test ./cmd/... ./internal/...` 全绿。
- 逐包 PASS 行：bridge **24** / codex **17** / server **15** / discover **5** / redact **5**，合计 **66**（Phase 5 交付时 55：bridge 16、server 12）。
- Android：五个提交没有一个碰 `app/`（`git show --stat` 逐一核对），仍复跑一次门禁，免得「不影响」只是口头断言——**36 suites / 411 tests / 0 failures**，`assembleDebug` 通过，Lint **0 error / 31 warning**，与 Phase 6 收尾时逐项相同。
- `tools/smoke/assert-bridge.ps1`：**38 checks / 0 failed**（原 27；实测输出 `tools/smoke/out/bridge-accept.log`）。
- `tools/smoke/assert-bridge-phone.ps1`：手机半边对着修好的 Bridge 原样复跑，**57 checks / 0 failed**（一条断言都没改，Phase 6 的消费路径不受影响）。

### 仍不做的事（写明，不是遗漏）

- `/v1/*` 仍无鉴权、Bridge 仍只绑回环——那是 Phase 7 与 token 校验同批交付的内容（复审 §6 也未要求在本批做）。
- 不为了跑出「真实令牌过期」而登出这台机器的真实账户；该形状用真子进程伪 app server 走全链路验证。
- `dataTimestamp` 与 `sourceTimestamp` 仍同值（Codex 载荷无源端时间戳）。
