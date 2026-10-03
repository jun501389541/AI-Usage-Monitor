# Phase 4 实施计划 — 历史快照的读取与保留

> 依据：Spec `AI-Usage-Monitor-Development-Plan.md` §26（UsageSnapshot）、§44（导航含「历史」）、§55 规则 24（从第一版保留历史 Snapshot）、Phase 4 章（L2001-2012：「加入历史 Snapshot，暂时不必实现复杂图表，确保刷新成功后保存数据」），以及 `docs/REVIEW-AND-NEXT-STEPS.md`「第四步」的明确要求：补齐按账户和时间段读取、保留策略及必要索引，并验证 Widget 配置变更不损坏历史。
> 状态：**2026-10-02 已批准，按 §7 序列实施中**。§8 的决策已由用户确认。

## 0. 前置事实（本计划撰写时实测，不是估算）

设备库 `ai_usage_monitor.db`（模拟器，两真实账户）：

| 指标 | 实测值 |
| --- | --- |
| 文件体积 | 131 072 字节 |
| `usage_snapshots` 行数 | 171（成功 99 / 失败 72） |
| `usage_data` JSON 平均 / 最大长度 | 220 字节 / 269 字节 |
| 覆盖时间跨度 | 0.72 天（两账户合计 171 行） |
| 推算日增 | ≈ 237 行/天/两账户 ≈ **52 KB/天**，一年约 19 MB（仅 payload，未计索引） |
| 现有索引 | `idx_snapshots_account_time (account_id, timestamp DESC)`、`idx_widget_slots_account`，其余为主键自动索引 |

代码现状（决定本阶段范围的关键事实）：

| 位置 | 现状 |
| --- | --- |
| `usage/UsageRepository.java:59` | 只有 `history(String accountId, int limit)`，**没有按时间区间读取** |
| `storage/SqliteUsageRepository.java:100-126` | `history()` 已实现（`ORDER BY timestamp DESC`，`limit<=0` 表示不限），但**生产代码零调用方，只有 5 个测试类在用** |
| `usage/UsageSnapshot.java` | `id/accountId/timestamp/usageData/source/success` 齐备，`toResult()` 解码 |
| `storage/Database.java` | `VERSION = 2`；`usage_snapshots` 无保留策略，只增不减；`deleteForAccount()` 在删除账户时清历史（`AccountListActivity.java:410`、`AccountEditActivity.java:397`） |
| `refresh/AccountRefreshManager` | 成功与失败都落一行；`latest()` 只读 `success = 1`，`latestAttempt()` 读最新一行 |
| `daily_usage` 表 | 已有 `(account_id, day, last_balance, total_usage, updated_at)`，**日粒度事实已经存在**，实测 4 行 |

**上一轮审查的教训直接约束本阶段**：`history()` 已经是「只被测试调用的生产 API」。本阶段不允许再造一个没有消费者的读取接口 —— 见决策 D1。

## 1. 目标与范围

**目标**：让已保存的历史能被真正按账户与时间段读出来并有一个真实使用场景，同时给只增不减的明细表一个明确、可测、不可逆性受控的保留策略。

**本阶段做**：

1. 按账户 + 时间区间读取（含分页上限），并让它在生产路径上被消费。
2. 保留策略：明细快照保留最近 N 天，更早的清理；日粒度长期数据继续由 `daily_usage` 承担（不新增聚合表）。
3. 清理的硬约束：任何情况下不得删掉某账户「最后一次成功读数」与当日行，否则 Widget 与列表会被清空（违反 §39 与规则 18）。
4. 用 `EXPLAIN QUERY PLAN` 实测决定是否需要额外的时间索引，不凭感觉加。
5. 验证 Widget 配置变更（改绑、加删 Slot、Widget 实例删除）不损坏历史。
6. **最小历史界面**（若 D1 通过）：账户详情页展示「最近若干次读数」列表（时间、余额、与上次的差值、成功/失败），不做图表。

**本阶段不做**：折线/柱状图、消耗速度与预计耗尽（Spec §57 属 V2）、Bridge / Codex / 配对（Phase 5+）、导出与云同步、用户可配置保留天数（见 D4）。

## 2. 关键设计决定（含取舍）

