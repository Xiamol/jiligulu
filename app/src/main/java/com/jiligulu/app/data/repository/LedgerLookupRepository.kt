package com.jiligulu.app.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.jiligulu.app.data.local.AppDatabase
import androidx.room.withTransaction
import com.jiligulu.app.domain.chat.LedgerLookup
import com.jiligulu.app.domain.chat.LedgerLookupResult

class LedgerLookupRepository(private val database: AppDatabase) {
    private val dao get() = database.ledgerLookupDao()
    suspend fun search(lookup: LedgerLookup): LedgerLookupResult = database.withTransaction {
        val args = mutableListOf<Any>()
        val clauses = mutableListOf("b.deletedAt IS NULL")
        lookup.startMillis?.let { clauses += "b.timestamp >= ?"; args += it }
        lookup.endMillis?.let { clauses += "b.timestamp < ?"; args += it }
        if (lookup.type.isNotBlank()) { clauses += "b.type = ?"; args += lookup.type }
        if (lookup.categories.isNotEmpty()) {
            clauses += "c.name IN (${lookup.categories.joinToString { "?" }})"
            args.addAll(lookup.categories)
        }
        if (lookup.keywords.isNotEmpty()) {
            // INSTR treats %, _ and apostrophes literally. Never interpolate model text into SQL.
            clauses += lookup.keywords.joinToString(" OR ", "(", ")") {
                args.addAll(listOf(it, it, it))
                "(instr(lower(b.detail), lower(?)) > 0 OR instr(lower(b.note), lower(?)) > 0 OR instr(lower(c.name), lower(?)) > 0)"
            }
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
