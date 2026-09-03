package com.example.data.db

import kotlinx.coroutines.flow.Flow

class TransferHistoryRepository(
    private val dao: TransferHistoryDao,
) {
    val allHistory: Flow<List<TransferHistoryEntity>> = dao.getAllHistory()

    suspend fun recordTransfer(entity: TransferHistoryEntity): Long = dao.insert(entity)

    suspend fun deleteEntry(id: Long) {
        dao.deleteById(id)
    }

    suspend fun clearHistory() {
        dao.clearAll()
    }
}
