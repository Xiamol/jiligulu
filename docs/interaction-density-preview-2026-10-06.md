# 0.6.2 本地预览：紧凑交互、附近棋桌与游戏存档

本轮在 `5c1eccd` 之后继续调整。版本保持 **0.6.2 / versionCode 12**，本次只交付本地预览，不发布 GitHub。

## 已完成的行为

### 记账和统计

- 「记一笔」改为细则、金额、收支、分类和时间的紧凑输入，主要保存操作固定在下方；新的生活剪贴背景不挤占输入和照片的位置。
- 手动记账可以夹照片。选图复制到本软件私有目录，导入完成后才允许保存；换图、取下和放弃编辑清理本次未提交副本。已保存账单、回收站账单、愿望和纪念卡引用的文件会保留。
- 手动分类可按用途自动建议，优先匹配已有分类，允许建议新分类。请求只发送当前细则、备注、收支和分类表，金额、时间、其它账单及历史对话不参与这次分类请求；请求有取消和缓存处理。
- 分类图标入口改为紧凑图案选择，收支只需直接切换。已有账单和分类通过数据库升级保留。
- 每日收支卡片保留柱图 / 折线切换。折线整月同屏，展示实际支出和本月日均，右侧独立开关；日均按月首日至所选日期的累计支出与天数计算，包含零支出日期，今天按截至当前的记录计算，未来日期为空。收入展示实际数据。**本轮已用日均替换上一预览的消费预测，不再推算未来消费。**
- 统计详情、预算调整、日期选择和账单详情减少重复标题、大框和留白，主要确认按钮固定；金额仍以整数分保存，图表与编辑入口沿用收支区分。

### 小世界与弹窗

- 未来信箱、生活纪念册、时光列车和秘密空间保留整页场景，内容窗口缩小到约 260–300 dp，滚动内容与固定操作区分开；嵌套内容继续有弹簧和右侧滑条。
- 未来邮局在没有到达信件时使用空邮槽场景。单封信直接阅读，多封信使用紧凑列表；标记已读后同步更新入口。
- 生活照片随账单进入纪念册；周明信片、海报预览、分享和保存继续可用。保存海报前先保护图片，再提交收藏引用，避免关闭或重建页面时清掉已经保存的海报。
- 愿望与贴纸编辑改为平面输入行，金额触区至少 50 dp；贴纸收支直接切换。存钱、保存贴纸等主要操作放在固定下方。
- 设置页去掉大框套大框，四个页签固定在标题下方。名字、后缀和 API Key 各自弹出小输入窗、各自保存；密钥保持隐藏显示，编辑值不写入页面保存状态，关闭后清空临时输入。
- 小算盘居中、保持方便点按的数字键；抽签收藏、时光列车行李、信笺阅读和欢迎页的尺寸与间距同步调整。
- 秘密空间的纸条可阅读、收藏和写入；夜间状态使用独立场景，睡觉与唤醒会更新灯光和阿噜状态并记住选择。下拉与长按入口保留不同动画。

### 游戏、联机与存档

- 五子棋保持固定 15 路和整页棋盘。人机搜索强化；贪吃蛇支持从边缘穿到另一侧。
- 象棋保留整页棋桌、上方单个秒数时钟、合法落点 / 吃子提示、最近落子和对方选中提示；本地计时是思考提醒，不以超时判负。
- 本地人机悔棋退回玩家的一整轮；同屏和联机双人悔棋需要对方同意。联机协商期间暂停落子，拒绝和超时保持原局。
- 五子棋和象棋都提供「附近的人」与互联网创建 / 加入房间。附近入口自动广播并发现同 Wi-Fi 的棋友，按名字选择，无需填写 IP；只在附近页前台搜索，连接、离页和后台时收起广播。
- 互联网使用各自隔离的房间命名空间。收到有效初始棋局后才进入对战；初始同步独立限时 8 秒，心跳和重复连接事件不会无限延长等待。显式重试创建新连接，不运行无止境自动重连。
- 联机由房主验证合法落子和单调 revision。悔棋绑定请求方、请求编号、原 revision、原局面及本机已验证历史；专门的回退消息必须符合双方同意与已知目标。普通棋局消息仍不能任意改写或回滚棋盘。
- 隐藏七次点按辅助继续只走正常的本地选子 / 落子流程，不产生额外提示，也不调用 AI API。
- 五子棋人机 / 同屏、象棋人机 / 同屏和贪吃蛇有五个独立本地槽。保存局面、开始状态、合法悔棋历史、模式和象棋剩余毫秒；切模式和退出会保存，重新进入暂停恢复。五子棋历史最多 64 手，象棋最多 128 手。坏槽只重置对应槽；网络棋局不伪装成可恢复的本地对局。
- 存档同步捕获不可变快照，后台合并写入私有 SharedPreferences；离开软件时使用有界落盘等待，不在每帧主线程写文件。

