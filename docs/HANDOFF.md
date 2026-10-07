# HANDOFF — AI Usage Monitor 交接文档

> 写给下一个接手的 agent。本文件自包含：读完这份 + §4 文档地图里的计划与审查文档，你就能继续开发而不需要翻旧会话。
> 最后更新：2026-10-07。代码基线 = `ff45925`（**Phase 7 手机半边步骤 5-10 交付完毕**：`bridges` 表 v3、payload 三通道、SPKI 钉指纹 + 逐连接主机名校验、配对/设备界面、`tools/smoke/assert-bridge-pair.ps1`；此前 Bridge 半边与两份外部复审也已处置完）。⚠️ 本地把 `a6cc527` 的 subject 改写后重排了 5 笔提交，那批哈希全变了（`a6cc527`→`e384598`、`880e624`→`e8ccf6b`、`4b27aa5`→`54e7976`、`1a76b94`→`8524095`、`efe9373`→`45f73d3`）；这些提交从未推送过，但引用旧哈希的地方都以 `git log --oneline -8` 为准。
> 最近已记录门禁（2026-10-04 09:25）：Android `SUITES=50 TESTS=517 FAILURES=0 ERRORS=0 SKIPPED=0`、Lint **0 error / 41 warning**；Bridge `gofmt`/`vet` 干净、**109 PASS / 0 失败 / 2 跳过**。
> **设备验收全绿且无「未判定」**：`assert-bridge-pair.ps1 -BridgeHost 0.0.0.0 -PairTtl 600s`（不带 `-SkipLegacy`，2026-10-04 08:55）= **CHECKS=59 FAILURES=0 SKIPPED=0 → RESULT: *PASS***，`tools/smoke/out/pair-run-21-full.txt`；`b1dc773` 的 insert 改动之后又以同一旗标重跑一轮，run 23 = **59 / 0 / 0 → RESULT: *PASS***（`pair-run-23-full.txt`），顺带把夹在中间的 run 22 结案：它那 12 条红是客机 UI 死了（`am_anr` 记录 nexuslauncher 与 systemui 输入分发超时，之后所有 dump 只有 `package="android"`），不是应用，同一份构建换个健康设备全绿。P-1…P-8、P-11（把 `assert-bridge-bind.ps1` 当子判据跑绿）与 P-9 的三个老脚本 + `assert-bridge-phone.ps1` 全在同一轮里判定；清理四行含「未登记的诱饵 Bridge 不被删」对照也过，两个真实 DeepSeek 账户首尾一致。
> P-9 曾在 run 12/20 里从第一条 UI 断言 `a Codex account can be added through the UI` 起红、其余 30 条都是下游，根因是**老手机脚本不滚动**：步骤 9 往账户编辑卡里加了「与电脑配对（推荐）」+「或直接手输地址（调试通道）」标题，把 `保存` 挤出视口，而 uiautomator 根本不上报 ScrollView 视口外的节点（run 20 的 `dumps/29-form-filled.xml` 里三个 EditText 全填好、`content-desc="保存"` 就是不存在）。`1a76b94`（改写提交信息后为 `8524095`） 让它滚动查找、找不到就明说，之后 `55 checks / 0 failed`。顺带一条 UI 事实（不是 bug）：1080×2400 上手输地址要滚一下才点得到保存。
> Phase 0-6 全部完成；Phase 7 手机半边交付且配对路径有全绿设备验收；扫码通道仍未做（等 D1），见 `docs/PHASE-7-PLAN.md` §10。

---

## 1. 项目一句话

把上游「DeepSeek 余额查询」安卓小工具（单账户、明文 Key、无测试）重写为**多账户、密钥加密、分层架构、有测试**的 AI Usage Monitor，严格按 `AI-Usage-Monitor-Development-Plan.md`（2504 行规范，下称 Spec）执行。Phase 0–6 全部交付并通过设备验收；Phase 7 手机半边（步骤 5-10）也已交付并通过设备验收。尚未实现的是二维码配对通道，等待 D1 决策。

## 2. 当前状态快照

