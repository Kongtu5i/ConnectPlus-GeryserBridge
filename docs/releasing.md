# GitHub 发布说明

源码与安装包分开发布：仓库保存源码、Gradle Wrapper、依赖校验值和文档；插件 JAR 作为 GitHub Release 附件。

## 当前发布状态

- 版本：1.0.1；建议首发标记为 GitHub Pre-release，真人客户端验收仍待完成。
- 2026-10-07：重新编译桥接与 mock，36 项 JUnit 测试通过，0 失败、0 跳过；验收打包目录隔离回归测试 1 项通过。
- 本地 Windows 中文路径下 Gradle test worker 出现 ClassNotFoundException；使用 README 中的导出依赖与备用验证脚本完成测试。
- 既有官方宿主启动检查记录通过；本次发布准备未重新运行宿主启动检查或 ConnectPlus 全量测试。
- 真人验收尚未执行，不能把单元测试、TCP/UDP 检查列为 G01—G11 客户端验收通过。
- 项目目前未声明源码许可证；正式选择许可证后，补充仓库 LICENSE 及 README 说明。

## 上传范围

上传 `src/`、`mock-connectplus/src/`、构建文件、`gradle/`、`gradlew`、`gradlew.bat`、`scripts/`、`docs/`、README、AGENTS.md 和 Git 配置文件。

`.gitignore` 排除构建输出、依赖缓存、本地 ViaProxy 编译依赖、旧宿主补丁目录、运行数据、日志和凭据文件。保留 Gradle Wrapper JAR，这是构建工具的一部分。

发布附件仅使用本次验证的 `connectplus-geyserbridge-1.0.1.jar` 和对应 `SHA256SUMS`。不上传旧 1.0.0 JAR、mock 插件、旧补丁宿主包或本地运行目录。官方宿主和 ConnectPlus 由用户另行安装。

## 发布前验证

先按照 README 下载官方 ViaProxy 编译依赖，再执行：

```powershell
.\gradlew.bat jar test exportVerificationClasspath
python -m unittest discover -s scripts -p 'test_*.py' -v
```

若仅因 Windows 中文路径导致 Gradle worker 无法加载类：

```powershell
.\gradlew.bat jar exportVerificationClasspath
python scripts/verify-local.py
```

依赖 SHA-256 必须通过，扩展 JAR 必须包含 `extension.yml`，不得包含 Geyser/ViaProxy 宿主类。两个项目的桥接协议保持同步。协议变化和宿主适配变化需重新执行对应联调；真人结果单独记录。

当前编译依赖包含锁定校验值的 SNAPSHOT。全新环境若解析到上游变化后的字节，构建会拒绝继续。不要为发布而跳过校验或直接刷新锁；必须先审查依赖变化。本地缓存构建通过不等于已验证全新环境构建。

## Release 文案

可使用 [1.0.1 发布说明](releases/1.0.1.md) 作为 GitHub Release 正文。添加标签和上传前，确认仓库归属、可见性、许可证选择以及发布的提交。
