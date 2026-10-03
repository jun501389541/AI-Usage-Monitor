# 初步功能审查与下一步执行步骤

审查日期：2026-10-01

审查版本：`ce724cf`；开发范围：上游基线 `a72dff8` 至当前 HEAD 的 Phase 0～2。

依据：`AI-Usage-Monitor-Development-Plan.md`、`docs/PHASE-0-2-PLAN.md`、实际实现及已有验证脚本。

## 结论

Phase 0～2 的主体已经落地：Gradle 构建、Provider/Account/Credential 分层、SQLite 快照、DeepSeek 多账户管理与按账户绑定 Widget 均有实际代码。架构方向适合继续扩展。

建议先完成下面四项缺陷修复和 Phase 2 验收，再进入 Phase 3。当前已有测试通过不足以证明跨日、真实 SQLite 接线和刷新与删除交错正确。此次审查未修改产品代码；新增本报告。

## 本次独立验证

| 检查 | 本次结果 | 说明 |
| --- | --- | --- |
| JVM 单元测试 | 250/250 通过；失败、错误、跳过均为 0 | 使用 `--rerun-tasks` 重新执行 |
| Debug APK 编译 | 通过 | `:app:assembleDebug` |
| Android Lint | 0 error，41 warning | 包括精确闹钟、固定方向、旧 target 与资源文案等警告；不是零警告 |
| 模拟器账户证据 | 未通过全部断言 | 运行只读 `assert-two-live-accounts.ps1`，读取设备保存的数据库；未重新发起平台请求 |
| GraphFlow | 未提供有效代码摘要 | 主审请求未及时返回；独立审查请求返回空 summary/anchors，后续依据本地计划和代码审查 |

本次重新执行的构建检查：

```powershell
. .\tools\env.ps1
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --rerun-tasks
```

只读设备验收：

```powershell
.\tools\smoke\assert-two-live-accounts.ps1
```

当前设备保存的证据：

- `DeepSeek` 有 31 条成功记录，最新记录成功。
- `DeepSeekWork` 有 7 条成功记录，最新记录为 `AUTH_REQUIRED`。
- 两个账户最后成功余额均为 `24.94 CNY`。
- 当前快照均属于已存在账户，未检测到孤儿快照。

这能证明两个账户都曾获得读数，且一个账户失败时另一个账户仍保有成功记录；不能证明两个账户当前都可正常刷新。最新认证失败可能是此前隔离测试留下的状态，应在验收时重新确认。余额相同也不能证明 Key 相同，脚本不能把不同余额作为不同凭据的充分必要条件。

## 已确认的缺陷

### R1 · P2：跨日后今日用量可能持续归零

位置：`app/src/main/java/com/aiusage/monitor/storage/SqliteUsageRepository.java:134–143`。

`daily_usage` 的主键是 `(account_id, day)`，累计前读取却只使用 `account_id = ?` 和 `LIMIT 1`，没有日期条件或排序。存在昨天和今天的记录时，该查询会读取旧日记录；`sameDay` 为 false，后续刷新会再次重置今天累计值。

独立审查用内存 SQLite 复现了相同主键和查询：同时有昨日和今日记录时，查询返回昨日记录。当前 JVM 测试没有覆盖生产 SQLite 的这条查询。

修复要求：按账户与当天读取累计状态，确保读、算、写在并发情况下保持一致。引入可控制的时间来源，避免测试依赖真实午夜。

验收：

- 保留昨天记录；今天余额依次为 100、98、97，今天累计应依次为 0、2、3，昨天记录不变。
- 两个账户跨日累计互不影响。
- 覆盖充值导致余额上升的情况。
- 通过真实 SQLite/设备验证，而不只验证内存替身中的金额函数。

### R2 · P2：凭据保护降级提示未接通

位置：`app/src/main/java/com/aiusage/monitor/storage/SqliteCredentialStore.java:32`；`app/src/main/java/com/aiusage/monitor/account/AccountManager.java:228–232`。

`AccountManager.isCredentialDegraded()` 只接受实现 `DegradedAware` 的存储对象。`SqliteCredentialStore` 虽然有 `isDegraded()` 方法，却没有实现该接口，因此真实接线下检查始终返回 false。

Keystore 不可用并使用 `degraded-local` 时，账户列表和编辑页的降级提示不会显示，与 D3 和 README 的描述不符。

修复要求：连接真实存储的保护状态接口；界面正确显示降级状态；重新安全保存后解除提示。

验收：模拟 Keystore 失败，数据库保护标记、AccountManager 返回值、账户列表和编辑页提示一致；恢复 Keystore 后重新保存，状态与提示随之恢复。日志和截图证据不得包含凭据。

### R3 · P2：删除账户后在途刷新可以写回已删除历史

位置：`app/src/main/java/com/aiusage/monitor/refresh/AccountRefreshManager.java:187–192`；删除入口位于 `ui/account/AccountListActivity.java:404–415`。

