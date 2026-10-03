# AI Usage Monitor 开发计划

## 1. 项目背景

Fork 项目：

`kiuaah/DeepSeek-API-Balance-check-tool`

当前项目主要功能：

- DeepSeek API Key 配置
- DeepSeek CNY 余额查询
- 今日用量计算
- 前台自动刷新
- 后台定时刷新
- Android 桌面小组件
- 本地保存配置
- 原生 Java Android
- HttpURLConnection 网络请求
- RemoteViews Widget
- AlarmManager 后台任务
- 无第三方依赖

本次 Fork 不应只是在现有项目中“增加一个 Codex 页面”。

目标是将项目逐步重构为：

# AI Usage Monitor

一个支持：

- 多平台
- 多账户
- 多种认证方式
- 统一余额/额度模型
- Codex
- API 平台
- Windows Bridge
- ChatGPT 登录
- 可配置 Android Widget
- 历史使用记录

的通用 AI 额度监控工具。

---

# 2. 产品核心定位

产品的主要使用方式应为：

```text
Android App
=
配置中心

Android Widget
=
日常查看入口
```

正常情况下，用户不需要频繁打开 App。

用户主要通过 Widget 查看：

```text
Codex 5H 剩余额度
Codex Weekly 剩余额度
重置时间

DeepSeek余额
今日消费

OpenRouter余额

其他Provider额度
```

App 主要用于：

- 添加账户
- 删除账户
- 修改 API Key
- ChatGPT 登录
- 扫码连接 Windows Bridge
- 管理 Provider
- 管理 Widget
- 修改刷新频率
- 查看历史数据
- 管理设备

---

# 3. 总体架构原则

必须将以下四个概念彻底分离：

```text
Provider
Account
Credential
UsageResult
```

关系：

```text
Provider
    ↓
Account
    ↓
Credential / DataSource
    ↓
UsageResult
```

不要将：

```text
DeepSeek = API Key
Codex = Bridge
Claude = OAuth
```

写死。

同一个 Provider 未来必须可以支持多个认证方式。

例如：

```text
CodexProvider

├── DIRECT_CHATGPT
└── BRIDGE
```

---

# 4. Provider

Provider 表示一个服务平台。

例如：

```text
DeepSeek
Codex
OpenAI API
OpenRouter
Claude
Gemini
MiniMax
智谱
硅基流动
……
```

统一接口建议：

```java
interface UsageProvider {

    String getId();

    String getName();

    List<AuthType> getSupportedAuthTypes();

    ProviderCapabilities getCapabilities();

    UsageResult fetchUsage(
        Account account,
        AuthContext authContext
    );
}
```

Provider 负责：

1. 定义平台信息
2. 定义认证方式
3. 调用平台接口
4. 解析原始响应
5. 转换成统一 UsageResult

Provider 不负责：

- UI
- Widget
- 数据库
- Token 持久化
- SharedPreferences
- Widget 更新

---

# 5. Provider Registry

建立：

```text
ProviderRegistry
```

负责注册 Provider。

例如：

```java
registry.register(new DeepSeekProvider());
registry.register(new CodexProvider());
```

禁止在项目中大量出现：

```java
if (provider == DEEPSEEK)

else if (provider == CODEX)

else if (provider == OPENROUTER)
```

新增 Provider 时，应尽量只需要：

1. 新建 Provider
2. 注册 Provider
3. 定义认证配置
4. 定义可展示指标

不得要求大规模修改：

- MainActivity
- Widget
- AccountManager
- RefreshManager

---

# 6. Account：实现同平台多账户

必须支持：

```text
DeepSeek
├── 个人
├── 工作
└── 测试

Codex
├── 个人 Plus
├── 工作 Pro
└── 备用账号
```

Account 建议：

```text
Account

id
providerId
displayName
authType
credentialId
bridgeId
enabled
sortOrder
createdAt
updatedAt
```

