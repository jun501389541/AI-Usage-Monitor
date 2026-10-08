# 当前电脑的调试入口

2026-10-07 已在本机完成 Android 模拟器与 Codex Bridge 联调。

## 启动与查看日志

在 PowerShell 中执行：

```powershell
Set-Location E:\Workspace\project\AI-Usage-Monitor\repo

# 复用本次准备的 Go 二进制，启动回环地址上的 Bridge；已运行时直接返回。
& ..\.debug-runtime\start-bridge.ps1

# 复用已安装的 APK，打开 App 并查看本次错误日志。
& ..\.debug-runtime\debug-app.ps1 -NoBuild -ErrorsOnly

# 修改代码或资源后，重新构建、安装、启动。
& ..\.debug-runtime\debug-app.ps1

# 持续查看日志（Ctrl+C 退出日志跟踪）。
& ..\.debug-runtime\debug-app.ps1 -NoBuild -Follow
```

这些辅助脚本位于仓库之外的本机 `.debug-runtime` 目录，不随 Git 提交。
它们使用隔离的 Gradle 缓存，避免全局镜像脚本与项目仓库设置冲突。
`-NoBuild` 不会应用新的代码或资源改动。

模拟器为 `Pixel8_API36`。若已关闭，可重新启动：

```powershell
Start-Process E:\Android\sdk\emulator\emulator.exe -ArgumentList '-avd','Pixel8_API36'
```

App 中已添加 `Codex_Debug` 调试账户，地址为 `http://10.0.2.2:38411`。
点击账户进入详情，再点“查询额度”即可刷新。电脑上的 Bridge 健康检查地址为
`http://127.0.0.1:38411/v1/health`。此 HTTP 入口用于本机模拟器联调；真机使用正式配对流程。

## 本次环境

- Android SDK：`E:\Android\sdk`；JDK：`E:\Android\jdk21`。
- Gradle：项目 Wrapper 9.1.0；缓存：`..\.debug-runtime\gradle-home`。
- Go：`..\.debug-runtime\toolchain\go\bin\go.exe`（1.27.1）。
- Bridge 数据与日志：`..\.debug-runtime\bridge-data`、`bridge.stdout.log`、`bridge.stderr.log`。
- Android 检查日志：`..\.debug-runtime\android-checks.log`。

Bridge 源码修改后需重新构建二进制并重启 Bridge；启动脚本不会自动重建。

## 验证结果

- Debug APK 已构建、安装并启动。
- Android 单元测试：517 条全部通过；Lint：0 错误、41 条警告。
- Bridge：`go test -count=1 ./...` 与 `go vet ./cmd/... ./internal/...` 通过。
- 模拟器中已显示真实的 5 小时与每周额度，手动刷新成功。
- 本次运行的 App 进程日志未发现 `FATAL EXCEPTION` 或 ANR。

本次没有验证真机 TLS 配对、后台长时间刷新或桌面 Widget 的完整流程。

## 手机扫码连接（2026-10-07）

- 添加 Codex 账户的主入口为“扫码连接电脑（推荐）”，点击直接进入内置扫码页面。
- 支持相机扫码及系统图片选择器，识别在手机本机完成；复用现有配对内容校验和 TLS 配对客户端。
- 已构建并安装最新 Debug APK；模拟器中检查了入口、首次权限请求、拒绝后重试授权及相册入口，未发现崩溃。
- 电脑端已有配对二维码，本次没有修改电脑端。
- 本次未新增或运行单元测试，未验证真机摄像头识别和完整扫码 TLS 配对。

## CodexLauncher 跨项目配对（2026-10-08）

已补兼容并验证模拟器相册二维码识别、HTTPS、等待桌面测试批准、保存设备令牌、ACK、读取授权额度及 App 重启后继续查询。
全量 Android 单元测试 526 项通过。用户随后确认已安装新版的真机能够连接 Bridge 并读取 Codex 额度。
真机相机扫码过程与具体局域网/防火墙条件没有单独记录。
具体接口差异、证据与使用方法见 [CodexLauncher 联调记录](CODEXLAUNCHER-PAIRING.md)。
