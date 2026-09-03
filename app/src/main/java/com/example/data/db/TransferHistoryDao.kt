
package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferHistoryDao {
    /**
     * Inserts one transfer history record.
     *
     * Use only when a transfer reaches a terminal state:
     * COMPLETED, FAILED, or CANCELLED.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TransferHistoryEntity): Long

    /**
     * Efficient batch insertion.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<TransferHistoryEntity>)

    /**
     * Observe recent transfer history.
     *
     * LIMIT prevents large lists from being loaded into memory
     * and limits the amount of work caused by Flow emissions.
     */
    @Query(
        """
        SELECT *
        FROM transfer_history
        ORDER BY timestamp DESC
        LIMIT :limit
        """,
    )
    fun getRecentHistory(limit: Int = DEFAULT_LIMIT): Flow<List<TransferHistoryEntity>>

    /**
     * Backward-compatible unbounded query.
     */
    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<TransferHistoryEntity>>

    /**
     * Observe recent transfers with a specific status.
     *
     * Optimized by the (status, timestamp) index.
     */
    @Query(
        """
        SELECT *
        FROM transfer_history
        WHERE status = :status
        ORDER BY timestamp DESC
        LIMIT :limit
        """,
    )
    fun getHistoryByStatus(
        status: String,
        limit: Int = DEFAULT_LIMIT,
    ): Flow<List<TransferHistoryEntity>>

    /**
     * Delete one history entry.
     */
    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Delete all history.
     */
    @Query("DELETE FROM transfer_history")
    suspend fun clearAll()

    /**
     * Number of stored transfer records.
     */
    @Query("SELECT COUNT(*) FROM transfer_history")
    suspend fun getCount(): Int

    /**
     * Keep only the newest records.
     *
     * Useful for preventing unlimited database growth.
     */
    @Query(
        """
        DELETE FROM transfer_history
        WHERE id NOT IN (
            SELECT id
            FROM transfer_history
            ORDER BY timestamp DESC
            LIMIT :keepCount
        )
        """,
    )
    suspend fun trimToLatest(keepCount: Int)

    companion object {
        private const val DEFAULT_LIMIT = 100
    }
}