例如：

```text
providerId = deepseek
displayName = DeepSeek个人
credentialId = cred_001
```

另一个账户：

```text
providerId = deepseek
displayName = DeepSeek工作
credentialId = cred_002
```

同一个 Provider 可以拥有任意数量 Account。

---

# 7. Credential

敏感认证信息不得直接写入 Account。

建立：

```text
Credential
```

支持：

```text
API_KEY
BRIDGE_TOKEN
OAUTH
COOKIE
CUSTOM
```

建议结构：

```text
Credential

id
type
encryptedPayload
createdAt
updatedAt
```

API Key 示例：

```json
{
  "apiKey": "..."
}
```

OAuth 示例：

```json
{
  "accessToken": "...",
  "refreshToken": "...",
  "expiresAt": 123456
}
```

Bridge：

```json
{
  "deviceToken": "..."
}
```

敏感信息应使用 Android Keystore 保护。

禁止：

- API Key 明文写数据库
- Token 明文 SharedPreferences
- Token 写日志
- Token 放进二维码

---

# 8. Auth Adapter

认证机制建议独立：

```text
AuthAdapter
```

例如：

```text
ApiKeyAuthAdapter
OAuthAuthAdapter
BridgeAuthAdapter
```

Provider 只获取 AuthContext。

Provider 不负责 Token 如何保存和刷新。

---

# 9. UsageResult：统一不同平台的数据

不同平台数据形态不同。

DeepSeek：

```text
人民币余额
```

Codex：

```text
5H百分比
Weekly百分比
Reset
```

OpenRouter：

```text
美元余额
```

因此不得简单定义：

```java
double balance;
```

建议：

```text
UsageResult

accountId
providerId
balance
quotaWindows[]
metrics[]
status
updatedAt
source
```

---

# 10. Balance

```text
Balance

amount
currency
```

例如：

```text
38.52 CNY
17.82 USD
```

---

# 11. QuotaWindow

用于 Codex 等额度型服务。

```text
QuotaWindow

id
label
usedPercent
remainingPercent
windowMinutes
resetAt
```

例如：

```text
5H

used = 32
remaining = 68
window = 300
```

以及：

```text
Weekly

used = 58
remaining = 42
```

不要假定：

```text
primary = 5H
secondary = Weekly
```

应根据 API 实际窗口信息识别。

---

# 12. Metric

无法归入 Balance / Quota 的信息统一使用：

```text
Metric
```

结构：

```text
key
label
value
unit
```

例如：

```text
today_usage
今日使用
1.28
CNY
```

以后可以扩展：

- Tokens
- Requests
- Cache Hit
- Credits
- Yesterday Usage
- Monthly Cost

而不用修改 UsageResult 数据结构。

---

# 13. 第一阶段：重构现有 DeepSeek

第一阶段禁止加入 Codex。

目的：

证明新架构可靠。

将当前 DeepSeek 功能迁移至：

```text
DeepSeekProvider
```

同时建立：

```text
Account
Credential
ProviderRegistry
UsageResult
UsageRepository
AccountRefreshManager
```

必须保持原有功能：

- 查询余额
- 今日用量
- 自动刷新
- 手动刷新
- Widget
- 峰谷信息

第一阶段增加：

# DeepSeek 多账户

例如：

```text
DeepSeek个人
DeepSeek工作
```

分别绑定两个 API Key。

必须做到：

- 数据独立
- 刷新独立
- 今日用量独立
- Widget 可独立选择
- 一个账户报错不影响另外账户

---

# 14. 第一阶段验收

必须证明：

```text
DeepSeek个人
API Key A

DeepSeek工作
API Key B
```

可以同时存在。

修改 API Key 后：

- Account ID 不变
- 历史数据不丢失
- Widget 配置不丢失

新增第三个 DeepSeek Account 时：

不得修改 DeepSeekProvider。

---

# 15. Codex Provider