| # | 决定 | 理由 / 代价 |
| --- | --- | --- |
| N1 | **不新增聚合表**。日粒度已由 `daily_usage` 承担（余额基线 + 当日累计），历史曲线类需求等到真有消费者时再说 | 少一份会漂移的第二来源；代价是 `daily_usage` 只有「当日累计」，无法回答「上周每天的消耗」——那是 V2 图表的事 |
| N2 | 保留策略做成**纯 JVM 可测的决策函数**：输入现有行 + `nowMs` + 策略，输出要删除的 id 集合；SQLite 只执行 | 与 `Money.accumulate`、`WidgetSlotResolver` 同一手法；「哪些行该删」是本项目最容易出事故的地方，必须在能写断言的层 |
| N3 | 清理在**后台闹钟回调里、刷新之后**执行，且先 `select` 出待删 id 再删（同事务），删除条数写进 `app_meta` 便于设备核验 | 不在 UI 线程做 I/O 密集操作；先查后删让「保留最后成功」的判定可被断言 |
| N4 | 删除用 `DELETE ... WHERE id IN (...)` 显式 id 列表，不用一条带子查询的 `DELETE` | 显式 id 才能与决策函数的输出逐行比对；SQLite 的删除子查询行为不易测 |
| N5 | 时间区间读取的边界为 **左闭右开** `[fromInclusive, toExclusive)` | 与 `daily_usage.day` 的日历日切分一致，跨日聚合不会出现同一行既算今天又算昨天 |
| N6 | 历史列表**只读不刷**：打开历史段落不触发 Provider 请求 | 与账户列表同理（点进列表就烧配额是本项目的既有反例，见 `AccountListActivity.statusText` 注释） |

## 3. 逐项设计

### 3.1 读取

`usage/UsageRepository` 增：

```java
List<UsageSnapshot> history(String accountId, long fromInclusive, long toExclusive, int limit);
int pruneBefore(long cutoffMs, List<Long> keepSnapshotIds);   // 返回删除行数
int countSnapshots(String accountId);                          // 供界面显示「共 N 条」
```

`SqliteUsageRepository.history(accountId, from, to, limit)`：`WHERE account_id = ? AND timestamp >= ? AND timestamp < ? ORDER BY timestamp DESC LIMIT ?`，走现有 `idx_snapshots_account_time`（用 `EXPLAIN QUERY PLAN` 在设备上确认，见 §5）。

现有 `history(accountId, limit)` 保留还是删除，取决于 D1：**若历史界面通过，则旧签名被新签名取代**（不留两个来源）；若 D1 不通过，则连同 5 个测试里的用法一起删掉这个无生产消费者的 API。

### 3.2 保留策略

`usage/SnapshotRetention.java`（纯逻辑）：

- 输入：`List<UsageSnapshot> rows`、`nowMs`、`detailRetentionDays`（常量，建议 14 天）、每账户必保留的 id 集合（最后一次成功、最新一行、当日全部）。
- 输出：待删除 id 列表。
- 规则（每条一个断言）：
  1. 年龄 < 保留期的行一律不删；
  2. 每账户**最后一次成功读数**即使在保留期外也不删（Widget/列表靠它，§39）；
  3. 每账户最新一行不删（状态展示靠它）；
  4. 当日行不删（`daily_usage` 与它同属「今天」的事实）；
  5. 其余过期行全部删除；
  6. 失败行与成功行同权（历史里「那天查失败了」也是事实）。

`pruneBefore()` 由 `AccountRefreshManager` 之外调用（避免把存储策略塞进刷新链），落点见 N3。

### 3.3 索引

只在实测需要时加。判定方法：设备上对区间查询与清理查询各跑一次 `EXPLAIN QUERY PLAN`，若出现 `SCAN TABLE usage_snapshots` 才加 `idx_snapshots_time (timestamp)`；结果原样写进本文件 §5 的证据表，不写「应该会用索引」。

### 3.4 最小历史界面（D1）

`MainActivity`（账户详情页）在余额/今日用量之下加一段「最近读数」：最多 10 行，每行 `HH:mm · ¥24.94 · -0.02 · 成功/失败文案`，数据来自 3.1 的区间读取（今天 00:00 → 现在）。纯程序化 UI，沿用 `UiKit`，无布局 XML。

**为什么值得做**：它让 3.1 的读取有真实消费者；否则本阶段就是刚被审查批评过的「造只给测试用的 API」。若你不想现在加界面，则 3.1 收缩为只加 `pruneBefore`/`countSnapshots`，区间读取等真有界面需求再做。

## 4. 分层与红线

- `account/` 不得依赖 `usage/`（§45）—— 保留策略放 `usage/`，由存储层实现，`account/` 不动。
- UI/Widget 不直调 Provider（规则 8/9）—— 历史界面只读 `UsageRepository`。
- 刷新失败不得清除最后成功数据（规则 18）—— 由 §3.2 规则 2 在清理路径上同样成立。
- 历史读取不得变成第二个「余额来源」：所有金额仍出自 `UsageResult`/`Money`，界面不重算。

## 5. 测试与验收

**宿主机 JVM（估算 +25～35 例）**

