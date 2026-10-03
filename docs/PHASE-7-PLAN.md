# Phase 7 计划：配对与长期设备认证（Pair Token → Device Token）

日期：2026-10-03。前置：Phase 0-6 已交付、Phase 5 复审四项已关闭（`docs/PHASE-5-PLAN.md` §11）。
本计划对应 Spec `AI-Usage-Monitor-Development-Plan.md` L2063「# Phase 7：二维码配对」与 §20 / §21 / §22。

**先说结论里最硬的两条**：§0.1 决定 Phase 7 的传输必须是 HTTPS + 指纹锁定（不能靠扩明文白名单）；§0.2 决定「手机扫码」这一步与 Spec L22「无第三方依赖」正面冲突，需要用户拍板才能做（D1）。其余部分可以先动工。

---

## 0. 前置事实（本计划撰写时实测，不是推断）

### 0.1 配对必然离开回环，而离开回环就必然要求鉴权 + TLS + 指纹

| 事实 | 实测方式 | 后果 |
| --- | --- | --- |
| Bridge 现在**只接受回环**：`server.New` 里 `allowedHosts` 只含 `127.0.0.1 / localhost / ::1`，其他一律 `ErrNotLoopback` 且进程退出 | 读 `bridge/internal/server/server.go:21-59`；验收 B5 实测 `--host 0.0.0.0` 退出码非 0、本机 LAN 地址连不上（`docs/PHASE-5-PLAN.md` §10） | 配对的第一现场就是「手机要能连到电脑」，这一步必然要放宽绑定 |
| `/v1/*` **今天完全没有鉴权**（`handleUsage` 不看任何请求头） | 读 `server.go:173-202`；Phase 5 的 D2 明确「只到 localhost，接口留给后续」 | 放宽绑定的同一批必须交付令牌校验，否则等于把账户额度广播给整个网段（Phase 6 §9 R4 已登记） |
| Android 的明文 HTTP 例外是**静态 XML**：`network_security_config.xml` 里只有 `10.0.2.2` 与 `localhost` 两个 domain | `cat app/src/main/res/xml/network_security_config.xml`；框架没有「运行时加白名单」的 API | 用户机器的 LAN 地址不可预知（DHCP、网段各异），**不可能**用明文白名单支持配对。唯一可行路径是 HTTPS |
| HTTPS + 自签证书必须由 App 侧校验证书来源，否则 MITM 就是「谁都能冒充 Bridge 拿到 Device Token 请求」 | Spec §20 L823-829 把 `Server Fingerprint` 放进二维码，正是为这件事准备的 | 自签证书 + 二维码带 SPKI SHA-256 指纹 + App 侧 `X509TrustManager` 逐字节比对指纹（框架 API，零依赖） |
| 证书里**没有** LAN 地址，而 Android 在 TrustManager 之后还要单独判主机名 | 实测（2026-10-03，`tools/smoke/out/sancheck.ps1` 读 `%LOCALAPPDATA%\AIUsageBridge\identity.crt.pem`）：SAN = `DNS:localhost`、`IP:127.0.0.1`、`IP:::1`，CN = 随机 Bridge ID，签名 sha256RSA；`HttpsURLConnection` 文档明写握手成功后仍用 `HostnameVerifier` 校验 URL 主机名 | **只钉指纹不够**：手机连 `https://<LAN-IP>` 时，即使 SPKI 与配对指纹完全一致，默认主机名校验仍会因证书不含该 IP 而拒绝。LAN 地址不能预先写进证书（它会变，且换地址=换证书=全员重配，见 A2 的取舍），所以必须按 A10 逐连接给 verifier |

一条反直觉但重要的读法：**明文 + 令牌的组合不能当 TLS 的替代品**。局域网里任何一台机器都能嗅探明文 HTTP 并拿走一次性 pair token 与之后的 Device Token（Phase 6 的 `assert-bridge-phone.ps1` 之所以能证明拦得住，靠的是白名单只放行了 `10.0.2.2`，而不是加密）。

### 0.2 「手机扫码」需要第三方解码库，而 Spec L22 禁止第三方依赖

实测（2026-10-03，本机 SDK）：

```text
$ unzip -l /d/Android/sdk/platforms/android-36/android.jar | grep -iE "barcode|zxing|vision"
android/nfc/tech/NfcBarcode.class            <- 只有 NFC 的条码技术标签，不是解码器
android/telephony/ims/ProvisioningManager…   <- 与本节无关
$ adb -s emulator-5554 shell dumpsys media.camera | head -2
Number of camera devices: 1                  <- 摄像头不是瓶颈，解码器才是
```

框架里没有任何条码/QR 解码 API；`camera2` 只给原始帧。可用的解码方案全是第三方：ML Kit（`com.google.mlkit:barcode-scanning`，还带 Play 依赖）、zxing（`com.journeyapps:zxing-android-embedded`）、或自己写解码器（数千行，Reed-Solomon + 定位 + 掩码，等于本项目里最大的一块代码）。而 Spec L22 明写「无第三方依赖」、本项目从 Phase 1 起生产代码零依赖（`docs/HANDOFF.md` §3）。

**这是 Spec 自身的内部冲突，不是我挑出来回避的理由**：要么 L22 为相机/解码让一次步（D1 选项 ②），要么把「配对通道」与「配对协议」分开——协议、令牌、设备管理、TLS 全部做完，扫码只当作 payload 的一个来源（D1 选项 ①）。已存在的接缝让后者很便宜：`BridgeTransport`（Phase 6 A2）证明了「把外部输入抽成一个接口，实现换掉而测试不动」的写法在这套代码里行得通。

### 0.3 「Token 不许进二维码」有两处，读法必须并起来看

| 出处 | 原文 | 说的是哪种令牌 |
| --- | --- | --- |
| L358（§7 敏感信息） | 禁止：… Token 放进二维码 | 长期凭据：API Key、OpenAI/ChatGPT 令牌、Device Token |
| L823-829（§20） | 二维码包含：… **一次性 Pair Token** | 引入用的短期一次性凭据 |
| L2307（§54 红线） | 不要：二维码携带 OpenAI 认证信息 | OpenAI 侧凭据 |

采信的读法（D3 请确认）：二维码里**只允许**出现「一次性、短时效、成功即失效」的 pair token；OpenAI 凭据、DeepSeek API Key、长期 Device Token 出现即为违规。本计划把它写成可机器检查的断言（§5.4 D-7：QR payload 里不得出现 `sk-`、`eyJ`、以及任何已签发 Device Token 的字面值），而不是留成注释里的默契。

### 0.4 已经建好、本阶段直接接着用的部分（逐条读码确认）