完成基础架构之后，再开发：

```text
CodexProvider
```

Codex 必须支持两种数据通道：

```text
CodexProvider

├── DirectChatGPTDataSource
│
└── BridgeCodexDataSource
```

不要开发两个独立 Provider：

```text
CodexDirectProvider
CodexBridgeProvider
```

它们属于同一个 Codex Provider 的不同 DataSource。

---

# 16. Codex Direct 模式

目标：

```text
Android
↓
Sign in with ChatGPT
↓
OAuth
↓
Codex数据
```

认证方式：

```text
DIRECT_CHATGPT
```

使用官方 ChatGPT OAuth / Sign in with ChatGPT。

不得：

- 读取 ChatGPT Android App Cookie
- 偷取浏览器 Cookie
- 要求用户复制 ChatGPT Session
- 要求输入 OpenAI 密码

需要处理：

```text
Access Token
Refresh Token
Token刷新
Token失效
重新授权
多个ChatGPT账户
```

注意：

如果当前公开 OAuth 能获取的 Codex Usage 数据字段不完整：

不得依赖未经稳定确认的内部接口作为唯一实现。

必须通过：

```text
Capabilities
```

声明 Direct 当前实际支持什么。

例如：

```text
exactQuota = false
resetTime = false
credits = false
```

以后官方接口完善时，只修改 DirectChatGPTDataSource。

---

# 17. Windows AI Usage Bridge

第二种 Codex 数据源：

```text
BRIDGE
```

架构：

```text
Android
↓
AI Usage Bridge
↓
Codex App Server
↓
account/rateLimits/read
```

Bridge 不应命名：

```text
Codex Bridge
```

建议名称：

```text
AI Usage Bridge
```

因为未来还可能支持：

- Claude Code
- Gemini CLI
- 其他桌面 Agent

---

# 18. Bridge 功能

Windows Bridge 第一版负责：

1. 检测 Codex
2. 检测登录状态
3. 获取 Codex rate limits
4. 缓存最新数据
5. 提供本地 HTTP API
6. 手机配对
7. 管理授权设备
8. 系统托盘运行
9. 开机自动启动
10. 局域网自动发现

Bridge 应尽量做到：

```text
安装一次
↓
开机自启
↓
后台托盘
↓
长期无感运行
```

---

# 19. Bridge API

API 不应暴露 Codex 原始认证信息。

例如：

```text
GET /v1/providers
```

```text
GET /v1/accounts/{id}/usage
```

```text
POST /v1/pair
```

返回统一：

```text
UsageResult
```

不得返回：

- ChatGPT Access Token
- Refresh Token
- Cookie
- Codex auth.json

---

# 20. 二维码配对

Bridge 点击：

```text
添加设备
```

生成二维码。

二维码包含：

```text
Bridge ID
局域网地址
端口
一次性Pair Token
Server Fingerprint
```

例如概念：

```text
aiusage://pair
```

Pair Token：

- 一次性
- 短时间有效
- 成功后立即失效

禁止二维码包含：

- OpenAI Token
- ChatGPT Token
- Cookie
- API Key

---

# 21. 长期设备认证

扫码成功后：

```text
Pair Token
↓
Device Token
```

手机保存：

```text
Device Token
Bridge ID
Server Fingerprint
```

以后无需重新扫码。

只有以下情况需要重新扫码：

- App 数据清除
- App 重新安装
- Bridge 数据重置
- 用户解除授权
- 用户主动重置所有设备

Codex 登录失效：

不应该要求手机重新扫码。

手机与 Bridge 配对和 Bridge 与 OpenAI 登录是两套认证。

---

# 22. Bridge 自动发现

不要永久依赖：

```text
192.168.x.x
```

Account 应绑定：

```text
Bridge ID
```

Bridge 地址变化后，通过：

```text
mDNS / LAN Discovery
```

重新找到对应 Bridge。

用户体验：

