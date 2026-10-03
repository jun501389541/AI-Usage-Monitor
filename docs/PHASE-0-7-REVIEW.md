# Phase 0–7 综合复审与修复提案

日期：2026-10-03<br>
复审基线：`bb41d26`（当前 HEAD；Phase 7 Android 步骤 5–10 尚未实施）
范围：对照各阶段计划、交接文档、既有审查记录与当前源码做只读复审。本轮没有改产品代码、没有运行测试或设备验收。

## 1. 结论

确认 **5 项仍未关闭的问题**：

| 阶段 | 优先级 | 问题 |
| --- | --- | --- |
| Phase 0–2 | P2 | 批量刷新可能把旧 `Account.credentialId` 与新凭据代次拼在一起，导致刚保存的有效凭据被误报失效 |
| Phase 3 | P2 | 移除最后一个桌面 Widget 后，零点闹钟仍每天刷新所有账户并重排 |
| Phase 4 | P2（低频） | 同一时间戳的快照在保留策略中没有按 id 决胜，可能与 `latest()` 的最新记录定义不一致 |
| Phase 4 × 6 | P2 | Codex 成功历史记录没有余额；历史列表只读余额字段，因而显示 `—` 并隐藏额度窗口 |
| Phase 7 Bridge | P2（故障路径） | 新配对写盘失败时，`Issue()` 在提交前清除 fail-closed 标志 |

Phase 5 旧复审的四项问题与 Phase 7 原复审的四项 P2，在当前 HEAD 都有对应修复及回归用例记录。Android 端逐连接主机名校验是 Phase 7 步骤 7 的未完成前置条件，不是当前 Go 半边已交付功能；计划已要求 `PinnedHostnameVerifier`，并禁止全局放宽验证。

## 2. 确认的问题

### 2.1 Phase 0–2：刷新使用旧账户对象和新代次

`AccountRefreshManager.refreshAll()` 先取一次启用账户列表，顺序刷新这些对象（`AccountRefreshManager.java:325–329`）。如果前一个账户请求期间，用户给后一个账户保存了凭据，列表里的后一个 `Account` 仍有旧 `credentialId`。`refresh(Account)` 却在调用时读取当前代次，再把旧对象交给 `openCredential()`（`AccountRefreshManager.java:187–195`；`AccountManager.java:312–317`）。这样旧 credential ID 的打开失败会被当成当前代次的真实 `AUTH_REQUIRED` 写入，刚保存的新凭据不会被请求。

这是既有审查账本中的 R3-B 场景（`docs/REVIEW-AND-NEXT-STEPS.md:237–248`）；当前 HANDOFF 只登记代次提交窗口已关闭，没有登记此旧对象/新代次组合已修复。现有凭据代次测试未覆盖批量列表中的陈旧对象。

### 2.2 Phase 3：没有 Widget 时零点仍刷新

`ACTION_BOOT_COMPLETED` 无条件安排零点闹钟（`WidgetRefreshReceiver.java:41–44`），`MainActivity.onCreate()` 也会安排（`MainActivity.java:211`）。闹钟触发时，`midnight || hasWidgets` 使零点路径无条件调用 `refresh()`，其内执行 `refreshAll()` 和快照清理；`finally` 又每天重排（`WidgetRefreshReceiver.java:60–70`）。各 Widget 的 `onDisabled()` 只在最后一个 Widget 消失时取消常规闹钟，不取消零点闹钟。于是卸载最后一个 Widget 后仍会每日联网刷新账户。

零点重绘本身不需要请求 Provider：Widget 的 `dailyUsage()` 按当前本地日期读取，没有当日行时返回 0（`SqliteUsageRepository.java:267–285`），因此可从已有数据重绘。Phase 4 的清理继续由常规后台刷新与 Widget 手动刷新触发，符合 `docs/HANDOFF.md:40` 已记载的触发点。

### 2.3 Phase 4：相同时间戳时保留行不确定

`SqliteUsageRepository.prune()` 查询全表行时没有 `ORDER BY`（`SqliteUsageRepository.java:156–164`）。纯策略 `SnapshotRetention.expired()` 只按 timestamp 排序，并通过顺序覆盖 `newestRow` 和 `newestSuccess`（`SnapshotRetention.java:87–99`）。相同 timestamp 的输入顺序没有契约；但 `latest()` 与 `latestAttempt()` 明确以 `timestamp DESC, id DESC` 判定最新行（`SqliteUsageRepository.java:77–84, 101–108`）。极低频的同毫秒旧快照进入清理窗口时，保留策略可能保护了与读取接口定义不同的成功行。

### 2.4 Phase 4 × 6：Codex 历史条目显示破折号

历史列表对每条快照只读取 `UsageResult.getBalance()`，为空时直接使用 `Money.EMPTY`（`MainActivity.java:847–853`）。但 `UsageResult` 明确允许 balance 为空而携带 quota windows（`UsageResult.java:69–76`），Codex 解析器就是这种形状（`BridgeUsageParser.java:59–66, 84–90`）。因此 Codex 查询成功后，详情页“最近读数”会显示时间和 `—`，不呈现实际额度窗口。

