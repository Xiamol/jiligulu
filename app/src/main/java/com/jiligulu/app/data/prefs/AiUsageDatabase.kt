package com.jiligulu.app.data.prefs

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jiligulu.app.core.ai.*

data class AiCostGroup(val day: String?, val purpose: AiUsagePurpose, val providerKey: String,
    val providerName: String, val model: String, val calls: Long, val reportedCalls: Long,
    val cacheHit: Long, val cacheMiss: Long, val unclassifiedInput: Long, val output: Long,
    val cachePico: Long?, val missPico: Long?, val outputPico: Long?, val flatPico: Long?,
    val knownPico: Long?, val unknownCalls: Long, val legacyCalls: Long,
    val inputReportedCalls: Long = 0, val outputReportedCalls: Long = 0, val cacheReportedCalls: Long = 0,
    val cacheHitReportedCalls: Long = 0, val cacheMissReportedCalls: Long = 0)

/** Persistent request metadata, bounded SQL chart queries; never stores messages, images, URLs or keys. */
internal class AiUsageDatabase(context: Context, name: String = "ai_usage_v2.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE prices (providerKey TEXT PRIMARY KEY, version TEXT NOT NULL, cacheRate INTEGER NOT NULL, missRate INTEGER NOT NULL, outputRate INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE attempts (
            id TEXT PRIMARY KEY, day TEXT, startedAt INTEGER NOT NULL, providerKey TEXT NOT NULL,
            providerGroup TEXT NOT NULL, providerName TEXT NOT NULL, model TEXT NOT NULL, purpose TEXT NOT NULL,
            calls INTEGER NOT NULL, reportedCalls INTEGER NOT NULL, inputReportedCalls INTEGER NOT NULL,
            outputReportedCalls INTEGER NOT NULL, cacheReportedCalls INTEGER NOT NULL, cacheHitReportedCalls INTEGER NOT NULL,
            cacheMissReportedCalls INTEGER NOT NULL, cacheHit INTEGER NOT NULL, cacheMiss INTEGER NOT NULL,
            unclassifiedInput INTEGER NOT NULL, output INTEGER NOT NULL, cachePico INTEGER, missPico INTEGER,
            outputPico INTEGER, flatPico INTEGER, knownPico INTEGER, unknownCalls INTEGER NOT NULL,
            unknownFlags INTEGER NOT NULL, legacyCalls INTEGER NOT NULL, priceVersion TEXT,
            cacheRate INTEGER, missRate INTEGER, outputRate INTEGER)""")
        db.execSQL("CREATE INDEX attempts_day ON attempts(day, purpose)")
        db.execSQL("CREATE INDEX attempts_provider_day ON attempts(providerKey, day)")
        db.execSQL("CREATE INDEX attempts_group ON attempts(providerGroup, purpose)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported usage schema upgrade")

    fun price(profile: AiProviderProfile): AiPriceSnapshot? {
        val key = AiUsageTicket.providerKey(profile)
        readableDatabase.rawQuery("SELECT version,cacheRate,missRate,outputRate FROM prices WHERE providerKey=?", arrayOf(key)).use {
            if (it.moveToFirst()) return AiPriceSnapshot(it.getString(0), it.getLong(1), it.getLong(2), it.getLong(3))
        }
        return AiPriceSnapshot.userDeepSeekDefault.takeIf { profile.id == AiProviderId.DEEPSEEK && profile.model == AiConfig.MODEL }
    }
    fun savePrice(profile: AiProviderProfile, price: AiPriceSnapshot) {
        writableDatabase.insertWithOnConflict("prices", null, ContentValues().apply {
            put("providerKey", AiUsageTicket.providerKey(profile)); put("version", price.version)
            put("cacheRate", price.cacheRateMicros); put("missRate", price.missRateMicros); put("outputRate", price.outputRateMicros)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun finish(ticket: AiUsageTicket, usage: AiTokenUsage?): Boolean {
        val estimate = AiCostEstimate.calculate(usage, ticket.price)
        return writableDatabase.insertWithOnConflict("attempts", null, row(ticket.id, ticket.day, ticket.startedAt,
            ticket.providerKey, ticket.providerGroup, ticket.providerName, ticket.model, ticket.purpose,
            AiUsageTotals().add(usage), estimate, 0, ticket.price), SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun migrateLegacy(snapshots: List<Pair<String, AiUsageSnapshot>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!legacyMigrated(db)) for ((provider, snapshot) in snapshots) {
                val name = when (provider) { "deepseek" -> "DeepSeek（旧汇总）"; "custom" -> "自定义（旧汇总）"; else -> "历史配置（旧汇总）" }
                val group = if (provider.startsWith("deepseek")) "deepseek" else if (provider == "custom") "custom" else "legacy"
                val today = snapshot.today
                fun insert(id: String, day: String?, totals: AiUsageTotals) {
                    if (totals.calls <= 0) return
                    db.insertWithOnConflict("attempts", null, row("legacy:$provider:$id", day, 0,
                        "legacy:$provider", group, name, "未记录模型", AiUsagePurpose.UNSPECIFIED,
                        totals, AiCostEstimate(null, null, null, null, AiCostUnknown.LEGACY), totals.calls, null),
                        SQLiteDatabase.CONFLICT_IGNORE)
                }
                insert("dated", snapshot.day.takeIf { runCatching { java.time.LocalDate.parse(it) }.isSuccess }, today)
                fun minus(a: Long, b: Long) = (a - b).coerceAtLeast(0)
                insert("undated", null, snapshot.total.copy(calls = minus(snapshot.total.calls, today.calls),
                    reportedCalls = minus(snapshot.total.reportedCalls, today.reportedCalls),
                    inputReportedCalls = minus(snapshot.total.inputReportedCalls, today.inputReportedCalls),
                    outputReportedCalls = minus(snapshot.total.outputReportedCalls, today.outputReportedCalls),
                    cacheReportedCalls = minus(snapshot.total.cacheReportedCalls, today.cacheReportedCalls),
                    cacheHitReportedCalls = minus(snapshot.total.cacheHitReportedCalls, today.cacheHitReportedCalls),
                    cacheMissReportedCalls = minus(snapshot.total.cacheMissReportedCalls, today.cacheMissReportedCalls),
                    cacheHit = minus(snapshot.total.cacheHit, today.cacheHit), cacheMiss = minus(snapshot.total.cacheMiss, today.cacheMiss),
                    unclassifiedInput = minus(snapshot.total.unclassifiedInput, today.unclassifiedInput), output = minus(snapshot.total.output, today.output)))
            }
            db.insertWithOnConflict("metadata", null, ContentValues().apply { put("name", "legacy_v1"); put("value", "1") }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun legacyMigrated(db: SQLiteDatabase = readableDatabase): Boolean = db.rawQuery(
        "SELECT value FROM metadata WHERE name='legacy_v1'", null).use { it.moveToFirst() && it.getString(0) == "1" }

    /** At most days × purposes rows are rendered, regardless of lifetime request count. */
    fun daily(start: String, end: String, providerGroup: String? = null, providerKey: String? = null): List<AiCostGroup> =
        aggregate("day>=? AND day<=?", listOf(start, end), providerGroup, providerKey, "day,purpose", detail = false)
    fun total(providerGroup: String? = null, providerKey: String? = null): List<AiCostGroup> =
        aggregate("1=1", emptyList(), providerGroup, providerKey, "purpose", detail = false)
    fun details(day: String, providerGroup: String? = null, providerKey: String? = null): List<AiCostGroup> =
        aggregate("day=?", listOf(day), providerGroup, providerKey, "purpose,providerKey,providerName,model", detail = true)

    private fun aggregate(where: String, args: List<String>, providerGroup: String?, providerKey: String?,
        grouping: String, detail: Boolean): List<AiCostGroup> {
        val selection = buildString {
            append(where)
            if (providerGroup != null) append(" AND providerGroup=?")
            if (providerKey != null) append(" AND providerKey=?")
        }
        val parameters = args + listOfNotNull(providerGroup, providerKey)
        val fields = """day,purpose,${if (detail) "providerKey,providerName,model" else "'' AS providerKey,'' AS providerName,'' AS model"},
            SUM(calls) AS calls,SUM(reportedCalls) AS reportedCalls,SUM(cacheHit) AS cacheHit,SUM(cacheMiss) AS cacheMiss,
            SUM(unclassifiedInput) AS unclassifiedInput,SUM(output) AS output,SUM(cachePico) AS cachePico,
            SUM(missPico) AS missPico,SUM(outputPico) AS outputPico,SUM(flatPico) AS flatPico,SUM(knownPico) AS knownPico,
            SUM(unknownCalls) AS unknownCalls,SUM(legacyCalls) AS legacyCalls,SUM(inputReportedCalls) AS inputReportedCalls,
            SUM(outputReportedCalls) AS outputReportedCalls,SUM(cacheReportedCalls) AS cacheReportedCalls,
            SUM(cacheHitReportedCalls) AS cacheHitReportedCalls,SUM(cacheMissReportedCalls) AS cacheMissReportedCalls"""
        return readableDatabase.rawQuery("SELECT $fields FROM attempts WHERE $selection GROUP BY $grouping ORDER BY day,purpose", parameters.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.costGroup()) }
        }
    }
    private fun Cursor.costGroup(): AiCostGroup {
        fun number(name: String) = getLong(getColumnIndexOrThrow(name))
        fun optional(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
        fun text(name: String) = getString(getColumnIndexOrThrow(name)).orEmpty()
        return AiCostGroup(text("day").ifBlank { null }, runCatching { AiUsagePurpose.valueOf(text("purpose")) }.getOrDefault(AiUsagePurpose.UNSPECIFIED),
            text("providerKey"), text("providerName"), text("model"), number("calls"), number("reportedCalls"),
            number("cacheHit"), number("cacheMiss"), number("unclassifiedInput"), number("output"),
            optional("cachePico"), optional("missPico"), optional("outputPico"), optional("flatPico"), optional("knownPico"),
            number("unknownCalls"), number("legacyCalls"), number("inputReportedCalls"), number("outputReportedCalls"), number("cacheReportedCalls"),
            number("cacheHitReportedCalls"), number("cacheMissReportedCalls"))
    }
    private fun row(id: String, day: String?, startedAt: Long, key: String, group: String, name: String, model: String,
        purpose: AiUsagePurpose, usage: AiUsageTotals, cost: AiCostEstimate, legacy: Long, price: AiPriceSnapshot?) = ContentValues().apply {
        put("id", id); put("day", day); put("startedAt", startedAt); put("providerKey", key); put("providerGroup", group)
        put("providerName", name); put("model", model); put("purpose", purpose.name); put("calls", usage.calls)
        put("reportedCalls", usage.reportedCalls); put("cacheHit", usage.cacheHit); put("cacheMiss", usage.cacheMiss)
        put("inputReportedCalls", usage.inputReportedCalls); put("outputReportedCalls", usage.outputReportedCalls); put("cacheReportedCalls", usage.cacheReportedCalls)
        put("cacheHitReportedCalls", usage.cacheHitReportedCalls); put("cacheMissReportedCalls", usage.cacheMissReportedCalls)
        put("unclassifiedInput", usage.unclassifiedInput); put("output", usage.output)
        put("cachePico", cost.cachePico); put("missPico", cost.missPico); put("outputPico", cost.outputPico)
        put("flatPico", cost.flatInputPico); put("knownPico", cost.knownPico)
        put("unknownCalls", if (cost.incomplete) usage.calls else 0L); put("unknownFlags", cost.unknownFlags); put("legacyCalls", legacy)
        put("priceVersion", price?.version); put("cacheRate", price?.cacheRateMicros); put("missRate", price?.missRateMicros); put("outputRate", price?.outputRateMicros)
    }
}
