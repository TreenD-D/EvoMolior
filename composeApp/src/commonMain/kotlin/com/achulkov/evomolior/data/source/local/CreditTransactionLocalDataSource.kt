package com.achulkov.evomolior.data.source.local

import com.achulkov.evomolior.domain.model.credit.CreditTransaction
import kotlinx.coroutines.flow.Flow

interface CreditTransactionLocalDataSource : LocalDataSource<String, CreditTransaction> {
    fun getRecentsFlow(limit: Int): Flow<List<CreditTransaction>>
    suspend fun getRecents(limit: Int): List<CreditTransaction>
}