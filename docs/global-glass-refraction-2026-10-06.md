# 可选全局边缘折射

日期：2026-10-06。状态：a4 统一构建、566 项测试及下述 API 34 原生授权/采样回归已完成。最终 df 样式包未改变全局采样逻辑，另做 Release 构建和样式原生复看；不能把 a4 截图改标为 df 新包。

## 用户可见行为

- 新增「全局边缘折射 · 可选」，默认关闭；关闭时保留已有系统局部窗口模糊，不持续采样屏幕。
- 用户打开设置项后先看到说明，再进入 Android 系统共享授权。Android 14 使用默认整屏配置；如果当前已有用户批准且仍有效的截图共享会话，只有用户明确点击启用后才在该会话中开始折射。
- 记住的是开启意愿，不保存系统授权 token。进程被清理或共享被系统结束后，需要再次授权；普通启动、恢复浮球、或后来只准备截图，不会因为偏好为 true 而静默启动折射采样。
- 设置显示运行、暂停或待授权状态。共享期间有系统共享标记及 LOW 静默前台通知，可从通知关闭，也可在设置中「停止本次共享」。单独关闭折射开关立即停止其采样，已有按需截图共享会话可保留。
- 应用内继续使用本应用窗口的 PixelCopy 与原有完整折射；回到本应用时暂停整屏采样。跨应用采用实时外环边缘折射，**中心仍由系统真模糊提供，不能宣称与应用内完整折射相同**。
- Android 13 以下不启用无效的 RuntimeShader 采样；基础浮球与系统模糊路径不受影响。

## 为什么只采外环

整屏 MediaProjection 会包含应用自己的悬浮窗口。公开 API 没有提供“从整屏捕获中去掉本应用浮球，同时暴露其正下方实时像素”的逐层排除能力。把浮球设为安全窗口会造成捕获遮黑，不是取得下方背景的办法；每帧隐藏浮球又会闪烁。

因此本实现只把**浮球完整矩形之外**的实时像素用于玻璃边缘：

1. 系统共享流输出低分辨率整屏缓冲，长边最多 1280px，保持比例。应用工作线程仅复制浮球附近的小 ROI。
2. 浮球完整矩形，包括透明角、精灵与其影子，再向外加 3 个采样像素作为禁止读取区。`copyRing` 不读取该矩形内的屏幕像素，输出对应位置固定为透明。
3. AGSL 从中心向外求与禁止区矩形的交点，向外再移后采样；采样点还有额外范围检查。色散的相邻采样同样经过检查。中心不从捕获流填充，也不臆造被遮住的像素。
4. 靠屏幕边缘时，超出有效 ROI 的部分透明回退到系统模糊，不重复边缘纹理、不取自己的遮挡区域。
5. 按下、拖动、位置改变时清掉缓存并暂停；稳定至少 300ms 后重新取新帧。旧 reader 的迟到回调受 generation 隔离，旧位置像素不会回灌到当前浮球。

这是实时环境像素驱动的边缘效果，不是静态截图贴图，也不是重建中心背景。没有通过 root、私有系统权限或绕过 `FLAG_SECURE` 取得受保护内容。黑色/无可见内容的采样帧会清旧背景并回退；其他应用的安全区域由系统捕获策略决定。

## 单共享会话与资源边界

`ScreenCaptureService` 保留原来“一次授权、一个 VirtualDisplay”的结构，只调用一次 `getMediaProjection` 与一次 `createVirtualDisplay`。Android 14 禁止重复消费同一授权创建显示；模式切换通过现有显示的 resize 和 surface 完成。

- 折射请求间隔至少 84ms，最高约 12fps。收到所需帧后立即将 display surface 置空，下一次请求再挂回同一个 reader，减少请求间的捕获工作。
- 有一帧正在处理时，多余 Image 立即关闭。ROI 整数像素缓冲和双 Bitmap 复用，避免每帧整屏 Bitmap 解码或分配。
- 采样帧只在内存流转，不调用图片保存、OCR 或网络接口。工作线程直接从 RGBA plane 按行距/像素距读取允许的区域；内存输出只含外环。
- 用户主动点截图时，原浮球路径先隐藏窗口；折射暂停，原 display 改为全分辨率单张 reader。等待隐藏后才获取原图并走原有图片导入，完成后恢复浮球和允许的采样。该明确截图动作的原图保存与自动折射帧严格分开。
- 屏灭、锁定、共享内容不可见、停止授权或服务结束时，断开 surface，清除内存背景并拒绝迟到回调。解锁时只有本次共享仍有效才可恢复；系统已终止会话则需要新授权。
- 不把通知改成隐身通知，也不隐藏系统共享状态。API 已存在的截图前台服务声明继续复用。

