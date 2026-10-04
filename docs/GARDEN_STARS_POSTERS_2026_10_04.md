# 小窝边角、瓶中星星与生活海报

2026-10-04，接续三个主页面、静默悬浮记账与玻璃图标的本地预览。版本仍为 0.6.2 / 12，本次未发布 GitHub。

## 最终效果

- 用户否定紫藤瀑布边框，随后允许其他花卉和原创设计。去掉废弃紫藤 PNG，改为原生 Canvas 绘制的稀疏五瓣花、细叶、小蝶和星光。只占两侧留白，不增加高度或拦截手势；路径、笔刷由 drawWithCache 缓存，没有常驻动画。
- 保留已认可的星形玻璃瓶外形，瓶内改用更有折面、厚度和光泽的六种星星素材，显示尺寸约为原来的 1.9 倍。星星数量仍随真实愿望进度变化，满瓶样图只是设计参考。
- 生活海报调整纸色、标题、分类条形图和落款；显式选择可变中文字体 wght=400/650，解决正文过细。字体与阿噜图片复用缓存。
- 竖照片海报为 1080×1760，保留整张照片比例。编辑弹窗先展示真实海报预览，再提供标题、文案和金额开关；金额默认隐藏。

## 参考与素材

仅借鉴布局、花量和色彩，不下载或拷贝商业图：

- [淡彩五瓣花参考](https://dribbble.com/shots/26669126-Pastel-Bloom-Harmony-Elegant-Watercolour-Floral-Border)
- [曾研究的简洁紫藤参考](https://tw.pixtastock.com/illustration/73806453)

内置 image_gen 生成星瓶填充参考和透明星星图集，实际产品素材为 `app/src/main/res/drawable-nodpi/wish_puffy_stars_atlas_v3.png`。工作区参考、提示词概要与废弃稿保存在 `D:/叽里咕噜/output/wisteria-wish-reference/`；废弃稿不打入 APK。

## 验证

- 一个有针对性的回归：`MemoriesTest.nativeLifePosterRendersBundledChineseAndSyntheticPhoto`，1 个测试通过。
- `:app:assembleRelease` 通过并安装到 API 34 安卓模拟器（1080×2400、density 420、硬件 GPU）。
- 查看了小窝主页面、30% 和满瓶截图；在实际 Release 应用中导出周明信片（隐藏金额/展示金额）和竖照片海报，检查字重、重叠、裁切和预览比例。
- 截图使用虚构账单、抽象测试照片；未向外部平台分享，不代表物理手机帧率测试。

## 交付

- APK：`D:/叽里咕噜/releases/jiligulu-0.6.2-garden-poster-preview.apk`
- SHA256：`b7e4da71d189e79cedc0f2f22a46ec42e554bd13ffb7063d1b363f2577debc14`
- 签名证书 SHA256 与历史版本一致：`ddd9a47bcf8646ed468a1f0741be61a6e7f3bbae82133d3666d28264a2b534f8`
- 真实结果画廊：`D:/叽里咕噜/releases/garden-poster-preview/index.html`
