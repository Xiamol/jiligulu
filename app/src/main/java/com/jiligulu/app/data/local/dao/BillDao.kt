package com.jiligulu.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.jiligulu.app.data.local.entity.BillEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BillDao {

    @Insert
    suspend fun insert(bill: BillEntity): Long

    @Delete
    suspend fun delete(bill: BillEntity)

    @Query("SELECT * FROM bills WHERE id = :id")
    fun observeById(id: Long): Flow<BillEntity?>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getById(id: Long): BillEntity?

    /** Only user-editable columns change; attachments and original AI input remain intact. */
    @Query("UPDATE bills SET amountFen = :amountFen, detail = :detail, timestamp = :timestamp WHERE id = :id")
    suspend fun updateDetails(id: Long, amountFen: Long, detail: String, timestamp: Long): Int

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** 时间范围内的账单，新的在前。日视图/月视图/预算周期都靠它，聚合在内存里做 */
    @Query("SELECT * FROM bills WHERE timestamp >= :startMillis AND timestamp < :endMillis ORDER BY timestamp DESC")
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<BillEntity>>
}