| 位置 | 现状 | 本阶段怎么用 |
| --- | --- | --- |
| `provider/AuthContext.java:33` | `KEY_DEVICE_TOKEN = "deviceToken"` 已定义 | 配对成功后写入的正是这一格 |
| `auth/CredentialPayload.java:77/93/111` | `forBridge(url, token)`、`extractDeviceToken`（缺失即 `BRIDGE_UNAUTHORIZED`）、`extractOptionalDeviceToken` | 令牌链已通，缺的只是「谁Issued它」 |
| `accounts.bridge_id` 列（`storage/Database.java:73`）与 `Account.bridgeId` | **一直空置**。Phase 6 A3 故意留的债：地址进了凭据 payload，`bridge_id` 要留给配对身份 | 本阶段就是还债时点：`bridges` 表 + `accounts.bridge_id` 填上 |
| `storage/Database.java:26` `VERSION = 2`，`:132` `onUpgrade`，`:155-180` v1→v2 事务化迁移模板 | 有可复用形状（单事务、`INSERT OR IGNORE`、失败不留半份） | v2→v3 加 `bridges` 表，照此模板写 |
| `provider/codex/BridgeCodexDataSource.java` | 令牌为空就不发 `Authorization` 头；四类失败已映射 | 加上 `https://` 与配对失败态；不动既有映射 |
| `bridge/internal/server/server.go` | 已有 loopback 守卫、`?refresh=1`、503 带结构化 class、出口统一脱敏 | 新增 `/v1/pair`、鉴权中间件、TLS 分支；守卫从「拒绝非回环」升级为「非回环必须同时具备 TLS + 强制鉴权」 |
| `bridge/internal/bridge/state.go` | `CreateTemp` + rename 原子写、0600、Windows 并发 rename 会 `Access is denied`（Phase 5 复审 §3 实测） | 设备注册表落盘照此写法，**不与 state.json 共用一个进程内事务锁**（新文件，需要自己的锁） |
| `tools/smoke/assert-bridge.ps1` | 38 checks；能真起进程、真子进程假 app server、断言解析后的 JSON 字段 | 复用同一写法：配对断言按字段，不按子串 |

### 0.5 未做，但 Spec 要求本阶段不做（写明以免顺手做）

- **mDNS / 自动发现 / Bridge 名字解析**：Phase 8（L2091「配对以后自动找到电脑」）。本阶段只做「配对时拿到一次地址并记下来」。
- **Codex Direct / AUTO**：Phase 9-10。
- **托盘、开机自启**：Phase 5 D4 已延后。
- 手动填 IP：Spec 要求「删除手动 IP 作为主要流程，保留用于调试」→ 本阶段保留其能力并显式标注为调试通道（D4），不做删除，因为删了就没有 dep-free 通道之外的替代（见 §0.2）。

---

## 1. 目标与范围

做：Bridge 身份与密钥材料（Bridge ID、自签证书、SPKI 指纹）→ 一次性 Pair Token 签发/兑换/失效 → Device Token 签发、哈希落盘、轮换与撤销 → 设备管理界面（电脑侧列表 + 手机侧列表）→ `bridges` 表与 `accounts.bridge_id`（还 A3 的债）→ 非回环绑定只有在「TLS + 强制令牌」同时成立时才允许，否则照今天一样拒绝启动。

不做：相机扫码的解码实现（等 D1）、mDNS、Direct OAuth、AUTO、多 Bridge 并发轮询、图表。

交付判定：**协议与安全属性全部可测且已被测**；配对通道里「扫码」这一格由 payload 来源接口顶着，D1 落定后一次性接上，不改协议、不改测试骨架。

---

## 2. 关键设计决定（含取舍）

| # | 决定 | 理由与代价 |
| --- | --- | --- |
| A1 | **Bridge ID 是随机不透明 ID，不是主机名/IP** | Spec §22 L892 不许永久依赖 `192.168.x.x`，§54 L2325 不许把 IP 当身份。代价：设备换网段后需要重新发现（Phase 8 的 mDNS 解决），本阶段地址变化要手动重连一次 |
| A2 | **密钥材料：2048-bit RSA 自签证书，存 `--data-dir`，CN 用 Bridge ID，有效期 10 年，SPKI SHA-256 是指纹** | 只用 Go 标准库（`crypto/rsa`、`crypto/x509`、`net/http` TLS）。指纹是「钉」的对象。代价：自签证书不被系统信任，必须靠 App 侧钉指纹；10 年有效期意味着指纹长期稳定（换证书=重新配对） |
| A3 | **Pair Token：256-bit 随机、默认 120s 有效、一次性、只存 SHA-256** | 「成功即失效」是 Spec §20 L839-842 的硬要求；一次性 + 哈希落盘意味着泄漏也不等于可复用。代价：过期就得在电脑侧重新点「添加设备」，这是正确的摩擦 |
| A4 | **Device Token：256-bit 随机，服务器只存 SHA-256；一次一设备，可单独撤销；`X-Device-Id` 由服务器签发时给出** | 与 Spec §21「手机保存 Device Token + Bridge ID + Fingerprint」一致。代价：撤销后手机只能重新配对（Spec §21 L861-871 列的正是这几种情形） |
| A5 | **配对交换走 TLS；配对成功后所有 `/v1/accounts/*` 请求必须带 Bearer Device Token** | 没有这一步，放宽绑定就是把额度读给整个网段（Phase 6 R4）。代价：手机侧要能钉指纹；调试通道（明文 + 无令牌）只在回环上继续可用 |
| A6 | **绑定守卫从「非回环一律拒绝」升级为「非回环必须同时满足 TLS + 强制鉴权」** | 保住 B5 的可测形式（不满足条件时启动即失败、退出码非 0），而不是悄悄放宽。代价：`--host 0.0.0.0` 在缺 `--pair`/`--require-token` 时必须报错而不是降级运行 |
| A7 | **配对 payload 的来源抽成 `PairingPayloadSource` 接口，本阶段实现三条 dep-free 通道：深链 `aiusage://pair#…`、粘贴板/手输长文本、手输「地址 + 短码」** | Spec §20 的 payload 是「字符串进、结构出」，与来源无关。这样 D1 无论选哪边，协议与测试都不动；而相机只是第四个实现。代价：扫码 UX 缺一个阶段（明确登记，不假装已交付） |
| A8 | **指纹校验在 App 侧用自定义 `X509TrustManager`：只接受链上叶子证书 SPKI 摘要等于配对时钉住的指纹，其余一律拒绝** | 框架能力（`javax.net.ssl`），零依赖。不做「trust all + 手工比字符串」那种常见写法：那会让主机名与证书都失去意义。代价：单靠它还不能连 LAN 地址——见 A10。 |
| A9 | **`bridges` 表（v3）保存配对结果；配对账户的 `accounts.bridge_id` 指向它，`CredentialPayload` 里不再重复地址** | 还 Phase 6 A3 的债；地址变化时改一行 `bridges.base_url`，不动每个账户。代价：需要迁移，且要兼容已经存在的「手输地址」账户（它们的 payload 里有 URL，`bridge_id` 为空 = 调试通道，行为不变） |
| A10 | **主机名校验策略（Phase 7 复审 P1）：配对用的每个 `HttpsURLConnection` 实例各自 `setHostnameVerifier(PinnedHostnameVerifier(fingerprint))`；该 verifier 只回答一句「这条连接呈现的叶子证书，其 SPKI 摘要是否等于这台 Bridge 配对时保存的指纹」，等于才 true。禁止 `HttpsURLConnection.setDefaultHostnameVerifier(...)`，禁止无条件 `return true`，禁止把 verifier 复用到非配对连接** | 为什么不能「就用默认校验」：证书没有 LAN SAN（§0.1 实测），默认 verifier 会在握手后拒绝 `https://<LAN-IP>`，配对在真机上必然失败。为什么不能全局放开：`setDefaultHostnameVerifier` 是进程级，会把 DeepSeek 与一切其它 HTTPS 请求的证书校验同时关掉，等于用「配对」这一个功能买断了全 App 的 MITM 防线。代价：每个建连点都要显式装配（漏装=用默认=连不上 LAN，属可测的响亮失败）；`PinnedHostnameVerifierTest`（§5.2）与 P-2（§5.3）把「漏装 / 全局装 / 先请求再判」三种写法都变成会红的断言。 |
| A11 | **手输「地址 + 8 位短码」这条通道怎么建立信任：首次信任 + 人工核对指纹尾段**（2026-10-03 步骤 6-8 动工前由用户拍板）。客户端先向该地址发一次不带任何凭据的 TLS 探测，取回服务器证书的 SPKI 摘要，屏幕上只打尾 8 位，与电脑终端同一行打印的尾段由人眼比对；调用方必须把「用户确认过」与「确认的那枚完整摘要」一起交回来，短码才会发出，而发出用的那条连接的 trust manager 与 verifier 都按这枚摘要装配 | 为什么单独立一条：三条通道里只有这条不携带指纹（没人会读 64 个十六进制字符），而 A8/A10 要求配对连接必须钉指纹——不钉就是把一次性短码发给路径上任何一台机器。为什么不选「配对完成后再补核对」：那让首次配对成为唯一没有人工在场的时刻，而此刻手机与电脑恰在同一网段，是 MITM 成本最低的一刻。残余风险登记为 R10：人工核对是否发生，脚本无法证明，所以 P-3 断言的是「没有确认就一定不发」这一侧。代价：这条通道比粘贴 payload 多两步（探测、核对），且探测本身是一次不验证书的握手——它只读 `/v1/health`、结构上无法带 header 或 body（`BridgeTls.openProbe` 的签名就是这条边界，`PairingWiringTest` 钉住它） |

