# Phase 3 实施计划 — 通用 Account Widget（Slot 模型）

> 依据：Spec `AI-Usage-Monitor-Development-Plan.md` §27–§41、§53 规则 8/9/15/16/17、Phase 3 章（L1966-1999），
> 以及 `docs/REVIEW-AND-NEXT-STEPS.md` L129-141（第三步）与 `docs/HANDOFF.md` §6。
> 状态：**2026-10-02 已批准，按 §7 序列实施中**。§8 的四项决策已由用户确认。

## 0. 前置事实（本计划撰写时已复核）

- 计划撰写基线：提交 `df85cbc`。复跑 `. .\tools\env.ps1; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --rerun-tasks`：
  **19 suites / 273 tests / 0 failures / 0 errors / 0 skipped**，BUILD SUCCESSFUL，Lint **0 error / 43 warning**。
- **步骤 0（决策 D4）已完成：R3 凭据代次窗口已关闭**。`AccountManager` 为每个账户维护单调递增的凭据代次（`replaceCredential` / `updateCredential` / `clearCredential` 时递增），
  `AccountRefreshManager` 在读取凭据**之前**捕获代次，成功与失败快照提交前在删除锁内同时比对「账户存在 + 代次未变」，任一不符即 `abandoned` 不落库；
  `refreshWithAuthContext` 另在发出请求前确认账户仍存在。新增 `CredentialEpochDropsStaleResultTest` 11 例（含「保存发生在凭据读取期间」的顺序用例与 4 例对照），
  双向变异验证：去掉代次比对 → 恰好 5 例红；改成无条件丢弃 → 43 例红。复跑 **20 suites / 284 tests / 0 failures**，Lint **0 error / 43 warning**（与基线同数）。
  实现细节：代次保存在内存（进程内竞争的窗口；进程死亡同时杀掉在途请求），且必须用 `putIfAbsent`+`AtomicLong` 而非 `Map#compute`——后者是 API 24，minSdk 23 会被 Lint 判 Error。
- Phase 3 期间的回归基线数字以 **284** 为准，不是文档里旧的 273。
- 当前 widget 层实况（本计划的事实基础，不是推测）：

  | 位置 | 现状 |
  | --- | --- |
  | `widget/WidgetConfig.java:19-24` | 只有 `widgetId/widgetType/accountId/refreshIntervalMs/sortOrder/updatedAt`，**无 slot、无 metric** |
  | `storage/Database.java:26` | `VERSION = 1`；`widget_config` 列为 `widget_id PK, widget_type, account_id, refresh_interval_ms, sort_order, updated_at`；`onUpgrade` 空实现（L108-113） |
  | `widget/WidgetRenderer.java:66-82` | 只接收 balance/usage/peak 三段文本，布局固定 3 个 id：`widget_balance`/`widget_usage`/`widget_peak_status` |
  | `widget/WidgetUpdateManager.java:212-238` | `buildViews` 取 `lastKnown()` 后**只渲染余额与今日用量**，未渲染状态与更新时间（审查报告 L136 指出的缺口，实现在 `AccountRefreshManager.java:394-398` 已有 `view()`/`AccountView.displayStatus()`，widget 未接） |
  | `WidgetUpdateManager.java:176-196` | `updateWidgetsForAccount` 按 `config.getAccountId()` 单账户匹配 → Slot 化后必须改为「任一 slot 引用该账户」 |
  | `widget/WidgetConfigActivity.java:184-226` | 单选账户的清单；无多账户、无指标选择、无排序 |
  | `res/layout/widget_4x2.xml` | 单一「余额 + 今日用量 + 当前时段」，无账户名、无更新时间、无手动刷新入口 |
  | `AndroidManifest.xml:52-84` | 3 个 `AppWidgetProvider`（4x2/2x2/2x1），均指向 `WidgetConfigActivity` 作 `android:configure` |
  | `provider/ProviderCapabilities.java` | 只有 `reportsBalance/QuotaWindows/Metrics` 布尔，无「可展示指标清单」 |
  | `res/xml/*_info.xml` | `updatePeriodMillis=1800000`、`resizeMode=horizontal|vertical` |

## 1. 目标与范围

