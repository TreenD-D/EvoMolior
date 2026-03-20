package com.achulkov.evomolior.data.source.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.achulkov.evomolior.data.source.local.entity.ExampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExampleDao: BaseRoomDao<String, ExampleEntity> {
    @Query("SELECT * FROM example WHERE id = :id")
    override suspend fun getById(id: String): ExampleEntity?

    @Query("SELECT * FROM example WHERE id = :id")
    override fun getByIdFlow(id: String): Flow<ExampleEntity?>

    @Query("SELECT * FROM example")
    override fun getAllFlow(): Flow<List<ExampleEntity>>

    @Query("SELECT * FROM example")
    override suspend fun getAll(): List<ExampleEntity>

    //Insert or update if exists
    @Upsert
    override suspend fun upsert(entity: ExampleEntity)

    @Query("DELETE FROM example WHERE id = :id")
    override suspend fun deleteById(id: String)

    @Delete
    override suspend fun delete(entity: ExampleEntity)

    @Query("DELETE FROM example")
    override suspend fun deleteAll()
}