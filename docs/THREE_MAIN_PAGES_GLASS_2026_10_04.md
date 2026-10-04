# 三主页、立体纸星及玻璃悬浮球（2026-10-04）

本地预览，沿用 0.6.2 / versionCode12，未发布 GitHub。

## 用户确定的行为

- 主页面顺序为账本、小窝、统计；小窝在中间，不是另一个带返回栏的子页面。
- 顶部原房子改信箱，去掉账本通栏信箱；拉下刷新公告保持。
- 账本今天的尽头及非账单区域左滑到相邻小窝；小窝正文双向横拖到账本/统计，弹窗期间不抢手势。统计内部日期手势保留。
- 底栏三等份，点按跳转、按住直接拖动及真实页面跟手均保留。
- 悬浮球静默操作、记忆正常停留位置；拖到隐藏区不记垃圾桶位置，重启恢复拖动前的正常位置。
- 星星需要立体纸质与堆叠；悬浮球参考苹果 Liquid Glass 的半透明材质。

## 实现与接手重点

- MainPageCount=3，底栏中心 w/6、w/2、5w/6，连续进度 0..2；持续 pager.scroll 跟手，三页常驻。
- LittleWorldScreen embedded/active/modifier/onModalChanged 接口：共享顶栏底栏、移除独立返回及重复系统边距，生活桌角含真实愿望进度、收藏数量与阿噜。离屏停持续订阅、日期 ticker、时光机查询，保留已显示状态。
- MainActivity 传入愿望/信笺/纪念册/算盘记账回调。旧 LITTLE_WORLD route 仅保留兼容。
- MailboxHeaderButton 可打开最新信笺，现有阅读窗保留上一封/下一封；加载时转圈，空信箱有明确小窗。数字是现存来信数量，不伪称未读。
- 删去公告 lazy item 后，Home 吸附锚点使用 HOME_LEDGER_ITEM_INDEX=2。不能再写固定 3；此前手势/归零修复保留。
- StarWishJar 使用真正透明的 3×2 立体纸星图集，阴影/折面/不同尺寸与角度、前后遮挡和玻璃高光；数量仍对应进度。尺寸相关布局缓存，资源加入预加载。
- FloatingCaptureService 启动前原子读大小/位置。移动不写 IO，松手保存归一化位置；取消拖动复原，截图临时隐藏不覆盖，垃圾桶隐藏保存 dragStart。最终 edit 使用 NonCancellable 避免刚松手退出丢失。
- GlassFloatingBubbleView 使用透明阿噜代替含奶油方块的 launcher；半透明玻璃壳、边光/软影、按压回弹，绘图材料按尺寸缓存，无常驻动画。
- 玻璃效果是 Android 透底/高光近似，不采样其他 app 屏幕、不使用私有 SurfaceControl 或增加截屏授权。[Apple 设计参考](https://www.apple.com/newsroom/2025/06/apple-introduces-a-delightful-and-elegant-new-software-design/)。
- 普通隐藏 Toast 已删除。Android 必需前台通知为静默 LOW/PRIORITY_LOW、无声/振动/badge/横幅，不使用 MIN，避免额外系统运行提示。[Android 约束](https://developer.android.com/develop/background-work/services/fgs/launch)。

## 实际检查

- 16 项目标测试通过：几何位置1、位置设置3、Home吸附5、底栏真实手势2、三页进度4、信箱/键盘入口1。未跑全套。
- Release 实际安装于专用 Android14 模拟器。小窝左滑到统计、右滑到账本；右上信箱打开空信笺；桌角与入口文字检查。
- 立体纸星与玻璃球的原生截图已看。正常拖动后停止/重启服务恢复；再拖垃圾桶隐藏后重新启动仍恢复到相同的 (873,790)，非隐藏区落点。
- 截图目录 D:/叽里咕噜/output/android-qa/screenshots；重点 three-main-world-final.png、stars-wish-after.png、glass-floating-moved.png、glass-floating-hidden-restored.png。
- QA 数据全为虚构，无手机连接/未修改 vivo 用户数据。系统通知抽屉中的必需服务条目仍由 Android 控制。

## 素材与交付

- 使用 built-in image_gen，生成透明立体纸星 sprite atlas；原生成文件留在 CODEX_HOME，项目副本 app/src/main/res/drawable-nodpi/wish_lucky_stars_atlas_v2.png。
- 素材备份 D:/叽里咕噜/output/wish-stars-v2/wish_lucky_stars_atlas_v2.png；完整最终提示词 D:/叽里咕噜/output/wish-stars-v2/prompt.txt。
- APK D:/叽里咕噜/releases/jiligulu-0.6.2-main-world-glass-preview.apk；SHA256 dae75916c454ea52420687cf54118a826467ad316a8b93b5583ca5195854ac5a。
- 原签名 SHA256 ddd9a47bcf8646ed468a1f0741be61a6e7f3bbae82133d3666d28264a2b534f8；Release 非 debug。
- 最新实际图集 D:/叽里咕噜/releases/main-world-preview/index.html。