| 项 | 状态 |
|---|---|
| 提交链 | 收尾：`b1dc773`(九处 insert 结果改为「-1 就抛并点名表与键」 + `StorageWriteReportTest`) ← `1d07642`(reword 之后把文档里的哈希指回新号) ← Phase 7（手机半边步骤 5-10）：`45f73d3`(步骤 10 收尾：run 21 全绿 + 文档) ← `8524095`(老手机脚本滚动找保存) ← `54e7976`(PLAN §10 步骤 10 记录 + HANDOFF 同步) ← `e8ccf6b`(README 记 重新配对 已设备验证 + 验收清单) ← `e384598`(被修账户按 `credentials.updated_at` 认定；`-f` 自炸改拼接) ← `3be3102`(UI 流程中途不再拉库、每个 tap 返回值进断言) ← `55a53d5`(验收抬头写 AVD，删无信息量的 ro.debuggable) ← `973e199`(子进程有界等待 + 每轮独立目录 + `-SelfTest`) ← `9b71cc9`(验收脚本入库) ← `dd7db91`(README 地址改写时序) ← `9919dc8`(报出被拒的桥接写入 + 让「重新配对」可达) ← `dfbd2a4`(`PairingStore` 落库原子性) ← `6526cc4`(步骤 9：配对落库 + 按 `bridges` 行读取 + 配对/设备界面) ← `3a02ae6`(步骤 6-8：payload 三通道 + 钉指纹 + `PairingClient`，含 IPv6 offer 的跨语言缺口修复) ← `597ebaa`(步骤 5：`bridges` 表 v3 + 设备侧可逆彩排) ‖ Phase 7（Bridge 半边 + 复审处置）：`cbae8fd`(综合复审 §2.4 历史读数形状) ← `a0bf9cf`(§2.2 无 Widget 不再每日联网) ← `c52b6e1`(§2.1 批量刷新按 id 重读) ← `1c3f06e`(§2.3 保留并列决胜) ← `abcd8a7`(§2.5 拒绝标记的落盘次序) ← `bb41d26`(A10 主机名策略写进计划 + 复审入库) ← `2d10035`(手机验收脚本的两处自身缺陷) ← `8aeea1e`(offer 由绑定推导 + 管理路径回到回环 + `--add-device` 不再 mint) ← `827435f`(配对注册表只记住真落盘的东西：撤销事务、锁定持久、空文件=损坏) ← `cbab79e`(步骤 1-4 实施记录) ← `99733c4`(`/v1/admin/*` + payload + flags) ← `a0ca7e0`(令牌门 + 绑定守卫) ← `71be00a`(pair/device 存储，哈希落盘) ← `331abdc`(身份与指纹) ‖ Phase 5 复审：`8bb8f50`(验收断言改字段 + 真子进程两场景) ← `0c21ba7`(拒绝记录随每条路径带出) ← `568de0f`(刷新事务串行化) ← `0eb16e2`(首次失败脱敏 + 结构化类型) ‖ Phase 6：`c1f73d0`(详情页查询路径) ← `41eed22`(文档+措辞) ← `d68e7e1`(表单三输入) ← `a2e6e4b`(HTTP 白名单，**subject 写错**) ← `f9cd188`(额度窗口上屏) ← `7168563`(文案分支) ← `b12aeb2`(provider 注册) ← `64ff288`(BRIDGE_TOKEN 凭据链) ← `60598f4`(解析器) ‖ Phase 5：`2a295ca`(探测) ← `c72a2ac`(脱敏) ← `9cf3125`(localhost API) ← `f8f2d57`(缓存/失败不清空) ← `9ba17d5`(解析) ← `5c9019c`(发现+客户端) ← `cf0977c`(忽略产物) ← `3e140f0`(Go 骨架) ‖ Phase 4：`c865bf1`(H5) ← `be229c3`(H1-H4/H7) ← `16c1a9e`(prune) ← `23ed717`(保留判定) ← `8b8ba78`(区间读取+历史列表) ← `a57e987`(P4 计划) ‖ Phase 3 审查：`03d9b63` ← `b1f1613` ← `366e71f` ← `6b2268e` ← `7ee7ba3` ← `091095e` ‖ Phase 3 六步：`46f6579` ← `c468d11` ← `b40e03c` ← `dc41a06` ← `46cf40e` ← `8dc162f` ← `a742eb8`(R3 代次) ← `df85cbc` ← … ← `a1f62b3`(Phase2) ← `4ed7df1`(Phase1) ← `89dec91`(Phase0) ← `a72dff8`(上游基线) |
| 测试 | SUITES=50 TESTS=517 FAILURES=0 ERRORS=0 SKIPPED=0（2026-10-04 09:25 复跑；host-JVM，JUnit 4.13.2；演变 273→284→304→329→331→344→349→407→411→428→487→512→516→517。最后 +1 是 `StorageWriteReportTest`：全仓 insert 结果不许被丢弃的源码 pin，写它时我自己的正则漏了 3 处（`String.matches()` 是双端锚定的，模式结尾停在 `\(` 就不吃带参数的行），是这条测试的最小计数守卫把「扫描自己坏掉」和「代码干净」区分开来而暴露的。最后 +4 是复审 P1/P2 补的 `PairingStoreTest`（保存失败回滚两条方向：`aBridgeRowThatCouldNotBeSavedLeavesNoAccountBehind`、`aRebindWhoseRowCouldNotBeSavedTouchesNothing`）与 `PairingWiringTest`（写入结果被报出、`rebind` 可达且真传账户 id）。**配对界面已有设备证据**：`assert-bridge-pair.ps1` run 19 57 项 0 失败，粘贴/深链、手输地址 + 短码 + 尾号人工确认门、撤销后原地「重新配对」恢复同一账户、重启后凭同一配对继续读数，都是跑出来的而不是推断的 |
| Go 门禁 | **109 PASS 行（含 2 条子测试）/ 0 失败 / 2 跳过**（`cd bridge && gofmt -l ./cmd ./internal && go vet ./cmd/... ./internal/... && go test -count=1 -v ./...`，Go 1.27.0；8 包：bridge / codex / discover / filestore / identity / pairing / redact / server。演变 55→66（Phase 5 复审）→98（Phase 7 步骤 1-4）→108（Phase 7 复审 P2 修复）→109（综合复审 §2.5）。两条跳过是 `TestKeyFileIsOwnerOnly` 与 `TestModeIsRequestedOnTheTemporary`——Windows 不携带 POSIX 权限位，测试自己 skip 并说明原因）。`gofmt -l .` 会把忽略目录 `bin/phase5-review-probe` 也列出来——那是复审者留下的探针，不在门禁范围内。Bridge 是**独立 Go module**、不在 Gradle 里，所以这是第二套门禁，Android 门禁替代不了它 |
| Lint | 0 error / **41 warning**（2026-10-04 09:25；比早上那轮记录的 40 多 1。**这 1 条我没有归因**：新代码全在 `storage/` 的纯 Java 里，lint 报告里没有一条指向 `storage/` 的文件，所以不是 `b1dc773` 带来的；差额落在哪个 id 上还没有逐项对过，别把它当成已解释；逐项分布见下）（按 id：`LockedOrientationActivity`×6、`DiscouragedApi`×6、`HardcodedText`×4、`SetTextI18n`×4、`CustomX509TrustManager`×2、`TrustAllX509TrustManager`×2、`SmallSp`×2、`UselessParent`×2、`UnusedResources`×3，其余各 1）。Phase 7 手机半边带来 10 条：`bridge/` 里 5 条（`BadHostnameVerifier`×1 + `TrustAllX509TrustManager`×2 + `CustomX509TrustManager`×2）全部指向**刻意为之**的探针通道与钉证 TrustManager，界面 5 条与既有屏幕同族——按 `UnusedAttribute`（`networkSecurityConfig` 需 API 24 而 minSdk 23）的同一立场**都不压制**。`AndroidGradlePluginVersion` 要联网探测新版本，`--offline` 下不出，这是基线在 30/35/40 之间跳动时的一项口径差 |
| 工作区 | 分支 `codex/project-optimizations` 包含本轮优化（Wrapper、CI/Lint、刷新策略、配对凭据迁移、职责拆分与文档同步）。本轮未运行本地构建、单测或 Lint；推送后以 GitHub Actions 结果为准。`.graphflow-cache/`、`graphflow-out/` 是工具缓存。历史复审文档按原样保留。 |
| 设备 | 模拟器 **Pixel_7_API_37**（Android 17 / SDK 37 预览镜像，`hw.gpu.enabled=no` → 冷启很慢且会拖垮 adb 自动化；AVD 名只能问控制台 `adb emu avd name`，`getprop` 里那几个属性是空的，验收日志的抬头因此现在打 `avd=Pixel_7_API_37`）。⚠️ **2026-10-03 出过一次事故**：为了重启设备误用 `-wipe-data`，把这台机器上仅有的两个真实 DeepSeek Key 抹掉了（用户随后重录，桌面 Widget 也一并没了——依赖 Widget 的 C 系列行要么先 `place-widget.ps1` 重放要么如实记 SKIP）。此后设备侧一律：**只拷不写**（`run-as cat` db + `-wal`/`-shm`）、绝不用 `adb input text` 打密钥（adbd 会把命令行原样记进 logcat）、绝不登出真 Codex。模拟器恢复快照时可能带回一个**系统 ANR 对话框**，它是系统窗口、app 窗口的 `uiautomator dump` 看不见（当时 dump 只有 4470 字节、焦点写着 `Application Not Responding: com.aiusage.monitor`），`adb reboot` 40 秒即可清掉——金丝雀失败先查焦点，别当成注入坏了。这台机器内存吃紧：模拟器在跑时 Gradle 默认 `-Xmx2048m` 起不来（`页面文件太小` / DOS 1455），要 `-Dorg.gradle.jvmargs=-Xmx900m -Dorg.gradle.workers.max=1`；且 Gradle 必须带 `GRADLE_USER_HOME=D:\Android\.gradle`（否则 `--offline` 找不到 `lint-gradle` 直接 BUILD FAILED，看着像代码坏了） |
| 待办 | ① 扫码通道等 **D1**（第三方解码库 vs 手写 vs 不做）——协议与三条免依赖通道都在，扫码只是第四个 `PairingPayloadSource`；② 本分支为配对账户实现了凭据迁移，paired payload 只存设备令牌，手动调试账户仍在凭据中保存 URL；③ UI 事实（不是缺陷）：1080×2400 上「手输地址（调试通道）」要往下滚一下才点得到 `保存`。~~9 处 `insert*` 返回值被丢弃~~ 已在 `b1dc773` 全部改成「-1 就抛并点名表与键」，配 `StorageWriteReportTest` 源码 pin。Phase 3/4/5/6 遗留见 §6，Phase 7 全过程见 `docs/PHASE-7-PLAN.md` §10 |

