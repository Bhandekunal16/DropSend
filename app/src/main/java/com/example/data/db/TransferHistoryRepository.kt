package com.example.data.db

import kotlinx.coroutines.flow.Flow

class TransferHistoryRepository(
    private val dao: TransferHistoryDao,
) {
    val allHistory: Flow<List<TransferHistoryEntity>> = dao.getAllHistory()

    fun getRecentHistory(limit: Int = 100): Flow<List<TransferHistoryEntity>> =
        dao.getRecentHistory(limit)

    suspend fun recordTransfer(entity: TransferHistoryEntity): Long = dao.insert(entity)

    suspend fun recordTransfers(entities: List<TransferHistoryEntity>) {
        dao.insertAll(entities)
    }

    suspend fun deleteEntry(id: Long) {
        dao.deleteById(id)
    }

    suspend fun clearHistory() {
        dao.clearAll()
    }

    suspend fun trimHistory(keepCount: Int = 100) {
        dao.trimToLatest(keepCount)
    }
}
