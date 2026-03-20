package com.achulkov.evomolior.data.source.local.impl

import com.achulkov.evomolior.data.source.local.ExampleLocalDataSource
import com.achulkov.evomolior.domain.model.ExampleModel

class InMemoryExampleLocalDataSource :
    BaseInMemoryLocalDataSource<String, ExampleModel>({ it.id }), ExampleLocalDataSource