## 3. 架构与红线

- Android 原生 Java，**零第三方生产依赖**（`HttpURLConnection`、`RemoteViews`、`AlarmManager`、程序化 UI 无布局 XML）。测试依赖仅 JUnit 4.13.2 + `org.json:json:20240303`。
- Gradle 9.1.0 + AGP 9.0.1，单模块 `:app`；Gradle Wrapper 使用官方 HTTPS 分发地址并校验 SHA-256。
- 包名/应用 ID `com.aiusage.monitor`，minSdk 23 / targetSdk 35 / compileSdk 36。
- 分层（`app/src/main/java/com/aiusage/monitor/`）：`model/ auth/ provider/ util/ storage/ usage/ account/ refresh/ widget/ ui/`。
- **Spec §45 红线：`account/` 不得依赖 `usage/`**。单一刷新链：`Account → ProviderRegistry → CredentialStore → AuthAdapter → Provider → UsageResult → UsageRepository → UsageSnapshot → WidgetUpdateManager`；App/Widget/后台都只读 `UsageRepository`。
- `ProviderRegistry` 是 JVM 单例且 `registerBuiltIns()` 不幂等——`AppGraph.registerBuiltIns()` 用 `find(id)==null` 守卫；测试里共享 provider 要 `reset()` + 条件注册。
- Provider 层契约（零回归保留）：UA `"DeepSeekBalance/1.0 Android"`、超时 12000/15000ms、401/403/429 错误映射、`REFRESH_INTERVALS/LABELS`、`DEFAULT_REFRESH_MS=15000L`。刷新设置按 Provider 保存；Bridge 前台刷新最短 5 分钟以匹配缓存，后台默认 30 分钟、最短 15 分钟，并支持仅手动刷新。Phase 2 验收 6 用 provider SHA-256 锁过（`a081ab3a…`）。
- SQLite 表（`storage/Database.java`，**`VERSION = 3`**）：`accounts / credentials / usage_snapshots / widget_config / widget_slots / daily_usage / app_meta / bridges`。`bridges` 列 = `(id, name, base_url, fingerprint, added_at, last_seen)`，一台电脑一行、按 **Bridge ID** 认，地址只是可变属性；`accounts.bridge_id` 指过去（空 = 没配对）。v2→v3 是单事务迁移、不 `INSERT`，且**只能在设备上验**（`android.database.sqlite` 在宿主 JVM 是桩）：`tools/smoke/assert-bridge-migration.ps1` 做过「备份→降到 v2→升回 v3→逐表比对→字节级还原」18 项。`daily_usage` 列 = `(account_id, day, last_balance, total_usage, updated_at)`，PK `(account_id, day)`，`updated_at INTEGER NOT NULL`（注入测试数据时必须给值）。
- **Widget Slot 模型（Phase 3）**：`widget_slots(widget_id, slot_index, account_id, metric_ids)`，PK `(widget_id, slot_index)` + `idx_widget_slots_account`。`widget_config.account_id` 是**遗留列**（minSdk 23 上 SQLite 不能 DROP COLUMN），v2 起无人读写，唯一事实是 slot 行。`metric_ids` 用 `|` 分隔，编解码只在 `widget/WidgetMetricId` 一处；迁移 SQL 里的字面量由 `WidgetSlotModelTest` 与 `encode(defaults())` 对钉。
- Widget 展示口径：`widget/WidgetSlotResolver`（纯 JVM、可测）产出 `WidgetSlotView`，`WidgetRenderer` 只收字符串；状态文案统一走 `util/StatusWords`，新鲜度走 `util/Freshness`，两者与账户列表共用，**不要再各写一份**。4×2 静态 3 行（`WidgetRenderer.MAX_SLOTS`），未使用的行 `View.GONE`。
- 凭据：Android Keystore AES-GCM（`protection=keystore-aes-gcm`）或 `degraded-local`；`SqliteCredentialStore implements AccountManager.DegradedAware`（R2 修复）。DB 中永不出现明文 `sk-`。
- 今日用量口径：DeepSeek 无个人 Key 用量汇总端点，实现为**余额下降差额累计的估算值**（上升计 0、跨日重算、应用未运行期间不计）——README 已写明，别再当成 bug 修。
- **历史与保留（Phase 4）**：`UsageRepository.history(accountId, fromInclusive, toExclusive, limit)` 是**左闭右开**窗口（与 `daily_usage` 的日历日切分同一边界），失败尝试也在内；`latest()` 才是「只取成功」。保留判定在 `usage/SnapshotRetention`（纯 JVM 可测），`prune()` 只执行它给出的 id 列表（分块 500，同事务）。**永不删**：14 天内、每账户最后一次成功、每账户最新一行、当日全部。触发点只有两处：后台闹钟刷新后、Widget 手动刷新后 —— 打开 App 前台不清理。
- **Bridge（Phase 5，Windows 侧）**：`bridge/` 是独立 Go module（`aiusage.local/bridge`），零第三方依赖。链路 `discover → codex app-server（stdio JSON-RPC）→ account/rateLimits/read → 解析 → 5 分钟缓存 → 只绑 127.0.0.1 的 /v1/*`。**Bridge 从不读 `auth.json`**（不接触令牌，Spec L804-809 因此是结构性满足，不是「我们小心不打印」）；`--host` 非 loopback 直接拒绝启动。失败态 `CODEX_NOT_FOUND / METHOD_UNAVAILABLE / AUTH_REQUIRED / TIMEOUT` 之外还有第五类 `CODEX_UNKNOWN`——归不了名的错误不许冒充归得了名的。出站文本（上游错误串、`limitName`）先过 `internal/redact` 再落盘。
- `UsageRepository` 每个方法都必须有生产调用方，由 `UsageRepositoryContractTest` 静态守住（它找不到源码目录或没解析出方法就**主动失败**，防止空扫描假绿）。加新接口方法前先想清楚谁来调。

## 4. 文档地图

