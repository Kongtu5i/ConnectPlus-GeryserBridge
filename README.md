# ConnectPlus-GeyserBridge

ConnectPlus 的基岩版身份桥接扩展，适用于同时运行 ConnectPlus 和 Geyser-ViaProxy 的 ViaProxy 服务器。

它把 Geyser 验证过的 Xbox 玩家身份交给 ConnectPlus，并将身份对应到正确的玩家连接。玩家档案、书签、Java 账号关联和解绑由 ConnectPlus 管理。

## 运行要求

| 组件 | 要求 |
| --- | --- |
| Java | 21 或更高版本 |
| ViaProxy | 官方 3.4.x，最低 3.4.13 |
| Geyser-ViaProxy | 官方 2.11.x，最低 2.11.3 |
| ConnectPlus | 0.1.0（包含基岩桥接功能） |

ConnectPlus 与 Geyser-ViaProxy 必须安装在同一个 ViaProxy 实例中。本扩展使用未修改的官方宿主，仅适用于 Geyser-ViaProxy 部署方式。

## 安装

1. 安装 ViaProxy，将 ConnectPlus 和 Geyser-ViaProxy 放入 `plugins/`。
2. 启动一次 ViaProxy，生成插件配置文件，然后关闭服务器。
3. 将 `connectplus-geyserbridge-1.0.1.jar` 放入 `plugins/Geyser/extensions/`；目录不存在时手动创建。
4. 按下方说明修改配置，再启动 ViaProxy。

目录结构如下，路径相对于 ViaProxy 的运行目录：

```text
ViaProxy/
├── viaproxy.jar
└── plugins/
    ├── ConnectPlus-0.1.0.jar
    ├── Geyser-ViaProxy.jar
    ├── ConnectPlus/
    │   └── config.yml
    └── Geyser/
        ├── config.yml
        └── extensions/
            └── connectplus-geyserbridge-1.0.1.jar
```

桥接 JAR 应放在 **Geyser 的 `extensions/` 目录**。

## 配置

修改现有配置中的对应字段，保留其他设置。

### ConnectPlus

文件：`plugins/ConnectPlus/config.yml`

```yaml
mode: lobby
allowAccountLogin: true

geyser-support:
  enabled: true
```

`mode: lobby` 启用 ConnectPlus 大厅，`geyser-support.enabled` 开启基岩身份桥接，`allowAccountLogin` 允许玩家使用微软 Java 账号登录功能。

### Geyser-ViaProxy

文件：`plugins/Geyser/config.yml`

```yaml
java:
  auth-type: offline

advanced:
  bedrock:
    validate-bedrock-login: true
    use-waterdogpe-forwarding: false
```

上述配置让 Java 账号登录由 ConnectPlus 处理。`auth-type: offline` 指 Geyser 的 Java 下游认证方式，基岩玩家仍须通过 Xbox 身份验证；请保持 `validate-bedrock-login: true`。

本扩展不支持 Floodgate 认证模式或 WaterdogPE 身份转发。

## 连接与使用

启动后，在控制台中查找：

```text
registered with ConnectPlus (bridge capabilities:
```

出现这段日志表示桥接已成功接入 ConnectPlus。首次启动时，请等待 Geyser 完成 Minecraft 素材下载和加载。

基岩玩家在 Minecraft 中登录 Xbox 账号，添加服务器，填写 ViaProxy 所在机器的地址及 **Geyser 配置中的基岩端口**。基岩连接使用 UDP，跨机器访问时需要放行该端口。

进入后，通过 ConnectPlus 大厅使用书签、连接服务器或关联 Java 账号。Java 玩家连接 ViaProxy 的 Java TCP 端口。

## 重复登录行为

- **同一个 Xbox 账号在两台基岩客户端登录**：原客户端保持在线，新连接由 Geyser 拒绝。
- **基岩账号已关联 Java 档案**：Java 与基岩客户端对同一档案的登录互斥由 ConnectPlus 管理。

## 常见问题

| 现象 | 检查方法 |
| --- | --- |
| 桥接没有加载 | 确认 JAR 位于 `plugins/Geyser/extensions/`，并已重启 ViaProxy。 |
| 日志出现 `ConnectPlus not found` | 确认真实 ConnectPlus 插件已安装，并在控制台中正常加载。 |
| 日志出现 `bridge disabled` | 查看同一条日志中的原因，检查版本要求、Xbox 登录验证及认证配置。 |
| ConnectPlus 拒绝桥接注册 | 确认 `geyser-support.enabled: true`，并使用带有基岩桥接功能的 ConnectPlus。 |
| 基岩客户端无法连接 | 检查 Geyser 的监听地址、基岩端口及 UDP 防火墙或端口映射设置。 |
| 大厅中的账号登录功能不可用 | 检查 ConnectPlus 的 `allowAccountLogin`，以及桥接是否成功注册。 |

## 问题反馈

请通过 [GitHub Issues](https://github.com/Kongtu5i/ConnectPlus-GeryserBridge/issues) 提交问题，并附上 Java、ViaProxy、Geyser-ViaProxy 和 ConnectPlus 的版本，以及相关错误日志。分享日志前请移除账号令牌、密码等敏感信息。
