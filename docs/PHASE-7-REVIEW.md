# Phase 7 Bridge 半边复审

日期：2026-10-03。复审范围：计划提交 `296c513` 至当前 HEAD `cbab79e`，即 Phase 7 步骤 1–4 的 Bridge 端实现。工作区没有未提交的产品代码。此轮为只读代码审查；没有修改产品代码，也没有运行测试。

## 结论

Bridge 身份、一次性配对、TLS/令牌门和 CLI 已有实现，计划记录了 Go 门禁与一次完整进程实测。发现四项后端问题，以及一个 Android 集成前必须修正的 TLS 计划缺口。Phase 7 尚未完成：Android 步骤 5–10、手机端指纹固定、数据库迁移和配对 UI 均未开始。

## 发现

### P1：计划中的 Android TLS 主机名校验会拒绝 LAN 证书

位置：`bridge/internal/identity/identity.go:174-188`；`docs/PHASE-7-PLAN.md:92`。

服务器证书没有 SAN 中的 LAN 地址，CN 是随机 Bridge ID。计划要求 App 用自定义 `X509TrustManager` 校验 SPKI 指纹，同时保留 `HttpsURLConnection` 默认行为。Android 文档说明，`HttpsURLConnection` 在 TLS 握手成功后仍用 `HostnameVerifier` 对 URL 主机名做校验；信任管理器的证书校验不会替代这一步。手机连接 `https://<LAN-IP>` 时，即使 SPKI 与已配对指纹一致，默认主机名校验仍会因证书不含该 IP 而失败。参见 [Android HttpsURLConnection API](https://developer.android.com/reference/javax/net/ssl/HttpsURLConnection)。

在开始 Android 步骤 7 前，计划应明确逐连接的 hostname-verifier 策略：只允许该连接呈现的证书 SPKI 等于配对时保存的指纹，并保留现有 trust-manager 钉证；不能使用全局的无条件 `return true`。补充 LAN IP 正向握手、错指纹在发出 HTTP 请求前拒绝的测试，再执行 P-1/P-2。

### P2：配对地址、监听地址和 CLI 管理路径不一致

位置：`bridge/cmd/aiusage-bridge/main.go:54,95-116,130-139`；`bridge/internal/server/server.go:108-120`；`bridge/internal/server/admin.go:18-27`。

默认 `--host` 是 `127.0.0.1`，但默认 `--advertise` 会把探测到的 LAN IP 放进配对 payload；服务只检查 advertise 列表非空，不验证这些地址是否真由 listener 提供。于是默认 `--pair` 会把无法连接到的 LAN 地址交给手机。模拟器把 loopback 改写为 `10.0.2.2` 可以作为单独的客户端规则，但不能使未绑定的 LAN 地址变得可达。

使用计划所写的 `--host <LAN-IP>` 时，服务只绑定该地址；启动日志建议的 `--add-device` 命令省略 `--host`，因此会连 `127.0.0.1` 并失败。若给管理命令传 LAN IP，它的来源地址也会是 LAN 地址，随后被管理员路由的 loopback 检查拒绝。日志命令还省略自定义 `--data-dir`，此时管理进程会读默认身份目录并无法通过指纹校验。

修正启动/管理流程，使配对 payload 中的地址确实可达、管理请求始终从 loopback 到达，并把运行实例的 `--data-dir` 及所需参数带入给出的命令。加入默认 `--pair`、指定 LAN bind、wildcard bind、自定义数据目录四种配置的路径验收。

### P2：撤销落盘失败会被报成“设备不存在”，重启后令牌可能恢复

位置：`bridge/internal/pairing/pairing.go:266-273`；`bridge/internal/server/admin.go:108-110`。

`Revoke` 先修改内存中的 `Revoked`，再写文件。若写入失败，磁盘仍保留未撤销记录；管理处理器又把所有错误都映射成 404“no such device”。当前进程暂时拒绝该令牌，但重启后从旧文件加载，令牌会重新有效，而操作者收到的是设备不存在而非撤销失败。

让撤销以事务方式持久化，并在写失败时保留明确失败结果；仅把 `ErrUnknownDevice` 映射为 404，存储错误应返回服务错误。通过可注入写入失败验证：失败响应后重新打开 Store，令牌状态必须与接口报告一致。

### P2：配对猜测锁定没有持久化，重启可恢复旧短码

位置：`bridge/internal/pairing/pairing.go:196-207,317-332`；现有测试 `bridge/internal/pairing/pairing_test.go:226-257`。

失败兑换只调用 `countFailure` 修改内存，没有保存计数或被烧毁的 pairing 记录。20 次失败后 `Outstanding()` 在当前进程内变成 0，但 Bridge 重启会重新加载旧的 `pairing.json`，计数回到旧值、尚未过期的短码重新可用。当前锁定测试未重开 Store，因而只验证单进程行为。

持久化失败计数及达到上限后的失效状态；加入达到上限、重启 Store、再用旧短码尝试仍被拒绝的回归用例。保存失败时也应保持 fail-closed。

### P2：零字节 `pairing.json` 被当作首次启动

位置：`bridge/internal/pairing/pairing.go:109-123`。

缺少文件是首次启动；但现有文件长度为零时也返回空注册表。若文件被截断或备份恢复不完整，Bridge 会安静启动并忘记所有设备，和同一函数注释里“存在但无法解析就报错，不能悄悄撤销所有手机”的规则不一致。当前损坏文件测试只覆盖非空无效 JSON。

只把 `os.ErrNotExist` 当成新安装；零字节文件按损坏报告并保持原文件。新增空文件测试，并确认启动失败、不生成新 pairing 注册表。

## 证据边界与后续顺序

- 本次没有重跑 Go/Android 门禁。`docs/PHASE-7-PLAN.md` §10 记录了步骤 1–4 的 Go 结果和一次真实进程回合；这些是计划中的既有记录，不是本次重新验证。
- Phase 5 复审的四项在后续提交和 Phase 5 §11 中记为已修复；当前代码可见刷新事务锁、冷失败结构化类别及脱敏出口、失败路径携带 Codex 拒绝请求记录。
- 先修主机名校验计划与配对地址/管理路径，再修撤销事务、猜测锁定持久性与损坏文件判定。之后开始 Android 步骤 5–9，最后执行 P-1–P-10、迁移彩排和 DeepSeek 回归验收。
- 同步更新 `docs/HANDOFF.md`：它仍把“出 Phase 7 计划”写作下一步，没有反映步骤 1–4 已交付的状态。
