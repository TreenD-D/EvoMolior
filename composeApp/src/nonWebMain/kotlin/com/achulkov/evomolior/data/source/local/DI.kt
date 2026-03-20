package com.achulkov.evomolior.data.source.local

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.achulkov.evomolior.data.source.local.impl.CreditTransactionLocalDataSourceImpl
import com.achulkov.evomolior.data.source.local.impl.ExampleLocalDataSourceImpl
import com.achulkov.evomolior.util.defaultAsyncDispatcher
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

private val localDataSourceModule = module {
    //Local Source
    single<AppDatabase> {
        val databaseProvider = get<DatabaseProvider>()
        databaseProvider.provideAppDatabaseBuilder()
            .fallbackToDestructiveMigration(dropAllTables = true)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(defaultAsyncDispatcher)
            .build()
    }

    //Local DAOs
    single { get<AppDatabase>().exampleDao() }
    single { get<AppDatabase>().creditTransactionDao() }

    // Impl
    single { ExampleLocalDataSourceImpl(get()) } bind ExampleLocalDataSource::class
    single { CreditTransactionLocalDataSourceImpl(get()) } bind CreditTransactionLocalDataSource::class
}
val nonWebModule = module {
    includes(localDataSourceModule)
}