---

## 3. 逐项设计

### 3.1 Bridge（Go，`bridge/internal/`）

| 新位置 | 内容 |
| --- | --- |
| `identity/identity.go` | 加载或首次生成密钥材料：`Bridge ID`（`br_` + 12 字节 base32）、RSA-2048、自签证书（CN=Bridge ID）、`tls.Certificate`、指纹 = `SHA256(SPKI)` 的 hex；落盘 `--data-dir`（`identity.key.pem`/`identity.crt`，0600），文件已存在就复用（**配对状态不能因为重启而失效**） |
| `pairing/store.go` | `PairingStore`：pair token 记录（哈希、过期时间、一次性消费标记）、设备记录（哈希、名称、首次/最后Seen、撤销标记）。与 `state.json` 分文件（`pairing.json`），照 A2/§0.4 的 `CreateTemp`+rename 写法，并自带互斥锁（复审 §3 的教训：跨文件也要串行化） |
| `pairing/service.go` | `IssuePairCode()`（电脑侧点「添加设备」；同时产出 QR payload 文本）、`Exchange(pairToken) → deviceToken`（校验哈希、未过期、未消费；消费后立刻作废）、`Validate(deviceToken)`、`Revoke(deviceId)`、`List()` |
| `server/server.go` | 新增 `POST /v1/pair`（body 只有 `pairToken` 与 `deviceName`）、`/v1/health` 增 `paired`/`bridgeId`/`fingerprint`（**指纹不是秘密，可以进明文健康检查**）；`/v1/accounts/*` 前挂鉴权中间件；`New()` 的守卫升级为 A6 |
| `cmd/aiusage-bridge/main.go` | 新 flag：`--pair`（允许配对通道，隐含 TLS + 强制鉴权）、`--device-name`、`--pair-ttl`（默认 120s）；`--host` 非回环时缺 `--pair` 直接报错退出（保住 B5 的形状） |
| 输出「添加设备」的可见文本 | 终端打印 QR payload + 短码；GUI 不在本阶段（托盘仍延后） |

### 3.2 App（Android，Java，零依赖）

| 新位置 | 内容 |
| --- | --- |
| `storage/Database.java` v3 | `bridges(id TEXT PK, name TEXT, base_url TEXT, fingerprint TEXT, added_at INTEGER, last_seen INTEGER)`；迁移单事务，照 v1→v2 模板 |
| `bridge/BridgeRepository.java` | 增删改查 `bridges`；`accounts.bridge_id` 写入与解绑 |
| `pairing/PairingPayload.java` | 解析 `aiusage://pair#<base64url(json)>`：`{v,bridgeId,hosts[],port,pairToken,fingerprint}`（2026-10-03 实施时与 `bridge/internal/pairing/payload.go` 对齐：`hosts` 是列表而非单个 `host`，没有 `path`，字段名写全（`pairToken`/`fingerprint`）而不缩写；同一份 Go 侧 `ParsePayload` 会被 `--add-device` 读回，两端必须同形）；**校验只允许 §0.3 的字段**，多余字段拒绝而不是忽略 |
| `pairing/PairingPayloadSource.java` | A7 的接口 + 三条实现：`DeepLinkSource`（manifest intent-filter）、`PasteSource`（用户在配对页粘贴）、`ManualSource`（地址 + 短码） |
| `pairing/FingerprintPin.java` + `pairing/PinnedTrustManager.java` + `pairing/PinnedHostnameVerifier.java` | A8 + A10：SPKI 摘要比对；任何其它证书 → 失败。verifier 是**实例级**的（配对的每个连接各自 `setHostnameVerifier`），全项目不得出现 `setDefaultHostnameVerifier`。日志与异常里不得出现令牌（Phase 5 复审 §1 的同一红线） |
| `pairing/BridgeTls.java`（或 `PairingClient` 内的私有装配） | 唯一的「建一条配对连接」出口：SSLContext(钉证 TrustManager) + HostnameVerifier(同指纹) 一起装上，返回配好的 `HttpsURLConnection`。抽成一个出口而不是每处手搓，是为了让「只装了 TrustManager 忘了 verifier」这种半配置在代码上不可表达 |
| `pairing/PairingClient.java` | `POST https://host:port/v1/pair`，成功→ `CredentialPayload.forBridge(url, deviceToken)` 走现有 Keystore 链；失败按类别给文案（配对码过期 / 已被使用 / 指纹不符 / 连不上 / 服务器要求 TLS）。payload 的 `hosts` 按顺序试连；**模拟器把 `127.0.0.1`/`localhost` 改写为 `10.0.2.2`**（宿主机回环的另一副面孔，复审 P2a 后默认绑定只给出回环地址，没有这条改写模拟器就配不上对），改写只发生在配对连接的地址解析里，且有一条单测钉住「真机不改写、模拟器改写、两条都仍然钉指纹」 |
| `ui/pair/PairActivity.java` | 配对页：选来源、显示目标 Bridge 名与指纹尾 8 位、成功后落到账户创建/绑定 |
| `ui/bridge/BridgeListActivity.java` | 设备与 Bridge 管理：列 Bridge、列已配对设备、撤销、重新配对入口 |
| `AccountEditActivity` | 服务商选 Codex 后增加「配对（推荐）」与「手输地址（调试）」两条路径（D4） |
| `refresh/`、`provider/codex/` | 失败态新增 `BRIDGE_PAIRING_REQUIRED`（未配对/被撤销 → 明确要重新配对，而不是「电脑离线」；Spec §53 规则 19 的延伸） |

