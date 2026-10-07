# Geyser 扩展开发说明：ConnectPlus 身份桥接协议 v1

更新：2026-10-05。状态：已实现的官方宿主桥接协议；真人验收尚未执行。

本文可独立交给 Geyser 扩展开发者。ConnectPlus 侧实施文档见 [ConnectPlus 开发计划](../superpowers/plans/2026-10-02-bedrock-account-linking.md)。协议发生变更时，双方同步修改这两份文档；不得各自改变字段或调用顺序。

## 0. 永久宿主边界（2026-10-04 用户确认）

当前及后续所有功能都不得修改 ViaProxy 或 Geyser-ViaProxy 的源码、JAR、类、认证流程或重复登录逻辑，也不得以字节码、Java Agent、反射注入或网络处理器替换实现同等修改。普通插件/扩展 API、运行配置和只读适配器探测可以使用。

本版本使用官方宿主。三项基础能力构成完整支持范围；同 XUID 重复登录保留原生拒绝行为。Java/基岩关联档案的会话互斥继续由 ConnectPlus 实现。`authenticated-duplicate-admission` 是可选协议能力，当前扩展不声明、不发送相关事件；只有将来官方 API 自身提供经过验证的接入点时才可另行评估。

## 1. 交付目标和部署方式

运行环境为同一个 ViaProxy 进程，Geyser 使用 Geyser-ViaProxy 形态。需要开发一个 Geyser Extension，暂定名称 ConnectPlus-GeyserBridge，提供可信的基岩玩家身份和精确的连接关联。

预期安装结构如下，Geyser 数据目录以官方宿主实际路径为准：

```text
ViaProxy/
  plugins/
    ConnectPlus.jar
    Geyser-ViaProxy.jar
    Geyser/
      extensions/
        ConnectPlus-GeyserBridge.jar
```

扩展采用 Geyser 的 extension.yml、Extension 和生命周期事件机制，不按 Bukkit 插件编写。ConnectPlus 不内嵌 Geyser，也不要求跨进程转发 XUID。v1 不支持独立 Geyser、其他代理链、多个 ConnectPlus 实例之间的全局顶号或身份转发。

### 职责边界

| 功能 | Geyser 扩展 | ConnectPlus |
| --- | --- | --- |
| 确认当前基岩连接已完成可信 Xbox 身份认证 | 实现 | 信任已注册的进程内提供者，并检查会话有效性 |
| 提供 XUID、基岩名称、唯一会话标识 | 实现 | 接收和校验 |
| 将基岩会话对应到 ViaProxy 的真实客户端 Channel | 实现关联算法 | 捕获原始连接信息，调用扩展查询 |
| 判断 Java 客户端是否完成正版身份校验 | 不处理 | 实现 |
| 微软 Java 账号授权、令牌加密与刷新 | 不处理 | 实现 |
| XUID 与 Java UUID 的绑定、解绑 | 不读写 | 实现 |
| 书签、档案、登录信息 | 不读写 | 实现 |
| 决定哪个 Java 账号被新登录占用 | 不决定 | 实现 |
| 精确关闭指定基岩会话、传达顶号提示 | 实现 | 决定时机，先完成旧档案写入 |
| 同 XUID 两台基岩客户端重复登录 | 保留官方 Geyser 原生拒绝 | 不处理重复准入事件，旧会话保持在线 |

扩展绝不传递 Microsoft access token、refresh token、密码或 ConnectPlus 档案内容。XUID 也不应放到聊天、握手主机名、客户端可构造的插件消息中作为身份凭据。

## 2. 开工前必须验证的两项能力

先用锁定版本完成最小原型，再开发正式接口。记录 ViaProxy 版本、Geyser 版本及提交号、扩展版本、运行 JDK、认证配置。ConnectPlus 当前编译基线为 Java 17 / ViaProxy 3.4.13；这不是对任意 Geyser 版本的运行兼容保证。

### 2.1 精确关联 ViaProxy 连接

需要证明能够从某个活跃 GeyserSession 找到其通往 ViaProxy 的下游连接，并与 ViaProxy 接收到的 c2p Channel 一一对应。

关联依据优先为实际连接对象；若使用 TCP 地址，必须同时匹配两端地址、端口、活跃会话和此次连接生命周期。必须区分 IPv4、IPv6、地址规范化和端口复用。不能只按玩家 IP、昵称、Java UUID 或 XUID 查到一个人就认定是当前连接。

