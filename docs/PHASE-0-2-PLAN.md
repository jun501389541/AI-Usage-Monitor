# Phase 0–2 实施计划

> 依据 `AI-Usage-Monitor-Development-Plan.md`（下称 Spec）与已确认的 9 项设计决策。
> 每个 Phase 结束时必须编译、跑测试、跑设备冒烟，并留下输出证据。

## 已确认的设计决策

| # | 决策 | 影响 |
| --- | --- | --- |
| D1 | 迁移到 Gradle (AGP)，不再保留 `build.ps1` 作为主构建 | 上游脚本硬编码 `build-tools 35.0.0` 与 `platforms/android-35`，本机不存在 |
| D2 | 用 SQLite（`SQLiteOpenHelper`）统一存 accounts / credentials / snapshots / widget 配置 | 取代 `SharedPreferences` 的 10 个散落键 |
| D3 | Phase 1 就上真 Keystore AES-GCM + 老数据迁移 + 失败降级标记 | 不再有明文 API Key 入库 |
| D4 | Phase 1 完成包名/applicationId `com.aiusage.monitor`、应用名 `AI Usage Monitor`，仓库根 = 项目根 | 手机上需卸载重装，已放置的 Widget 实例失效需重加 |
| D5 | Phase 1 UI 原地重接（单屏、单账户、零可见变化）；Phase 2 新增 `AccountListActivity` 作 launcher，旧屏降级为账户详情 | 降低 Phase 1 的回归面 |
| D6 | Phase 2 只做最小账户绑定：沿用现有 3 个 Widget 布局，每个 `appWidgetId` 记住一个 `accountId` | Slot 模型、2×4/4×4 属于 Phase 3 |
| D7 | 宿主机 JVM 测试（不引入 JUnit 之外的框架）+ adb 设备冒烟脚本 | 纯逻辑类必须不依赖 Android |
| D8 | 有 ≥2 个真实 DeepSeek API Key 可用于验收 | 模拟器 + 真机 |
| D9 | `git init` + 上游 zip 解压结果作首个 commit，之后每 Phase 一个 commit | 已有 `a72dff8`（上游基线）、`89dec91`（Phase 0） |

## 分层与依赖方向

依赖只能向下，禁止反向或跨层直连：

```
ui/            MainActivity, AccountListActivity, AccountEditActivity
  ↓
widget/        WidgetConfig, WidgetUpdateManager, WidgetRenderer
  ↓
refresh/       AccountRefreshManager, RefreshPolicy
  ↓
usage/         UsageRepository, UsageSnapshot
account/       Account, AccountRepository, AccountManager
  ↓
provider/      UsageProvider, ProviderRegistry, ProviderCapabilities
               provider/deepseek/DeepSeekProvider
auth/          AuthType, Credential, CredentialStore, AuthAdapter, ApiKeyAuthAdapter
  ↓
storage/       Database, SecureStorage
model/         UsageResult, Balance, QuotaWindow, Metric, UsageStatus, UsageError
util/          Money, JsonCodec
```

**硬约束（Spec §53）：** UI 与 Widget 不得直接调 Provider；Widget 不得读 Token；
Provider 不得操作 Widget；Account 不得存明文凭据；新 Provider 只经 Registry 接入；
刷新失败不得清除最后一次成功数据。

---

## Phase 0 — Fork 与基线 ✅ 已完成

**目标：** 保证原项目可编译 / 安装 / 运行 / 查询 / Widget 正常，建立基线测试，禁止重构。