---

## 4. 分层与红线（新增/收紧）

1. `ui/` 不直接碰 HTTP：配对客户端归 `pairing/`，与 Phase 6 的 `provider/codex` 同层规则（Spec §45/§53）。
2. 二维码/payload 里出现 `sk-`、`eyJ`、或任何已签发 Device Token → 断言直接红（§0.3、§54 L2307）。
3. Bridge 仍**永不读取** `%USERPROFILE%\.codex\auth.json`，仍**不得**回应 `account/chatgptAuthTokens/refresh`（Spec L804-809；Phase 5 已实现，本阶段不得为「方便配对」而破例）。
4. Pair Token / Device Token 明文不得落盘、不得进日志、不得进异常串（只存哈希，日志只留尾 6 位——沿用 `redact` 的指纹习惯）。
5. 非回环绑定必须与 TLS + 强制鉴权同批；缺任一项，进程启动失败（§54 精神 + A6）。
6. 撤销/过期必须与「电脑离线」可分辨（规则 19 的延伸，D-6 断言）。
7. 失败不得清空上一次成功读数（规则 18，配对改动不得回退 Phase 6 的行为）。

---

## 5. 测试与验收

### 5.1 Go 单测（无需 Codex、无需网络）

| 用例 | 覆盖 |
| --- | --- |
| identity | 首次生成→复用；换 data-dir 生成新 ID；指纹与证书 SPKI 一致；私钥文件 0600 |
| pair token | 一次性（兑换两次第二次失败）；过期失败；错误 pair token 失败；**落盘只有哈希**（断言文件里没有明文 pair token 字面值） |
| device token | 签发后 `Validate` 通过；撤销后失败；重启进程仍认（复用 identity 与 pairing.json）；设备列表持久 |
| 绑定守卫 | `--host 0.0.0.0` 无 `--pair` → 启动失败退出码非 0；有 `--pair` 但无 TLS/鉴权配置 → 同样失败；两者齐备 → 起来且 `/v1/accounts/codex/usage` **无令牌返回 401** |
| 鉴权中间件 | 正确令牌 200；错误/缺失 401；被撤销 401；`/v1/health` 不需令牌；响应与日志中不出现令牌 |
| payload | `POST /v1/pair` body 带多余字段/超长 deviceName 的处理；配对成功后 health 报 paired 计数 |
| 地址与绑定（复审 P2a，`address_test.go`） | 回环绑定只 offer 回环（哪怕机器有 LAN）；LAN 绑定 offer 自己 + 回环；通配绑定 offer 本机单播地址且不含 link-local；`--advertise` 指向绑定之外的地址 → 构造失败且消息里给出该地址与 `--host`；本机地址列表取不到时通配绑定拒绝启动；`ListenAll` 在 LAN 绑定上补出回环监听器并共用同一端口；`:0` 时 offer 携带实际端口 |
| 真 socket（`bind_test.go`） | 绑到本机 LAN 地址后：该地址用钉指纹能握手、错指纹握不上；`/v1/admin/pair` 从回环真连一次拿得到 payload（hosts/port/fingerprint 三项都对得上）；LAN 来源的连接打管理路由得到 404（本机自连若被操作系统选成回环源地址，这条明确说明未断言而不是硬猜）；已配对设备令牌经 LAN 地址读到额度 |

### 5.2 JVM 单测

| 用例 | 覆盖 |
| --- | --- |
| `PairingPayloadTest` | 合法 payload 解析；缺 `fp`/缺 `token`/未知键/`sk-` 形状/已签发 Device Token 字面 → 全部拒绝 |
| `FingerprintPinTest` | 钉对指纹通过；钉错指纹拒绝；**不验证主机名的通用 TrustManager 不接受**（防「先接受再说」） |
| `PinnedHostnameVerifierTest` | 主机名是 LAN IP 而证书指纹匹配 → true；指纹不符 → false（**与主机名无关**，防「名字对了就放行」）；证书链里有多张证书时取叶子；`pairing/` 与 `bridge/` 源码里 `setDefaultHostnameVerifier` 出现 0 次、`HostnameVerifier` 只与 `Pinned*` 同现（源码 pin，写法沿用 Phase 6 的 `BridgeQueryPathWiringTest`） |
| `BridgeRepositoryTest` / schema v3 | 迁移幂等（v2 库升级、重复升级）；未配对账户 `bridge_id` 为空仍可用（Phase 6 手输通道零回归）；删 Bridge 时账户的降级行为明确 |
| `PairingClientTest`（假 `BridgeTransport`） | 200/401/过期/指纹不符/TLS 要求；断言令牌只进请求头、不进 URL（Phase 6 A4 的同一写法） |
| 状态分离 | `BRIDGE_PAIRING_REQUIRED` 与 `BRIDGE_OFFLINE`、`BRIDGE_AUTH_REQUIRED` 三态文案互不相同且都不含「API Key」 |

### 5.3 设备/本机验收（`tools/smoke/assert-bridge-pair.ps1`，UTF-8 BOM）

| # | 断言 | 它能怎么失败 |
| --- | --- | --- |
| P-1 | 电脑侧起 `--pair --host <本机 LAN>`：真起 TLS 服务器，终端出现 payload，且 `addresses offered to phones` 就是这台机器能连上的两条（复审 P2a 后由绑定推导，不再自动塞 LAN）；模拟器走 `PasteSource` 完成配对 → 手机出现已配对 Bridge，`/v1/accounts/codex/usage` 带 Bearer 真读到两个额度窗口。**这一条同时是 A10 的正向证据**：证书没有 LAN SAN，握手能成只可能是因为逐连接 verifier 装对了 | 令牌链、TLS 钉指纹、主机名策略任何一环没接上 → 变红 |
| P-2 | 指纹被改一位（本机改 payload 里的 fp 再贴）→ 手机明确报「指纹不符」且**没有发出任何请求**（用服务器侧计数证明；「先连上再验」与「主机名放行后再看证书」两种写法都在这条上变红） | 「先连上再验」的实现 → 变红 |
| P-3 | 同一个 pair token 兑换两次：第二次 401；过期后（`--pair-ttl 3s`）兑换 401；电脑侧文案与手机文案都区分「过期/已用」 | 一次性或 TTL 任一未实现 → 变红 |
| P-4 | 电脑侧撤销设备 → 手机下一次刷新显示「需要重新配对」，与「电脑离线」不同；重新配对后恢复 | 撤销不生效或三种状态混成一种 → 变红 |
| P-5 | 守卫实测：`--host <LAN>` 不带 `--pair` 必须启动失败（非 0 退出）；带 `--pair` 但明文访问 `/v1/accounts/*` 无令牌必须 401 | 悄悄放宽绑定或忘了挂中间件 → 变红 |
| P-6 | 配对状态重启不丢：杀掉 Bridge 再起 → 同一手机**不重新配对**仍能读额度；且 identity 文件字节不变（A2/A3 的持久性） | 每次重启都要重配 → 变红（真实事故形状） |
| P-7 | 凭据形态扫描：`pairing.json` 与 `state.json` 全文 grep 不出明文 pair token / device token；logcat 同（并把「测试自己的 adbd 回显」按进程归属拆开，Phase 6 已踩） | 落盘写明文 → 变红 |
| P-8 | 手机侧数据库：`bridges` 有行、`accounts.bridge_id` 指向它；`credentials.encrypted_payload` 里解出来是 Device Token（用备份-恢复模式读，绝不明文入库） | A9 的债没还上 → 变红 |
| P-9 | 回归：`assert-bridge.ps1`、`assert-bridge-phone.ps1`、三个老 DeepSeek 脚本全绿；两个真实 DeepSeek 账户 id+credential_id 首尾一致 | 迁移/守卫牵连老链路 → 变红 |
| P-10 | 不脚本化并写明原因：真机 Wi-Fi 扫码（等 D1）、真实 Codex 登出后配对仍有效（不许为造状态而登出真账户；该形状由 Go 侧假 app server 覆盖，Phase 5 复审 §4 的同一手法） | — |
| P-11 | 启动与地址本身（`tools/smoke/assert-bridge-bind.ps1`，无需手机）：四种配置——默认 `--pair`、`--host <LAN>`、`--host 0.0.0.0`、自定义 `--data-dir`——逐一断言：打印的监听地址 = 真绑上的地址；offer 里的地址 = 该绑定能答应的地址（回环绑定不再塞 LAN，复审 P2 的正身）；`--advertise` 指到绑定之外的地址 → 启动即失败且说清要改什么；终端打印的 `to pair a phone:` 一行**原样执行**能拿到 payload（含 `--port` 与 `--data-dir`，路径带引号且实测带空格）；LAN 绑定仍然有回环监听器可供管理路由；缺 `--data-dir` 的管理调用被拒且**不生成**新身份 | 「offer 里塞了连不上的地址」「建议的命令自己跑不通」「LAN 绑定把管理员锁在门外」「客户端偷偷 mint 身份」四种写法都会红 |

