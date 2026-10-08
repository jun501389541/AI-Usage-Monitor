# CodexLauncher 手机联调契约

2026-10-08：安卓线程负责实现兼容客户端，桌面线程保留现有 HTTPS 邀请及本机审批协议。
电脑工作树：`E:/Workspace/project/CodexLauncher/analysis/worktrees/fix-high-priority`。

- QR：`https://host:port/v1/device#<percent-encoded JSON>`。
- 字段：schemaVersion=1、bridgeId(UUID)、endpoint(同源 HTTPS)、certificateSha256(证书 DER 摘要)、pairToken(base64url 32 bytes)、expiresAt。
- 手机先钉证书，再 POST /v1/pair，202 返回 pairId/sessionToken；GET /v1/pair/{pairId} 带 session Bearer，等待本机批准。
- Approved 返回 deviceId/deviceToken；手机加密保存令牌与远端账户 ID，然后 POST /v1/pair/{pairId}/ack。
- GET /v1/accounts 带设备 Bearer，读取授权的 codex 账户；GET /v1/accounts/{accountId}/usage 返回 quotaWindows/ISO 时间。
- 手机使用 `cert-sha256:` 显式区分证书 pin 和旧 Go Bridge SPKI pin，不降级为忽略证书校验。
- 原 Go Bridge aiusage://pair 和 state.windows 继续支持。

## 已完成验证

- 手机端全量单元测试 526 项通过，Debug APK 构建、安装成功。
- 使用桌面项目真实 Kestrel HTTPS 与配对服务，在 Pixel8_API36 模拟器相册中选择电脑生成的二维码图片。
- 手机识别邀请并发送申请，界面显示等待电脑批准；测试服务延迟约 3 秒调用本机批准接口。
- 手机领取设备令牌、读取获准账户、加密保存凭据后发 ACK，账户名为 `CodexLauncher_Test`。
- 查询显示 5 小时剩余 78%、每周剩余 19%，与测试服务合成数据一致。
- 强制停止 App 并重新打开后，原账户仍能查询相同额度；没有再次扫码。
- “已配对的电脑”中保留 CodexLauncher、HTTPS 地址和正确的证书指纹尾号。
- 用户随后在已安装新版的真机上确认成功连接 Bridge 并读取 Codex 额度，补齐了真实设备连接验收。
- 模拟器阶段使用隔离测试服务和合成额度；本次未采集真实账户额度或令牌。
- 真机的相机扫码过程和局域网/防火墙条件没有单独记录，不据此宣称相机与各种网络配置都已覆盖。

本机证据在仓库外 `.debug-runtime/launcher-paired-usage.xml`、`launcher-restart-usage.xml`、`launcher-paired-computers.xml`、`launcher-paired.png`。
测试二维码不提交到仓库，日志不打印邀请或设备令牌。

验收期间两个对话同时运行 UIAutomation 导致一次工具进程冲突；重新串行导航后完成电脑记录检查。
该错误来自 `com.android.commands.uiautomator`，不能作为 App 崩溃证据。

## 正式使用

1. 电脑端选择推荐的实体 WLAN/局域网地址，应用监听设置并启用手机共享；登录 Codex 并开启额度监测。
2. 在电脑端“添加设备”生成二维码。手机和电脑处于同一局域网。
3. 安装新的 Android APK，添加账户选择 OpenAI Codex，点击“扫码连接电脑（推荐）”。
4. 手机扫码后，回到电脑端批准该设备申请；手机自动保存账户并查询额度。
5. 若登录的 Codex 账户变化，需在电脑重新授权并重新配对；手机不会自动跳到另一账户读取数据。
