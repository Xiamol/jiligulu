# 叽里咕噜

Android 本地记账应用，Kotlin + Jetpack Compose + Room。当前版本 `0.5.6`（versionCode 9），奶油手账与糯云团桌宠。

[已发布安装包](https://github.com/Xiamol/jiligulu/releases/latest) · [架构审计](docs/AUDIT_2026_09_23.md) · [在线聊天与更新修复](docs/CHAT_PROTOCOL_2026_09_23.md) · [远程更新配置](docs/UPDATES.md)

后续接手与功能讨论先看 [当前路线图与用户决策](docs/ROADMAP.md)；旧交接中的优先级可能已被这里的最新反馈替代。

本地待验收功能：远程公告小信箱（已发布测试来信）。详见 [公告运营与语音说明](docs/ANNOUNCEMENTS.md)，尚未包含在已发布的 v0.5.6 APK 中。

## 构建

使用 Android SDK 35、JDK 17 或 21。`local.properties` 中配置本机 `sdk.dir`，命令行的 `JAVA_HOME` 指向 JDK。Gradle Wrapper 已随项目提供并固定为 8.11.1，无需单独安装 Gradle。

如需内置 AI 服务的默认 API Key，在 `local.properties` 中增加 `DEEPSEEK_API_KEY=你的Key`（该文件不入版本库）。未配置也能构建，只是 AI 功能需在应用设置页手动填写 Key。

在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。
测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。
Lint 报告：`app/build/reports/lint-results-debug.html`。

## 代码入口

- `JiliguluApp.kt` / `AppContainer`：应用级依赖和数据库、仓库的创建。
- `ui/main/MainScreen.kt`：主导航、独立的顶部桌宠区域、各页可保存状态。
- `ui/persona/GuluCompanionHeader.kt`：常驻桌宠及台词展示；包含浅色、深色 Compose Preview。
- `ui/persona/PersonaViewModel.kt`：始终有值的文案状态、可见时的刷新计时、点击互动。
- `ui/startup`：入场动画与数据准备协调，旧 Activity 的暂停不能取消新 Activity 的加载。
- `data/update`：公开 GitHub Releases 版本检查，未配置源时不联网。
- `data/reminder`：AlarmManager 单一提醒源，权限不足时使用非精确闹钟；保存到期时间，开机、覆盖安装、冷启动时恢复。
- `domain/persona/PersonaEngine.kt`：台词选择、展示间隔与提醒优先级；纯逻辑测试在 `app/src/test`。
- `ui/components/LedgerCard.kt` / `ui/theme`：共享卡片、颜色与排版。
- `ui/settings/SettingsViewModel.kt`：设置草稿、加载、保存和提醒调度；界面仅发用户事件。
- `data/repository` / `data/local`：持久化操作、Room DAO、实体与 schema。

## 界面验收

1. 进入账本：标题、桌宠和台词同时显示；滚动账单时顶部保持位置。
2. 普通状态停留约 10 秒：只更换台词；待喝水时保留拿杯姿态，直到点击并完成喝水动作。
3. 连续点桌宠：每次立即换句；有其他候选台词时不连续重复。
4. 账本与统计切换后返回：保持各自滚动位置；打开设置再返回也保持状态。
5. 设置已有免打扰时间后重新打开：显示已保存值；分别检查浅色与深色主题。
6. 手动记账打开键盘：表单可滚动，保存操作始终可达；保存中禁止重复提交。
7. 检查小屏、大字体、长金额及长分类名。统计页在窄屏或大字体下将图表与图例上下排列。

## 当前数据行为

- 账本和统计使用同一个账单详情面板，可改金额、名称和日期时间。手动新增也支持补记。
- 对话未明确时间时默认确认入账的此刻；明确“昨天中午”等时间则按发送时的设备时区解析并显示在草稿中。模糊或无效时间需确认，不静默改成今天。
- 聊天消息、草稿与确认卡保存在 Room。新草稿首次展开，主动收起或离开聊天再返回后折叠；确认成功才发一条结果回复。旧版 DISMISSED 草稿也可继续入账。删除的草稿保留墓碑，不能再次入账。
- AI 改喝水设置、彻底清空回收站需点击确认卡。检查更新卡跳转设置并发起真实检查；模型文字不会直接改变设置。
- 多轮 AI 请求将 assistant 原文封装为确定的 JSON reply，与强制 JSON 输出格式保持一致；不改写数据库中的聊天历史。本轮末尾附加输出协议，避免重新混入纯文本示例。请保留此约定及对应真实接口复现工具。
- 自动更新在首次进入和从后台返回时实际检查，成功后统一记录完成时间；失败保留上次成功时间，不再按 6 小时跳过。
- 设置支持《阿噜使用手册》与清空历史对话。清空保留账单、分类、预算、回收站账单和未入账草稿；正在回复时拒绝清空。
- 批量确认和草稿状态同事务，失败回滚，重复确认不重复入账。
- Room schema 为 v6，v1 至 v6 的迁移链保留账单、分类、预算与对话，详见 [升级说明](docs/DATABASE_UPGRADES.md)。
- 正文和品牌字体内置，许可证包含在 assets/licenses 中；角色资源位于 res/drawable-nodpi。

账单、对话与设置保存在本机；调用 AI 时，会发送消息、近期对话及部分账本上下文。独立备份、跨设备同步与 SVG 真正渲染仍不在本轮实现范围。

当前为本地开发版本；打包结果与真机交互、视觉验收分别记录，不用构建成功代替设备验证。