`assert-bridge-bind.ps1` 的 TLS 探测钉的是证书的 DER 指纹（读 `identity.crt.pem` 比对），与手机钉的 SPKI 摘要是两个摘要；两者在同一张证书上的一致性由 Go 侧 `TestFingerprintIsTheServedCertificatesSPKI` 与 `bind_test.go`（真 socket 打到本机 LAN 地址）负责，脚本不假装验了 SPKI。

### 5.4 门禁

- 每步：`gofmt -l ./cmd ./internal && go vet ./cmd/... ./internal/... && go test ./cmd/... ./internal/...`（基线 66 PASS 行），Gradle `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --rerun-tasks`（基线 36 suites / 411 tests / 0 failures；Lint 0 error / 31 warning，数字从 XML 数）。
- 每条新断言都要过一次「先让它红」的变异验证；不能表达为测试的（例如删掉唯一调用导致编译失败的）在文档里写明，不记成命中数。

---

## 6. 交付物

1. Go：`identity/`、`pairing/`、`server`/`main` 改动 + 上述单测。
2. Android：`pairing/`、`bridge/`、`ui/pair`、`ui/bridge`、`Database` v3 迁移、失败态与文案 + 上述单测。
3. `tools/smoke/assert-bridge-pair.ps1`（UTF-8 BOM）与 `docs/PHASE-7-PLAN.md` §10 实施记录。
4. README/HANDOFF 的状态、门禁数字与配对使用说明（含「扫码未接、D1 待拍板」的诚实边界）。

---

## 7. 提交序列（每步：改 → 测 → 提交）

| 步 | 内容 | 判据 |
| --- | --- | --- |
| 0 | 计划本文 + §0 实测事实 | 交用户拍板 D1-D4 |
| 1 | `identity/`：Bridge ID、自签证书、指纹、复用与 0600 | Go 单测绿；重启字节不变 |
| 2 | `pairing/`：pair token 与设备存储（哈希落盘、一次性、TTL、撤销） | Go 单测绿；文件里无明文令牌 |
| 3 | `server`：`/v1/pair`、鉴权中间件、绑定守卫升级 A6 | 守卫与 401 都有会红的测试 |
| 4 | `main` flag 接线 + 终端输出 payload（`--pair`/`--pair-ttl`/`--host`） | 本机实测：非回环缺 `--pair` 退出码非 0 |
| 5 | Android：schema v3 + `BridgeRepository` + 迁移测试 | 老「手输地址」账户零回归 |
| 6 | Android：`PairingPayload` + `PairingPayloadSource` 三通道 + 解析测试 | 未知键/长期凭据形状一律拒绝 |
| 7 | Android：`FingerprintPin`/`PinnedTrustManager`/`PinnedHostnameVerifier` + 测试 | 错指纹拒；不做通用 trust；主机名校验**逐连接**且源码 pin 证明没有全局默认（A10） |
| 8 | Android：`PairingClient` + 状态与文案分离 | 三态互不相同且都不提 API Key |
| 9 | Android：配对页 + 设备管理页 + `AccountEditActivity` 两条路径 | 截图为证；布局不挤掉既有区块 |
| 10 | `assert-bridge-pair.ps1`（P-1…P-10）+ 文档 + 门禁复跑 | 全绿，或逐条写清为何不计分 |

---

## 8. 需要用户拍板（定了我才写对应代码）

| # | 决策 | 选项 | 我的倾向 |
| --- | --- | --- | --- |
| **D1** | 扫码与「零第三方依赖」（§0.2）怎么解 | ①本阶段不做相机解码，协议 + 三条 dep-free 通道交付，扫码留作第四个 `PairingPayloadSource` 实现；②接受第三方依赖（ML Kit 或 zxing），本阶段就把扫码做完；③自己写 QR 解码器 | **①**。它不动 Spec 的红线、不引入 Play 依赖，而且把「扫码」变成一次性接线。②最贴合 Spec §20 的字面，但要你明确同意为它破 L22；③的工作量超过 Phase 7 其余部分总和 |
| **D2** | 是否在这台机器上真把 Bridge 绑到局域网（配对必须让手机连进来） | ①做，且与 TLS + 强制令牌同批（A5/A6 的守卫是硬的）；②本阶段只到「守卫与协议齐备、默认仍回环」，真机 LAN 留到你选定的机器上开 | **①**，但要点在于：这是本项目第一次对外可达，验收必须含 P-5（无令牌的明文请求被 401、缺 TLS 的非回环绑定启动失败）。若你不想让本机现在监听网段，选 ②，我把 P-1/P-2 改成在 `10.0.2.2` 上走 TLS 的同形验证 |
| **D3** | §0.3 的读法确认 | ①按本计划读法（一次性 pair token 允许进 payload；OpenAI 凭据 / Device Token / API Key 禁止）；②pair token 也不进 payload，改为短码由人转述 | **①**（Spec §20 明确要求 payload 带它），且本计划把它写成可机器检查的断言 |
| **D4** | 手动填 IP 的去留（Spec 要「删除作为主要流程，保留用于调试」） | ①保留但降级为「调试」标签，配对成为推荐路径；②彻底删除手输 | **①**。D1 选 ① 时手输是唯一真机可用通道；删了会让手机没法用 |

---

## 9. 风险