“刷新全部”在后台执行时，账户卡片仍可操作。用户删除账户并清理历史后，已经开始的 HTTP 请求仍会按旧 Account 对象写入 `daily_usage` 和 `usage_snapshots`。数据库没有外键阻止写入，产生已删除账户的孤儿数据。

修复要求：协调刷新提交与删除，采用能保证原子性的事务、互斥或请求版本机制。仅在写入前做一次存在性查询仍有竞态窗口。成功和失败快照两条提交路径都要受保护。

验收：用可控请求暂停刷新 → 删除账户 → 放行成功或失败响应；账户、凭据、快照与日用量均不应重新出现。另验证更换凭据期间旧请求不会把新凭据下的数据状态覆盖回旧状态。

### R4 · P2：账户列表忽略最新失败状态

位置：`app/src/main/java/com/aiusage/monitor/ui/account/AccountListActivity.java:284–299`；`storage/SqliteUsageRepository.java:63–77`。

账户列表通过 `lastKnown()` 获取状态，但它底层只读取 `success = 1` 的快照。近期成功后再出现 401 或网络失败，列表仍可能显示“账户可用”；首次查询失败的账户则显示“尚未查询”。`AUTH_REQUIRED` 等失败分支不能反映实际最新尝试。

保留最后成功余额是正确的；最新尝试状态需要独立保存并展示。

修复要求：为读取端提供“最后成功数据 + 最新尝试状态”的统一视图，明确更新时间和错误状态，不把失败结果当作新的有效余额。

验收：成功 → 401、成功 → 网络失败、首次失败三个场景均显示正确状态；之前的成功余额和历史保留；成功恢复后错误状态解除。

## 下一步执行顺序

### 第一步：完成 Phase 2 修复批次

按 R1 → R2 → R3 → R4 的顺序实施，每项用独立提交便于复查。

先列出具体文件和验证案例再修改；补充能够覆盖上述触发条件的回归测试。生产 SQLite、凭据对象接线、请求与删除交错不能只用简单内存假对象代替验收。

修复期间同时对齐开发说明：

- README 称今日用量优先来自平台汇总接口，但当前实现由余额变化累计，应改为实际行为并说明其估算性质。
- 包名变化是实施计划 D4 已接受的“新应用身份”；不要据此宣称可以自动读取旧应用私有数据。旧版本迁移若要对外提供，应另行定义可执行的导入路径和验收。
- 旧明文 Key 删除与早期计划“旧键一律不删除”存在冲突，更新文档说明安全取舍，不应为了旧文档恢复明文凭据。

### 第二步：关闭 Phase 2 验收

调整 `assert-two-live-accounts.ps1` 的凭据独立判断；余额相等不应自动导致失败。使用明确的账户/凭据绑定证据与可控差异场景验证隔离。

按以下矩阵留存执行结果：

1. 两个有效凭据分别刷新并产生成功快照；记录账户 ID、来源、快照时间，隐藏凭据。
2. 使 A 失败并确认 B 继续成功，再反向验证。
3. 分别修改 Key，确认 Account ID、历史与 Widget 绑定不丢失。
4. 两个 Widget 绑定不同账户，冷启动、已有详情页复用、返回列表时均打开正确账户。
5. 跨日累计、凭据降级、刷新期间删除按 R1～R4 的验收验证。
6. 重跑现有单元测试、编译、Lint，并明确列出仍接受的警告。

完成后把 `docs/PHASE-0-2-PLAN.md` 的验收状态更新为有证据的实际结果，再进行修复复查。

### 第三步：Phase 3 通用 Widget

先完成 2×2 和 4×2 的具体实施计划，随后逐步实现：

- `WidgetConfig` 升级为 Slot 配置：每个 Slot 保存稳定的 Account ID、Metric ID 和顺序。
- Provider 公开可展示指标；配置界面根据实际能力列出可选指标。
- 2×2 单账户；4×2 允许同平台多账户及多平台账户组合。
- 显示账户名称、最后成功更新时间、最新刷新状态和 stale 提示。当前 `WidgetUpdateManager` 虽获取 stale 结果，渲染时只传余额与用量，没有把状态/时间展示出来。
- 小组件增加手动刷新入口，经共享刷新管理器刷新关联账户。
- 对账户删除、停用、无数据、无支持指标给出明确状态。
- 更新数据库版本与迁移；现有每实例单账户绑定转换为一个 Slot，保持原账户绑定。

Phase 3 验收重点：同一个账户可以出现在多个 Widget；4×2 能显示两个 DeepSeek 账户；刷新失败保留成功数据且明确提示状态；配置在重启、尺寸变化和升级后保留。2×4 在这两个核心尺寸通过后再扩展。

### 第四步：确认 Phase 4 快照能力，再进入 Bridge

当前已有成功/失败 `usage_snapshots`，不要重复建设存储基础。补齐按账户和时间段读取、保留策略及必要索引，并验证 Widget 配置变更不损坏历史。

