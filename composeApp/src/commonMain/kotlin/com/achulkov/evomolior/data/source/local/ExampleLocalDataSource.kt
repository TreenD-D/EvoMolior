package com.achulkov.evomolior.data.source.local

import com.achulkov.evomolior.domain.model.ExampleModel
import kotlinx.coroutines.flow.Flow

interface ExampleLocalDataSource: LocalDataSource<String, ExampleModel>