Phase 4 历史最初只服务余额账户；Phase 6 增加 quota-only Provider 后没有扩展该生产消费者。这不影响快照落库或详情页当前额度卡片，但历史功能对 Codex 账户不完整。

### 2.5 Phase 7 Bridge：`Issue()` 写盘失败会解除拒绝状态

`rejectGuess()` 在失败计数无法持久化时设置 `s.refused = true`，让当前进程 fail-closed（`bridge/internal/pairing/pairing.go:384–413`）。`Issue()` 在 `commit(next)` 之前就清除该标志（`pairing.go:202–207`）。如果此前一次猜测触发拒绝，随后管理员签发新码也遇到写盘错误，候选状态未提交，但旧配对状态仍在内存中，`Exchange()` 的后续调用不再被拒绝（`pairing.go:215–245`）。拒绝状态应只在新状态成功持久化后清除。

## 3. 已关闭的问题与已接受的限制

- Phase 0–2：R1 日期范围查询、R2 降级存储提示、R4 最近尝试状态显示在当前源码中已接通；R3-B 仍未关闭。Phase 3 前后的账号刷新代次与删除同步设计不是对这个陈旧对象问题的覆盖证明。
- Phase 3：Slot 指标选择、刷新中状态、4×2 三 Slot 上限、2×4/4×4 与 2×1 手动刷新入口均按计划延期，不计本轮缺陷。
- Phase 4：14 天明细保留、固定保留期、打开页面不请求 Provider、H6/H7 不脚本化等均按计划记录，不计本轮缺陷。
- Phase 5：冷失败脱敏与错误类型、刷新写入串行化、拒绝记录传播、结构化断言/真子进程覆盖均有后续修复记录。
- Phase 6：失败时详情页的读数区显示错误文字、Codex 额度暂不进 Widget、手工地址调试通道等均已记录为阶段决策或限制，不擅自改成新需求。
- Phase 7：旧复审里的撤销落盘、猜测锁定持久化、空文件损坏处理、offer/bind/CLI 地址与实例一致性均在 `827435f`/`8aeea1e` 后关闭；Android A10 主机名校验仍待步骤 7 实施与验收。

## 4. 建议修复边界与验收

建议一次修复以上五项，不改数据模型、不扩展 Bridge 协议、不改变已批准的阶段范围：

1. **刷新快照一致性**：刷新入口在短临界区内按 account ID 重新读取当前 `Account` 并取得相同版本的 credential generation，再将该对象用于开凭据和请求；网络调用仍在锁外。回归用例复现“刷新 A 阻塞时给 B 保存新 key，批次继续到 B”，断言 B 使用新凭据成功而非记录 `AUTH_REQUIRED`。
2. **零点只重绘**：零点 action 只在存在 Widget 时重绘，不调用 `AccountRefreshManager`、不做 prune；常规后台刷新/手动刷新继续负责取数和保留清理。午夜闹钟随首个 Widget 启用、设备重启恢复，并在最后一个 Widget 移除时取消。验收断言无 Widget 时不排后台任务；有 Widget 的零点只重绘且不增加快照或网络调用。
3. **保留并列决胜**：策略排序使用 `(timestamp ASC, id ASC)`，让同时间戳下最大的 id 与 repository 的最新行规则一致。添加相同行集合、正序/逆序输入的纯逻辑测试，断言保留相同最新成功与最新尝试。
4. **历史按读数类型显示**：余额快照保持当前金额与差值显示；quota-only 快照以窗口 label 和 used percent 显示历史数值，不显示误导性的 `—`。重置文字按快照时间解释，避免用当前时间改写历史状态；增加余额、quota-only 与空数据形状测试。
5. **Bridge 拒绝状态提交顺序**：只在 `commit(next)` 成功后清除 `s.refused`。测试猜测计数写入失败后再让 `Issue()` 写入失败，断言旧代码仍返回 `ErrLockedOut`；成功签发并持久化后，才允许新交换。
6. **同步 HANDOFF**：更新基线、修复状态、验证结果与 Android 尚未完成的门槛；历史测试数字继续标成历史证据，不声称本轮设备验证已通过。

## 5. 验证范围

修复完成后再运行，不把本文或历史计划里的旧数字当成本轮结果：

- Android：`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug`，并记录实际 suites/tests 与 lint 数字。
- Bridge：`gofmt -l ./cmd ./internal`、`go vet ./...`、`go test -count=1 ./...`。
- 设备侧只运行与更改直接相关、且能保全现有模拟器数据库的验收；不对含真实凭据的手工设备做破坏性数据清理。

## 6. 当前文档状态

`docs/HANDOFF.md` 顶部仍写 `8aeea1e` 为基线并称手机套件正在重跑，而当前 HEAD 已是 `bb41d26`。修复后应按实际设备状态更新；如果没有新跑设备验收，就明确标为未复测。
