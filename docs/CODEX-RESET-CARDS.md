# Codex 使用限额重置卡

Codex 详情页在常规额度卡下方显示“使用限额重置”：可用次数、每张卡片的重置范围和到期日期。日期统一为 `yyyy-MM-dd HH:mm`，默认中国时区，再跟随设备本地时区；非中国时区用括号标明 UTC 偏移。过期卡片明确标记已到期，查询失败后的旧数据标记“上次读数”。

## 数据来源与兼容

电脑端使用已有只读 `account/rateLimits/read`，读取官方 `rateLimitResetCredits`，经 CodexLauncher Bridge `/v1/accounts/{accountId}/usage` 传到手机。

- `availableCount` 是服务端可用次数，不用卡片明细条数推算；服务端可能只返回部分明细。
- `credits: null` 表示只有次数、没有明细，不能当作没有卡片。
- `expiresAt: null` 表示没有到期限制；缺失或格式错误表示到期时间未知。
- 新版 Bridge 同时报告 `rateLimitResetCreditsStatus`：`AVAILABLE`（包括 0 次）、`NOT_RETURNED`、`INVALID_FORMAT`。手机分别显示有效次数、电脑端本次未返回或响应格式无法识别。
- 旧版 Bridge 没有次数和诊断字段时，手机提示检查电脑端版本并完整重启 Bridge。重新配对不会自动刷新；需要在 Codex 详情页查询额度。
- 数据进入 usage snapshot JSON，重启、缓存读取和 stale 副本均保留它，无需数据库迁移。
- 本功能展示信息；Bridge 保持原有只读权限，不传递用于兑换的原始卡片 ID。

官方字段说明：[Codex App Server](https://learn.chatgpt.com/docs/app-server)。本机 Codex CLI `0.162.0-alpha.2` 的协议导出和真实只读查询均确认了这些字段。

## Bridge 可选字段

`schemaVersion: 1` 增加可选的 `rateLimitResetCredits` 和 `rateLimitResetCreditsStatus`。状态字段位于 Bridge 响应顶层，枚举为 `AVAILABLE`、`NOT_RETURNED`、`INVALID_FORMAT`：

```json
{
  "availableCount": 1,
  "credits": [{
    "resetType": "codexRateLimits",
    "status": "available",
    "grantedAt": "2026-10-07T20:00:06Z",
    "expiresAt": "2026-11-06T20:00:06Z",
    "title": "Full reset (Weekly + 5 hr)",
    "description": null
  }]
}
```

这是合成示例。时间统一为 UTC RFC3339，手机转换到显示时区。电脑端实现位于 `codexlauncher/reset-card-info` 分支，基于已验证的 `codexlauncher/floating-desktop-mode`。实际显示这段信息需要手机 APK 和电脑端 Bridge 同时更新。

## 验证入口

最新 Android 全量检查及账户交互验收见 [2026-10-09 全局审查](REVIEW-2026-10-09.md)，
真实桌面额度及重置卡验收见 [额度桥联调记录](BRIDGE-INTEGRATION-2026-10-09.md)。下方为各阶段历史结果。

- Android：`ResetCreditsPipelineTest` 验证 Bridge 解析、缓存往返、stale 副本及旧协议兼容；`LimitResetWordsTest` 验证次数、到期日期、未知字段、跨时区和 DST。
- 电脑端：`ResetCreditTests` 验证次数与明细的独立性、UTC 日期、可选字段兼容、stale 数据、禁用监测和网络允许字段列表。
- 当前测试环境的原 Gradle 发行包缓存校验失败。本次使用独立的 `GRADLE_USER_HOME` 重新下载官方发行包，项目校验值未修改。

## 重置卡功能首次验证结果（后续日期格式调整未重跑测试）

- Android 完整单元测试 535 项通过，Debug APK 构建和 Lint 通过。
- 电脑端重置卡测试 5 项、Bridge 测试 38 项、额度相关测试 19 项均通过（测试集合存在交集）。
- 专用模拟器通过二维码连接合成 HTTPS Bridge，正确显示可用次数、范围和到期日期；切换到中国时区后日期正确更新。
- Bridge 离线并重启 App 后，卡片信息仍保留，标记“上次读数”。真实手机未连接，本次未安装到真实手机。

## 2026-10-08 诊断与账户列表更新

- Android `testDebugUnitTest`：538 项通过；`lintDebug` 和 `assembleDebug` 均通过。
- CodexLauncher 重置卡与 Bridge schema 目标用例通过。完整桌面测试在当前 Windows 沙箱中为 150/166；剩余用例因 OS 级文件、端口或加密权限返回 `Unauthorized operation`，不属于本次代码路径。
- 当前没有连接的 ADB 设备；Pixel 8 AVD 无法启动，因为其 Android 用户配置目录拒绝创建锁文件。滑动和拖动交互尚未在设备上做运行时演练。
- 数据库 v4 增加默认未置顶列；迁移只添加列，不重写账户、排序或历史数据。账户管理的组内排序与置顶逻辑由 AccountIdentityTest 覆盖。