| 项 | 状态 | 证据 |
| --- | --- | --- |
| 上游 zip 验真（git blob 哈希） | ✅ | `bc6dc440a023078c40de624304da69847699ef63` |
| 目录摊平到仓库根 | ✅ | `app/src/main/{java,res}` |
| Gradle 迁移 | ✅ | `BUILD SUCCESSFUL in 1m 27s`，AGP 9.0.1 / Gradle 9.1.0 / JDK 17 |
| 宿主机 JVM 测试 | ✅ | 4/4 pass，含 org.json 门控 |
| 安装 / 启动 / 无崩溃 | ✅ | emulator-5554，`FATAL EXCEPTION` 零命中 |
| UI 与上游一致 | ✅ | 截图逐项比对 |
| 真实 HTTP 往返 | ✅ | 无效 Key → 401 → 「API Key 无效或已失效」 |
| 3 个 Widget 注册 + 实例渲染 | ✅ | 4×2 与 2×1 已放桌面 |

**关键陷阱（已固化到 `tools/`）：**

1. `android.jar` 里的 `org.json.*` 是抛 `Stub!` 的桩实现 → 测试 classpath 必须引真实 `org.json`。
2. Widget 拖拽无法用 `input draganddrop`（它立即移动，从不触发长按）→ 必须显式 `motionevent DOWN→MOVE→UP`。
3. `tools/**/*.ps1` 必须存为 **UTF-8 with BOM**，否则 Windows PowerShell 5.1 按 GBK 读，中文字面量变乱码。
4. `uiautomator dump` 前必须 `rm -f /sdcard/u.xml`，否则可能读到上一次内容。
5. Widget 选择器展开必须点右侧 chevron（x≈982），且**不可重试**——它是 toggle，重试会振荡。

---

## Phase 1 — 基础架构重构

**目标（Spec §51）：** 实现 Provider / ProviderRegistry / Account / Credential / UsageResult /
UsageRepository / AccountRefreshManager；把 DeepSeek 迁移为 `DeepSeekProvider`；
**UI 功能暂时保持基本不变**。验收 = 原有功能无明显回归 + Spec §14 四项。

**同时完成 D4 的包名迁移**，因为后续所有新文件都写在 `com.aiusage.monitor` 下，
留着旧包名只会产生两套并存的目录。

### 1.1 包与坐标迁移

| 动作 | 文件 |
| --- | --- |
| 改 `namespace` / `applicationId` → `com.aiusage.monitor` | `app/build.gradle.kts` |
| 改应用名 → `AI Usage Monitor` | `app/src/main/res/values/strings.xml` |
| 改 `package` 声明 + import | 11 个上游 `.java` |
| 改 manifest 的 `.ClassName` 引用 | `app/src/main/AndroidManifest.xml` |
| Widget 标签去 DeepSeek 化 → `AI Usage 4×2` 等 | `AndroidManifest.xml` |

> **代价（已接受）：** 包名变化 = 新应用，手机上需卸载重装，已放置的 Widget 实例失效。

### 1.2 新增文件（本 Phase 的核心交付）

