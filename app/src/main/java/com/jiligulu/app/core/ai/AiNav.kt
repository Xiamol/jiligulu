package com.jiligulu.app.core.ai

/**
 * R5：AI 输出的跳转目标取值表（AiParseResult.navigate / AiOption.action）。
 *
 * 集中定义在一处而不是让模型与 UI 各自约定字符串：模型侧由 system 提示词里的
 * 「App 功能地图」引用，UI 侧（T04）据此路由；任何一方新增目标都必须加在这里。
 * UI 拿到表外的值一律忽略，不崩——防模型编造跳转目标。
 */
object NavTargets {
    const val TRASH = "trash"
    const val TRASH_DRAFT = "trash_draft"
    const val SETTINGS = "settings"
    const val ADD_BILL = "add_bill"
    const val CHECK_UPDATE = "check_update"
}

/**
 * R4：AI 输出的「动作型选项」取值表。
 *
 * 与 [NavTargets] 的区别：这些不是页面跳转，而是触发一条追问 / 工作流
 * （当前只有「让阿噜帮你恢复」一条，点击 = 以「帮我恢复账单」继续对话）。
 */
object IntentActions {
    /** 点击 = 发送「帮我恢复账单」追问，让模型进入恢复分派。 */
    const val RESTORE_ASSIST = "restore_assist"
}
