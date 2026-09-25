package com.clearline.storage

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profiles") data class ProfileRow(@PrimaryKey val profileId: String, val createdAtMs: Long, val json: String)
@Entity(tableName = "sessions", indices = [Index("profileId"), Index("phase")]) data class SessionRow(@PrimaryKey val sessionId: String, val profileId: String, val phase: String, val inputRevision: Long, val stateVersion: Long, val createdAtMs: Long, val json: String)
@Entity(tableName = "clips", indices = [Index(value = ["sessionId", "sha256"], unique = true)]) data class ClipRow(@PrimaryKey val clipId: String, val sessionId: String, val sha256: String, val privatePath: String, val json: String)
@Entity(tableName = "jobs", indices = [Index(value = ["sessionId", "scope", "scopeRevision", "kind", "clipKey"], unique = true), Index("status")]) data class JobRow(@PrimaryKey val jobId: String, val sessionId: String, val scope: String, val scopeRevision: Long, val kind: String, val clipKey: String, val status: String, val createdAtMs: Long, val json: String)
@Entity(tableName = "actions", indices = [Index(value = ["sessionId", "scope", "scopeRevision", "ordinal"], unique = true), Index("jobId")]) data class ActionRow(@PrimaryKey val actionId: String, val jobId: String, val sessionId: String, val scope: String, val scopeRevision: Long, val ordinal: Int, val status: String, val json: String)
@Entity(tableName = "checkpoints") data class CheckpointRow(@PrimaryKey val sessionId: String, val json: String)
@Entity(tableName = "events", indices = [Index("sessionId")]) data class EventRow(@PrimaryKey val eventId: String, val sessionId: String, val type: String, val createdAtMs: Long)
@Entity(tableName = "session_summaries", primaryKeys = ["sessionId", "version"], indices = [Index("profileId")]) data class SummaryRow(val sessionId: String, val version: Int, val profileId: String, val completedAtMs: Long, val json: String)
@Entity(tableName = "source_versions", primaryKeys = ["evidenceId", "version"], indices = [Index("sessionId")]) data class SourceRow(val evidenceId: String, val version: Int, val sessionId: String, val json: String)
@Entity(tableName = "consent_records", primaryKeys = ["sessionId", "revision"]) data class ConsentRow(val sessionId: String, val revision: Long, val json: String)
@Entity(tableName = "outbox", indices = [Index("sessionId"), Index("status")]) data class OutboxRow(@PrimaryKey val exportId: String, val sessionId: String, val profileId: String, val status: String, val claimToken: String?, val attempt: Int, val createdAtMs: Long, val json: String, val lastError: String? = null)
@Entity(tableName = "deletion_tombstones", primaryKeys = ["kind", "identity"]) data class TombstoneRow(val kind: String, val identity: String, val deletedAtMs: Long)
@Entity(tableName = "file_cleanup") data class CleanupRow(@PrimaryKey val privatePath: String)

