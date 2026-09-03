
package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferHistoryDao {

    /**
     * Inserts a single completed/failed/cancelled transfer.
     *
     * Do not use this for live transfer progress updates.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TransferHistoryEntity): Long

    /**
     * Efficient batch insertion.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<TransferHistoryEntity>)

    /**
     * Observes the newest transfer records.
     *
     * Preferred query for UI/history screens.
     */
    @Query(
        """
        SELECT *
        FROM transfer_history
        ORDER BY timestamp DESC
        LIMIT :limit
        """,
    )
    fun getRecentHistory(
        limit: Int = DEFAULT_LIMIT,
    ): Flow<List<TransferHistoryEntity>>

    /**
     * Legacy unbounded history query.
     *
     * Prefer getRecentHistory() for new code.
     */
    @Deprecated(
        message = "Use getRecentHistory() to keep the result bounded.",
        ReplaceWith("getRecentHistory()"),
    )
    @Query(
        """
        SELECT *
        FROM transfer_history
        ORDER BY timestamp DESC
        """,
    )
    fun getAllHistory(): Flow<List<TransferHistoryEntity>>

    /**
     * Observes newest transfers for a specific status.
     *
     * Uses the (status, timestamp) index.
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
     * Deletes one transfer history record.
     */
    @Query(
        """
        DELETE FROM transfer_history
        WHERE id = :id
        """,
    )
    suspend fun deleteById(id: Long)

    /**
     * Deletes all transfer history.
     */
    @Query("DELETE FROM transfer_history")
    suspend fun clearAll()

    /**
     * Returns the number of stored records.
     */
    @Query("SELECT COUNT(*) FROM transfer_history")
    suspend fun getCount(): Int

    /**
     * Keeps only the newest [keepCount] records.
     *
     * Call periodically rather than after every insert.
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
    suspend fun trimToLatest(
        keepCount: Int,
    )

    companion object {
        const val DEFAULT_LIMIT = 100
    }
}