| # | 风险 | 处置 |
| --- | --- | --- |
| R1 | 第一次对外可达 = 第一次真实攻击面 | A5/A6 同批 + P-5 硬断言；默认回环不变，LAN 只在 `--pair` 下开 |
| R2 | 自签证书 + 钉指纹在部分 Android 版本/ROM 上握手细节不同 | 用 `HttpsURLConnection` + 自定义 TrustManager（不 hack SSLContext）；minSdk 23 起在验收里真跑；失败形状有专属测试（P-2） |
| R3 | schema v3 迁移与真实设备数据（两把不可重输的 DeepSeek Key）同库 | 迁移在测试库里跑；设备验收按备份-恢复模式（`assert-daily-usage.ps1` 已建立的写法），且绝不写真 Key、绝不删真账户 |
| R4 | 「配对状态重启不丢」这类持久性最容易在 CI 里假绿 | P-6 用文件字节与重启后真读额度双重断言，且先证伪（故意让 identity 每次重生成 → P-6 必红） |
| R5 | 令牌字面值的泄漏渠道很杂（日志、异常、dump、终端） | 服务端只存哈希；`redact` 出口已有；P-7 扫 `pairing.json`/`state.json`/logcat/dump 四类，并按进程归属拆开（Phase 6 已踩 adbd 回显） |
| R6 | D1 悬而未决 → 「配对」做完了但手机上没有扫码，容易被当成已交付 | §1 与 §6 把边界写死；README 不写「扫码」二字，直到真接线 |
| R7 | 明文调试通道（回环 + 无令牌）继续存在，可能被误用为真机方案 | 只放行 `10.0.2.2`/`localhost`（现状）；配对账户必须 TLS + 令牌；UI 上「调试」标签明示 |
| R8 | 为让 LAN 地址连上而写 `setDefaultHostnameVerifier { true }`（Android 上最常见的「修法」）——一句全局放开就把 DeepSeek 与所有其它 HTTPS 请求的证书校验一起废掉 | A10 定成逐连接 verifier；`PinnedHostnameVerifierTest` 里源码 pin「`setDefaultHostnameVerifier` 出现 0 次」；P-2 用服务器侧请求计数证明拒绝发生在发出请求之前 |
| R9 | 配对 payload 里的地址与 Bridge 实际监听的地址脱钩（回环绑定却把 LAN 地址交给手机），以及终端建议的 `--add-device` 缺 `--data-dir`/`--host` 而连到别的实例 | §0.1 的「offer 由绑定推导」+ P-11 的四配置启动验收；地址不在该绑定能答应的集合里就拒绝启动，而不是发一个手机连不上的码 |
| R10 | 短码通道的人工核对可能被跳过：屏幕上「这枚指纹和电脑终端上的一样吗」渲染得再清楚，脚本也无法证明有一个人真的比对了（A11 的另一半） | 能机器检的半边全机器检：`PairingClient.exchangeManual` 在没有 `userConfirmed` 时**不发**请求（`PairingClientTest#theTypedCodeStaysHomeUntilAHumanConfirmsTheDigest` 断言的是请求数为 0，不是文案），且发出去那条连接钉的就是被确认的摘要（`#theCodeOnlyGoesToTheDigestThatWasShown`）；P-3 在设备侧断言「未确认时服务器端没有收到任何 `/v1/pair`」。剩下的「人有没有看」只能靠界面把它做成必经一步，这一条不假装已被验收覆盖 |

---

## 10. 实施进度（截至 2026-10-03 本轮）

### 已交付：Windows/Bridge 半边（步骤 1-4）

| 步 | 提交 | 内容 | 门禁与实测 |
| --- | --- | --- | --- |
| 1 | `331abdc` | `internal/identity`：Bridge ID、RSA-2048 自签证书、SPKI SHA-256 指纹；缺文件/坏文件/换错密钥一律报错而非重铸 | 7 测试；6 变异 5 命中、1（Windows 上不携带 POSIX 权限位）由该测试自己 skip 并说明原因 |
| 2 | `71be00a` | `internal/pairing` + `internal/filestore`：pair token/短码/设备令牌全部只存 SHA-256 摘要；一次性、TTL、超次作废、撤销保留记录 | 12 测试；10 变异 9 命中、1 无法表达（删调用即编译失败，改写成不可达写后命中） |
| 3 | `a0ca7e0` | `/v1/accounts/*` 挂设备令牌门；`/v1/pair`；绑定守卫升级为「非回环必须同时有 TLS 与凭据注册表」；health 播报 id/指纹/TLS/配对数 | 5 测试；6 变异全命中，其中放宽守卫那条**同时**被 Phase 5 原有的两条 B5 守卫测试命中 |
| 4 | `99733c4` | `pairing.BuildPayload/ParsePayload`（`aiusage://pair#…`）、`/v1/admin/*` 只答回环、`--pair/--pair-ttl/--advertise/--add-device` | 真起 exe 走完整回合（见下） |

步骤 4 的实测（脚本在忽略目录里跑，不把一次性程序留在仓库）：

```text
非回环且不带 --pair        exit=1 "refuses to bind a non-loopback address: 28.0.0.1 …"
offer 反解字段             bridgeId / fingerprint / hosts / pairToken / port / v
health 指纹 == offer 指纹   True，tls=True
无令牌读额度                401 CODEX_AUTH_REQUIRED
兑换后立即重放              200 然后 401
带设备令牌读额度            200，真 Codex 数据（5 小时 9%）
明文打到 TLS 端口           "Client sent an HTTP request to an HTTPS server."，无数据
列出→撤销→再读              200 / {"revoked":true} / 401
```

跑完无残留进程；默认仍是只绑回环，本机没有因为本阶段多监听任何网段。

### 复审处置（`docs/PHASE-7-REVIEW.md`，2026-10-03）

一份外部复审交了 1 项 P1 + 4 项 P2。逐条先复现再修，修完各自独立提交：

