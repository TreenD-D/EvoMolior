package com.achulkov.evomolior.data.source.local.entity

interface EntityMapper<Entity, Model> {
    fun toEntity(model: Model): Entity
    fun toModel(entity: Entity): Model
}