| 文件 | 作用 |
|---|---|
| `AI-Usage-Monitor-Development-Plan.md` | 权威规范（2504 行），一切以它为准 |
| `docs/PHASE-0-2-PLAN.md` | 已批准的 Phase 0–2 计划；L223-227 明确 Phase 3+ out of scope |
| `docs/PHASE-3-PLAN.md` | 已批准并**已实施完**的 Phase 3 计划；§10 是实施记录（提交、门禁、A1-A9 结果、已知限制） |
| `docs/PHASE-4-PLAN.md` | 已批准并**已实施完**的 Phase 4 计划；§10 是实施记录（H1-H8 结果、H6/H7 为何不做成断言、已知限制） |
| `docs/PHASE-5-PLAN.md` | 已批准并**已实施完**的 Phase 5 计划；§10 是实施记录（提交、门禁、B1-B9、**B7/B8 为何不脚本化**、两个时间戳为何同值、已知限制） |
| `docs/PHASE-6-PLAN.md` | 已批准并**已实施完**的 Phase 6 计划；§10 是实施记录（提交、门禁、C1-C8 + A4/A6/C9 逐条证据、**断言可失败性的五次证伪**、已知限制） |
| `docs/PHASE-7-PLAN.md` | **Phase 7 计划**：§0 三条实测前置（框架无 QR 解码器 / 明文白名单是静态 XML / 证书没有 LAN SAN），A1-A10 决策（**A10 = 逐连接 `PinnedHostnameVerifier`，禁止全局默认**），§5 门禁与 P-1…P-11 矩阵，§8 待用户拍板 D1-D4，**§10 是实施记录 + 复审处置表（四项 P2 已修、P1 写成 Android 动工前置）** |
| `docs/PHASE-7-REVIEW.md` | **另一个 Agent 写的 Phase 7 Bridge 半边复审**：1 项 P1（Android 主机名校验会拒 LAN 证书）+ 4 项 P2。逐条复现与处置结果记在 PHASE-7-PLAN §10，别把这份文件当验收文档 |
| `docs/PHASE-0-7-REVIEW.md` | **另一个 Agent 写的跨阶段综合复审**（基线 `bb41d26`）：Phase 0–2 批量刷新用陈旧对象、Phase 3 无 Widget 仍每日刷新、Phase 4 保留并列决胜不确定、Phase 4×6 历史读数形状、Phase 7 Bridge 拒绝标记次序，共 5 项。处置表与本轮门禁数字在 `docs/REVIEW-AND-NEXT-STEPS.md` 末节 |
| `docs/PHASE-5-BRIDGE-FEASIBILITY.md` | **Phase 5 前置调研（结论已被计划采纳）**：本机真跑 `codex app-server` 证明 Spec 要的 `5H / Weekly / Reset` 三个数字一次调用就能取到，且全程不接触 Codex 令牌；§4 三方案取舍、§7 风险与未测项、**§9 六个待用户拍板的决策** |
| `docs/REVIEW-AND-NEXT-STEPS.md` | 审查账本：R1–R4 + 复查 5 项 + 第三方复审 3 项全部闭环；L151「完成修复并复查后，另行提交 Phase 3 的实施计划」是下一步的出处 |
| `README.md` | 已对齐现实（今日用量口径、旧明文 Key 不迁移的取舍） |
| `docs/HANDOFF.md` | 本文件 |

## 5. 已完成里程碑与验收证据

- **Phase 0**（`89dec91`）：Gradle 迁移，smoke-phase0.ps1 13 PASS。
- **Phase 1**（`4ed7df1`，84 files/9839+/620-）：分层重写、零可见 UI 变化（像素级对照）、加密迁移、测试体系建立。8 项验收全过。
- **Phase 2**（`a1f62b3`…`959d69f`）：`AccountListActivity` 成为 launcher + `AccountEditActivity` + widget 按 `accountId` 绑定。验收：①②③ 双真实 Key 独立工作 16/16 PASS；④ 冷/暖双跑 8/8 tap 命中；⑤ 10/10；⑥ provider SHA 不变。
- **R1–R4 审查修复**（`a6ed074` + `d83d055`）：
  - R1 今日累计归零（写侧查询缺 day 条件）→ 修 `SqliteUsageRepository.recordDailyUsage`；R1 A/B 对照实验双向证明。
  - R2 降级提示永不显示（少 implements `DegradedAware`）→ 修。
  - R3 删除竞态孤儿写入 → 最终形态 = 落库前 `isGone(accountId)` 存在性检查（用户批准的 epoch revision 方案被**变异测试证伪**后删除——epoch 恒 false 时 6 用例仍全绿 ⇒ 无贡献；这是经用户知情的一处偏离）。
  - R4 失败后余额清空 → `AccountView`（lastAttempt + lastSuccess 双行，同毫秒 `>=` 判保留数据），列表显示 `¥24.94 · API Key 无效或已失效`。
- **复查 5 项修复**（`601bcac` + `b2fb412` + `5f2f487`）：
  - P2-1 stale 判定收进 `AccountRefreshManager.AccountView.displayStatus(intervalMs, now)`，列表 `case STALE` 恢复可达，与 widget 口径一致。
  - P2-2 `AccountManager.writeMonitor()` 门闩：`delete()` 全程（凭据→历史→账户行三步跨两 store）与刷新写入段互斥；网络调用留锁外。测试 `aDeleteWaitingOnTheWriteMonitorCannotSlipIntoTheWriteSection`。
  - P3-1 `MainActivity` 对 abandoned outcome 提前 return（否则 `showError(null)` 把详情页刷成「查询失败」并清空余额）。
  - P3-2 测试类注释与实现对齐。
  - P3-3 `tools/smoke/assert-daily-usage.ps1` 固化 R1 设备回归，10/10 PASS。
  - **第三方复审处置**（`f0e9319` + `0379612`）：
    - R3 失败分支竞态（openCredential / registry.require catch 在锁外写失败快照）→ 两处包进 writeMonitor 同锁；新增 3 用例 + 变异验证（isGone 恒 false → 删除类用例变红）。**凭据代次窗口（在途请求携旧 Key 落库）仍未关闭，属已知限制**。
    - R5 → `tools/smoke/assert-daily-usage.ps1` 重写为备份-恢复模式：拉库即全库备份，try 包注入/刷新/断言，finally 推回备份 + 删 -wal/-shm 侧车 + 逐行恢复断言；跑后独立拉库复检零残留。
    - 设备历史恢复：2026-09-30 假行删除、2026-10-01 total 还原 0.00（证据 tools/smoke/out/r5-cleanup-before.db vs 基线 verify-b4.db）。