之后按原计划进入 Windows AI Usage Bridge 的独立可行性验证和 MVP：先确认当前官方 Codex App Server 额度接口、字段和认证边界，再固定统一返回结构与离线缓存行为。配对、发现、Direct OAuth 和 AUTO 继续按各自阶段实施。

## 可直接交给开发 Agent 的本轮任务

> 阅读本报告与当前 Phase 0～2 计划，先给出 R1～R4 的逐项文件改动计划和回归案例，然后实现修复并关闭 Phase 2 验收。不要把现有测试通过等同于真实双账户验收。每项修复独立提交，提供测试、编译、Lint、设备验证结果及剩余限制。完成本轮修复并复查后，另行提交 Phase 3 的 Widget 配置迁移、Slot 模型、状态与更新时间展示实施计划。

## 审查范围与限制

本次针对当前实现和已有计划做静态审查、独立构建测试及设备数据库的只读检查。未重新执行会修改账户/Key、安装 APK 或删除历史的设备脚本，因此已有截图与此前安装记录不能作为本次重新完成的设备验收。

Phase 3～12 尚未开始是已声明的阶段范围，不把 Codex、Bridge、Slot 和历史图表的缺失计作当前 Phase 2 缺陷。包名变化属于 D4 已接受决定。首次审查没有发现有足够证据支持的 P0/P1 问题；以上为 2026-10-01 首次审查记录，修复后的复审见下节。

## 修复后复审

复审日期：2026-10-02

复审范围：`ce724cf..5f2f487`，聚焦 R1～R4、Phase 2 验收以及新加入的跨日设备测试脚本。

### 结果

首次审查的 R1、R2、R4 已按报告修复。R3 的主要提交路径已加保护，但复审发现仍有失败分支未纳入保护，因此 R3 还不能标记为完全关闭：

- R1 按 `(account_id, day)` 读取累计记录。
- R2 `SqliteCredentialStore` 已实现 `AccountManager.DegradedAware`。
- R3 删除与 Provider 成功/失败结果写入共用 `AccountManager.writeMonitor`；网络请求留在锁外，返回时重新确认账户仍存在。新增测试覆盖 Provider 请求中的删除及其他账户仍可刷新。
- R4 账户视图分别读取最后成功数据和最新尝试，列表能呈现认证/网络失败并标明保留的旧余额。

### R3 仍需补齐的竞态

位置：`app/src/main/java/com/aiusage/monitor/refresh/AccountRefreshManager.java:181–205`。

- `openCredential()` 失败分支在 `isGone()` 返回后直接写失败快照，没有在共享锁内重新确认并落库。删除可以插入“账户存在检查”和 `recordFailure()` 之间，重新产生孤儿快照。
- Provider 注册查找失败分支也直接写失败快照，没有使用删除共用的写锁。
- 凭据修改期间，已用旧 Key 发出的请求在返回后只检查 Account ID 是否还存在。因为账户仍存在，请求结果仍会写入该账户历史；若新 Key 属于另一个 DeepSeek 账户，列表会显示旧 Key 的余额。清除凭据也有同样窗口。

修复要求：所有成功与失败快照提交都在同一删除锁内完成存在性检查；为凭据变更建立单调递增的请求代次，提交前比较代次并丢弃旧代次结果；在读取凭据失败或发起请求前也处理账户已删除的状态。为上述每条路径补锁存器控制的交错测试。

调用方传入内存 `AuthContext` 的入口还应在发起 Provider 请求前确认账户仍存在，避免已删除账户的陈旧调用继续使用已读入内存的密钥发出请求；结果提交时的检查仍需保留。

新增一项 P2 验收工具问题：

### R5 · P2：跨日设备测试会污染实际账户的今日用量

位置：`tools/smoke/assert-daily-usage.ps1:51–82`。

脚本把完整应用数据库拉到主机后，直接删除第一个启用账户的 `daily_usage` 行，插入 `5.00` 昨日累计和 `3.00` 今日累计，再把数据库推回设备。脚本结尾没有备份/恢复原行；即使检查通过，界面今日用量也会被固定成注入值，失败退出也会留下修改。

设备/产物证据表明此脚本此前已执行：`tools/smoke/out/inject.sql` 在 07:48 记录了 `2026-09-30 = 12.00/5.00`、`2026-10-01 = 10.00/3.00` 的注入；当前设备副本显示 `2026-10-01 = 24.94/3.00`。执行前的 `verify-b4.db`（05:54）显示同一账户当天累计为 `0.00`，证明历史值已被测试数据替换。当前设备日期 `2026-10-02` 的累计是 `0.00`。复审没有再次运行该脚本，也没有覆盖设备数据库恢复这些记录。

修复要求：把验证放进可丢弃的 QA 数据库/测试账户；若必须操作设备库，应先保留原账户全部日用量行，在保证应用停止和写回成功的前提下用 `try/finally` 恢复，并明确异常中断时如何复原。对现有个人账户不应直接运行当前脚本。

