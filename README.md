# AI Usage Monitor

Android 应用，用于查看 AI 服务平台的余额与用量，并提供桌面小组件。项目源自
[kiuaah/DeepSeek-API-Balance-check-tool](https://github.com/kiuaah/DeepSeek-API-Balance-check-tool)，
正在按 `AI-Usage-Monitor-Development-Plan.md` 从「DeepSeek 余额查询工具」演进为
支持多平台、多账户、多种认证方式的通用 AI 额度监控工具。

已完成 **Phase 0（Gradle 基线）、Phase 1（架构分层）、Phase 2（多账户）、
Phase 3（通用 Account Widget：Slot 模型、状态与更新时间、手动刷新）、
Phase 4（历史快照的区间读取与保留策略）、Phase 5（Windows AI Usage Bridge
MVP）、Phase 6（手机经 Bridge 读取 Codex 额度）、Phase 7（手机与电脑配对：
三种引入通道、SPKI 指纹钉定与逐连接主机名校验、`bridges` 表、配对与设备界面）**。应用名
`AI Usage Monitor`，包名 / applicationId `com.aiusage.monitor`
（`versionCode 57` / `versionName 4.0.0`）。手机现已接入二维码配对。
局域网自动发现、Direct OAuth、AUTO、`UsageSnapshot` 图表、2×4 / 4×4 尺寸尚未开始。

## 当前功能

- **账户列表（启动页）**：管理多个 AI 服务商账户，每张卡片独立显示用量与状态；支持刷新全部账户
- **添加 / 重命名 / 启用停用 / 排序 / 删除账户**，每个账户单独保存一组凭据。
  左滑显示删除（需确认），右滑显示置顶或取消置顶；长按可在置顶组或普通组内拖动排序。
  重命名、启停及重新配对保留在右侧更多菜单。
- **账户详情页**：DeepSeek 账户在此输入并记住 API Key，点「查询余额」；Codex 账户
  没有可打的字段；配对账户从本机保存的 Bridge 记录读取地址、加密凭据只存设备令牌，
  手动调试账户则把地址和令牌一起加密保存。详情页显示正在调用的 Bridge 地址，可点「查询额度」；自动查询按该账户的间隔设置发起
- 进入详情页时立即查询一次（选择「仅手动刷新」时除外）
- 前台间隔按服务商分别保存：DeepSeek 默认 15 秒，Bridge 最短 5 分钟；两者都可设为仅手动
- 退到后台后停止详情页自动查询；有 Widget 时按至少 15 分钟（默认 30 分钟）安排后台刷新
- 提供 4×2、2×2、2×1 三种桌面小组件，**每个 Widget 绑定一组账户 Slot**：
  4×2 是最多三个账户的 Dashboard（可同平台多账户，也可多平台混排），2×2 / 2×1 为单账户
- 每个 Slot 显示账户名、余额与今日用量、**最后读取时间**与**最新刷新状态**（过期/认证失败/网络失败均明示，且保留上次成功的余额）
- **小组件上可直接点「刷新」**，经共享的 `AccountRefreshManager` 只刷新该 Widget 关联的账户，与前台、后台走同一条链路
- 未绑定账户的 Widget 回退到第一个启用账户，升级后不会显示空白；绑定的账户被删除时该 Slot 明确显示「账户已删除」
- **历史读数**：每次成功与失败都落 `usage_snapshots`；账户详情页显示「最近读数」（时间、余额、与上次的差值、失败标记）
- **保留策略**：明细快照保留 14 天，日粒度事实由 `daily_usage` 长期保留；清理永不删除某账户的「最后一次成功读数」与「最新一行」，因此不会把 Widget 清成空白
- **OpenAI Codex 账户**：账户可选 DeepSeek 或 Codex。Codex 数据由电脑端额度桥提供；列表显示每周剩余额度，详情页显示额度条、剩余重置时间及具体时间点。支持展示重置卡次数、类型及到期日期，并区分未返回与格式无效；此功能只读取状态，不兑换卡片。
- **失败态按来源分开**：Bridge 未连接、电脑端授权已失效、当前 Codex 不支持此查询是三句话三种状态，不会互相冒充（Codex 账户不会被告知「API Key 无效」，它没有 API Key）。
- 一键进入 `https://platform.deepseek.com/`
- 可选“记住密钥”，仅保存在应用私有存储中
- 黑 / 灰配色；扫码使用 ZXing，账户拖动列表使用 AndroidX RecyclerView

### 今日用量的口径

今日用量**不是**平台汇总接口的读数——DeepSeek 开放平台没有面向个人 Key 的用量
汇总端点。实现是由本应用自行累计的**估算值**：每次成功刷新后，若余额较上次
成功读数下降，差额计入今日用量；余额上升（充值）计 0，不追溯；跨日重新起算。
因此它只反映「本应用看到的余额下降」，应用未运行期间的用量不会被计入。

## 架构

Provider、Account、Credential 三层严格分离，账户凭据不进入 Provider 参数。唯一的
刷新链路如下（Spec §25），App、Widget 与后台都只从 `UsageRepository` 取数：

```text
Account → ProviderRegistry → CredentialStore → AuthAdapter → Provider
        → UsageResult → UsageRepository → UsageSnapshot → WidgetUpdateManager
```

- `ProviderRegistry` 按 provider id 解析实现，代码中不存在
  `if (provider == DEEPSEEK)` 这类分支；新增账户不需要改动 Provider。
- `DeepSeekProvider` 是**无状态**的：它只接收 `Account` 与 `AuthContext`，唯一使用
  账户的地方是 `account.getId()`，从不读取凭据存储。（由
  `DeepSeekProviderStabilityTest` 的结构断言守住。）
- 持久化使用 SQLite（`SQLiteOpenHelper`），表为 `accounts` / `credentials` /
  `usage_snapshots` / `widget_config` / `daily_usage` / `app_meta`。
- 凭据使用 Android Keystore（AES-GCM）加密，`credentials.protection` 列记录
  `keystore-aes-gcm` 或降级标记 `degraded-local`。
- 刷新失败只**追加**一条失败快照，永不删除历史；`latest()` 过滤 `success = 1`，
  因此一次网络故障不会清空最后一次成功读数（Spec §39 rule 18）。

## Windows AI Usage Bridge（Phase 5）

`bridge/` 是与 Android 应用并列的**独立 Go module**（不在 Gradle 里），负责把本机
Codex 的额度读数取出来、缓存、并只对本机提供服务：

```text
codex app-server（stdio JSON-RPC）→ account/rateLimits/read → 解析 → 缓存 5 分钟
→ http://127.0.0.1:38411/v1/*
```

- 只读三件事：5 小时窗口、周窗口、两者的重置时间（Spec Phase 5 的验收目标）。
- **不读 Codex 的凭据**：额度由 `codex` 自己的登录态回答，Bridge 只看结果，因此不存在
  令牌被存储、透传或写进日志的路径（Spec L804-809）。
- 只绑回环：`--host` 给非 loopback 地址时直接拒绝启动，而不是「能跑但危险」。
- 读失败**不清空旧数据**：返回上一次的窗口并带上 `degraded` 失败类别，四个类别把
  「电脑不在 / Codex 没这方法 / 登录失效 / 超时无应答」分得很开。
- 本阶段**不与手机通信**：没有配对、没有局域网发现，接口留给 Phase 6。

```powershell
cd bridge
go build -o bin\aiusage-bridge.exe .\cmd\aiusage-bridge
.\bin\aiusage-bridge.exe                 # 默认 127.0.0.1:38411
```

接口：`GET /v1/health`、`GET /v1/providers`、`GET /v1/accounts/codex/usage`（加
`?refresh=1` 忽略缓存）。手机上（当前仅模拟器）在「添加账户」里选 OpenAI Codex，
地址填 `http://10.0.2.2:38411` —— `10.0.2.2` 是模拟器访问宿主 loopback 的地址，所以 Bridge 无需监听局域网。
应用的明文 HTTP 例外只放行 `10.0.2.2` 与 `localhost`（`res/xml/network_security_config.xml`），
`usesCleartextTraffic` 保持 `false`。注意 `networkSecurityConfig` 需要 API 24+，minSdk 仍为 23：更低版本上这条例外不生效。
参数：`--port`、`--host`、`--codex`（显式可执行文件路径，
留空则自己找——Windows 上 `where codex` 只会给到 npm 的 `.cmd` shim，Go 无法直接
启动批处理，所以找的是包目录里的原生 `codex.exe`）、`--data-dir`、`--ttl`、`--timeout`。

## 手机与电脑配对（Phase 7）

Phase 6 的 hand-typed 地址是调试通道；正式通道是让手机认识这台电脑。

```powershell
cd bridge
go build -o bin\aiusage-bridge.exe .\cmd\aiusage-bridge
.\bin\aiusage-bridge.exe --pair --host 192.168.1.20 --port 38411 --data-dir D:\bridge-data
```

- `--pair` 打开 TLS（自签 RSA-2048 身份，指纹 = 证书 SPKI 的 SHA-256）并要求设备令牌；
  **绑定到非回环地址而没有 `--pair` 时进程直接启动失败**，不会悄悄裸奔。
- 引入通道三条：① 整段 payload（`aiusage://pair#<base64url(json)>`，深链或粘贴）；
  ② 手输「地址 + 端口 + 8 位配对码」，先出指纹尾号、由人勾选确认后才发送配对码（A11）；
  ③ 手机内置二维码扫描（相机或相册），识别后自动走同一条 TLS 配对流程。
  为满足扫码连接需求，手机引入 ZXing Embedded；识别在本机完成，无需另装扫码 App。
  配对码字母表排除了易混的 `0o1i2l`。

### 用手机扫码连接

1. 手机与电脑连接同一个 Wi-Fi，在电脑端打开“添加设备”的配对二维码。
2. 手机点击添加账户，选择 Codex，再点击“扫码连接电脑（推荐）”。
3. 允许相机权限并对准二维码，识别后自动配对并添加账户；也可从相册选择二维码图片。

手机支持 Go Bridge 的 `aiusage://pair#…` 和 CodexLauncher 的 `https://…/v1/device#…` 二维码。
CodexLauncher 扫码后还需在电脑端批准设备申请，手机等待批准并自动完成配对。
过期或已使用时，请在电脑端重新生成。
“其它配对方式”提供粘贴配对内容及手输地址入口。已有账户重新配对会保留历史与小组件绑定。
本仓库的 Bridge CLI 通过 `--add-device` 输出配对内容；上述二维码由电脑端界面提供。
两端字段与验证范围见 [CodexLauncher 联调记录](docs/CODEXLAUNCHER-PAIRING.md)。

### 配对数据

- pair token 一次性、`--pair-ttl` 过期即失效；换回来的 Device Token 长期有效，
  落盘只有哈希，明文既不进数据库也不进日志。
- 手机侧 `bridges` 表（加入于 schema v3；v2→v3 是单事务迁移，并在真机上彩排过「备份→降到 v2→
  升回 v3→逐表比对→字节级还原」的往返）按 **Bridge ID** 记一台电脑，
  地址只是它的可变属性；账户通过 `accounts.bridge_id` 指过去，不再各存一份地址。
- 每条连接都按钉住的 SPKI 摘要校验，主机名由**逐连接**的 verifier 处理
  （证书没有 LAN SAN，也绝不 `setDefaultHostnameVerifier`）。
- 界面上是「与电脑配对」和「已配对的电脑」（后者可忘记电脑，指向它的账户显示「还没有与这台
  电脑配对」，并可在账户列表更多菜单选择「重新配对」原地换回令牌、保留历史与 Widget 绑定）。
  「重新配对」的可恢复性已在设备上跑通（2026-10-04 的 `assert-bridge-pair.ps1`：能进界面、
  读到证书、原地保住同一个账户不新增，修复后的刷新写回成功快照）。此前它记红，是验收脚本
  在**数错了账户**——每次配对都把账户命名成那台电脑的地址，列表里三行同名，长按修的是第一行，
  计数却按「最新创建的 id」算；现在脚本按「哪条凭据的 `updated_at` 被这次修复推进」来认定。
- 模拟器专属：`127.0.0.1 / localhost / ::1` 在**拼 URL 时**就被改写成 `10.0.2.2`，也就是
  在建连之前（`PairingClient` 的探测与兑换走同一个 `addresses.baseUrl(...)`，两条路径不会
  指向不同机器）；改写只换拨出的地址，不换钉住的摘要——指纹仍然来自那台机器出示的证书。

## 构建

项目使用 Gradle（AGP 9.0.1 / Gradle 9.1.0，JDK 17+）。当前数据库为 schema v4，
v3→v4 增加账户置顶状态，现有账户保持原顺序并迁移为未置顶。运行时依赖为 ZXing 与 RecyclerView；
`org.json` 仅作为单元测试依赖引入，因为 `android.jar` 里的 `org.json.*` 是抛
`Stub!` 的桩实现。

```powershell
.\gradlew.bat :app:assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

最新全局审查与验证见 [2026-10-09 审查记录](docs/REVIEW-2026-10-09.md)。
`assembleRelease` 当前生成未签名 APK；正式发布前需递增版本号并配置固定发布签名。

Bridge 侧另有**第二套门禁**（Go，独立于 Gradle）：

```powershell
cd bridge
gofmt -l ./cmd ./internal; go vet ./...; go test ./...   # 109 个 PASS、2 个 SKIP（含子测试）
```

### 环境

`tools\env.ps1` 会设置本机工具链路径（JDK / Android SDK / AVD），可在任何脚本
开头点源引入：

```powershell
. .\tools\env.ps1
```

需要 `local.properties` 指向本机 SDK（已被 gitignore）：

```properties
sdk.dir=D\:\\Android\\sdk
```

## 测试

宿主机 JVM 单元测试（50 个 suite / 517 个测试方法；最近记录为 2026-10-04 09:25）：

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
```

> 必须带 `--rerun-tasks`，否则 Gradle 会报 `UP-TO-DATE` 秒退，`build\test-results`
> 里留的还是上一次的结果。

设备冒烟测试（需要一台在线设备或模拟器）：

```powershell
.\tools\smoke\smoke-phase0.ps1
```

该脚本会安装 APK、启动账户列表、点进详情页、用无效 Key 走一遍真实的 HTTP 往返
（断言 401 被映射为「API Key 无效或已失效」）、检查无崩溃，并确认三个小组件
Provider 已注册且至少有一个实例绑定（共 13 项检查）。

验证“更换 API Key 后账户身份不变”（历史、Widget 绑定不丢）：

```powershell
.\tools\smoke\assert-account-identity.ps1
```

验证“刷新失败不清空最后一次成功读数”的 SQL 层行为：

```powershell
.\tools\smoke\assert-last-success.ps1
```

验证 Windows 侧 Bridge 本身（真起 Go 进程、真读 Codex、真扫凭据形态，外加用测试二进制自己冒充 app server 造出「先拒绝再失败」与「上游文本带凭据」两种形状，38 项）：

```powershell
.\tools\smoke\assert-bridge.ps1
```

验证配对模式的四种启动配置（默认 `--pair`、`--host <LAN>`、`--host 0.0.0.0`、自定义
`--data-dir`，53 项）：打印的监听地址、交给手机的地址、以及终端建议的那行
`--add-device` **原样执行**是否真能拿到 payload。不需要手机。

```powershell
.\tools\smoke\assert-bridge-bind.ps1
```

验证手机经 Bridge 读 Codex 额度（C1-C8 + A4/A6，57 项；需要模拟器与本机 Bridge，
它会创建并删除自己的 4 个 Codex 账户，并在 `0.0.0.0` 临时起一台额度 stand-in）：

```powershell
.\tools\smoke\assert-bridge-phone.ps1
.\tools\smoke\assert-bridge-phone.ps1 -SkipLegacy   # 不重跑三个老 DeepSeek 脚本
```

> 该脚本对设备数据库**只读**：拷出后在主机 `sqlite3.exe` 上查，且两个真实 DeepSeek
> 账户的 `id + credential_id` 在首尾各测一次并断言相等。

验证手机与电脑配对（P-1…P-11，57 项；需要模拟器，且必须显式给出一个手机可达的绑定地址）：

```powershell
.\tools\smoke\assert-bridge-pair.ps1 -BridgeHost 0.0.0.0 -PairTtl 600s
.\tools\smoke\assert-bridge-pair.ps1 -BridgeHost 0.0.0.0 -SkipLegacy   # 不重跑老回归脚本
.\tools\smoke\assert-bridge-pair.ps1 -SelfTest                         # 只验脚本自身的子进程等待
```

> 不给 `-BridgeHost` 时手机侧各条一律记「未判定」而不是 PASS：只绑 `127.0.0.1` 的 Bridge
> 在模拟器网段上没有响应，配对必然连不上。脚本只清理**自己这一轮**记录在
> `tools/smoke/out/pair-manifest.txt` 里的账户与电脑，并故意留一台未登记的诱饵 Bridge 断言
> 它不被删；设备数据库同样只读（拷出 db 与 `-wal`/`-shm`），两个真实 DeepSeek 账户首尾比对。

把一个小组件放到桌面（Widget 拖拽无法用 `input draganddrop` 完成，
必须用显式的 DOWN→MOVE→UP 手势）：
```powershell
.\tools\smoke\place-widget.ps1 -WidgetSize 4x2
```

### 脚本注意事项

`tools` 下的 PowerShell 脚本**必须保存为 UTF-8 with BOM**。本机默认的
Windows PowerShell 5.1 会按 GBK(936) 读取无 BOM 文件，导致脚本里的中文
字面量变成乱码、匹配永久失败。

## 调试

改完代码重新看一遍效果，一条命令即可：

```powershell
.\tools\dev.ps1
```

它依次执行 `:app:installDebug` → 启动账户列表 → 打印与本次运行相关的
logcat，并统计 `FATAL EXCEPTION`（有则退出码 1，可直接接进脚本）。

```powershell
.\tools\dev.ps1 -NoBuild      # 复用已安装版本，启动 + 看日志；代码或资源改动需重新编译
.\tools\dev.ps1 -Follow       # 持续滚动日志，Ctrl+C 退出
.\tools\dev.ps1 -ErrorsOnly   # 只看崩溃与错误
```

> 本项目没有安装 Android Studio，全部调试经由上面的脚本 + `adb` 完成。

三个容易踩的坑，`dev.ps1` 已经绕开：

1. **`adb` 不在 PATH。** 先 `. .\tools\env.ps1`，或直接用 `dev.ps1`（它会自动引入）。
2. **`am start -n ...AccountListActivity` 不一定切到账户列表。** 若同一 task 里已经
   停着详情页，Android 只把该 task 提到前台，屏幕仍停在详情页，看起来像命令没生效。
   必须带 MAIN/LAUNCHER：`am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n <组件>`。
3. **`installDebug` 会 force-stop 应用**，桌面小组件随即变成启动器的灰色占位卡片，
   直到应用再被启动一次。这不是组件丢了。
4. **`adb shell am broadcast -a android.appwidget.action.APPWIDGET_UPDATE -n <provider>`
   会被拒绝**（`SecurityException: not allowed to send broadcast ... from unknown caller`），
   所以脚本无法从外部强制重绘小组件。要触发重绘只能走用户路径：点小组件上的「刷新」，
   或让后台闹钟到点。`assert-widget-slots.ps1` 因此用 `uiautomator` 定位「刷新」文本再 `input tap`。

`MainActivity` 的 `exported=false`，`adb` 无法直接启动详情页，只能从账户列表点击进入；
可被 `adb` 直接启动的只有 `AccountListActivity` 与 `WidgetConfigActivity`。

应用目前**没有任何 `android.util.Log` 调用**，所以应用自身的 logcat 输出为空属于正常，
真正值得看的是框架层记录：`ActivityTaskManager: START`（真的启动了哪个 Activity）与
`E AndroidRuntime`（崩溃）。

查看设备上的数据库：

```powershell
.\tools\smoke\dump-db.ps1
```

查看当前界面控件树（坐标、id、clickable）：

```powershell
.\tools\smoke\dump-ui.ps1 -All
```

## 目录结构

```text
.
├── AI-Usage-Monitor-Development-Plan.md   演进计划（Spec）
├── docs/PHASE-0-2-PLAN.md                  Phase 0～2 实施计划
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── debug/                          仅 debug 变体：PinWidgetActivity
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/aiusage/monitor/
│       │   │   ├── account/                账户模型、排序、凭据替换
│       │   │   ├── auth/                   认证适配器与凭据存储
│       │   │   ├── model/                  Balance / Metric / UsageResult / UsageError
│       │   │   ├── provider/               ProviderRegistry + deepseek 实现
│       │   │   ├── refresh/                刷新策略、刷新管理器、节假日更新
│       │   │   ├── storage/                SQLite、Keystore、旧数据迁移
│       │   │   ├── ui/                     详情页与 UiKit
│       │   │   ├── ui/account/             账户列表与账户编辑
│       │   │   ├── usage/                  快照仓库与编解码
│       │   │   ├── util/                   Http / Money / PeakTimeUtils
│       │   │   └── widget/                 Widget 渲染、配置与调度
│       │   └── res/
│       └── test/java/com/aiusage/monitor/  宿主机 JVM 测试
├── tools/
│   ├── env.ps1                             工具链环境（唯一含本机路径的文件）
│   ├── dev.ps1                             一键 编译→安装→启动→看日志
│   └── smoke/                              设备冒烟测试与截图
└── gradle/libs.versions.toml               版本目录
```

## 安全说明

- 每个账户的 API Key 只发送给 `https://api.deepseek.com`。
- 勾选“记住密钥”时，密钥经 Android Keystore（AES-GCM）加密后存放在应用私有
  SQLite 中；硬件加密不可用时降级存储并在界面上提示。
- 从上游版本升级时（仅同包名覆盖安装可走导入；上游包名是 `com.deepseek.balance`，
  与本应用 `com.aiusage.monitor` 不同，覆盖安装不可行，需卸载重装，此时
  `LegacyMigration` 不会运行、也没有旧数据可读），导入逻辑仍会把旧
  `SharedPreferences` 明文密钥读入后立即删除，磁盘上不再保留明文副本。这是
  有意为之的安全取舍：把密钥移入加密存储后继续留在明文里，迁移就失去了意义；
  代价是回退到旧版本后需要重新输入密钥。早期计划曾写「旧键一律不删除」，与该
  取舍冲突，以本节为准。
- 本应用**不能**读取旧应用（`com.deepseek.balance`）的任何私有数据——Android
  沙箱按包名隔离应用私有存储，包名变了就是另一个应用。若未来要提供旧数据导入，
  需要另行设计（如导出/导入文件或桥接方案），并单独验收；当前没有这个功能。
- 不要将 API Key 分享给他人；如密钥泄露，请立即在开放平台删除并重新创建。
- 当前 APK 使用 Android 调试证书签名，适合个人安装使用。

## 版本沿革

| 版本 | 说明 |
| --- | --- |
| 3.32 | 上游基线（纯 SDK 工具链构建，`build.ps1`） |
| 3.33（code 56） | Phase 0：迁移到 Gradle，功能不变 |
| 4.0.0（code 57） | Phase 1：Provider / Account / Credential 分层，包名迁移到 `com.aiusage.monitor`，凭据迁入 Keystore + SQLite |
| 4.0.0（code 57） | Phase 2：多账户管理（增删改排序），Widget 按 `appWidgetId` 绑定单个账户 |
| 4.0.0（code 57） | Phase 3：Widget 改为账户 Slot 模型（`widget_slots` 表，schema v2），4×2 三 Slot Dashboard，显示更新时间与状态，Widget 内手动刷新；每 Slot 的指标选择仍待后续 |
| 4.0.0（code 57） | Phase 4：历史按账户 + 时间窗口读取（左闭右开），账户详情页「最近读数」，快照 14 天保留策略（永不删最后成功与最新一行） |
| 4.0.0（code 57） | Phase 5：Windows AI Usage Bridge（独立 Go module）——经 `codex app-server` 读 5 小时 / 周额度与重置时间，缓存 5 分钟，失败保留旧数据，只对本机提供 `/v1/*`；不读 Codex 凭据，Android 侧零改动 |

Phase 1 与 Phase 2 共用了 `versionCode 57`——两个阶段都未发布，未单独递增版本号。
