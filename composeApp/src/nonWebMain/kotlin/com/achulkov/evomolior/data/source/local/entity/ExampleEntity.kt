@file:OptIn(ExperimentalUuidApi::class)

package com.achulkov.evomolior.data.source.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.achulkov.evomolior.domain.model.ExampleModel
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


@Entity(tableName = "example")
data class ExampleEntity(
    @PrimaryKey @ColumnInfo("id") val id: String = Uuid.random().toString(),
    @ColumnInfo("title") val title: String? = null,
)

class ExampleEntityMapper : EntityMapper<ExampleEntity, ExampleModel> {
    override fun toEntity(model: ExampleModel): ExampleEntity {
        return ExampleEntity(
            id = model.id,
            title = model.title,
        )
    }

    override fun toModel(entity: ExampleEntity): ExampleModel {
        return ExampleModel(
            id = entity.id,
            title = entity.title,
        )
    }
}
