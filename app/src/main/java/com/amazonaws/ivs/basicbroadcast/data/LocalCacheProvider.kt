package com.amazonaws.ivs.basicbroadcast.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [AuthDataItem::class], version = 1, exportSchema = false)
abstract class LocalCacheProvider : RoomDatabase() {

    abstract fun authDao(): AuthDao
}