```
app/src/main/java/com/aiusage/monitor/
├── model/
│   ├── Balance.java              ✅ 金额 + 币种，禁止裸 double
│   ├── QuotaWindow.java          额度窗口，禁止假定 primary=5H
│   ├── Metric.java               无法归入 Balance/Quota 的指标
│   ├── UsageResult.java          统一返回结构
│   ├── UsageStatus.java          OK/REFRESHING/STALE/NETWORK_ERROR/BRIDGE_OFFLINE/AUTH_REQUIRED/NO_DATA
│   ├── UsageError.java           9 类错误 + 用户可见文案映射
│   ├── AuthType.java             API_KEY/BRIDGE_TOKEN/OAUTH/COOKIE/CUSTOM
│   ├── Credential.java           id/type/encryptedPayload/createdAt/updatedAt
│   └── Account.java              id/providerId/displayName/authType/credentialId/bridgeId/enabled/sortOrder/...
├── provider/
│   ├── UsageProvider.java        接口（Spec §4 原文签名）
│   ├── ProviderCapabilities.java
│   ├── ProviderRegistry.java     注册表，禁止 if(provider==DEEPSEEK) 链
│   ├── AuthContext.java          Provider 只拿得到这个，拿不到存储
│   └── deepseek/DeepSeekProvider.java
├── auth/
│   ├── AuthAdapter.java          接口
│   ├── ApiKeyAuthAdapter.java
│   └── CredentialStore.java      接口
├── storage/
│   ├── Database.java             SQLiteOpenHelper，accounts/credentials/snapshots/widget_config
│   ├── SecureStorage.java        Keystore AES-GCM；失败时降级并打标记
│   ├── SqliteCredentialStore.java
│   ├── SqliteAccountRepository.java
│   ├── SqliteUsageRepository.java
│   └── LegacyMigration.java      旧 prefs → 新表，保留旧键不删
├── usage/
│   ├── UsageSnapshot.java        id/accountId/timestamp/usageData/source/success
│   └── (UsageRepository 见 storage)
├── refresh/
│   ├── AccountRefreshManager.java  统一刷新链
│   └── RefreshPolicy.java          各 Provider 建议间隔 + 用户覆盖
├── widget/
│   ├── WidgetConfig.java         widgetId/accountId/...（Phase 2 只用 accountId）
│   └── WidgetUpdateManager.java  updateWidget/updateWidgetsForAccount/updateAllWidgets
└── util/
    ├── Money.java                金额格式化（纯，可 JVM 测）
    └── JsonCodec.java            UsageResult ↔ JSON（纯，可 JVM 测）
```

### 1.3 改写既有文件

| 文件 | 改什么 | 不改什么 |
| --- | --- | --- |
| `MainActivity` | `queryBalance()` 改为经 `AccountRefreshManager` 取数；删除内嵌 HTTP/JSON 解析；Key 存取改走 `CredentialStore` | 全部 UI 构建代码、文案、颜色、布局**逐字不动**（D5） |
| `WidgetRefreshReceiver` | 删除第二份 `BALANCE_URL` 与 `fetchCnyBalance`；改为触发 `AccountRefreshManager` | `BOOT_COMPLETED` 重排逻辑保留 |
| `WidgetUpdater` | 快照读写改走 `UsageRepository`；删除 `PREFS_NAME`/`PREF_API_KEY` 直读 | 3 个布局的渲染逻辑保留（Phase 3 才重构） |
| `PeakTimeUtils` | 移入 `util/`，逻辑不变 | 峰谷规则本身 |
| `HolidayStore` / `HolidayUpdater` | 移入 `storage/` 与 `refresh/`，逻辑不变 | 抓取与回退逻辑 |
| `UsageTracker` | 逻辑并入 `UsageRepository` 的今日用量计算 | — |
| 3 个 Widget Provider | 包名变更 | 其余逐字不动 |

### 1.4 数据迁移

`LegacyMigration` 在 `Database` 首次创建时运行一次：

| 旧键 | 新位置 |
| --- | --- |
| `api_key` | `credentials` 一行（API_KEY，Keystore 加密）+ `accounts` 一行（displayName = "DeepSeek"） |
| `widget_balance` / `widget_usage` | 首次快照（`usage_snapshots`），避免升级瞬间 Widget 显示 `—` |
| `usage_date` / `usage_last_balance` / `usage_total` | 今日用量基线 |
| `refresh_interval_ms` / `widget_refresh_interval_ms` | 刷新策略 |
| `holiday_dates_*` / `holiday_last_check_*` | 保持原样（节假日缓存不属于本次模型范围） |

**旧键一律不删除**，作为回滚保险。

### 1.5 验收

