# Phase 6 实施计划 — 手机接入 Bridge

> 依据：Spec `AI-Usage-Monitor-Development-Plan.md` Phase 6 章（L2039-2055：`Android 手动填写 IP / Port / Temporary Token`，先打通 `Android → Bridge → Codex`，**目的是「确认架构正确」**）、§19（L780-811 Bridge API 与「不得返回凭据」）、§53 规则 19/20/23（L2256-2260）、§54（L2313 不为实时轮询、L2325 不把 IP 当 Bridge 永久身份）、§45（L1688-1689 已给出 `CodexProvider` / `BridgeCodexDataSource` 的文件名）。
> 上游已交付：Phase 5 的 `bridge/`（本机验收 27 checks 全绿，契约见 `docs/PHASE-5-PLAN.md` §10）。
> 状态：**2026-10-02 已批准，按 §7 七步实施中**。§8 的 D1=只到模拟器、D3=详情页上屏、D4=本阶段不进 Widget 由用户确认；D2 取①（文案按来源分支）。

---

## 0. 前置事实（本计划撰写时实测，不是推断）

### 0.1 手机能不能在不放开局域网的前提下连上 Bridge —— 实测：能（限模拟器）

| 动作 | 实测结果 |
| --- | --- |
| 宿主机起 Bridge（Phase 5 的默认绑定） | `netstat -ano`：`TCP 127.0.0.1:38411 ... LISTENING`，**没有** `0.0.0.0` 监听 |
| 宿主机自查 | `curl http://127.0.0.1:38411/v1/health` → `{"capabilities":{"balance":false,"quotaWindows":true},"ok":true,"provider":"codex","stateReadable":true}` |
| **模拟器内**发起同一请求 | `adb shell "printf 'GET /v1/health ...' \| nc -w 8 10.0.2.2 38411"` → **`HTTP/1.1 200 OK` + 同一 JSON** |
| 负对照（无人监听的端口） | `nc -w 4 10.0.2.2 39999` → `Connection refused`，证明上一条不是假通 |

结论：**`10.0.2.2` 是模拟器访问宿主 loopback 的别名**，所以「手机 → Bridge → Codex」这条链在模拟器上可以完整跑通，而 Bridge 继续保持 Phase 5 的**只绑回环**。真机在 Wi-Fi 上则拿不到 `10.0.2.2`，那才需要放开 LAN —— 而放开 LAN 是**安全边界变更**，见 D1。

### 0.2 应用侧现状（决定本阶段工作量的关键，全部逐条读码确认）

| 位置 | 现状 | 对 Phase 6 的含义 |
| --- | --- | --- |
| `app/src/main/AndroidManifest.xml:15` | `android:usesCleartextTraffic="false"`，且**没有** `networkSecurityConfig`；`res/xml/` 下只有三个 widget info | 明文 HTTP 必须走**按主机白名单**的 `network_security_config.xml`，不能把全局开关翻成 `true` |
| `storage/SqliteCredentialStore.java:117-122` | 凭据 type 的 `switch` 只有 `case API_KEY`，`default:` 抛 `UNSUPPORTED「暂不支持该认证方式」` | `BRIDGE_TOKEN` **现在连凭据都解不开**，这是第一个必须补的口子 |
| `auth/AuthType.java:17`、`auth/CredentialPayload.java:54-65`、`provider/AuthContext.java:33`、`model/UsageResult.java:36`、`model/UsageError.java:24-25`、`model/Account.java:23,68` | Bridge 相关钩子**全部已存在但无生产调用方**（只有测试引用）；`bridge_id` 列已持久化但无人读写 | 不用新造概念，但也别以为已经通了 —— 它们是形状，不是实现 |
| `model/UsageError.java:24-25` → `refresh/AccountRefreshManager.java:446-451` → `util/StatusWords.java:49` | `BRIDGE_OFFLINE / BRIDGE_UNAUTHORIZED` 的**映射与文案已经写好**，只是没有任何 provider 会抛出它们 | Phase 6 只要真的抛出来，状态文案就白拿 |
| `storage/UsageSnapshotCodec.java:51-62`、`:89-134` | `quotaWindows` 六个字段**编码与解码都齐全**（`UsageSnapshotCodecTest.java:78-86` 已钉） | 额度窗口能落库、能读回，不需要动 codec |
| 全仓库 `getQuotaWindows()` 的生产调用方 | **只有 codec 的 encode 一处**；`ui/`、`widget/`、`refresh/` 无一读取 | 「落库了但界面上永远看不见」—— 本阶段要么显示它，要么明确不显示（D3） |
| `ui/MainActivity.java:732-733` | `available = metric != null && value != 0d` | Bridge 结果若不带 `is_available`，详情页会**直接显示「账户不可用」** —— 这是必须处理的真实断点，不是假想 |
| `ui/MainActivity.java:736-738` | `todayUsage` 没有像列表/Widget 那样做「无余额」保护 | 无余额账户会显示 **`今日 0.00`**，即「一分钱没花」——正是 `AccountListActivity.java:272-278` 注释里刻意避免的误读 |
| `widget/WidgetMetricId.java:24,31` | 只有 `balance` / `today_usage` 两个指标 id，**没有任何额度指标**；`WidgetConfigActivity` 也没有选指标界面（Phase 3 遗留①） | 额度进 Widget 需要「新指标 id + 迁移 + 选择器」三件事，量级不小（D4） |
| `refresh/AccountRefreshManager.java:443-451` + `util/StatusWords.java:47-50` | `BRIDGE_UNAUTHORIZED` 被并到 `AUTH_REQUIRED`，而 `AUTH_REQUIRED` 的文案是 **「API Key 无效或已失效」** | 映射与文案确实在等 Phase 6，但**措辞是 DeepSeek 专用的**：Bridge 账户没有 API Key，只有设备令牌。授权类失败必须按来源分支文案（并入 D2） |
| `refresh/AccountRefreshManager.java:429-439` | `recordFailure()` 无论哪个 provider，失败快照都写 `source = DIRECT_API` | Codex 账户的失败历史会标错来源。步骤 2 顺带修，并把「来源正确」写进断言，否则历史里的 Codex 记录看起来像 DeepSeek 的 |
| `util/Http.java:21-27` | 只有一个静态 `Http.get`，**没有可注入传输层**；现有 provider 测试全部用「测试类内自定义 `UsageProvider` 实现」绕开网络 | Bridge provider 要能测，必须自己开一个传输接缝（A2） |
| 门禁基线 | `README.md:130` 与 `docs/HANDOFF.md:18` 均记 **26 suites / 349 tests / 0 failures**；Bridge 另有 55 个 Go 单测 | 两套门禁都要跑；本阶段会新增 Android 测试类，数字必须重新实测 |