### 音效与玻璃

- 普通按钮、选子和落子使用短音效，设置有「按键与棋子音效」开关和试听，默认开启、自动记住；尊重静音状态和媒体音量，不争抢音频焦点或后台循环播放。
- 模拟器以 `-no-audio` 启动，音效触发路径、开关和保存状态已核对，**没有在此环境验听实际声音或实体手机听感**。
- 悬浮材质继续复用画笔、轮廓和采样缓存，减少无效更新。API 33+ 在本软件 Activity / Dialog 内用 PixelCopy 与 RuntimeShader 采样背景，做边缘折射、缩放、轻色散和反射；失去前台、关闭或换窗口时清理采样任务。
- **跨软件只能使用 Android 系统窗口模糊及边缘光泽回退。** 系统或设备关闭跨窗口模糊时仍可显示边缘和材质，但不能读出其它软件的实时背景像素。没有为玻璃额外启动持续录屏，也不声称等同于 iOS 全系统液态玻璃。

## 验证证据和当前状态

原生验证使用同一电脑的两个 AOSP API 34 实例 `emulator-5580` / `emulator-5582`，1080 × 2400、density 420；所有账单、照片、纸条和棋友名字为 QA 数据。原始截图在 `D:/叽里咕噜/output/android-qa/screenshots`。

| 范围 | 原生证据 | 覆盖边界 |
| --- | --- | --- |
| 照片记账 | `oct6-manual-photo-filled.png`、`oct6-photo-bill-saved.png`、`oct6-photo-memories-linked.png` | 选图、保存账单、纪念册关联；不是仅靠表单截图推断已保存 |
| 紧凑页面 | `oct6-final-manual.png`、`oct6-final-settings.png`、`oct6-centered-calculator.png`、`oct6-future-editor.png` | 本轮原生尺寸、排版及入口；不同实体手机仍需各自核对系统缩放 |
| 小世界 | `oct6-future-empty.png`、`oct6-future-single.png`、`oct6-paper-saved-native.png`、`oct6-night-after-restart.png`、`oct6-train-luggage.png` | 空信槽、读信、纸条、夜间记忆及内容入口 |
| 本地游戏 | `oct6-game-snake-after-wrap.png`、`oct6-game-gomoku-undo.png`、`oct6-game-xiangqi-undone.png` | 穿墙、回手、合法选中和悔棋；性能截图不能证明所有实体设备的帧率 |
| 附近棋桌 | `oct6-nearby-found.png`、`oct6-nearby-two-moves.png`、`oct6-network-gomoku-accept-requester.png`、`oct6-network-xiangqi-refuse-requester.png` | 同虚拟 Wi-Fi 发现、双向落子、同意 / 拒绝悔棋及对方选中 |
| 互联网棋桌 | `oct6-network-go-online-black-received.png`、`oct6-network-go-online-white-received.png`、`oct6-network-xiangqi-online-retry-joined.png` | 公共 PeerServer / WebRTC 建房、加入和双向数据；冷连接曾失败，显式重试后成功 |
| 游戏存档 | `oct6-archive-go-process-restored.png`、`oct6-archive-xq-process-restored.png`、`oct6-archive-xq-process-undo.png`、`oct6-archive-snake-process-restored.png` | 切本地模式、离页、重启及暂停恢复；网络重进仍需重新创建 / 加入 |

