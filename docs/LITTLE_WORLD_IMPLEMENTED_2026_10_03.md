> 2026-10-04 最终验收包完成：D:/叽里咕噜/releases/jiligulu-0.6.2-little-world-preview.apk。SHA256 2e956f9cf9fd49095fcab43516b4315c7bde0fbc7e30cccc87950847e2db714e。保持历史签名，版本0.6.2，未推送GitHub。以下记录覆盖10月3日至4日的同一批工作。

# 2026-10-03 阿噜小窝完整落地

本轮用户明确要求全部推进，过程中补充了“精美素材”“真正星形瓶身”“整张便签歪斜”的修订。保持0.6.2/versionCode12，同签名Release验收，尚未发布GitHub。

## 功能与入口

- 首页右上角小房子 → 阿噜的小窝。底部仍是账本/统计。
- 记一笔右侧折角 → 贴纸墙。无首页额外高度；12个初始可修改模板，三列可滚动，新增/编辑/删除/移动，只有确认保存才记账。
- 手动记账金额框右侧及小窝 → 四则计算器。BigDecimal、有界解析、括号优先级、除零/非法输入反馈，带入结果时按分显示取舍。不再有AA/折扣独立模式。
- 本地每日固定生活小签32句，可收藏；时间变更按本地日界重新选，同一天重进仍同一签。
- 小窝时光机读200笔以内过去账单，点击原账单详情，可关闭；不在首页增加大卡。
- 星星愿望册：真正五角星玻璃瓶、绳结/蝴蝶结水彩素材，真实进度填在星形内腔。进行中主目标+横向目标切换、候场、已完成纪念3区；CRUD/存入/记录/撤销/完成庆祝。完成瓶保留，可照片/短注/海报。候场转愿望原子归档，买到了先生成草稿；确认入账后才自动收起候场项，取消返回时保留，避免未确认就冒充入账。
- 未来便签：指定日期时间、前台首次到期小信、收件箱/旧信匣；可选POST_NOTIFICATIONS，后台用普通inexact闹钟，重启/升级/时间变化恢复。与更新/公告依次显示，不叠窗口；不请求新的精准闹钟权限。
- 账单详情夹照片与短注，金额/名称/时间/备注/照片一次SQL更新，软删除账单不能被照片修改复活。
- 生活纪念册保存照片票根和海报，周明信片默认上一完整周，按周一/次周一真实收支统计；标题可改、季节印章，金额默认隐藏。预览、相册保存、FileProvider系统分享、收藏，绝不自动发送社交消息。
- 点击/长按阿噜轻压缩回弹；普通聊天头像取消持续呼吸，互动角色保留轻动效。

## 数据与效率

新收藏使用独立Preferences DataStore原子JSON，不动已有账单/聊天数据库结构；SQLite版本保持6。金额均用分，愿望与账本不混算。照片先复制到私有files/life-memories/photos；生成海报在私有posters，取消渲染会清理未完成文件；已收藏/分享图不误删。

贴纸12图案为4×3水彩图集，纸片与墙面为素材底图，标题和金额由代码绘制；稳定ID决定角度/错位/颜色，重进不乱跳。星形瓶体为独立PNG，星星填充由真实进度驱动。四张位图后台预热、共享采样缓存，避免每枚贴纸/历史瓶重复解码。未使用店家/设计师原图作为App资产。

## 付款码已在真机确认

用户vivo双开手机已连接。直接调内部WalletOfflineCoinPurseUI原来失败；现在通过公开LauncherUI发ShortCutDispatchAction，extra LauncherUI.Shortcut.LaunchType=launch_type_offline_wallet，NEW_TASK|CLEAR_TOP。系统出现双开选择页，用户选择常用微信后明确回复“成功跳转/已进入付款码”。只查看组件/启动结果，没有读取付款码或聊天。

源码依据：https://github.com/ChaoMixian/vFlow/blob/b18b62ce1ab248dbbe0838da0051270af7119f78/app/src/main/java/com/chaomixian/vflow/core/workflow/module/integration/WeChatShortcutsModule.kt

## 视觉参考和资产

- UI层次参考：https://dribbble.com/shots/23326503-Coinly-App-Design-Animation-Saving-Jars
- 纸张/胶带层次参考：https://dribbble.com/shots/27263101-Travel-Mobile-App-UI-Vintage-Postcard-Journal-Retro
- 真正星形玻璃瓶实物参考：https://www.at-yokohama.net/events/3989
- App实际水彩PNG由内置image_gen生成，source在res/drawable-nodpi；原图、生成提示词备份在D:/叽里咕噜/output/little-world/assets/。

## 定向验证

24项独立针对性检查最终通过：收藏/愿望原子并发与撤销2、计算器6、愿望金额3、照片/账单/周期/真实海报7、微信4、综合新页面保存/渲染1、歪斜贴纸渲染1。修复了测试发现的冷Room订阅主线程问题和Windows file URI图片导入路径编码问题；实际PNG复制后删源仍可读取。相册/系统分享遵循原生流程，尚未自动操作用户手机发送或读取真实照片。

截图在app/build/reports/ui：sticker-drawer、wishbook-active/completed、little-world-fortune、future-notes-list/letter、life-poster-sample。都是虚构测试数据，照片示例为本地绘制fixture。
