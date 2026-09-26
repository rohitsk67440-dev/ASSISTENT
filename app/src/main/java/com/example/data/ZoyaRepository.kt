package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class ZoyaRepository(context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val memoryDao = database.memoryDao()
    private val routineDao = database.routineDao()
    private val noteDao = database.noteDao()

    val allMemories: Flow<List<MemoryEntity>> = memoryDao.getAllMemories()
    val allRoutines: Flow<List<RoutineEntity>> = routineDao.getAllRoutines()
    val allNotes: Flow<List<NoteEntity>> = noteDao.getAllNotes()

    // Memories
    suspend fun saveMemory(key: String, value: String, category: String = "general"): Long {
        return memoryDao.insertMemory(
            MemoryEntity(
                key = key.trim(),
                value = value.trim(),
                category = category
            )
        )
    }

    suspend fun getMemoryByKey(key: String): MemoryEntity? {
        return memoryDao.getMemoryByKey(key.trim())
    }

    suspend fun searchMemories(query: String): List<MemoryEntity> {
        return memoryDao.searchMemories(query.trim())
    }

    suspend fun deleteMemoryById(id: Long) {
        memoryDao.deleteById(id)
    }

    suspend fun deleteMemoryByKey(key: String): Int {
        return memoryDao.deleteByKey(key.trim())
    }

    suspend fun clearAllMemories() {
        memoryDao.clearAll()
    }

    // Routines
    suspend fun saveRoutine(name: String, triggerPhrase: String, stepsJson: String, description: String = ""): Long {
        return routineDao.insertRoutine(
            RoutineEntity(
                name = name.trim(),
                triggerPhrase = triggerPhrase.trim(),
                stepsJson = stepsJson,
                description = description
            )
        )
    }

    suspend fun getRoutine(nameOrTrigger: String): RoutineEntity? {
        return routineDao.getRoutineByName(nameOrTrigger.trim())
    }

    suspend fun getEnabledRoutines(): List<RoutineEntity> {
        return routineDao.getEnabledRoutines()
    }

    suspend fun deleteRoutineById(id: Long) {
        routineDao.deleteById(id)
    }

    // Notes
    suspend fun saveNote(title: String, content: String): Long {
        return noteDao.insertNote(
            NoteEntity(
                title = title.trim(),
                content = content.trim()
            )
        )
    }

    suspend fun searchNotes(query: String): List<NoteEntity> {
        return noteDao.searchNotes(query.trim())
    }

    suspend fun deleteNoteById(id: Long) {
        noteDao.deleteById(id)
    }

    suspend fun seedDefaultsIfEmpty() {
        val existing = routineDao.getRoutineByName("Gaming Mode")
        if (existing == null) {
            routineDao.insertRoutine(
                RoutineEntity(
                    name = "Gaming Mode",
                    triggerPhrase = "activate gaming mode",
                    stepsJson = """[{"name":"setVolumePercent","args":{"percent":75}},{"name":"setBrightness","args":{"level":80}},{"name":"playMedia","args":{"query":"game sound"}}]""",
                    description = "Sets volume to 75%, brightness to 80%, and optimizes audio."
                )
            )
            routineDao.insertRoutine(
                RoutineEntity(
                    name = "Work Mode",
                    triggerPhrase = "start work mode",
                    stepsJson = """[{"name":"adjustVolume","args":{"direction":"mute"}},{"name":"setBrightness","args":{"level":60}}]""",
                    description = "Mutes volume, sets gentle screen brightness."
                )
            )
            routineDao.insertRoutine(
                RoutineEntity(
                    name = "Night Mode",
                    triggerPhrase = "night routine",
                    stepsJson = """[{"name":"adjustVolume","args":{"direction":"mute"}},{"name":"setBrightness","args":{"level":10}},{"name":"toggleTorch","args":{"state":"off"}}]""",
                    description = "Mutes volume, turns off torch, dims screen for sleep."
                )
            )
        }
    }
}
