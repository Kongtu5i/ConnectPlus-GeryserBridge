# ConnectPlus-GeyserBridge

独立 Geyser 扩展，为同一个 ViaProxy 进程中的 ConnectPlus 提供可信 XUID、精确 TCP 连接绑定和定向断开。

**当前及后续功能均使用未修改的官方 ViaProxy、Geyser-ViaProxy。** 约束见 [AGENTS.md](AGENTS.md)，双方接口见 [协议](docs/geyser-bridge-v1.md)。旧补丁版验收包已废弃。当前版本 1.0.1，真人客户端验收尚未完成，建议作为预发布版使用。

## 构建

需要 Java 21 或更高版本。依赖由 Gradle 下载，无需准备本地 ViaProxy JAR。

```powershell
.\gradlew.bat jar test exportVerificationClasspath
```

Windows 测试 worker 遇到中文路径类加载问题时，单独执行 `.\gradlew.bat jar exportVerificationClasspath`，再运行 `python scripts/verify-local.py`（需要 Python 3.10+）。产物为 `build/libs/connectplus-geyserbridge-1.0.1.jar`。编译依赖的 SHA-256 锁用于核对构建输入；运行时不锁 Git 提交或宿主 JAR 校验值。依赖含 SNAPSHOT，全新环境若下载到变化后的字节会拒绝构建，不应跳过校验。

## 安装与配置

```text
ViaProxy/
  viaproxy.jar
  plugins/
    ConnectPlus-0.1.0.jar
    Geyser-ViaProxy.jar
    Geyser/
      config.yml
      extensions/
        connectplus-geyserbridge-1.0.1.jar
```

ConnectPlus：`mode: lobby`、`geyser-support.enabled: true`、`allowAccountLogin: true`。
Geyser：启用 `advanced.bedrock.validate-bedrock-login`，关闭 `use-waterdogpe-forwarding`，认证类型不得为 FLOODGATE。
连接端口以宿主配置为准。首次启动需等待 Minecraft 素材下载完成。

## 能力与兼容性

扩展通过检查后注册 `verified-xuid`、`exact-channel-binding`、`targeted-disconnect`。ConnectPlus 接受这三项能力，不要求重复登录准入能力。

同一 Xbox XUID 两台基岩客户端重复登录时，保留 Geyser 原生行为：旧客户端继续在线，新连接被拒绝。已绑定 Java 档案的 Java/基岩互斥登录、书签、关联和解绑仍由 ConnectPlus 管理。

当前允许尝试的运行范围：Geyser 2.11.x（最低 2.11.3）、ViaProxy 3.4.x（最低 3.4.13）、Java 21+，还必须通过适配器接口探测与认证配置检查。实际验收基线是官方 Geyser 2.11.3 build 1247 / ViaProxy 3.4.13 / Java 21；同系列未来版本的探测通过不等于已完成客户端兼容验收。新系列只审查和更新桥接适配器，不修改宿主。

精确连接匹配仍需读取 Geyser 内部下游连接，因此不能承诺任意未来版本自动兼容。内部访问隔离在 `adapter/GeyserViaProxyAdapter.java`，不匹配时停用桥接。

## 验证

桥接的 36 项 JUnit 测试覆盖版本判断、XUID、地址、会话索引和请求处理。此前官方宿主启动检查已验证扩展加载、三项能力注册、Java TCP 入口、基岩 RakNet UDP 响应、素材加载和正常关闭。

真实 ConnectPlus 全量测试及真人客户端验收需分别记录。自动检查不能代替真人验收；当前真人客户端验收尚未完成。

源码仓库保留正式扩展、单元测试、构建工具和协议文档。安装时只需真实 ConnectPlus、官方 Geyser-ViaProxy 和本扩展，无需模拟插件。

项目目前未声明源码许可证。