- **Phase 3（`46cf40e`…`46f6579` + 步骤 6 文档）**：Widget 通用化为账户 Slot。设备验收 A1-A9 全过（`assert-widget-slots.ps1` 现 32 checks / 0 failed，含可逆的 v1→v2 升级彩排），细节与已知限制只写在 `docs/PHASE-3-PLAN.md` §10 一处。Phase 3 另经审查一轮，发现与处置见 `docs/REVIEW-AND-NEXT-STEPS.md` R6-R17。
- **Phase 4（`8b8ba78`…`c865bf1`）**：历史区间读取 + 详情页「最近读数」+ 14 天保留策略。设备验收 `assert-history.ps1` **17 checks / 0 failed**；H4 用 `EXPLAIN QUERY PLAN` 实测决定**不加**新索引；H6（删真实账户）有意不脚本化，H7（体积）只报数不断言。详见 `docs/PHASE-4-PLAN.md` §10。
- **Phase 6（`60598f4`…`c1f73d0` + 步骤 6 文档）**：手机经只绑回环的 Bridge 读 Codex 额度。设备验收 `assert-bridge-phone.ps1` **57 checks / 0 failed**：C2 与本机 `/v1/accounts/codex/usage` 逐项一致（本轮真值 5 小时 100% / 7 天 71%）；C3 杀掉 Bridge 后库里 success 行仍带 `quotaWindows`、列表写「Bridge 未连接 · 最后成功数据」；C4 与 C3 在详情文案 / 库 status / 列表文案三处都不同且全程不出现「API Key」；C8 同一台 stand-in、同一端口、同一个正确令牌，`10.0.2.2` 通、本机 LAN 地址被平台原话拒绝「Cleartext HTTP traffic to … not permitted」；A4 用「只对正确的 `Authorization: Bearer` 返回 200」的 stand-in 证明令牌确实只进请求头。步骤 6a（`c1f73d0`）是**计划里没有的一步**：详情页原本只认 API Key 输入框，Codex 账户点查询什么都不发生（库里 0 行）——计划只覆盖了表单的凭据字段。
- **Phase 5（`3e140f0`…`2a295ca` + 步骤 7 文档）**：Windows AI Usage Bridge MVP。本机验收 `assert-bridge.ps1` **27 checks / 0 failed**（复审后扩到 **38 checks / 0 failed**，见 §6 第 5 条与 `docs/PHASE-5-PLAN.md` §11），对**真实** Codex（codex-cli 0.121.0）真读到 5 小时/周窗口与两个重置时间；B5 用本机 LAN 地址实测连不上，证明 loopback 守卫不是纸面规则；B7/B8 有意不脚本化并写明原因。Android 侧**零改动**，每步复跑 26 suites / 349 tests 以证明没被牵连。

## 6. 下一步（按序）

0. **R3 凭据代次窗口已关闭**（`a742eb8`）：`AccountManager` 每账户单调代次，刷新在读取凭据**之前**捕获、提交时在删除锁内比对，成功与失败快照都比对；`refreshWithAuthContext` 发请求前先确认账户仍在。11 例交错测试 + 双向变异验证。**Phase 3 审查又发现该修复自身的漏洞**（`7ee7ba3` 修）：三个凭据写方法当时不在 `writeMonitor` 内，代次可在「检查通过」与「落库」之间被推进 —— 现在凭据变更也在同一锁内。**教训：加锁的比对两端都要锁住，否则等于没锁。**
1. **Phase 3 遗留项**（都不是缺陷，是明确延后，见 `docs/PHASE-3-PLAN.md` §10）：① 每 Slot 选指标（`metric_ids` 已按 Slot 存好，只差配置界面第三步）；② 按下刷新到响应之间的 `REFRESHING` 态（需要先有"在途账户"记录）；③ 4×2 静态 3 行上限；④ 2×4 / 4×4 尺寸。
2. **Phase 4 遗留项**（明确延后，见 `docs/PHASE-4-PLAN.md` §10）：① 保留天数不可配置；② 清理只在后台闹钟与 Widget 手动刷新后跑，前台打开不清理；③ 无图表（Spec 把曲线放 V2）；④ `countSnapshots()` 未实现（没有消费者就不加）。
3. **Phase 5 已交付**（`docs/PHASE-5-PLAN.md` §10）。遗留（明确延后，非缺陷）：① 只服务 codex 一个 provider；② 无配对 / 无局域网发现 / 手机不接入（Phase 6-8）；③ 托盘与开机自启未做；④ `dataTimestamp` 与 `sourceTimestamp` 目前同值——Codex 载荷里没有源端时间戳；⑤ B7/B8 只有单测覆盖，真实的「方法不存在 / 令牌过期」形状仍未观测。
4. **Phase 6 已交付**（`docs/PHASE-6-PLAN.md` §10）。遗留（明确延后，非缺陷）：① Bridge 仍只绑回环、`/v1/*` **无鉴权**——手机能连是因为 `10.0.2.2` 就是宿主 loopback，真机 LAN 必须与 token 校验同批（D1 选 ①）；② 额度不进 Widget（要新指标 id + `metric_ids` 迁移 + 每 Slot 选择器，D4）；③ 失败快照 `providerId` 是空串（今天无消费方读它，登记不掩盖）；④ 详情页失败时读数区被错误文本替换，「保留上次成功」成立的位置是库与列表而非详情页；⑤ 提交 `a2e6e4b` 的 subject 写错（重复了步骤 1 的措辞），不改写历史。
5. **Phase 5 复审四项已修完并关闭**（`docs/PHASE-5-PLAN.md` §11：复现原文、四个提交、11 次变异、门禁与两份验收数字）。要复现的四项都在改代码**之前**跑过一遍：`raw_secret_present=true`、`error_is_unknown=true`、`after_late_failure has_data=false`，第四项是控制流事实（`CodexFetcher.Fetch` 只在成功分支挂 `RefusedRequests()`）。**别把这份复审当成已完成的验收文档**——它记录的是问题，处置与实测在 PHASE-5-PLAN §11。
6. **Phase 7 手机半边步骤 5-10 已交付**（`docs/PHASE-7-PLAN.md` §10）；设备验收记录为 59 checks / 0 failures / 0 skipped。当前剩余的是明确范围决策：① 二维码通道 D1；② 是否将 Bridge 监听扩展到 LAN（D2），当前仍默认 loopback。
7. **跨阶段综合复审（`docs/PHASE-0-7-REVIEW.md`）五项已关闭**：`abcd8a7`（§2.5 拒绝标记）← `1c3f06e`（§2.3 保留决胜）← `c52b6e1`（§2.1 陈旧账户）← `a0bf9cf`（§2.2 零点只重绘）← `cbae8fd`（§2.4 历史读数）。每项都是先写会红的复现用例再改代码；处置表、变异清单与本轮门禁数字在 `docs/REVIEW-AND-NEXT-STEPS.md` 末节。Phase 7 手机半边已经完成设备验收；第 6 条剩余项均为 D1/D2 范围决策。
8. 实施期照旧规矩：每步独立提交 + 门禁数字 + 设备证据；先计划后动工；先给文件与验证案例再改代码；**验收脚本里不许出现永不会失败的断言**——Phase 3/4/5/6 四轮都栽过，Phase 6 这轮又当场抓到两条（见 §8 的「额度窗口」与 `-Serial` 两条）。

## 7. 用户决策史（不要重新发明）