@Dao abstract class WorkflowDao {
    @Upsert abstract suspend fun profile(row: ProfileRow)
    @Query("SELECT * FROM profiles WHERE profileId=:id") abstract suspend fun profile(id: String): ProfileRow?
    @Query("SELECT * FROM profiles ORDER BY createdAtMs") abstract fun profiles(): Flow<List<ProfileRow>>
    @Query("DELETE FROM profiles WHERE profileId=:id") abstract suspend fun deleteProfile(id: String)
    @Upsert abstract suspend fun session(row: SessionRow)
    @Query("SELECT * FROM sessions WHERE sessionId=:id") abstract suspend fun session(id: String): SessionRow?
    @Query("SELECT * FROM sessions WHERE sessionId=:id") abstract fun observeSession(id: String): Flow<SessionRow?>
    @Query("SELECT * FROM sessions WHERE profileId=:id ORDER BY createdAtMs DESC") abstract fun observeSessions(id: String): Flow<List<SessionRow>>
    @Query("SELECT * FROM sessions WHERE profileId=:id") abstract suspend fun sessions(id: String): List<SessionRow>
    @Query("SELECT * FROM sessions") abstract suspend fun sessions(): List<SessionRow>
    @Query("DELETE FROM sessions WHERE sessionId=:id") abstract suspend fun deleteSession(id: String)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertClip(row: ClipRow)
    @Update abstract suspend fun updateClip(row: ClipRow)
    @Query("SELECT * FROM clips WHERE clipId=:id") abstract suspend fun clip(id: String): ClipRow?
    @Query("SELECT * FROM clips WHERE sessionId=:id") abstract suspend fun clips(id: String): List<ClipRow>
    @Query("DELETE FROM clips WHERE sessionId=:id") abstract suspend fun deleteClips(id: String)
    @Upsert abstract suspend fun job(row: JobRow)
    @Query("SELECT * FROM jobs WHERE jobId=:id") abstract suspend fun job(id: String): JobRow?
    @Query("SELECT * FROM jobs WHERE sessionId=:id ORDER BY createdAtMs, jobId") abstract suspend fun jobs(id: String): List<JobRow>
    @Query("DELETE FROM jobs WHERE sessionId=:id") abstract suspend fun deleteJobs(id: String)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertAction(row: ActionRow)
    @Update abstract suspend fun updateAction(row: ActionRow)
    @Query("SELECT * FROM actions WHERE actionId=:id") abstract suspend fun action(id: String): ActionRow?
    @Query("SELECT * FROM actions WHERE sessionId=:id ORDER BY scopeRevision, ordinal") abstract suspend fun actions(id: String): List<ActionRow>
    @Query("DELETE FROM actions WHERE sessionId=:id") abstract suspend fun deleteActions(id: String)
    @Upsert abstract suspend fun checkpoint(row: CheckpointRow)
    @Query("SELECT * FROM checkpoints WHERE sessionId=:id") abstract suspend fun checkpoint(id: String): CheckpointRow?
    @Query("DELETE FROM checkpoints WHERE sessionId=:id") abstract suspend fun deleteCheckpoints(id: String)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun event(row: EventRow)
    @Query("DELETE FROM events WHERE sessionId=:id") abstract suspend fun deleteEvents(id: String)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun summary(row: SummaryRow)
    @Query("SELECT * FROM session_summaries WHERE sessionId=:id ORDER BY version DESC LIMIT 1") abstract suspend fun summary(id: String): SummaryRow?
    @Query("SELECT * FROM session_summaries WHERE profileId=:id ORDER BY completedAtMs DESC, version DESC") abstract suspend fun summaries(id: String): List<SummaryRow>
    @Query("SELECT * FROM session_summaries WHERE profileId=:id ORDER BY completedAtMs DESC, version DESC") abstract fun observeSummaries(id: String): Flow<List<SummaryRow>>
    @Query("DELETE FROM session_summaries WHERE sessionId=:id") abstract suspend fun deleteSummaries(id: String)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun source(row: SourceRow)
    @Query("SELECT * FROM source_versions WHERE evidenceId=:id AND version=:version") abstract suspend fun source(id: String, version: Int): SourceRow?
    @Query("DELETE FROM source_versions WHERE sessionId=:id") abstract suspend fun deleteSources(id: String)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun consent(row: ConsentRow)
    @Query("SELECT * FROM consent_records WHERE sessionId=:id AND revision=:revision") abstract suspend fun consent(id: String, revision: Long): ConsentRow?
    @Query("DELETE FROM consent_records WHERE sessionId=:id") abstract suspend fun deleteConsents(id: String)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertOutbox(row: OutboxRow)
    @Update abstract suspend fun updateOutbox(row: OutboxRow)
    @Query("SELECT * FROM outbox WHERE exportId=:id") abstract suspend fun outbox(id: String): OutboxRow?
    @Query("SELECT * FROM outbox WHERE sessionId=:id") abstract suspend fun outboxForSession(id: String): List<OutboxRow>
    @Query("SELECT * FROM outbox WHERE status='PENDING' ORDER BY createdAtMs,exportId") abstract suspend fun pendingOutbox(): List<OutboxRow>
    @Query("SELECT * FROM outbox WHERE status='CLAIMED'") abstract suspend fun claimedOutbox(): List<OutboxRow>
    @Query("DELETE FROM outbox WHERE sessionId=:id") abstract suspend fun deleteOutbox(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun tombstone(row: TombstoneRow)
    @Query("SELECT COUNT(*) FROM deletion_tombstones WHERE kind=:kind AND identity=:id") abstract suspend fun tombstoned(kind: String, id: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun cleanup(row: CleanupRow)
    @Query("SELECT privatePath FROM file_cleanup") abstract suspend fun cleanupPaths(): List<String>
    @Query("DELETE FROM file_cleanup WHERE privatePath=:path") abstract suspend fun acknowledgeCleanup(path: String)
}

@Database(entities = [ProfileRow::class, SessionRow::class, ClipRow::class, JobRow::class, ActionRow::class, CheckpointRow::class, EventRow::class, SummaryRow::class, SourceRow::class, ConsentRow::class, OutboxRow::class, TombstoneRow::class, CleanupRow::class], version = 2, exportSchema = true)
abstract class ClearLineDatabase : RoomDatabase() {
    abstract fun workflow(): WorkflowDao
    companion object {
        /** v2 adds crash-safe deletion. Existing check-ins and action identities remain unchanged. */
        val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS deletion_tombstones (kind TEXT NOT NULL, identity TEXT NOT NULL, deletedAtMs INTEGER NOT NULL, PRIMARY KEY(kind, identity))")
            db.execSQL("CREATE TABLE IF NOT EXISTS file_cleanup (privatePath TEXT NOT NULL, PRIMARY KEY(privatePath))")
        } }
    }
}