### 0.3 Bridge 契约（Phase 5 已实现，本阶段照此消费）

```text
GET /v1/health                 → {ok, provider:"codex", capabilities:{balance:false, quotaWindows:true}, codexVersion?, lastFailureClass?}
GET /v1/providers              → [{id:"codex", name:"OpenAI Codex", authType:"bridge", reportsBalance:false, reportsQuotaWindows:true}]
GET /v1/accounts/codex/usage   → {state:{codexVersion, planType, creditsBalance, windows:[{id,label,usedPercent,
                                  remainingPercent,windowMinutes,resetAtMillis}], sourceFetchedAt, lastFailure?},
                                  source, dataTimestamp, sourceTimestamp, fromCache, degraded?}
无鉴权（Phase 5 只绑回环，所以没有 token 校验；见 D1）；读失败但有旧数据 → 200 + degraded.class；无数据 → 503 + {error: class}
```

---

## 1. 目标与范围

**目标一句话**：让手机把一个 Codex 账户当成普通账户来用 —— 点刷新能拿到额度窗口、能落库、失败时显示对的原因且不清掉屏幕上的数字，同时 DeepSeek 的老行为零回归。

| 做 | 不做 |
| --- | --- |
| `CodexProvider`（一个 Provider）+ `BridgeCodexDataSource`（Phase 6 只有这一个数据源） | `DirectChatGPTDataSource`（Direct OAuth 是 Phase 9） |
| 纯函数 `BridgeUsageParser`：Bridge JSON → `UsageResult`（含 `QuotaWindow`） | 二维码配对 / Pair Token → Device Token（Phase 7） |
| 手动填写 Bridge 地址与端口（+ 凭据里的 token 字段，见 A4） | mDNS 自动发现（Phase 8）、`bridges` 表与设备管理 |
| `BRIDGE_TOKEN` 走 Keystore 加密凭据链 | 把 Bridge 绑定到 LAN（**除非 D1 选 6b**） |
| 详情页显示额度窗口与重置时间，并修掉 §0.2 那两个断点 | Widget 新增额度指标（见 D4，倾向留后） |
| 失败态映射到既有的 `BRIDGE_OFFLINE / BRIDGE_UNAUTHORIZED` | 图表、`daily_usage` 改造（额度账户没有余额差可累计） |
| 模拟器上的端到端设备验收 | 真机 LAN 验收（同 D1） |

---

## 2. 关键设计决定（含取舍）

