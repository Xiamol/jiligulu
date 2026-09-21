# 配置远程更新

当前实现是 GitHub Releases 的检查、提示与用户确认下载。默认更新源为空，不会访问任何账号或仓库，也没有创建或发布远程项目。

GitHub 并非技术上必需：自己的 HTTPS 服务也能提供版本信息与 APK。此版本先提供 GitHub Releases 适配器，方便个人使用，无需把登录凭据放进应用。

## 接通方式

1. 之后创建一个专门的公开发布仓库，例如 `你的账号/jiligulu-releases`。发布仓库可以只放发行文件，不必公开源码。
2. 在 Releases 发布一个正式版本，标签使用 `v0.5.2` 这样的三段数字格式，附上 APK 和更新说明。草稿、预发布、没有 APK 的版本不会被当作可安装更新。
3. 应用“设置 → 应用更新 → 设置更新源”填写 `账号/仓库` 或其 HTTPS GitHub 链接。
4. 点击“检查更新”验证。启用“启动时自动检查”后，最多每 6 小时自动检查一次，失败也会退避；手动检查仍可用。
5. 新版本弹窗或设置卡片的下载按钮打开浏览器。下载后由 Android 系统确认安装，没有静默安装或覆盖用户数据的操作。

## 发布约定

- 保持 `applicationId` 与签名一致，增加 `versionCode`，并将 `versionName` 更新为与 Release 标签相同的版本。当前为 versionCode 4 / versionName 0.5.1。
- 保留签名私钥，不能随便换签名；不要把私钥、密码或令牌提交到公开仓库。
- 如果改变数据库结构，追加并测试 Migration，不要用清库迁移兜底。
- 正式公开分发前，应移除 APK 内置的服务 API Key，改为用户自填或由自己的后端处理。仅仅不公开源码，并不能阻止从 APK 中提取内置 Key。
- 当前仓库与发布端尚未配置，因此还没有进行真实远端版本检查；测试使用受控版本元数据覆盖比较、过滤与错误场景。

实现参考：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)。Google Play 的应用内更新是另一套适用于 Play 分发的方案，见 [Android 官方说明](https://developer.android.com/guide/playcore/in-app-updates)。