Geyser-ViaProxy 会在初始化过程中改写 ViaProxy 看到的远端地址。ConnectPlus 将在 Client2ProxyChannelInitializeEvent 的 PRE 阶段保存原始地址；扩展需要结合 Geyser 下游连接的真实本地/远端地址进行关联。开发者必须验证锁定版本的执行顺序，不能依赖改写后的玩家 IP。

公开 Geyser API 能查玩家，不等于它公开了上述 Channel 关联能力。允许隔离一个明确版本范围的内部适配器；不匹配的版本必须拒绝启用桥接，不能猜测匹配结果。

### 2.2 同 XUID 重复登录：原生策略

官方 Geyser 在公开会话初始化/登录事件之前拒绝重复 XUID。当前扩展保留该行为：先登录客户端继续在线，后登录客户端被拒绝，不产生重复登录候选，不触发旧玩家下线。

不得修改宿主或以运行时注入绕过检查。此限制是用户确认后的交付范围，不影响 Java/基岩关联档案在 ConnectPlus 内的互斥占用。

## 3. 信任、身份字段和生命周期

v1 只接受直接通过本机 Geyser 验证的基岩连接。关闭 Xbox 登录校验、离线伪造身份、未经专门验证的 Waterdog 等外部身份转发模式不在支持范围。运行时配置为“要求认证”本身不证明某条已有会话已经完成认证；提供者必须跟踪认证完成的实际结果。

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| protocolVersion | Integer | 固定为 1 |
| providerId | String | 固定为 connectplus-geyser-bridge |
| providerEpoch | String | 每次扩展启用时生成的新 UUID，重启不可复用 |
| connectionId | String | ConnectPlus 为每个 c2p Channel 生成的 UUID |
| bridgeSessionId | String | 扩展为每个真实基岩连接生成的 UUID，不能使用 XUID 代替 |
| xuid | String | 已验证的正整数十进制字符串，范围 1 至 18446744073709551615，不含前导零 |
| bedrockUsername | String | 当前基岩名称，仅显示用途，不用于授权或文件路径 |
| clientVersion、locale | String，可省略 | 仅显示/本地化用途，不影响身份可信性 |

所有 ID 必须与同一次注册、同一个存活的真实连接对应。相同 XUID 重连会得到新的 bridgeSessionId 和 connectionId。不要将 XUID 解析为有符号 Java long。

### 3.1 ConnectPlus 档案与关联文件

Java 认证完成的只读判据要求客户端连接仍存活且 `ENCRYPTION_ATTRIBUTE_KEY` 的实际加密对象非空。官方 `PacketCryptor` 会为所有连接分配此属性；`hasAttr` 为真或属性值为空均不构成 Java 正版认证。未经 Java 认证的连接继续走桥接 `RESOLVE`，不能先当作 Java 玩家读取档案。

基岩身份 `VERIFIED` 后，ConnectPlus 先取得档案租约，再加载已有档案。未绑定玩家首次进入大厅时，在存储线程中立即保存 `plugins/ConnectPlus/players/bedrock/<XUID>.json`，不等待微软 Java 账号登录、书签或设置操作；保存失败则拒绝交付档案并归还租约。已存在的档案不因进入大厅而重写。

基岩玩家完成微软 Java 账号授权并成功提交关联后，绑定索引写入 `plugins/ConnectPlus/players/identity-links.json`，当前玩家使用 `players/java/<JavaUUID>.json`，旧 `players/bedrock/<XUID>.json` 按关联事务删除。不存在每个账号单独的 `.link` 文件。已绑定玩家再次进入大厅直接使用对应 Java 档案，不重建基岩 JSON；解绑后重新建立独立的空白基岩档案。

## 4. 进程内接口约定

为避免双方插件类加载器加载出不同的 DTO 类，v1 边界只使用 JDK 类型 Map、String、Integer、Function、CompletionStage 等。Channel 以实际宿主对象传入，禁止序列化、克隆或使用客户端提交的数据替代。

以下是 **ConnectPlus 已实现的公共接口**。扩展通过 ViaProxy.getPluginManager().getPlugin("ConnectPlus") 获得真实插件实例，再以反射调用其公共方法：

```java
public Map<String, Object> registerBedrockBridgeV1(
    Map<String, Object> descriptor,
    Function<Map<String, Object>, CompletionStage<Map<String, Object>>> handler);

public CompletionStage<Map<String, Object>> bedrockBridgeEventV1(
    String registrationId,
    Map<String, Object> event);
```