| # | 决定 | 为什么这么选 |
| --- | --- | --- |
| A1 | **一个 `CodexProvider`，数据源可换**（Spec §53 规则 20、§54 L2295 双向禁止） | Phase 6 只装 Bridge 源；将来 Direct 是同一个 Provider 的另一个源，不是第二个 Provider |
| A2 | **打开一个传输接缝**：`BridgeTransport`（`String get(String url, Map<String,String> headers)`）+ `HttpURLConnection` 实现；`BridgeCodexDataSource` 只依赖接口 | 现有测试全靠「自定义 UsageProvider」绕过网络，那是绕开而不是覆盖。有了接缝，401/503/超时/坏 JSON 都能**在宿主机 JVM 上真测**，且不引入第三方 HTTP 库 |
| A3 | **地址不是凭据、也不是身份**：`bridge_url`（形如 `http://10.0.2.2:38411`）存进凭据 payload JSON，与 token 同条加密记录一起存；**不占用 `bridge_id`** | Spec L2325 明令「不要把 IP 当 Bridge 永久身份」，`bridge_id` 要留给 Phase 7 的配对身份。走 payload 还省一次 schema 迁移，且轮换 token 时地址与密钥天然一起更新。代价：地址不是明文列，将来做设备列表时要迁到 `bridges` 表 —— 记进 §9 风险 |
| A4 | **token 只进请求头**（`Authorization: Bearer …`），永不进 URL；`BridgeAuthAdapter` 把 payload 变成 `AuthContext.KEY_DEVICE_TOKEN`；`SqliteCredentialStore:117` 的 `switch` 补 `case BRIDGE_TOKEN` | URL 会进日志与异常串；头不会。这也让 §0.2 那条 `UNSUPPORTED` 死路变成有测试的活路 |
| A5 | **`is_available` 由 Bridge 的应答本身决定**：200 且带窗口 → `1`；否则不带 | 不改这一条，Codex 账户详情页会永远显示「账户不可用」（§0.2）。语义也是对的：Bridge 答得上来就是可用 |
| A6 | **额度账户不显示「今日 0.00」**：`MainActivity:736` 补上与列表同样的保护 —— 无余额时显示 `—` | 显示 0.00 是**错误信息**而不是缺信息；`AccountListActivity.java:272-278` 已有先例与注释 |
| A7 | **详情页新增「额度窗口」卡片**（label / 已用% / 剩余 / 重置时间），Widget 本阶段不动 | Phase 5 的 D2 说「先只落库不上屏」，Phase 6 就是它承诺的「上屏」。落库已经能用（codec 已支持），所以这一步能独立验收；Widget 那条要动指标 id + 迁移 + 选择器，量级差一个台阶（D4） |
| A8 | **明文 HTTP 走按主机白名单的 `network_security_config.xml`**：只放行 `10.0.2.2`、`localhost`（选 6b 时再加用户填的 LAN 主机），`usesCleartextTraffic` 保持 `false` | 把全局开关翻成 `true` 等于为一个大发 HTTP 的 app 撤掉整条保护；白名单是同一功能的最小代价实现 |
| A9 | **文案按来源分支，映射四行**：`CODEX_NOT_FOUND→BRIDGE_OFFLINE`、`CODEX_TIMEOUT→BRIDGE_OFFLINE`、`CODEX_AUTH_REQUIRED→BRIDGE_UNAUTHORIZED`、`CODEX_METHOD_UNAVAILABLE`/`CODEX_UNKNOWN→` 新文案（见 D2） | 规则 19 要求「电脑离线」与「授权失效」可分辨，而 `StatusWords` / 映射**已经写好在等**（§0.2）。第 4 类既不是离线也不是授权，硬塞进两者之一就是撒谎。`AUTH_REQUIRED` 现文案「API Key 无效或已失效」只对密钥类账户成立（§0.2），因此 `StatusWords` 需要按来源给词，而不是再共用一条 |

---

## 3. 逐项设计

| 文件 | 动作 |
| --- | --- |
| `provider/codex/CodexProvider.java`（新） | `getId()="codex"`、`getSupportedAuthTypes()=[BRIDGE_TOKEN]`、`getCapabilities()`：`reportsBalance=false / reportsQuotaWindows=true / widgetMetrics=[]`（D4 决定何时非空）、`recommendedRefreshIntervalMs=300_000`（与 Bridge 的 5 分钟缓存对齐，**不是越勤越好**：§54 L2313 禁每分钟轮询） |
| `provider/codex/BridgeCodexDataSource.java`（新） | 用 `BridgeTransport` 打 `GET {base}/v1/accounts/codex/usage`；401/403 → `BRIDGE_UNAUTHORIZED`；503 → 按 body 的 class 分流；超时/不可达 → `BRIDGE_OFFLINE` |
| `provider/codex/BridgeUsageParser.java`（新，**纯函数**） | JSON → `UsageResult`：窗口六个字段、`resetAtMillis` 直接用（Bridge 已换成毫秒）、`degraded.class` → `UsageError`、A5 的 `is_available`。仿 `DeepSeekBalanceParser` 的形态，好测 |
| `util/BridgeTransport.java` + `util/HttpBridgeTransport.java`（新） | A2 接缝；实现走 `HttpURLConnection`，带 `Authorization` 头与超时 |
| `auth/BridgeAuthAdapter.java`（新）+ `storage/SqliteCredentialStore.java:117`（改） | `BRIDGE_TOKEN` 分支；坏 payload 抛 `BRIDGE_UNAUTHORIZED` |
| `auth/CredentialPayload.java`（改） | 加 `bridgeUrl` 键与 `forBridge(url, token)` / 两个 extract；`deviceToken` 键已存在（:54-65） |
| `ui/account/AccountEditActivity.java:354-355`（改） | provider 决定 authType，不再硬写 `API_KEY`；选 Codex 时显示「Bridge 地址 + 端口 + Token」三个输入 |
| `ui/MainActivity.java:736`（改） | A6 的 `—`；另加 A7 的额度窗口卡片 |
| `util/StatusWords.java`（可能改） | 第 4 类失败的新文案（D2 定了再写） |
| `AndroidManifest.xml` + `res/xml/network_security_config.xml`（新） | A8 |
| `AppGraph.java:55-63`（改） | `registerBuiltIns()` 里注册 `CodexProvider`，与 DeepSeek 同一守卫模式（该方法是 JVM 单例且不幂等） |

