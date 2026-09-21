package com.jiligulu.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 颜色 token —— PRD §5.1：业务代码只允许引用这里的 token，禁止硬编码色值。
 * v0.3 按 GPT 设计稿（奶油便签风）定稿，换皮只改这里。
 */

// ---------- 浅色（默认 · 奶油便签） ----------
val BgLight = Color(0xFFFDFBF7)                 // 奶油纸色页面底
val SurfaceLight = Color(0xFFFFFFFF)            // 纯白卡片
val SurfaceVariantLight = Color(0xFFF0EEE8)     // 输入框/次级面
val OnBgLight = Color(0xFF292735)               // 文字主色
val OnSurfaceSecondaryLight = Color(0xFF777480) // 文字次色
val HintLight = Color(0xFF9CA3AF)               // 提示/占位
val OutlineLight = Color(0xFFE8E4DC)            // 暖米色描边

// ---------- 深色（深夜奶油：延续浅色的暖色温，不用冷蓝黑） ----------
val BgDark = Color(0xFF211E1B)                // 暖深棕页面底
val SurfaceDark = Color(0xFF2B2723)           // 卡片：暖灰棕
val SurfaceVariantDark = Color(0xFF363129)    // 输入框/次级面
val OnBgDark = Color(0xFFF0EAE0)              // 暖白文字主色
val OnSurfaceSecondaryDark = Color(0xFFABA29A) // 文字次色
val HintDark = Color(0xFF7D766C)              // 提示/占位
val OutlineDark = Color(0xFF463F37)           // 暖描边

// ---------- 叽里咕噜品牌色 ----------
val GuluPurple = Color(0xFFAFA9EC)      // 品牌紫（按钮/头像/选中）
val GuluPurpleDeep = Color(0xFF534AB7)  // 品牌深紫（强调/按下）
val GuluPurpleSoft = Color(0xFFF0EFFB)  // 品牌紫淡底（浅色气泡/chip 底）
val GuluPurpleSoftDark = Color(0xFF3A3554)
val ActionPurple = Color(0xFF7965D0)          // 手账品牌紫
val OnPurpleDark = Color(0xFF26215C)
val CompanionSurfaceLight = Color(0xFFEEEBF7)
val CompanionSurfaceDark = Color(0xFF322D42)
val PaperNoteLight = Color(0xFFFFF5DE)
val PaperNoteDark = Color(0xFF41382B)
val PaperInkLight = Color(0xFF6C5F48)
val PaperInkDark = Color(0xFFE3D4B4)

// ---------- 语义色（中国习惯：红=收入/涨，绿=支出/跌） ----------
val IncomeRed = Color(0xFFE0654F)
val ExpenseGreen = Color(0xFF5FA568)

/** 余粮环"剩余预算"固定语义绿（PRD §5.4：不与分类色混用） */
val BudgetRemainGreen = Color(0xFF639922)
val DangerRed = Color(0xFFE24B4A)

/** 余粮环"已用预算"槽底色 */
val BudgetTrackLight = Color(0xFFEDEAE0)
val BudgetTrackDark = Color(0xFF463F37)

/** 喝水提醒小水杯的水色（奶油系淡蓝，深浅色通用） */
val CupWaterBlue = Color(0xFF8FCDE8)

/** 叽里咕噜腮红（设计稿同款粉） */
val GuluBlushPink = Color(0xFFF2A9B8)
