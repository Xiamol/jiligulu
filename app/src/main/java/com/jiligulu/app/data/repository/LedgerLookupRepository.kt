package com.jiligulu.app.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.jiligulu.app.data.local.AppDatabase
import androidx.room.withTransaction
import com.jiligulu.app.domain.chat.LedgerLookup
import com.jiligulu.app.domain.chat.LedgerLookupResult
import com.jiligulu.app.domain.category.CategoryLabels

class LedgerLookupRepository(private val database: AppDatabase) {
    private val dao get() = database.ledgerLookupDao()
    suspend fun search(lookup: LedgerLookup): LedgerLookupResult = database.withTransaction {
        val args = mutableListOf<Any>()
        val clauses = mutableListOf("b.deletedAt IS NULL")
        val catalog = if (lookup.categories.isNotEmpty() || lookup.keywords.isNotEmpty())
            database.categoryDao().findAllOnce() else emptyList()
        lookup.startMillis?.let { clauses += "b.timestamp >= ?"; args += it }
        lookup.endMillis?.let { clauses += "b.timestamp < ?"; args += it }
        if (lookup.type.isNotBlank()) { clauses += "b.type = ?"; args += lookup.type }
        if (lookup.categories.isNotEmpty()) {
            // The model sees display labels; old IDs/raw names must remain searchable as aliases.
            val ids = catalog.filter { category ->
                lookup.categories.any { requested -> category.name.equals(requested, true) ||
                    CategoryLabels.displayName(category.name).equals(CategoryLabels.displayName(requested), true) }
            }.map { it.id }
            if (ids.isEmpty()) clauses += "0" else {
                clauses += "b.categoryId IN (${ids.joinToString { "?" }})"
                args.addAll(ids)
            }
        }
        if (lookup.keywords.isNotEmpty()) {
            // INSTR treats %, _ and apostrophes literally. Never interpolate model text into SQL.
            val keywordClauses = lookup.keywords.map { term ->
                args.addAll(listOf(term, term))
                "(instr(lower(b.detail), lower(?)) > 0 OR instr(lower(b.note), lower(?)) > 0)"
            }.toMutableList()
            val categoryIds = catalog.filter { category -> lookup.keywords.any { term ->
                category.name.contains(term, true) || CategoryLabels.displayName(category.name).contains(term, true)
            } }.map { it.id }
            if (categoryIds.isNotEmpty()) {
                keywordClauses += "b.categoryId IN (${categoryIds.joinToString { "?" }})"
                args.addAll(categoryIds)
            }
            clauses += keywordClauses.joinToString(" OR ", "(", ")")
        }
        val from = " FROM bills b LEFT JOIN categories c ON c.id = b.categoryId WHERE " + clauses.joinToString(" AND ")
        val groups = dao.groups(SimpleSQLiteQuery(
            "SELECT COALESCE(c.name, '未分类') AS categoryName, b.type AS type, COUNT(*) AS billCount, SUM(b.amountFen) AS amountFen" +
                from + " GROUP BY b.categoryId, b.type ORDER BY amountFen DESC", args.toTypedArray()))
        val details = dao.details(SimpleSQLiteQuery("SELECT b.*" + from +
            " ORDER BY b.timestamp DESC, b.id DESC LIMIT ${LedgerLookup.DETAIL_LIMIT}", args.toTypedArray()))
        LedgerLookupResult(lookup, details, groups)
    }
}
