package dev.jskw.counter.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.jskw.counter.data.model.Counter

@Database(entities = [Counter::class], version = 3, exportSchema = false)
abstract class CounterDatabase : RoomDatabase() {
    abstract fun counterDao(): CounterDao

    companion object {
        @Volatile
        private var INSTANCE: CounterDatabase? = null

        fun getInstance(context: Context): CounterDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CounterDatabase::class.java,
                    "counter_database"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
