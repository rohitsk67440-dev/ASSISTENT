package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerPhrase: String,
    val stepsJson: String, // JSON array of tool actions: [{"name": "setVolumePercent", "args": {"percent": 70}}, ...]
    val description: String = "",
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY name ASC")
    fun getAllRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE isEnabled = 1")
    suspend fun getEnabledRoutines(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE LOWER(name) = LOWER(:name) OR LOWER(triggerPhrase) = LOWER(:name) LIMIT 1")
    suspend fun getRoutineByName(name: String): RoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutine(routine: RoutineEntity): Long

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM routines WHERE LOWER(name) = LOWER(:name)")
    suspend fun deleteByName(name: String): Int
}
