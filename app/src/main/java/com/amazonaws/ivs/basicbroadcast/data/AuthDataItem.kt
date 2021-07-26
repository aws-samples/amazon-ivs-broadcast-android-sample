package com.amazonaws.ivs.basicbroadcast.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "auth_table")
data class AuthDataItem(
    @PrimaryKey
    val pos: Int = 0,
    val key: String,
    val endpoint: String
)