**目标**：把 3 个 DeepSeek 专用 Widget 变成**通用 Account Widget**：一个 Widget 系统同时支持「单平台单账户」和「同平台多账户 / 多平台混合」，本质都是**多个 Account Slot**（Spec §30、§33）。验收口径 = 同一个 4×2 能同时显示两个 DeepSeek 账户；同一账户可出现在多个 Widget。

**本阶段做**：

1. Slot 模型 + `widget_config` schema v2 迁移（每实例已有单账户绑定转成 1 个 Slot，保持绑定不变）。
2. Provider 公开可展示指标，配置界面按实际能力列指标。
3. 2×2（单账户 + 次要指标）与 4×2（3 Slot Dashboard）两种核心尺寸。
4. 2×1 保留为「单 Slot 极简」，只随迁移改数据结构，**视觉与行为不变**（不做零新增即下线）。
5. Slot 展示：账户名、主要指标、次要指标、**最后成功更新时间**、**最新刷新状态**、stale 提示。
6. Widget 手动刷新入口 → 经共享 `AccountRefreshManager` 刷新该 Widget 关联的账户（禁止 Widget 自己调 Provider）。
7. 明确状态：账户已删除 / 已停用 / 无数据 / 该指标不支持。

**本阶段不做**（明确排除，避免范围漂移）：2×4 与 4×4（Spec §56/§57 属 V1.1/V2，审查报告亦要求核心两尺寸通过后再扩）、历史图表与 `UsageSnapshot` 读取扩展（Phase 4）、Windows Bridge / Codex / 二维码 / AUTO、主题与 density 自定义、每 Widget 独立刷新间隔（仍用全局 `AppSettings`）、`yesterday_usage`（应用当前没有昨日消耗的数据来源，见 §3.2）。

## 2. 关键设计决定（含取舍）

| # | 决定 | 理由 / 代价 |
| --- | --- | --- |
| N1 | Slot 存**新表 `widget_slots`**（`(widget_id, slot_index)` 主键，`account_id`、`metric_ids`、`sort_order`），不把 JSON 塞进 `widget_config` | 需要按 `account_id` 反查「哪些 Widget 引用了这个账户」（Spec §38 规则 16/17），JSON 列只能全表扫。代价：两表写入需包在一个事务里，避免半更新 |
| N2 | `WidgetConfig` 去掉 `accountId`，改为 `List<WidgetSlot>`；不保留兼容访问器 | 保留双源会允许 slot 与旧列不一致，是后续 bug 的温床。仓库风格亦要求不留兼容垫片 |
| N3 | 渲染用**静态 Slot 视图**（布局里预置 3 组，未使用的 `View.GONE`），不引入 `RemoteViewsService`/`ListView` | 集合类 RemoteViews 要一个 `RemoteViewsService` + 工厂 + 集合项 PendingIntent，且长按/点击路由复杂；本项目 Slot 上限是 3，静态视图足够。代价：4×2 上限固定 3 Slot，扩到 4+ 需重做 |
| N4 | 指标解析逻辑放**纯 JVM 类** `WidgetSlotPlan`/`WidgetSlotResolver`（无 `Context`），`WidgetRenderer` 继续只收字符串 | 与 `WidgetRenderer` 现有注释的契约一致（不取数、不解密、不判断），并且是跨日/状态/降级这些用例唯一可单测的入口 |
| N5 | 状态与时间口径**复用** `AccountRefreshManager.AccountView.displayStatus(intervalMs, now)` | 复查 P2-1 已把它统一为列表与 Widget 的共同口径；widget 侧另写一份判新鲜会重新制造两套事实 |
| N6 | 手动刷新用 `PendingIntent.getBroadcast` + 自定义 action + `EXTRA_WIDGET_ID`，request code 由 `widgetId` 派生 | `openAppPendingIntent`（`WidgetUpdateManager.java:292-305`）已记录过教训：只差 extras 的 intent 视为相等，request code 不区分会让第二个按钮刷新第一个 Widget |
| N7 | 指标清单经 `ProviderCapabilities` 暴露（新增 `widgetMetrics()`），接受因此**重定基线** Phase 2 验收 6 的 provider 哈希 | 见 §8 决策 D1，需要用户确认 |

## 3. 逐项设计

### 3.1 Slot 模型与迁移

新增 `widget/WidgetSlot.java`：`slotIndex`、`accountId`（稳定 Account ID，Spec §28 强调不能只绑 providerId）、`metricIds`（有序 `List<String>`）。

