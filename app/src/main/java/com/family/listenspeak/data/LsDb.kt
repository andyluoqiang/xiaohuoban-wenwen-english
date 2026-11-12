package com.family.listenspeak.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

@Dao
interface ChildDao {
    @Query("SELECT * FROM Child ORDER BY id")
    fun all(): Flow<List<Child>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: Child): Long
    @Query("DELETE FROM Child WHERE id=:id")
    suspend fun delete(id: Long)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM Progress WHERE childId=:childId")
    fun forChild(childId: Long): Flow<List<Progress>>
    @Query("SELECT * FROM Progress WHERE `key`=:key")
    suspend fun byKey(key: String): Progress?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: Progress)
}

@Dao
interface AttemptDao {
    @Insert
    suspend fun insert(a: Attempt): Long
    @Query("SELECT COUNT(*) FROM Attempt WHERE childId=:childId AND cardId=:cardId AND kind='LISTEN' AND firstTryCorrect=1")
    suspend fun firstTryOkCount(childId: Long, cardId: String): Int
    @Query("SELECT DISTINCT cardId FROM Attempt WHERE childId=:childId AND kind='LISTEN' AND firstTryCorrect=1")
    suspend fun passedCardIds(childId: Long): List<String>
}

@Dao
interface ReviewDao {
    @Query("SELECT * FROM ReviewItem WHERE childId=:childId")
    suspend fun forChild(childId: Long): List<ReviewItem>
    @Query("SELECT * FROM ReviewItem WHERE childId=:childId AND nextDueTs<=:now AND intervalStep<4 ORDER BY priority DESC LIMIT :limit")
    suspend fun due(childId: Long, now: Long, limit: Int): List<ReviewItem>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: ReviewItem)
    @Query("SELECT COUNT(*) FROM ReviewItem WHERE childId=:childId AND intervalStep<4")
    suspend fun activeCount(childId: Long): Int
    @Query("SELECT * FROM ReviewItem WHERE `key`=:key")
    suspend fun byKey(key: String): ReviewItem?
}

@Dao
interface EvalQueueDao {
    @Query("SELECT * FROM EvalQueue WHERE status='PENDING'")
    suspend fun pending(): List<EvalQueue>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: EvalQueue)
    @Query("UPDATE EvalQueue SET status=:s, resultJson=:r WHERE id=:id")
    suspend fun mark(id: Long, s: String, r: String?)
}

@Dao
interface AwardDao {
    @Insert
    suspend fun insert(a: Award)
    @Query("SELECT COUNT(*) FROM Award WHERE childId=:childId")
    suspend fun countFor(childId: Long): Int
}

@Dao
interface ParentTaskDao {
    @Query("SELECT * FROM ParentTask WHERE childId=:childId AND date=:date LIMIT 1")
    suspend fun today(childId: Long, date: String): ParentTask?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: ParentTask)
    @Query("UPDATE ParentTask SET done=1, doneTs=:ts WHERE id=:id")
    suspend fun markDone(id: Long, ts: Long)
}

@Dao
interface UserDao {
    @Query("SELECT * FROM UserAccount WHERE username=:u")
    suspend fun byName(u: String): UserAccount?
    @Query("SELECT COUNT(*) FROM UserAccount")
    suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(u: UserAccount)
    @Query("UPDATE UserAccount SET totalStars=:s WHERE username=:u")
    suspend fun setStars(u: String, s: Int)
}

@Dao
interface LessonStatDao {
    @Query("SELECT * FROM LessonStat WHERE childId=:childId AND lessonId=:lessonId")
    suspend fun get(childId: Long, lessonId: String): LessonStat?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: LessonStat)
    @Query("SELECT * FROM LessonStat WHERE childId=:childId")
    suspend fun forChild(childId: Long): List<LessonStat>
}

@Database(
    entities = [Child::class, Progress::class, Attempt::class,
                ReviewItem::class, EvalQueue::class, Award::class, ParentTask::class,
                UserAccount::class, LessonStat::class],
    version = 2, exportSchema = false
)
abstract class LsDb : RoomDatabase() {
    abstract fun childDao(): ChildDao
    abstract fun progressDao(): ProgressDao
    abstract fun attemptDao(): AttemptDao
    abstract fun reviewDao(): ReviewDao
    abstract fun evalQueueDao(): EvalQueueDao
    abstract fun awardDao(): AwardDao
    abstract fun parentTaskDao(): ParentTaskDao
    abstract fun userDao(): UserDao
    abstract fun lessonStatDao(): LessonStatDao

    companion object {
        @Volatile private var inst: LsDb? = null
        fun get(ctx: Context): LsDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, LsDb::class.java, "ls.db")
                // v1→v2 仅新增 UserAccount/LessonStat 两表，显式迁移保住旧学习进度
                .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `UserAccount` (`username` TEXT NOT NULL, `childId` INTEGER NOT NULL, `serverUserId` INTEGER NOT NULL, `totalStars` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`username`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `LessonStat` (`key` TEXT NOT NULL, `childId` INTEGER NOT NULL, `lessonId` TEXT NOT NULL, `learnCount` INTEGER NOT NULL DEFAULT 0, `lastTs` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                    }
                })
                .build().also { inst = it }
        }
    }
}
