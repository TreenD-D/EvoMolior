package com.achulkov.evomolior.data.source.local.impl

import com.achulkov.evomolior.data.source.local.LocalDataSource
import com.achulkov.evomolior.data.source.local.dao.BaseRoomDao
import com.achulkov.evomolior.data.source.local.entity.EntityMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

abstract class BaseRoomLocalDataSource<ID, Entity, Model>(
    protected val dao: BaseRoomDao<ID, Entity>,
    protected val mapper: EntityMapper<Entity, Model>
) : LocalDataSource<ID, Model> {

    override suspend fun getById(id: ID): Model? =
        dao.getById(id)?.let(mapper::toModel)

    override fun getByIdFlow(id: ID): Flow<Model?> =
        dao.getByIdFlow(id).map { it?.let(mapper::toModel) }

    override suspend fun getAll(): List<Model> =
        dao.getAll().map(mapper::toModel)

    override fun getAllFlow(): Flow<List<Model>> =
        dao.getAllFlow().map { it.map(mapper::toModel) }

    override suspend fun upsert(model: Model) =
        dao.upsert(mapper.toEntity(model))

    override suspend fun delete(model: Model) =
        dao.delete(mapper.toEntity(model))

    override suspend fun deleteById(id: ID) =
        dao.deleteById(id)

    override suspend fun deleteAll() =
        dao.deleteAll()
}
