package com.example.data.db

import kotlinx.coroutines.flow.Flow

class TransferHistoryRepository(
    private val dao: TransferHistoryDao,
) {
    /**
     * Backward-compatible stream of transfer history for ViewModel and UI.
     */
    val allHistory: Flow<List<TransferHistoryEntity>> = dao.getAllHistory()

    /**
     * Observe only the most recent transfers.
     *
     * Keeps UI memory usage and Flow emissions bounded.
     */
    fun getRecentHistory(limit: Int = DEFAULT_HISTORY_LIMIT): Flow<List<TransferHistoryEntity>> =
        dao.getRecentHistory(limit)

    /**
     * Observe transfers filtered by status.
     */
    fun getHistoryByStatus(
        status: String,
        limit: Int = DEFAULT_HISTORY_LIMIT,
    ): Flow<List<TransferHistoryEntity>> =
        dao.getHistoryByStatus(status, limit)

    /**
     * Record a single completed/failed/cancelled transfer.
     */
    suspend fun recordTransfer(entity: TransferHistoryEntity): Long =
        dao.insert(entity)

    /**
     * Record multiple transfers efficiently.
     */
    suspend fun recordTransfers(
        entities: List<TransferHistoryEntity>,
    ) {
        if (entities.isNotEmpty()) {
            dao.insertAll(entities)
        }
    }

    suspend fun deleteEntry(id: Long) {
        dao.deleteById(id)
    }

    suspend fun clearHistory() {
        dao.clearAll()
    }

    /**
     * Keep only the newest records.
     */
    suspend fun trimHistory(
        keepCount: Int = DEFAULT_HISTORY_LIMIT,
    ) {
        if (keepCount > 0) {
            dao.trimToLatest(keepCount)
        }
    }

    companion object {
        private const val DEFAULT_HISTORY_LIMIT = 100
    }
}

