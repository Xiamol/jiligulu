# 叽里咕噜 · 阿噜的小生活家 🪻

一只会陪你记账、收好回忆的糯云团。聊天记一笔，夹一张生活照片，再去阿噜的小窝坐坐。

**当前版本：1.0.0** · Android 8.0 及以上 · Kotlin / Jetpack Compose / Room

[下载 1.0.0](https://github.com/Xiamol/jiligulu/releases/tag/v1.0.0) · [本次更新](docs/RELEASE_1_0_0.md) · [公告信箱](docs/ANNOUNCEMENTS.md)

## 从小账本，长成小生活家

- **账本**：对话记账、图片识别和悬浮截图，先检查草稿，再确认入账；手动记账支持自动分类、小算盘和夹照片。
- **小窝**：整页插画场景里的愿望瓶、未来信箱、生活纪念册、今日小签与时光列车。点点房间里的物件，打开属于自己的小角落。
- **统计**：五天柱图与整月折线切换，实际支出和日均参考线分别开关；分类圆环、可滚动图例、小窗明细和预算进度。

星星形状的愿望瓶记录攒钱进度；未来信笺留给以后的自己；照片可以夹进账单、纪念册，也能制作明信片和海报。喜欢的小签与纸条可以收藏，还能写下自己的话。

四款皮肤关联页面装饰与配色。阿噜会说悄悄话，也有睡眠、夜景和一些藏起来的小彩蛋，等你慢慢发现 ♡

### 记账之外，坐下来玩一局

象棋、15 路五子棋、穿墙贪吃蛇和好运转盘。棋类支持人机、同屏轮流、附近棋友及互联网房间；选中、可落点、悔棋协商、回合计时、走子动画和结算反馈都更清楚。附近对局需要同一局域网，互联网房间需要网络和可用的房间服务。

## 看看阿噜的小窝

以下是 Android 模拟器的实际界面，使用演示数据；显示会随主题、屏幕与内容变化。

<img src="docs/images/v1-room.png" width="280" alt="阿噜的小窝，整页场景与物件入口" /> <img src="docs/images/v1-statistics.png" width="280" alt="收支统计与分类圆环" />

## 安装与使用

从 [GitHub Releases](https://github.com/Xiamol/jiligulu/releases/latest) 下载 APK。已有版本请**直接覆盖安装，不要先卸载**，保留本机账单、照片、对话与设置。1.0.0 使用 versionCode 13，沿用此前安装包签名。

基本账本、小窝和本地游戏可离线使用。AI 对话及识图需要在设置中填写自己的 DeepSeek API Key，并连接网络；公开安装包不内置开发者的私人 Key，费用以服务商账单为准。用量页显示本机收到的 token、缓存情况和预估费用，不承诺固定缓存命中率。

悬浮截图由你授权后使用，可调整大小并记住位置。全局折射默认关闭，开启时由 Android 请求录屏授权；微信快捷入口需要安装微信，双开设备可能先显示系统应用选择页。

数据保存在本机。调用 AI 时会发送你提交的文字/图片、近期对话和必要的账本上下文；识别不会自动入账。当前没有跨设备同步或独立整库备份功能，请妥善保留本机数据。

## 构建

需要 Android SDK 35、JDK 17 或 21。配置本机 `local.properties` 的 `sdk.dir`，使用随项目提供的 Gradle Wrapper。

```powershell
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest --offline -PpublicRelease=true
```

`-PpublicRelease=true` 强制留空构建时默认 API Key。个人本地构建可在未提交的 `local.properties` 配置 `DEEPSEEK_API_KEY`，也可安装后在设置中填写。

APK：`app/build/outputs/apk/release/app-release.apk`；测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。

Room schema 为 v6，保留 v1–v6 迁移链，升级不清库。发布时增加 versionCode，保持 applicationId 和签名一致。[数据升级](docs/DATABASE_UPGRADES.md) · [更新源](docs/UPDATES.md)

## 源码与第三方资源

源码已公开在本仓库。界面、字体、美术及第三方组件的许可分别适用，不将代码公开等同于所有素材均可任意商用。

Pikafish 组件及对应源码、作者和许可证随包保留，见 [打包说明](third_party/pikafish/PACKAGING.txt)。配套权重有独立的[非商业用途许可](third_party/pikafish/NNUE-License.md)。字体许可在 `app/src/main/assets/licenses`。

阿噜会继续陪你慢慢记，把普通的一天也收好。