**不碰**：`UsageSnapshotCodec`、`Database` schema（A3 因此省掉 v3 迁移）、`widget/`（D4）、刷新链路 `AccountRefreshManager` 的锁与凭据代次逻辑（Phase 2/3 的成果，回归风险最高的地方）。

---

## 4. 分层与红线

1. `ui/` 与 `widget/` 不直连 provider（§53 规则 7/8）：Codex 账户照旧走 `AccountRefreshManager → UsageRepository → 界面`。
2. Provider 不碰 Widget（规则 10），结果统一是 `UsageResult`（规则 23）。
3. Token 是凭据：只进 Keystore 加密的 `credentials` 行，**不落 URL、不落日志、不进异常串**；测试里断言异常 message 不含 token 字面量。
4. 一个 Codex 账户可以进多个 Widget、一个 Widget 可以显示多个账户 —— 本阶段不动 Widget，但也不许破坏这条（规则 15/16/17 的现有测试必须继续绿）。
5. Bridge 侧：若 D1 不选 6b，则**一行 Go 服务代码都不需要为「对外可达」改动**，Phase 5 的 loopback 守卫继续生效。

---

## 5. 测试与验收

### 5.1 宿主机 JVM（预计新增 ~40 例，全部不需要网络）

| 目标 | 关键用例（每条都要能被变异证伪） |
| --- | --- |
| `BridgeUsageParser` | 正常两窗口；`windows` 缺失；`usedPercent` 越界；`resetAtMillis=0`；`degraded.class` 四类各自映射到哪个 `UsageError`；200 无窗口 → 不可用；A5 的 `is_available` |
| `BridgeCodexDataSource` + 假 `BridgeTransport` | 200 / 401 / 503(带 class) / 连接失败 / 超时 / 坏 JSON / 响应过大；**断言请求头带 Bearer 且 URL 里没有 token** |
| `SqliteCredentialStore` | `BRIDGE_TOKEN` 能解出 `AuthContext`；坏 payload 抛 `BRIDGE_UNAUTHORIZED`；**原有 `API_KEY` 行为零变化** |
| A6/A7 的显示口径 | 无余额 → 今日 `—` 而非 `0.00`；有窗口 → 卡片渲染字符串来自 `QuotaWindow` 的 label，不硬编码「5 小时」 |
| 凭据代次与删除竞态 | 用既有 `CredentialEpochDropsStaleResultTest` / `DeleteRaceDropsOrphanTest` 的模式，把 provider 换成 Codex 再跑一遍（**这两个文件是本项目的看家测试，必须覆盖新链路**） |
| 规则 19 的可分辨性 | 一个用例显式断言：Bridge 不可达 与 token 失效 产生的状态**不相同** |

### 5.2 设备/本机验收（`tools/smoke/assert-bridge-phone.ps1`，UTF-8 BOM）