- **D1–D9**（已批准）：Gradle 单模块；SQLite；Keystore AES-GCM+迁移+degraded 标记；包名 `com.aiusage.monitor`（旧应用不覆盖、不做同包名导入——上游真包名是 `com.deepseek.balance`，Android 沙箱隔离，旧数据读不到是**有意取舍**）；Phase 1 零可见变化 / Phase 2 新 launcher；widget 绑 accountId 最小化；host-JVM 测试 + adb smoke；真实 Key 可用；git 每阶段一提交。
- **R1–R4 四项**：余额+错误并列显示；余额上升计 0；R3 方案已如上偏离（变异测试证伪）；先 R2+R4 后 R1+R3 分开提交。
- **Phase 3 决策 D1–D5（2026-10-02 批准）**：D1 指标清单放 `ProviderCapabilities`（接受重定 provider 哈希基线，`DeepSeekProvider.java a081ab3a→554f9893`、`ProviderCapabilities.java 0b292246→cd7ea02b`）；D2 4×2 = **3 个 Slot**；D3 接受 Widget 可见变化（账户名/更新时间/状态/刷新按钮）；D4 **先关闭 R3 代次窗口再做 Phase 3**；D5 2×4 不纳入本阶段。
- **Phase 5 决策（2026-10-02 用户答复）**：D1 **Go 单文件 exe**，并**单独批准安装 Go 工具链**这一本机改动；D2 无余额账户**先只落库不上屏**；D4 Phase 5 **只到 localhost，不碰手机**；D5 **按方法探测、不钉 Codex 版本**。计划 §8 的默认值一并获批准：N1 端口 38411、N2 状态文件 `%LOCALAPPDATA%\AIUsageBridge\state.json`、N3 子进程超时 8s、N4 托盘/自启不做。
- **Phase 6 决策（2026-10-02 用户答复）**：批准按 §7 七步动工；D1 **只到模拟器**（Bridge 保持只绑 127.0.0.1，手机靠 `10.0.2.2` 就是宿主回环）；D3 额度窗口**上详情页**；D4 额度**本阶段不进 Widget**。步骤 7（LAN + 令牌校验）随 D1 一并作废。
- **Phase 6 执行期用户追加批准**：详情页查询路径的三段修复 + 验收脚本自身的缺陷修复（「嗯，按你的建议执行」）——这一段后来变成提交 `c1f73d0`。
- 汇报偏好：**记录交付事实，不记录评分**；证据链（命令 + 输出）说话，不许空口完成。

## 8. 环境事实与坑清单（每一滴都是踩出来的）

### 构建与测试
- 每条 pwsh **先 `. .\tools\env.ps1`**（JAVA_HOME、PATH、adb）。
- **宿主 Codex 会间歇性连不上 `chatgpt.com`**：实测 `503 failed to fetch codex rate limits: error sending request for url (https://chatgpt.com/backend-api/wham/usage)`，约 1 分钟内自愈，两次撞到（一次在 Phase 6 手机验收，一次在 Bridge 验收）。凡是需要真读数的断言都要重试，且**拿不到读数时明确写「不计分」**——写成产品失败是把环境当代码，写成静默通过是第三次犯「永不会失败的断言」。
- **Windows 上并发 `os.Rename` 覆盖同一目标会被拒绝**（`Access is denied`）。原子写只能保证「不会半份」，不能保证「每次都落」；断言要落在「落盘的那一份完整自洽 + 不留临时文件」上（`bridge/internal/bridge/concurrency_test.go` 就是这么写的）。
- **本 agent 的 Git Bash 里没有 `pwsh`，也没有 JAVA_HOME**，上面那条 dot-source 在这里直接失败。可用形式：`export JAVA_HOME=D:/Android/jdk-17 ANDROID_HOME=D:/Android/sdk GRADLE_USER_HOME=D:/Android/.gradle` 然后 `cmd.exe //c gradlew.bat …`。
- **门禁数字要从 XML 里数**：`gradlew -q` 不打印计数。JUnit 看 `app/build/test-results/testDebugUnitTest/*.xml`，Lint 看 `app/build/reports/lint-results-debug.xml` 的 `severity=`。`cmd … | tail` 还会吞掉真实退出码，要看 `${PIPESTATUS[0]}`。
- **Git Bash 会吃掉 `-File tools\smoke\x.ps1` 里的反斜杠**（变成 `toolssmokex.ps1`）。给 powershell.exe 传脚本路径一律用正斜杠。
- **`go build ./...` 会把可执行文件拉到 go.mod 旁边**（不像多数人对 `go build` 会丢弃结果的直觉）。步骤 0 因此把 2.4 MB 的 exe 提交进了仓库，`cf0977c` 修的，历史里那份产物未改写。要产出就用 `go build -o bin/…`，`.gitignore` 已盖住 `bridge/bin/` 与 `bridge/*.exe`。
- **Go 不能直接 spawn `.cmd`/`.bat`**：Windows 上 `where codex` 只给到 npm 的 `codex` 与 `codex.cmd` 两个 shim，原生二进制在 `%APPDATA%\Roaming\npm\node_modules\@openai\codex\node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\codex\codex.exe`。发现逻辑必须 glob，不能信 PATH。
- **PowerShell 5.1 的非 2xx 响应体在 `$_.ErrorDetails.Message` 上**：`Invoke-WebRequest` 早已读走响应流，再 `GetResponseStream()` 只会拿到空串——于是「503 + 类名」会**因为脚本自己的原因**失败（assert-bridge 第一版就栽在这里，且它报的是 FAIL 而不是假绿）。
- **winget 默认先试 msstore 源**，本机该源连不上（0x80072efd）；要加 `--source winget`。装完 Go 不进当前会话 PATH，位置是 `C:\Program Files\Go\bin`。
- 测试必须 `--rerun-tasks`，否则 Gradle UP-TO-DATE、XML 结果陈旧。
- android.jar 的 `org.json.*` 是 `Stub!` → 测试 classpath 必须真 `org.json:json:20240303`。
- 提交信息写 `commitmsg.txt` + `git commit -q -F commitmsg.txt`（here-string 直传 `-m` 会被引号炸掉；该文件已被 `.gitignore:38` 覆盖）。