## 验证记录

- `GlobalGlassSamplingTest` 六项独立 JVM 测试通过：缩放与频率边界、完整矩形排除、四个屏幕角的裁切、实时外部颜色更新、保护黑帧不保留旧像素、无效目标拒绝。
- `FloatingCapturePreferenceTest` 的默认关闭、跨 owner 记住意愿及偏好不等于 live service ready 测试，已纳入统一通过的测试集。
- 独立 Kotlin PSI 语法解析通过；不等于 Android 类型检查或授权后运行验证。
- 桌面 JVM 基准：1080×2400 屏幕、126px 浮球，对应 576×1280 共享缓冲，复制 ROI 为 129×128，整数像素缓冲约 66KB；热身后 2000 次平均复制约 0.024ms。此结果只反映桌面 CPU 的区域拷贝，**不是手机帧率、GPU 功耗或端到端性能结论**。
- 运行日志 `GlobalGlass` 只报告计数、ROI/共享尺寸和平均处理时间，不记录图片或用户内容。

## 已完成的原生验收

设备仅为自有 `emulator-5580`，AOSP API 34，1080×2400。使用本应用的虚构 QA 记录、AOSP 桌面/应用抽屉以及系统授权/快捷设置；没有连接实体手机，没有读取其他应用的私人数据。

逻辑基线 APK SHA256：`a4ba1dda2640a9b4fccaf930cce7f34949595790fc480ab5cae720c2a53a1a97`。统一 `assembleRelease` 和 `testDebugUnitTest` 成功，99 秒；92 suites / 566 tests / 0 failures / 0 errors / 0 skipped。

最终 df 样式包 SHA256：`df1a2034b35ddaa3eb2ef7aa0c2d749d8acc6d716cfbd89933e8d4904def9d1e`，76,300,094 字节，Release 构建 42 秒；双端覆盖安装和启动成功。该包仅调整木牌、共享文字色与二维转盘等 UI 样式，不改捕获、授权、游戏或网络逻辑，没有宣称在 df 上重新运行 566 项测试或重做本节所有授权场景。

原始 PNG/XML 位于 `D:/叽里咕噜/output/android-qa/screenshots/`，标签日志为 `D:/jiligulu-android-qa/oct6-a4-global-native.log`。

| 实际操作 | 观测结果 | 原生证据 |
| --- | --- | --- |
| 首次打开设置 | 新开关为 OFF；`dumpsys media_projection` 为 null | `oct6-a4-global-default-off.png` |
| 开启说明后拒绝系统授权 | 保留开启意愿并提示重新授权；projection 仍 null，没有采样帧日志 | `oct6-a4-global-system-consent.png`、`oct6-a4-global-denied.png` |
| 再次明确批准 | 系统显示共享标记；本应用内显示“整屏采样暂停”，继续原有本应用背景路径 | `oct6-a4-global-authorized-in-app.png` |
| 返回桌面、打开应用抽屉 | 外环随真实背景变化；无 RuntimeShader 异常和运行崩溃 | `oct6-a4-global-home-active.png`、`oct6-a4-global-dynamic-drawer.png` |
| 实际拖动到四角并松手 | 浮球均处于允许边界；稳定后继续显示，无阿噜自递归；ROI 在屏幕边缘缩窄 | `oct6-a4-global-corner-top-left.png`、`top-right`、`bottom-right`、`bottom-left` 同前缀文件 |
| 折射运行中点击截图 | 正常进入聊天的待识别图片卡片；截图预览是原桌面，未见浮球；未点击识别、未发出 DeepSeek 请求 | `oct6-a4-global-screenshot-import.png` |
| 截图后回本应用 | 同一个虚拟显示仍存在，但为全分辨率 1080×2400 且 state OFF；外部采样暂停，没有重复 token / VirtualDisplay 异常 | `oct6-a4-global-returned-app-pause.png` 与实际 dumpsys 观测 |
| 熄屏后唤醒 | 熄屏时 `mWakefulness=Asleep`，虚拟显示 `mCurrentSurface=null`、state OFF；唤醒后同次有效共享可继续 | 实际 power/display 输出与帧日志时间段 |
| 从系统快捷设置点击正在投屏的 Screen Cast | 系统状态变为 Off，随后 projection 为 null | `oct6-a4-global-system-cast-control.png` |
| 强停后 COLD 启动，保持原先 true 偏好 | 界面明确待重新授权，projection 仍 null，未自动采样 | `oct6-a4-global-cold-start-no-capture.png`、`oct6-a4-global-cold-resumed-state.png` |
| 最终关闭全局开关并交还设备 | UI 显示基础玻璃、不持续采样；projection=null，无 live 共享 | `oct6-a4-global-final-off.png`、最终标签日志 |