### 验证更新

本次重新执行 `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug`：19 个测试套件共 **270 项通过**，失败/错误/跳过均为 0；Debug APK 编译成功；Lint 为 43 条 Warning、0 Error。与初次审查的 250 项和 Phase 2 计划记录的 265 项不同，这是当前提交复跑得到的数量；Phase 2 计划中的旧数字应更新。

本次再次执行只读 `tools/smoke/assert-two-live-accounts.ps1`：16/16 检查通过。两个账户都有最新成功快照、账户与凭据行分离且无孤儿快照；最后余额都是 `24.94 CNY`，相等属合理情况。该检查证明设备状态健康和两个凭据行独立；两个密钥本身的不同根据阶段记录中的脱敏指纹/真实密钥刷新证据确认，数据库脚本不读取或打印明文。

复审没有再次运行 `assert-daily-usage.ps1` 或其他会改写账户/密钥/历史的设备脚本。

### 下一步

R1、R2、R4 的实现修复、构建与双账户只读验收已通过。进入 Phase 3 开发前仍应先关闭 R3 遗漏的凭据代次和所有失败分支竞态，修复 R5 并调查/处理设备中被替换的历史累计，修正 Phase 2 计划中的验收方式与测试总数，然后单独编写 Widget Slot 迁移及 2×2/4×2 验收计划。Phase 3 应把状态、新鲜度、更新时间和手动刷新一并列为交付项。后续 Bridge 工作另行验证其接口与认证边界。

### 复审结论处置（2026-10-02，复审后修复）

- **R3 失败分支竞态 — 已关闭**（commit `f0e9319`）：`AccountRefreshManager` 的 openCredential catch 与 registry.require catch 两处失败快照写入全部包进 `accountManager.writeMonitor()` 与 `isGone` 同锁；新增 3 用例（删除后 openCredential 失败 → abandoned 零写入、存活账户 openCredential 失败 → 记失败行、未知 provider 失败 → 记失败行），变异验证 isGone 改恒 false 时删除类用例变红。回归 19 suites / 273 tests / 0 failures / lint 0 error。凭据代次窗口（在途请求携旧 Key 落库）按本节前述建议仍为已知限制，未在本次关闭。
- **R5 脚本污染 — 已修复**：`tools/smoke/assert-daily-usage.ps1` 重写为备份-恢复模式——拉库后立即 `Copy-Item` 全库备份，注入/刷新/断言包进 `try`，`finally` 中 force-stop → 推回备份 → 删除 `-wal`/`-shm` 侧车（防旧页复活）→ 拉回并逐行比对 daily_usage 断言恢复成功。修复后全量跑 10/10 PASS（含「device daily_usage restored to pre-test state」），跑后独立拉库复检 daily_usage 与基线一致、零注入残留。
- **设备历史恢复 — 已完成**：污染现状先固化证据（`tools/smoke/out/r5-cleanup-before.db`：acct_294b8c1eaff5 存在基线中没有的 2026-09-30 假行 12.0/5.0，且 2026-10-01 total_usage 被注入成 3.00；基线 `verify-b4.db` 中 09-30 行不存在、10-01 total=0.00），随后宿主侧 `DELETE` 假行 + `UPDATE total_usage='0.00'` 后推回设备，拉回复检与基线一致（10-02 行为基线之后自然累积的合法行，保留）。

## Phase 3 与前次修复的独立复审（2026-10-02）

范围：`5f2f487..00e2163677cf8dbabbd5f5f5dd7b32fe92b0bc70`。只读审查产品代码；仅更新本报告，临时探针在被忽略的 `tools/smoke/out/review-00e2163/`。未安装 APK，未执行任何会修改设备凭据、历史或 Widget 配置的脚本。

结论：前次两条失败提交与删除互斥的修复成立，已删除账户传入 `refreshWithAuthContext` 也会提前放弃；Slot 数据模型、schema v2 迁移及多账户刷新链已实现。但 **R3 和 R5 尚不能完全关闭，Phase 3 有三条可触发的展示/点击回归**。以下六项均为 P2，建议修复后再推进 Phase 4。

### R3-A：凭据修改未参与提交锁，代次检查仍存在窗口

位置：`account/AccountManager.java:194–244`（三种凭据变更）；`refresh/AccountRefreshManager.java:240–264`（检查与落库）。

`updateCredential` / `replaceCredential` / `clearCredential` 不持有 `writeMonitor`，且在数据库修改之后才推进代次。因此旧响应在提交锁内通过 `isVoid` 后，UI 仍可完成换 Key/清除 Key；随后旧响应继续记入日用量与成功快照。凭据更新已完成、代次尚未递增之间也有相同窗口。

宿主探针使用本次编译的生产类与现有测试替身，把旧请求暂停在 `recordDailyUsage` 入口（即检查之后、落库之前），此时完成换 Key，再放行，得到：