`WidgetConfig` 变为：`widgetId`、`widgetType`、`slots`、`refreshIntervalMs`、`sortOrder`、`updatedAt`；`unbound(widgetId, type, interval)` = 空 slot 列表；`withSlots(...)` 取代 `withAccount(...)`。

`Database`：`VERSION = 2`；`onCreate` 建 `widget_slots`；`onUpgrade(1→2)`：

1. 建表（同 `onCreate`）+ `CREATE INDEX idx_widget_slots_account ON widget_slots(account_id)`；
2. `INSERT INTO widget_slots SELECT widget_id, 0, account_id, <按 type 的默认 metric_ids>, sort_order, updated_at FROM widget_config WHERE account_id <> ''`；
3. 全程 `db.beginTransaction()` / `setTransactionSuccessful()`；失败即回滚，版本不推进，下次启动重试。

`SqliteWidgetConfigStore`：`find`/`save`/`all` 改成一次事务内读写两表；新增 `List<Integer> widgetIdsUsingAccount(String accountId)` 与 `deleteSlots(int widgetId)`；`WidgetConfigStore` 接口同步扩这两个方法（`android-free` 的 fake 在测试里逐语句镜像，沿用 `WidgetBindingTest` 的既有做法）。

**默认指标集**（迁移与新建时）：2×1 与 4×2 → `[balance, today_usage]`；2×2 → `[balance, today_usage]`。与当前视觉一致，升级不改变既有 Widget 的显示内容（只有 §3.4 新增的时间行是有意变化，见 D3）。

### 3.2 指标目录（Spec §34）

`widget/WidgetMetricId`（常量）：`balance`、`today_usage`、`quota_<windowId>_remaining`、`account_available`。DeepSeek 提供前两项；`yesterday_usage` 明确不提供——`daily_usage` 表有昨日 `total_usage` 行，但它是「余额下降差额估算」，昨日值今早已重算基线，展示它会给一个来路不明的数字（HANDOFF §3 今日用量口径）。留给 Phase 4 的历史读取一起做。

`ProviderCapabilities` 增 `List<WidgetMetric> supportedMetrics()`（builder 加 `widgetMetric(id, label, format)`）；`DeepSeekProvider` 在既有 `capabilities()` 里声明两项。`today_usage` 不是 Provider 返回的字段而是本地估算，因此 resolver 对它的取值走 `UsageRepository.dailyUsage()`——**这条来源差异必须在界面文案上说明**（README 已写明估算性质）。

配置页只列「该 Provider 声明的 + 本地可算的」指标；某 Slot 绑到不支持该指标的账户时，该 Slot 显示「此账户不提供该指标」而不是空字符串（审查报告 L138）。

### 3.3 取值与状态

`WidgetSlotResolver.resolve(...)` 输入：`Account`（可能为 null=已删除）、`AccountView`、`dailyUsage`、`intervalMs`、`nowMs`、`PeakTimeUtils.Status`；输出每 Slot 的：账户名、主指标文本、次指标文本、时间文本、状态文本与颜色、`View.GONE` 与否。规则：

- **余额取 `lastSuccess`**，绝不取失败尝试的占位值（Spec §39 规则 18）；`lastAttempt` 只决定状态文本 → 复用列表已有的「¥24.94 · API Key 无效或已失效 + 保留数据」表现（R4/P3-1 成果）。
- 时间文本：刚刚 / N分钟前 / HH:mm / N小时前（超过 1 天显示日期）；`displayStatus` 判 `STALE` 时该行改用警示色并加「数据已过期」。
- `NO_DATA`（从未成功）→ 指标显示 `—` 且状态「尚未查询」。
- 账户已删除 → 「账户已删除 · 请重新配置」，点击进配置页；不猜「第一个启用账户」（`resolveAccount` 的旧回退只保留给**完全未绑定**的 Widget，即升级瞬间与取消选择的场景）。
- `REFRESHING`：手动刷新按钮按下后，广播在 `AccountRefreshManager` 上取不到结果前，Widget 先由接收器直接重绘一次并带 `refreshing` 标记，避免用户以为没响应。

### 3.4 布局与渲染