| # | 断言 | 它能怎么失败 |
| --- | --- | --- |
| C1 | 宿主机起 Bridge（**只绑回环**）；模拟器里 app 添加 `codex` 账户指向 `http://127.0.0.1:38411`（经 `10.0.2.2` 等价路径，用 §0.1 实测的那条）→ 刷新成功，详情页出现两个额度窗口与重置时间 | 关掉 Bridge 再起 → 若仍显示「成功」就是假 |
| C2 | 数字与 Bridge `/v1/accounts/codex/usage` 的 `usedPercent` 逐项一致（**比对本机读数，不与历史百分比比**） | 解析错位/单位错 → 不一致 |
| C3 | 杀掉宿主机 Bridge 后再刷新：显示「电脑离线」**且余额区数字仍在** | 任何「失败即清空」的实现回归 → 变红（规则 18/19） |
| C4 | 把 token 改错后刷新：显示授权类失败，与 C3 的「电脑离线」**文案不同**，且**不含「API Key」字样**（§0.2：现有 `AUTH_REQUIRED` 文案就是「API Key 无效或已失效」，复用即变红） | 合并两类，或直接把 Codex 挂到 DeepSeek 的文案上 → 变红 |
| C4b | 失败落库的 `usage_snapshots.source` 对 Codex 账户是 `BRIDGE` 而不是 `DIRECT_API`（先给 C4b 写一条会红的断言，再改 `recordFailure`） | 不修 `recordFailure` → 变红 |
| C5 | DeepSeek 两个真实账户**零回归**：现有 `assert-two-live-accounts.ps1` / `assert-last-success.ps1` / `assert-daily-usage.ps1` 全部仍绿 | 凭据 `switch` 或注册改动影响老链路 → 变红 |
| C6 | 落库与重启可见：杀 app 再进，窗口与「更新于」时间仍在（证明 codec 真的往返了 `quotaWindows`） | codec 漏字段 → 变红 |
| C7 | `logcat` 里不出现 token 字面量（脚本 grep 真值），且**该 grep 先被一次故意的错误实现证伪过** | 日志泄漏凭据 → 变红 |
| C8 | 明文 HTTP 白名单生效：app 能访问 `10.0.2.2`，但访问一个外网 `http://` 主机被系统拦下 | 把全局 `usesCleartextTraffic` 翻真的做法 → C8 变红（**这条是防我自己偷懒**） |

若 D1 选 6b，追加 C9（真机同 Wi-Fi 可达 + 无 token 请求被 401 拒）与 C10（Bridge 放开 LAN 时 `--require-token` 缺失则**拒绝启动**，而不是匿名开放）。

### 5.3 门禁

- 每步：`. .\tools\env.ps1; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --rerun-tasks`，**记录真实数字**（基线 26 suites / 349 tests / 0 failures，Lint 0 error / 30 warning）。
- Bridge 若因 D1=6b 有 Go 改动，同步跑 `cd bridge && gofmt -l . && go vet ./... && go test ./...`（基线 55）+ 重跑 `assert-bridge.ps1`（基线 27 checks）。
- 老验收脚本一律复跑（C5 不是可选项）。

---

## 6. 交付物

1. 上述新增/修改的 Java 文件 + 测试类。
2. `res/xml/network_security_config.xml`；manifest 一处改动。
3. `tools/smoke/assert-bridge-phone.ps1`（含每条断言的变异说明）。
4. 文档：`README.md`（Phase 6 一行 + 如何把 app 指向本机 Bridge）、`docs/HANDOFF.md`、本文 §10 实施记录。

---

## 7. 提交序列（每步：改 → 测 → 提交）

| 步 | 内容 | 判据 |
| --- | --- | --- |
| 0 | 纯函数解析器 + 数据源接缝 + 假传输测试（不接线，先能解析真的 Bridge JSON） | 新单测绿；DeepSeek 用例零变化 |
| 1 | `BRIDGE_TOKEN` 凭据链（adapter + store switch + payload） | 凭据往返可用；老 `API_KEY` 行为不变 |
| 2 | `CodexProvider` 注册 + capabilities + 刷新链跑通（JVM 假传输端到端） | 规则 19 两态不同；代次/删除竞态测试覆盖新链路 |
| 3 | 两个显示断点（A5 `is_available`、A6 今日 `—`） | 对应用例 + 截图 |
| 4 | 详情页额度窗口卡片（A7） | C1/C2 可达 |
| 5 | 明文 HTTP 白名单（A8）+ 账户编辑界面三个输入 | C8 绿 |
| 6 | `assert-bridge-phone.ps1` 全绿（C1-C7）+ 老脚本复跑 + 文档 | 全部门禁数字实录 |
| 7 | **仅当 D1=6b**：Bridge 的 LAN 绑定 + token 校验 + C9/C10 | 单独一步，单独提交 |

---

## 8. 需要用户拍板（定了我才写代码）