```text
第一次扫码
↓
以后自动找到电脑
```

---

# 23. Codex AUTO 模式

Codex Account 支持：

```text
DIRECT
BRIDGE
AUTO
```

AUTO 推荐设计：

```text
优先使用 Direct
↓
Direct 缺少的数据由 Bridge 补齐
↓
Bridge 离线时保留 Direct 数据
↓
完整数据不可用时显示最后一次完整 Snapshot
```

例如：

```text
Codex个人

Direct ChatGPT
✓

Bridge
DESKTOP-HOST
✓

Mode
AUTO
```

---

# 24. Codex Capabilities

每个 DataSource 返回：

```text
CodexCapabilities
```

例如：

```text
supportsExactQuota
supportsResetTime
supportsCredits
supportsPlan
```

Widget 和 UI 不得假定所有 DataSource 都提供全部字段。

---

# 25. UsageRepository

建立唯一数据源：

```text
UsageRepository
```

所有：

- App
- Widget
- 历史记录
- 后台刷新

都从这里读取数据。

禁止：

```text
Widget自己请求Provider
```

也禁止：

```text
MainActivity自己请求DeepSeek
```

---

# 26. UsageSnapshot

从第一版就建立：

```text
usage_snapshots
```

即使暂时不做图表也要保存数据。

建议：

```text
id
accountId
timestamp
usageData
source
success
```

用途：

以后实现：

- Codex 消耗曲线
- 每日 API 使用金额
- 周额度消耗
- 使用速度
- 预计耗尽时间
- 历史统计

---

# 27. Widget 是核心功能

Widget 不是附属功能。

目标：

```text
日常看Widget
配置才进App
```

Widget 不直接：

- 调 DeepSeek API
- 调 Bridge
- 保存 API Key
- 保存 Token
- 解析 Provider 原始响应

Widget：

```text
UsageRepository
↓
UsageResult
↓
显示
```

---

# 28. WidgetConfig

每一个 Widget 保存自己的：

```text
WidgetConfig
```

建议：

```text
widgetId
widgetType
accountIds
metricIds
layoutMode
density
refreshInterval
sortOrder
theme
```

Widget 必须绑定：

```text
Account ID
```

不能只绑定：

```text
Provider ID
```

因为同平台支持多账户。

---

# 29. Widget 尺寸规划

## 2×1

极简模式。

适合：

```text
一个账户
一个主要指标
```

例如：

```text
Codex Plus
5H 68%
```

---

## 2×2

主要作为：

```text
单平台
单账户
```

例如：

```text
Codex · Plus

5H
███████░░ 68%

Week
████░░░░░ 42%

2h17m 后重置
```

DeepSeek：

```text
DeepSeek个人

¥38.52

今日 ¥1.28

12:31更新
```

用户可以自定义显示指标。

---

# 30. 4×2

作为：

```text
Dashboard Widget
```

可以配置：

## 多平台

```text
Codex个人
5H 68% / W 42%

DeepSeek
¥38.52

OpenRouter
$17.82
```

也可以：

## 单平台多账户

```text
Codex

个人
68% / 42%

工作
91% / 73%
```

不得将：

```text
多平台
```

和：

```text
多账户
```

设计成两种完全不同 Widget。

本质都是：

```text
多个Account Slot
```

---

# 31. 2×4

用于更详细 Dashboard。

可以显示：

```text
3~4个账户
```

并增加：

- Progress Bar
- Reset
- 更新时间
- 今日用量

---

# 32. 4×4

后期支持完整 Dashboard：

```text
4~6个账户
```

用于重度用户。

不是 V1 必须项。

---

# 33. Slot 模型

Widget 建议采用：

```text
Slot
```

例如 4×2：

```text
Slot 1
Codex个人
5H + Weekly

Slot 2
DeepSeek工作
余额 + 今日用量

Slot 3
OpenRouter
余额
```