- `res/layout/widget_2x2.xml`：账户名 + 主指标（32sp 沿用 `widget_balance` 样式）+ 次指标 + 时间/状态行 + 刷新按钮（`widget_refresh`）。
- `res/layout/widget_4x2.xml`：标题行（`widget_title` = "AI Usage"，点击开 App）+ 刷新按钮 + 3 组 `widget_slot_{0,1,2}_{name,line,time}`；保留 `widget_peak_status` 于标题行（现网可见元素，不静默删除）。
- `res/layout/widget_2x1.xml`：**不改**，只让 `WidgetRenderer` 走 Slot 0 的主/次指标填同两个 id。
- `WidgetRenderer.build(...)` 签名改为接收 `List<SlotViewData>` + 类型；`layoutFor`/`showsPeakStatus` 保留。
- 点击语义按 Spec §36：点 Slot 行 → 该账户详情页（`MainActivity.EXTRA_ACCOUNT_ID`，现有机制）；点标题 → App；点刷新 → §3.5；长按 → 系统配置。`EXTRA_ACCOUNT_ID` 意图按 Slot 索引派生不同 request code。

### 3.5 手动刷新

`WidgetRefreshScheduler` 增 `ACTION_WIDGET_REFRESH`。`WidgetRefreshReceiver`（已有 `goAsync()` + 单线程 executor）识别该 action：取该 `widgetId` 的 Slot 账户集合（去重、跳过已删除）→ `refreshManager().refresh(accountId)` 逐个 → `updateWidget(widgetId)`。不新增线程模型、不新增 Provider 调用点（`grep` 断言：`widget/` 下除 `WidgetRefreshReceiver`/`WidgetUpdateManager` 经 `AccountRefreshManager` 外无 `provider.` import）。

刷新期间删除账户：走的是同一条 `AccountRefreshManager`，R3 已收口的 writeMonitor 语义自动生效，本阶段只需补一例「双 Slot Widget 刷新中删除其中一个账户」的交错的测试。

### 3.6 配置界面

`WidgetConfigActivity` 扩展为三步（仍纯程序化 UI，无布局 XML，沿用 `UiKit`）：模式（单账户 / Dashboard）→ 多选账户（点选加入，含顺序与上移/下移）→ 每个 Slot 选指标（按 Provider 能力）。容量按类型限制：2×1/2×2 = 1 Slot，4×2 = 3 Slot；超出时给明确文案而不是静默截断。

保持 launcher 放置契约：未确认仍 `RESULT_CANCELED`（不写 slot 行 ⇒ 无孤儿，`WidgetConfigActivity.java:84-93` 的既有推理不变）；从 Widget 内「配置」入口进入时带 `EXTRA_WIDGET_ID`，走 re-bind 分支。

## 4. 分层与红线检查

- Spec §45：`account/` 不得依赖 `usage/` —— 不变；新逻辑只在 `widget/` 与 `storage/`。
- UI/Widget 不直调 Provider、Widget 不读 Token（规则 8/9）—— resolver 输入全为已解密后的展示值，测试断言 `widget/` 无 `CredentialStore`/`AuthContext` 引用。
- 规则 15/16/17（按 Account ID 工作、一账户可在多 Widget、一 Widget 可显示多账户）—— 由 `widgetIdsUsingAccount()` 与 Slot 模型直接支撑，各出一例单测。
- Provider 层零回归契约（UA、超时、401/403/429 映射、`REFRESH_INTERVALS`、`DeepSeekProvider.java` 哈希）：`DeepSeekProvider.java` 本体不动，只有 `ProviderCapabilities.java` 因 D1 变动。

## 5. 测试与验收

**宿主机 JVM（新增，估算 ~45-60 例，基线为 R3 关闭后的 284）**

| 套件 | 覆盖 |
| --- | --- |
| `WidgetSlotModelTest` | Slot 增删改与顺序、`withSlots` 字段保真、未使用 Slot 不渲染、同账户出现在两 Widget |
| `WidgetSlotResolverTest` | 成功 / 成功后失败（保留余额 + 错误态）/ 首次失败 / `NO_DATA` / `STALE` / 已删除账户 / 指标不支持 / 余额上升计 0 / 跨日；`now` 由参数注入，不依赖真实午夜 |
| `WidgetConfigStoreSlotsTest` | fake 逐语句镜像 `SqliteWidgetConfigStore`：两表同事务、`widgetIdsUsingAccount` 反查、`delete` 清 Slot、`all()` 顺序 |
| `DeleteRaceSlotOrphanTest` | 双 Slot Widget 刷新中删除其中一账户 → 不落孤儿数据（沿用 `DeleteRaceDropsOrphanTest` 的门闩手法） |
| `WidgetManualRefreshGuardTest` | 静态断言：`widget/` 无 Provider/凭据直连；PendingIntent request code 唯一性（每 widgetId × 每 action 不撞） |