扩展依赖 Geyser/ViaProxy/Netty 时使用 compileOnly，不把宿主依赖打入自身 JAR。所有边界对象使用不可变快照；接收方校验类型和必填字段，不能假定外部 Map 一定合法。

### 4.1 注册

descriptor 必须包含 protocolVersion、providerId、providerEpoch、bridgeVersion、geyserVersion、viaproxyVersion，以及 capabilities。后三个版本字段为 String，capabilities 为 List<String>，基础支持必须包含以下三项：

```text
verified-xuid
exact-channel-binding
targeted-disconnect
```

可选能力 authenticated-duplicate-admission 不影响基础注册。ConnectPlus 保存注册时的不可变能力快照；未声明此能力的提供者发送 VERIFIED_DUPLICATE_CANDIDATE 时返回 REJECTED/INVALID_REQUEST，不调用重复登录协调器。当前官方宿主扩展不声明此能力。

注册成功返回 status=REGISTERED、protocolVersion=1、registrationId。registrationId 为 ConnectPlus 生成的随机 UUID 字符串，仅用于此次进程内注册，不输出日志、不持久化、不传给客户端。

失败返回 status=REJECTED 和 reasonCode，取值为 DISABLED、UNSUPPORTED_PROTOCOL、UNSUPPORTED_RUNTIME、MISSING_CAPABILITY、PROVIDER_ALREADY_REGISTERED 或 INVALID_REQUEST。只允许一个提供者；同一 providerEpoch 重试也不能覆盖现有注册。扩展保存成功结果以避免重复注册。

注册声明的 viaproxyVersion 必须与实际运行的 ViaProxy 版本一致。双方在各自适配层只读访问已加载宿主的公开 VERSION 字段；不得直接引用这个编译期常量，否则 Java 会将构建依赖的版本写入插件，造成升级后的误拒绝。真实版本不一致仍返回 UNSUPPORTED_RUNTIME，ConnectPlus 日志会显示 `Bedrock bridge declares host version ... but this process runs ...`。此处的运行版本读取修复只修改 ConnectPlus，不改变官方宿主或放宽桥接的受支持版本系列检查。

扩展在 Geyser 初始化完成、ConnectPlus 可用后注册。若加载顺序导致暂不可用，使用有界的异步重试：每秒一次、最多 30 次；超时记录停用原因。ConnectPlus 不存在时只停用桥接，不使 Geyser 崩溃。热重载不属于 v1 的无感支持范围。

### 4.2 ConnectPlus 调用扩展：handler

每次请求必填 op、protocolVersion=1、providerEpoch。响应必填 status、protocolVersion=1、providerEpoch，并原样回传请求中存在的 connectionId 和 bridgeSessionId；RESOLVE 成功时新增 bridgeSessionId。

| op | 请求特有字段 | status | 行为 |
| --- | --- | --- | --- |
| RESOLVE | connectionId、channel、rawLocalAddress、rawRemoteAddress；可选 wireUuid、wireName | VERIFIED / NO_MATCH / REJECTED / UNAVAILABLE | 查询此次 c2p 的可信基岩身份 |
| VALIDATE | connectionId、bridgeSessionId、channel | VALID / INVALID / UNAVAILABLE | 确认此前映射仍绑定到同一个活跃基岩会话和 Channel |
| DISCONNECT | connectionId、bridgeSessionId、reasonCode、message | CLOSED / ALREADY_CLOSED / STALE / UNAVAILABLE | 只关闭指定的旧基岩连接 |

channel 为 ViaProxy 的 io.netty.channel.Channel 对象；两个地址字段是 java.net.SocketAddress。wireUuid 和 wireName 是 String，只用于交叉核对。它们不是身份凭据，也不能替代原始连接匹配。

VERIFIED 响应还必须含 xuid 和 bedrockUsername，可含 clientVersion、locale。不得返回一个用于绕过证明的独立 trusted=true 字段；VERIFIED 本身表示第 2、3 节条件全部满足。

NO_MATCH 仅表示未匹配到基岩会话，不表示已验证为 Java 玩家。REJECTED 表示已识别到相关会话但认证不可信或数据冲突；UNAVAILABLE 表示初始化、运行版本或提供者异常。负面结果不得携带可供 ConnectPlus 继续使用的身份快照。

