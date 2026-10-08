package com.jiligulu.app.domain.category

import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import java.util.Locale

/** Names and metadata share one contract across preview, reclassification and creation. */
object CategorySuggestions {
    fun name(value: String): String? = value.trim().takeIf { it.none(Char::isISOControl) }
        ?.replace(Regex("\\s+"), " ")?.takeIf {
        it.isNotBlank() && it.length <= 32 &&
            listOf("零钱通支付", "微信支付", "支付宝支付", "银行卡支付", "支付方式").none(it::contains)
    }
    fun key(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    fun existing(value: String, catalog: List<CategoryEntity>) = catalog.firstOrNull { key(it.name) == key(value) }
    fun preset(value: String) = CategoryDefaults.presets.firstOrNull { key(it.name) == key(value) }

    private data class Extra(val name: String, val icon: String, val keywords: String)
    private val extras = listOf(
        Extra("摄影", "📷", "摄影,拍摄,相机,镜头,胶卷,冲印"),
        Extra("保险", "🛡️", "保险,保费,医保,车险"),
        Extra("公益", "💝", "捐款,公益,捐赠"),
        Extra("母婴", "🍼", "奶粉,尿不湿,纸尿裤,婴儿,母婴"))

    fun icon(value: String, provided: String): String = provided.trim().take(16).takeIf { it.isNotBlank() }
        ?: preset(value)?.icon ?: extras.firstOrNull { it.name == value }?.icon ?: "🏷️"
    fun keywords(value: String, provided: String): String = provided.split(',', '，', ';', '；')
        .map(String::trim).filter { it.isNotBlank() && it.none(Char::isISOControl) }.distinct().joinToString(",").take(160)
        .ifBlank { preset(value)?.keywords ?: extras.firstOrNull { it.name == value }?.keywords ?: value }

    /** Conservative offline suggestions can restore a deleted useful category after confirmation. */
    fun local(text: String, type: BillType, catalog: List<CategoryEntity>): CategoryEntity? {
        if (text.isBlank()) return null
        val incomeNames = setOf("工资", "生活费", "红包", "转账")
        val useful = catalog.filter { it.deletable }
        val presets = CategoryDefaults.presets.filter { it.deletable && (type != BillType.INCOME || it.name in incomeNames) }
            .map { CategoryEntity(id = -1, name = it.name, iconValue = it.icon, keywords = it.keywords, colorHue = 0f, colorIndex = 0) }
        val extraCategories = if (type == BillType.INCOME) emptyList() else extras.map {
            CategoryEntity(id = -1, name = it.name, iconValue = it.icon, keywords = it.keywords, colorHue = 0f, colorIndex = 0)
        }
        val input = text.lowercase(Locale.ROOT)
        val specific = if (type == BillType.EXPENSE) when {
            listOf("苹果手机", "苹果耳机", "苹果电脑", "iphone", "ipad", "macbook", "airpods").any(input::contains) -> "数码"
            extras.firstOrNull { extra -> extra.keywords.split(',').any(input::contains) } != null ->
                extras.first { extra -> extra.keywords.split(',').any(input::contains) }.name
            else -> null
        } else null
        val existingMatch = CategoryEngine.suggest(text, useful)
        if (existingMatch != null && (specific == null || preset(existingMatch.name) == null || existingMatch.name == specific))
            return existingMatch
        val suggested = specific?.let { target -> (useful + presets + extraCategories).firstOrNull { it.name == target } }
            ?: CategoryEngine.suggest(text, useful + presets + extraCategories)
        return suggested?.let { existing(it.name, useful) ?: it }
    }
}
