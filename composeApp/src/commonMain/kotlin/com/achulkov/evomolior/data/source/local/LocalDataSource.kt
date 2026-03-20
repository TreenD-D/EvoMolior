package com.achulkov.evomolior.data.source.local

import kotlinx.coroutines.flow.Flow

interface LocalDataSource<ID, M> {
    suspend fun getById(id: ID): M?
    fun getByIdFlow(id: ID): Flow<M?>
    suspend fun getAll(): List<M>
    fun getAllFlow(): Flow<List<M>>

    suspend fun upsert(model: M)
    suspend fun delete(model: M)
    suspend fun deleteById(id: ID)
    suspend fun deleteAll()
}