DISCONNECT 的 reasonCode 取 REPLACED 或 BRIDGE_UNAVAILABLE。REPLACED 的 message 必须原样传给玩家：

> 检测到异地登录，你的账号已在其他客户端登录，当前连接已断开。

ALREADY_CLOSED 只在指定的旧会话确实已关闭时返回；若同 XUID 已经是新会话，返回 STALE，不得关闭新会话。ConnectPlus 可独立关闭指定 c2p Channel，扩展负责将提示送到相应基岩客户端并清理 Geyser 侧状态。

### 4.3 扩展通知 ConnectPlus：bedrockBridgeEventV1

event 必填 op、protocolVersion=1、providerEpoch。每次调用携带当前 registrationId，旧注册的调用返回 STALE。

| op | 额外字段 | 返回 status | 行为 |
| --- | --- | --- | --- |
| SESSION_CLOSED | connectionId、bridgeSessionId | ACK / STALE | 仅使指定会话失效，重复通知可安全重放 |
| PROVIDER_STOPPING | reasonCode | ACK / STALE | 停止接受新桥接身份并使本次注册失效 |
| VERIFIED_DUPLICATE_CANDIDATE（可选能力，当前扩展不发送） | xuid、oldBridgeSessionId、newBridgeSessionId | READY / DENIED / STALE | 在可信认证完成后协调同 XUID 顶号 |

reasonCode 为诊断代码字符串，不包含玩家输入、令牌或完整个人信息。所有响应回传 protocolVersion、providerEpoch；重复登录事件还回传 newBridgeSessionId。字段错误统一返回 REJECTED、reasonCode=INVALID_REQUEST。

重复登录事件的具体规则：

- 新旧会话必须属于当前 providerEpoch、同一已验证 XUID，且不是同一个 bridgeSessionId。
- 只有旧会话已由 ConnectPlus 管理，才可由此流程替换；否则返回 DENIED、reasonCode=OLD_SESSION_NOT_MANAGED，保持 Geyser 原有拒绝行为。
- ConnectPlus 在每个 XUID 上串行处理候选请求，并将关闭操作绑定到 oldBridgeSessionId；扩展在继续新连接前再次验证候选仍活跃，旧会话已关闭。
- READY 仅说明指定旧连接已完成安全退出；它不是 Java 账号授权，也不授予档案权限。新 c2p 仍必须成功 RESOLVE 并由 ConnectPlus 占用目标账号。
- 新候选消失、保存失败、超时或会话不匹配，返回 DENIED 或 STALE。DENIED 可附 reasonCode=SAVE_FAILED、TIMEOUT 或 INVALID_IDENTITY。不得直接强行继续。
- 第三个连接到来时不得凭旧 READY 关闭它；后续正常账号占用使用 ConnectPlus 的会话代次决定胜者。顺序以认证通过后进入串行协调器的先后为准。

扩展不能在事件循环内同步等待此 Future；否则 ConnectPlus 回调 DISCONNECT 时可能死锁。需要暂停/续接登录流程的能力，不能用阻塞等待模拟。

### 4.4 超时与停用

v1 默认 RESOLVE/VALIDATE 超时为 3 秒，DISCONNECT 和重复登录协调超时为 5 秒。迟到响应不得改变已失效连接、注册或候选的状态。超时后不允许新玩家访问受保护档案；旧会话已被冻结或关闭时也不自动恢复其写权限。

PROVIDER_STOPPING 或提供者失联后，ConnectPlus 停止接受新基岩身份，冻结已有受影响会话的账号操作，保存已接受的修改并断开这些会话。Java 会话不受桥接停用影响。重新启用必须生成新 providerEpoch 并重新注册，不能复用旧连接证明。

## 5. 推荐内部模块

模块命名可由扩展开发者调整，职责必须保留：

| 模块 | 职责 |
| --- | --- |
| ExtensionBootstrap | extension.yml、启动注册、停用注销、版本报告 |
| GeyserIdentityVerifier | 记录真实认证完成状态，排除不支持的转发/离线模式 |
| GeyserViaProxyAdapter | 隔离内部 API，保存下游连接信息，与原始 c2p 精确匹配 |
| BridgeSessionIndex | 按 bridgeSessionId 管理活跃会话，维护已验证 Channel 绑定，关闭即清理 |
| 可选重复登录协调 | 当前扩展不实现；未来仅允许官方公开 API 支持，不得修改宿主 |
| ConnectPlusEndpointClient | JDK 类型协议编解码、反射调用、超时及注册代次校验 |

