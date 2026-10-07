package com.jiligulu.app.data.local.dao

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.domain.chat.LedgerLookupGroup

@Dao
interface LedgerLookupDao {
    @RawQuery
    suspend fun details(query: SupportSQLiteQuery): List<BillEntity>
    @RawQuery
    suspend fun groups(query: SupportSQLiteQuery): List<LedgerLookupGroup>
}