| 发现 | 复现 | 处置 | 证据 |
| --- | --- | --- | --- |
| 撤销落盘失败被说成「设备不存在」 | 把 `pairing.json` 换成目录后打 `/v1/admin/devices/revoke` | `827435f`：`commit()` 先落盘再生效；`ErrNotPersisted` → 503，只有 `ErrUnknownDevice` → 404 | `TestAdminDistinguishesRevocationFailures`；变异「把持久化改成只改内存」被它打死 |
| 猜测锁定不持久，重启恢复旧短码 | 20 次失败后重开 Store，旧码仍可兑换 | `827435f`：`rejectGuess()` 连同烧毁状态一起落盘 | `TestLockoutSurvivesARestart` |
| 零字节 `pairing.json` 当首次启动 | 造一个 0 字节文件再起 Store | `827435f`：只有 `os.ErrNotExist` 是新装，空文件按损坏报错并保住原文件 | 新增空文件用例 |
| 配对地址、监听地址与 CLI 管理路径不一致（P2 主体） | 用 `99733c4` 的源码单独编一份旧二进制，在临时 data-dir 里真跑：`listening on https://127.0.0.1:39611` 却打印 `addresses offered to phones: 28.0.0.1, 127.0.0.1`，建议行是 `aiusage-bridge --port 39611 --add-device`（没有 `--data-dir`，程序名也是裸的）——复审描述逐字对上 | `8aeea1e`：offer 由绑定推导并与绑定核对；LAN 绑定补出回环监听器；`ListenAll` 采纳真实端口；`--add-device` 固定走回环且用 `identity.OpenExisting`（不再 mint）；打印的建议行带 `--port` 与带引号的 `--data-dir` | Go：`address_test.go` + `bind_test.go`（真 socket 打到本机 LAN 地址：钉指纹可握手、错指纹拒、回环 admin 拿得到 payload、LAN 源地址打 admin 得 404）；进程：`tools/smoke/assert-bridge-bind.ps1` 四配置 53 断言 0 失败。8 次变异全命中（`tools/smoke/out/mutate-bind-results.txt`），另 2 次在 CLI 层（去掉 `--data-dir`、打印未采纳的列表）也被该脚本打死 |
| 综合复审 §2.5（`docs/PHASE-0-7-REVIEW.md`）：`Issue()` 在落盘前就清掉 fail-closed 标记 | 猜测写盘失败 → `refused=true` → 管理员再签发也写失败 → 内存里旧码重新可兑换，而磁盘从没同意过任何一步 | `abcd8a7`：标记只在 `commit()` 成功后清除 | `TestIssueCannotLiftTheRefusalWithoutWriting`（修前红：「a failed Issue lifted the refusal and let the old code back in」）；Go 门禁 108→109 |
| 计划里的 Android TLS 主机名校验会拒绝 LAN 证书（P1） | 读 `identity.go` 的证书模板 + 实测 `identity.crt.pem` 的 SAN（只有 localhost/127.0.0.1/::1，无 LAN），对照 `HttpsURLConnection` 文档「握手后仍跑 HostnameVerifier」 | 本轮只改计划：新增 A10 逐连接 `PinnedHostnameVerifier` 策略（禁止全局 `setDefaultHostnameVerifier`、禁止无条件 true）、§0.1 增实测行、§3.2 增 `PinnedHostnameVerifier.java` 与单一装配出口、§5.2 增 `PinnedHostnameVerifierTest`（含源码 pin）、P-1/P-2 改写、R8 登记；Android 步骤 7 动工前它就是硬前置 | 未写代码=无运行证据，这一条只登记为计划变更，不算交付 |

本轮门禁与回归（复跑，不是引用旧数字）：`gofmt -l` 空、`go vet ./...` 干净、`go test -count=1 ./...` **108 PASS 行（含 2 条子测试）/ 0 失败 / 2 跳过**（8 包，步骤 1-4 结束时是 98）；`assert-bridge-bind.ps1` 53/0（`RESULT: *PASS*`）；`assert-bridge.ps1` 38/0。

`assert-bridge-phone.ps1` **本轮未跑完，不计分**，两件事叠在一起：

1. 脚本自身两个缺陷（提交 `2d10035`）：一条 UI 步骤失败时，`Check` 的 `[bool]` 参数收到「日志行 + 值」的数组而抛异常，整轮在 C1 就中断（第一次重跑：57 项里 32 红，全是那一条的下游）；改成 `Write-Host` 记日志后，失败被逐条计出，才看得见根因。
2. 环境事实：模拟器的软键盘开始吞掉注入按键——`input text probe` 打到普通文本框一个字不进（逐字符 + 200ms 也只进 'p'），同屏密码框 14 个字符全进。修法是在整轮开始前 `ime disable` 当前软键盘、清理时还原，并且只在 `mDecorViewVisible=true` 时才发 KEYCODE_BACK。金丝雀「injected text reaches a plain-text field」在修好后的第一轮 **PASS**（`tools/smoke/out/regress-phone3.log`），紧接着模拟器自己挂了（emulator 日志 `detected a hanging thread 'QEMU2 CPU0 thread'`，重启后长时间不注册设备），所以 C 系列要等一台活设备重跑。

### 已交付：手机半边第一批（步骤 5-8，2026-10-03）

| 步 | 提交 | 内容 | 门禁与实测 |
| --- | --- | --- | --- |
| 5 | `597ebaa` | `Database` v3 + `bridges` 表（单事务迁移，无 `INSERT`）、`model/Bridge`、`BridgeRepository` + `SqliteBridgeRepository`、`AccountManager.attachBridge/bridgeIdOf` | 设备侧**可逆**彩排 `assert-bridge-migration.ps1`：18 断言 0 失败（备份真库→降到 v2→升回 v3→逐表比对→字节级还原） |
| 6 | 步骤 6-8 提交 | `PairingPayload`（`aiusage://pair#<base64url(json)>`：未知键、缺字段、版本不符、形状不符全部拒绝，并按字段名报错）、`Base64Url`（minSdk 23 用不了 `java.util.Base64`，手写并钉边界）、`PairingPayloadSource` 三通道：`TextOfferSource`（深链 / 粘贴共用「字符串进」这一条）、`ManualSource`（地址 + 短码） | 19 条单测（`PairingPayloadTest` 12 + `Base64UrlTest` 7），含**用 Go 真产出的 payload** 做向量（`GoVectors`）：两语言同一份格式各写一遍，正是漂移的来源 |
| 7 | 同上 | `FingerprintPin`（SPKI SHA-256）、`PinnedTrustManager`、`PinnedHostnameVerifier`（A10）、`BridgeTls`（唯一的建连出口：钉证 + 逐连接 verifier 一起装；探针通道的两件 accept-any 是**命名类**，`PairingWiringTest` 数得出每一个装配点） | 12 条单测（`TlsPinningTest` 7 + `PairingWiringTest` 5）：Android 算出的摘要 == Go 播报的摘要；错指纹的 Go 真证书被拒 |
| 8 | 同上 | `PairingClient`（hosts 按序试连；401 / 503 / 握手失败 / 应答缺 token 四类文案互不混用；A11 的确认门在**客户端**而不是界面）、`PinnedPairingTransport`、`AddressResolver` + `DeviceProfile`（只有模拟器把回环改写成 `10.0.2.2`，两条路径钉同一枚摘要） | 18 条单测（`PairingClientTest` 11 + `AddressResolverTest` 7）；`onlyTheEmulatorDialsTheLoopbackAddressSomewhereElse` 同时钉住「真机不改写 / 模拟器改写 / 两边都还钉指纹」 |

与计划不同的两处命名（协议与测试不受影响）：包名用 `bridge/` 而不是 §3.2 写的 `pairing/`（和步骤 5 的 `BridgeRepository`、`model/Bridge` 同一侧，不给同一个概念两个包）；`PasteSource`/`DeepLinkSource` 合成了一个 `TextOfferSource`（两者都只是「字符串进」，差别只有标签，而标签是界面要显示的）。

顺手修掉的事实错误与跨语言缺口：

- `pairing.codeAlphabet` 的注释写着排除 `2/z`，实际只排了数字 `2`（`z` 在表内）。Android 侧照抄的那句会告诉用户「你输入的 `z` 不合法」，而那正是电脑刚发出来的码。两处注释与界面文案现在都写「不含 0、1、2 和 o、l、i」。
- Go 的 `net.IP.String()` 会给出裸 IPv6（`::1`、`2001:db8::1`），而 Android 的 `requireHosts` 见 `:` 就拒、`baseUrlFor` 也不加中括号：**只要有一台机器把 IPv6 报进 offer，这枚配对码在手机端就是废的**。现在按「两个以上冒号 = IPv6 字面量」区分裸地址与 `host:port`，建 URL 时补中括号；手输框仍然拒绝把端口写进地址（那是另一个输入框的内容），并拒绝中括号未闭合的半成品。