| # | 验收项 | 验证方式 |
| --- | --- | --- |
| 1 | 编译通过 | `gradlew :app:assembleDebug` |
| 2 | 纯逻辑单测通过 | `gradlew :app:testDebugUnitTest` |
| 3 | 安装 / 启动 / 无崩溃 | `tools/smoke/smoke-phase0.ps1`（参数化包名） |
| 4 | 原功能无回归：余额、今日用量、自动刷新、手动刷新、Widget、峰谷 | 设备冒烟 + 截图比对 |
| 5 | **改 Key 后 Account ID 不变、历史不丢、Widget 配置不丢**（Spec §14） | 新脚本：改 Key → 断言 accountId 与 snapshot 数不变 |
| 6 | **新增第三个 DeepSeek Account 不修改 DeepSeekProvider**（Spec §14） | 代码审查 + 单测：注册两个 account 后 provider 文件 hash 不变 |
| 7 | Widget 不直接读 Token、UI 不直接调 Provider | `grep` 断言：`WidgetUpdater`/`MainActivity` 无 `api_key`、无 `BALANCE_URL` |
| 8 | 刷新失败不清空最后成功数据 | 单测：失败路径下 `UsageRepository` 仍返回上次成功快照 |

---

## Phase 2 — DeepSeek 多账户

**目标（Spec §51）：** 实现多个 DeepSeek Account；增加添加/删除/重命名/修改 Key/排序；
验收 = 至少两个 API Key 独立工作。

### 2.1 新增 UI

| 文件 | 职责 |
| --- | --- |
| `ui/account/AccountListActivity.java` | **新 launcher**。列表显示 `displayName` + 余额 + 状态；点击进详情；长按菜单（重命名/删除/排序） |
| `ui/account/AccountEditActivity.java` | 添加/编辑账户：名称、Provider（Phase 2 只有 DeepSeek）、API Key、启用开关 |
| `ui/account/AccountAdapter.java` | 列表适配器 |

旧的 `MainActivity` 降级为**账户详情页**（D5）：从列表进入，显示该账户的余额/今日用量/峰谷。
`AndroidManifest.xml` 的 LAUNCHER intent-filter 从 `MainActivity` 移到 `AccountListActivity`。

### 2.2 Widget 账户绑定（D6）

Phase 2 只做最小绑定，**不做** Spec §33 的 Slot 模型：

1. 新增 `WidgetConfigActivity`，并在 3 个 widget 的 info XML 加 `android:configure`。
2. 添加 Widget 时弹出账户选择器，把 `appWidgetId → accountId` 写入 `widget_config` 表。
3. `WidgetUpdateManager.updateWidgetsForAccount(accountId)` 只重绘绑定了该账户的实例。
4. **未绑定账户的旧实例**回退到「列表第一个启用账户」，保证升级后不显示空白。

### 2.3 验收

审查报告（`docs/REVIEW-AND-NEXT-STEPS.md` 第二步）要求按 6 项矩阵留存执行结果，下表为 2026-10-02 设备实测状态（模拟器 Pixel_7_API_37，两账户 DeepSeek `acct_294b8c1eaff5` / DeepSeekWork `acct_93e6722626d6`）：

