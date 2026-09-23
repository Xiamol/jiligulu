package com.jiligulu.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 跳转卡的选项解析。
 *
 * 守的是「模型编跳转目标」这条底线：表外 action 必须判成 [ActionPick.Ignore]，
 * 否则用户会点到一个不存在的页面——比"没有按钮"糟得多。
 * 同时兼容提示词里的 `navigate:trash` 前缀式和裸值 `trash`：
 * 模型两种都可能吐，UI 不该因为写法差异丢掉一次合法跳转。
 */
class ActionCardTest {

    @Test
    fun `both prefixed and bare targets are accepted`() {
        assertEquals(ActionPick.Navigate("trash"), ChatViewModel.parseAction("navigate:trash"))
        assertEquals(ActionPick.Navigate("trash"), ChatViewModel.parseAction("trash"))
        assertEquals(ActionPick.Navigate("trash_draft"), ChatViewModel.parseAction("navigate:trash_draft"))
        assertEquals(ActionPick.Navigate("settings"), ChatViewModel.parseAction("settings"))
        assertEquals(ActionPick.Navigate("add_bill"), ChatViewModel.parseAction("navigate:add_bill"))
    }

    @Test
    fun `the restore assist action maps to its local action`() {
        assertEquals(ActionPick.RestoreAssist, ChatViewModel.parseAction("restore_assist"))
    }

    @Test
    fun `invented targets are ignored rather than navigated`() {
        listOf(
            "", "   ", "navigate:", "navigate:home", "navigate:  ",
            "delete_everything", "https://example.com", "trash2", "设置"
        ).forEach { junk ->
            assertEquals("表外取值必须忽略，'$junk'", ActionPick.Ignore, ChatViewModel.parseAction(junk))
        }
    }

    @Test
    fun `surrounding whitespace does not break a valid target`() {
        assertEquals(ActionPick.Navigate("trash"), ChatViewModel.parseAction("  navigate:trash  "))
    }
}