| # | 决策 | 选项 | 我的倾向 |
| --- | --- | --- | --- |
| **D1** | 本阶段是否把 Bridge 放开到局域网 | ①**只到模拟器**（§0.1 已实测可通，Bridge 保持只绑回环，零新增攻击面）；②顺带做真机 LAN + Temporary Token 鉴权 | **①**。Spec Phase 6 的目标写的是「确认架构正确」，模拟器路径已能完整证明这条链；而且 ② 是本项目第一次对外可达，鉴权（现在 `/v1/*` **完全没有鉴权**）必须一起做完才许绑定，硬塞进本阶段会把两件事的验收搅在一起 |
| **D2** | 失败文案怎么分（§0.2 查出两处会把话说错） | ①按来源分支：Codex 用「电脑端未连接 / 设备令牌无效 / 电脑端 Codex 不支持此查询」，DeepSeek 保持现有「API Key 无效或已失效」；②Codex 复用现有 `AUTH_REQUIRED` 文案；③所有账户统一改文案 | **①**。②会让 Codex 账户显示「API Key 无效或已失效」，而它压根没有 API Key；③去动 DeepSeek 的成熟文案，风险和收益不成比例（R1） |
| **D3** | 额度窗口是否本阶段上详情页 | ①上（A7）；②仍只落库，等 Widget 指标一起做 | **①**。Phase 5 的「先只落库」承诺的就是「有人消费时再上屏」，本阶段就是那个时点；否则 Phase 6 做完屏幕上什么都看不见，验收只能靠读库 |
| **D4** | 额度指标是否本阶段进 Widget | ①不进（详情页够用）；②一起进 | **①**。②要新指标 id + `metric_ids` 迁移 + 每 Slot 选择器（Phase 3 遗留①），会把这个阶段撑成两个；且「额度百分比」在 2×1 小组件上的排版需要单独设计 |

（`Account.bridgeId` 本阶段继续空置 —— 它是 Phase 7 的配对身份，不是地址；A3 已解释为什么不拿它当 IP 字段用。）

---

## 9. 风险清单

| # | 风险 | 处置 |
| --- | --- | --- |
| R1 | 凭据 `switch` 与 `AppGraph` 注册是全账户共用改动，碰坏 DeepSeek 就是回归事故 | 步骤 1/2 各自独立提交；C5 强制复跑三个老验收脚本；`DeepSeekProviderStabilityTest` 的哈希基线不许顺手重定 |
| R2 | 模拟器 `10.0.2.2` 行为依赖宿主的防火墙/代理设置，换机器可能不通 | 已在本机实测并留下命令（§0.1）；不通时按 C1 的失败信息定位，不改代码去绕 |
| R3 | A3 把地址塞进 payload，Phase 7 做多 Bridge 时要迁移 | 明确记为技术债，Phase 7 引入 `bridges` 表时一并处理；本阶段不提前建表（没有第二个 Bridge 之前它是空表） |
| R4 | `/v1/*` 目前无鉴权；若有人据此提前把 Bridge 绑到 LAN，等于向全网公开账户额度 | D1 选 ① 就不发生；步骤 7 单独成步且必须与 token 校验同批交付（C10） |
| R5 | 额度账户没有余额，`daily_usage` 的「今日用量」对它天生无意义 | A6 显示 `—`；本阶段不试图给额度账户造「今日用量」，那是另一件事（额度窗口本身就是答案） |
| R6 | 验收脚本再犯「永不会失败的断言」（Phase 3/4/5 三轮都栽过） | §5.2 每行都写了「它能怎么失败」；C7 还额外要求先证伪一次 |
| R7 | 详情页加卡片可能挤掉既有区块空间（Phase 4 加列表时同类问题） | 以截图为准，必要时折叠为「最近一次读数 + 两个窗口」 |

---

## 10. 实施记录（2026-10-03）

### 提交

| 步 | 提交 | 内容 | 与计划的差异 |
| --- | --- | --- | --- |
| 0 | `60598f4` | `BridgeUsageParser`（Bridge JSON → Bridge 单位的 `UsageResult`） | 无 |
| 1 | `64ff288` | `BRIDGE_TOKEN` 凭据链：adapter、`SqliteCredentialStore` 的 `case`、payload | token 在库里**可选**（Phase 5 的 `/v1/*` 不鉴权，必填等于假承诺） |
| 2 | `b12aeb2` | `CodexProvider` 注册 + capabilities + 失败快照来源 | 顺带把 `recordFailure` 的 `source` 从硬编码 `DIRECT_API` 改成按凭据机制推导（C4b 的前半） |
| 3 | `7168563` | `is_available`、今日 `—`、按来源分支的失败文案 | 文案分支扩到四处（`AUTH_REQUIRED` 原文只对密钥账户成立） |
| 4 | `f9cd188` | 详情页额度窗口（新增 `QuotaWords`） | 重置时间多了「重置时间已过 / 未知」两种措辞：过期时刻不等于「已重置」 |
| 5a | `a2e6e4b` | 按主机白名单的明文 HTTP 例外 | **subject 写错了**：重复了步骤 1 的措辞。历史已提交，不改写，在此登记 |
| 5b | `d68e7e1` | 账户表单：服务商选择 + 地址 + 可选令牌 | 服务商在编辑态固定（改服务商＝换凭据机制，不是改名字） |
| — | `41eed22` | README Phase 6 + 详情页两处 DeepSeek 专用措辞分支 | 实跑后又发现更多，见 6a |
| 6a | `c1f73d0` | **详情页发起查询的路径**（计划里没有这一步） | 见下 |
| 6 | 本次 | `tools/smoke/assert-bridge-phone.ps1` + 本文档 | C3 改成可证形式，见下 |