每步收尾固定：`. .\tools\env.ps1; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --rerun-tasks`，并记录实际数字（不许沿用旧数字）。

**设备验收矩阵**（新脚本 `tools/smoke/assert-widget-slots.ps1`、`widget-config-slots.ps1`，全部遵守 HANDOFF §8 的 BOM/`${pkg}`/PNG/`input text` 分段等既有规矩；两把真实 Key 只在设备上）

| # | 验收项 | 证据形式 |
| --- | --- | --- |
| A1 | Phase 2 → Phase 3 升级：已放置 Widget 的账户绑定原样转成 Slot 0，余额不变、无空白 | `dump-db` 升级前后 `widget_config`+`widget_slots` 对照 + 截图 |
| A2 | 4×2 同时显示 DeepSeek 与 DeepSeekWork 两账户，数值/名称分别正确 | 截图 + 两账户 `daily_usage`/`latest` 读数对照 |
| A3 | 同一账户绑到两个 Widget（2×2 + 4×2），一次刷新两者同步更新 | 刷新前后时间文本与快照计数 |
| A4 | 使一账户失败（打假 Key）→ 保留最后成功余额 + 显示 `AUTH_REQUIRED` 文案，另一 Slot 正常；恢复后错误态解除 | 截图 + 快照 `success` 列 |
| A5 | 点 Widget 刷新按钮 → 后台经 `AccountRefreshManager` 刷新该 Slot 账户并更新 | `adb logcat` 无 FATAL + 快照新增行 + 时间文本变化 |
| A6 | 配置在冷启动、尺寸变化（`onAppWidgetOptionsChanged`）、应用升级后均保留；取消选择放置不落 Slot 行 | `dump-db` + 冷启动截图 |
| A7 | 删除被 Slot 引用的账户 → 该 Slot 显示「账户已删除」不崩溃，其余 Slot 不受影响，无孤儿写入 | 截图 + `usage_snapshots`/`daily_usage` 无该 account_id 新行 |
| A8 | 跨日：注入昨日+今日两行后刷新，今日累计不归零（R1 设备回归不回归） | 复用 `assert-daily-usage.ps1`（自带备份-恢复，10/10 基线） |
| A9 | `assert-two-live-accounts.ps1` 仍全绿；provider 目录哈希按 D1 结论复核并记录 | 脚本输出 |

**已知限制**（完成后须原样写进文档，不得写成通过）：RemoteViews 静态 3 Slot 上限；`today_usage` 为估算且应用未运行期间不计；R3 凭据代次窗口若未关闭仍保留。

## 6. 交付物

`docs/PHASE-3-PLAN.md`（本文件，批准后更新为实施记录）、`docs/PHASE-0-2-PLAN.md` 无需改动、`README.md` 补 Widget Slot 与手动刷新说明、`docs/HANDOFF.md` 更新到 Phase 3 实况（含测试/编译/Lint 真实数字与设备状态）。

## 7. 提交序列（每步：改 → 测 → 编译 → Lint → 提交）

1. `feat: give widgets a slot model and migrate the config schema to v2`（§3.1 + `WidgetSlotModelTest`/`WidgetConfigStoreSlotsTest`；此步渲染仍走 Slot 0，视觉零变化）
2. `feat: resolve widget slot display state and freshness`（§3.2/§3.3 + `WidgetSlotResolverTest`）
3. `feat: render widgets as account slots with state and update time`（§3.4 + 布局改动，2×1 保持原样）
4. `feat: configure widget slots and metrics from the widget config screen`（§3.6）
5. `feat: refresh a widget's accounts through the shared refresh manager`（§3.5 + `WidgetManualRefreshGuardTest`/`DeleteRaceSlotOrphanTest`）
6. `test: pin the Phase 3 widget acceptance matrix on a device script` + `docs: record Phase 3 results`

## 8. 决策（2026-10-02 用户审批结果）