不按 XUID 永久缓存 Channel，不将已关闭对象留作后续重连依据。一次 RESOLVE 成功后，绑定不能转移到另一个 Channel；重连必须创建新的 bridgeSessionId。

## 6. 联调场景和验收标准

使用真实基岩客户端、正版 Java 客户端和隔离测试账号完成端到端测试。自动化桩只能证明接口一致，不能替代认证与关联原型验证。

| 编号 | 场景 | 必须观察到的结果 |
| --- | --- | --- |
| G01 | 基岩 A 正常进入 | VERIFIED 的 XUID 与实际账号一致，连接绑定唯一 |
| G02 | 两个不同基岩账号在同一 IP/NAT 下连接 | 分别匹配正确 Channel，不串身份 |
| G03 | 伪造昵称、Java UUID、XUID 或插件消息 | 不产生 VERIFIED，不触发旧玩家下线 |
| G04 | Geyser 改写远端 IP、IPv4/IPv6、快速重连/端口复用 | 旧快照无法作用于新 Channel |
| G05 | A 已绑定 Java B，B 从 Java 客户端进入；再由 A 进入 | 每次新验证连接取得唯一使用权，旧客户端收到指定提示 |
| G06 | 同一 XUID 两台基岩客户端先后连接 | 旧客户端继续在线，新连接被官方 Geyser 原生拒绝，旧档案不变 |
| G07 | 未完成认证的重复登录尝试 | 旧玩家继续在线，档案无变化 |
| G08 | 旧 SESSION_CLOSED、DISCONNECT 延迟到达 | 不能踢掉新连接、注销新会话或重新授权旧会话 |
| G09 | 提供者停止、重启、版本不兼容 | 有明确停用原因；旧证明失效；正常 Java 登录仍可用 |
| G10 | 后端切服、返回大厅 | 同一个 c2p 的身份仍有效，p2s 关闭不冒充玩家退出 |
| G11 | 旧档案保存失败、协调超时、新候选中途断开 | 不授予两个连接同时访问档案的权限，不在未认证时顶号 |

### 外部开发者需要交付

- 扩展源码、可重复构建说明、可安装 JAR、依赖声明和 extension.yml。
- 确切支持的 ViaProxy/Geyser/JDK 版本及认证配置；只读内部 API 适配项逐项列出；禁止任何宿主改动。
- 精确关联原型的验证记录及原生重复 XUID 拒绝的客户端观察记录。
- v1 接口测试和 G01—G11 的联调记录；未通过项目明确列出，不能用“理论支持”替代。
- 诊断日志样例：版本、注册/停用原因、脱敏的连接标识、超时和关联失败原因；不记录令牌、registrationId 或完整 XUID。

扩展可以先使用模拟 ConnectPlus 接口开展开发；最终必须连接实现了本文方法的真实 ConnectPlus 验收。

## 7. 上游参考与兼容性依据

以下为设计时查阅的上游入口，分支链接会变化，实施时必须补记实际提交号：

- [Geyser Extensions 文档](https://geysermc.org/wiki/geyser/extensions/)：扩展形态和生命周期。
- [Geyser SessionLoginEvent](https://github.com/GeyserMC/Geyser/blob/master/api/src/main/java/org/geysermc/geyser/api/event/bedrock/SessionLoginEvent.java)：登录事件边界；不能假定它早于重复 XUID 拒绝。
- [Geyser UpstreamPacketHandler](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/network/bedrock/UpstreamPacketHandler.java)：上游登录处理与重复 XUID 检查。
- [Geyser LoginEncryptionUtils](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/util/LoginEncryptionUtils.java)：身份校验及不同转发模式，签名解析不应被当作完整会话授权。
- [Geyser-ViaProxy 启动器](https://github.com/GeyserMC/Geyser/blob/master/bootstrap/viaproxy/src/main/java/org/geysermc/geyser/platform/viaproxy/GeyserViaProxyPlugin.java)：ViaProxy 事件接入和连接地址处理。
- [ViaProxy PluginManager](https://github.com/ViaVersion/ViaProxy/blob/main/src/main/java/net/raphimc/viaproxy/plugins/PluginManager.java)：插件查找及加载方式。

这些来源说明需要适配的位置，不构成“公开 API 已覆盖全部需求”的承诺。同 XUID 重复登录按第 2.2 节保留官方原生策略。
