package com.moyue.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 应用数据库。
 *
 * v1 共 4 张表；后续增加字段时必须提供 Migration，禁止在生产环境使用
 * fallbackToDestructiveMigration，以免丢失用户书籍与进度。
 */
@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ChapterContentEntity::class,
        ReadingProgressEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        const val DATABASE_NAME = "moyue.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME,
            ).build()
    }
}