| # | 决策 | 结论 |
| --- | --- | --- |
| **D1** | 指标清单放哪 | **扩展 `ProviderCapabilities`**（`widgetMetrics()`），接受因此重定 Phase 2 验收 6 的 provider 目录哈希基线：基线从 `4ed7df1`（`a081ab3a…`）改到步骤 2 的首个提交，理由是该验收保护的是「新增账户不需要改 Provider」，`DeepSeekProvider.java` 本体仍不动。步骤 2 提交信息里必须写明新旧哈希 |
| **D2** | 4×2 Slot 数 | **3 个 Slot**（Spec §33 示例），每 Slot 保留账户名 + 指标 + 更新时间/状态两行 |
| **D3** | Widget 可见变化 | **接受**：4×2/2×2 新增账户名、更新时间、状态文案与刷新按钮（审查报告 L136-138 点名的交付项）；「像素级零变化」仅是 Phase 1 约束。2×1 视觉不变 |
| **D4** | R3 凭据代次窗口 | **先关闭再做 Phase 3** —— 已按 §0 所述完成，独立于 Phase 3 的 6 个提交，先行提交 |
| **D5** | 2×4 | **不纳入本阶段**（Spec §56；审查报告要求核心两尺寸先通过） |
| — | 计划整体 | **批准，按 §7 的 6 个提交序列动工**；步骤 6 后才上设备跑 A1-A9 |

## 9. 风险清单

- **RemoteViews 体积**：3 Slot × 3 文本 + 标题/刷新 ≈ 12 个 view，仍远低于限制；若加进度条需重估（2×4 才需要）。
- **launcher 差异**：`updatePeriodMillis=1800000` 是系统最小值，实时性仍靠现有 alarm；手动刷新按钮是唯一即时路径，需在文档说明。
- **PendingIntent 等价性**：extras 不同但 request code 相同的 intent 会被合并 —— 已在 N6 处理，测试用例锁住。
- **配置页复杂度**：多选 + 排序 + 每 Slot 选指标在一个 Activity 里做完，是本阶段最容易超工时的一块；必要时把「每 Slot 选指标」留到 3.6 之后的独立提交，先交付默认指标。
- **设备时钟**：模拟器内部日期与宿主不同（HANDOFF §8），跨日断言一律以 `adb shell date +%Y-%m-%d` 为准。
- **数据库迁移不可回退**：v2 之后旧 APK 装回会因未知表报错——真机验证前保留 `dump-db` 备份，且 QA 只在模拟器上做。
- **`assert-daily-usage.ps1` 之外的写库脚本**：一律沿用 R5 的备份-恢复模式（`try/finally` + 删 `-wal`/`-shm` 侧车 + 逐行比对恢复）。

---

## 10. 实施记录（2026-10-02）

### 提交

| 提交 | 内容 | 与计划的顺序差异 |
| --- | --- | --- |
| `a742eb8` | 步骤 0：R3 凭据代次窗口关闭 | 按 D4 提前到 Phase 3 之前 |
| `8dc162f` | 本计划文档 | — |
| `46cf40e` | 步骤 1：Slot 模型 + schema v2 迁移 | `widget_slots` 去掉 `sort_order` 列（`slot_index` 即顺序）；不单独暴露 `deleteSlots`（`save` 同事务替换整组） |
| `dc41a06` | 步骤 2：指标目录 + 展示解析 | 按 D1 扩展 `ProviderCapabilities`，provider 哈希基线重定（见下） |
| `b40e03c` | 步骤 3：布局与渲染 Slot 化 | — |
| `c468d11` | 步骤 5：Widget 手动刷新 | **提前到步骤 4 之前**：否则步骤 3 放上的「刷新」标签会连续两个提交是死按钮 |
| `46f6579` | 步骤 4：Slot 配置界面 | 「每 Slot 选指标」按 §9 的预案延后，当前每 Slot 存默认指标 |
| 本次 | 步骤 6：设备验收脚本 + 文档 | — |

### 门禁与测试

- 宿主机：`23 suites / 331 tests / 0 failures / 0 errors / 0 skipped`（Phase 3 前为 19/273；R3 +11、步骤 1 +20、步骤 2 +25、审查修复 +2）。
- `:app:assembleDebug` 通过；Lint **0 error / 30 warning**（改前基线 43；Widget 文案移入 `strings.xml`、运行时填充的单元格不再带占位文本后下降 13 条）。
- R3 变异验证：去掉代次比对 → 恰好 5 例红；改成无条件丢弃 → 43 例红。
- provider 哈希基线（D1）：`DeepSeekProvider.java a081ab3a → 554f9893`、`ProviderCapabilities.java 0b292246 → cd7ea02b`；Phase 2 验收 6 的保护语义（新增账户不改 Provider 代码）不变，此后基线为 `dc41a06`。

