package io.github.kimgeonwoo718.roki.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ConversationEntity::class, TurnEntity::class], version = 1, exportSchema = true)
abstract class RokiDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        fun create(context: Context): RokiDatabase =
            Room.databaseBuilder(context.applicationContext, RokiDatabase::class.java, "roki.db").build()
    }
}
