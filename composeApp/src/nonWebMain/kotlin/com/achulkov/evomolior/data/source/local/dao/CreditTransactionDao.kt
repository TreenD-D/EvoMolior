package com.achulkov.evomolior.data.source.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.achulkov.evomolior.data.source.local.entity.CreditTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CreditTransactionDao : BaseRoomDao<String, CreditTransactionEntity> {
    @Query("SELECT * FROM credit_transaction WHERE id = :id")
    override suspend fun getById(id: String): CreditTransactionEntity?

    @Query("SELECT * FROM credit_transaction WHERE id = :id")
    override fun getByIdFlow(id: String): Flow<CreditTransactionEntity?>

    @Query("SELECT * FROM credit_transaction ORDER BY created_at DESC")
    override fun getAllFlow(): Flow<List<CreditTransactionEntity>>

    @Query("SELECT * FROM credit_transaction")
    override suspend fun getAll(): List<CreditTransactionEntity>

    @Query("SELECT * FROM credit_transaction ORDER BY created_at DESC LIMIT :limit")
    fun getRecentsFlow(limit: Int): Flow<List<CreditTransactionEntity>>

    @Query("SELECT * FROM credit_transaction ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecents(limit: Int): List<CreditTransactionEntity>

    //Insert or update if exists
    @Upsert
    override suspend fun upsert(entity: CreditTransactionEntity)

    @Query("DELETE FROM credit_transaction WHERE id = :id")
    override suspend fun deleteById(id: String)

    @Delete
    override suspend fun delete(entity: CreditTransactionEntity)

    @Query("DELETE FROM credit_transaction")
    override suspend fun deleteAll()
}