### PowerShell（5.1，大量坑）
- `tools/**/*.ps1` 必须 **UTF-8 with BOM**，否则中文按 GBK 读成乱码；**edit 工具每次都会丢 BOM，改完必须补**：`[IO.File]::WriteAllText($p,$t,[Text.Encoding]::UTF8)` 写回后校验 `EF BB BF`。这是本会话踩过三次的真实事故（脚本内中文 regex 因此静默失配；write 工具同样丢 BOM）。
- 双引号内 `"$pkg/..."` 会被当属性访问/作用域变量静默清空 → 用 `${pkg}`。
- 单元素数组被自动解包 → 调用侧 `@(Invoke-Sql ...)` 包裹。
- `cmd 2>&1 | Select-Object` 会把 stderr 包成 NativeCommandError 强制 exit 1 → `cmd /c "... > log 2>&1"` 再 `Get-Content`。
- `Get-Content` GBK 乱码 → `[IO.File]::ReadAllText($path,[Text.Encoding]::UTF8)`。
- `SetNamedSecurityInfoW failed (Win32 5)` → 该命令需要 danger-full-access 路径执行。
- `sqlite3.exe` 参数形式会 ParserError → `& $sqlite $db "..."` 或管道 stdin。
- **`(int)$m.Groups[1].Value + (int)$m.Groups[3].Value` 这种内联强转在 PS 5.1 直接解析失败** → 先分行赋值再用。
- 函数返回单元素数组在调用侧仍会被解包：`$row = Query-Db ...; $row[0]` 拿到的是**首字符**（`"0"` 恰好让计数断言假通过，`"10"` 就红）。**每个调用点都要 `@(...)` 包裹**；同理 `$arr -ne $null` 是过滤不是比较，绑到 `[bool]` 参数会抛 `ParameterArgumentTransformationError` → 写 `$null -ne $arr`。
- **会被赋值接收的函数里不能用 `Write-Output` 记日志**：`$x = Assert-HintWorks ...` 会把函数里所有 `Check`/`Note` 行一起吞进 `$x`，PASS/FAIL 不再出现在日志里，而失败计数照加——本轮实测 53 条断言只有 37 条打印出来，其中 4 条红被完全藏住。日志类输出改 `Write-Host`（bash 层的 `>` 仍然收得到），或把结果放 `$script:` 变量里而不是 return。
- **scriptblock 当 .NET 委托用会在别的线程上炸**：`[Net.ServicePointManager]::ServerCertificateValidationCallback = { ... }` 由 TLS 线程调用，那里没有 PowerShell runspace → 握手失败「基础连接已经关闭: 接收时发生错误」，异常里才是 `Runspace.DefaultRunspace` 缺失。正解：`Add-Type` 一个静态方法，再 `[Delegate]::CreateDelegate([Net.ServicePointManager].GetProperty("ServerCertificateValidationCallback").PropertyType, $mi)`（委托类型要从属性拿，`[System.Net.ServerCertificateValidationCallback]` 在 PS 5.1 里解析不出来）。
- **`Invoke-WebRequest` 复用连接会跳过证书回调**：同一 host:port 上第一条用对指纹的连接建立会话后，第二条故意用错指纹的请求直接骑过去拿到 200 → 「钉指纹被拒」这条断言假通过。加 `-DisableKeepAlive`。
- **`Start-Process -ArgumentList` 不给带空格的参数加引号**：`--data-dir "C:\...\lan bind"` 变成两个参数，Go 的 flag 包在第一个裸词后停止解析，于是**后面的 `--host` 被忽略**，LAN 绑定静默降级成回环绑定。传参前自己按空格加引号。
- **`Start-Process -PassThru` 不加 `-Wait` 时 `$p.ExitCode` 是 `$null`**，而 `$null -ne 0` 为真 → 「拒绝启动」类断言永远不会红。用 `-Wait`，并在拿到 `$null` 时直接 throw。
- **`& $exe args 2>&1 | Out-String` 会把原生 stderr 重排**：长行被折行插空格，`(--host 1.2.3.4)` 变成 `(- -host …)`，于是断言文本自己失配。短命令一律 `Start-Process -Wait -RedirectStandardOutput/-RedirectStandardError` 到文件再读。
- **被进程占用的重定向日志读不了**：`[IO.File]::ReadAllText` 在 Bridge 还开着 stdout 时抛「另一个进程正在使用此文件」→ 轮询看起来像 Bridge 没起来。用 `[IO.File]::Open(path, Open, Read, [IO.FileShare]::ReadWrite)`。

### adb / 设备
- PNG 截图：`adb exec-out screencap -p` 必须经 `cmd /c` 落盘，PowerShell 管道会损坏 PNG。
- **DB 注入路径**（run-as 无 sqlite3）：host 拉库 → host sqlite3 改 → `adb push /sdcard/` → `adb shell "cat /sdcard/x.db | run-as com.aiusage.monitor sh -c 'cat > databases/ai_usage_monitor.db'"` → 注入前必须 `am force-stop`。第一次 `run-as cp /sdcard` Permission denied 是假阳性，cat 管道才是正道。
- 拉库后**必须检查文件大小**（<10KB 即假文件：env.ps1 没加载时 'adb not recognized' 文本会被写进输出文件）。
- `input text` 单次超过 ~35 字符丢字符 → 分段注入（真实事故：整串 35 字符 Key 注入后凭据密文 paylen 60B≠104B）。
- `uiautomator dump` 前先 `adb shell rm -f /sdcard/u.xml` 防陈旧文件；冷启动后 dump 要重试循环。
- **`run-as cat databases/x.db` 只拷主文件，读不到 WAL 里已提交的行**：本轮 C3 因此变红（屏上有额度，库里查不到）。改成 `am force-stop` 后连 `-wal` / `-shm` 一起拉；WAL 帧自带校验和，撕裂的尾帧会被自动忽略。空侧车文件要删掉，别留 0 字节。
- **`uiautomator dump` 会把 hint 印在 `text=` 里**（2026-10-03 在 Pixel_7_API_37 上重测：空的地址框 dump 出来就是 `text="http://10.0.2.2:38411"`，那只是 `setHint`）。所以「框里出现了这段文字」**不能**证明输入成功——只有输入的值与 hint 不同（或断言前先确认过 hint 原值）才算证据。早期那条「按提示语定位会命中说明文字」的教训仍然成立，但结论要反过来记：框仍按 `class="…EditText"` 序号定位，验证则要比对**你输入的具体值**。
- **模拟器的软键盘会吞掉注入的按键**（2026-10-03 实测）：`adb shell input text probe` 打到普通文本框**一个字都不进**（逐字符 + 200ms 也只进 'p'），而同一屏上的密码框 14 个字符全进——带候选词的那条 IME 路径忽略注入事件。该 AVD 有硬键盘（配置里 `qwerty`），所以 `adb shell ime disable <当前 IME>` 之后所有框都能正常收字（`ime enable` + `ime set` 还原）。`assert-bridge-phone.ps1` 现在整轮禁用软键盘、清理时还原，并且只在 `dumpsys input_method` 里 `mDecorViewVisible=true` 时才发 KEYCODE_BACK——没键盘时 BACK 会把 Activity 弹掉。金丝雀断言「injected text reaches a plain-text field」不成立就直接中止整轮，免得 30 条下游 FAIL 掩盖一个环境事实。
- **模拟器会在验收中途挂掉**：2026-10-03 一轮跑到 C1 时 `adb devices` 变空，emulator 日志给 `detected a hanging thread 'QEMU2 CPU0 thread'`；重启后长时间不注册设备。这是 Android 17 预览镜像 + `hw.gpu.enabled=no` 的组合，不是产品问题——**但也不能当成跑过了**：设备没了就写「未跑完」。
- **一句短语可能同时是标题和副标题**：`额度窗口` 既是被断言的区块标题，也是 Codex 详情页副标题「实时读取额度窗口」的一部分，`-like "*额度窗口*"` 因此永真（它把 C8 也判成假红）。断言要挑只有真数据渲染才会出现的形状：`已用 \d+%`。判别力在同一次运行里可见——同一正则在两个账户命中、在被拦下的那个账户必须不命中。
- **adbd 把自己的命令行回显进 logcat**：用 `adb shell input text <secret>` 打进设备的秘密**必然**出现在日志里，那是测试自己的手。断言按进程归属拆开（adbd 若干次、其他进程 0 次），**不许**把 adbd 一行过滤掉了事——那样连真实泄漏也会一起被滤掉。
- **`Invoke-Adb logcat -v brief` 里的 `-v` 被 PowerShell 当成函数自身的参数名吞掉**（实测残余参数是 `logcat|brief|-d|-t|6000`），剩下的 `brief` 被 logcat 读成一个 filter spec，输出格式静默停在 threadtime，于是按 brief 写的正则全部落空。把尾巴整段引号成一个参数：`Invoke-Adb logcat "-v brief -d -t 6000"`。
- **调子脚本别传它没声明的参数**：三个老验收脚本都没有 `-Serial`，`powershell -File x.ps1 -Serial y` 报 `NamedParameterNotFound` 而 `$LASTEXITCODE` 还是上一条命令留下的 0 → C5 当场**假绿**。要么按声明传，要么同时要求子脚本自己打出结论行（本项目是 `RESULT: *PASS*`）。
- **上一轮中止留下的账户会改变屏幕**：四个同名 Codex 账户把「添加账户」挤出可视区（uiautomator 只给可见节点），而按名解析 id 会读到别的账户。查找一律带滚动重试，脚本开头做 pre-clean 并用数据库计数断言它清干净了。
- 模拟器 framebuffer wedge（全黑小 PNG、input 无响应）：杀 `qemu-system-x86_64*` 重启；重启会清数据 → **持久 userdata 已修**（`disk.dataPartition.path` 从 `<temp>` 改为 `userdata-qemu.img`），若再现按此查。
- 模拟器内部时钟是 2026-10-01（宿主已是 10-02）；day 边界以**设备时间**为准，脚本里用 `adb shell date +%Y-%m-%d`。
- **shell 发不了 `APPWIDGET_UPDATE`**：`am broadcast -a android.appwidget.action.APPWIDGET_UPDATE -n <provider>` 报 `SecurityException: ... from unknown caller`，脚本无法从外部强制重绘 Widget。要重绘只能走用户路径：`uiautomator` 定位「刷新」文本 → `input tap`。
- **force-stop 会把应用置为 stopped 态，桌面 Widget 随即变成启动器的灰色占位卡片**（`uiautomator dump` 里根本没有 Widget 文本节点，看着像断言写错）。任何 UI 断言前先 `am start` 一次应用再 HOME。
- 设备现况：两账户都活——DeepSeek `acct_294b8c1eaff5`（真实 Key，指纹 f684d775，¥24.94）、DeepSeekWork `acct_93e6722626d6`（第二把真实 Key，¥24.94）；凭据 `keystore-aes-gcm`；桌面 Widget 6（4×2）**同时绑两账户（slot 0/1）**、Widget 7（2×1）绑 DeepSeekWork，`widget_slots` 为准。**两把真实 Key 都只在设备上，会话中没有明文**——所以 A7 的"账户被删除"验收用注入不存在的 account_id 做，绝不写真 Key 或删除真账户。