变异测试跑了五轮、共 **44 条**，最终全部命中；过程中 **7 条逃过**，每一条都变成了一条新的会红断言而不是被忽略：第一轮 34 条里 6 条无人发现，全部是「断言只要求出错、不要求原因」那一类（空的 `hosts` 列表、`BridgeTls` 的空指纹门——它其实被内层 `PinnedTrustManager` 兜住，断言只写「报了指纹错」就等于没钉这一层；短码的长度规则与字符表规则互相掩盖；base64url 的两条边界），改成指名原因的断言并各自留一个只违反一条规则的用例后才收口；第二轮新增的 IPv6 相关里又逃过 1 条（中括号未闭合）。找缺口过程中改出的**两个真缺陷**：手输通道探测走**解析后**的地址、发码却走**解析前**的（模拟器上会把码发给手机自己，而用户核对的是另一台机器的指纹），现在两条同用一个 `addresses.baseUrl(...)`，并由 `theManualChannelProbesAndExchangesThroughTheSameAddress` 钉住；以及假传输（`FakeTransport`）原本只记「答了的」请求，于是「两条地址都试过了」这条断言永远只能看到第二条——改成进入 `post()` 就记账、再决定抛不抛，试连顺序才是可断言的东西。同一类问题在 `assertFailure` 上又出现一次：它进 case 前把调用方刚设的 `handshakeFails` 清掉了，于是「握手失败要说指纹不符」实际测的是上一条 case 留下的应答。

门禁（步骤 6-8 之后复跑，数字取自报告而不是控制台）：Android `SUITES=46 TESTS=487 FAILURES=0 ERRORS=0 SKIPPED=0`（步骤 5 之前 40/438；新增 6 个测试类共 49 条，逐类见 §10 上表）；`assembleDebug` 出包 `app/build/outputs/apk/debug/app-debug.apk`；lint **0 error / 35 warning**，其中 5 条落在 `bridge/`（`BadHostnameVerifier`×1、`TrustAllX509TrustManager`×2、`CustomX509TrustManager`×2）——全部指向刻意为之的探针通道与钉证 TrustManager，按本项目对 `UnusedAttribute` 的同一立场**不压制**（另 1 条 `AndroidGradlePluginVersion` 因 `--offline` 拿不到版本探测而缺席，基线 31→30 的来源就是它）。Go 门禁 `gofmt -l` 空、`go vet` 干净、`go test -count=1 ./...` **109 PASS / 0 失败 / 2 跳过**（与步骤 4 复审后同数，本轮未动 Go 逻辑，只改了那条注释）。

### 已交付：配对落库、按配对读取、配对界面（步骤 9，2026-10-03）

| 内容 | 提交 | 谁能把它测红 |
| --- | --- | --- |
| `PairingStore`：配对结果写成 `bridges` 行 + `BRIDGE_TOKEN` 账户 + `accounts.bridge_id`；同一 Bridge ID 换指纹直接拒绝、重配保留首次配对时间、写凭据失败则回滚本次调用创建的东西 | 步骤 9 提交 | `PairingStoreTest` 10 条；8 次变异全命中（`tools/smoke/out/mutate-store2-report.txt`），其中两条专门钉「只回滚自己创建的」与「失败时一行都不留」这两个相反方向 |
| 读侧按行解析：`AccountRefreshManager` 用 `bridge_id` 取行，用行里的 `base_url` 与 `fingerprint` 重建 `AuthContext`；行没了落 `BRIDGE_PAIRING_REQUIRED` 且**一次请求都不发**；读成功在同一个监视器里 stamp `last_seen` | 同上 | `PairedAccountReadsThroughItsComputerTest` 6 条；6 次变异全命中（`mutate-reader-report.txt`），含「地址仍从凭据里取」「digest 没传给 provider」「没有存储时静默放行」 |
| `BridgeCodexDataSource.chooseTransport`：有指纹只走钉指纹的 HTTPS 通道；HTTPS 但没指纹、或指纹配 `http://` 都拒绝；全空白指纹按「没有指纹」处理（拒绝而不是抛未检查异常） | 同上 | `BridgeCodexDataSourceTest` 新增 5 条 + `PinnedReadTransportTest` 4 条；5 次变异全命中（`mutate-reader2-report.txt`） |
| 配对页（粘贴 / 深链 / 手输地址 + 短码 + 尾号人工核对）、设备页（列电脑、看尾号与上次联系、忘记）、账户编辑页的「配对（推荐）/手输（调试）」两条路径、`aiusage://pair` intent-filter | 同上 | 界面本身**没有设备证据**（见下）；能机器检的那半边是 `PairingWiringTest#thePairingScreenCannotSendTheCodeWithoutTheConfirmation`：屏幕那条 `exchangeManual(...)` 只能由勾选框之后的按钮触发，且复选框初始为 false |

本轮门禁（复跑）：Android `SUITES=49 TESTS=512 FAILURES=0 ERRORS=0 SKIPPED=0`（步骤 6-8 之后是 46/487；本轮新增 25 条：`PairingStoreTest` 10、`PairedAccountReadsThroughItsComputerTest` 6、`PinnedReadTransportTest` 4、`BridgeCodexDataSourceTest` +5，`PairingWiringTest` +1）；`assembleDebug` 出包；lint **0 error / 40 warning**，较步骤 8 后 +5，全部与既有界面同族（`LockedOrientationActivity`×2 = 两个新 Activity 沿用全应用的竖屏约束、`DiscouragedApi`×2、`SetTextI18n`×1），步骤 6-8 那 5 条钉证/探针相关的仍按同一立场**不压制**；Go 门禁未动，仍 `109 PASS / 0 失败 / 2 跳过`。

变异两轮共 **67 条，全部命中**：步骤 6-8 的 52 条（含 `PairingStore` 的 8 条）在步骤 9 改完 `BridgeTls`/`PairingPayload` 之后**整轮重跑**一遍，`tools/smoke/out/mp-final-report.txt`；步骤 9 自己的 15 条（reader 5、transport 选择 5、钉指纹读 3、配对界面 2）在 `mr-final-report.txt`。界面那 2 条命中的是 `thePairingScreenCannotSendTheCodeWithoutTheConfirmation`——它不是「界面长对了」的截图断言，而是「屏幕这条发送路径的守卫被拆掉就会红」的源码断言；界面本身的行为仍要等设备验收（见下）。

**还没有做的两件事**，不要在读这份记录时误以为已完成：

1. **步骤 9 的界面没有任何设备证据**：本轮没有跑 `assert-bridge-pair.ps1`（脚本本身也还没写，见步骤 10），也没有安装到模拟器截图。所以「配对页能用」目前是编译与逻辑层面的断言，不是观察到的行为。
2. **A9 的重复地址仍在**：配对账户的读已经不看凭据里的 `bridgeUrl`，但那个字段还在写入（Phase 6 的手输账户靠它）。清掉它要一次凭据重写迁移，登记为遗留。

一处与计划不同的做法需要先记下：schema v3 的迁移**不能**只在 JVM 里测——`android.database.sqlite` 在宿主 JVM 是桩，所以 v2→v3 的验证沿用 Phase 3 已建立的办法：在真机/模拟器上把设备库降级→升级回来，逐表比对（`assert-bridge-migration.ps1` 已按这套跑过 18 断言），并照例先备份后恢复、绝不写真 Key、绝不删两个真实 DeepSeek 账户。
