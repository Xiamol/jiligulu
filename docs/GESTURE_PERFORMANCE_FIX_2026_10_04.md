# 2026-10-04 回弹残留、底栏即时拖动及绘制开销

接续 bab0dcb 的实际界面整理。用户报告：支出文字 caret 不对齐；上滑触底再下拉、翻往日再下拉会留下汇总与账单间的空白；底栏等长按才响应导致滑块滞后；整体仍有卡顿。

## 本次处理

- 草稿收支按钮使用独立 ExpandMore 图标、水平居中和明确间距，点击显示收入/支出小菜单，不再用文字字符充当箭头。
- Home 仅保留一个有正负方向的 EdgeSpringMotion，移除账单内第二个弹簧位移。触摸结束后晚到的 UserInput 不再改变位移；动画代次防止旧任务覆盖新手势。取消、离页、换日、暂停、销毁均清零。
- 原事件顺序漏洞：Initial-pass 的 up 先启动回弹，Main-pass 的最后一段滚动仍可能取消回弹。新增 touching 门槛，晚到滚动只消费、不再取消动画。
- 底栏不等长按，DOWN 抓住当前进度，超过横向 touchSlop 就拖动；第一段包含阈值前的位移，绝对轨道位置及 grabOffset 避免偏心抓取/反向滞后。未拖动的 UP 保留单次点击。
- 主页面拖动使用持续 pager.scroll 会话，以 scrollBy 跟随位置；不再对每个 MOVE 使用 collectLatest 取消、重建 scrollToPage。松手单独归位。
- Home/通用回弹的 Job 使用普通字段，位移只在 graphics layer 读。星瓶按尺寸缓存轮廓/星星/分面；虚线 PathEffect、图标解析、插画坐标、纸纹滤色减少重复创建。
- Android 14+ 的已挂载前台窗口可申请同分辨率真正支持的更高刷新率；尊重已有偏好和省电模式，暂停撤回自己的请求。不写 preferredDisplayModeId、不设一个假定的 120Hz。依据 [Android 官方窗口说明](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#preferredRefreshRate)，这是系统可接受的偏好，不保证实际 FPS。

## 小范围验证

- 18 项目标测试通过：HomePinGestureTest 5、EdgeSpringMotionTest 1、EdgeSpringStateTest 1、MainTabNavigationGestureTest 2、TabScrubPositionTest 3、WindowRefreshPreferenceTest 4、CategoryBadgeTest 2。Release 构建通过，未跑全套。
- 专用 Android 14/API 34 模拟器采用 NVIDIA 硬件加速，1080×2400/density420。QA 数据全为虚构，时区恢复 Asia/Tokyo。
- Release 实际检查：昨日 3 笔换页后上下拉、今日短列表触底后下拉及带最后 UP 位移均归位；日期/汇总保持，首笔图标 y=1054，未留下额外空白。底栏 DOWN 后立即 MOVE、没有等待长按，截图已有真实两页各半的画面。箭头实际截图核对。
- 截图在 D:/叽里咕噜/output/android-qa/screenshots：regression-shortday-after.png、regression-today-short-after.png、instant-tab-half-after.png、arrow-draft-after.png。
- 本轮没有手机连接；没有测得 vivo FPS，不把模拟器帧数当手机提升幅度。早期软件渲染模拟器的 gfxinfo 明显受 CPU 渲染影响，不能作为前后性能基准。

## 本地交付

- APK：D:/叽里咕噜/releases/jiligulu-0.6.2-gesture-smooth-preview.apk
- SHA256：ab153a882554e83e0e984d02c2c649efe1d9f23bb4665d3efcebcfec74061706
- 沿用 0.6.2 / versionCode12 / 原签名，未发布 GitHub。
- QA 启动脚本修复了 PowerShell Target 字符串被当数组首字符的问题；模拟器改用 host GPU，保留软件模式备份，原 SDK/用户手机未修改。
