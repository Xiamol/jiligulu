# 配置远程更新

当前实现是 GitHub Releases 的检查、提示与用户确认下载。应用只读取公开的发行元数据，不涉及账号密码、令牌，也不做静默安装。

GitHub 并非技术上必需：自己的 HTTPS 服务也能提供版本信息与 APK。此版本先提供 GitHub Releases 适配器，方便个人使用，无需把登录凭据放进应用。

**当前状态：更新源已可用。** 发布仓库为 `Xiamol/jiligulu`（公开），已在 Releases 发布 `v0.5.1`。

## 接通方式

1. 在公开仓库的 Releases 发布一个正式版本，标签使用 `v0.5.2` 这样的三段数字格式，附上 APK 和更新说明。草稿、预发布、没有 APK 的版本不会被当作可安装更新。
2. 应用“设置 → 应用更新 → 设置更新源”填写 `账号/仓库` 或其 HTTPS GitHub 链接（当前填 `Xiamol/jiligulu`）。
3. 点击“检查更新”验证。启用“启动时自动检查”后，最多每 6 小时自动检查一次，失败也会退避；手动检查仍可用。
4. 新版本弹窗或设置卡片的下载按钮打开浏览器。下载后由 Android 系统确认安装，没有静默安装或覆盖用户数据的操作。

> 仅当远端标签版本 **高于** 应用当前 `versionName` 时才会提示更新。用与当前同版本的 Release 测试时，界面会正确显示“已是最新”。

## 发布约定

- 保持 `applicationId` 与签名一致，增加 `versionCode`，并将 `versionName` 更新为与 Release 标签相同的版本。当前为 versionCode 4 / versionName 0.5.1。
- 保留签名私钥，不能随便换签名；不要把私钥、密码或令牌提交到公开仓库。
- 如果改变数据库结构，追加并测试 Migration，不要用清库迁移兜底。
- **内置 API Key 已移出源码**：`AiConfig.DEFAULT_API_KEY` 改由 `app/build.gradle.kts` 在构建期读取 `local.properties` 的 `DEEPSEEK_API_KEY` 注入 `BuildConfig`，该文件已被 `.gitignore` 排除。源码仓库不含 Key。
  - 需注意：Key 会被编译进 APK，反编译仍有可能提取。分发面向公众的正式包前，建议改为用户自填或由自己的后端代管。
- 发布用的 `local.properties` 只在本机存在；他人 clone 源码后未配置该字段也能构建，只是 AI 功能需在设置页手动填 Key。

## 已完成的验证

2026-09-21：以匿名方式（不带任何凭据）请求 `releases/latest`，返回 200；解析结果 `tag_name=v0.5.1`、`draft=false`、`prerelease=false`、附件 `jiligulu-0.5.1-debug.apk`（34,696,098 字节），`browser_download_url` 满足应用侧的 host 与路径校验；该下载链接匿名请求返回 200，MIME 为 `application/vnd.android.package-archive`。

实现参考：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)。Google Play 的应用内更新是另一套适用于 Play 分发的方案，见 [Android 官方说明](https://developer.android.com/guide/playcore/in-app-updates)。
