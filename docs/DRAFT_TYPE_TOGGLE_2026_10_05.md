# 草稿收支直接切换

2026-10-05。展开草稿的收支字段只有支出、收入两个值，点击标签直接切到另一种，删除下拉窗、箭头及弹窗状态。沿用现有 onUpdate 草稿更新与保存路径，禁用状态不响应；40dp 最小宽度保留点击范围，读屏操作提示标明要切到的类型。

Release 构建和 lintVitalRelease 通过。在 API 34 安卓模拟器的虚构草稿页中，点击第一笔“支出”即显示“收入”和绿色 +¥，再次点击恢复“支出”和红色 −¥；另一笔草稿、金额、分类、时间与确认按钮不受影响。没有确认入账，没有在线 AI 调用，未新增或运行无关测试。

- APK：D:/叽里咕噜/releases/jiligulu-0.6.2-draft-toggle-preview.apk
- SHA256：2d30d9f59c72cd5cec2b3db3cda0eeb4d5ee0b04caef45010069376f5eec11dd
- 签名 SHA256：ddd9a47bcf8646ed468a1f0741be61a6e7f3bbae82133d3666d28264a2b534f8（与历史版本相同）
- 原生截图：D:/叽里咕噜/output/android-qa/screenshots/draft-toggle-expense.png、draft-toggle-income.png、draft-toggle-back-expense.png

版本保持 0.6.2 / 12，预览包包含前面的四款贴合皮肤改动，未发布 GitHub。
