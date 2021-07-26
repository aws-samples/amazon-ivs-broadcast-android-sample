package com.amazonaws.ivs.basicbroadcast.injection

import androidx.room.Room
import com.amazonaws.ivs.basicbroadcast.App
import com.amazonaws.ivs.basicbroadcast.data.LocalCacheProvider
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class InjectionModule(private val context: App) {

    @Provides
    @Singleton
    fun provideLocalCacheProvider(): LocalCacheProvider =
        Room.databaseBuilder(context, LocalCacheProvider::class.java, "configuration_database").fallbackToDestructiveMigration().build()

}
