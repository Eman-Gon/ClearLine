package com.clearline.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.clearline.core.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMigrationTest {
    @Test fun migrationFromV1PreservesProfilesAndAddsDurableDeletionTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val profile = LocalProfile(ProfileId.new(), "Synthetic migration fixture", DataOrigin.SYNTHETIC, 1)
        // Materialize the unchanged v1 tables, then remove the two tables introduced in v2.
        RoomWorkflowStore.open(context, name).use { it.putProfile(profile) }
        val file = File(context.noBackupFilesDir, "database/$name")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE deletion_tombstones")
            db.execSQL("DROP TABLE file_cleanup")
            db.version = 1
        }
        RoomWorkflowStore.open(context, name).use { migrated ->
            assertEquals(profile, migrated.getProfile(profile.profileId))
            migrated.deleteProfile(profile.profileId, 2)
            assertNull(migrated.getProfile(profile.profileId))
            assertTrue(migrated.pendingFileCleanup().isEmpty())
        }
        file.delete()
        Unit
    }
}
