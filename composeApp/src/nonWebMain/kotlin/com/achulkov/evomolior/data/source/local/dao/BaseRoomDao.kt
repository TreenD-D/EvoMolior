package com.achulkov.evomolior.data.source.local.dao

import kotlinx.coroutines.flow.Flow

interface BaseRoomDao<ID, Entity> {
    suspend fun getById(id: ID): Entity?
    fun getByIdFlow(id: ID): Flow<Entity?>
    suspend fun getAll(): List<Entity>
    fun getAllFlow(): Flow<List<Entity>>

    suspend fun upsert(entity: Entity)

    suspend fun delete(entity: Entity)
    suspend fun deleteById(id: ID)
    suspend fun deleteAll()
}
