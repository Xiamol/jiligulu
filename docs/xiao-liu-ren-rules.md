# 小六壬起课与解读约定

先保存用户这一问，再由用户选择当前农历月日时，或自己想到的三个数字。计算时保存本地时区、时间戳、农历月日、时辰及原始报数；重看和跨午夜均不重新起课。盘是本地确定性计算，AI 只结合问题解读已有落宫。

采用六宫顺序“大安、留连、速喜、赤口、小吉、空亡”，每一段的起点都计为 1。三位数字逐段顺数，本版本明确选择 **0 按 10 计**。这是报数的一种约定，不声称所有流派一致。闰月沿用该月号，23:00–00:59 为子时，日期仍取本次问占的当地公历日期，不采用晚子时自动加一天。

规则核对来自作者自己的公开仓库：[kev1nzh37/liuren](https://github.com/kev1nzh37/liuren)、[Sneezry/XiaoLiuRen-MCP](https://github.com/Sneezry/XiaoLiuRen-MCP)、[mxwz 的三数计算](https://github.com/mxwz/astrbot_plugin_zhanbu/blob/main/xlr.py)。0→10 的报数约定对照了[国学居作者的工具说明](https://www.guoxueju.com/shushu/liurensuduan.html)。仅核对规则，未复制这些项目的代码、签文、解释或美术。

农历转换使用 Android 平台 [ChineseCalendar](https://developer.android.com/reference/android/icu/util/ChineseCalendar)。公历日期在中国标准时中午转换，避免设备时区让日期漂移；本地时辰由保存的起课瞬间计算。回归日期以香港天文台 [2024](https://www.hko.gov.hk/tc/gts/time/calendar/pdf/files/2024.pdf) / [2025](https://www.hko.gov.hk/tc/gts/time/calendar/pdf/files/2025.pdf) 年历核对。

解读最多保留 16 份，缓存键绑定规范化问句、起课方式、实际算法来源和三段落宫。月日时法包含日期、时区、农历月日时及闰月；报数法包含三位原数及零转十后的计数。精确秒数仅保存展示，不导致同课重复收费。返回页面不会调用 AI；失败时先显示本地说明，用户可主动重试。只发送此问题和既定盘，不附账本或历史聊天，忽略模型返回的账单、记忆与导航操作。联网任务由应用持有，限时 50 秒；明确换一问并重新起课时替换上一任务。计量归入“小六壬分析”，由已有客户端统一记录每次 HTTP 请求。

星座运势沿用旧 `rewritten_day`，小六壬独立保存 `liuren_rewritten_day`，各自每日一次。趣味盖章只添一句鼓励，保留真实计算的原始三宫。

这是民俗娱乐，不作结果保证，不能替代现实决定。界面问号中提供说明，不在每一步堆放提示。