因此用户可以自由组合：

```text
多平台
```

或者：

```text
同平台多个账户
```

Widget 不需要理解这些区别。

---

# 34. Widget Metric

Provider 应公开：

```text
WidgetMetric
```

例如 Codex：

```text
quota_5h_remaining
quota_weekly_remaining
quota_5h_reset
quota_weekly_reset
credits
```

DeepSeek：

```text
balance
today_usage
yesterday_usage
```

用户配置的是：

```text
Account ID
+
Metric ID
```

---

# 35. Widget 配置页面

添加 Widget 后：

```text
配置AI Usage Widget
```

选择：

```text
单账户
多账户
自定义Dashboard
```

然后：

```text
选择账户

☑ Codex个人
☑ DeepSeek工作
☐ Codex工作
```

可调整顺序。

单账户 Widget：

```text
主要指标
次要指标
底部指标
```

---

# 36. Widget 点击行为

建议：

```text
点击刷新图标
→ 立即刷新Widget对应账户

点击Account
→ 打开该账户详情

点击标题
→ 打开App

长按
→ 系统Widget配置
```

Widget 手动刷新：

必须调用：

```text
AccountRefreshManager
```

不能自己调用 Provider。

---

# 37. AccountRefreshManager

统一刷新系统：

```text
AccountRefreshManager
```

逻辑：

```text
Account
↓
ProviderRegistry
↓
CredentialStore
↓
AuthAdapter
↓
Provider
↓
UsageResult
↓
UsageRepository
↓
UsageSnapshot
↓
WidgetUpdateManager
```

App 前台刷新：

使用同一套流程。

Widget 手动刷新：

使用同一套流程。

后台刷新：

使用同一套流程。

---

# 38. WidgetUpdateManager

建立：

```text
WidgetUpdateManager
```

职责：

```text
updateWidget(widgetId)

updateWidgetsForAccount(accountId)

updateAllWidgets()
```

一个 Account 更新后：

所有引用该 Account 的 Widget 同步更新。

---

# 39. Widget 离线策略

绝对不能：

```text
刷新失败
↓
清空Widget
```

必须保留：

```text
lastSuccessfulResult
```

例如：

```text
Codex Plus

5H 68%
Week 42%

⚠ 电脑离线
上次更新 2小时前
```

---

# 40. Widget 状态

统一：

```text
OK
REFRESHING
STALE
NETWORK_ERROR
BRIDGE_OFFLINE
AUTH_REQUIRED
NO_DATA
```

需要区分：

```text
电脑离线
```

和：

```text
Codex需要重新登录
```

这是两个完全不同问题。

---

# 41. Widget 数据新鲜度

Widget 必须显示：

```text
刚刚
5分钟前
12:31
2小时前
```

超过一定时间：

显示 stale 状态。

用户必须知道：

```text
68%
```

是不是实时数据。

---

# 42. 后台刷新

推荐默认：

```text
30分钟
```

用户可设置：

```text
15分钟
30分钟
1小时
2小时
仅手动
```

后台调度受 Android 系统限制。

必须允许：

```text
Widget手动刷新
```

作为可靠补充。

不要尝试后台：

```text
1秒
5秒
30秒
```

高频刷新。

---

# 43. DeepSeek 和 Codex 的刷新策略可以不同

Provider 可以提供：

```text
recommendedRefreshInterval
```

例如：

```text
Codex
15~30分钟

DeepSeek
30分钟
```

但最终允许用户覆盖。

---

# 44. App 首页

不要把首页设计成巨大 Dashboard。

建议导航：

```text
账户
Widget
历史
Bridge
设置
```

默认首页：

```text
账户列表
```

例如：

```text
Codex个人
Direct ✓
Bridge ✓

DeepSeek个人
¥38.52

DeepSeek工作
¥91.25
```

详细额度仍然可以查看。

---

# 45. 推荐目录结构