```text
PROBE1 replacement completed while refresh holds writeMonitor: generation 1 -> 2
PROBE1 old response committed after replacement: success=true abandoned=false snapshots=1 dailyWrites=1
```

修复：凭据变更、代次推进与结果提交应使用同一互斥协议；网络调用仍放在锁外。补覆盖“代次检查之后、数据库提交之前”的成功/失败交错测试，不仅覆盖 Provider 请求期间换 Key。证据：`RefreshRaceProbe.java`、`refresh-race-output.txt`。

### R3-B：旧 Account 对象与当前凭据代次混用

位置：`refresh/AccountRefreshManager.java:191–195`，调用方 `refreshAll():325–328`。

刷新在入口读取当前代次，却用调用者此前捕获的 `Account.credentialId` 打开凭据。复现：A、B 顺序刷新，B 最初没有凭据；暂停 A；给 B 保存有效新 Key；继续批次。B 的旧对象仍持有空 credentialId，但捕获的是新代次，因此打开凭据失败后检查通过，写入 `AUTH_REQUIRED`，有效新 Key 根本未被请求。清除后重新保存产生新 credentialId 时同样可触发。

```text
PROBE2 stale batch credentialIdEmpty=true currentCredentialIdEmpty=false currentGeneration=1
PROBE2 valid new credential incorrectly reported: error=INVALID_CREDENTIAL abandoned=false status=AUTH_REQUIRED providerFetchCount=1
```

修复：短临界区内取得当前 Account、凭据及一致的代次，再发起锁外网络请求；不能把旧 Account 对象与新代次拼成一个请求状态。证据同上，探针实测了当前生产类。

### R5：恢复上传失败后仍会覆盖设备数据库

位置：`tools/smoke/assert-daily-usage.ps1:147–150`；新脚本 `assert-widget-slots.ps1` 的 `Push-Db` 同样未核对原生命令退出码。

`finally` 已补全正常恢复流程，但没有检查 `adb push` 是否成功。PowerShell 的 `ErrorActionPreference=Stop` 不能保证这些 `cmd /c` 原生命令失败就终止。上传失败后仍执行远端 `cat backup | run-as sh -c 'cat > db'`：源文件不存在时会截断目标数据库；若远端残留旧文件，则可能覆盖成旧备份。恢复后的单账户日用量比对发现问题时，设备库已被覆盖。

本地 adb 替身模拟“上传失败、下一条 shell 仍执行”，使用合成文件，得到：

```text
RESTORE_PROBE ErrorActionPreference=Stop pushExit=1 shellContinued=true shellExit=0 bytesBefore=20000 bytesAfter=0
```

此项是 Windows 等价管道与原生命令控制流的模拟，**不是 Android shell 的设备实测**，未调用真实 adb。证据：`RestoreFailureProbe.ps1`、`fake-adb.cmd`、`restore-failure-output.txt`。

修复：优先使用可丢弃 QA 数据库；若保留设备恢复，必须验证上传退出码、远端备份完整性后才能覆盖，使用应用目录内的临时文件验证后替换，并验证整个恢复数据库。缺失/旧远端备份、断连、上传失败等应有故障注入验证。不要把现有脚本“正常流程 PASS”视为恢复可靠性验收。

### R6：4×2 升级后不再显示今日用量

位置：`widget/WidgetRenderer.java:125–126`。

默认指标仍是 `[balance, today_usage]`，Resolver 也生成两项值；但 Dashboard 每 Slot 仅画 `valueLines[0]`，布局没有第二项显示路径。因此任何 4×2 只显示余额，旧版今日用量在升级后消失。配置页仍承诺显示两项。现有保存的 `widget-slots-ui.xml` 也只显示每行 `¥24.94`。这不是“指标选择延期”，而是默认已有指标的展示回归。

修复：每行显示已配置的两项默认指标及可辨识标签，例如 `余额 ¥24.94 · 今日估算 ¥0.00`；设备 A2 验收应比对每个 Slot 的余额与今日用量，而不只是账户名或第一项值。

### R7：失败尝试将旧余额的显示时间重置为“刚刚”

位置：`widget/WidgetSlotResolver.java:134–149`。

指标取 `lastSuccess`，时间却优先取 `lastAttempt`。最后成功在 12 小时前、刚刚网络失败时，当前生产 Resolver 的宿主探针输出：

```text
actual_balance_age_hours=12
rendered_values=[¥24.94, ¥0.00]
rendered_footer=刚刚 · 网络连接失败 · 最后成功数据
```

反复失败会持续把旧余额标成“刚刚”，无法判断保留值实际年龄，违背本阶段“最后成功更新时间”的口径。现有 `WidgetSlotResolverTest` 断言了这一错误表现，测试通过不能排除此问题。

修复：保留值的时间取最后成功；最新错误状态仍取最后尝试。若另显示尝试时间，应明确标注“尝试”，不能混作数据更新时间。首次失败没有成功值时可以单独显示尝试时间。证据：`FreshnessProbe.java`、`freshness-output.txt`。