计划 §7 的七步里没有「详情页从哪里拿凭据」这一步。步骤 5 做完在模拟器上实跑：Codex 账户点「查询余额」什么都不发生。`MainActivity.queryBalance()` 和 `onResume` 的门都以 `keyInput` 为唯一凭据来源，而 Codex 账户没有那个输入框——既不发请求，也就不落任何行。设备库里三个 `provider_id='codex'` 账户的 `usage_snapshots` 是 **0 行**（同期两个 DeepSeek 账户 172 / 111 行）。`c1f73d0` 补这一步：查询走 `AccountRefreshManager.refresh(account)`（由刷新链去解密凭据），门走 `canQueryNow()`（Bridge 账户读凭据里的地址、DeepSeek 读输入框），凭据卡片与所有 DeepSeek 专用措辞按服务商分支。

C3 原文「显示『电脑离线』**且余额区数字仍在**」在本机做不到，也不该做：详情页失败时 `showError()` 会用错误文本替换读数区，DeepSeek 一直是这个行为。规则 18 真正可证的形式是两条，脚本按它们断言——库里该账户 `success=1` 的行仍在且 `usage_data` 仍带 `quotaWindows`；列表行的文案是「Bridge 未连接 · 最后成功数据」。

### 门禁

- Android：**36 suites / 411 tests / 0 failures / 0 errors**（Phase 5 收尾时 26 / 349），`assembleDebug` 通过。Lint **0 error / 31 warning**：worktree 量的基线 28，加 `AndroidGradlePluginVersion`、`NewerVersionAvailable`（两次版本探测，文件未动）与 `UnusedAttribute`（`networkSecurityConfig` 需要 API 24，minSdk 仍是 23，**不压制**）。数字一律从 `test-results/*.xml` 与 `lint-results-debug.xml` 数。
- Go 侧本阶段零改动，`assert-bridge.ps1`（基线 27 checks）未重跑。
- 变异验证：步骤 6a 的 `BridgeQueryPathWiringTest` 被 **6 个变异**逐个命中（用打字密钥代替凭据、门忽略 Bridge 地址、凭据卡片不分支、caption 硬编码 CNY、按钮文案硬编码、监听器无条件接线），每个只红它该红的那条。断言写成**顺序与计数**而非「文件里某处有这个字符串」，因为后者把分支删掉也能通过。

### 设备验收（`tools/smoke/assert-bridge-phone.ps1` → **57 checks / 0 failed**）

| # | 项 | 结果 | 证据 |
| --- | --- | --- | --- |
| C1 | 模拟器经 `10.0.2.2` 连只绑回环的 Bridge | **通过** | 详情页出现两行窗口、状态「账户可用」、按钮是「查询额度」、屏幕显示它正在调的地址；并断言读数**取代**了占位文案 |
| C2 | 屏上数字与本机 `/v1/accounts/codex/usage` 逐项一致 | **通过** | 本轮真值：5 小时 100%、7 天 71%；断言用 `已用 \d+%`，比对本机同名字段，不与历史百分比比 |
| C3 | 杀掉 Bridge 后失败**不清空**上次成功（规则 18） | **通过** | 屏上「无法连接电脑端 Bridge」；库里该账户 success=1 行仍在且带 `quotaWindows`；最新行 status=`BRIDGE_OFFLINE`；列表行「Bridge 未连接 · 最后成功数据」 |
| C4 | 令牌被拒 ≠ 电脑离线（规则 19） | **通过** | 同一界面语言体系下三处都不同：详情文案「电脑端拒绝了这个令牌（HTTP 401）」、库里 status=`BRIDGE_AUTH_REQUIRED`、列表「电脑端授权已失效」；且全程不出现「API Key」 |
| C4b | Codex 失败行的 `source` 是 `BRIDGE` | **通过** | 逐行按 `account_id` 作用域查出，不再用全局计数 |
| C5 | DeepSeek 半边零回归 | **通过** | `assert-two-live-accounts` / `assert-last-success` / `assert-daily-usage` 三个脚本各自打出 `RESULT: *PASS*`；两个真实账户的 `id + credential_id` 在脚本首尾逐字节相同 |
| C6 | 杀 app 重启后读数仍在 | **通过** | 证明 codec 真的往返了 `quotaWindows`，而不是内存里恰好还没掉 |
| C7 | 令牌不进日志 | **通过** | logcat 尾部 6000 行里，令牌的每一处出现都是 adbd 对自己 `input text` 的命令回显（即测试自己，≤4 次）；非 adbd 进程 0 次。本轮 96 份 uiautomator dump 全量 grep 0 次；凭据路径 5 个文件 0 个日志调用 |
| C8 | 明文例外只放行白名单主机 | **通过** | 同一台 stand-in、同一端口、同一个正确令牌：`10.0.2.2` 读到额度，`192.168.1.170` 被平台拒绝，屏上原话「Cleartext HTTP traffic to 192.168.1.170 not permitted」；`nc` 先证明该端口 TCP 可达，否则这条不计分 |
| A4 | 令牌只进请求头 | **通过** | stand-in 只对 `Authorization: Bearer <token>` 返回 200，其余一律 401。带对令牌的账户读到 13% / 40%，带错令牌的账户读到 401 文案——同一台服务器，唯一差别是请求头 |
| A6 | 额度账户不显示「今日 0.00」 | **通过** | 页面上余额位是 `text="—"` 且整页无 `0.00` |
| C9 | Codex 屏幕不冒充 DeepSeek | **通过** | 无 `api.deepseek.com`、无开放平台按钮、无 API KEY 框与「记住密钥」、无「CNY 余额」 |