采样日志稳定段约每 14.1 秒处理 120 帧，约 8.5fps，低于 12fps 上限。默认位置 ROI 为 129×128，边角观测为 98×128；共享输入为 576×1280。日志记录的含主线程交付平均约 4ms，首帧为 43ms；不能把它解释为纯 GPU 时间或实体手机功耗。SurfaceFlinger 同时保留 `blurRegions radius=38 / rect=126×126`，中心的系统局部模糊没有被环带替代。

边界：截图原生预览已查看，但 release 包不可 `run-as`，未绕过权限读取私有 JPEG 做逐像素核验。该 AVD 未配置凭据锁屏，实际验证的是熄屏与唤醒，不把它写成 PIN/指纹锁屏验证。未另测所有旋转尺寸、受保护应用、实体 GPU 功耗或实体手机听感。停止共享与冷启的结论由系统状态确认；最终关开关是在系统共享已经停止后进行，不冒称额外重跑了运行中关闭开关的全部组合。

## 原生验收步骤

1. 升级安装后检查新开关默认关闭，基础全局玻璃仍有系统模糊，`GlobalGlass` 没有采样帧日志。
2. 设置中显式开启新选项，确认整屏共享授权；拒绝时不得出现采样，状态应等待授权。再次批准后查看系统共享标记与可关闭的静默通知。
3. 离开本应用，在桌面或普通可捕获应用中移动背景，观察玻璃外缘实时变化；中心保持模糊。对照 `GlobalGlass` 帧数与 ROI，检查无 shader 编译/采样异常。
4. 拖动浮球，包括四个屏幕角，过程中旧折射应清掉、无阿噜递归或闪烁；松手稳定后恢复边缘采样。
5. 回到本应用，确认通知/设置显示整屏采样暂停，原有应用内 PixelCopy 折射继续；再离开后恢复本次已授权采样。
6. 开启折射期间点击截图，检查最终原图没有浮球或被处理过的环带，截图正常导入；不重复申请或再次创建同 token 的 VirtualDisplay。
7. 熄屏、锁屏、停止系统共享、从通知关闭、关闭新开关分别检查停止/暂停和清缓存。杀进程再开，偏好可以为 true，但不得自行重新录取屏幕。
8. 旋转设备、改浮球大小、观察安全/黑屏内容的回退与尺寸变化。若系统实际仅共享单个应用，跨应用折射应暂停并提示共享整个屏幕，不使用错误坐标。

该列表保留作为后续设备回归清单；本轮实际执行范围以上面的“已完成的原生验收”为准，未执行项不算通过。

## 官方依据

- [Media projection](https://developer.android.com/media/grow/media-projection)：用户会话授权、单次 token、单个 VirtualDisplay、尺寸回调和资源释放。
- [MediaProjectionConfig](https://developer.android.com/reference/android/media/projection/MediaProjectionConfig)：Android 14 默认整屏共享配置。
- [WindowManager.LayoutParams.FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)：受保护窗口的捕获限制。
- [Android 窗口模糊](https://source.android.com/docs/core/display/window-blurs)：跨应用局部系统模糊与窗口内图像处理的区别。
