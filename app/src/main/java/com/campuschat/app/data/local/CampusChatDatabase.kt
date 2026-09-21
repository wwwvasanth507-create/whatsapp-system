package com.campuschat.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.campuschat.app.data.local.dao.ConversationDao
import com.campuschat.app.data.local.dao.MessageDao
import com.campuschat.app.data.local.entity.ConversationEntity
import com.campuschat.app.data.local.entity.MessageEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class CampusChatDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: CampusChatDatabase? = null

        fun getInstance(context: Context): CampusChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CampusChatDatabase::class.java,
                    "campuschat_local_db.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