### 断言的可失败性（R6 要的证伪记录）

这一轮不是写完就绿，五次变红都给了信息：

1. **「额度窗口」同时是 Codex 详情页的副标题**，拿它当断言等于永真。改成 `已用 \d+%`。判别力在同一次运行里就能看见：同一正则在两个账户上命中、在 LAN 账户上必须不命中（C8）。
2. **C7 起初变红**，命中行是 `adbd … input text <token>`——是测试自己打字造成的回显。做法不是把 adbd 过滤掉（那会连真实泄漏一起滤掉），而是拆成两条断言：非 adbd 进程 0 次；总命中数等于 adbd 命中数且 ≤4。
3. **C3 起初变红**：`run-as cat` 只拷主 db 文件，而 WAL 里的已提交行还没检查点进去，于是读到的是过去；改成 force-stop 后连 `-wal` / `-shm` 一起拉，撕裂的尾帧由帧校验和自动忽略。
4. **C5 起初假绿**：三个老脚本都不接受 `-Serial`，PowerShell 报 `NamedParameterNotFound`，而 `$LASTEXITCODE` 还是上一条命令的 0——一条**永远不会失败**的断言。改成不传参，并要求子脚本自己打出 `RESULT: *PASS*`。
5. **uiautomator 不输出 hint 文本**：空输入框无法按提示语定位，第一稿按「10.0.2.2」找地址框，实际点到的是它下面那行说明文字。改成按 EditText 序号定位，并在输入后用该框的真实 `text=` 值确认。

两次被环境打断，也都写进了脚本：宿主 Codex 取额度会瞬时失败（实测连续 3 次 503 `failed to fetch codex rate limits`，约 20 秒后正常）→ 本机预读改为最多 6 次重试，拿不到就停止而不是假装通过；上一轮中止留下的 4 个账户把「添加账户」挤出屏幕、且同名两行会让作用域查询读到别的账户 → 开头加 pre-clean，并用数据库计数断言它真的清干净了。

Phase 5 的独立复审 `docs/PHASE-5-REVIEW.md`（另一个 Agent 写的，本阶段未改它）在其 P2 说的「首次失败丢结构化类型」在本轮被实测到：冷启动失败返回 `error=CODEX_UNKNOWN`，`detail` 里带着未脱敏的上游原话。修它属于 Phase 5 复审批次，不与本步混做。

### 已知限制

1. D1 选 ①：Bridge 仍只绑回环，`/v1/*` **无鉴权**。手机能连是因为 `10.0.2.2` 就是宿主 loopback。真机 LAN 必须与 token 校验同批交付（Phase 7）。
2. D4：额度不进 Widget——要新指标 id + `metric_ids` 迁移 + 每 Slot 选择器，量级另算。
3. 失败快照的 `providerId` 是空串（`recordFailure` 只填 accountId / status / source）。目前无消费方读它，C4b 也只要求 `source` 正确；登记而不是掩盖。
4. 详情页失败时读数区被错误文本替换，与 DeepSeek 行为一致；「保留上次成功」成立的位置是库与列表，不是详情页读数区。
5. 令牌字段是密码框，屏幕上看不见明文，因此「令牌确实存下来且只进请求头」用 stand-in 的行为差异证明（A4 正/负对照），不靠读屏。
6. `assert-bridge-phone.ps1` 会创建并删除它自己的 4 个账户、force-stop app、清一次 logcat，并在 `0.0.0.0` 上临时起一台额度 stand-in；它对设备数据库**只读**（拷出后在主机 sqlite3 上查）。两个真实 DeepSeek 账户的 `id + credential_id` 在脚本首尾各取一次并断言相等——「没碰真账户」是被断言的，不是被承诺的。
