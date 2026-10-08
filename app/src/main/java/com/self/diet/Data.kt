package com.self.diet

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.OffsetDateTime
import java.time.LocalDate
import java.util.UUID

object Kinds {
    const val WEIGHT = "weight"
    const val MEAL = "meal"
    const val EXERCISE = "exercise"
    fun label(kind: String) = when (kind) { WEIGHT -> "体重"; MEAL -> "饮食"; else -> "运动" }
    fun unit(kind: String) = when (kind) { WEIGHT -> "kg"; MEAL -> "g"; else -> "分钟" }
}

@Entity(tableName = "records", indices = [Index("date")])
data class Record(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val occurredAt: String,
    val date: String,
    val name: String = "",
    val amount: Double? = null,
    val calories: Double? = null,
    val met: Double? = null,
    val photos: String = "",
    val note: String = "",
    val createdAt: String = OffsetDateTime.now().toString(),
    val updatedAt: String = OffsetDateTime.now().toString()
) {
    fun photoNames() = photos.split(';').filter { it.isNotBlank() }
    fun validate() {
        require(kind in listOf(Kinds.WEIGHT, Kinds.MEAL, Kinds.EXERCISE)) { "记录类型无效" }
        val time = OffsetDateTime.parse(occurredAt)
        require(time.toLocalDate() == LocalDate.parse(date)) { "日期和记录时间不一致" }
        require(amount == null || amount.isFinite() && amount > 0) { "重量或时长必须大于 0" }
        require(calories == null || calories.isFinite() && calories >= 0) { "热量须为不小于 0 的数字" }
        require(met == null || met.isFinite() && met > 0) { "MET 必须大于 0" }
        require(kind != Kinds.WEIGHT || amount != null) { "请填写体重" }
        require(kind == Kinds.WEIGHT || name.isNotBlank()) { "请填写名称" }
        require(kind != Kinds.EXERCISE || amount != null) { "请填写运动时长" }
        require(photoNames().all { it.matches(Regex("[a-f0-9-]+\\.jpg")) }) { "照片路径无效" }
    }
}

@Dao
interface RecordDao {
    @Query("SELECT * FROM records ORDER BY occurredAt DESC, id") fun observe(): Flow<List<Record>>
    @Query("SELECT * FROM records ORDER BY occurredAt, id") suspend fun all(): List<Record>
    @Upsert suspend fun save(record: Record)
    @Query("DELETE FROM records WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM analysis_snapshots ORDER BY generatedAt DESC") fun observeSnapshots(): Flow<List<AnalysisSnapshot>>
    @Query("SELECT * FROM analysis_snapshots ORDER BY generatedAt") suspend fun snapshots(): List<AnalysisSnapshot>
    @Insert suspend fun saveSnapshot(snapshot: AnalysisSnapshot)
}

@Database(entities = [Record::class, AnalysisSnapshot::class], version = 1, exportSchema = true)
abstract class DietDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    companion object {
        @Volatile private var instance: DietDatabase? = null
        fun get(context: Context): DietDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, DietDatabase::class.java, "diet.db").build().also { instance = it }
        }
    }
}
