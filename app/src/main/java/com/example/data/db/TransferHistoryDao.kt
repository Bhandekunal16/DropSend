package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TransferHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<TransferHistoryEntity>)

    /**
     * Returns recent history only.
     * Keeps memory usage and Flow emissions under control.
     */
    @Query("""
        SELECT *
        FROM transfer_history
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    fun getRecentHistory(limit: Int = 100): Flow<List<TransferHistoryEntity>>

    @Query("""
        SELECT *
        FROM transfer_history
        WHERE status = :status
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    fun getHistoryByStatus(
        status: String,
        limit: Int = 100
    ): Flow<List<TransferHistoryEntity>>

    @Query("""
        DELETE FROM transfer_history
        WHERE id = :id
    """)
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transfer_history")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM transfer_history")
    suspend fun getCount(): Int

    @Query("""
        DELETE FROM transfer_history
        WHERE id NOT IN (
            SELECT id
            FROM transfer_history
            ORDER BY timestamp DESC
            LIMIT :keepCount
        )
    """)
    suspend fun trimToLatest(keepCount: Int)
}

