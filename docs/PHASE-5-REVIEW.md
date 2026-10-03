# Phase 5 Bridge 复审与下一步

日期：2026-10-02。审查对象：Phase 5 实现 `8c8583c..d1aac0c`；复审时 HEAD 已推进至 `41eed22`（Phase 6），以下 Bridge 路径仍存在。Android 工作区另有未提交改动，本次保留原样，未纳入 Phase 5 结论。

结论：现有 Go 测试通过，但以下四项需要修复才能关闭 Phase 5 复审。此次只读审查产品代码，没有修复或提交产品修改。

## 1. P1：首次失败响应绕过脱敏

位置：`bridge/internal/bridge/service.go:110-111`，`bridge/internal/server/server.go:198`。

第一次启动、尚无成功读数时，上游错误只在 `LastFailure.Message` 落盘前经过 `redact.Text`；返回值却重新使用原始 `detail` 构造 error。HTTP 层直接输出 `err.Error()`，所以 503 响应可能携带完整凭据形状。已有 HTTP 脱敏测试先成功、再失败，覆盖的是保留旧读数的 200 路径。

宿主探针使用合成 `sk-` 字符串，未读取任何真实凭据：`status=503 raw_secret_present=true`。该结果证明脱敏出口可被绕过，不表示实际 Codex 已输出或泄露真实令牌。

修复要求：所有公开错误消息走统一脱敏出口；避免从脱敏后的 Failure 回退到原始字符串。覆盖首次失败与已有缓存两种路径，分别注入 JWT、sk、Bearer 形状，并检查响应及状态文件都不含原串。

## 2. P2：首次失败的结构化错误类型丢失

位置：`bridge/internal/bridge/service.go:111`，`bridge/internal/server/server.go:194-198`。

服务识别出了 `CODEX_NOT_FOUND` 等类型，却返回空 `View`；HTTP 层只能从 `view.Degraded` 取类型，于是无缓存失败一律输出 `error=CODEX_UNKNOWN`。具体类型留在 detail 字符串里，客户端不能按结构化 error 正确显示电脑缺少 Codex、方法不可用或认证需要处理。

宿主探针：`cold_notfound error_is_unknown=true`。现有测试只检查整个响应包含 NOT_FOUND，因 detail 包含该文本而假绿。

修复要求：通过结构化错误或携带 Failure 的返回值传递类型；HTTP 测试解析 JSON，逐项断言 `error` 字段，而不是在整个响应中查子串。

## 3. P2：交错请求可清空刚保存的成功读数

位置：`bridge/internal/bridge/service.go:84-125`；`bridge/internal/bridge/state.go:124-130`。

HTTP 请求并发执行，Service 没有保护完整的 Load→Fetch→Save。请求 A 读取空状态后等待查询；B 同样读取空状态并成功保存额度；A 随后失败，把自己的旧空状态连同失败记录写回，覆盖 B 的成功读数，违反失败不得清空最后成功数据。Store 的固定 `state.json.tmp` 也会让并发保存共享同一个临时文件。

确定性门闩探针，直接调用当前生产 Service/Store，结果：

```text
concurrent_success has_data=true err=<nil>
after_late_failure has_data=false err=<nil>
```

修复要求：序列化刷新事务或合并在途请求，并明确失败提交读取/合并最新成功状态的策略；临时文件写入必须避免共享冲突。加入确定性交错回归测试，覆盖冷启动成功与失败交错、两次同时强刷，以及保存失败。

## 4. P2：实际认证反向请求记录未随失败返回

位置：`bridge/internal/bridge/service.go:182-198`。

`classify` 依赖 `Reading.RefusedServerRequests` 将拒绝 `account/chatgptAuthTokens/refresh` 的情况识别为 AUTH_REQUIRED；生产 CodexFetcher 只在成功解析后复制 `c.RefusedRequests()`。initialize、查询或解析失败的三个返回路径都丢失该记录，因而认证反向请求后发生失败或超时会被误分类。现有 Service 假 Fetcher 测试直接填入拒绝记录，绕过了生产 Fetcher 的缺口。

本项由生产控制流核实；未对真实账户制造令牌过期或退出登录。

修复要求：每个返回路径保留拒绝请求记录。使用伪 App Server 子进程执行完整生产 Fetcher，发送认证反向请求后分别返回 RPC 错误与不返回结果，断言最终 AUTH_REQUIRED，且没有提供令牌。

## 5. 验证证据与边界

- 本次执行 `go vet ./...` 与 `go test ./...`，退出成功；五个内部包测试通过。这不能排除上述未覆盖场景。
- 宿主探针：`bridge/bin/phase5-review-probe/main.go`（忽略目录，仅本地审查辅助文件）。运行：在 `bridge` 下执行 `go run ./bin/phase5-review-probe`。探针只用合成数据、httptest 和可丢弃临时状态目录，结束自动清理该状态目录。
- 未重新执行 Android 安装、设备烟测或真实 Codex 认证故障测试；此前文档中的 Android 与 Bridge 烟测通过记录不属于本次新验证证据。
- 审查期间 HEAD/工作区已推进 Phase 6，本结论仅覆盖以上 Phase 5 Bridge 生产路径；不等同于 Phase 6 审查通过。

## 6. 下一步执行顺序与验收

1. 同一修复批次处理首次失败的脱敏与结构化类型传递；验收四种已定义失败类别的首次 503 响应，并检查三个凭据形状均已脱敏。
2. 修复并发刷新与状态提交；将上述门闩顺序固化为回归测试，确认失败始终保留最新成功窗口、时间戳，状态 JSON 不损坏。
3. 修复真实 Fetcher 的认证拒绝记录传播，用伪子进程走协议全链路验证，不能仅用假 Fetcher 填好答案。
4. 重跑 Go 格式、vet、全部测试及 Bridge 烟测；更新验收断言，明确检查 JSON 字段和失败分支。记录对应提交及实测结果后关闭本复审。
5. 保留正在进行的 Phase 6 修改；完成其独立审查与 Android/模拟器验收后再宣称交付。修正 HANDOFF 中仍停留在 Phase 5/待计划的状态与旧基线。

本文件可直接交给修复 Agent。以上是修复和验收要求，不代表已经执行修复。