### R8：点击已删除 Slot 会打开其他账户

位置：`widget/WidgetUpdateManager.java:245–246`，关联 `WidgetSlotResolver.java:114–115` 和 `ui/MainActivity.java:215–230`。

删除态仍携带非空原 accountId，Manager 为它创建账户详情 PendingIntent；详情页找不到该 ID 后回退到首个启用账户。因此点“账户已删除 · 请重新配置该 Slot”会打开其他账户，且详情页可能自动刷新它，没有进入 Widget 配置。计划 §3.3 要求删除态点击进配置页。

修复：为不存在账户的 Slot 创建带当前 widgetId 的 `WidgetConfigActivity` Intent；账户详情页也应明确处理指定账户消失的情况。补删除后点击的路由验收，不能只断言删除态文本和零快照。

### 本次验证与范围限制

- 重新执行 `. .\tools\env.ps1` 后，`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --rerun-tasks` **BUILD SUCCESSFUL**；23 suites / **329 tests** / 0 failures / 0 errors / 0 skipped；Lint **0 Error / 30 Warning**；`git diff --check 5f2f487..HEAD` 通过。
- 本次 Debug APK SHA-256：`09B71D36CE72193EF82A3BA7231AC214BF99A8A8DCCFC7FBED7D3E161E41EB77`。这是编译产物证据，不代表本次安装或设备验收。
- R3 的两条宿主探针与 R7 的 Resolver 探针运行当前编译的生产类。R5 仅为本地替身故障模拟。R6/R8 根据具体生产路径和已保存的桌面 XML 核对。
- 当前设备只读检查 `assert-two-live-accounts.ps1` 在普通及提升权限环境均未返回 adb 数据，已中断；本次**没有新的双账户设备 PASS**。既有 A1–A9/23 checks 的记录是前次 Agent 的证据，不冒充本次重新验收。
- 逐 Slot 指标选择与 REFRESHING 态在当前计划实施记录中明确写为延期，不列入上述六条代码缺陷，但也不能称为已交付功能；三行上限和 2×4/4×4 不在本轮修复范围。

### 下一步执行顺序

1. 先修 R3-A/B：统一凭据变更与提交互斥，取得一致的当前 Account/凭据/代次；加入两条已复现顺序的确定性回归测试，覆盖失败分支与清除后重新保存。
2. 修 R5：隔离设备烟测或建立可靠恢复协议，并补上传失败/缺失备份/旧备份的本地故障模拟；修完前不运行两个会推回数据库的脚本。
3. 修 R6/R7/R8：Dashboard 两项指标、成功数据时间、删除态配置路由；修改错误的时间断言，补实际 Renderer 与点击路径验收。
4. 更新 `HANDOFF.md`/Phase 3 验收表：不能仍把 R3 全关闭、R5 异常恢复可靠以及完整显示/路由验收写为已通过。重新验证上述缺陷后，再补 Phase 3 延期项或明确交付范围，然后制定 Phase 4 计划。

---

## Phase 3 审查（2026-10-02）

审查范围：`df85cbc..b1f1613`（R3 凭据代次修复 + Phase 3 六步）。方法：主审自查死代码，另开两个独立审查 agent（一个宽范围、一个只查正确性）；**每条发现先回代码核实再处置**，不照单全收。审查期间未改动产品代码之外的结论：所有修复各自独立提交，门禁数字为提交时实测。

### 已修复

