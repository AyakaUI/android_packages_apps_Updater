/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pixelos.ota.data.source.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for the Updater application.
 *
 * Use [UpdatesLocalDataSource] to interact with data.
 */
@Database(entities = [UpdateEntity::class], version = 4, exportSchema = true)
abstract class UpdatesDatabase : RoomDatabase() {
    abstract fun updateDao(): UpdateDao

    companion object {
        @Volatile
        private var instance: UpdatesDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // PixelOS's legacy version-1 database predates Lineage's `type` column.
                // Inspect the source table so both layouts can upgrade in place.
                val typeExpression = if (db.hasColumn("updates", "type")) {
                    "`type`"
                } else {
                    "NULL"
                }
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `updates_new` (
                        `download_id` TEXT NOT NULL,
                        `download_url` TEXT,
                        `name` TEXT NOT NULL,
                        `path` TEXT,
                        `size` INTEGER NOT NULL,
                        `status` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `type` TEXT,
                        `version` TEXT NOT NULL,
                        PRIMARY KEY(`download_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `updates_new` (`download_id`, `status`, `path`,
                        `timestamp`, `type`, `version`, `size`, `name`)
                    SELECT `download_id`, IFNULL(`status`, 0), `path`,
                        IFNULL(`timestamp`, 0), $typeExpression, IFNULL(`version`, ''),
                        IFNULL(`size`, 0),
                        COALESCE(REPLACE(`path`, RTRIM(`path`, REPLACE(`path`, '/', '')), ''),
                            `download_id`)
                    FROM `updates`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `updates`")
                db.execSQL("ALTER TABLE `updates_new` RENAME TO `updates`")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `updates` ADD COLUMN `os_sdk_level` INTEGER")
                db.execSQL("ALTER TABLE `updates` ADD COLUMN `os_patch_level` TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `updates` ADD COLUMN `payload_metadata_offset` INTEGER")
                db.execSQL("ALTER TABLE `updates` ADD COLUMN `payload_metadata_size` INTEGER")
                db.execSQL("ALTER TABLE `updates` ADD COLUMN `payload_offset` INTEGER")
                db.execSQL("ALTER TABLE `updates` ADD COLUMN `payload_size` INTEGER")
                db.execSQL("ALTER TABLE `updates` ADD COLUMN `payload_properties_offset` INTEGER")
                db.execSQL("ALTER TABLE `updates` ADD COLUMN `payload_properties_size` INTEGER")
            }
        }

        @JvmStatic
        fun getInstance(context: Context): UpdatesDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                UpdatesDatabase::class.java,
                "updates.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }

        private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
            query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return@use true
                }
                false
            }
    }
}