| 套件 | 覆盖 |
| --- | --- |
| `SnapshotRetentionTest` | §3.2 六条规则逐条 + 边界（正好 14 天、时钟回拨、空表、单账户单行、失败行） |
| `UsageHistoryQueryTest` | 左闭右开边界、跨日不重复计入、limit 生效、倒序、区间为空返回空列表 |
| `PruneKeepsLastSuccessTest` | 清理后 `latest()` / `latestAttempt()` 仍返回原值；Widget 解析器（`WidgetSlotResolver`）对同一账户的输出在清理前后逐字段相等 |
| `HistoryReadHasProductionCallerTest` 或删旧 API | 明确禁止「只被测试调用的读取 API」再次出现（静态断言：`UsageRepository` 每个方法在 `app/src/main` 下至少一个调用点） |

每步收尾固定：`. .\tools\env.ps1; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --rerun-tasks`，并记录真实数字（当前基线 **23 suites / 331 tests / 0 failures，Lint 0 error / 30 warning**）。

**设备验收矩阵**（新脚本 `tools/smoke/assert-history.ps1`，沿用 R5/R16 规矩：拉库即全库备份、`try/finally` 恢复、推库前后校验字节数与退出码、恢复后逐表比对）

| # | 验收项 | 证据形式 |
| --- | --- | --- |
| H1 | 注入跨 30 天的合成历史 → 清理 → 过期明细消失、每账户最后成功与当日行保留 | 逐表行数 + 保留 id 清单 |
| H2 | 清理后 Widget 与账户列表仍显示原余额与状态（不得变 `—`） | `uiautomator` 文本断言 + 截图 |
| H3 | 区间读取正确：按「今天」「昨天」「全部」三种区间取回的行数与时间戳单调性 | 脚本对 `dump-db` 结果直接校验 |
| H4 | `EXPLAIN QUERY PLAN` 走索引（或据此新增索引后复测） | 原样记录计划输出 |
| H5 | Widget 配置变更不损坏历史：改绑、加 Slot、删 Slot、删除 Widget 实例，前后 `usage_snapshots` 与 `daily_usage` 行数与内容校验和不变 | 校验和对比（审查报告点名项） |
| H6 | 删除账户仍清干净且不误伤他人（既有行为不回归） | 复用 `assert-two-live-accounts.ps1` + 现有删除用例 |
| H7 | 体积：给出清理前后 DB 字节数与日增推算，证明保留策略真的在起作用 | 实测数字写进本节 |
| H8 | 历史界面（若 D1 通过）：读数列表显示正确、打开它不产生网络请求 | 截图 + 快照计数不变 |

**已知限制**（完成后原样写进文档）：明细只留 N 天，更早只有日粒度；不做图表；保留天数不可配置。

## 6. 交付物

`docs/PHASE-4-PLAN.md`（本文件，批准后更新为实施记录）、`README.md` 增「历史与保留」说明、`docs/HANDOFF.md` 更新到 Phase 4 实况、`docs/REVIEW-AND-NEXT-STEPS.md` 增本阶段审查节。

## 7. 提交序列（每步：改 → 测 → 编译 → Lint → 提交）

1. `feat: read usage history by account and time range`（3.1 + `UsageHistoryQueryTest`；含 D1 结果带来的旧 API 处置）
2. `feat: decide which snapshots retention may delete`（3.2 纯逻辑 + `SnapshotRetentionTest`，先不接线，只证明判定正确）
3. `feat: prune expired snapshots without losing the last good reading`（存储实现 + 后台落点 + `PruneKeepsLastSuccessTest`）
4. `feat: show recent readings on the account screen`（3.4，若 D1 通过）
5. `test: pin the history and retention acceptance on a device script`（H1-H8）+ `docs:` 记录

## 8. 决策（2026-10-02 用户审批结果）

| # | 决策 | 结论 |
| --- | --- | --- |
| **D1** | 最小历史界面 | **包含**：账户详情页「最近读数」列表（最多 10 行，不做图表）。区间读取因此有生产消费者；旧 `history(accountId, limit)` 被新区间签名取代，不留两个来源 |
| **D2** | 保留形态 | **明细快照 14 天 + 日粒度复用现有 `daily_usage`**，不新增聚合表（N1） |
| **D3** | 清理时机 | **后台闹钟刷新之后**（N3），先查待删 id 再同事务删除 |
| **D4** | 保留天数是否可配置 | **本阶段固定常量**，配置项留到设置界面统一做（用户未反对建议值） |
| **D5** | Windows Bridge 可行性验证 | **不并入本阶段代码工作**，单独出一份调研文档（官方 Codex App Server 额度接口、字段与认证边界），作为 Phase 5 前置 |
| — | 计划整体 | **批准，按 §7 的 5 个提交序列动工** |

## 9. 风险清单