```text
com.xxx.aiusage

account/
    Account.java
    AccountRepository.java
    AccountManager.java

provider/
    UsageProvider.java
    ProviderRegistry.java
    ProviderCapabilities.java

provider/deepseek/
    DeepSeekProvider.java

provider/codex/
    CodexProvider.java
    CodexDataSource.java
    DirectChatGPTDataSource.java
    BridgeCodexDataSource.java
    CodexCapabilities.java

auth/
    AuthType.java
    Credential.java
    CredentialStore.java
    AuthAdapter.java
    ApiKeyAuthAdapter.java
    OAuthAuthAdapter.java
    BridgeAuthAdapter.java

bridge/
    BridgeDevice.java
    BridgeRepository.java
    BridgeClient.java
    PairingManager.java
    DiscoveryManager.java

model/
    UsageResult.java
    Balance.java
    QuotaWindow.java
    Metric.java
    UsageStatus.java
    UsageError.java

usage/
    UsageRepository.java
    UsageSnapshot.java

refresh/
    AccountRefreshManager.java
    RefreshPolicy.java

widget/
    WidgetConfig.java
    WidgetSlot.java
    WidgetMetric.java
    WidgetUpdateManager.java
    WidgetRenderer.java
    WidgetConfigActivity.java

ui/
    account/
    provider/
    widget/
    bridge/
    history/

storage/
    Database.java
    SecureStorage.java
```

第一版不要求拆成多个 Gradle Module。

先保持项目易维护。

---

# 46. Windows Bridge 项目结构

建议独立项目：

```text
ai-usage-bridge/
```

模块：

```text
codex/
network/
pairing/
device/
cache/
discovery/
tray/
settings/
```

Bridge 与 Android App 使用版本化协议：

```text
/v1/
```

以后协议升级：

```text
/v2/
```

---

# 47. Bridge 缓存策略

避免每次 Widget 请求都调用 Codex。

例如：

```text
缓存 < 5分钟
→ 返回缓存

缓存过期
→ 请求Codex

用户强制刷新
→ 忽略缓存
```

Bridge 返回：

```text
dataTimestamp
sourceTimestamp
```

方便 Android 判断数据是否过期。

---

# 48. 多电脑支持

BridgeDevice 必须独立于 Codex Account。

例如：

```text
家里电脑
公司电脑
```

Codex Account：

```text
个人 Plus
→ 家里电脑

工作 Pro
→ 公司电脑
```

以后一个 Bridge 可以同时提供：

```text
Codex
Claude
Gemini
```

---

# 49. 错误模型

统一：

```text
INVALID_CREDENTIAL
AUTH_EXPIRED
NETWORK_ERROR
RATE_LIMITED
SERVICE_UNAVAILABLE
BRIDGE_OFFLINE
BRIDGE_UNAUTHORIZED
UNSUPPORTED
UNKNOWN
```

UI 根据错误类型展示。

Provider 原始错误不得直接暴露到 Widget。

---

# 50. 安全要求

必须遵守：

1. API Key 不进入日志
2. OAuth Token 不进入日志
3. Bridge Token 不进入日志
4. 二维码不含 OpenAI Token
5. Bridge 不向手机暴露 Codex Token
6. Android Sensitive Credential 使用 Keystore
7. Pair Token 短期、一次性
8. Device Token 可撤销
9. Bridge 支持设备列表
10. 用户可以删除单个授权设备
11. 支持重置所有设备
12. Server Fingerprint 用于识别 Bridge
13. 不允许未授权 LAN 设备读取 Usage

---

# 51. 开发阶段顺序

必须严格分阶段。

不要一次性重构全部功能。

---

# Phase 0：Fork 与基线

目标：

保证原项目可以：

```text
编译
安装
运行
查询DeepSeek
Widget正常
```

建立基线测试。

禁止立即重构。

---

# Phase 1：基础架构重构

实现：

