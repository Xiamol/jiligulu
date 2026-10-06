# Compose 单测调度器修复（2026-10-06）

本轮将 `testImplementation` 的 `ui-test-junit4`、`ui-test` 固定为 1.8.0，并给 `debugImplementation` 单独添加 Compose BOM 2025.04.01。Release 的生产 `implementation`、版本目录 BOM 和 APK 依赖不变。Debug 与单测使用修正后的同一套 Compose；发布 Release 另在 Android API 34 原生模拟器上实际验收。

Robolectric 小窝收藏测试中，收藏数据已写入 DataStore，但界面订阅终止于 `SafeCollector.checkContext`。诊断显示 collected/emitted 两边的 `CoroutineId` 与 `ProducerCoroutine` 相同，两边却同时包含 `Dispatchers.IO` 和测试专有的 `ApplyingContinuationInterceptor`。

Compose 1.7.x 的该拦截器使用 `AbstractCoroutineContextKey` 子 key；切换 dispatcher 时它没有被普通 `ContinuationInterceptor` key 替换，导致 Flow 上下文校验失败。不能为此移除生产 `flowOn(Dispatchers.IO)`，也不能忽略业务收藏更新断言。

[AndroidX 上游修复](https://android.googlesource.com/platform/frameworks/support/+/e9b12da75cde38f0c216bfc86bf73ade12f60add%5E2..e9b12da75cde38f0c216bfc86bf73ade12f60add) 删除了该子 key，并补上替换 dispatcher 的测试。实际核对 Google Maven 源码：

- [1.7.8 源码](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.7.8/ui-test-android-1.7.8-sources.jar) 仍包含旧 key。
- [1.8.0 源码](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.8.0/ui-test-android-1.8.0-sources.jar) 已采用修正后的普通 key。

仅升级 UI 测试包后，测试运行时会使用 Foundation 1.8，但 `appDebug` 仍按 1.7.6 编译。两者的 `FlowRow` 重载签名不同，因此出现 `NoSuchMethodError`。让 Debug 的 UI、Foundation、Runtime 与测试依赖统一解决该 ABI 问题；不以删除 `FlowRow` 或减少界面检查绕过。

[Google Maven BOM 2025.04.01 的依赖映射](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.04.01/compose-bom-2025.04.01.pom) 已逐项核对：UI、Foundation、Runtime、ui-test 和 ui-test-junit4 均为 1.8.0，Material3 为 1.3.2，图标包为 1.7.8。2025.04.00 仍映射 1.7.8，不能代替此 Debug 专用版本。

诊断期间只对白名单 `Flow invariant is violated` 消息临时记录 CoroutineContext。定位后已移除该消息日志；生产 DEBUG 分支仅保留异常类和栈，不输出纸条、账单、API key 或异常业务消息。

UI fixture 同时排除了联网公告的异步盖窗、明确选择所属账单的关闭控件，并等待预加载页面中真正可见且可点击的统计分类。愿望场景需等待已解码瓶子的唯一可见入口，不能把先出现的场景标题当作入口已经就绪。所有数据写入、分类两次选中、收藏文字更新、固定按钮位置及愿望存款断言保留。

全量测试的线程转储另确认了 fixture 关闭数据库时的锁倒置：关闭线程持 Room write lock 等 SQLite ProcessLock，而尚在执行 `onOpen` 的查询线程持 ProcessLock 等 Room read lock。现在测试专用 helper 先完成已初始化数据库的 `writableDatabase` 打开，再关闭；这两步放在专用 I/O 工作线程。SDK Main 继续泵送被取消的 composition 工作、快照通知和 Looper 回调，并使用真实时间的 10 秒上限。关闭失败或超时会明确使测试失败，不会忽略清理或等待二十分钟。实际数据库关闭仍在 Robolectric 重置之前完成，避免旧 SQLite 指针污染下一个 fixture。生产进程的数据库生命周期没有调整。

进一步运行还确认 `ViewModelStore.clear()` 只请求取消，不代表 Room 的阻塞 cursor 查询已经退出。因此 fixture 在 Activity 销毁前、导航到目标页后登记 Activity/导航目的地/手工 store 的 `viewModelScope` Job，清理时先等待这些 Job 真正完成，再关闭数据库。导航目的地所有权按已核对的 Navigation 2.8.5 内部 store map 在测试里读取，不改变数据或生产控制流；已弹出的目的地在等待页面就绪时登记，避免销毁后丢失所有者。取消等待与 I/O 关闭共用同一真实时间上限，仍不采用固定延时或过滤未捕获异常。

最终统一执行 `:app:assembleRelease :app:testDebugUnitTest`：56 秒完成，`BUILD SUCCESSFUL`。实际报告为 85 suites、513 tests，失败 / 错误 / 跳过均为 0；13 项 UI fixture 全部保留并通过。Robolectric fixture 是自动回归证据，不当作实体手机或 ADB 原生截图；后者仍单独记录在本轮交付文档中。