### 代码陷阱
- `Account` 没有 `equals` → 测试断言一律用 id（`findEnabledById`），`contains(account)` 是永真假通过。
- `AccountManager.replaceCredential` 存 `toBuilder()` 副本 → 调用后必须重新 `find(id)`。
- 凭据 payload 必须 `CredentialPayload.forApiKey(...)` 编码 JSON，裸串会 INVALID_CREDENTIAL。
- `MainActivity.afterTextChanged` 勾选「记住密钥」时逐字符 `saveKey` → 往输入框打字就是换凭据，脚本里 type+ESC 是真生效不是空转。
- `AppWidgetProvider` 没有 `onConfigure`（javap 核实过），launch contract 靠 `RESULT_CANCELED` ⇒ widget 不添加 ⇒ 无孤儿行。
- 上游基线 manifest 在仓库根 `AndroidManifest.xml`（`git show a72dff8:AndroidManifest.xml`），不在 app/ 下。

## 9. 工具脚本地图（`tools/smoke/`）

| 脚本 | 用途 |
|---|---|
| `env.ps1` | 每个 pwsh 会话第一行 dot-source |
| `smoke-phase0.ps1` | Phase 0 全量冒烟（默认 launcher 已是 AccountListActivity） |
| `assert-account-identity.ps1` | 账户身份/历史保留/加密断言（会选中最后一个 sort_order 的账户，注意 `-cmatch '^DeepSeek'` 防大写 kicker 污染） |
| `assert-last-success.ps1` | 失败保留最后成功数据断言 |
| `assert-two-live-accounts.ps1` | 验收①②③：双真实 Key 独立工作，16 项全 PASS（凭据绑定证据：`accounts.credential_id` 互异且存在） |
| `assert-daily-usage.ps1` | R1 设备回归：注入昨日+今日行 → 真实刷新 → 断言 total 不归零；R5 加固后自带备份-恢复零残留（10/10 PASS） |
| `assert-widget-slots.ps1` | **Phase 3 验收 A1/A1u/A2/A3/A5/A6/A7 + §53 红线 grep**：32 checks；含**可逆的 v1→v2 升级彩排**（降级真实库→应用真跑迁移→逐表恢复比对）；A7 注入不存在的 account_id；推库前后各校验字节数，失败即中止；UI 断言前先拉起应用（见 §8） |
| `assert-history.ps1` | **Phase 4 验收 H1/H2/H3/H4/H5/H7**：17 checks；注入 40 天前行→点刷新→断言过期行消失且「最后成功 + 最新一行」仍在；H4 用 `EXPLAIN QUERY PLAN` 决定是否加索引；H5 先断言配置确实变了再比对历史；全库备份-恢复逐表比对 |
| `assert-bridge.ps1` | **Phase 5 验收 B1-B6 + B9 + 复审 §1/§3/§4 + §54 红线 grep**：38 checks；真起 Go 进程、真读 Codex、真扫凭据形态；B5 用本机 LAN 地址实测连不上；「失败不清空」靠预置 `state.json` + `--ttl 1s` 才逼出真实失败路径；失败类别与脱敏**按解析后的 JSON 字段断言**（不是全响应找子串），并用测试二进制自己当 app server 造出「先拒绝再失败」（→ `CODEX_AUTH_REQUIRED`，且上游文本被整句替换）与「未归类错误带凭据」（→ `CODEX_UNKNOWN` + `[redacted]`）两种形状，响应与 `state.json` 两头都查；再加两条并发 `?refresh=1` 看是否都有答案且状态文件完整。B7 仍只有单测（造它要换掉真实 Codex 安装），脚本里写明了原因 |
| `assert-bridge-phone.ps1` | **Phase 6 验收 C1-C8 + A4/A6/C9**：57 checks；真起 Bridge、真在模拟器里建/删 Codex 账户；额度数字与本机读数逐项比对；明文策略用「同一台服务器、同一令牌、只有主机不同」逼出真实拦截；两个真实 DeepSeek 账户的 id+credential_id 首尾比对，对设备库**只读**（连 `-wal`/`-shm` 一起拉，见 §8） |
| `place-widget.ps1` / `widget-open-account.ps1` / `capture-accept4.ps1` | widget 放置与验收④ |
| `dump-ui.ps1` / `dump-db.ps1` | 排查助手 |

## 10. PUA 交付记录（按用户要求：记事实，不记分）

本会话在 PUA skill（华为味，RCA+蓝军方法论）下执行：复查修复三轮提交（`601bcac`/`b2fb412`/`5f2f487`）均有可见 PUA 输出——`[PUA-DIAGNOSIS]` 行绑定证据与动作、`[PUA生效 🔥]` 标记超额工作（设备回归脚本落地、垃圾文件清理、坑教训固化进脚本注释）、失败时按 L1 换本质不同方案而非调参（BOM 丢失事故即按此排查）。按用户明确要求，**只记录交付事实，不记录评分**。

---

接手后的第一个动作建议：核对工作区和分支，阅读 `docs/PHASE-7-PLAN.md` §10；Phase 7 手机半边已完成，后续先按 D1/D2 决策确定扫码与 LAN 范围。