- **清理是不可逆数据丢失**，且这个库里有两把无法重新输入的真实 Key 的配套历史。约束：决策函数先算后删（N4）、设备脚本必须先全库备份并逐表验证恢复、真机验证只在模拟器上做、任何情况下不对手工设备跑破坏性 prune。
- **「保留最后成功」判定错一个分支，Widget 就会变 `—`** —— 这是规则 18 的反向违反，必须有 `PruneKeepsLastSuccessTest` 与 H2 双层兜底。
- 新增读取 API 又变成死代码：用 `HistoryReadHasProductionCallerTest` 静态守住。
- 历史列表若实现成「打开即请求」会烧配额（§44 反例）：N6 + H8 断言快照计数不变。
- `EXPLAIN QUERY PLAN` 结论依赖真实数据分布：H1 注入跨 30 天数据后再测计划，不在空表上测。
- 时钟回拨/设备与宿主日期不一致（HANDOFF §8：模拟器曾落后一天）：保留判定一律用同一个 `nowMs` 入参，测试注入时间。
- 详情页加列表可能与既有「峰谷/自动刷新」区块抢空间：实现时以截图为准，必要时折叠显示最近 5 条。

---

## 10. 实施记录（2026-10-02）

### 提交

| 提交 | 内容 | 与计划的差异 |
| --- | --- | --- |
| `a57e987` | 本计划（含 D1-D5 审批结果） | — |
| `8b8ba78` | 区间读取 + 详情页「最近读数」 | **计划里的步骤 1 与步骤 4 合并成一个提交**：分开做就会先提交一个没有生产消费者的读取 API，正是上一轮 R17 删掉的那类东西 |
| `23ed717` | `SnapshotRetention` 纯判定（12 例） | 输出按 id 升序（计划未要求，但不排序的话设备侧比对不可复现） |
| `16c1a9e` | `prune()` 落库 + 接在后台闹钟之后 | **额外也接在 Widget 手动刷新后**：闹钟是唯一「自动」触发点但不可按需触发，清理需要一个能被验收的入口 |
| `be229c3` | `assert-history.ps1`（H1/H2/H3/H4/H7） | — |
| `c865bf1` | H5 配置变更不损坏历史 | 完成验收矩阵 |

`countSnapshots()` 未实现：计划里它是给界面显示「共 N 条」用的，而列表只取 10 行，没有消费者就不加（同 R17）。

### 门禁

- 宿主机：**26 suites / 349 tests / 0 failures / 0 errors**。
- `:app:assembleDebug` 通过；Lint **0 error / 30 warning**（与 Phase 3 收尾同数）。
- 两处 API 23 陷阱被 Lint 拦下而非运行时崩溃：`List#sort`、`Comparator.comparingLong` 均为 API 24。

### 设备验收（`tools/smoke/assert-history.ps1` → **17 checks / 0 failed**）

| # | 项 | 结果 | 证据 |
| --- | --- | --- | --- |
| H1 | 过期明细被清理 | **通过** | 注入 40 天前的两条成功读数 → 点 Widget 刷新 → 该时间窗内行数为 0 |
| H2 | 清理不带走屏幕上的数字 | **通过** | 清理后该账户仍有成功读数；桌面仍显示 `¥24.94` 与时间行 |
| H3 | 区间读取语义 | **通过** | 有读数、倒序、未来窗口为空 |
| H4 | 查询计划 | **通过，结论：不加新索引** | `EXPLAIN QUERY PLAN` 报 `USING INDEX idx_snapshots_account_time`，无 `SCAN TABLE usage_snapshots` |
| H5 | 改 Widget 配置不损坏历史 | **通过** | 先断言 `widget_slots` 确实变了，再断言 `usage_snapshots` 逐行摘要与 `daily_usage` 计数不变 |
| H6 | 删除账户清干净 | **不脚本化**（有意） | 该操作要删掉真实账户，两把 Key 只存在于设备上、无法重新输入；同一规则由 JVM 用例与 `assert-account-identity.ps1` 覆盖 |
| H7 | 体积 | **只报数不断言** | 清理前后文件字节数与行数打印为信息行 —— SQLite 删除后不 VACUUM 不缩文件，拿体积做断言等于写一条永不会失败的检查 |
| H8 | 历史列表可见 | **通过（人工截图）** | 详情页「最近读数」10 行倒序 `08:52 · ¥24.94 … 03:58 · ¥24.94`；余额未变故不显示差值 |

恢复纪律：全库备份 → `try/finally` 推回 → 删 `-wal`/`-shm` 侧车 → 逐表比对 + 「注入行不得残留」。推库前后各校验字节数与退出码（R16）。

### 已知限制

1. 明细只保 14 天，更早只有 `daily_usage` 的日粒度；「上周每天消耗」这类问题答不了。
2. 保留天数不可配置（D4）。
3. 清理触发点是后台闹钟与 Widget 手动刷新；只打开 App 前台不会触发。
4. H3 的窗口断言跑在拉回宿主的库文件上，证明数据形状；应用自身的读取由 H8 的截图证明。