上述目录还包含过程截图，最终画廊由打包步骤筛选。图片生成的设计参考、Robolectric 原生图形测试、`UiSmokeScreenshotTest` 页面 harness 与 ADB 截图是不同证据；不会把旧 harness 或设计图标为真实模拟器页面。

最终统一执行 `:app:assembleRelease :app:testDebugUnitTest`，**BUILD SUCCESSFUL，耗时 56 秒**。实际 JUnit XML 汇总为 **85 suites / 513 tests / 0 failures / 0 errors / 0 skipped**，包含修复后的 13 项页面 harness、12 项本地存档测试、取消导入和媒体归属测试。

页面 harness 的 Android JSONObject、JUnit void 返回类型、Compose 调度器和 fixture 关闭问题已在测试环境修正，没有改业务来迁就 stub，也未删除数据写入、收藏或存款断言。调度器与清理依据见 `docs/COMPOSE_TEST_DISPATCHER_2026_10_06.md`。上述自动测试结果与 ADB 原生验收仍分别记录。

Release 构建及签名核验已完成。原生存档另外核对了五子棋 / 象棋人机与同屏独立槽、重开与悔棋、象棋 57 秒恢复，以及贪吃蛇身体、食物、方向和暂停状态；最终画廊由这些原生图筛选。

## 网络测试范围

两个原生客户端都运行在同一开发电脑，附近发现通过模拟器共享虚拟 Wi-Fi，互联网经公共信令与 WebRTC；这覆盖真实应用的传输与状态路径，不代表已经覆盖两台实体手机、所有校园网 / NAT / 路由器隔离策略。

模拟器 36.5+ 的共享 Wi-Fi 栈支持 NSD 自动发现；使用旧隔离网络或 `-feature -WiFiPacketStream` 时，互相发现的结果不同。产品的附近模式使用各自固定端口和私有 IPv4，端口冲突或伙伴退出会回到可重试状态。互联网首次连接受 DNS、WebView、信令与 STUN / TURN 条件影响，冷连接测试记录保留；不把一次成功写成公共服务永久可用保证。

短暂后台保留房间以便分享房间码，本地 AI / 游戏计时停止，附近广播立即停止；超过后台宽限或主动退出关闭连接。已经发出的落子、协商和重复消息都有 revision / 请求绑定处理。

## 参考、资源与许可

- [Android NSD](https://developer.android.com/develop/connectivity/wifi/use-nsd)、[NsdServiceInfo](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo)：自动登记、发现、解析服务和 API 34 的地址列表。
- [模拟器互联](https://developer.android.com/studio/run/emulator-networking-interconnect)：共享 Wi-Fi 与旧虚拟网络的区别。
- [liquid-glass-react](https://github.com/rdev/liquid-glass-react)、[RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader)、[PixelCopy](https://developer.android.com/reference/android/view/PixelCopy)：参考玻璃方向，应用内效果为自己的 Android 实现。
- [PeerJS 1.5.5](https://github.com/peers/peerjs)：仅远程房间使用，MIT 许可保留在 `app/src/main/assets/chess-online/PEERJS-LICENSE.txt`；未请求摄像头 / 麦克风。
- 生图记录：`docs/dense-ui-design-2026-10-06.json`、`docs/manual-scrapbook-art-2026-10-06.json`、`docs/world-art-design-2026-10-06.json`。生成素材与用户照片分开，音效为本轮生成的短提示音。

## 交付核定栏

- 安装包：`D:/叽里咕噜/releases/jiligulu-0.6.2-interaction-density-preview.apk`，已复制。
- APK SHA256：`acec3a1fd661aa728f0a903ab416a317ac64cd0a8f99713bf8add3a58b9d7a9f`，最终文件已重新读取核对。
- 签名 SHA256：`ddd9a47bcf8646ed468a1f0741be61a6e7f3bbae82133d3666d28264a2b534f8`，核验通过，与前一预览一致，可覆盖安装。
- Release 构建与全部 513 项测试：通过，85 suites，无失败、错误或跳过。
- 发布状态：本地预览，本次未发布 GitHub。
