package com.achulkov.evomolior.data.source.local.impl

import com.achulkov.evomolior.data.source.local.ExampleLocalDataSource
import com.achulkov.evomolior.data.source.local.dao.ExampleDao
import com.achulkov.evomolior.data.source.local.entity.ExampleEntity
import com.achulkov.evomolior.data.source.local.entity.ExampleEntityMapper
import com.achulkov.evomolior.domain.model.ExampleModel

class ExampleLocalDataSourceImpl(
    exampleDao: ExampleDao,
    exampleEntityMapper: ExampleEntityMapper = ExampleEntityMapper()
) : BaseRoomLocalDataSource<String, ExampleEntity, ExampleModel>(
    dao = exampleDao,
    mapper = exampleEntityMapper
), ExampleLocalDataSource