### 设备验收矩阵（模拟器 Pixel_7_API_37，两把真实 Key 仍在设备上）

`tools/smoke/assert-widget-slots.ps1` → **32 checks / 0 failed**；既有脚本复跑：`assert-two-live-accounts.ps1`（双账户独立）、`assert-last-success.ps1`（9/9）、`assert-daily-usage.ps1`（含「device daily_usage restored to pre-test state」）。

**Phase 3 审查后的补强**（发现与处置记在 `docs/REVIEW-AND-NEXT-STEPS.md` 的「Phase 3 审查」节，R6–R17）：验收脚本原先只读已失效的 `widget_config.account_id`、推库不校验，且 A1 只检查升级结果、没有真的走过升级。现改为**可逆的 v1→v2 彩排**（把真实库降级回 v1 形状推回去，让应用真跑一次迁移，再逐表比对恢复），并补上「每行两个指标都上屏」与「点已删除行打开配置页」两条断言。

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| A1 | v1→v2 升级保留绑定、无空白、无孤儿 | **通过** | `user_version=2`；slot 0 与 legacy `widget_config.account_id` 全一致；孤儿/空 account_id/非默认 metric_ids 计数均为 0 |
| A1u | 真的走一遍 v1→v2 升级（可逆彩排） | **通过** | 降级副本 `user_version=1` → 应用重跑迁移回到 2；重建的 slot 0 与降级时遗留列记录的绑定逐行相等；遗留列被清空；恢复后逐表比对与备份一致 |
| A2 | 4×2 同时显示两个 DeepSeek 账户 | **通过** | `widget_slots` 有 `6/0=acct_294b…`、`6/1=acct_93e6…`；桌面 dump 同屏出现两个账户名 + 各自数值；审查后补断言「每行两个指标都上屏」（R7 修复的回归） |
| A3 | 同一账户出现在多个 Widget | **通过** | `acct_93e6…` 同时出现在 Widget 6 与 7；脚本按 account_id 反查断言 |
| A4 | 刷新失败保留成功余额并明示状态 | **通过** | `assert-last-success.ps1` 9/9；UI 断言健康 Slot 文案「账户可用」 |
| A5 | Widget 刷新按钮经共享链落库并重绘 | **通过** | `uiautomator` 定位「刷新」→ `input tap` → 该账户成功快照 +1（严格等于 before+1）、另一账户不动、footer 回到「刚刚」 |
| A6 | 配置在冷启动后保留 | **通过** | force-stop + 启动 + force-stop 后 `widget_slots` 逐行 `Compare-Object` 无差异 |
| A7 | 删除被 Slot 引用的账户 | **通过** | 用不存在的 `acct_qa_missing` 注入 slot 1（不动真实账户）→ 该 Slot 显示「账户已删除」、另一 Slot 照常、无 `AndroidRuntime`、该 id 零快照行；`finally` 恢复并逐行比对一致 |
| A8 | 跨日累计不被冲掉（R1 不回归） | **通过** | `assert-daily-usage.ps1` 全绿且自带恢复 |
| A9 | 双真实 Key 仍独立工作 | **通过** | `assert-two-live-accounts.ps1` 结果行 |

### 已知限制（不得当作已完成）

1. **每 Slot 的指标选择未做**：Slot 已按 `metric_ids` 存指标，配置界面统一给默认两指标；解析器对不支持的 id 已显示「不支持该指标」。
2. **按下刷新到响应之间没有 `REFRESHING` 态**：应用当前不记录「在途账户」，从存储数据里画一个不存在状态是假；延迟就是请求本身的耗时。
3. 4×2 静态三行，超过 3 个账户需重做布局（N3 的代价）；2×4 / 4×4 未做（D5）。
4. `today_usage` 仍是余额差额估算，且它由本地累计而非平台返回，Provider 声明它只为让界面可选项统一。
5. 迁移不可回退：装回旧 APK 会因 `user_version=2` 走降级路径，QA 只在模拟器上做。