| # | 发现（严重度） | 位置 | 处置与提交 |
| --- | --- | --- | --- |
| R6 | 凭据变更的代次推进不在写锁内，旧 Key 结果仍可能在「检查通过」与「落库」之间被判为未变（P1，属上一轮 R3 修复自身的漏洞） | `account/AccountManager.java` 三个凭据写方法 | 三个方法整体纳入 `writeMonitor`；新增门闩用例 `aCredentialSaveCannotAdvanceInsideTheWriteSection`。变异验证：把锁换成 `new Object()` 后该用例红（`the save must block ... expected:<1> but was:<0>`）。`7ee7ba3` |
| R7 | 4×2 只画每个 Slot 的第一个指标，`today_usage` 算出来被丢弃（P2，Spec §33 的 Dashboard 恰恰要求多指标） | `widget/WidgetRenderer.java` 行渲染 | 每行加第二数值单元；设备脚本新增断言「每行两个金额都上屏」。`6b2268e` |
| R8 | 保留余额被标注失败尝试的时间（三小时前的数字显示成「刚刚」）（P2，违反 §41） | `widget/WidgetSlotResolver.java` footer | 年龄跟随屏幕上那个数字的时间；新增 `theAgeQuotesTheRetainedBalanceRatherThanTheFailureBesideIt`。`6b2268e` |
| R9 | 账户列表与 Widget 在无余额时的今日用量不一致（`今日 0.00` vs `今日 —`）（P2） | `ui/account/AccountListActivity.usageText` | 与解析器同一道守卫。`6b2268e` |
| R10 | Widget 配置页自成第三套口径：只读成功快照并自写「上次读取成功/尚未查询」，认证失败的账户被当成可用推荐（P2，R4 在新界面复现） | `widget/WidgetConfigActivity.statusText` | 改走 `AccountView.displayStatus` + `StatusWords`。`366e71f` |
| R11 | 「账户已删除 · 请重新配置该 Slot」这行的点击却打开另一个账户详情（MainActivity 对死 id 回退）；且配置页自 Phase 2 起声称可从 Widget 进入重绑，实际无任何入口（P2） | `widget/WidgetUpdateManager.tapTargetFor` | 无可解析账户的行改为打开本 Widget 的选择器，重绑路径首次真正可用。`366e71f` |
| R12 | 迁移把绑定复制进 `widget_slots` 却留下 `widget_config.account_id` 原值，注释还声称它是默认值 —— 一个事实两个来源（P2）；`INSERT` 非幂等，重跑会撞主键并让版本永远升不上去（P1 后果） | `storage/Database.migrateWidgetSlots` | 迁移内清空遗留列 + `INSERT OR IGNORE`；新增可逆的 v1→v2 升级彩排断言（A1u）。`b1f1613` |
| R13 | 未绑定 Widget 永远不重绘：它画的是回退账户，但没有 slot 行记录，反查找不到它（P2） | `widget/WidgetUpdateManager.updateWidgetsForAccount` | 无 Slot 的实例一并重绘。`b1f1613` |
| R14 | Widget 手动刷新排队期间实例被删除，仍会对可能已复用的 id 重绘（P3） | `widget/WidgetRefreshReceiver` | 配置行不存在即跳过。`b1f1613` |
| R15 | 验收脚本读已被停止写入的 `widget_config.account_id`，「Widget 配置未变」这条断言变成永远不会失败（P2，正是 R 类缺陷本身） | `assert-account-identity.ps1`、`dump-db.ps1`、`place-widget.ps1` | 改查 `widget_slots`（含未绑定显示为 `-`）。待与脚本一起提交 |
| R16 | 验收脚本推库不校验：`adb push` 或写回失败会把唯一保存两把真实 Key 的数据库写成残缺文件（P1） | `tools/smoke/assert-widget-slots.ps1` | 推前后各校验字节数与退出码、失败即中止不动应用库；A7/A1u 恢复改为逐表比对 |
| R17 | 只被测试调用的生产 API 共 7 处（`lastKnown`、`WidgetConfig.slot/primaryAccountId/hasAccount`、`showsPeakStatus`、`boundWidgetTypes`、`findWidgetMetric`、`WidgetSlot.withAccountId/withMetricIds`），其中「整 Widget 主账户」正是历史两次口径分叉的形状（P3） | 多处 | 全部删除，测试改经 `getSlots()`/`accountIds()` 读取（`091095e`） |

### 核实后判为不成立或按限制接受

- 「`2001+widgetId*16+slot` 与 `3001+widgetId` 数字相同会串台」：不成立。`PendingIntent` 相等比较包含 Intent 的组件与数据，指向不同 Activity 不会互相覆盖；同一 Activity 内的唯一性由 `widgetId*16+slot` 保证，前提是 `slot_index < 16`（配置页容量上限 3，注入超出的行也不会上屏）。接受，不加代码。
- 2×1 不显示时间/状态/刷新、4×2 超过 3 个 Slot 只画前 3 行、每 Slot 选指标与 `REFRESHING` 态延后：均为 Phase 3 计划 §10 已声明的范围，不是缺陷。
- 「迁移重跑会双插」的严重度：独立审查判为 P1，实为需要事务半提交才可达；仍按低成本加了 `OR IGNORE`。

### 门禁

`331 tests / 0 failures / 0 errors`，`:app:assembleDebug` 通过，Lint **0 error / 30 warning**。设备验收矩阵在脚本加固后重跑，结果记在 `docs/PHASE-3-PLAN.md` §10。

## Phase 0–7 综合复审的处置（2026-10-03）

复审文档：`docs/PHASE-0-7-REVIEW.md`（基线 `bb41d26`，只读复审，未跑测试）。它确认了 5 项仍未关闭的问题，跨 Phase 0–2、3、4、4×6 与 Phase 7 Bridge。逐条先复现再修，一项一提交：**能在 host JVM 上跑红的（§2.1/§2.3/§2.5）先让新断言红**；红不出来的（§2.2/§2.4 的判断落在需要 Android Context 的类里）先读代码确认形状，再用变异证明新加的断言真的会咬。下表「复现」列区分这两种。