```text
Provider
ProviderRegistry
Account
Credential
UsageResult
UsageRepository
AccountRefreshManager
```

将 DeepSeek 迁移为：

```text
DeepSeekProvider
```

UI 功能暂时保持基本不变。

验收：

原有功能无明显回归。

---

# Phase 2：DeepSeek 多账户

实现：

```text
多个DeepSeek Account
```

增加：

```text
添加账户
删除账户
重命名
修改Key
账户排序
```

验收：

至少两个 API Key 独立工作。

---

# Phase 3：Widget 重构

将现有 DeepSeek 专用 Widget 重构成：

```text
通用 Account Widget
```

实现：

```text
2×1
2×2
4×2
2×4
```

优先完成：

```text
2×2
4×2
```

验收：

同一个 Widget 系统同时支持：

```text
DeepSeek个人
DeepSeek工作
```

---

# Phase 4：UsageSnapshot

加入：

```text
历史Snapshot
```

暂时不必实现复杂图表。

确保刷新成功后保存数据。

---

# Phase 5：Windows AI Usage Bridge MVP

先不要做二维码。

先实现：

```text
Codex
↓
Bridge
↓
localhost API
```

确认可以读取：

```text
5H
Weekly
Reset
```

---

# Phase 6：Android Bridge 接入

Android 手动填写：

```text
IP
Port
Temporary Token
```

先打通：

```text
Android
↓
Bridge
↓
Codex
```

确认架构正确。

---

# Phase 7：二维码配对

加入：

```text
Pair Token
Device Token
QR Code
Fingerprint
Device Management
```

删除手动 IP 配置作为主要流程。

手动连接保留用于调试。

---

# Phase 8：局域网自动发现

加入：

```text
Bridge ID
mDNS
LAN Discovery
```

配对以后自动找到电脑。

---

# Phase 9：Codex Direct Login

实现：

```text
Sign in with ChatGPT
OAuth
Token Refresh
Multiple ChatGPT Accounts
```

先实现认证和 Capability。

不要为了获取 Usage 强依赖未确认稳定的内部接口。

---

# Phase 10：Codex AUTO

实现：

```text
DIRECT
BRIDGE
AUTO
```

AUTO：

```text
Direct优先
Bridge补齐
离线缓存fallback
```

---

# Phase 11：Widget 完整支持 Codex

2×2：

```text
单Codex账户
```

4×2：

```text
多平台
或
多Codex账户
```

支持：

```text
Metric选择
顺序
Slot
刷新
数据状态
```

---

# Phase 12：第三方 Provider 验证

选择一个新的 API 平台，例如：

```text
OpenRouter
```

通过增加：

```text
OpenRouterProvider
```

验证扩展性。

验收标准：

添加 OpenRouter 不应该要求修改：

```text
DeepSeekProvider
CodexProvider
Widget核心
Account核心
RefreshManager核心
```

---

# 52. 最终架构

```text
                        Android UI
                             │
                        AccountManager
                             │
                    AccountRefreshManager
                             │
          ┌──────────────────┴──────────────────┐
          │                                     │
   ProviderRegistry                      CredentialStore
          │
 ┌────────┼─────────┐
 │        │         │
DeepSeek Codex   OpenRouter
 │        │
API      CodexProvider
          │
    ┌─────┴────────┐
    │              │
 Direct          Bridge
    │              │
ChatGPT       AI Usage Bridge
                   │
                Codex
```

刷新后：

```text
UsageResult
     ↓
UsageRepository
     ↓
UsageSnapshot
     ↓
 ┌───┴─────┐
 │         │
App       Widget
```

---

# 53. Agent 开发规则

Agent 必须遵守：

