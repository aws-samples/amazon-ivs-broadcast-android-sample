package com.amazonaws.ivs.basicbroadcast.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuthDao {

    @Query("SELECT * FROM auth_table WHERE pos = 0")
    fun getAuth(): Flow<AuthDataItem?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(authItem: AuthDataItem)
}
