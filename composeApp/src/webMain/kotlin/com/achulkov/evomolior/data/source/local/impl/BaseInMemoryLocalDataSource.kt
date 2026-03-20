package com.achulkov.evomolior.data.source.local.impl

import com.achulkov.evomolior.data.source.local.LocalDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

abstract class BaseInMemoryLocalDataSource<ID, M>(
    private val idSelector: (M) -> ID
) : LocalDataSource<ID, M> {

    private val mutex = Mutex()
    private val items = mutableListOf<M>()
    private val flow = MutableStateFlow<List<M>>(emptyList())

    override suspend fun getById(id: ID): M? =
        mutex.withLock { items.find { idSelector(it) == id } }

    override fun getByIdFlow(id: ID): Flow<M?> =
        flow.map { list -> list.find { idSelector(it) == id } }

    override suspend fun getAll(): List<M> =
        mutex.withLock { items.toList() }

    override fun getAllFlow(): Flow<List<M>> = flow

    override suspend fun upsert(model: M) = mutex.withLock {
        val idx = items.indexOfFirst { idSelector(it) == idSelector(model) }
        if (idx >= 0) items[idx] = model else items.add(model)
        flow.value = items.toList()
    }

    override suspend fun delete(model: M) =
        deleteById(idSelector(model))

    override suspend fun deleteById(id: ID) = mutex.withLock {
        items.removeAll { idSelector(it) == id }
        flow.value = items.toList()
    }

    override suspend fun deleteAll() = mutex.withLock {
        items.clear()
        flow.value = emptyList()
    }
}