1. 先理解原项目再修改。
2. 每个阶段先制定具体修改计划。
3. 不允许一次性重写整个项目。
4. 每阶段完成后先编译。
5. 每阶段进行回归测试。
6. 原 DeepSeek 功能不能因为架构升级失效。
7. MainActivity 不直接请求 Provider。
8. Widget 不直接请求 Provider。
9. Widget 不直接读取 Token。
10. Provider 不直接操作 Widget。
11. Account 不直接保存明文 Credential。
12. 新 Provider 通过 ProviderRegistry 接入。
13. Provider 之间禁止互相依赖。
14. 同平台必须支持多账户。
15. Widget 必须按 Account ID 工作。
16. 一个 Account 可以出现在多个 Widget。
17. 一个 Widget 可以显示多个 Account。
18. 刷新失败不能清除最后一次成功数据。
19. Bridge 离线与 OAuth 失效必须区分。
20. Codex Direct 与 Bridge 必须共用 CodexProvider。
21. 任何内部 API 使用前必须明确其稳定性。
22. OAuth 认证和 Usage 获取必须分开设计。
23. Provider 返回统一 UsageResult。
24. 从第一版开始保留历史 Snapshot。
25. 不为了短期开发速度破坏扩展结构。

---

# 54. 不要做的事情

不要：

```text
先硬塞Codex进MainActivity
```

不要：

```text
复制现有DeepSeek Widget然后写CodexWidget
```

不要：

```text
每个平台一个独立Activity + 一套刷新系统
```

不要：

```text
Provider == AuthType
```

不要：

```text
Codex Bridge 和 Codex Direct 做成两个Provider
```

不要：

```text
Widget直接访问OpenAI
```

不要：

```text
二维码携带OpenAI认证信息
```

不要：

```text
为了实时Widget每分钟后台轮询
```

不要：

```text
刷新失败以后清空旧数据
```

不要：

```text
把IP地址当Bridge永久身份
```

不要：

```text
把内部未公开API当稳定公开API设计核心架构
```

---

# 55. V1 建议范围

V1 发布目标：

## DeepSeek

- 多账户
- API Key
- 余额
- 今日用量

## Codex

- Bridge
- 5H
- Weekly
- Reset
- 多账户基础
- 二维码配对

## Widget

- 2×2 单账户
- 4×2 Dashboard
- 多平台
- 同平台多账户
- 手动刷新
- 自动刷新
- 最后更新时间
- Offline Cache

## 基础框架

- Provider
- Account
- Credential
- UsageResult
- UsageSnapshot

Direct ChatGPT OAuth 如果实现风险较高，可以作为 V1.1。

---

# 56. V1.1

增加：

```text
Sign in with ChatGPT
Codex Direct
Codex AUTO
2×4 Widget
```

---

# 57. V2

增加：

```text
更多Provider
历史曲线
额度消耗速度
预计耗尽时间
低额度提醒
Reset提醒
4×4 Widget
Bridge多Provider
远程访问方案
```

---

# 58. 最终验收标准

项目架构最终必须能够做到：

### Case 1

增加第三个 DeepSeek API Key：

```text
只新增Account
```

不修改 Provider。

### Case 2

增加 OpenRouter：

```text
新增OpenRouterProvider
```

不修改现有 Provider。

### Case 3

Codex 从 Bridge 切换到 Direct：

```text
Widget不需要修改
```

### Case 4

Codex Direct 暂时缺少 Reset：

```text
Widget正常显示已有数据
```

不会崩溃。

### Case 5

电脑关机：

```text
Widget保留上次Codex数据
显示Bridge Offline
```

### Case 6

Codex OAuth 失效：

```text
Bridge配对仍然有效
```

### Case 7

一个 4×2 Widget：

```text
Codex个人
DeepSeek工作
OpenRouter
```

可以同时显示。

### Case 8

另一个 4×2 Widget：

```text
Codex个人
Codex工作
```

也可以正常显示。

如果以上场景成立，说明项目已经从：

```text
DeepSeek Balance Tool
```

真正升级成：

```text
AI Usage Monitor
```

并具备后续长期扩展能力。