| # | 验收项 | 验证方式 | 实际结果 |
| --- | --- | --- | --- |
| 1 | 两个真实 Key 各自独立工作 | 设备冒烟：两个账户余额互不相同且都对 | **通过**（2026-10-02 补测）：第二把真实 Key（sha256-8 `06e0c9d0`，与 A 指纹 `f684d775` 不同）注入 DeepSeekWork 后端到端刷新成功——B newest 快照 `success=1` / status OK / 余额 ¥24.94（id 116-118）；`assert-two-live-accounts.ps1` 16/16 PASS（RESULT: both accounts are live and independent, acceptance 1, 2 and 3 pass）。宿主直连 `api.deepseek.com/user/balance` 对照：同 Key HTTP 200 / is_available=true。注入教训：单次 `adb shell input text` 35 字符会丢字符（密文 60B ≠ 完整密文 104B），分 3 段注入后密文长度与 A 对齐（均 104B） |
| 2 | 数据独立 | 一个账户刷新失败，另一个仍正常显示 | **通过**：DeepSeekWork 持续 AUTH_REQUIRED 期间，DeepSeek 独立刷新成功（快照 id 56→59，成功数 33→34）；反向证据为停用实验——停用任一账户后「刷新全部账户」跳过它（总行数与成功数不变），另一账户正常刷新 |
| 3 | 今日用量独立 | 两个账户的 `usage_snapshots` 按 accountId 隔离 | **通过**：读写均按 `account_id + day` 定位（R1 修复，commit `d83d055`，注入昨日+今日两行的设备 A/B 对照实验：修复版 total_usage 保持 3.00，回退版被冲成 0.00）；`Money.accumulate` 余额上升计 0 |
| 4 | Widget 可独立选择账户 | 放两个 Widget 绑不同账户，截图确认 | **通过**：4x2→DeepSeek、2x1→DeepSeekWork（`widget_config`: `6|4x2|acct_294b8c1eaff5; 7|2x1|acct_93e6722626d6`）；`tools/smoke/widget-open-account.ps1` 冷启动与 warm 复用两种模式均 2/2 命中绑定账户 |
| 5 | 改 Key 后 Account ID 不变、历史不丢、Widget 配置不丢 | 同 Phase 1 验收 5 | **通过**：`tools/smoke/assert-account-identity.ps1` 10/10 PASS（打假 Key 进 DeepSeekWork，widget_config 断言非空转：`6|4x2|…;7|2x1|…` 前后一致）；账户行/历史行/其他账户凭据 `cred_5154ea79778c` 均不变，protection=keystore-aes-gcm，DB 无明文 sk- |
| 6 | 新增第三个账户不修改 `DeepSeekProvider` | `git diff --stat` 断言 provider 目录无改动 | **通过**：`git diff --stat 4ed7df1 HEAD -- app/src/main/java/com/aiusage/monitor/provider` 输出为空，`DeepSeekProvider.java` SHA-256 与 Phase 1 基线一致 |

补充：跨日累计（R1，对照实验见上）、凭据降级提示（R2，`SqliteCredentialStore implements AccountManager.DegradedAware`）、刷新期间删除不落孤儿数据（R3，落库前 `isGone` 存在性检查 + `DeleteRaceDropsOrphanTest`，含 f0e9319 收口的 openCredential / registry.require 两处失败分支加锁，现 9 用例）的验证记录见 `docs/REVIEW-AND-NEXT-STEPS.md` 与各自提交 `a6ed074`、`d83d055`、`f0e9319`。回归基线数字随提交持续变化，**以 `docs/HANDOFF.md` §2 的当前值为准**（Phase 2 收尾为 19 suites / 273 tests；R3 凭据代次 +11 → 284；Phase 3 Slot 模型与解析器 +25 → 329；Phase 3 审查修复 +2 → 331，Lint 由 43 warning 降至 30、0 error）。R3 的锁在 `b1f1613`/`7ee7ba3` 又收紧过一次：凭据变更的代次推进现在也在 `writeMonitor` 内，理由见 `docs/REVIEW-AND-NEXT-STEPS.md` 的 Phase 3 审查节。

---

## 明确不在本次范围（Phase 3+）

Widget 通用化重构、`UsageSnapshot` 历史图表、Windows AI Usage Bridge、
二维码配对、Bridge 自动发现、Codex Provider / Direct ChatGPT OAuth / AUTO、
2×4 与 4×4 Widget、Slot 模型、`AUTO` 刷新策略。

---

## 每阶段的固定动作（Spec §53）

1. 先理解原项目再修改 —— 已完成（结构勘察报告）
2. 每个阶段先制定具体修改计划 —— 本文件
3. 不允许一次性重写整个项目 —— 按 1.1→1.5 顺序小步走
4. 每阶段完成后先编译 —— `gradlew :app:assembleDebug`
5. 每阶段进行回归测试 —— 宿主机 JVM 测试 + 设备冒烟
6. 原 DeepSeek 功能不能因为架构升级失效 —— 验收 4
