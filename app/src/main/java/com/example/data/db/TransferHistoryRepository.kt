
package com.example.data.db

import kotlinx.coroutines.flow.Flow

class TransferHistoryRepository(
    private val dao: TransferHistoryDao,
) {
    /**
     * Backward-compatible history stream.
     *
     * Kept for existing ViewModel/UI consumers.
     *
     * Prefer getRecentHistory() for new code.
     */
    val allHistory: Flow<List<TransferHistoryEntity>>
        get() = dao.getAllHistory()

    /**
     * Observe only the newest transfer records.
     *
     * Preferred API for new UI code.
     */
    fun getRecentHistory(limit: Int = DEFAULT_HISTORY_LIMIT): Flow<List<TransferHistoryEntity>> = dao.getRecentHistory(limit)

    /**
     * Observe recent transfers filtered by status.
     */
    fun getHistoryByStatus(
        status: String,
        limit: Int = DEFAULT_HISTORY_LIMIT,
    ): Flow<List<TransferHistoryEntity>> = dao.getHistoryByStatus(status, limit)

    /**
     * Records a single terminal transfer state.
     *
     * Call only for COMPLETED, FAILED, or CANCELLED transfers.
     */
    suspend fun recordTransfer(entity: TransferHistoryEntity): Long = dao.insert(entity)

    /**
     * Records multiple transfers in one Room operation.
     */
    suspend fun recordTransfers(entities: List<TransferHistoryEntity>) {
        if (entities.isNotEmpty()) {
            dao.insertAll(entities)
        }
    }

    /**
     * Deletes one transfer history entry.
     */
    suspend fun deleteEntry(id: Long) {
        dao.deleteById(id)
    }

    /**
     * Deletes all transfer history.
     */
    suspend fun clearHistory() {
        dao.clearAll()
    }

    /**
     * Keeps only the newest [keepCount] records.
     */
    suspend fun trimHistory(keepCount: Int = DEFAULT_HISTORY_LIMIT) {
        if (keepCount > 0) {
            dao.trimToLatest(keepCount)
        }
    }

    /**
     * Returns the total number of stored records.
     */
    suspend fun getCount(): Int = dao.getCount()

    companion object {
        private const val DEFAULT_HISTORY_LIMIT = 100
    }
}