| # | 问题 | 复现（修前红的那一句） | 处置 | 提交 |
| --- | --- | --- | --- | --- |
| §2.1 | `refreshAll()` 用批量列表里的旧 `Account` 配当前代次，刚保存的新 key 被记成 `AUTH_REQUIRED` | `CredentialEpochDropsStaleResultTest > aKeySavedWhileTheFirstAccountIsInFlightIsTheOneTheBatchUses`：「the request for the second account must carry the key that was saved, not the empty one its list held」 | 刷新入口在写监视器内按 id 重读 Account 与同代次凭据，再拿这个对象开凭据；网络调用留在锁外。另加反向对照：轮到自己时仍无 key 的账户照旧 `INVALID_CREDENTIAL`，「重读」不能变成吞掉缺失凭据 | `c52b6e1` |
| §2.2 | 移除最后一个 Widget 后零点闹钟仍每日 `refreshAll()` + prune，且自己重排 | 修前：读 `WidgetRefreshReceiver` 确认守卫是 `midnight || hasWidgets(...)`，receiver 需要 Context、JVM 上跑不红；修后：变异 N1/N2/N3 三条全部被 `RefreshDutyTest` 打死（含两条源码 pin） | 新增 `RefreshDuty`（零点=只重绘、间隔=刷新+重绘、无 Widget=无事），receiver 问它而不是 `midnight \|\| hasWidgets`；`scheduleMidnight` 补上与 `schedule` 同样的 `hasWidgets` 守卫；最后一个 Widget 的 `onDisabled` 用新加的 `cancelMidnight` 一起拆掉 | `a0bf9cf` |
| §2.3 | 同时间戳快照的保留决胜取决于游标返回顺序，而读取端用 `timestamp DESC, id DESC` | `SnapshotRetentionTest > rowsSharingOneInstantAreJudgedByTheSameRuleTheReadPathUses` 与 `aTieBetweenSuccessesKeepsTheNewerIdNotWhicheverRowCameFirst` 两条同时红 | 策略排序键补成 `(timestamp ASC, id ASC)`；同一行集合正序/逆序喂进去必须给出同一个 doomed 列表 | `1c3f06e` |
| §2.4 | 历史列表只读 `getBalance()`，Codex 成功读数显示成「时间 · —」 | 修前：读 `renderRecentReadings` 确认只取余额；`ReadingWordsTest` 的四条形状断言是新写的、直接红在缺实现上；修后变异 K1（历史退回只读余额）、K3（quota-only 又变破折号）都被打死 | 新增 `ReadingWords.value()`（有钱显示钱，否则显示窗口，两者都没有才是破折号）与 `QuotaWords.compact()`（带重置那一句会把历史事实按今天的钟改写，所以故意不带）；MainActivity 的历史行改问它，配一条源码 pin | `cbae8fd` |
| §2.5 | `Issue()` 在 `commit()` 之前清除 fail-closed 的 `refused` 标记 | `TestIssueCannotLiftTheRefusalWithoutWriting`：「a failed Issue lifted the refusal and let the old code back in」 | 标记只在落盘成功后清除；测试走完「猜测写失败 → 签发也写失败 → 旧码仍 `ErrLockedOut` → 真签发成功才放行 → 重开库确认旧码已不在」 | `abcd8a7` |

### 本轮门禁（复跑，不是引用旧数字）

```text
Android  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --rerun-tasks
         SUITES=38 TESTS=428 FAILURES=0 ERRORS=0 SKIPPED=0   （Phase 6 末是 36/411）
         Lint 0 error / 30 warning
Bridge   gofmt -l 空；go vet 干净；go test -count=1 -v ./... → 109 PASS 行 / 0 失败 / 2 跳过
         （Phase 7 步骤 1-4 收尾是 108；+1 来自 R-5 的新用例）
```

Lint 从 Phase 6 记的 31 条 warning 变成 30：少的是 `AndroidGradlePluginVersion`，那条要联网探测新版本，`--offline` 下不出。其余 30 条按 id 数：`DiscouragedApi`×4、`HardcodedText`×4、`LockedOrientationActivity`×4、`SetTextI18n`×3、`UnusedResources`×3、`SmallSp`×2、`UselessParent`×2，以及 `DataExtractionRules`/`DisableBaselineAlignment`/`MissingPermission`/`NewerVersionAvailable`/`OldTargetApi`/`RtlSymmetry`/`UnusedAttribute`/`VectorPath` 各 1。

### 未闭环的一条

复审 §5 要求「设备侧只运行与更改直接相关、且能保全现有模拟器数据库的验收」。本轮做不到：`assert-bridge-phone.ps1` 在 2026-10-03 两次跑不完，原因都在环境而不是代码——模拟器的软键盘开始吞掉注入按键（`input text` 打到普通文本框不进字，同屏密码框 14 个字符全进；金丝雀「injected text reaches a plain-text field」在 `ime disable` 之后 PASS），随后模拟器整机挂死（`detected a hanging thread 'QEMU2 CPU0 thread'`）。所以 R-1/R-2/R-4 三条涉及设备行为的修复，目前只有 host-JVM 证据（含两条源码 pin），**设备验收记为未判定**，等一台活设备重跑。
